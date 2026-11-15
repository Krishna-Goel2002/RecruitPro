"""EvidenceItem invariants."""
from __future__ import annotations

import hashlib
from datetime import datetime, timezone

from app.evidence import make_item
from app.models import MAX_SNAPSHOT

NOW = datetime(2026, 11, 15, 8, 30, 1, 123456, tzinfo=timezone.utc)


def test_hash_is_of_the_returned_snapshot_and_limits_hold():
    item = make_item("k" * 60, "PAGE", url="https://x.example/?" + "q" * 2000, title="t " * 400,
                     text="line  one\r\n\r\n\r\n\tline two\x00" + "z" * (MAX_SNAPSHOT + 500), retrieved_at=NOW)
    assert len(item.snapshot_text) == MAX_SNAPSHOT
    assert item.content_hash == "sha256:" + hashlib.sha256(item.snapshot_text.encode("utf-8")).hexdigest()
    assert item.snapshot_text.startswith("line one\n\nline two")
    assert len(item.excerpt) <= 1000 and len(item.title) <= 300 and len(item.key) == 40
    assert item.url == "https://x.example/" and item.retrieved_at == "2026-11-15T08:30:01.123Z"


def test_serialisation_is_camel_case():
    item = make_item("a", "DOCUMENT", url=None, title="x", text="é unicode", retrieved_at=NOW)
    data = item.model_dump(by_alias=True)
    assert set(data) == {"key", "kind", "url", "title", "retrievedAt", "contentHash", "excerpt", "snapshotText",
                         "attributes"}
    assert data["contentHash"] == "sha256:" + hashlib.sha256("é unicode".encode()).hexdigest()
