"""SYNTHETIC demo fixtures for offline demos (``AGENT_DEMO_FIXTURES=true``).

* GitHub ``demo-asha`` (strong, multi-repository history), ``demo-ravi`` (forks only and a bulk-history repo);
  GitLab ``demo-asha`` (one project).
* Website ``https://demo-portfolio.example`` (JSON-LD Person, about page, blog post with byline, well-known file).
* Badges on ``https://demo-badges.example``: an OB 2.0 hosted assertion and an OB 3.0 VC-JWT (issuer
  ``did:web:demo-badges.example``) signed with a key derived from a public, fixed seed (demo only; it proves nothing
  about any real issuer).
* DNS TXT for any ``demo-`` host returns ``recruitpro-verification=<the requested code>``; profile bios and the
  well-known file echo the requested code too, so every demo ownership check succeeds.

All names, sites and credentials are invented and labelled "synthetic demo" in their content.
"""
from __future__ import annotations

import hashlib
import json

import httpx

from app.demo.fakes import (FakeAccount, FakeCommit, FakePage, FakeRepo, fixed_resolver, github_handler,
                            gitlab_handler, web_handler)
from app.network import Network

DEMO_ADDRESS = "100.0.0.1"  # never contacted: demo transports are MockTransports
DEMO_SITE = "https://demo-portfolio.example"
DEMO_BADGES = "https://demo-badges.example"
DEMO_DID = "did:web:demo-badges.example"
DEMO_HOLDER_EMAIL = "asha.demo@example.com"
DEMO_HOLDER_NAME = "Asha Synthetica"
DEMO_SALT = "demo-salt-01"
LABEL = "Synthetic demo account (RecruitPro demo fixture, not a real person)."


def _commits(login: str, name: str, dates: list[str]) -> list[FakeCommit]:
    return [FakeCommit(login, name, date) for date in dates]


def _monthly(year: int, months: range, day: int = 12) -> list[str]:
    return [f"{year}-{month:02d}-{day:02d}T10:00:00Z" for month in months]


def demo_accounts(code: str = "") -> list[FakeAccount]:
    bio = f"{LABEL} {code}".strip()
    asha = FakeAccount(login="demo-asha", name=DEMO_HOLDER_NAME, bio=bio, created_at="2018-05-02T08:00:00Z", repos=[
        FakeRepo(name="react-dashboard", languages={"TypeScript": 82000, "CSS": 9000, "HTML": 3000},
                 topics=["react", "dashboard"],
                 files={"README.md": "# React dashboard\nSynthetic demo project: a small analytics dashboard.\n",
                        "package.json": json.dumps({"name": "react-dashboard", "dependencies": {
                            "react": "^18.3.1", "react-dom": "^18.3.1", "redux": "^5.0.1"},
                            "devDependencies": {"typescript": "^5.4.0", "vite": "^5.2.0", "jest": "^29.7.0"}},
                            indent=2)},
                 commits=_commits("demo-asha", DEMO_HOLDER_NAME, _monthly(2025, range(1, 9))),
                 created_at="2024-12-20T09:00:00Z", pushed_at="2025-08-12T10:00:00Z",
                 stats=[("demo-asha", 40), ("demo-friend", 6)]),
        FakeRepo(name="ui-kit", languages={"TypeScript": 41000, "SCSS": 6000}, topics=["react", "design-system"],
                 files={"README.md": "# UI kit\nSynthetic demo component library.\n",
                        "package.json": json.dumps({"name": "ui-kit", "peerDependencies": {"react": ">=18"},
                                                    "devDependencies": {"typescript": "^5.4.0", "sass": "^1.77.0"}},
                                                   indent=2)},
                 commits=_commits("demo-asha", DEMO_HOLDER_NAME, _monthly(2024, range(3, 11))),
                 created_at="2024-02-01T09:00:00Z", pushed_at="2024-10-12T10:00:00Z", stats=[("demo-asha", 25)]),
        FakeRepo(name="data-pipeline", languages={"Python": 56000, "Dockerfile": 900}, topics=["etl"],
                 files={"README.md": "# Data pipeline\nSynthetic demo ETL jobs.\n",
                        "requirements.txt": "pandas==2.2.2\nfastapi==0.111.0\nsqlalchemy==2.0.30\npytest==8.2.0\n"},
                 commits=_commits("demo-asha", DEMO_HOLDER_NAME, _monthly(2025, range(2, 4))),
                 created_at="2025-01-15T09:00:00Z", pushed_at="2025-03-12T10:00:00Z", stats=[("demo-asha", 2)]),
        FakeRepo(name="awesome-list", languages={"Markdown": 1000}, fork=True,
                 commits=[FakeCommit("upstream-dev", "Upstream Dev", "2023-01-01T00:00:00Z")],
                 created_at="2024-01-01T00:00:00Z"),
    ])
    ravi = FakeAccount(login="demo-ravi", name="Ravi Synthetica", bio=f"{LABEL} {code}".strip(), repos=[
        FakeRepo(name="spring-shop", languages={"Java": 90000},
                 files={"README.md": "# Spring shop\nSynthetic demo.\n",
                        "pom.xml": "<project><dependencies><dependency><groupId>org.springframework.boot</groupId>"
                                   "<artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>"
                                   "</project>"},
                 commits=_commits("demo-ravi", "Ravi Synthetica", ["2025-11-10T10:00:00Z"] * 30),
                 created_at="2025-11-10T09:00:00Z", pushed_at="2025-11-10T12:00:00Z", stats=[("demo-ravi", 30)]),
        FakeRepo(name="tensorflow", languages={"Python": 900000}, fork=True,
                 commits=[FakeCommit("tf-maintainer", "TF Maintainer", "2024-01-01T00:00:00Z")],
                 created_at="2024-06-01T00:00:00Z"),
    ])
    return [asha, ravi]


