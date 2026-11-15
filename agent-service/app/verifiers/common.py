"""Helpers shared by the verifiers: claim-statement matching in text."""
from __future__ import annotations

import re
from dataclasses import dataclass

from app.skills import covers, resolve_claim_skill
from app.taxonomy.taxonomy import get_taxonomy

_STOP = {"a", "an", "and", "at", "by", "for", "from", "in", "of", "on", "or", "the", "to", "with", "as", "is", "my",
         "i", "&", "-", "certified", "certificate", "certification", "degree", "course", "program", "programme",
         "experience", "years", "year", "skills", "skill", "level"}
_YEAR = re.compile(r"\b(19[5-9]\d|20\d\d)\b")
_WORD = re.compile(r"[A-Za-z0-9+#.]+")


def tokens(text: str) -> list[str]:
    return [token.strip(".").lower() for token in _WORD.findall(text) if token.strip(".")]


def significant_tokens(text: str) -> list[str]:
    return [token for token in tokens(text) if token not in _STOP and not _YEAR.fullmatch(token)]


def years(text: str) -> set[str]:
    return set(_YEAR.findall(text))


@dataclass
class Statement:
    line: str  # verbatim line (or clipped verbatim window) from the text
    conflicting_year: bool = False


def _clip_line(line: str, start: int, limit: int = 300) -> str:
    if len(line) <= limit:
        return line.strip()
    begin = max(0, start - limit // 3)
    return line[begin:begin + limit].strip()


def find_statement(value: str, claim_type: str, text: str) -> tuple[Statement | None, Statement | None]:
    """(supporting statement, conflicting statement) for a claim value in ``text``.

    * SKILL: a line naming the same taxonomy skill (or a specialisation), else the value verbatim (word bounded).
    * Other types: a line containing every significant token of the value (case-insensitive). When the claim names a
      year, the line must also contain that year; a line with all tokens but only *different* years is conflicting.
    """
    lines = [line for line in text.split("\n") if line.strip()]
    if claim_type == "SKILL":
        skill = resolve_claim_skill(value)
        taxonomy = get_taxonomy()
        exact = re.compile(r"(?<![A-Za-z0-9])" + re.escape(value.strip()) + r"(?![A-Za-z0-9])", re.I)
        for line in lines:
            if skill is not None:
                for mention in taxonomy.find_mentions(line, list_context=True):
                    if covers(skill.id, mention.skill.id):
                        return Statement(_clip_line(line, mention.start)), None
            match = exact.search(line)
            if match:
                return Statement(_clip_line(line, match.start())), None
        return None, None
    wanted = significant_tokens(value)
    if not wanted:
        return None, None
    claim_years = years(value)
    conflict: Statement | None = None
    for line in lines:
        line_tokens = set(tokens(line))
        if not all(token in line_tokens for token in wanted):
            continue
        index = line.lower().find(wanted[0])
        line_years = years(line)
        if not claim_years or claim_years & line_years:
            return Statement(_clip_line(line, max(index, 0))), None
        if line_years and conflict is None and len(wanted) >= 2:
            conflict = Statement(_clip_line(line, max(index, 0)), conflicting_year=True)
    return None, conflict
