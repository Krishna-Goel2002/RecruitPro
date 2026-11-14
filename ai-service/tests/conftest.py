import pytest

from tests.helpers import TOKEN


@pytest.fixture
def token(monkeypatch: pytest.MonkeyPatch) -> str:
    """Configure the internal token (and an offline LLM setting) for API tests."""
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("LLM_PROVIDER", "none")
    return TOKEN
