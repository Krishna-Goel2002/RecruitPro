"""Ownership checks (the candidate proves control of a source by publishing a challenge code).

* ``DNS_TXT``: a TXT record on the target host equal to ``recruitpro-verification=<code>``;
* ``WELL_KNOWN_FILE``: ``https://<host>/.well-known/recruitpro-verification.txt`` contains the code (https only,
  robots.txt not consulted: the candidate placed the file for us);
* ``PAGE_CODE``: the page at the target URL contains the code (raw HTML, so a ``<meta>`` tag works); the code must
  not be part of the URL (pages echo their address), and for both file and page the final URL after redirects must
  stay on the target's site (``www.`` ignored);
* ``PROFILE_BIO_CODE``: the GitHub/GitLab profile bio of ``provider:login`` contains the code.

Fetch failures answer ``proven: false`` with a reason; nothing here raises to the caller. The code must look like a
random challenge (8-64 chars of ``[A-Za-z0-9_-]``, enforced by the request model).
"""
from __future__ import annotations

import re
from datetime import datetime
from urllib.parse import unquote_plus, urlsplit

from app.core.budget import Budget, BudgetExceeded
from app.core.config import Settings
from app.evidence import make_item
from app.fetch.client import PAGE_TYPES
from app.fetch.ssrf import FetchError, parse_url
from app.models import LOGIN_PATTERN, OwnershipCheckRequest, OwnershipCheckResponse
from app.network import Network
from app.sources.common import SourceError
from app.sources.github import GitHubSource
from app.sources.gitlab import GitLabSource

TXT_PREFIX = "recruitpro-verification="
WELL_KNOWN_PATH = "/.well-known/recruitpro-verification.txt"
_HOST_RE = re.compile(r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,62}$")


def _host(target: str) -> str:
    value = target.strip()
    if "://" not in value:
        value = "https://" + value
    parts, host, _ = parse_url(value)
    if not _HOST_RE.match(host):
        raise FetchError("URL_BLOCKED", "the target must be a public domain name")
    return host


def _code_in_url(code: str, url: str) -> bool:
    """The challenge code appears in the URL, also percent-encoded (once or twice) or with ``+`` for spaces. Many pages
    echo their address (search results, 404 pages), so such a page proves nothing about who controls the site."""
    text = url
    for _ in range(3):
        if code.lower() in text.lower():
            return True
        decoded = unquote_plus(text)
        if decoded == text:
            break
        text = decoded
    return code.lower() in text.lower()


def _site(url: str) -> str:
    """Host without ``www.`` (the same-site rule of the web crawl)."""
    try:
        host = (urlsplit(url.strip()).hostname or "").lower().rstrip(".")
    except ValueError:
        return ""
    return host[4:] if host.startswith("www.") else host


def _response(request: OwnershipCheckRequest, proven: bool, detail: str, *, url: str | None = None,
              title: str = "", text: str = "", now: datetime, demo: bool = False) -> OwnershipCheckResponse:
    evidence = None
    if proven:
        evidence = make_item("ownership-1", "OWNERSHIP_PROOF", url=url, title=title, text=text, retrieved_at=now,
                             attributes={"method": request.method,
                                         **({"demoFixture": True, "synthetic": True} if demo else {})})
    return OwnershipCheckResponse(proven=proven, method=request.method, detail=detail, evidence=evidence)


def check_ownership(request: OwnershipCheckRequest, *, settings: Settings, network: Network,
                    now: datetime) -> OwnershipCheckResponse:
    code = request.code
    budget = Budget(10, settings.request_deadline_seconds)
    try:
        if request.method == "DNS_TXT":
            host = _host(request.target)
            records = network.txt_resolver(host)
            proven = any(record.strip() == TXT_PREFIX + code for record in records)
            return _response(request, proven, "TXT record found" if proven else "no matching TXT record on the domain",
                             url=None, title=f"DNS TXT records of {host}",
                             text=f"Host: {host}\n" + "\n".join(f"TXT: {record}" for record in records),
                             now=now, demo=network.demo)
        if request.method in {"WELL_KNOWN_FILE", "PAGE_CODE"}:
            if request.method == "WELL_KNOWN_FILE":
                url = f"https://{_host(request.target)}{WELL_KNOWN_PATH}"
                result = network.fetcher(settings, budget).fetch(
                    url, accept=frozenset({"text/plain"}) | PAGE_TYPES, max_bytes=64 * 1024, respect_robots=False)
            else:
                url = request.target.strip()
                if _code_in_url(code, url):
                    return _response(request, False, "the code must not be part of the URL: a page that echoes its "
                                                     "address would contain it", now=now)
                result = network.fetcher(settings, budget).fetch(url, accept=PAGE_TYPES, max_bytes=1_000_000)
            if any(_code_in_url(code, hop) for hop in result.redirects + [result.url]):
                return _response(request, False, "the page redirects to an address that contains the code", now=now)
            if _site(result.url) != _site(url):
                return _response(request, False, "the page redirects to another site", now=now)
            if not result.ok:
                return _response(request, False, f"the page answered HTTP {result.status}", now=now)
            text = result.text()
            proven = code in text
            return _response(request, proven, "code found" if proven else "the code was not found",
                             url=result.url, title="Ownership proof", text=text, now=now, demo=network.demo)
        provider, _, login = request.target.partition(":")
        provider = provider.strip().lower()
        if provider not in {"github", "gitlab"} or not re.match(LOGIN_PATTERN, login.strip()):
            return _response(request, False, "target must be github:<login> or gitlab:<login>", now=now)
        source_cls = GitHubSource if provider == "github" else GitLabSource
        source = source_cls(settings, budget, None, transport=network.api_transport)
        try:
            profile = source.profile(login.strip())
        finally:
            source.api.close()
        proven = bool(profile.bio and code in profile.bio)
        return _response(request, proven, "code found in the profile bio" if proven else "the code is not in the bio",
                         url=profile.url, title=f"{provider} profile bio",
                         text=f"Account: {profile.login}\nBio: {profile.bio or ''}", now=now, demo=network.demo)
    except FetchError as exc:
        return _response(request, False, f"check failed: {exc.code}", now=now)
    except SourceError as exc:
        return _response(request, False, f"check failed: {exc.code}", now=now)
    except BudgetExceeded:
        return _response(request, False, "check failed: BUDGET", now=now)
