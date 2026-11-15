"""Builders for SYNTHETIC test and evaluation inputs: small documents, signed test badges (keys generated at run
time), status lists and network wiring around the fake upstreams of ``app.demo.fakes``. Shared by tests and eval."""
from __future__ import annotations

import base64
import gzip
import hashlib
import io
import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, rsa
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.badges.keys import b64url_encode, did_key_from_ed25519
from app.demo.fakes import FakeAccount, FakeCommit, FakePage, FakeRepo, fixed_resolver, github_handler, \
    gitlab_handler, web_handler
from app.network import Network

NOW = datetime(2026, 11, 15, 12, 0, tzinfo=timezone.utc)
PUBLIC_IP = "93.184.216.34"


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def days_ago(days: float) -> str:
    return iso(NOW - timedelta(days=days))


# ----------------------------------------------------------------------------------------------- network
@dataclass
class FakeNet:
    accounts: list[FakeAccount] = field(default_factory=list)
    pages: dict[str, FakePage] = field(default_factory=dict)
    hosts: dict[str, list[str]] = field(default_factory=dict)  # host -> addresses (default: PUBLIC_IP)
    txt: dict[str, list[str]] = field(default_factory=dict)
    gitlab: bool = False
    calls: list[str] = field(default_factory=list)

    def network(self) -> Network:
        api = gitlab_handler(self.accounts, self.calls) if self.gitlab else github_handler(self.accounts, self.calls)
        return Network(resolver=fixed_resolver(self.hosts, [PUBLIC_IP]),
                       transport_factory=lambda: httpx.MockTransport(web_handler(self.pages, self.calls)),
                       api_transport=httpx.MockTransport(api), txt_resolver=lambda host: list(self.txt.get(host, [])))


def commits(login: str | None, name: str, dates: list[str], committed: str | None = None) -> list[FakeCommit]:
    return [FakeCommit(login, name, date, committed) for date in dates]


def monthly(start_days_ago: int, count: int, step: int = 30) -> list[str]:
    return [days_ago(start_days_ago - index * step) for index in range(count)]


def repo(name: str, *, languages: dict[str, int] | None = None, topics: list[str] | None = None,
         files: dict[str, str] | None = None, user: str = "dev", user_dates: list[str] | None = None,
         others: int = 0, created_days_ago: int = 400, fork: bool = False, stats: bool = True,
         unlinked: bool = False, name_on_commits: str = "Dev Person") -> FakeRepo:
    dates = user_dates if user_dates is not None else monthly(300, 6)
    login = None if unlinked else user
    history = commits(login, name_on_commits, dates) + commits("someone-else", "Someone Else",
                                                               [days_ago(200)] * others)
    contributors = [(user, 0 if unlinked else len(dates))] + ([("someone-else", others)] if others else [])
    return FakeRepo(name=name, languages=languages or {}, topics=topics or [], files=files or {}, commits=history,
                    created_at=days_ago(created_days_ago), pushed_at=days_ago(10), fork=fork,
                    stats=contributors if stats else None)


def package_json(dependencies: dict[str, str]) -> str:
    return json.dumps({"name": "synthetic", "dependencies": dependencies}, indent=2)


# --------------------------------------------------------------------------------------------- documents
def _pdf_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(lines: list[str], *, author: str | None = None, created: str = "D:20240101120000Z") -> bytes:
    """A minimal one-page PDF (Helvetica text) with an Info dictionary."""
    stream = "BT /F1 11 Tf 50 750 Td 14 TL " + " ".join(f"({_pdf_escape(line)}) Tj T*" for line in lines) + " ET"
    info = f"<< /Producer (Synthetic test generator) /CreationDate ({created})" + \
           (f" /Author ({_pdf_escape(author)})" if author else "") + " >>"
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(stream.encode('latin-1'))} >>\nstream\n{stream}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        info,
    ]
    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{number} 0 obj\n{body}\nendobj\n".encode("latin-1"))
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets:
        out.write(f"{offset:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def make_docx(lines: list[str], *, author: str | None = None, created: datetime | None = None) -> bytes:
    import docx

    document = docx.Document()
    for line in lines:
        document.add_paragraph(line)
    document.core_properties.author = author or ""
    document.core_properties.last_modified_by = author or ""
    if created:
        document.core_properties.created = created.replace(tzinfo=None)
    buffer = io.BytesIO()
    document.save(buffer)
    return buffer.getvalue()


def make_pptx(lines: list[str], *, author: str | None = None) -> bytes:
    from pptx import Presentation
    from pptx.util import Inches

    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[5])
    box = slide.shapes.add_textbox(Inches(1), Inches(2), Inches(6), Inches(3))
    box.text_frame.text = "\n".join(lines)
    presentation.core_properties.author = author or ""
    buffer = io.BytesIO()
    presentation.save(buffer)
    return buffer.getvalue()


