"""Tokens published in the repository are refused (security incident of 17 Nov 2026)."""
import pytest

from app.core.config import ConfigError, is_published_token, load_settings, validate_settings

# Rebuilt from fragments so the published value is not repeated verbatim in this file.
OLD_DEV_TOKEN = "dev-only-ai-service-" + "token-change-me-0123456789"


def test_published_token_is_refused():
    assert is_published_token(OLD_DEV_TOKEN)
    with pytest.raises(ConfigError, match="published"):
        validate_settings(load_settings({"AI_SERVICE_TOKEN": OLD_DEV_TOKEN}))


def test_fresh_token_is_accepted():
    assert not is_published_token("a-fresh-random-token-0f9e8d7c6b5a")
    assert not is_published_token("")
    validate_settings(load_settings({"AI_SERVICE_TOKEN": "a-fresh-random-token-0f9e8d7c6b5a"}))
