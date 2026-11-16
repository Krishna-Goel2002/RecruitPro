"""Prompt-injection marker detection in fetched text.

Fetched content is data, never instructions: nothing here changes a verdict. A match produces an integrity note
``INSTRUCTION_TEXT`` naming the evidence items, so a reviewer learns that a source *tried* to instruct an automated
verifier. Fetched text is never placed in an LLM system prompt and the LLM has no tools.
"""
from __future__ import annotations

import re
import unicodedata

_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(?:ignore|disregard|forget|override)\s+(?:all\s+|any\s+)?(?:the\s+|your\s+)?(?:previous|prior|above|"
                r"earlier|preceding|other)\s+(?:instructions?|prompts?|directions?|rules?|guidelines?|context)", re.I),
     "instruction override"),
    (re.compile(r"\byou\s+are\s+(?:now\s+)?an?\s+(?:ai|assistant|language\s+model|llm|chatbot|gpt|recruit(?:er|ing)"
                r"(?:\s+(?:bot|assistant|agent))?|hiring\s+(?:manager|assistant)|screening\s+(?:tool|system|bot)|"
                r"verifi(?:er|cation)\s+(?:agent|bot|system|tool))\b", re.I), "role reassignment"),
    (re.compile(r"\b(?:mark|label|treat|report|classify|consider)\s+(?:this|these|all|every|each|my)\s+(?:\w+\s+){0,2}?"
                r"(?:claims?|skills?|candidates?|credentials?|badges?|repositor(?:y|ies)|profiles?)\s+as\s+"
                r"(?:verified|confirmed|corroborated|true|authentic|valid)", re.I), "verdict manipulation"),
    (re.compile(r"\b(?:rank|rate|score|mark|place|list|put)\s+(?:this|me|this\s+(?:candidate|applicant|resume|cv))\s+"
                r"(?:as\s+|at\s+(?:the\s+)?)?(?:first|highest|top|#?\s?1|number\s+one|best|10\s*/\s*10|100\s*%?)", re.I),
     "ranking manipulation"),
    (re.compile(r"\bsystem\s+prompt\b|\bprompt\s+injection\b|<\s*/?\s*(?:system|assistant|instructions?)\s*>|"
                r"\[\s*/?\s*(?:system|inst)\s*\]", re.I), "system prompt reference"),
    (re.compile(r"\b(?:dear\s+|hey\s+|attention\s+|note\s+to\s+(?:the\s+)?)?(?:ai|llm|language\s+model|chatgpt|gpt|claude|"
                r"assistant|verifier|verification\s+agent|bot)\s*[,:]\s*(?:please\s+)?(?:you\s+(?:must|should|will)|ignore|"
                r"rank|rate|score|recommend|shortlist|mark|verify|confirm|output|return)\b", re.I),
     "instruction to an AI model"),
    (re.compile(r"\b(?:shortlist|hire|select|recommend|approve|verify)\s+(?:this\s+(?:candidate|applicant)|me)\s+"
                r"(?:immediately|now|regardless|without)", re.I), "instruction to the screener"),
)


# Zero-width and other invisible format characters that would split a phrase without changing how it reads.
_INVISIBLE = re.compile("[­͏؜ᅟᅠ឴឵᠋-᠏​-‏‪-‮"
                        "⁠-⁯ㅤ︀-️﻿ﾠ]")


def _normalise(text: str) -> str:
    """NFKC (full-width and compatibility letters become ASCII) without invisible characters."""
    return unicodedata.normalize("NFKC", _INVISIBLE.sub("", text))


def find_markers(text: str) -> list[str]:
    """Sorted kinds of instruction-like content found in ``text`` (also when disguised with zero-width characters or
    full-width letters)."""
    normalised = _normalise(text)
    return sorted({kind for pattern, kind in _PATTERNS if pattern.search(text) or pattern.search(normalised)})
