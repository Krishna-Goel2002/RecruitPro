"""Open Badges verifier: OB 2.0 hosted assertions and OB 3.0 VC-JWTs.

Credential check (one per request):
* OB 3.0 VC-JWT: header ``alg`` must be EdDSA, ES256 or RS256 and match the issuer key type; the key comes from the
  issuer (``app.badges.keys``: did:key, did:web, JWKS); the signature is verified with PyJWT. Expiry (``exp``,
  ``validUntil``/``expirationDate``), not-before (``nbf``, ``validFrom``/``issuanceDate``) and revocation through a
  simple status list (``app.badges.status``; other status types -> warning) are checked.
* OB 2.0 hosted: the assertion is (re)fetched from its ``id``; ``verification.type`` must be hosted; the badge class and
  issuer profile are fetched when given as URLs; the assertion must live on the issuer's origin (or an
  ``allowedOrigins`` entry); ``revoked`` / HTTP 410 means revoked; ``expires`` is checked.
* JSON credentials with a Data Integrity ``proof`` (or OB 3.0 JSON without proof) -> ``NOT_CHECKABLE``
  ("proof type not supported yet"); signed OB 2.0 assertions -> ``NOT_CHECKABLE``.

Recipient: e-mail identities (hashed ``sha256$``/``md5$`` with salt, or plain) against ``holderEmail``; name identities
against ``holderName``. ``ownershipProven`` = recipient matched. A comparable identity that does not match is a wrong
recipient.

Issuer trust (``app.trust.assess_issuer_domain``) for a valid credential whose issuer is bound to a domain: the
organisation's list or the built-in list of well-known issuers (``app/data/trusted_issuers.json``) -> trusted;
an issuer domain equal to (or under) a website the candidate proved ownership of -> untrusted (self-issued); else the
domain must have been registered >= 365 days ago (RDAP; unreachable -> not trusted). An untrusted issuer caps the
verdict at ``PARTLY_VERIFIED`` with note ``ISSUER_UNTRUSTED`` (``app.verdicts.finalize``).

Per claim (only claims whose value matches the achievement by taxonomy skill or token overlap are judged; others are
``NOT_FOUND``): checked, valid, recipient matched, issuer bound to a domain (did:web, JWKS on the issuer's origin,
OB 2.0 hosted) and trusted -> ``VERIFIED``; a ``did:key`` issuer (self-certifying, anyone can mint one) -> at most
``PARTLY_VERIFIED`` with note ``ISSUER_NOT_DOMAIN_BOUND``; expired or not yet valid -> ``PARTLY_VERIFIED``;
wrong recipient, invalid signature, revoked or hosted-origin mismatch -> ``CONTRADICTED``; unsupported proof or
unresolvable key -> ``NOT_CHECKABLE``. Recipient not comparable -> ownership unproven (capped at PARTLY_VERIFIED).
"""
from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

import jwt
from jwt.exceptions import InvalidSignatureError, InvalidTokenError, PyJWKError

from app.badges.keys import KeyResolutionError, ResolvedKey, b64url_decode, resolve_issuer_key
from app.badges.status import check_status
from app.core.budget import Budget
from app.core.clock import iso_z, parse_instant
from app.core.config import Settings
from app.deidentify import Identities
from app.evidence import make_item
from app.fetch.client import DATA_TYPES, Fetcher, origin
from app.fetch.ssrf import FetchError
from app.models import BadgeVerifyRequest, ClaimIn, EvidenceItem, IntegrityNote, Reason, Usage
from app.network import Network
from app.skills import covers, resolve_claim_skill, skills_in_text
from app.trust import DomainTrust, assess_issuer_domain, issuer_domain
from app.verdicts import DraftVerdict, VerifierOutput
from app.verifiers.common import significant_tokens, tokens

SUPPORTED_ALGS = {"EdDSA", "ES256", "RS256"}
CLOCK_SKEW = timedelta(minutes=5)
DOC_MAX_BYTES = 512 * 1024
TRUST_REQUESTS = 6  # RDAP bootstrap + up to 3 registry queries
ISSUER_KEY = "issuer-domain"


