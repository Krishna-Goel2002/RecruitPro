"""Service configuration, read from the environment on every call (cheap, and test friendly).

Environment variables (all optional except the token):

* ``AGENT_SERVICE_TOKEN``      shared secret expected in ``X-Internal-Service-Token`` (required; the service refuses
                               to start without it unless ``AGENT_ALLOW_NO_TOKEN=true``, which is for tests only)
* ``LLM_PROVIDER``             ``none`` (default) | ``anthropic`` | ``openai_compatible``; only writes ``summary``
* ``LLM_MODEL``, ``ANTHROPIC_API_KEY``, ``ANTHROPIC_BASE_URL``, ``OPENAI_BASE_URL``, ``OPENAI_API_KEY``,
  ``LLM_TIMEOUT_SECONDS``      as in ai-service
* ``AGENT_DEMO_FIXTURES``      ``true`` serves synthetic recorded responses for ``demo-`` logins/hosts (never in prod)
* ``AGENT_FETCH_TIMEOUT_SECONDS`` per-request network timeout (default 10)
* ``AGENT_REQUEST_DEADLINE_SECONDS`` wall-clock budget of one verification (default 50; the backend waits 60)
* ``AGENT_USER_AGENT``         default ``RecruitProVerifier/1.0 (+contact)``
* ``GITHUB_API_URL``, ``GITHUB_GRAPHQL_URL``, ``GITLAB_API_URL`` API bases (defaults: the public services)
* ``GITHUB_TOKEN``             optional server token used for GitHub when the candidate gave none (rate limits)
* ``AGENT_MAX_DOCUMENT_BYTES`` upload cap for /verify/document (default 15 MB)
"""
from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from typing import Mapping

VERSION = "1.0.0"
LLM_PROVIDERS = ("none", "anthropic", "openai_compatible")
VERIFIER_VERSIONS = {
    "code": "code-1.0",
    "web": "web-1.0",
    "document": "document-1.0",
    "badge": "badge-1.1",  # 1.1: issuer trust (app/trust.py)
    "reference": "reference-1.1",  # 1.1: employer-domain trust
}
DEFAULT_USER_AGENT = "RecruitProVerifier/1.0 (+contact)"


class ConfigError(ValueError):
    """The environment describes an impossible configuration (no token, LLM provider without a model, ...)."""


@dataclass(frozen=True)
class Settings:
    agent_service_token: str
    allow_no_token: bool
    llm_provider: str
    llm_model: str | None
    anthropic_api_key: str | None
    anthropic_base_url: str
    openai_base_url: str | None
    openai_api_key: str | None
    llm_timeout_seconds: float
    demo_fixtures: bool
    fetch_timeout_seconds: float
    request_deadline_seconds: float
    user_agent: str
    github_api_url: str
    github_graphql_url: str
    gitlab_api_url: str
    github_token: str | None
    max_document_bytes: int

    @property
    def llm_enabled(self) -> bool:
        return self.llm_provider != "none"


