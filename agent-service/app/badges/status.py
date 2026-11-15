"""Revocation through a simple status list (``StatusList2021Entry`` / ``BitstringStatusListEntry``).

The list credential is fetched through the guarded fetcher. A VC-JWT list must verify with the issuer's key; a JSON
list (unsigned or Data Integrity) is read but reported with warning ``STATUS_LIST_UNVERIFIED``. ``encodedList`` is
a GZIP bitstring, base64url (optionally multibase ``u``-prefixed); bit ``statusListIndex`` (most significant bit
first) set means revoked. Decompression is capped at 2 MB. Any other status type -> not checked (warning).
"""
from __future__ import annotations

import base64
import json
import zlib
from dataclasses import dataclass
from typing import Any, Callable

from app.fetch.client import DATA_TYPES, Fetcher
from app.fetch.ssrf import FetchError

SUPPORTED_TYPES = {"StatusList2021Entry", "BitstringStatusListEntry"}
MAX_LIST_BYTES = 2 * 1024 * 1024


@dataclass
class StatusResult:
    state: str  # "revoked" | "active" | "not_checked"
    warning: str | None = None
    document: str | None = None
    url: str | None = None


def _decode_list(encoded: str) -> bytes:
    text = encoded[1:] if encoded.startswith("u") else encoded
    raw = base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    data = inflater.decompress(raw, MAX_LIST_BYTES)
    if inflater.unconsumed_tail:
        raise ValueError("status list too large")
    return data


def check_status(status: Any, fetcher: Fetcher,
                 verify_jwt: Callable[[str], dict[str, Any] | None]) -> StatusResult:
    """``verify_jwt`` returns the verified payload of a list VC-JWT from the same issuer, or None."""
    if status is None:
        return StatusResult("not_checked", None)
    entry = status[0] if isinstance(status, list) and status else status
    if not isinstance(entry, dict) or entry.get("type") not in SUPPORTED_TYPES:
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED")
    if str(entry.get("statusPurpose", "revocation")) not in {"revocation", "suspension"}:
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED")
    url = entry.get("statusListCredential")
    try:
        index = int(str(entry.get("statusListIndex")))
    except (TypeError, ValueError):
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED")
    if not isinstance(url, str) or index < 0:
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED")
    try:
        result = fetcher.fetch(url, accept=DATA_TYPES, max_bytes=512 * 1024, respect_robots=False)
    except FetchError:
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", url=url)
    if not result.ok:
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", url=url)
    text = result.text().strip()
    warning = None
    if text.count(".") == 2 and not text.startswith("{"):
        payload = verify_jwt(text)
        if payload is None:
            return StatusResult("not_checked", "STATUS_LIST_UNVERIFIED", document=text, url=url)
        credential = payload.get("vc", payload)
    else:
        try:
            credential = json.loads(text)
        except ValueError:
            return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", document=text, url=url)
        warning = "STATUS_LIST_UNVERIFIED"
    subject = credential.get("credentialSubject") if isinstance(credential, dict) else None
    encoded = subject.get("encodedList") if isinstance(subject, dict) else None
    if not isinstance(encoded, str):
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", document=text, url=url)
    try:
        bits = _decode_list(encoded)
    except (ValueError, zlib.error):
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", document=text, url=url)
    if index // 8 >= len(bits):
        return StatusResult("not_checked", "REVOCATION_NOT_CHECKED", document=text, url=url)
    revoked = bool(bits[index // 8] & (0x80 >> (index % 8)))
    return StatusResult("revoked" if revoked else "active", warning, document=text, url=url)