_HOME = f"""<!doctype html><html><head><title>Asha Synthetica - portfolio (synthetic demo)</title>
<meta name="author" content="{DEMO_HOLDER_NAME}">
<script type="application/ld+json">{json.dumps({"@context": "https://schema.org", "@type": "Person",
                                                "name": DEMO_HOLDER_NAME, "jobTitle": "Frontend Engineer",
                                                "knowsAbout": ["React", "TypeScript", "Accessibility"],
                                                "alumniOf": {"@type": "CollegeOrUniversity",
                                                             "name": "Demo Institute of Technology"}})}</script>
</head><body><header><nav><a href="/about">About</a> <a href="/blog/accessible-forms">Blog</a></nav></header>
<main><h1>{DEMO_HOLDER_NAME}</h1><p>Synthetic demo page for RecruitPro. Frontend engineer working with React and
TypeScript.</p></main></body></html>"""
_ABOUT = f"""<!doctype html><html><head><title>About - synthetic demo</title>
<meta name="author" content="{DEMO_HOLDER_NAME}"></head><body><main><h1>About</h1>
<p>Synthetic demo page.</p><ul><li>B.Tech in Computer Science, Demo Institute of Technology, 2019</li>
<li>Frontend Engineer at Demo Analytics since 2021</li><li>Certified Kubernetes Application Developer (CKAD)</li>
</ul></main></body></html>"""
_POST = f"""<!doctype html><html><head><title>Accessible forms in React (synthetic demo)</title>
<script type="application/ld+json">{json.dumps({"@context": "https://schema.org", "@type": "BlogPosting",
                                                "headline": "Accessible forms in React",
                                                "author": {"@type": "Person", "name": DEMO_HOLDER_NAME},
                                                "datePublished": "2025-04-02"})}</script></head>
<body><article><p class="byline">By {DEMO_HOLDER_NAME}</p><p>Synthetic demo post about accessible React forms with
TypeScript and testing-library.</p></article></body></html>"""


def demo_pages() -> dict[str, FakePage]:
    return {
        f"{DEMO_SITE}/robots.txt": FakePage("User-agent: *\nAllow: /\n", "text/plain"),
        f"{DEMO_SITE}/": FakePage(_HOME),
        f"{DEMO_SITE}/about": FakePage(_ABOUT),
        f"{DEMO_SITE}/blog/accessible-forms": FakePage(_POST),
        f"{DEMO_SITE}/.well-known/recruitpro-verification.txt": FakePage("{{code}}\n", "text/plain"),
        f"{DEMO_SITE}/verify": FakePage("<html><body><p>recruitpro-verification: {{code}}</p></body></html>"),
        **demo_badge_pages(),
    }


def demo_issuer_key():  # -> Ed25519PrivateKey
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

    return Ed25519PrivateKey.from_private_bytes(hashlib.sha256(b"recruitpro-synthetic-demo-issuer").digest())