class BadgeInputError(ValueError):
    """Neither a usable credential nor a URL was given (HTTP 422)."""


@dataclass
class BadgeCheck:
    status: str = "VALID"  # VALID | INVALID | UNCHECKABLE
    status_reason: str = ""
    format: str = "unknown"
    expired: bool = False
    not_yet_valid: bool = False
    revoked: bool = False
    recipient: str = "not_comparable"  # matched | mismatch | not_comparable
    achievement_name: str = ""
    achievement_text: str = ""
    issuer_name: str = ""
    issuer_id: str = ""
    notes: list[IntegrityNote] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    evidence: list[EvidenceItem] = field(default_factory=list)
    attributes: dict[str, Any] = field(default_factory=dict)


def _dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _canonical(value: Any) -> str:
    return json.dumps(value, indent=1, sort_keys=True, ensure_ascii=False)


def _hash_matches(identity: str, salt: str, value: str) -> bool:
    if "$" not in identity:
        return False
    algorithm, digest = identity.split("$", 1)
    algorithm = algorithm.lower()
    if algorithm not in {"sha256", "md5"}:
        return False
    computed = hashlib.new(algorithm, (value + salt).encode("utf-8")).hexdigest()
    return hmac.compare_digest(computed.lower(), digest.strip().lower())


def _match_identity(kind: str, identity: str, hashed: bool, salt: str, request: BadgeVerifyRequest) -> str | None:
    """matched / mismatch for a comparable identity; None when the holder data cannot be compared."""
    kind = kind.lower()
    if kind in {"email", "emailaddress"}:
        if not request.holder_email:
            return None
        email = request.holder_email.strip().lower()
        ok = _hash_matches(identity, salt, email) if hashed else identity.strip().lower() == email
        return "matched" if ok else "mismatch"
    if kind == "name":
        if not request.holder_name:
            return None
        name = request.holder_name.strip()
        if hashed:
            ok = _hash_matches(identity, salt, name) or _hash_matches(identity, salt, name.lower())
        else:
            ok = set(tokens(identity)) == set(tokens(name))
        return "matched" if ok else "mismatch"
    return None


def _combine(results: list[str | None]) -> str:
    if "matched" in results:
        return "matched"
    if "mismatch" in results:
        return "mismatch"
    return "not_comparable"


