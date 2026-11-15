"""Web verifier: a small same-site crawl from the given URL through the SSRF-guarded fetcher.

Crawl: breadth-first from ``url`` over links on the same site (same host, ``www.`` ignored), pages most likely to
hold claims first (about, cv, resume, portfolio, projects, publications, ...), at most ``maxPages`` pages and
``maxBytes`` decoded bytes in total; HTML, plain text and PDF only; robots.txt respected; no cookies, no JavaScript.

Per page: title, visible text, JSON-LD (``Person``, ``CreativeWork`` family, ``author``), meta author, bylines and
dates (``app.fetch.html``). Hidden text is kept in the snapshot for the injection screen but never counts as a
statement.

Authorship marker: an author/byline/JSON-LD name on the page that matches ``holderName`` when the request gives one
(optional extension), otherwise the site owner's name taken from the start page's JSON-LD ``Person`` (or first
author marker).

Claim verdicts:
* ``VERIFIED``: stated on a page with a matching authorship marker (the shared cap lowers it without ownership);
* ``PARTLY_VERIFIED``: stated on a page without such a marker;
* ``CONTRADICTED``: not stated anywhere, but a page states the same item with a different year (both quotes given);
* ``NOT_FOUND`` otherwise.
"""
from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import urldefrag, urljoin, urlsplit

from app.core.budget import Budget
from app.core.config import Settings
from app.deidentify import Identities
from app.evidence import make_item
from app.extract import ExtractionError, extract_document
from app.fetch.client import PAGE_TYPES, FetchResult
from app.fetch.html import PageContent, extract_html, page_snapshot
from app.fetch.ssrf import FetchError, parse_url
from app.models import EvidenceItem, Reason, Usage, WebVerifyRequest
from app.network import Network
from app.verdicts import DraftVerdict, VerifierOutput
from app.verifiers.common import find_statement, tokens

PAGE_MAX_BYTES = 1_000_000
_SKIP_EXTENSIONS = re.compile(r"\.(?:png|jpe?g|gif|webp|svg|ico|css|js|mjs|map|zip|gz|tgz|rar|7z|mp4|webm|mp3|wav|"
                              r"woff2?|ttf|eot|xml|rss|atom|json|exe|dmg|apk|iso|docx?|pptx?|xlsx?)$", re.I)
_PRIORITY = re.compile(r"about|cv|resume|résumé|portfolio|project|work|publication|paper|talk|blog|"
                       r"certif|education|experience|skills", re.I)


@dataclass
class _Page:
    key: str
    url: str
    content: PageContent
    statement_text: str  # snapshot without the hidden-text section
    snapshot: str


def site_key(host: str) -> str:
    host = host.lower().rstrip(".")
    return host[4:] if host.startswith("www.") else host


def _same_site(url: str, site: str) -> bool:
    try:
        return site_key(urlsplit(url).hostname or "") == site
    except ValueError:
        return False


def name_matches(marker: str, name: str) -> bool:
    """Token-set match: equal, or the shorter name's tokens (>= 2) all occur in the longer one."""
    first, second = set(tokens(marker)), set(tokens(name))
    if not first or not second:
        return False
    if first == second:
        return True
    shorter, longer = sorted((first, second), key=len)
    return len(shorter) >= 2 and shorter <= longer


def _links(page: PageContent, base: str, site: str) -> list[str]:
    found = []
    for href in page.links:
        href = href.strip()
        if not href or href.lower().startswith(("mailto:", "javascript:", "tel:", "data:", "#")):
            continue
        absolute = urldefrag(urljoin(base, href))[0]
        if not absolute.lower().startswith(("http://", "https://")) or not _same_site(absolute, site):
            continue
        if _SKIP_EXTENSIONS.search(urlsplit(absolute).path):
            continue
        found.append(absolute)
    return sorted(dict.fromkeys(found), key=lambda link: 0 if _PRIORITY.search(urlsplit(link).path) else 1)


def _parse(result: FetchResult) -> PageContent:
    if result.content_type == "application/pdf":
        try:
            document = extract_document(result.body)
        except ExtractionError:
            return PageContent()
        page = PageContent(title=str(document.metadata.get("title") or ""), text=document.text)
        if document.metadata.get("author"):
            page.authors.append(str(document.metadata["author"]))
        return page
    if result.content_type == "text/plain":
        return PageContent(text=result.text())
    return extract_html(result.text())