def demo_vc_jwt() -> str:
    import jwt

    key = demo_issuer_key()
    did = DEMO_DID
    identity = "sha256$" + hashlib.sha256((DEMO_HOLDER_EMAIL + DEMO_SALT).encode()).hexdigest()
    credential = {
        "@context": ["https://www.w3.org/ns/credentials/v2", "https://purl.imsglobal.org/spec/ob/v3p0/context-3.0.3.json"],
        "type": ["VerifiableCredential", "OpenBadgeCredential"], "issuer": {"id": did, "name": "Demo Academy (synthetic)"},
        "validFrom": "2025-02-01T00:00:00Z", "name": "Python Programming (synthetic demo badge)",
        "credentialSubject": {"type": ["AchievementSubject"], "identifier": [
            {"type": "IdentityObject", "identityType": "emailAddress", "hashed": True, "salt": DEMO_SALT,
             "identityHash": identity}],
            "achievement": {"id": f"{DEMO_BADGES}/achievements/python", "type": ["Achievement"],
                            "name": "Python Programming", "description": "Synthetic demo achievement: Python basics.",
                            "criteria": {"narrative": "Completed the synthetic demo Python course."}}},
    }
    return jwt.encode({"iss": did, "nbf": 1738368000, "jti": "urn:uuid:demo-1", "vc": credential}, key,
                      algorithm="EdDSA", headers={"kid": f"{did}#key-1", "typ": "JWT"})


def demo_did_document() -> dict:
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    from app.badges.keys import b64url_encode

    raw = demo_issuer_key().public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return {"@context": ["https://www.w3.org/ns/did/v1"], "id": DEMO_DID, "verificationMethod": [
        {"id": f"{DEMO_DID}#key-1", "type": "JsonWebKey2020", "controller": DEMO_DID,
         "publicKeyJwk": {"kty": "OKP", "crv": "Ed25519", "x": b64url_encode(raw)}}]}


def demo_badge_pages() -> dict[str, FakePage]:
    identity = "sha256$" + hashlib.sha256((DEMO_HOLDER_EMAIL + DEMO_SALT).encode()).hexdigest()
    assertion = {"@context": "https://w3id.org/openbadges/v2", "type": "Assertion",
                 "id": f"{DEMO_BADGES}/assertions/react-1",
                 "recipient": {"type": "email", "hashed": True, "salt": DEMO_SALT, "identity": identity},
                 "badge": f"{DEMO_BADGES}/badges/react", "verification": {"type": "hosted"},
                 "issuedOn": "2025-03-01T00:00:00Z"}
    badge = {"@context": "https://w3id.org/openbadges/v2", "type": "BadgeClass", "id": f"{DEMO_BADGES}/badges/react",
             "name": "React Fundamentals", "description": "Synthetic demo badge for React fundamentals.",
             "criteria": {"narrative": "Built a synthetic demo React app."}, "issuer": f"{DEMO_BADGES}/issuer"}
    issuer = {"@context": "https://w3id.org/openbadges/v2", "type": "Issuer", "id": f"{DEMO_BADGES}/issuer",
              "name": "Demo Academy (synthetic)", "url": DEMO_BADGES}
    json_type = "application/ld+json"
    return {
        f"{DEMO_BADGES}/assertions/react-1": FakePage(json.dumps(assertion), json_type),
        f"{DEMO_BADGES}/badges/react": FakePage(json.dumps(badge), json_type),
        f"{DEMO_BADGES}/issuer": FakePage(json.dumps(issuer), json_type),
        f"{DEMO_BADGES}/credentials/python.jwt": FakePage(demo_vc_jwt(), "application/vc+jwt"),
        f"{DEMO_BADGES}/.well-known/did.json": FakePage(json.dumps(demo_did_document()), "application/did+json"),
    }


def demo_network(code: str | None = None) -> Network:
    """A fully offline Network: demo- hosts resolve to a placeholder address served by MockTransports."""
    accounts = demo_accounts(code or "")
    pages = demo_pages()

    def resolve(host: str) -> list[str]:
        if host.startswith("demo-"):
            return [DEMO_ADDRESS]
        return fixed_resolver({})(host)

    def api_handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "gitlab.com" or "/api/v4" in request.url.path:
            return gitlab_handler(accounts)(request)
        return github_handler(accounts)(request)

    def txt(host: str) -> list[str]:
        return [f"recruitpro-verification={code}"] if host.startswith("demo-") and code else []

    return Network(resolver=resolve, transport_factory=lambda: httpx.MockTransport(web_handler(pages, code=code)),
                   api_transport=httpx.MockTransport(api_handler), txt_resolver=txt, demo=True)