# ------------------------------------------------------------------------------------------------- OB 3.0
def _check_vc_jwt(token: str, request: BadgeVerifyRequest, fetcher: Fetcher, now: datetime, check: BadgeCheck) -> None:
    check.format = "OB3_VC_JWT"
    try:
        header = jwt.get_unverified_header(token)
        payload = json.loads(b64url_decode(token.split(".")[1]))
    except (InvalidTokenError, ValueError, IndexError):
        check.status, check.status_reason = "INVALID", "the credential is not a well-formed JWT"
        return
    credential: dict[str, Any] = payload["vc"] if isinstance(payload.get("vc"), dict) else _dict(payload)
    issuer = credential.get("issuer")
    issuer_id = str(payload.get("iss") or (issuer.get("id") if isinstance(issuer, dict) else issuer) or "")
    check.issuer_id = issuer_id
    check.issuer_name = str(issuer.get("name", "")) if isinstance(issuer, dict) else ""
    subject = _dict(credential.get("credentialSubject"))
    achievement = _dict(subject.get("achievement"))
    check.achievement_name = str(achievement.get("name") or credential.get("name") or "")
    check.achievement_text = " ".join(str(part) for part in (achievement.get("name"), achievement.get("description"),
                                                             (achievement.get("criteria") or {}).get("narrative")
                                                             if isinstance(achievement.get("criteria"), dict) else None,
                                                             credential.get("name")) if part)
    item_text = "JWT header:\n" + _canonical(header) + "\n\nJWT payload:\n" + _canonical(payload)
    alg = header.get("alg")
    check.attributes.update({"alg": alg, "issuer": issuer_id, "kid": header.get("kid")})
    if alg not in SUPPORTED_ALGS:
        check.status, check.status_reason = "UNCHECKABLE", f"signature algorithm {alg!r} is not supported"
    elif payload.get("iss") and str((issuer.get("id") if isinstance(issuer, dict) else issuer) or "") not in \
            {"", str(payload["iss"])}:
        check.status, check.status_reason = "INVALID", "the JWT issuer differs from the credential issuer"
    else:
        try:
            key = resolve_issuer_key(issuer_id, header, fetcher)
        except KeyResolutionError as exc:
            check.status, check.status_reason = "UNCHECKABLE", f"the issuer key could not be resolved ({exc})"
            check.warnings.append("KEY_UNRESOLVED")
            key = None
        if key is not None:
            _verify_signature(token, key, alg, check)
            if key.document:
                check.evidence.append(make_item("issuer-key", "BADGE", url=key.url, title="Issuer key document",
                                                text=key.document, retrieved_at=now,
                                                attributes={"keySource": key.source}))
            if key.source == "did:key":
                check.notes.append(IntegrityNote(code="ISSUER_NOT_DOMAIN_BOUND", evidence_keys=["badge-1"], text=(
                    "The issuer is identified by a did:key, which proves the signature but is not tied to a "
                    "domain; the issuer's name is self-asserted, so the result is at most partly verified.")))
            if check.status == "VALID":
                def verify_list(list_token: str) -> dict[str, Any] | None:
                    try:
                        list_header = jwt.get_unverified_header(list_token)
                        list_key = resolve_issuer_key(issuer_id, list_header, fetcher)
                        return jwt.decode(list_token, jwt.PyJWK(list_key.jwk).key, algorithms=[list_key.alg],
                                          options={"verify_exp": False, "verify_aud": False, "verify_iat": False,
                                                   "verify_nbf": False})
                    except (KeyResolutionError, InvalidTokenError, PyJWKError, ValueError):
                        return None

                status = check_status(credential.get("credentialStatus"), fetcher, verify_list)
                check.attributes["revocation"] = status.state
                if status.warning:
                    check.warnings.append(status.warning)
                if status.state == "revoked":
                    check.revoked = True
                    check.status, check.status_reason = "INVALID", "the issuer has revoked the credential"
    expires = parse_instant(payload.get("exp")) or parse_instant(credential.get("validUntil")) \
        or parse_instant(credential.get("expirationDate"))
    starts = parse_instant(payload.get("nbf")) or parse_instant(credential.get("validFrom")) \
        or parse_instant(credential.get("issuanceDate"))
    check.expired = bool(expires and expires < now)
    check.not_yet_valid = bool(starts and starts > now + CLOCK_SKEW)
    check.attributes.update({"expiresAt": iso_z(expires) if expires else None,
                             "validFrom": iso_z(starts) if starts else None})
    results = []
    identifiers = subject.get("identifier")
    for identity in identifiers if isinstance(identifiers, list) else ([identifiers] if identifiers else []):
        if isinstance(identity, dict):
            results.append(_match_identity(str(identity.get("identityType", "")), str(identity.get("identityHash", "")),
                                           bool(identity.get("hashed")), str(identity.get("salt") or ""), request))
    if isinstance(subject.get("name"), str):
        results.append(_match_identity("name", subject["name"], False, "", request))
    if isinstance(subject.get("email"), str):
        results.append(_match_identity("email", subject["email"], False, "", request))
    check.recipient = _combine(results)
    check.evidence.insert(0, make_item("badge-1", "BADGE", url=request.url, title=check.achievement_name or "Credential",
                                       text=item_text, retrieved_at=now, attributes={"format": check.format}))


def _verify_signature(token: str, key: ResolvedKey, alg: str, check: BadgeCheck) -> None:
    check.attributes["keySource"] = key.source
    if key.alg != alg:
        check.status, check.status_reason = "INVALID", "the signature algorithm does not match the issuer key"
        return
    try:
        jwt.api_jws.PyJWS().decode_complete(token, key=jwt.PyJWK(key.jwk, algorithm=alg).key, algorithms=[alg])
        check.attributes["signatureValid"] = True
    except InvalidSignatureError:
        check.attributes["signatureValid"] = False
        check.status, check.status_reason = "INVALID", "the signature does not match the credential"
    except (InvalidTokenError, PyJWKError, ValueError) as exc:
        check.status, check.status_reason = "UNCHECKABLE", f"the signature could not be checked ({type(exc).__name__})"


