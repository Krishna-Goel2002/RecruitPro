"""De-identification of resume text.

Applied to every evidence string the service returns and to everything an LLM ever sees:
the candidate's name (from the header block), emails, phone numbers, URLs, street addresses
and PIN/ZIP codes in the header, ages and dates of birth, personal-detail lines (gender,
marital status, nationality, religion, caste, parents' names), gendered honorifics (-> "Mx.")
and gendered pronouns (-> they/them/their).

Redactions are kept as spans over the normalised document text, so any slice of the
document can be rendered de-identified and offsets stay comparable with ``spanStart``.
"""
from __future__ import annotations

import bisect
import re
from dataclasses import dataclass

from app.pipeline.text import Line
from app.pipeline.timeline import has_title_word

EMAIL_RE = re.compile(r"(?<![\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b")
URL_RE = re.compile(
    r"(?<![\w@])(?:https?://|www\.)[^\s<>()\"'|,]+"
    r"|(?<![\w@./])(?:linkedin\.com|github\.com|gitlab\.com|behance\.net|dribbble\.com|medium\.com|"
    r"stackoverflow\.com|kaggle\.com|leetcode\.com|twitter\.com|x\.com|instagram\.com|facebook\.com|"
    r"bitbucket\.org|codepen\.io)/[^\s<>()\"'|,]*",
    re.I,
)
PHONE_RE = re.compile(r"(?<![\w+/])(?:\+\d{1,3}[\s.\-]?)?(?:\(\d{2,5}\)[\s.\-]?)?\d[\d\s.\-]{6,16}\d(?![\w/])")
_YEAR_RANGE_RE = re.compile(r"^(?:19|20)\d{2}\s*[-.\s]\s*(?:19|20)\d{2}$")
_DATE_LIKE_RE = re.compile(r"^\d{1,4}[-.\s/]\d{1,2}[-.\s/]\d{2,4}$")
_HONORIFIC_RE = re.compile(r"\b(?:Mr|Mrs|Ms|Miss|Mx|Shri|Smt|Sri|Kumari|Kum)\b\.?(?=\s+[A-Z\[])")
_PRONOUNS = {
    "he": "they", "she": "they", "him": "them", "his": "their", "her": "their", "hers": "theirs",
    "himself": "themself", "herself": "themself",
}
_PRONOUN_RE = re.compile(r"\b(?:he|she|him|his|her|hers|himself|herself)\b", re.I)
_PERSONAL_RE = re.compile(
    r"\b(?:gender|sex|marital\s+status|nationality|religion|caste|category|father'?s\s+name|mother'?s\s+name|"
    r"husband'?s\s+name|spouse(?:'?s\s+name)?|passport\s+(?:no|number)|aadha?ar(?:\s+(?:no|number))?|pan\s+(?:no|number))"
    r"\s*[:\-]\s*[^|;\n]{1,60}",
    re.I,
)
_DOB_RE = re.compile(r"\b(?:date\s+of\s+birth|d\.?\s?o\.?\s?b\.?|born(?:\s+on)?|birth\s*date)\s*[:\-]?\s*[^|;\n]{4,40}", re.I)
_AGE_RE = re.compile(r"\bage\s*[:\-]\s*\d{2}\b(?:\s*(?:years|yrs))?|\b\d{2}\s*(?:years|yrs)[\s-]*old\b|\baged\s+\d{2}\b", re.I)
_ADDRESS_LABEL_RE = re.compile(
    r"\b(?:address|residential\s+address|permanent\s+address|current\s+address|correspondence\s+address)\s*[:\-]\s*[^|\n]{3,120}",
    re.I,
)
_STREET_RE = re.compile(
    r"\b\d{1,5}[A-Za-z]?(?:/\d+)?,?\s+(?:[A-Z][\w.'\-]*\s+){0,4}"
    r"(?:Street|St|Road|Rd|Avenue|Ave|Lane|Ln|Boulevard|Blvd|Drive|Nagar|Colony|Marg|Sector|Block|Layout|Enclave|Vihar)\b\.?"
    r"(?:,?\s*[A-Z][\w.'\-]*(?:\s+[A-Z][\w.'\-]*){0,2}){0,3}(?:[\s,\-]*\d{5,6})?"
)
_POSTCODE_RE = re.compile(r"(?<![\d\-/])\b\d{6}\b(?![\-/]\d)|(?<![\d\-/])\b\d{5}(?:-\d{4})?\b(?![\-/]\d)")
_NAME_LABEL_RE = re.compile(r"^\s*(?:full\s+)?name\s*[:\-]\s*(?P<name>[A-Za-z][A-Za-z .'\-]{1,60})$", re.I)
_NOT_NAME_WORDS = {
    "resume", "curriculum", "vitae", "cv", "profile", "contact", "summary", "objective", "phone", "email", "mobile",
    "address", "linkedin", "github", "portfolio", "india", "remote",
}
_HONORIFIC_WORDS = {"mr", "mrs", "ms", "miss", "mx", "dr", "shri", "smt", "sri", "kumari", "kum", "prof"}


