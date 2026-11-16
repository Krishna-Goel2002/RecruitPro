"""Entry point of the HR assistant planner (``POST /api/v1/assistant/step``).

Routing:

* offline (``ASSISTANT_LLM_PROVIDER=none``, or ``allowExternalModel`` false): the rule-based planner, always;
* LLM mode: guided flows whose arguments must be exact stay deterministic (job posting slot filling, pending
  action slots, cancel, help, navigation, greetings); the model only writes the job description prose there.
  Everything else is planned by the model, validated, and on any failure planned offline with ``fallback=true``.
"""
from __future__ import annotations

import logging
from typing import Any

from app.assistant.intents import detect_intent
from app.assistant.llm_planner import PlanRejected, make_prose_writer, plan_with_llm
from app.assistant.offline import OfflinePlanner, latest_user_text
from app.core.config import Settings, load_settings
from app.llm.provider import LLMProvider, get_assistant_provider
from app.models.assistant import StepRequest

logger = logging.getLogger(__name__)
_DETERMINISTIC = {"create_job", "cancel", "help", "navigate", "greeting"}


def _meta(planner: str, model: str | None = None, fallback: bool = False) -> dict[str, Any]:
    return {"planner": planner, "model": model, "fallback": fallback}


def plan_step(request: StepRequest, settings: Settings | None = None, provider: LLMProvider | None = None) -> dict[str, Any]:
    settings = settings or load_settings()
    if request.allowExternalModel and provider is None:
        provider = get_assistant_provider(settings)
    if not request.allowExternalModel or provider is None:
        return {**OfflinePlanner(request).plan(), "meta": _meta("offline")}

    name, model = getattr(provider, "name", "llm"), getattr(provider, "model", None) or settings.assistant_llm_model
    max_tokens = settings.assistant_max_output_tokens
    planner = OfflinePlanner(request)
    intent = detect_intent(latest_user_text(request), request.context)
    draft_kind = request.draft.kind if request.draft else None
    guided = intent.name in _DETERMINISTIC or \
        (draft_kind == "job_posting" and planner._continues_job_flow(intent)) or \
        (draft_kind == "action" and planner._continues_action(intent))
    if guided:
        state: dict[str, Any] = {}
        planner.prose_writer = make_prose_writer(provider, max_tokens, state)
        step = planner.plan_intent(intent)
        if state.get("used"):
            return {**step, "meta": _meta(name, model)}
        return {**step, "meta": _meta("offline", None, bool(state.get("failed")))}
    try:
        step = plan_with_llm(request, provider, max_tokens)
        return {**step, "meta": _meta(name, model)}
    except PlanRejected as exc:
        logger.warning("Assistant LLM step rejected, planning offline: %s", exc)
    except Exception:  # noqa: BLE001 - the planner must always answer
        logger.warning("Assistant LLM step failed, planning offline", exc_info=True)
    return {**OfflinePlanner(request).plan(), "meta": _meta("offline", None, True)}