# ------------------------------------------------------------------------------------------------- OB 2.0
def _fetch_json(fetcher: Fetcher, url: str) -> tuple[int, Any, str]:
    result = fetcher.fetch(url, accept=DATA_TYPES, max_bytes=DOC_MAX_BYTES, respect_robots=False)
    text = result.text()
    try:
        return result.status, json.loads(text) if result.ok else None, text
    except ValueError:
        return result.status, None, text


def _types(document: dict[str, Any]) -> set[str]:
    value = document.get("type") or document.get("@type")
    values = value if isinstance(value, list) else [value]
    return {str(item) for item in values if item}


def _check_ob2(assertion: dict[str, Any], fetched_from: str | None, request: BadgeVerifyRequest, fetcher: Fetcher,
               now: datetime, check: BadgeCheck) -> None:
    check.format = "OB2_HOSTED"
    verification = _dict(assertion.get("verification"))
    vtype = str(verification.get("type", "")).lower()
    assertion_id = str(assertion.get("id") or "")
    if vtype in {"signed", "signedbadge"}:
        check.format = "OB2_SIGNED"
        check.status, check.status_reason = "UNCHECKABLE", "signed Open Badges 2.0 assertions are not supported yet"
    elif vtype not in {"hosted", "hostedbadge"}:
        check.status, check.status_reason = "UNCHECKABLE", "the assertion has no hosted verification"
    authoritative = assertion
    if check.status == "VALID" and assertion_id and fetched_from != assertion_id:
        try:
            status, fetched, _ = _fetch_json(fetcher, assertion_id)
        except FetchError as exc:
            status, fetched = 0, None
            check.warnings.append(f"ASSERTION_FETCH_FAILED:{exc.code}")
        if status == 410:
            check.revoked = True
            check.status, check.status_reason = "INVALID", "the issuer has revoked the assertion"
        elif not isinstance(fetched, dict):
            check.status, check.status_reason = "UNCHECKABLE", "the hosted assertion could not be fetched from its id"
        else:
            authoritative = fetched
            if fetched.get("recipient") != assertion.get("recipient") or fetched.get("badge") != assertion.get("badge"):
                check.status, check.status_reason = "INVALID", "the pasted assertion differs from the hosted one"
    elif check.status == "VALID" and not assertion_id:
        check.status, check.status_reason = "UNCHECKABLE", "the assertion has no id to check"
    badge = authoritative.get("badge")
    badge_doc: dict[str, Any] = badge if isinstance(badge, dict) else {}
    if isinstance(badge, str):
        try:
            status, fetched, text = _fetch_json(fetcher, badge)
            if isinstance(fetched, dict):
                badge_doc = fetched
                check.evidence.append(make_item("badge-class", "BADGE", url=badge, title="Badge class", text=text,
                                                retrieved_at=now, attributes={"document": "BadgeClass"}))
        except FetchError as exc:
            check.warnings.append(f"BADGE_CLASS_FETCH_FAILED:{exc.code}")
    issuer = badge_doc.get("issuer")
    issuer_doc: dict[str, Any] = issuer if isinstance(issuer, dict) else {}
    if isinstance(issuer, str):
        try:
            status, fetched, text = _fetch_json(fetcher, issuer)
            if isinstance(fetched, dict):
                issuer_doc = fetched
                check.evidence.append(make_item("issuer", "BADGE", url=issuer, title="Issuer profile", text=text,
                                                retrieved_at=now, attributes={"document": "Profile"}))
        except FetchError as exc:
            check.warnings.append(f"ISSUER_FETCH_FAILED:{exc.code}")
    check.issuer_id = str(issuer_doc.get("id") or (issuer if isinstance(issuer, str) else "") or "")
    check.issuer_name = str(issuer_doc.get("name") or "")
    check.achievement_name = str(badge_doc.get("name") or "")
    criteria = badge_doc.get("criteria")
    check.achievement_text = " ".join(str(part) for part in (badge_doc.get("name"), badge_doc.get("description"),
                                                             criteria.get("narrative") if isinstance(criteria, dict)
                                                             else None) if part)
    if check.status == "VALID":
        allowed = _dict(issuer_doc.get("verification")).get("allowedOrigins")
        allowed_hosts = {str(host).lower() for host in (allowed if isinstance(allowed, list) else [allowed] if allowed else [])}
        if not check.issuer_id:
            check.status, check.status_reason = "UNCHECKABLE", "the issuer profile could not be read"
        elif origin(assertion_id) != origin(check.issuer_id) and \
                (origin(assertion_id).split("://", 1)[-1] not in allowed_hosts):
            check.status, check.status_reason = "INVALID", "the assertion is not hosted on the issuer's domain"
        elif authoritative.get("revoked") is True:
            check.revoked = True
            check.status, check.status_reason = "INVALID", "the issuer has revoked the assertion"
    expires = parse_instant(authoritative.get("expires"))
    check.expired = bool(expires and expires < now)
    issued = parse_instant(authoritative.get("issuedOn"))
    check.not_yet_valid = bool(issued and issued > now + CLOCK_SKEW)
    recipient = _dict(authoritative.get("recipient"))
    check.recipient = _combine([_match_identity(str(recipient.get("type", "")), str(recipient.get("identity", "")),
                                                bool(recipient.get("hashed")), str(recipient.get("salt") or ""),
                                                request)])
    check.attributes.update({"issuer": check.issuer_id, "assertionId": assertion_id,
                             "expiresAt": iso_z(expires) if expires else None})
    check.evidence.insert(0, make_item("badge-1", "BADGE", url=assertion_id or request.url,
                                       title=check.achievement_name or "Badge assertion", text=_canonical(authoritative),
                                       retrieved_at=now, attributes={"format": check.format}))


