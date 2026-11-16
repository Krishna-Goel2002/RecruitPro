"""Citation validator for the optional LLM ``summary``.

A summary entry is kept only when all hold:

* it has a non-empty ``text`` (<= 600 chars) and at least one evidence key;
* every key names an evidence item of this response;
* it has a ``quote`` that is found verbatim (whitespace-insensitive) in the ``snapshotText`` of at least one of the
  cited items (the original snapshot, not a de-identified copy);
* neither its text nor its quote carries prompt-injection markers;
* its quote names no identity (login, name, URL, e-mail, repository), because it cannot be de-identified and stay
  verbatim.

Kept entries are de-identified. Everything else is dropped silently (the count is reported in warnings).
"""
from __future__ import annotations

import re
from typing import Any

from app.deidentify import Identities, deidentify
from app.injection import find_markers
from app.models import EvidenceItem, Reason

MAX_SUMMARY_TEXT = 600
MAX_SUMMARY_ENTRIES = 8


def find_quote(quote: str, text: str) -> tuple[int, int] | None:
    """Span of ``quote`` in ``text`` when it appears verbatim (whitespace runs may differ)."""
    pieces = quote.split()
    if not pieces or len(quote.strip()) < 3:
        return None
    match = re.search(r"\s+".join(re.escape(piece) for piece in pieces), text)
    return match.span() if match else None


def validate_summary(raw: Any, evidence: list[EvidenceItem], identities: Identities) -> tuple[list[Reason], int]:
    """Validated, de-identified summary entries and the number of dropped entries."""
    if not isinstance(raw, list):
        return [], 0 if raw in (None, []) else 1
    by_key = {item.key: item for item in evidence}
    kept: list[Reason] = []
    dropped = 0
    for entry in raw:
        reason = _validate_entry(entry, by_key, identities)
        if reason is None or len(kept) >= MAX_SUMMARY_ENTRIES:
            dropped += 1
        else:
            kept.append(reason)
    return kept, dropped


def _validate_entry(entry: Any, by_key: dict[str, EvidenceItem], identities: Identities) -> Reason | None:
    if not isinstance(entry, dict):
        return None
    text, keys, quote = entry.get("text"), entry.get("evidenceKeys"), entry.get("quote")
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_SUMMARY_TEXT:
        return None
    if not isinstance(keys, list) or not keys or not all(isinstance(key, str) and key in by_key for key in keys):
        return None
    if not isinstance(quote, str) or not any(find_quote(quote, by_key[key].snapshot_text) for key in keys):
        return None
    if find_markers(text) or find_markers(quote):  # the quote is shown to staff as part of the entry too
        return None
    if deidentify(quote, identities) != quote:  # a verbatim quote that names the candidate cannot be shown blind
        return None
    return Reason(text=deidentify(text.strip(), identities) or "", evidence_keys=list(dict.fromkeys(keys)),
                  quote=quote.strip())
