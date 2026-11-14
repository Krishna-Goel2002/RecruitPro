"""Service configuration, read from the environment on every call (cheap, and test friendly)."""
from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from typing import Mapping

VERSION = "2.0.0"
PARSER_VERSION = "rule-parser-2"
LLM_PROVIDERS = ("none", "anthropic", "openai_compatible")


class ConfigError(ValueError):
    """The environment describes an impossible configuration (e.g. an LLM provider without a model)."""


@dataclass(frozen=True)
class Settings:
    ai_service_token: str
    llm_provider: str
    llm_model: str | None
    anthropic_api_key: str | None
    anthropic_base_url: str
    openai_base_url: str | None
    openai_api_key: str | None
    llm_timeout_seconds: float
    ocr_enabled: bool
    soffice_path: str | None
    parse_workers: int
    parse_timeout_seconds: float
    max_upload_bytes: int

    @property
    def llm_enabled(self) -> bool:
        return self.llm_provider != "none"


def _flag(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    env = os.environ if env is None else env
    provider = (env.get("LLM_PROVIDER") or "none").strip().lower()
    soffice = env.get("SOFFICE_PATH")
    return Settings(
        ai_service_token=env.get("AI_SERVICE_TOKEN", ""),
        llm_provider=provider,
        llm_model=(env.get("LLM_MODEL") or "").strip() or None,
        anthropic_api_key=env.get("ANTHROPIC_API_KEY") or None,
        anthropic_base_url=(env.get("ANTHROPIC_BASE_URL") or "https://api.anthropic.com").rstrip("/"),
        openai_base_url=(env.get("OPENAI_BASE_URL") or "").rstrip("/") or None,
        openai_api_key=env.get("OPENAI_API_KEY") or None,
        llm_timeout_seconds=float(env.get("LLM_TIMEOUT_SECONDS") or 20),
        ocr_enabled=_flag(env.get("OCR_ENABLED")),
        soffice_path=(shutil.which("soffice") if soffice is None else (soffice.strip() or None)),
        parse_workers=max(1, int(env.get("PARSE_WORKERS") or 2)),
        parse_timeout_seconds=float(env.get("PARSE_TIMEOUT_SECONDS") or 60),
        max_upload_bytes=int(env.get("MAX_UPLOAD_BYTES") or 10 * 1024 * 1024),
    )


def validate_settings(settings: Settings) -> None:
    """Raise ConfigError for configurations that must stop the service from starting."""
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
