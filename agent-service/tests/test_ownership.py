"""Ownership checks: DNS TXT, well-known file, page code, profile bio."""
from __future__ import annotations

import pytest

from app.demo.fakes import FakeAccount, FakePage
from tests.conftest import HEADERS

CODE = "rp_8f3K2mQ9xZ"


def _check(client, method, target, code=CODE):
    response = client.post("/api/v1/ownership/check", headers=HEADERS,
                           json={"method": method, "target": target, "code": code})
    assert response.status_code == 200, response.text
    return response.json()


def test_dns_txt(client, fake_net):
    fake_net.txt["me.example"] = ["v=spf1 -all", f"recruitpro-verification={CODE}"]
    data = _check(client, "DNS_TXT", "me.example")
    assert data["proven"] and data["evidence"]["kind"] == "OWNERSHIP_PROOF"
    assert data["evidence"]["contentHash"].startswith("sha256:")
    assert not _check(client, "DNS_TXT", "https://me.example/path", code="rp_otherCode1")["proven"]
    assert not _check(client, "DNS_TXT", "10.0.0.1")["proven"]


def test_well_known_file_and_page_code(client, fake_net):
    fake_net.pages["https://me.example/.well-known/recruitpro-verification.txt"] = FakePage(f"{CODE}\n", "text/plain")
    fake_net.pages["https://me.example/robots.txt"] = FakePage("User-agent: *\nAllow: /\n", "text/plain")
    fake_net.pages["https://me.example/about"] = FakePage(f'<meta name="recruitpro-verification" content="{CODE}">')
    assert _check(client, "WELL_KNOWN_FILE", "me.example")["proven"]
    assert _check(client, "PAGE_CODE", "https://me.example/about")["proven"]
    missing = _check(client, "PAGE_CODE", "https://me.example/none")
    assert not missing["proven"] and missing["evidence"] is None


def test_profile_bio(client, fake_net):
    fake_net.accounts.append(FakeAccount(login="dev", bio=f"Builder. {CODE}"))
    assert _check(client, "PROFILE_BIO_CODE", "github:dev")["proven"]
    assert not _check(client, "PROFILE_BIO_CODE", "github:nobody")["proven"]


@pytest.mark.parametrize("target", ["octocat", "github:", "github:   ", "bitbucket:dev", ":dev", "github:a/b"])
def test_profile_bio_target_must_be_provider_and_login(client, fake_net, target):
    response = client.post("/api/v1/ownership/check", headers=HEADERS,
                           json={"method": "PROFILE_BIO_CODE", "target": target, "code": CODE})
    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "BAD_TARGET" and response.json()["detail"]["message"]
    assert fake_net.calls == []


def test_profile_bio_demo_target_still_works(monkeypatch):
    from fastapi.testclient import TestClient

    from app.main import create_app

    monkeypatch.setenv("AGENT_DEMO_FIXTURES", "true")
    with TestClient(create_app()) as demo:
        for target in ("github:demo-asha", "GitHub: demo-asha "):
            response = demo.post("/api/v1/ownership/check", headers=HEADERS,
                                 json={"method": "PROFILE_BIO_CODE", "target": target, "code": CODE})
            assert response.status_code == 200 and response.json()["proven"], target


def test_private_targets_are_never_fetched(client, fake_net):
    fake_net.hosts["internal.example"] = ["192.168.0.10"]
    data = _check(client, "PAGE_CODE", "http://internal.example/")
    assert not data["proven"] and "URL_BLOCKED" in data["detail"]
    assert not any("internal" in call for call in fake_net.calls)


@pytest.mark.parametrize("code", ["", "short", "has space!!", "x" * 65])
def test_codes_must_look_random(client, code):
    response = client.post("/api/v1/ownership/check", headers=HEADERS,
                           json={"method": "DNS_TXT", "target": "me.example", "code": code})
    assert response.status_code == 422


# ------------------------------------------------------------------------------------- bug-hunt regressions
def _echo_site(fake_net):
    """A site whose search and 404 pages echo the address (very common), and an open redirect."""
    import httpx
    from urllib.parse import unquote_plus

    from app.demo.fakes import web_handler

    pages = web_handler({"https://victim.example/robots.txt": FakePage("User-agent: *\nAllow: /\n", "text/plain")})

    def handle(request: httpx.Request) -> httpx.Response:
        path = request.url.raw_path.decode()
        if path.startswith("/search"):
            return httpx.Response(200, text=f"<p>Results for {unquote_plus(path)}</p>",
                                  headers={"content-type": "text/html"})
        if path.startswith("/go"):
            return httpx.Response(302, headers={"location": unquote_plus(path.split("to=", 1)[1])})
        return pages(request)

    network = fake_net.network()
    network.transport_factory = lambda: httpx.MockTransport(handle)
    return network


def test_page_code_cannot_be_proven_by_a_page_that_echoes_its_url(client, fake_net):
    from app.api.deps import get_network_factory

    network = _echo_site(fake_net)
    client.app.dependency_overrides[get_network_factory] = lambda: (lambda settings, identifier, code=None: network)
    for target in (f"https://victim.example/search?q={CODE}", "https://victim.example/search?q="
                   + "".join(f"%{ord(char):02X}" for char in CODE), f"https://victim.example/search/{CODE.upper()}",
                   "https://victim.example/go?to=https%3A%2F%2Fvictim.example%2Fsearch%3Fq%3D" + CODE):
        data = _check(client, "PAGE_CODE", target)
        assert not data["proven"] and data["evidence"] is None, target


def test_page_code_and_well_known_file_must_stay_on_the_site(client, fake_net):
    fake_net.pages["https://me.example/robots.txt"] = FakePage("User-agent: *\nAllow: /\n", "text/plain")
    fake_net.pages["https://me.example/about"] = FakePage("", status=302,
                                                          headers={"location": "https://paste.example/raw/1"})
    fake_net.pages["https://paste.example/robots.txt"] = FakePage("User-agent: *\nAllow: /\n", "text/plain")
    fake_net.pages["https://paste.example/raw/1"] = FakePage(f"<p>{CODE}</p>")
    data = _check(client, "PAGE_CODE", "https://me.example/about")
    assert not data["proven"] and "another site" in data["detail"]
    fake_net.pages["https://me.example/.well-known/recruitpro-verification.txt"] = FakePage(
        "", status=301, headers={"location": "https://paste.example/raw/1"})
    assert not _check(client, "WELL_KNOWN_FILE", "me.example")["proven"]
    # www. is the same site
    fake_net.pages["https://me.example/cv"] = FakePage("", status=301, headers={"location": "https://www.me.example/cv"})
    fake_net.pages["https://www.me.example/robots.txt"] = FakePage("User-agent: *\nAllow: /\n", "text/plain")
    fake_net.pages["https://www.me.example/cv"] = FakePage(f'<meta name="recruitpro-verification" content="{CODE}">')
    assert _check(client, "PAGE_CODE", "https://me.example/cv")["proven"]
