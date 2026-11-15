"""Skills taxonomy: loading, lookup, hierarchy and mention detection.

The curated source of truth is ``skills.json`` (see its header). Mention detection is
token based: text is split into word and punctuation tokens and every alias is looked up
as a token n-gram, longest match first, so ~850 skills cost a handful of dictionary
lookups per token instead of one regular expression per alias.

Ambiguity (finding H3): a skill flagged ``ambiguous`` has a bare name that is also an
ordinary word or a common acronym ("Go", "React", "Spring", "Oracle", "Swift", "R").
Its *name* is accepted only when

* it forms a whole item of a list (skills/languages section, a "Tools:" style label,
  or a requirement description), or
* it appears with its exact casing *and* a context word, or (for names longer than one letter)
  another unambiguous technology/same-domain skill, sits within six tokens on the same line.

Aliases are always unambiguous, which is why "golang", "React.js" or "MS Excel" count
anywhere. Aliases that are themselves too ambiguous ("ML", "DL", "CV", "Node") are simply
not listed.
"""
from __future__ import annotations

import json
import os
import re
from collections import deque
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Iterable

SKILLS_PATH = Path(__file__).with_name("skills.json")
CONTEXT_WINDOW = 6
# Domains whose skills support each other as context for ambiguous names.
_DOMAIN_GROUPS = {"SOFTWARE": "TECH", "DATA": "TECH", "CLOUD": "TECH"}

_TOKEN_RE = re.compile(r"[A-Za-z0-9]+|\+\+|[+#./&'\-]")
_TIGHT_PUNCT = {".", "+", "++", "#", "&", "'"}
_LIST_LABEL_RE = re.compile(
    r"^\s*(?:tools(?: used)?|technologies(?: used)?|tech(?:nology)? stack|stack|environment|skills|key skills|"
    r"tools (?:and|&) technologies|languages|frameworks|platforms|software|technical skills)\s*[:\-–]\s*",
    re.I,
)
_ITEM_DELIMITER_RE = re.compile(
    r"\s*(?:[,;|•·●▪/()\[\]{}:]|\s[-–—]\s|\s(?:and|or|&|with|plus|including|incl\.?|such as|like|e\.g\.?|eg)\s)\s*",
    re.I,
)
_LEADING_QUALIFIERS = {
    "advanced", "basic", "basics", "intermediate", "expert", "expertise", "strong", "good", "solid", "proficient",
    "proficiency", "in", "with", "knowledge", "of", "experience", "experienced", "hands-on", "hands", "on", "working",
    "familiarity", "familiar", "excellent", "deep", "sound", "the", "a", "an", "using", "skilled", "exposure", "to",
    "understanding", "fluent", "native", "conversational", "professional", "at", "least", "minimum", "min",
    "required", "preferred", "must", "have", "know", "how",
}
_TRAILING_QUALIFIERS = {
    "programming", "language", "languages", "development", "developer", "framework", "frameworks", "library",
    "libraries", "apps", "applications", "skills", "skill", "basics", "fundamentals", "expert", "advanced",
    "intermediate", "beginner", "fluent", "native", "proficient", "proficiency", "years", "year", "yrs", "yr",
    "experience", "knowledge", "certified", "speaker",
}
_VERSION_RE = re.compile(r"^v?\d+(?:\.\d+)*\+?$", re.I)


@dataclass(frozen=True)
class Skill:
    id: str
    name: str
    aliases: tuple[str, ...]
    domain: str
    parents: tuple[str, ...]
    distinctions: tuple[str, ...]
    ambiguous: bool
    context_words: tuple[str, ...]
    case_sensitive: bool = True  # ambiguous names must match the name's exact casing outside lists


@dataclass(frozen=True)
class SkillMention:
    skill: Skill
    start: int  # character offsets in the analysed text
    end: int
    surface: str


@dataclass(frozen=True)
class _Token:
    text: str
    lower: str
    start: int
    end: int
    link: str  # how it attaches to the previous token: "tight", "soft" (hyphen) or "space"

    @property
    def is_word(self) -> bool:
        return self.text[0].isalnum()