def _flag(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    env = os.environ if env is None else env
    return Settings(
        agent_service_token=env.get("AGENT_SERVICE_TOKEN", "").strip(),
        allow_no_token=_flag(env.get("AGENT_ALLOW_NO_TOKEN")),
        llm_provider=(env.get("LLM_PROVIDER") or "none").strip().lower(),
        llm_model=(env.get("LLM_MODEL") or "").strip() or None,
        anthropic_api_key=env.get("ANTHROPIC_API_KEY") or None,
        anthropic_base_url=(env.get("ANTHROPIC_BASE_URL") or "https://api.anthropic.com").rstrip("/"),
        openai_base_url=(env.get("OPENAI_BASE_URL") or "").rstrip("/") or None,
        openai_api_key=env.get("OPENAI_API_KEY") or None,
        llm_timeout_seconds=float(env.get("LLM_TIMEOUT_SECONDS") or 20),
        demo_fixtures=_flag(env.get("AGENT_DEMO_FIXTURES")),
        fetch_timeout_seconds=float(env.get("AGENT_FETCH_TIMEOUT_SECONDS") or 10),
        request_deadline_seconds=float(env.get("AGENT_REQUEST_DEADLINE_SECONDS") or 50),
        user_agent=(env.get("AGENT_USER_AGENT") or DEFAULT_USER_AGENT).strip(),
        github_api_url=(env.get("GITHUB_API_URL") or "https://api.github.com").rstrip("/"),
        github_graphql_url=(env.get("GITHUB_GRAPHQL_URL") or "https://api.github.com/graphql").rstrip("/"),
        gitlab_api_url=(env.get("GITLAB_API_URL") or "https://gitlab.com/api/v4").rstrip("/"),
        github_token=(env.get("GITHUB_TOKEN") or "").strip() or None,
        max_document_bytes=int(env.get("AGENT_MAX_DOCUMENT_BYTES") or 15 * 1024 * 1024),
    )


def validate_llm(settings: Settings) -> None:
    if settings.llm_provider not in LLM_PROVIDERS:
        raise ConfigError(f"LLM_PROVIDER must be one of {', '.join(LLM_PROVIDERS)}")
    if not settings.llm_enabled:
        return
    if not settings.llm_model:
        raise ConfigError("LLM_MODEL is required when LLM_PROVIDER is enabled")
    if settings.llm_provider == "anthropic" and not settings.anthropic_api_key:
        raise ConfigError("ANTHROPIC_API_KEY is required for LLM_PROVIDER=anthropic")
    if settings.llm_provider == "openai_compatible" and not settings.openai_base_url:
        raise ConfigError("OPENAI_BASE_URL is required for LLM_PROVIDER=openai_compatible")


MIN_TOKEN_LENGTH = 16
_PLACEHOLDER_TOKEN_PREFIXES = ("replace-with", "changeme", "change-me")


# SHA-256 of internal-service tokens that were published in the repository (security incident of 17 Nov 2026).
# The values are not repeated here; the service refuses to start with any of them. Generate fresh secrets with
# tools/generate_env.py (or tools/generate-env.ps1).
PUBLISHED_TOKEN_SHA256 = frozenset({
    "597c069b0ad74c2a1023941f12539446d3726a2bf8899854519782f2d44942d3",  # old compose AI-service token
    "2fa1faee3a707f0be22c198c3193edb9dba756bdbc5877b4d6ed8bcf7c4dd200",  # old compose agent-service token
    "be47c8e96320c1f4fa8e22ff9c93968d141f539ff091a45af0adde67ad1654ae",  # old README example agent token
    "5a92543aaf0b4adcd3d2cec4faf715daab501b536869c3d2288a2460332e0eb9",  # old .env.example placeholder
})


def is_published_token(token: str | None) -> bool:
    """True when the token is one of the values published in the repository."""
    if not token:
        return False
    return hashlib.sha256(token.strip().encode("utf-8")).hexdigest() in PUBLISHED_TOKEN_SHA256


def validate_settings(settings: Settings) -> None:
    """Raise ConfigError for configurations that must stop the service from starting."""
    if is_published_token(settings.agent_service_token):
        raise ConfigError("AGENT_SERVICE_TOKEN is a value that was published in the repository; generate a new one with "
                          "tools/generate_env.py")
    if not settings.agent_service_token and not settings.allow_no_token:
        raise ConfigError("AGENT_SERVICE_TOKEN is required (AGENT_ALLOW_NO_TOKEN=true is for tests only)")
    token = settings.agent_service_token
    if token and (len(token) < MIN_TOKEN_LENGTH or token.lower().startswith(_PLACEHOLDER_TOKEN_PREFIXES)):
        raise ConfigError(f"AGENT_SERVICE_TOKEN must be a random secret of at least {MIN_TOKEN_LENGTH} characters "
                          "(not the .env.example placeholder)")
    for name, value in (("AGENT_FETCH_TIMEOUT_SECONDS", settings.fetch_timeout_seconds),
                        ("AGENT_REQUEST_DEADLINE_SECONDS", settings.request_deadline_seconds),
                        ("LLM_TIMEOUT_SECONDS", settings.llm_timeout_seconds),
                        ("AGENT_MAX_DOCUMENT_BYTES", settings.max_document_bytes)):
        if not value > 0 or value != value or value == float("inf"):  # also NaN / inf from float("nan")
            raise ConfigError(f"{name} must be a positive number")
    validate_llm(settings)
