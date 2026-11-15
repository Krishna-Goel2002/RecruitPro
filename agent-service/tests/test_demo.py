"""Demo fixtures: the whole flow works offline for demo- logins and hosts (synthetic, labelled)."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.api.deps import build_network
from app.core.config import load_settings
from app.demo.fixtures import DEMO_HOLDER_EMAIL
from app.main import create_app
from tests.conftest import HEADERS

CLAIMS = [{"id": "fact:1", "type": "SKILL", "value": "React"},
          {"id": "fact:2", "type": "CERTIFICATION", "value": "Python Programming"}]


@pytest.fixture
def demo_client(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("AGENT_DEMO_FIXTURES", "true")
    with TestClient(create_app()) as client:
        yield client


def test_health_reports_demo_mode(demo_client):
    assert demo_client.get("/health").json()["demoFixtures"] is True


def test_demo_code_web_badge_and_ownership(demo_client):
    code = demo_client.post("/api/v1/verify/code", headers=HEADERS, json={
        "provider": "github", "login": "demo-asha", "ownershipProven": True, "claims": CLAIMS}).json()
    assert code["verdicts"][0]["verdict"] == "VERIFIED"
    assert "DEMO_FIXTURES: synthetic recorded data" in code["warnings"]
    assert all(item["attributes"].get("synthetic") for item in code["evidence"])
    assert "Synthetic demo" in code["evidence"][0]["snapshotText"]
    weak = demo_client.post("/api/v1/verify/code", headers=HEADERS, json={
        "provider": "github", "login": "demo-ravi", "ownershipProven": True,
        "claims": [{"id": "fact:1", "type": "SKILL", "value": "Java"}]}).json()
    assert weak["verdicts"][0]["verdict"] == "PARTLY_VERIFIED"
    assert {note["code"] for note in weak["verdicts"][0]["integrityNotes"]} >= {"FORK_ONLY"}
    web = demo_client.post("/api/v1/verify/web", headers=HEADERS, json={
        "url": "https://demo-portfolio.example/", "ownershipProven": True, "claims": CLAIMS}).json()
    assert web["verdicts"][0]["verdict"] == "VERIFIED"
    badge = demo_client.post("/api/v1/verify/badge", headers=HEADERS, json={
        "url": "https://demo-badges.example/credentials/python.jwt", "claims": CLAIMS,
        "holderEmail": DEMO_HOLDER_EMAIL}).json()
    assert badge["verdicts"][1]["verdict"] == "VERIFIED"
    for method, target in (("DNS_TXT", "demo-portfolio.example"), ("WELL_KNOWN_FILE", "demo-portfolio.example"),
                           ("PROFILE_BIO_CODE", "github:demo-asha")):
        result = demo_client.post("/api/v1/ownership/check", headers=HEADERS,
                                  json={"method": method, "target": target, "code": "demoCode123"}).json()
        assert result["proven"], method


def test_demo_code_verdicts_are_unchanged(demo_client):
    def verdicts(login, skills):
        data = demo_client.post("/api/v1/verify/code", headers=HEADERS, json={
            "provider": "github", "login": login, "ownershipProven": True,
            "claims": [{"id": f"fact:{index}", "type": "SKILL", "value": value}
                       for index, value in enumerate(skills, start=1)]}).json()
        assert not any(note["code"] == "BACKDATED_HISTORY" for verdict in data["verdicts"]
                       for note in verdict["integrityNotes"])
        return [verdict["verdict"] for verdict in data["verdicts"]]

    assert verdicts("demo-asha", ["React", "TypeScript", "Python"]) == ["VERIFIED", "VERIFIED", "PARTLY_VERIFIED"]
    assert verdicts("demo-ravi", ["Java", "TensorFlow"]) == ["PARTLY_VERIFIED", "NOT_FOUND"]


def test_demo_fixtures_are_only_for_demo_identifiers():
    on = load_settings({"AGENT_SERVICE_TOKEN": "x", "AGENT_DEMO_FIXTURES": "true"})
    off = load_settings({"AGENT_SERVICE_TOKEN": "x"})
    assert build_network(on, "demo-asha").demo
    assert build_network(on, "https://demo-portfolio.example/x").demo
    assert not build_network(on, "real-user").demo
    assert not build_network(on, "https://example.com/demo-page").demo
    assert not build_network(off, "demo-asha").demo
