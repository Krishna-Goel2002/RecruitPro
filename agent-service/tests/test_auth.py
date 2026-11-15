"""Token middleware: checked before the body is read; startup refuses an empty token."""
from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from app.core.auth import InternalTokenMiddleware
from app.core.config import ConfigError, load_settings, validate_settings
from app.main import create_app
from tests.conftest import HEADERS, TOKEN

BODY = {"claims": [], "referee": {"relationship": "PEER", "emailDomain": "acme.com"}, "answers": []}


def test_health_is_public_and_reports_settings(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": "1.0.0", "llmProvider": "none", "demoFixtures": False}


@pytest.mark.parametrize("headers", [{}, {"X-Internal-Service-Token": "wrong"}, {"X-Internal-Service-Token": ""}])
def test_api_requires_the_token(client: TestClient, headers: dict) -> None:
    response = client.post("/api/v1/verify/reference", json=BODY, headers=headers)
    assert response.status_code == 401


def test_valid_token_is_accepted(client: TestClient) -> None:
    assert client.post("/api/v1/verify/reference", json=BODY, headers=HEADERS).status_code == 200


def test_token_is_checked_before_the_body_is_read(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVICE_TOKEN", TOKEN)
    reached = {"app": False, "receive": 0}

    async def inner(scope, receive, send):  # pragma: no cover - must never run
        reached["app"] = True

    async def receive():
        reached["receive"] += 1
        return {"type": "http.request", "body": b"x" * 10, "more_body": False}

    sent = []

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "path": "/api/v1/verify/document", "headers": [(b"content-length", b"999999999")]}
    asyncio.run(InternalTokenMiddleware(inner)(scope, receive, send))
    assert sent[0]["status"] == 401 and not reached["app"] and reached["receive"] == 0


def test_oversized_json_body_is_refused_with_413(client: TestClient) -> None:
    response = client.post("/api/v1/verify/reference", content=b"{" + b" " * (1024 * 1024 + 10) + b"}",
                           headers={**HEADERS, "content-type": "application/json"})
    assert response.status_code == 413


def test_empty_token_refuses_requests_and_startup(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVICE_TOKEN", "")
    with pytest.raises(ConfigError):
        validate_settings(load_settings())
    with pytest.raises(ConfigError):
        with TestClient(create_app()):
            pass
    client = TestClient(create_app())  # no lifespan: the middleware still refuses
    assert client.post("/api/v1/verify/reference", json=BODY).status_code == 503


def test_allow_no_token_is_for_tests_only(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVICE_TOKEN", "")
    monkeypatch.setenv("AGENT_ALLOW_NO_TOKEN", "true")
    with TestClient(create_app()) as client:
        assert client.post("/api/v1/verify/reference", json=BODY).status_code == 200


def test_llm_provider_without_model_refuses_startup(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "anthropic")
    with pytest.raises(ConfigError):
        validate_settings(load_settings())
