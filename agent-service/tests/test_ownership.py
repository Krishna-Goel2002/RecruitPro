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
