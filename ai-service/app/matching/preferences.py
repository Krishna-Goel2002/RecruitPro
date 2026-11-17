"""Preferred / nice-to-have clauses inside one requirement (finding W6).

"Bachelor's degree; MBA preferred" asks for a bachelor's degree. The MBA is a preference, so a
non-business graduate meets the requirement. Requirement text written by people mixes the two in
one line, so both the requirement compiler (which splits them into a mandatory and an optional
requirement) and the matcher (which evaluates only the required part) use this split.
"""
from __future__ import annotations

import re

NICE_CUE_RE = re.compile(
    r"\bnice[- ]to[- ]have\b|\bpreferred\b|\bpreferably\b|\bpreferable\b|\bbonus\b|\ba\s+plus\b|\bdesirable\b|"
    r"\bgood[- ]to[- ]have\b|\badvantage(?:ous)?\b|\bideally\b|\bwould\s+be\s+great\b|\boptional\b|\bwelcome\b|"
    r"\bdesired\b|\bnot\s+mandatory\b|\bnot\s+required\b|\bwill\s+be\s+an\s+added\s+advantage\b|\badded\s+advantage\b",
    re.I,
)
# "preferably in Computer Science", "ideally an MBA": the cue starts the preference.
_LEADING_CUE_RE = re.compile(r"\b(?:preferably|ideally|preferable)\b", re.I)
# Clause boundaries. A full stop is not one: "B.Sc. Nursing" must stay whole.
_CLAUSE_SPLIT_RE = re.compile(r"\s*(?:;|,(?=\s)|\((?=[^)]*\))|\)|\s+-\s+|\s+–\s+|\s+(?:but|while|whereas)\s+)\s*", re.I)


def split_preference(text: str) -> tuple[str, list[str]]:
    """(required part, preferred parts). The required part is ``text`` itself when nothing is preferred,
    or when every clause is a preference (then the whole requirement is a preference, decided elsewhere)."""
    if not NICE_CUE_RE.search(text):
        return text, []
    required: list[str] = []
    preferred: list[str] = []
    for clause in (part.strip(" ,;:.()") for part in _CLAUSE_SPLIT_RE.split(text)):
        if not clause:
            continue
        cue = _LEADING_CUE_RE.search(clause)
        if cue and cue.start() > 0:
            # "Bachelor's degree preferably in Computer Science": the head stays required.
            head, tail = clause[:cue.start()].strip(" ,"), clause[cue.start():]
            if head:
                required.append(head)
            preferred.append(_strip_cue(tail))
        elif NICE_CUE_RE.search(clause):
            preferred.append(_strip_cue(clause))
        else:
            required.append(clause)
    preferred = [item for item in preferred if item]
    if not required:
        return text, []
    return "; ".join(required), preferred


def _strip_cue(clause: str) -> str:
    stripped = NICE_CUE_RE.sub(" ", clause)
    stripped = re.sub(r"\b(?:is|are|will\s+be|would\s+be|an?|in)\s*$", "", " ".join(stripped.split()), flags=re.I)
    return re.sub(r"^(?:is|are|an?|in|with)\s+", "", stripped.strip(" ,;:.()"), flags=re.I).strip()