# ---------------------------------------------------------------------------------------------- dispatch
def _is_jwt(text: str) -> bool:
    parts = text.split(".")
    return len(parts) == 3 and all(part and all(c.isalnum() or c in "-_" for c in part) for part in parts[:2])


def _check_json(document: dict[str, Any], fetched_from: str | None, request: BadgeVerifyRequest, fetcher: Fetcher,
                now: datetime, check: BadgeCheck) -> None:
    types = _types(document)
    if "proof" in document:
        check.format = "VC_DATA_INTEGRITY"
        check.status, check.status_reason = "UNCHECKABLE", "proof type not supported yet"
    elif "Assertion" in types or "verification" in document:
        _check_ob2(document, fetched_from, request, fetcher, now, check)
        return
    else:
        check.format = "VC_UNSIGNED" if "VerifiableCredential" in types else "unknown"
        check.status, check.status_reason = "UNCHECKABLE", ("the credential carries no proof" if check.format ==
                                                            "VC_UNSIGNED" else "the document is not a badge assertion")
    subject = _dict(document.get("credentialSubject"))
    achievement = _dict(subject.get("achievement"))
    check.achievement_name = str(achievement.get("name") or document.get("name") or "")
    check.achievement_text = " ".join(str(part) for part in (achievement.get("name"), achievement.get("description"),
                                                             document.get("name")) if part)
    check.evidence.insert(0, make_item("badge-1", "BADGE", url=fetched_from or request.url,
                                       title=check.achievement_name or "Credential", text=_canonical(document),
                                       retrieved_at=now, attributes={"format": check.format}))


