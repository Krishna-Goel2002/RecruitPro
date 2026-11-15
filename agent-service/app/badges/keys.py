"""Issuer key resolution for OB 3.0 VC-JWTs.

Accepted sources, all bound to the credential's issuer:
* ``did:key`` (Ed25519 only, multicodec ``0xed01``): the key is the identifier itself;
* ``did:web``: ``https://<host>[/<path>]/did.json`` (``/.well-known/did.json`` without a path), fetched through the
  SSRF-guarded fetcher; the document ``id`` must equal the DID; the verification method is chosen by ``kid``;
* JWKS: for an ``https`` issuer id, the header ``jku`` when it is on the issuer's origin, else
  ``<issuer origin>/.well-known/jwks.json``; the key is chosen by ``kid`` (or the only key).
An embedded ``jwk`` header is never trusted (anyone can sign with their own key). Algorithms are fixed by key type:
OKP/Ed25519 -> EdDSA, EC/P-256 -> ES256, RSA (>= 2048 bits) -> RS256.
"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from typing import Any
from urllib.parse import unquote, urlsplit

from app.fetch.client import DATA_TYPES, Fetcher, origin
from app.fetch.ssrf import FetchError

_B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
ED25519_MULTICODEC = b"\xed\x01"
KEY_DOC_MAX_BYTES = 256 * 1024


class KeyResolutionError(Exception):
    """The issuer key could not be found or is not acceptable (verdict NOT_CHECKABLE)."""


@dataclass
class ResolvedKey:
    jwk: dict[str, Any]
    alg: str
    source: str  # did:key | did:web | jwks
    document: str | None = None  # the fetched DID document / JWKS text (evidence)
    url: str | None = None


def b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def base58_decode(value: str) -> bytes:
    number = 0
    for char in value:
        index = _B58_ALPHABET.find(char)
        if index < 0:
            raise KeyResolutionError("invalid base58 text")
        number = number * 58 + index
    raw = number.to_bytes((number.bit_length() + 7) // 8, "big") if number else b""
    leading = len(value) - len(value.lstrip("1"))
    return b"\x00" * leading + raw


def base58_encode(value: bytes) -> str:
    number = int.from_bytes(value, "big")
    chars = ""
    while number:
        number, remainder = divmod(number, 58)
        chars = _B58_ALPHABET[remainder] + chars
    leading = len(value) - len(value.lstrip(b"\x00"))
    return "1" * leading + chars


def did_key_from_ed25519(public_key: bytes) -> str:
    return "did:key:z" + base58_encode(ED25519_MULTICODEC + public_key)


def ed25519_jwk_from_multibase(value: str) -> dict[str, Any]:
    if not value.startswith("z"):
        raise KeyResolutionError("only base58btc multibase keys are supported")
    raw = base58_decode(value[1:])
    if not raw.startswith(ED25519_MULTICODEC) or len(raw) != 34:
        raise KeyResolutionError("only Ed25519 did:key identifiers are supported")
    return {"kty": "OKP", "crv": "Ed25519", "x": b64url_encode(raw[2:])}


def alg_for_jwk(jwk: dict[str, Any]) -> str:
    kty, crv = jwk.get("kty"), jwk.get("crv")
    if kty == "OKP" and crv == "Ed25519":
        return "EdDSA"
    if kty == "EC" and crv == "P-256":
        return "ES256"
    if kty == "RSA":
        modulus = b64url_decode(str(jwk.get("n", "")))
        if len(modulus.lstrip(b"\x00")) * 8 < 2048:
            raise KeyResolutionError("RSA keys shorter than 2048 bits are not accepted")
        return "RS256"
    raise KeyResolutionError("unsupported key type")


def did_web_url(did: str) -> str:
    parts = did.split(":")
    if len(parts) < 3 or parts[0] != "did" or parts[1] != "web" or not parts[2]:
        raise KeyResolutionError("malformed did:web")
    host = unquote(parts[2])
    path = "/".join(unquote(part) for part in parts[3:])
    return f"https://{host}/{path}/did.json" if path else f"https://{host}/.well-known/did.json"


def _fetch_json(fetcher: Fetcher, url: str) -> tuple[Any, str]:
    try:
        result = fetcher.fetch(url, accept=DATA_TYPES, max_bytes=KEY_DOC_MAX_BYTES, respect_robots=False)
    except FetchError as exc:
        raise KeyResolutionError(f"key document unavailable ({exc.code})") from exc
    if not result.ok:
        raise KeyResolutionError(f"key document answered {result.status}")
    text = result.text()
    try:
        return json.loads(text), text
    except ValueError as exc:
        raise KeyResolutionError("key document is not JSON") from exc


def _from_did_web(did: str, kid: str | None, fetcher: Fetcher) -> ResolvedKey:
    url = did_web_url(did)
    document, text = _fetch_json(fetcher, url)
    if not isinstance(document, dict) or document.get("id") != did:
        raise KeyResolutionError("DID document id does not match the issuer")
    methods = [method for method in document.get("verificationMethod") or [] if isinstance(method, dict)]
    wanted = kid if kid and "#" in kid else (f"{did}{kid}" if kid and kid.startswith("#") else None)
    method = None
    for candidate in methods:
        method_id = str(candidate.get("id", ""))
        full_id = f"{did}{method_id}" if method_id.startswith("#") else method_id
        if wanted is None or full_id == wanted:
            method = candidate
            break
    if method is None:
        raise KeyResolutionError("no verification method matches the key id")
    if isinstance(method.get("publicKeyJwk"), dict):
        jwk = {key: value for key, value in method["publicKeyJwk"].items() if key in {"kty", "crv", "x", "y", "n", "e"}}
    elif isinstance(method.get("publicKeyMultibase"), str):
        jwk = ed25519_jwk_from_multibase(method["publicKeyMultibase"])
    else:
        raise KeyResolutionError("verification method has no usable public key")
    return ResolvedKey(jwk=jwk, alg=alg_for_jwk(jwk), source="did:web", document=text, url=url)


def _from_jwks(issuer: str, header: dict[str, Any], fetcher: Fetcher) -> ResolvedKey:
    issuer_origin = origin(issuer)
    jku = header.get("jku")
    url = jku if isinstance(jku, str) and origin(jku) == issuer_origin else issuer_origin + "/.well-known/jwks.json"
    document, text = _fetch_json(fetcher, url)
    keys = [key for key in (document.get("keys") if isinstance(document, dict) else None) or [] if isinstance(key, dict)]
    kid = header.get("kid")
    chosen = [key for key in keys if kid is not None and key.get("kid") == kid] or (keys if len(keys) == 1 else [])
    if not chosen:
        raise KeyResolutionError("no JWKS key matches the key id")
    jwk = {key: value for key, value in chosen[0].items() if key in {"kty", "crv", "x", "y", "n", "e"}}
    return ResolvedKey(jwk=jwk, alg=alg_for_jwk(jwk), source="jwks", document=text, url=url)


def resolve_issuer_key(issuer: str, header: dict[str, Any], fetcher: Fetcher) -> ResolvedKey:
    """The public key that must have signed a credential of ``issuer``."""
    kid = header.get("kid") if isinstance(header.get("kid"), str) else None
    if issuer.startswith("did:"):
        if kid and kid.startswith("did:") and kid.split("#", 1)[0] != issuer:
            raise KeyResolutionError("the key id belongs to a different DID than the issuer")
        if issuer.startswith("did:key:"):
            jwk = ed25519_jwk_from_multibase(issuer.split(":", 2)[2].split("#", 1)[0])
            return ResolvedKey(jwk=jwk, alg="EdDSA", source="did:key")
        if issuer.startswith("did:web:"):
            return _from_did_web(issuer, kid, fetcher)
        raise KeyResolutionError("unsupported DID method")
    if urlsplit(issuer).scheme == "https":
        return _from_jwks(issuer, header, fetcher)
    raise KeyResolutionError("the issuer has no resolvable key")
