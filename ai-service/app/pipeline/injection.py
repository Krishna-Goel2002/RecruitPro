"""Prompt-injection screen.

Resume text is only ever data: nothing in it changes how the service behaves. The screen
exists so a reviewer learns that a document *tried* to instruct an automated screener, which
is itself a signal. Hidden text listing many skills (keyword stuffing aimed at matchers) is
reported the same way.
"""
from __future__ import annotations

import re

from app.pipeline.text import Flag
from app.taxonomy.taxonomy import get_taxonomy

_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(?:ignore|disregard|forget|override)\s+(?:all\s+|any\s+)?(?:the\s+|your\s+)?(?:previous|prior|above|earlier|preceding|other)\s+"
                r"(?:instructions?|prompts?|directions?|rules?|guidelines?|context)", re.I), "instruction override"),
    (re.compile(r"\byou\s+are\s+(?:now\s+)?an?\s+(?:ai|assistant|language\s+model|llm|chatbot|gpt|recruit(?:er|ing)(?:\s+(?:bot|assistant|agent))?|"
                r"hiring\s+(?:manager|assistant)|screening\s+(?:tool|system|bot))\b", re.I), "role reassignment"),
    (re.compile(r"\b(?:rank|rate|score|mark|place|list|put)\s+(?:this|me|this\s+(?:candidate|applicant|resume|cv))\s+(?:as\s+|at\s+(?:the\s+)?)?"
                r"(?:first|highest|top|#?\s?1|number\s+one|best|10\s*/\s*10|100\s*%?)", re.I), "ranking manipulation"),
    (re.compile(r"\bsystem\s+prompt\b|\bprompt\s+injection\b|<\s*/?\s*(?:system|assistant|instructions?)\s*>|\[\s*/?\s*(?:system|inst)\s*\]", re.I),
     "system prompt reference"),
    (re.compile(r"\b(?:this|the)\s+(?:candidate|applicant)\s+(?:is|should\s+be\s+(?:considered|treated\s+as))\s+(?:a\s+|an\s+)?"
                r"(?:perfect|ideal|excellent|exceptional)\s+(?:match|fit|hire)", re.I), "instruction to the screener"),
    (re.compile(r"\b(?:shortlist|hire|select|recommend|approve)\s+(?:this\s+(?:candidate|applicant)|me)\s+(?:immediately|now|regardless|"
                r"without)", re.I), "instruction to the screener"),
    (re.compile(r"\b(?:dear\s+|hey\s+|attention\s+)?(?:ai|llm|language\s+model|chatgpt|gpt|claude|assistant)\s*[,:]\s*(?:please\s+)?"
                r"(?:you\s+(?:must|should|will)|ignore|rank|rate|score|recommend|shortlist)\b", re.I), "instruction to an AI model"),
)
_STUFFING_THRESHOLD = 3


def screen(visible_text: str, hidden_text: str) -> list[Flag]:
    """Return a PROMPT_INJECTION_SUSPECTED flag when instruction-like or stuffed text is present."""
    reasons: list[str] = []
    for label, text in (("visible text", visible_text), ("hidden text", hidden_text)):
        kinds = sorted({kind for pattern, kind in _PATTERNS if pattern.search(text)})
        if kinds:
            reasons.append(f"{', '.join(kinds)} in {label}")
    if hidden_text:
        stuffed = get_taxonomy().extract(hidden_text, list_context=True)
        if len(stuffed) >= _STUFFING_THRESHOLD:
            reasons.append(f"hidden text lists {len(stuffed)} skills (keyword stuffing)")
    if not reasons:
        return []
    return [Flag("PROMPT_INJECTION_SUSPECTED",
                 "Instruction-like or manipulative content detected (" + "; ".join(reasons)
                 + "). Resume content is treated as data only; review the document manually.")]