def run_check(request: BadgeVerifyRequest, fetcher: Fetcher, now: datetime) -> BadgeCheck:
    check = BadgeCheck()
    credential = request.credential
    if isinstance(credential, str):
        text = credential.strip()
        if _is_jwt(text):
            _check_vc_jwt(text, request, fetcher, now, check)
            return check
        try:
            credential = json.loads(text)
        except ValueError as exc:
            raise BadgeInputError("the credential is neither a JWT nor JSON") from exc
    if isinstance(credential, dict):
        _check_json(credential, None, request, fetcher, now, check)
        return check
    if not request.url:
        raise BadgeInputError("a credential or a url is required")
    result = fetcher.fetch(request.url, accept=DATA_TYPES, max_bytes=DOC_MAX_BYTES, respect_robots=False)
    if result.status == 410:
        check.format, check.revoked = "OB2_HOSTED", True
        check.status, check.status_reason = "INVALID", "the issuer has revoked the assertion"
        check.evidence.append(make_item("badge-1", "BADGE", url=request.url, title="Revoked assertion",
                                        text=f"HTTP {result.status}", retrieved_at=now))
        return check
    if not result.ok:
        raise FetchError("HTTP_ERROR", f"the badge URL answered {result.status}")
    text = result.text().strip()
    if _is_jwt(text):
        _check_vc_jwt(text, request, fetcher, now, check)
        return check
    try:
        document = json.loads(text)
    except ValueError:
        document = None
    if not isinstance(document, dict):
        check.status, check.status_reason = "UNCHECKABLE", "the URL does not serve a badge assertion"
        check.evidence.append(make_item("badge-1", "BADGE", url=request.url, title="Badge URL", text=text[:5000],
                                        retrieved_at=now))
        return check
    _check_json(document, result.url, request, fetcher, now, check)
    return check


def achievement_matches(claim: ClaimIn, check: BadgeCheck) -> bool:
    text = check.achievement_text or check.achievement_name
    if not text:
        return False
    skill = resolve_claim_skill(claim.value)
    if skill is not None and any(covers(skill.id, shown) for shown in skills_in_text(text)):
        return True
    wanted = significant_tokens(claim.value)
    name_tokens = significant_tokens(check.achievement_name)
    available = set(tokens(text))
    if wanted and all(token in available for token in wanted):
        return True
    if len(name_tokens) >= 2 and set(name_tokens) <= set(wanted):
        return True
    return bool(wanted and name_tokens) and len(set(wanted) & set(name_tokens)) / len(set(wanted)) >= 0.6


def _draft(claim: ClaimIn, check: BadgeCheck, owned: bool, trust: DomainTrust | None = None) -> DraftVerdict:
    quote = check.achievement_name or None
    keys = ["badge-1"]
    if not achievement_matches(claim, check):
        return DraftVerdict(claim, "NOT_FOUND", [Reason(
            text="The credential's achievement does not match this claim.", evidence_keys=keys, quote=quote)])
    if check.status == "UNCHECKABLE":
        return DraftVerdict(claim, "NOT_CHECKABLE", [Reason(
            text=f"The credential could not be checked: {check.status_reason}.", evidence_keys=keys, quote=quote)])
    if check.status == "INVALID":
        return DraftVerdict(claim, "CONTRADICTED", [Reason(
            text=f"The credential does not check out: {check.status_reason}.", evidence_keys=keys, quote=quote)])
    if check.recipient == "mismatch":
        return DraftVerdict(claim, "CONTRADICTED", [Reason(
            text="The credential was issued to a different recipient than the candidate.", evidence_keys=keys,
            quote=quote)])
    if check.expired or check.not_yet_valid:
        state = "has expired" if check.expired else "is not valid yet"
        return DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
            text=f"The credential checks out and matches the claim, but it {state}.", evidence_keys=keys,
            quote=quote)], trust=trust)
    if check.attributes.get("keySource") == "did:key":
        return DraftVerdict(claim, "PARTLY_VERIFIED", [Reason(
            text="The signature checks out and the achievement matches the claim, but the issuer is a did:key that "
                 "anyone can create, so the issuer's identity is not established.", evidence_keys=keys, quote=quote)])
    reasons = [Reason(text=("The issuer's signature checks out" if check.format == "OB3_VC_JWT" else
                            "The hosted assertion checks out on the issuer's domain")
                           + " and the achievement matches the claim.", evidence_keys=keys, quote=quote)]
    if check.recipient == "matched":
        reasons.append(Reason(text="The recipient identity matches the candidate.", evidence_keys=keys, quote=None))
    else:
        reasons.append(Reason(text="The recipient could not be compared with the candidate's details.",
                              evidence_keys=keys, quote=None))
    if trust is not None and trust.trusted:
        reasons.append(Reason(text=("The issuer is trusted by the organisation." if trust.trusted_by == "ORGANIZATION"
                                    else "The issuer's domain is an established issuer."),
                              evidence_keys=[ISSUER_KEY], quote=None))
    return DraftVerdict(claim, "VERIFIED", reasons, trust=trust)


