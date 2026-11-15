"""EvidenceItem construction: one place for hashing, truncation and timestamps.

Rules: ``snapshotText`` is the normalised text that was hashed, truncated to 262144 characters *before* hashing;
``contentHash`` is ``sha256:`` + hex of its UTF-8 bytes; ``excerpt`` <= 1000 chars; ``retrievedAt`` is UTC with ``Z``;
item keys are <= 40 chars; titles <= 300 chars; URLs <= 1000 chars (longer URLs lose their query string, then are
cut).
"""
from __future__ import annotations

import hashlib
import re
from datetime import datetime
from typing import Any

from app.core.clock import iso_z
from app.models import MAX_EXCERPT, MAX_SNAPSHOT, EvidenceItem, EvidenceKind

_SPACES = re.compile(r"[ \t\f\v ]+")
_BLANK_LINES = re.compile(r"\n{3,}")


def normalise_text(text: str) -> str:
    """Collapse horizontal whitespace, trim lines, keep at most one blank line, drop NULs."""
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    lines = [_SPACES.sub(" ", line).strip() for line in text.split("\n")]
    return _BLANK_LINES.sub("\n\n", "\n".join(lines)).strip()


def content_hash(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: max(0, limit - 1)].rstrip() + "…"


def _clip_url(url: str | None) -> str | None:
    if url is None:
        return None
    if len(url) <= 1000:
        return url
    return url.split("?", 1)[0][:1000]


def make_item(key: str, kind: EvidenceKind, *, url: str | None, title: str, text: str, retrieved_at: datetime,
              attributes: dict[str, Any] | None = None, excerpt: str | None = None) -> EvidenceItem:
    snapshot = normalise_text(text)[:MAX_SNAPSHOT]
    return EvidenceItem(
        key=key[:40], kind=kind, url=_clip_url(url), title=clip(normalise_text(title).replace("\n", " "), 300),
        retrieved_at=iso_z(retrieved_at), content_hash=content_hash(snapshot),
        excerpt=clip(normalise_text(excerpt) if excerpt is not None else snapshot, MAX_EXCERPT),
        snapshot_text=snapshot, attributes=attributes or {},
    )


def find_line(text: str, needle_re: re.Pattern[str], limit: int = 300) -> str | None:
    """The first line of ``text`` matching the pattern, clipped around the match (a verbatim substring)."""
    for line in text.split("\n"):
        match = needle_re.search(line)
        if match:
            if len(line) <= limit:
                return line.strip()
            start = max(0, match.start() - limit // 3)
            return line[start:start + limit].strip()
    return None
