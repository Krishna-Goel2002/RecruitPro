"""Document text normalisation shared by every pipeline stage.

The *normalised document text* is the NFKC-normalised, whitespace-collapsed, bullet-stripped
lines joined with ``\\n``. Every fact's ``spanStart``/``spanEnd`` is an offset into it.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

_CONTROL_RE = re.compile("[\x00-\x08\x0b-\x1f\x7f\u2028\u2029]")
# Invisible format characters are removed outright (not turned into spaces), so "Py\u00adthon" or
# "Py\u200bthon" still reads as "Python": soft hyphen, zero-width space/joiners, LRM/RLM, bidi
# embeddings/overrides/isolates, word joiner and invisible operators, BOM.
_INVISIBLE_RE = re.compile("[\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff]")
# Bullets: common symbols (with or without a following space), Word's private-use bullet glyphs
# (U+F0A7, U+F0B7, ... as extracted from PDFs), ASCII markers and "1." / "2)" numbering; a lone "o"
# (Word's second-level bullet) only before a capitalised word.
_BULLET_RE = re.compile(
    "^(?:[\u2022\u25aa\u25cf\u25e6\u2023\u2219\u00b7\u25cb\u25a0\u25a1\u27a2\u27a4\u25ba\u25b6\u2713\u2714"
    "\u2756\u2666\u25c6\u2043\u25b8\u25b9\u2794\u2192\ue000-\uf8ff]+\\s*"
    "|(?:[*\\-\u2013\u2014>]+|\\d{1,2}[.)])\\s+|o\\s+(?=[A-Z]))"
)


@dataclass(frozen=True)
class Flag:
    """A document-level warning reported to the backend (``ParseResponse.flags``)."""

    code: str  # HIDDEN_TEXT | PROMPT_INJECTION_SUSPECTED | TWO_COLUMN_LAYOUT | OCR_USED | LEGACY_DOC | LOW_TEXT_DENSITY
    detail: str


@dataclass(frozen=True)
class RawLine:
    """A line as produced by ingestion, before normalisation."""

    text: str
    reference: str | None = None  # e.g. "page:2"
    heading_hint: bool = False  # styled like a heading (DOCX heading style, larger/bold PDF font)
    style_key: str | None = None  # opaque style signature used to recognise sibling headings
    list_item: bool = False  # a bullet/numbered paragraph whose marker is not part of the text (DOCX numbering)


@dataclass(frozen=True)
class Line:
    index: int
    text: str
    start: int  # offsets into the normalised document text
    end: int
    reference: str | None
    heading_hint: bool
    style_key: str | None
    is_bullet: bool


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", _INVISIBLE_RE.sub("", value))
    value = _CONTROL_RE.sub(" ", value.replace(" ", " ").replace("\t", " "))
    return " ".join(value.split())


def build_document(raw_lines: list[RawLine]) -> tuple[str, list[Line]]:
    """Normalise lines and compute their offsets in the joined document text."""
    lines: list[Line] = []
    parts: list[str] = []
    offset = 0
    for raw in raw_lines:
        text = normalize_text(raw.text)
        bullet = _BULLET_RE.match(text)
        if bullet and len(text) > bullet.end():
            text = text[bullet.end():]
        is_bullet = raw.list_item or bool(bullet)
        if not text:
            continue
        if parts:
            offset += 1  # the joining newline
        lines.append(Line(len(lines), text, offset, offset + len(text), raw.reference, raw.heading_hint,
                          raw.style_key, is_bullet))
        parts.append(text)
        offset += len(text)
    return "\n".join(parts), lines


_WORD_RE = re.compile(r"[a-z0-9][a-z0-9+#]*")
STOP_WORDS = frozenset(
    "a an and or the of in on at to for with by from as is are be been was were this that these those using use used "
    "via per into over under within across our your their its it we you they he she i me my etc e.g eg ie i.e "
    "experience experienced years year yrs yr months month knowledge skills skill ability able strong good "
    "excellent proven solid hands relevant work working professional industry plus minimum least must should "
    "required preferred desirable nice have has having including include includes like such similar related "
    "equivalent field degree".split()
)
PROTECTED_TERMS = frozenset(
    "age aged gender male female man men woman women religion religious caste race racial ethnicity ethnic "
    "disability disabled nationality national marital married unmarried single pregnant pregnancy birth photo "
    "photograph young old".split()
)
_SUFFIXES = ("ations", "ation", "ments", "ment", "ings", "ing", "ants", "ant", "ers", "er", "ors", "or", "ed")
_PREFIX_STEMS = ("certif", "licen", "analy")
_SIBILANT_PLURAL_RE = re.compile(r"(?:ss|x|z|ch|sh)es$")


def stem(word: str) -> str:
    """A deliberately small stemmer so word families meet: developer/development/developing -> develop,
    manager/management -> manag, certified/certification/certificate -> certif."""
    for prefix in _PREFIX_STEMS:
        if word.startswith(prefix):
            return prefix
    for iteration in range(2):
        # Plural endings are only stripped once, before suffixes: a second pass would turn the
        # "nurs" of "nursing" into "nur" while "nurse" stays "nurs".
        if iteration == 0 and word.endswith("ies") and len(word) > 4:
            word = word[:-3] + "y"
        elif iteration == 0 and _SIBILANT_PLURAL_RE.search(word):
            word = word[:-2]
        elif iteration == 0 and word.endswith("s") and not word.endswith("ss") and len(word) > 3:
            word = word[:-1]
        for suffix in _SUFFIXES:
            if word.endswith(suffix) and len(word) - len(suffix) >= 3:
                word = word[: -len(suffix)]
                break
    if word.endswith("e") and len(word) > 4:
        word = word[:-1]
    return word


def content_tokens(value: str) -> set[str]:
    """Stemmed, lower-case content words with stop words and protected terms removed."""
    words = _WORD_RE.findall(value.lower().replace("-", " "))
    return {stem(word) for word in words if word not in STOP_WORDS and word not in PROTECTED_TERMS and len(word) > 1}
