"""Service configuration, read from the environment on every call (cheap, and test friendly)."""
from __future__ import annotations

import hashlib
import os
import shutil
from dataclasses import dataclass
from typing import Mapping

VERSION = "2.0.0"
PARSER_VERSION = "rule-parser-2"
LLM_PROVIDERS = ("none", "anthropic", "openai_compatible")
# The HR assistant planner has its own provider setting (plan ASSISTANT_PLAN.md section 2); none = offline rules.
ASSISTANT_LLM_PROVIDERS = ("none", "gemini", "groq", "anthropic", "openai_compatible")
GEMINI_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
GROQ_DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"


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
    # HR assistant planner (gemini and groq speak the OpenAI-compatible chat API).
    gemini_api_key: str | None = None
    gemini_base_url: str = GEMINI_DEFAULT_BASE_URL
    groq_api_key: str | None = None
    groq_base_url: str = GROQ_DEFAULT_BASE_URL
    assistant_llm_provider: str = "none"
    assistant_llm_model: str | None = None
    assistant_llm_timeout_seconds: float = 20.0
    assistant_max_output_tokens: int = 1024

    @property
    def llm_enabled(self) -> bool:
        return self.llm_provider != "none"

    @property
    def assistant_llm_enabled(self) -> bool:
        return self.assistant_llm_provider != "none"


def _flag(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _number(env: Mapping[str, str], name: str, default: float, cast: type = float) -> float:
    raw = (env.get(name) or "").strip()
    if not raw:
        return cast(default)
    try:
        return cast(raw)
    except ValueError:
        raise ConfigError(f"{name} must be a number, got {raw!r}") from None


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
        llm_timeout_seconds=_number(env, "LLM_TIMEOUT_SECONDS", 20),
        ocr_enabled=_flag(env.get("OCR_ENABLED")),
        soffice_path=(shutil.which("soffice") if soffice is None else (soffice.strip() or None)),
        parse_workers=max(1, int(_number(env, "PARSE_WORKERS", 2, int))),
        parse_timeout_seconds=_number(env, "PARSE_TIMEOUT_SECONDS", 60),
        max_upload_bytes=int(_number(env, "MAX_UPLOAD_BYTES", 10 * 1024 * 1024, int)),
        gemini_api_key=env.get("GEMINI_API_KEY") or None,
        gemini_base_url=(env.get("GEMINI_BASE_URL") or GEMINI_DEFAULT_BASE_URL).strip().rstrip("/"),
        groq_api_key=env.get("GROQ_API_KEY") or None,
        groq_base_url=(env.get("GROQ_BASE_URL") or GROQ_DEFAULT_BASE_URL).strip().rstrip("/"),
        assistant_llm_provider=(env.get("ASSISTANT_LLM_PROVIDER") or "none").strip().lower(),
        assistant_llm_model=(env.get("ASSISTANT_LLM_MODEL") or "").strip() or None,
        assistant_llm_timeout_seconds=_number(env, "ASSISTANT_LLM_TIMEOUT_SECONDS", 20),
        assistant_max_output_tokens=int(_number(env, "ASSISTANT_MAX_OUTPUT_TOKENS", 1024, int)),
    )


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
    if is_published_token(settings.ai_service_token):
        raise ConfigError("AI_SERVICE_TOKEN is a value that was published in the repository; generate a new one with "
                          "tools/generate_env.py")
    if settings.llm_provider not in LLM_PROVIDERS:
        raise ConfigError(f"LLM_PROVIDER must be one of {', '.join(LLM_PROVIDERS)}")
    if not 0 < settings.parse_timeout_seconds <= 600:
        raise ConfigError("PARSE_TIMEOUT_SECONDS must be between 0 and 600")
    if not 0 < settings.llm_timeout_seconds <= 300:
        raise ConfigError("LLM_TIMEOUT_SECONDS must be between 0 and 300")
    if not 1 <= settings.parse_workers <= 64:
        raise ConfigError("PARSE_WORKERS must be between 1 and 64")
    if not 1 <= settings.max_upload_bytes <= 10 * 1024 * 1024:
        # The parser itself refuses files over 10 MiB (ingest.MAX_FILE_BYTES); a larger limit only wastes memory.
        raise ConfigError("MAX_UPLOAD_BYTES must be between 1 and 10485760")
    validate_assistant_settings(settings)
    if not settings.llm_enabled:
        return
    if not settings.llm_model:
        raise ConfigError("LLM_MODEL is required when LLM_PROVIDER is enabled")
    if settings.llm_provider == "anthropic" and not settings.anthropic_api_key:
        raise ConfigError("ANTHROPIC_API_KEY is required for LLM_PROVIDER=anthropic")
    if settings.llm_provider == "openai_compatible" and not settings.openai_base_url:
        raise ConfigError("OPENAI_BASE_URL is required for LLM_PROVIDER=openai_compatible")


def validate_assistant_settings(settings: Settings) -> None:
    """The HR assistant planner's own provider settings (independent of LLM_PROVIDER)."""
    provider = settings.assistant_llm_provider
    if provider not in ASSISTANT_LLM_PROVIDERS:
        raise ConfigError(f"ASSISTANT_LLM_PROVIDER must be one of {', '.join(ASSISTANT_LLM_PROVIDERS)}")
    if not 0 < settings.assistant_llm_timeout_seconds <= 120:
        raise ConfigError("ASSISTANT_LLM_TIMEOUT_SECONDS must be between 0 and 120")
    if not 64 <= settings.assistant_max_output_tokens <= 8192:
        raise ConfigError("ASSISTANT_MAX_OUTPUT_TOKENS must be between 64 and 8192")
    if provider == "none":
        return
    if not settings.assistant_llm_model:
        # No default model name on purpose: provider model names change; check the provider's model list.
        raise ConfigError("ASSISTANT_LLM_MODEL is required when ASSISTANT_LLM_PROVIDER is set")
    required = {
        "gemini": (settings.gemini_api_key, "GEMINI_API_KEY"),
        "groq": (settings.groq_api_key, "GROQ_API_KEY"),
        "anthropic": (settings.anthropic_api_key, "ANTHROPIC_API_KEY"),
        "openai_compatible": (settings.openai_base_url, "OPENAI_BASE_URL"),
    }
    value, name = required[provider]
    if not value:
        raise ConfigError(f"{name} is required for ASSISTANT_LLM_PROVIDER={provider}")
    base = {"gemini": settings.gemini_base_url, "groq": settings.groq_base_url}.get(provider)
    if base is not None and not base.startswith(("https://", "http://")):
        raise ConfigError(f"{provider.upper()}_BASE_URL must be an http(s) URL")