# ------------------------------------------------------------------------------------------------ badges
@dataclass
class IssuerKey:
    private: Any
    alg: str
    jwk: dict[str, Any]
    kid: str | None = None


def ed25519_key() -> IssuerKey:
    private = ed25519.Ed25519PrivateKey.generate()
    raw = private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return IssuerKey(private, "EdDSA", {"kty": "OKP", "crv": "Ed25519", "x": b64url_encode(raw)})


def es256_key() -> IssuerKey:
    private = ec.generate_private_key(ec.SECP256R1())
    numbers = private.public_key().public_numbers()
    return IssuerKey(private, "ES256", {"kty": "EC", "crv": "P-256", "x": b64url_encode(numbers.x.to_bytes(32, "big")),
                                        "y": b64url_encode(numbers.y.to_bytes(32, "big"))})


def rs256_key() -> IssuerKey:
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    numbers = private.public_key().public_numbers()
    return IssuerKey(private, "RS256", {"kty": "RSA", "n": b64url_encode(numbers.n.to_bytes(256, "big")),
                                        "e": b64url_encode(numbers.e.to_bytes(3, "big"))})


def did_key_of(key: IssuerKey) -> str:
    raw = key.private.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return did_key_from_ed25519(raw)


def email_identity(email: str, salt: str = "s4lt") -> dict[str, Any]:
    return {"type": "IdentityObject", "identityType": "emailAddress", "hashed": True, "salt": salt,
            "identityHash": "sha256$" + hashlib.sha256((email + salt).encode()).hexdigest()}


def ob3_payload(issuer: str, achievement: str, *, email: str = "holder@example.org", expires: datetime | None = None,
                status: dict[str, Any] | None = None, description: str = "Synthetic test achievement.") -> dict[str, Any]:
    credential: dict[str, Any] = {
        "@context": ["https://www.w3.org/ns/credentials/v2"], "type": ["VerifiableCredential", "OpenBadgeCredential"],
        "issuer": {"id": issuer, "name": "Synthetic Test Issuer"}, "validFrom": iso(NOW - timedelta(days=100)),
        "credentialSubject": {"type": ["AchievementSubject"], "identifier": [email_identity(email)],
                              "achievement": {"type": ["Achievement"], "name": achievement,
                                              "description": description}}}
    if status:
        credential["credentialStatus"] = status
    payload: dict[str, Any] = {"iss": issuer, "nbf": int((NOW - timedelta(days=100)).timestamp()), "vc": credential}
    if expires:
        payload["exp"] = int(expires.timestamp())
    return payload


def sign(payload: dict[str, Any], key: IssuerKey, kid: str | None = None, extra: dict[str, Any] | None = None) -> str:
    headers = {"typ": "JWT", **({"kid": kid} if kid else {}), **(extra or {})}
    return jwt.encode(payload, key.private, algorithm=key.alg, headers=headers)


def tamper(token: str) -> str:
    header, payload, signature = token.split(".")
    data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    data["vc"]["credentialSubject"]["achievement"]["name"] += " Advanced"
    return f"{header}.{b64url_encode(json.dumps(data).encode())}.{signature}"


def status_list(revoked_indexes: set[int], size: int = 131072) -> str:
    bits = bytearray(size // 8)
    for index in revoked_indexes:
        bits[index // 8] |= 0x80 >> (index % 8)
    return "u" + b64url_encode(gzip.compress(bytes(bits)))


def ob2_documents(base: str, *, badge_name: str, email: str = "holder@example.org", salt: str = "pepper",
                  assertion_host: str | None = None, revoked: bool = False,
                  expires: str | None = None) -> tuple[str, dict[str, FakePage]]:
    """(assertion URL, pages) for an OB 2.0 hosted badge issued by ``base``."""
    host = assertion_host or base
    assertion_url = f"{host}/assertions/1"
    assertion = {"@context": "https://w3id.org/openbadges/v2", "type": "Assertion", "id": assertion_url,
                 "recipient": {"type": "email", "hashed": True, "salt": salt,
                               "identity": "sha256$" + hashlib.sha256((email + salt).encode()).hexdigest()},
                 "badge": f"{base}/badges/1", "verification": {"type": "hosted"}, "issuedOn": "2025-01-01T00:00:00Z"}
    if revoked:
        assertion["revoked"] = True
    if expires:
        assertion["expires"] = expires
    badge = {"type": "BadgeClass", "id": f"{base}/badges/1", "name": badge_name,
             "description": f"Synthetic test badge: {badge_name}.", "issuer": f"{base}/issuer"}
    issuer = {"type": "Issuer", "id": f"{base}/issuer", "name": "Synthetic Badge Issuer"}
    kind = "application/json"
    return assertion_url, {assertion_url: FakePage(json.dumps(assertion), kind),
                           f"{base}/badges/1": FakePage(json.dumps(badge), kind),
                           f"{base}/issuer": FakePage(json.dumps(issuer), kind)}