@dataclass(frozen=True)
class _Entry:
    skill: Skill
    links: tuple[bool, ...]  # True where the alias requires the token to be glued to its predecessor
    is_name: bool


def _tokenize(text: str) -> list[_Token]:
    """Split into word/punctuation tokens. Hyphens are dropped but glue their neighbours softly."""
    tokens: list[_Token] = []
    previous_end = None
    pending_hyphen: tuple[int, int] | None = None
    for match in _TOKEN_RE.finditer(text):
        value, start, end = match.group(0), match.start(), match.end()
        if value == "-":
            pending_hyphen = (start, end) if previous_end == start else None
            previous_end = end
            continue
        if pending_hyphen is not None and pending_hyphen[1] == start:
            link = "soft"
        elif previous_end == start:
            link = "tight"
        else:
            link = "space"
        pending_hyphen = None
        tokens.append(_Token(value, value.lower(), start, end, link))
        previous_end = end
    return tokens


def phrase_key(phrase: str) -> tuple[tuple[str, ...], tuple[bool, ...]]:
    tokens = _tokenize(phrase)
    key = tuple(token.lower for token in tokens)
    links = tuple(index > 0 and token.link == "tight" for index, token in enumerate(tokens))
    return key, links


class Taxonomy:
    """Immutable skills taxonomy with lookup, hierarchy and mention detection."""

    def __init__(self, skills: Iterable[Skill]) -> None:
        self.skills: tuple[Skill, ...] = tuple(skills)
        self._by_id: dict[str, Skill] = {}
        self._index: dict[tuple[str, ...], list[_Entry]] = {}
        self._max_tokens = 1
        self._children: dict[str, list[str]] = {}
        self._ancestors: dict[str, frozenset[str]] = {}
        self._context_res: dict[str, re.Pattern[str]] = {}
        self._collisions: list[str] = []
        for skill in self.skills:
            if skill.id in self._by_id:
                self._collisions.append(f"Duplicate id: {skill.id}")
            self._by_id[skill.id] = skill
            for phrase, is_name in [(skill.name, True)] + [(alias, False) for alias in skill.aliases]:
                key, links = phrase_key(phrase)
                if not key:
                    continue
                entries = self._index.setdefault(key, [])
                other = next((entry for entry in entries if entry.skill.id != skill.id), None)
                if other is not None:
                    self._collisions.append(f"Alias collision: '{phrase}' used by {other.skill.id} and {skill.id}")
                if not any(entry.skill.id == skill.id and entry.links == links for entry in entries):
                    entries.append(_Entry(skill, links, is_name))
                self._max_tokens = max(self._max_tokens, len(key))
            for parent in skill.parents:
                self._children.setdefault(parent, []).append(skill.id)
            if skill.context_words:
                words = sorted(skill.context_words, key=len, reverse=True)
                self._context_res[skill.id] = re.compile(
                    r"(?<![a-z0-9])(?:" + "|".join(re.escape(word) for word in words) + r")(?![a-z0-9])"
                )

    # ------------------------------------------------------------------ lookup
    def by_id(self, skill_id: str) -> Skill | None:
        return self._by_id.get(skill_id)

    def lookup(self, name_or_alias: str) -> Skill | None:
        """Exact (case-insensitive) lookup of a skill name or alias."""
        key, _ = phrase_key(name_or_alias.strip())
        entries = self._index.get(key)
        return entries[0].skill if entries else None

    # --------------------------------------------------------------- hierarchy
    def ancestors(self, skill_id: str) -> frozenset[str]:
        cached = self._ancestors.get(skill_id)
        if cached is not None:
            return cached
        seen: set[str] = set()
        queue = deque(self._by_id[skill_id].parents if skill_id in self._by_id else ())
        while queue:
            parent = queue.popleft()
            if parent in seen:
                continue
            seen.add(parent)
            if parent in self._by_id:
                queue.extend(self._by_id[parent].parents)
        result = frozenset(seen)
        self._ancestors[skill_id] = result
        return result

    def is_ancestor(self, ancestor_id: str, descendant_id: str) -> bool:
        """True when ``descendant_id`` specialises ``ancestor_id`` (MySQL -> SQL); directional."""
        return ancestor_id in self.ancestors(descendant_id)

    def children(self, skill_id: str) -> tuple[str, ...]:
        return tuple(sorted(self._children.get(skill_id, ())))

    def are_distinct(self, first_id: str, second_id: str) -> bool:
        first, second = self._by_id.get(first_id), self._by_id.get(second_id)
        if not first or not second:
            return False
        return second.id in first.distinctions or first.id in second.distinctions

    # -------------------------------------------------------------- detection
    def find_mentions(self, text: str, *, list_context: bool = False) -> list[SkillMention]:
        """Return accepted skill mentions in ``text`` (longest match first, non-overlapping).

        ``list_context`` declares the whole text a list (a skills-section line or a
        requirement description); otherwise a "Tools:"-style label still makes the rest of
        the line a list.
        """
        if not text:
            return []
        list_start = 0
        if not list_context:
            label = _LIST_LABEL_RE.match(text)
            if label:
                list_context, list_start = True, label.end()
        items = {(start, end) for start, end in list_item_cores(text, list_start)} if list_context else set()
        tokens = _tokenize(text)
        candidates: list[tuple[int, int, _Entry]] = []  # (first token index, token count, entry)
        index = 0
        while index < len(tokens):
            match = self._longest_match(tokens, index)
            if match is None:
                index += 1
                continue
            count, entry = match
            candidates.append((index, count, entry))
            index += count

        accepted: list[tuple[int, int, Skill, int, int]] = []
        deferred: list[tuple[int, int, Skill, int, int]] = []
        for first, count, entry in candidates:
            start, end = tokens[first].start, tokens[first + count - 1].end
            record = (first, count, entry.skill, start, end)
            if entry.skill.ambiguous and entry.is_name and (start, end) not in items:
                deferred.append(record)
            else:
                accepted.append(record)
        for first, count, skill, start, end in deferred:
            exact_case = text[start:end] == skill.name or not skill.case_sensitive
            if exact_case and self._has_context(text, tokens, first, count, skill, accepted):
                accepted.append((first, count, skill, start, end))
        accepted.sort(key=lambda record: record[3])
        return [SkillMention(skill, start, end, text[start:end]) for _, _, skill, start, end in accepted]

    def extract(self, text: str, *, list_context: bool = False) -> set[str]:
        """Names of the skills mentioned in ``text``."""
        return {mention.skill.name for mention in self.find_mentions(text, list_context=list_context)}

    def _longest_match(self, tokens: list[_Token], index: int) -> tuple[int, _Entry] | None:
        if not self._boundary_before_ok(tokens, index):
            return None
        for count in range(min(self._max_tokens, len(tokens) - index), 0, -1):
            entries = self._index.get(tuple(token.lower for token in tokens[index:index + count]))
            if not entries or not self._boundary_after_ok(tokens, index + count):
                continue
            for entry in entries:
                if all(not tight or tokens[index + offset].link == "tight" for offset, tight in enumerate(entry.links)):
                    return count, entry
        return None

    @staticmethod
    def _boundary_before_ok(tokens: list[_Token], index: int) -> bool:
        if index == 0 or tokens[index].link != "tight":
            return True
        previous = tokens[index - 1]
        return not (previous.is_word or previous.text in _TIGHT_PUNCT)

    @staticmethod
    def _boundary_after_ok(tokens: list[_Token], index: int) -> bool:
        if index >= len(tokens) or tokens[index].link != "tight":
            return True
        following = tokens[index]
        if following.is_word or following.text in {"+", "++", "#"}:
            return False
        if following.text in {".", "&", "'"}:
            after = tokens[index + 1] if index + 1 < len(tokens) else None
            return not (after is not None and after.link == "tight" and after.is_word)
        return True

    def _has_context(self, text: str, tokens: list[_Token], first: int, count: int, skill: Skill,
                     accepted: list[tuple[int, int, Skill, int, int]]) -> bool:
        low = max(0, first - CONTEXT_WINDOW)
        high = min(len(tokens), first + count + CONTEXT_WINDOW)
        pattern = self._context_res.get(skill.id)
        if pattern is not None:
            for start, end in ((low, first), (first + count, high)):
                if start < end and pattern.search(text[tokens[start].start:tokens[end - 1].end].lower()):
                    return True
        if len(skill.name) == 1:
            return False  # "C" or "R" next to another skill is too often a list letter or a placeholder name
        group = _DOMAIN_GROUPS.get(skill.domain, skill.domain)
        for other_first, other_count, other, _, _ in accepted:
            if other.id == skill.id or other.ambiguous:
                continue
            if _DOMAIN_GROUPS.get(other.domain, other.domain) != group:
                continue
            if other_first < high and other_first + other_count > low:
                return True
        return False

    # -------------------------------------------------------------- validation
    def validate(self) -> list[str]:
        errors = list(self._collisions)
        for skill in self.skills:
            for reference in skill.parents + skill.distinctions:
                if reference not in self._by_id:
                    errors.append(f"Unknown reference '{reference}' in {skill.id}")
            if skill.id in self.ancestors(skill.id):
                errors.append(f"Parent cycle through {skill.id}")
            if skill.ambiguous and not skill.context_words:
                errors.append(f"Ambiguous skill without context words: {skill.id}")
        return errors