def crawl(request: WebVerifyRequest, fetcher, now: datetime, warnings: list[str],
          demo: bool = False) -> list[_Page]:
    """Fetch the start page (errors propagate) and then same-site pages within the limits."""
    parse_url(request.url)  # raises URL_BLOCKED for bad schemes/ports before any request
    site = site_key(urlsplit(request.url.strip()).hostname or "")
    queue = [request.url.strip()]
    seen: set[str] = set()
    pages: list[_Page] = []
    used_bytes = 0
    while queue and len(pages) < request.limits.max_pages:
        url = queue.pop(0)
        if url in seen:
            continue
        seen.add(url)
        remaining = request.limits.max_bytes - used_bytes
        if remaining < 1024:
            warnings.append("BYTE_LIMIT_REACHED")
            fetcher.budget.truncated = True
            break
        try:
            result = fetcher.fetch(url, accept=PAGE_TYPES, max_bytes=min(PAGE_MAX_BYTES, remaining))
        except FetchError as exc:
            if not pages:
                raise
            if exc.code == "BUDGET":
                fetcher.budget.truncated = True
                break
            warnings.append(f"PAGE_SKIPPED:{exc.code}")
            continue
        final_url = result.url
        if not _same_site(final_url, site):
            if not pages:
                raise FetchError("OFF_SITE_REDIRECT", "the start page redirects to another site")
            warnings.append("PAGE_SKIPPED:OFF_SITE_REDIRECT")
            continue
        if not result.ok:
            if not pages:
                raise FetchError("HTTP_ERROR", f"the start page answered {result.status}")
            warnings.append(f"PAGE_SKIPPED:HTTP_{result.status}")
            continue
        used_bytes += len(result.body)
        if result.truncated:
            warnings.append("PAGE_TRUNCATED")
        content = _parse(result)
        snapshot = page_snapshot(content)
        hidden_at = snapshot.find("\n\nHidden text (not displayed by the page):")
        statement_text = snapshot if hidden_at < 0 else snapshot[:hidden_at]
        pages.append(_Page(key=f"page-{len(pages) + 1}", url=final_url, content=content,
                           statement_text=statement_text, snapshot=snapshot))
        queue.extend(link for link in _links(content, final_url, site) if link not in seen)
    return pages


def _owner_names(request: WebVerifyRequest, pages: list[_Page]) -> list[str]:
    if request.holder_name and request.holder_name.strip():
        return [request.holder_name.strip()]
    if not pages:
        return []
    start = pages[0].content
    if start.person_names:
        return [start.person_names[0]]
    counts = Counter(author for page in pages for author in page.content.authors)
    if start.authors:
        return [start.authors[0]]
    return [counts.most_common(1)[0][0]] if counts else []


def _has_marker(page: _Page, owners: list[str]) -> bool:
    markers = page.content.authors + page.content.person_names
    return any(name_matches(marker, owner) for marker in markers for owner in owners)


def build_output(request: WebVerifyRequest, pages: list[_Page], *, now: datetime, budget: Budget,
                 warnings: list[str], demo: bool = False) -> VerifierOutput:
    attributes = {"demoFixture": True, "synthetic": True} if demo else {}
    owners = _owner_names(request, pages)
    evidence: list[EvidenceItem] = []
    for page in pages:
        evidence.append(make_item(page.key, "PAGE", url=page.url, title=page.content.title or page.url,
                                  text=page.snapshot, retrieved_at=now, attributes={
                                      "authorshipMarkers": page.content.authors[:10],
                                      "personNames": page.content.person_names[:5],
                                      "authorshipMatches": _has_marker(page, owners), "dates": page.content.dates[:10],
                                      **attributes}))
    drafts = []
    for claim in request.claims:
        supported: list[tuple[_Page, str]] = []
        conflicts: list[tuple[_Page, str]] = []
        for page in pages:
            statement, conflict = find_statement(claim.value, claim.type, page.statement_text)
            if statement:
                supported.append((page, statement.line))
            elif conflict:
                conflicts.append((page, conflict.line))
        marked = [(page, line) for page, line in supported if _has_marker(page, owners)]
        if marked:
            page, line = marked[0]
            drafts.append(DraftVerdict(claim, "VERIFIED", [Reason(
                text="The claim is stated on a page of the site that carries a matching authorship marker.",
                evidence_keys=[page.key for page, _ in marked], quote=line)]))
        elif supported:
            page, line = supported[0]
            drafts.append(DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
                text="The claim is stated on the site, but no page stating it carries a matching authorship marker.",
                evidence_keys=[page.key for page, _ in supported], quote=line)]))
        elif conflicts:
            page, line = conflicts[0]
            drafts.append(DraftVerdict(claim, "CONTRADICTED", [
                Reason(text="The application states the claim as quoted.", evidence_keys=[], quote=claim.value),
                Reason(text="A page of the site states the same item with a different year.",
                       evidence_keys=[page.key for page, _ in conflicts], quote=line)]))
        else:
            drafts.append(DraftVerdict(claim, "NOT_FOUND", [Reason(
                text=f"The claim was not found on the {len(pages)} page(s) read from the site.", evidence_keys=[],
                quote=None)]))
    host = urlsplit(request.url.strip()).hostname or ""
    identities = Identities(names=[name for name in owners + ([request.holder_name] if request.holder_name else [])
                                   if name],
                            hosts=list(dict.fromkeys([host, site_key(host), "www." + site_key(host)])))
    if demo:
        warnings.append("DEMO_FIXTURES: synthetic recorded data")
    return VerifierOutput(verifier="web", evidence=evidence, drafts=drafts, ownership_proven=request.ownership_proven,
                          identities=identities, warnings=warnings,
                          usage=Usage(requests=budget.requests, truncated=budget.truncated))


def verify_web(request: WebVerifyRequest, *, settings: Settings, network: Network, now: datetime) -> VerifierOutput:
    budget = Budget(request.limits.max_pages * 3 + 5, settings.request_deadline_seconds)
    fetcher = network.fetcher(settings, budget)
    warnings: list[str] = []
    pages = crawl(request, fetcher, now, warnings, network.demo)
    return build_output(request, pages, now=now, budget=budget, warnings=warnings, demo=network.demo)
