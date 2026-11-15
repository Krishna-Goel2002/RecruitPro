"""Shared fixtures. Tests never touch the network: every outbound path uses MockTransport / fake resolvers."""
from __future__ import annotations

import socket
from typing import Iterator

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_clock, get_network_factory
from app.main import create_app
from eval.builders import NOW, FakeNet

TOKEN = "test-only-agent-token"
HEADERS = {"X-Internal-Service-Token": TOKEN}


@pytest.fixture(autouse=True)
def _offline(monkeypatch: pytest.MonkeyPatch) -> None:
    """Fail loudly if anything tries real DNS."""
    def refuse(*args, **kwargs):
        raise AssertionError("tests must not resolve real host names")

    monkeypatch.setattr(socket, "getaddrinfo", refuse)
    monkeypatch.setenv("AGENT_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("LLM_PROVIDER", "none")
    monkeypatch.delenv("AGENT_DEMO_FIXTURES", raising=False)
    monkeypatch.delenv("AGENT_ALLOW_NO_TOKEN", raising=False)
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)


@pytest.fixture
def fake_net() -> FakeNet:
    return FakeNet()


@pytest.fixture
def client(fake_net: FakeNet) -> Iterator[TestClient]:
    app = create_app()
    app.dependency_overrides[get_network_factory] = lambda: (lambda settings, identifier, code=None: fake_net.network())
    app.dependency_overrides[get_clock] = lambda: (lambda: NOW)
    with TestClient(app) as test_client:
        yield test_client