def list_item_cores(text: str, start: int = 0) -> list[tuple[int, int]]:
    """Character spans of list items after stripping qualifiers ("Advanced Excel" -> "Excel")."""
    spans: list[tuple[int, int]] = []
    cursor = start
    for delimiter in list(_ITEM_DELIMITER_RE.finditer(text, start)) + [None]:
        item_end = delimiter.start() if delimiter else len(text)
        core = _trim_item(text, cursor, item_end)
        if core:
            spans.append(core)
        if delimiter:
            cursor = delimiter.end()
    return spans


def _trim_item(text: str, start: int, end: int) -> tuple[int, int] | None:
    words = [(match.start(), match.end()) for match in re.finditer(r"\S+", text[start:end])]
    words = [(start + a, start + b) for a, b in words]

    def bare(span: tuple[int, int]) -> str:
        return text[span[0]:span[1]].strip(".,:;!?*\"'").lower()

    while words and bare(words[0]) in _LEADING_QUALIFIERS:
        words.pop(0)
    while words and (bare(words[-1]) in _TRAILING_QUALIFIERS or _VERSION_RE.match(bare(words[-1]) or "x")):
        words.pop()
    if not words:
        return None
    core_start, core_end = words[0][0], words[-1][1]
    while core_end > core_start and text[core_end - 1] in ".,:;!?*\"'":
        core_end -= 1
    return (core_start, core_end) if core_end > core_start else None


def load_skills(path: Path) -> list[Skill]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [
        Skill(
            id=item["id"], name=item["name"], aliases=tuple(item.get("aliases", ())), domain=item["domain"],
            parents=tuple(item.get("parents", ())), distinctions=tuple(item.get("distinctions", ())),
            ambiguous=bool(item.get("ambiguous", False)),
            context_words=tuple(word.lower() for word in item.get("contextWords", ())),
            case_sensitive=bool(item.get("caseSensitive", True)),
        )
        for item in data["skills"]
    ]


@lru_cache(maxsize=1)
def get_taxonomy() -> Taxonomy:
    """Load the curated taxonomy plus an optional extra file (``TAXONOMY_EXTRA_PATH``, e.g. an ESCO import)."""
    skills = load_skills(SKILLS_PATH)
    extra = os.environ.get("TAXONOMY_EXTRA_PATH", "").strip()
    if extra:
        known = {skill.id for skill in skills}
        skills.extend(skill for skill in load_skills(Path(extra)) if skill.id not in known)
    return Taxonomy(skills)
