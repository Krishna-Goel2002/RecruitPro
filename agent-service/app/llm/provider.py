"""Optional LLM provider (copied from ai-service so agent-service deploys on its own) (``LLM_PROVIDER=none|anthropic|openai_compatible``) over plain httpx.

Contract for every caller:

* only de-identified text is sent (evidence snapshots pass through ``app.deidentify`` first);
* fetched text goes in the *user* message as JSON data, never in the system prompt, and no tools are offered;
* the model must answer with a strict JSON object, otherwise ``LLMError``;
* summary sentences must quote the evidence verbatim (``app.citations``) or they are dropped;
* any error or time-out raises ``LLMError`` and the caller keeps its rules-based result.
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any, Protocol

import httpx

from app.core.config import ConfigError, Settings, validate_llm

logger = logging.getLogger(__name__)
ANTHROPIC_VERSION = "2023-06-01"


class LLMError(Exception):
    """The LLM call failed or returned something unusable; callers fall back to rules."""


class LLMProvider(Protocol):
    name: str

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> dict[str, Any]: ...


class _HttpProvider:
    name = "http"

    def __init__(self, settings: Settings, transport: httpx.BaseTransport | None = None) -> None:
        self.model = settings.llm_model or ""
        self._settings = settings
        self._transport = transport

    def _post(self, url: str, headers: dict[str, str], body: dict[str, Any]) -> dict[str, Any]:
        try:
            with httpx.Client(timeout=self._settings.llm_timeout_seconds, transport=self._transport) as client:
                response = client.post(url, headers=headers, json=body)
                response.raise_for_status()
                payload = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise LLMError(f"{self.name} request failed: {type(exc).__name__}") from exc
        if not isinstance(payload, dict):
            raise LLMError(f"{self.name} returned a non-object payload")
        return payload


class AnthropicProvider(_HttpProvider):
    name = "anthropic"

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> dict[str, Any]:
        payload = self._post(
            f"{self._settings.anthropic_base_url}/v1/messages",
            {"x-api-key": self._settings.anthropic_api_key or "", "anthropic-version": ANTHROPIC_VERSION},
            {"model": self.model, "max_tokens": max_tokens, "temperature": 0, "system": system,
             "messages": [{"role": "user", "content": user}]},
        )
        blocks = payload.get("content")
        if not isinstance(blocks, list):
            raise LLMError("anthropic response has no content")
        return parse_json_object("".join(str(block.get("text", "")) for block in blocks
                                         if isinstance(block, dict) and block.get("type") == "text"))


class OpenAICompatibleProvider(_HttpProvider):
    name = "openai_compatible"

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {self._settings.openai_api_key}"} if self._settings.openai_api_key else {}
        payload = self._post(
            f"{self._settings.openai_base_url}/chat/completions", headers,
            {"model": self.model, "temperature": 0, "max_tokens": max_tokens,
             "response_format": {"type": "json_object"},
             "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]},
        )
        try:
            content = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise LLMError("openai-compatible response has no message content") from exc
        return parse_json_object(str(content))


def parse_json_object(text: str) -> dict[str, Any]:
    """Strict JSON: the whole reply must be one JSON object (a surrounding code fence is tolerated)."""
    stripped = text.strip()
    fenced = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", stripped, re.S)
    if fenced:
        stripped = fenced.group(1)
    try:
        value = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise LLMError("model reply is not valid JSON") from exc
    if not isinstance(value, dict):
        raise LLMError("model reply is not a JSON object")
    return value


def find_quote(quote: str, text: str) -> tuple[int, int] | None:
    """Span of ``quote`` in ``text`` when it appears verbatim (whitespace runs may differ)."""
    pieces = quote.split()
    if not pieces or len(quote.strip()) < 3:
        return None
    match = re.search(r"\s+".join(re.escape(piece) for piece in pieces), text)
    return match.span() if match else None


def get_provider(settings: Settings, transport: httpx.BaseTransport | None = None) -> LLMProvider | None:
    """The configured provider, or None when disabled or misconfigured (the service then runs offline)."""
    if not settings.llm_enabled:
        return None
    try:
        validate_llm(settings)
    except ConfigError as exc:
        logger.error("LLM disabled: %s", exc)
        return None
    if settings.llm_provider == "anthropic":
        return AnthropicProvider(settings, transport)
    return OpenAICompatibleProvider(settings, transport)