def check_issuer_trust(request: BadgeVerifyRequest, check: BadgeCheck, *, settings: Settings, network: Network,
                       now: datetime, budget: Budget) -> DomainTrust | None:
    """Issuer trust for a valid credential whose issuer is bound to a domain (did:key issuers stay capped by their own
    rule); None when it does not apply. Adds the evidence item and the ISSUER_UNTRUSTED note."""
    if check.status != "VALID" or check.attributes.get("keySource") == "did:key":
        return None
    domain = issuer_domain(check.issuer_id)
    if not domain:
        return None
    trust = assess_issuer_domain(domain, now=now, fetcher=network.fetcher(settings, budget), cache=network.rdap_cache,
                                 org_trusted=request.trusted_issuer_domains,
                                 candidate_domains=request.candidate_domains)
    if check.issuer_name:
        trust.snapshot.insert(1, f"Issuer name (self-declared): {check.issuer_name}")
    check.evidence.append(make_item(ISSUER_KEY, "BADGE", url=None, title="Issuer domain check",
                                    text="\n".join(trust.snapshot), retrieved_at=now,
                                    attributes={"check": trust.check, **trust.signals}))
    if not trust.trusted:
        check.notes.append(IntegrityNote(code="ISSUER_UNTRUSTED", evidence_keys=[ISSUER_KEY], text=(
            "The badge issuer is not established as a trusted issuer ("
            + ("untrusted" if trust.status == "UNTRUSTED" else "weak") + "): " + " ".join(trust.reasons)
            + " The result is at most partly verified unless the organisation trusts this issuer.")))
    return trust


def build_output(request: BadgeVerifyRequest, check: BadgeCheck, *, budget: Budget, demo: bool = False,
                 trust: DomainTrust | None = None, extra_requests: int = 0) -> VerifierOutput:
    owned = check.recipient == "matched"
    for item in check.evidence:
        item.attributes.update({"status": check.status, "recipient": check.recipient, "expired": check.expired,
                                "revoked": check.revoked, **({"demoFixture": True, "synthetic": True} if demo else {})})
        if item.key == "badge-1":
            item.attributes.update(check.attributes)
    if check.recipient == "not_comparable":
        check.warnings.append("RECIPIENT_NOT_COMPARABLE")
    if check.status != "VALID":
        check.notes.append(IntegrityNote(code="CREDENTIAL_" + check.status, evidence_keys=["badge-1"],
                                         text=f"Credential check: {check.status_reason}."))
    drafts = [_draft(claim, check, owned, trust) for claim in request.claims]
    names = [request.holder_name] if request.holder_name else []
    identities = Identities(names=names)
    warnings = check.warnings + (["DEMO_FIXTURES: synthetic recorded data"] if demo else [])
    return VerifierOutput(verifier="badge", evidence=check.evidence, drafts=drafts, ownership_proven=owned,
                          identities=identities, notes=check.notes, warnings=warnings,
                          usage=Usage(requests=budget.requests + extra_requests, truncated=budget.truncated))


def verify_badge(request: BadgeVerifyRequest, *, settings: Settings, network: Network, now: datetime) -> VerifierOutput:
    budget = Budget(20, settings.request_deadline_seconds)
    fetcher = network.fetcher(settings, budget)
    check = run_check(request, fetcher, now)
    trust_budget = Budget(TRUST_REQUESTS, settings.request_deadline_seconds)
    trust = check_issuer_trust(request, check, settings=settings, network=network, now=now, budget=trust_budget)
    return build_output(request, check, budget=budget, demo=network.demo, trust=trust,
                        extra_requests=trust_budget.requests)