@dataclass(frozen=True)
class Redaction:
    start: int
    end: int
    replacement: str
    kind: str


@dataclass(frozen=True)
class Contact:
    kind: str  # EMAIL | PHONE | LINK
    value: str
    normalized: str
    start: int
    end: int


def is_phone(candidate: str) -> bool:
    compact = candidate.strip()
    digits = re.sub(r"\D", "", compact)
    if not 8 <= len(digits) <= 15:
        return False
    return not (_YEAR_RANGE_RE.match(compact) or _DATE_LIKE_RE.match(compact))


def _contact_occurrences(text: str) -> list[Contact]:
    """Every email, link and phone occurrence, in that precedence (a URL inside an email is the email)."""
    found: list[Contact] = []

    def free(start: int, end: int) -> bool:
        return not any(item.start < end and start < item.end for item in found)

    for match in EMAIL_RE.finditer(text):
        found.append(Contact("EMAIL", match.group(0), match.group(0).lower(), *match.span()))
    for match in URL_RE.finditer(text):
        url = match.group(0).rstrip(".;:")
        if free(match.start(), match.start() + len(url)):
            normalized = re.sub(r"^(?:https?://)?(?:www\.)?", "", url.lower())
            found.append(Contact("LINK", url, normalized, match.start(), match.start() + len(url)))
    for match in PHONE_RE.finditer(text):
        if free(*match.span()) and is_phone(match.group(0)):
            found.append(Contact("PHONE", match.group(0).strip(), re.sub(r"\D", "", match.group(0)), *match.span()))
    return sorted(found, key=lambda item: item.start)


def find_contacts(text: str) -> list[Contact]:
    """Emails, phone numbers and links, deduplicated by normalised value."""
    seen: set[tuple[str, str]] = set()
    contacts: list[Contact] = []
    for contact in _contact_occurrences(text):
        if (contact.kind, contact.normalized) not in seen:
            seen.add((contact.kind, contact.normalized))
            contacts.append(contact)
    return contacts


def scrub_contacts(text: str) -> str:
    """Replace emails, phone numbers and links (used for job texts before they reach an LLM)."""
    redactions = [Redaction(item.start, item.end, f"[{item.kind.lower()}]", item.kind.lower()) for item in _contact_occurrences(text)]
    return RedactedText(text, _non_overlapping(redactions)).text


def header_name_tokens(lines: list[Line], header_count: int) -> list[str]:
    """The candidate's name tokens, taken from the header block (lines before the first heading)."""
    limit = min(header_count, 6)
    for line in lines[:15]:
        labelled = _NAME_LABEL_RE.match(line.text)
        if labelled:
            return _name_words(labelled.group("name"))
    for line in lines[:limit]:
        text = line.text
        if EMAIL_RE.search(text) or URL_RE.search(text) or re.search(r"\d|[|@:,;]", text) or len(text) > 45:
            continue
        words = _name_words(text)
        raw_words = re.findall(r"[A-Za-z][A-Za-z.'\-]*", text)
        if 2 <= len(words) <= 4 and len(raw_words) <= 5 and all(word[0].isupper() for word in raw_words):
            if not any(word.lower() in _NOT_NAME_WORDS for word in raw_words):
                if not has_title_word(text):
                    return words
    return []


def _name_words(text: str) -> list[str]:
    words = re.findall(r"[A-Za-z][A-Za-z'\-]*", text)
    return [word for word in words if len(word) >= 2 and word.lower() not in _HONORIFIC_WORDS]


