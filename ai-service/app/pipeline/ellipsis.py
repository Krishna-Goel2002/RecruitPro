"""Coordination ellipsis in skill phrases (finding W8).

"field and inside sales" names Field Sales and Inside Sales; "B2B or SaaS sales" names B2B Sales and
SaaS Sales; "SQL, NoSQL and graph databases" shares "databases". The taxonomy matches whole phrases,
so only the last conjunct is found. This module restores the elided ones: for every multi-word
mention, the single words coordinated in front of it ("field and", "B2B or", "unit, integration
and") are tried with the mention's shared tail; a combination that is itself a taxonomy name or
alias becomes an extra mention spanning the coordinated word.
"""
from __future__ import annotations

import re

from app.taxonomy.taxonomy import SkillMention, get_taxonomy

# One coordinated word right before a phrase: "field and ", "B2B or ", "unit, ", "B2B/".
_CONJUNCT_RE = re.compile(r"([A-Za-z][A-Za-z0-9+#.\-]{0,20})\s*(?:,\s*(?:and\s+|or\s+|&\s+)?|\s+(?:and|or|&)\s+|\s*/\s*)$", re.I)
_STOP = frozenset({"in", "of", "and", "or", "the", "a", "an", "with", "for", "to", "on", "at", "by", "using", "as", "both",
                   "either", "strong", "good", "excellent", "basic", "advanced", "experience", "knowledge", "skills"})


def with_elided(text: str, mentions: list[SkillMention]) -> list[SkillMention]:
    """``mentions`` plus the coordinated skills whose shared words were elided, sorted by position."""
    taxonomy = get_taxonomy()
    found = {mention.skill.id for mention in mentions}
    taken = [(mention.start, mention.end) for mention in mentions]
    extra: list[SkillMention] = []
    for mention in mentions:
        words = mention.surface.split()
        if len(words) < 2:
            continue
        tail = " ".join(words[1:])
        cursor = mention.start
        for _ in range(3):  # "field, inside and B2B sales"
            conjunct = _CONJUNCT_RE.search(text[max(0, cursor - 40):cursor])
            if not conjunct:
                break
            word = conjunct.group(1)
            start = cursor - (len(text[max(0, cursor - 40):cursor]) - conjunct.start(1))
            end = start + len(word)
            if word.lower() in _STOP or any(a < end and start < b for a, b in taken):
                break
            skill = taxonomy.lookup(f"{word} {tail}")
            if skill is None:
                break
            if skill.id not in found:
                extra.append(SkillMention(skill, start, end, text[start:end]))
                found.add(skill.id)
            taken.append((start, end))
            cursor = start
    return sorted(mentions + extra, key=lambda item: item.start)
