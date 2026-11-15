"""HTML to text with the standard-library parser (no network, no scripts, no entity expansion beyond HTML's).

Extracts: title, visible text (block elements become line breaks; script/style/template/svg/iframe skipped), hidden
text (``hidden``, ``display:none``, ``visibility:hidden``; kept apart and only screened for injection), links,
``<meta>`` values, JSON-LD (``Person``, ``CreativeWork`` family, ``author``), meta author, bylines
(``rel=author``, ``itemprop=author``, ``class`` containing ``byline``/``author``, and "By Name" lines) and dates.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from typing import Any

MAX_JSON_LD_BYTES = 100_000
MAX_JSON_LD_BLOCKS = 10
MAX_LINKS = 500
_SKIP = {"script", "style", "noscript", "template", "svg", "iframe", "object", "embed", "canvas", "math", "select"}
_VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
_BLOCK = {"p", "div", "section", "article", "header", "footer", "main", "aside", "nav", "li", "ul", "ol", "tr", "td",
          "th", "table", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "dd", "dt", "dl", "figure",
          "figcaption", "address", "br", "hr", "title", "form", "fieldset", "details", "summary"}
_BYLINE_CLASS = re.compile(r"(?:^|[\s_-])(?:byline|author|author-name|post-author|entry-author)(?:$|[\s_-])", re.I)
_HIDDEN_STYLE = re.compile(r"display\s*:\s*none|visibility\s*:\s*hidden", re.I)
_BY_LINE = re.compile(r"^\s*(?:by|written by|posted by|author\s*:)\s+([A-Z][\w.'À-ɏ-]+"
                      r"(?:\s+[A-Z][\w.'À-ɏ-]+){0,3})\s*(?:[,|·•-].*)?$")
CREATIVE_TYPES = {"creativework", "article", "blogposting", "newsarticle", "scholarlyarticle", "softwaresourcecode",
                  "softwareapplication", "techarticle", "report", "book", "webpage", "profilepage", "aboutpage",
                  "presentationdigitaldocument", "digitaldocument", "course"}


@dataclass
class PageContent:
    title: str = ""
    text: str = ""
    hidden_text: str = ""
    links: list[str] = field(default_factory=list)
    meta: dict[str, str] = field(default_factory=dict)
    authors: list[str] = field(default_factory=list)  # authorship markers (meta, JSON-LD author, bylines)
    person_names: list[str] = field(default_factory=list)  # JSON-LD Person names (site owner markers)
    structured_lines: list[str] = field(default_factory=list)
    dates: list[str] = field(default_factory=list)


@dataclass
class _Frame:
    tag: str
    skip: bool
    hidden: bool
    byline: int | None


class _Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[_Frame] = []
        self.visible: list[str] = []
        self.hidden: list[str] = []
        self.title_parts: list[str] = []
        self.in_title = False
        self.in_json_ld = False
        self.json_ld_raw: list[str] = []
        self.links: list[str] = []
        self.meta: dict[str, str] = {}
        self.bylines: list[list[str]] = []
        self.times: list[str] = []

    @property
    def skipping(self) -> bool:
        return any(frame.skip for frame in self.stack)

    @property
    def hiding(self) -> bool:
        return any(frame.hidden for frame in self.stack)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {name.lower(): (value or "") for name, value in attrs}
        if tag in _BLOCK:
            self._emit("\n")
        if tag == "meta":
            key = (values.get("name") or values.get("property") or values.get("itemprop") or "").lower()
            if key and "content" in values and key not in self.meta:
                self.meta[key] = values["content"][:1000]
            return
        if tag == "a" and values.get("href") and len(self.links) < MAX_LINKS:
            self.links.append(values["href"])
        if tag == "time" and values.get("datetime"):
            self.times.append(values["datetime"][:40])
        if tag in _VOID:
            return
        if tag == "title":
            self.in_title = True
        is_json_ld = tag == "script" and values.get("type", "").lower() == "application/ld+json"
        if is_json_ld and len(self.json_ld_raw) < MAX_JSON_LD_BLOCKS:
            self.in_json_ld = True
            self.json_ld_raw.append("")
        byline = None
        if (values.get("rel", "").lower() == "author" or values.get("itemprop", "").lower() == "author"
                or _BYLINE_CLASS.search(values.get("class", ""))):
            self.bylines.append([])
            byline = len(self.bylines) - 1
        hidden = "hidden" in values or bool(_HIDDEN_STYLE.search(values.get("style", "")))
        self.stack.append(_Frame(tag, tag in _SKIP, hidden, byline))

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self.in_title = False
        if tag == "script":
            self.in_json_ld = False
        if tag in _BLOCK:
            self._emit("\n")
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                break

    def handle_data(self, data: str) -> None:
        if self.in_json_ld:
            if len(self.json_ld_raw[-1]) < MAX_JSON_LD_BYTES:
                self.json_ld_raw[-1] += data
            return
        if self.in_title:
            self.title_parts.append(data)
            return
        if self.skipping:
            return
        self._emit(data)
        for frame in self.stack:
            if frame.byline is not None:
                self.bylines[frame.byline].append(data)

    def _emit(self, data: str) -> None:
        (self.hidden if self.hiding else self.visible).append(data)


def _clean_lines(parts: list[str]) -> str:
    lines = [" ".join(line.split()) for line in "".join(parts).split("\n")]
    return "\n".join(line for line in lines if line)


def _types(node: dict[str, Any]) -> set[str]:
    value = node.get("@type")
    values = value if isinstance(value, list) else [value]
    return {str(item).lower() for item in values if isinstance(item, str)}


def _names(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        name = value.get("name")
        return [name] if isinstance(name, str) else []
    if isinstance(value, list):
        return [name for item in value[:20] for name in _names(item)]
    return []


def _walk_json_ld(node: Any, page: PageContent, depth: int = 0, budget: list[int] | None = None) -> None:
    budget = budget if budget is not None else [500]
    if depth > 6 or budget[0] <= 0:
        return
    budget[0] -= 1
    if isinstance(node, list):
        for item in node[:100]:
            _walk_json_ld(item, page, depth + 1, budget)
        return
    if not isinstance(node, dict):
        return
    types = _types(node)
    if "person" in types:
        for name in _names(node.get("name")):
            page.person_names.append(name)
            page.structured_lines.append(f"Person name: {name}")
        for prop in ("jobTitle", "knowsAbout", "alumniOf", "hasCredential", "worksFor", "award", "hasOccupation"):
            values = _names(node.get(prop)) or ([str(node[prop])] if isinstance(node.get(prop), (int, float)) else [])
            if values:
                page.structured_lines.append(f"Person {prop}: " + ", ".join(values))
    if types & CREATIVE_TYPES:
        label = sorted(types & CREATIVE_TYPES)[0]
        for prop in ("headline", "name"):
            if isinstance(node.get(prop), str):
                page.structured_lines.append(f"{label} {prop}: {node[prop]}")
                break
        for author in _names(node.get("author")) + _names(node.get("creator")):
            page.authors.append(author)
            page.structured_lines.append(f"{label} author: {author}")
        for prop in ("datePublished", "dateModified"):
            if isinstance(node.get(prop), str):
                page.dates.append(node[prop][:40])
                page.structured_lines.append(f"{label} {prop}: {node[prop][:40]}")
    for key, value in node.items():
        if isinstance(value, (dict, list)) and key not in {"author", "creator"}:
            _walk_json_ld(value, page, depth + 1, budget)


def extract_html(html: str) -> PageContent:
    parser = _Extractor()
    try:
        parser.feed(html)
        parser.close()
    except (AssertionError, ValueError):  # malformed markup: keep what was parsed
        pass
    page = PageContent(title=" ".join("".join(parser.title_parts).split())[:300], text=_clean_lines(parser.visible),
                       hidden_text=_clean_lines(parser.hidden), links=parser.links, meta=parser.meta)
    for raw in parser.json_ld_raw:
        try:
            _walk_json_ld(json.loads(raw), page)
        except (ValueError, RecursionError):
            continue
    for key in ("author", "article:author", "dc.creator", "citation_author"):
        value = parser.meta.get(key, "").strip()
        if value and not value.lower().startswith("http"):
            page.authors.append(value)
    for parts in parser.bylines:
        text = " ".join("".join(parts).split())
        text = re.sub(r"^(?:by|written by|posted by|author\s*:)\s+", "", text, flags=re.I)
        if 2 <= len(text) <= 80:
            page.authors.append(text)
    for line in page.text.split("\n"):
        match = _BY_LINE.match(line)
        if match:
            page.authors.append(match.group(1))
    for key in ("article:published_time", "datepublished", "date", "dc.date", "citation_publication_date"):
        if parser.meta.get(key):
            page.dates.append(parser.meta[key][:40])
    page.dates += parser.times
    page.authors = list(dict.fromkeys(author.strip() for author in page.authors if author.strip()))[:20]
    page.person_names = list(dict.fromkeys(page.person_names))[:10]
    page.dates = list(dict.fromkeys(page.dates))[:20]
    return page


def page_snapshot(page: PageContent) -> str:
    """The normalised text that is hashed and stored for a page (visible text plus extracted markers)."""
    sections = []
    if page.title:
        sections.append(f"Title: {page.title}")
    if page.text:
        sections.append(page.text)
    if page.authors:
        sections.append("Authorship markers: " + "; ".join(page.authors))
    if page.structured_lines:
        sections.append("Structured data:\n" + "\n".join(dict.fromkeys(page.structured_lines)))
    if page.dates:
        sections.append("Dates: " + ", ".join(page.dates))
    if page.hidden_text:
        sections.append("Hidden text (not displayed by the page):\n" + page.hidden_text)
    return "\n\n".join(sections)