def find_redactions(text: str, lines: list[Line], header_count: int) -> list[Redaction]:
    redactions: list[Redaction] = []

    def add_all(pattern: re.Pattern[str], replacement: str, kind: str, source: str = text, offset: int = 0) -> None:
        for match in pattern.finditer(source):
            redactions.append(Redaction(offset + match.start(), offset + match.end(), replacement, kind))

    for contact in _contact_occurrences(text):
        label = {"EMAIL": "[email]", "PHONE": "[phone]", "LINK": "[link]"}[contact.kind]
        redactions.append(Redaction(contact.start, contact.end, label, contact.kind.lower()))
    add_all(_PERSONAL_RE, "[personal detail removed]", "personal")
    add_all(_DOB_RE, "[date of birth removed]", "dob")
    add_all(_AGE_RE, "[age removed]", "age")
    add_all(_ADDRESS_LABEL_RE, "[address removed]", "address")
    add_all(_STREET_RE, "[address removed]", "address")
    for line in lines[: max(header_count, 3)]:
        add_all(_POSTCODE_RE, "[postcode]", "address", line.text, line.start)

    tokens = header_name_tokens(lines, header_count)
    if tokens:
        full = re.compile(r"\b" + r"\s+".join(re.escape(token) for token in tokens) + r"\b")
        add_all(full, "[name]", "name")
        for token in sorted(set(tokens), key=len, reverse=True):
            variants = {token, token.upper()}
            for variant in variants:
                add_all(re.compile(r"(?<![A-Za-z])" + re.escape(variant) + r"(?![A-Za-z])"), "[name]", "name")
    for match in _HONORIFIC_RE.finditer(text):
        redactions.append(Redaction(match.start(), match.end(), "Mx.", "honorific"))
    for match in _PRONOUN_RE.finditer(text):
        word = match.group(0)
        neutral = _PRONOUNS[word.lower()]
        neutral = neutral.capitalize() if word[0].isupper() else neutral
        redactions.append(Redaction(match.start(), match.end(), neutral, "pronoun"))
    return _non_overlapping(redactions)


def _non_overlapping(redactions: list[Redaction]) -> list[Redaction]:
    ordered = sorted(redactions, key=lambda item: (item.start, -(item.end - item.start)))
    result: list[Redaction] = []
    for item in ordered:
        if result and item.start < result[-1].end:
            if item.end > result[-1].end and item.kind == result[-1].kind:
                last = result.pop()
                result.append(Redaction(last.start, item.end, last.replacement, last.kind))
            continue
        result.append(item)
    return result


class RedactedText:
    """The de-identified rendering of a source text plus offset mapping between the two."""

    _IDENTIFYING = frozenset({"email", "phone", "link", "name", "address", "dob", "age", "personal"})

    def __init__(self, source: str, redactions: list[Redaction]) -> None:
        self.source = source
        self.redactions = redactions
        self._starts = [item.start for item in redactions]
        parts: list[str] = []
        self._segments: list[tuple[int, int, int]] = []  # (output start, source start, length) for unredacted runs
        cursor = out = 0
        for item in redactions:
            if item.start > cursor:
                parts.append(source[cursor:item.start])
                self._segments.append((out, cursor, item.start - cursor))
                out += item.start - cursor
            parts.append(item.replacement)
            out += len(item.replacement)
            cursor = item.end
        parts.append(source[cursor:])
        self._segments.append((out, cursor, len(source) - cursor))
        self.text = "".join(parts)

    def slice(self, start: int, end: int) -> str:
        """De-identified rendering of ``source[start:end]``; partially covered redactions still redact."""
        pieces: list[str] = []
        cursor = start
        first = max(0, bisect.bisect_right(self._starts, start) - 1)
        for item in self.redactions[first:]:
            if item.start >= end:
                break
            if item.end <= start:
                continue
            if item.start > cursor:
                pieces.append(self.source[cursor:item.start])
            pieces.append(item.replacement)
            cursor = max(cursor, item.end)
        if cursor < end:
            pieces.append(self.source[cursor:end])
        return "".join(pieces)

    def overlaps_identifier(self, start: int, end: int) -> bool:
        """True when ``source[start:end]`` touches a name, contact, address or other identifier."""
        first = max(0, bisect.bisect_right(self._starts, start) - 1)
        for item in self.redactions[first:]:
            if item.start >= end:
                return False
            if item.end > start and item.kind in self._IDENTIFYING:
                return True
        return False

    def to_source(self, offset: int) -> int | None:
        """Map an offset in the de-identified text back to the source text (None inside a redaction)."""
        for out_start, source_start, length in self._segments:
            if out_start <= offset <= out_start + length:
                return source_start + (offset - out_start)
        return None


def deidentify(text: str, lines: list[Line], header_count: int) -> RedactedText:
    return RedactedText(text, find_redactions(text, lines, header_count))
