"""Work-sample generator (plan Section 6.2 item 10).

Rules baseline: one task per two or three must-have skill/experience requirements (at most three
tasks, 15 minutes each, 15-45 minutes in total), a domain-appropriate scenario per task and a
rubric with one behaviourally anchored criterion per covered requirement plus one for reasoning.
"""
from __future__ import annotations

import logging

from pydantic import ValidationError

from app.core.config import Settings, load_settings
from app.llm.provider import LLMError, LLMProvider, get_provider
from app.matching.engine import skill_cnf
from app.models.authoring import RubricCriterion, WorkSampleRequest, WorkSampleResponse
from app.models.matching import MatchRequirement

logger = logging.getLogger(__name__)
MINUTES_PER_TASK = 15
MAX_TASKS = 3
_SCENARIOS = {
    "SOFTWARE": "Build a small, working piece of a realistic {title} task (for example an endpoint or component with "
                "input validation and two tests) using {skills}. Add a short README explaining your design choices.",
    "DATA": "Using a small dataset of your choice or the one provided, answer two business questions with {skills}. "
            "Show your method, the result and one limitation of the analysis.",
    "CLOUD": "Describe, as code or as a diagram with notes, how you would deploy and monitor a simple web service using "
             "{skills}. Call out cost, security and the most likely failure.",
    "DESIGN": "Redesign one screen of a familiar product for a stated user goal using {skills}. Share the artefact and "
              "a short rationale for the main decisions.",
    "PRODUCT": "Write a one-page brief for a new feature: problem, users, success metric, what you would cut, and how "
               "you would apply {skills}.",
    "SALES": "Prepare a discovery-call plan and a short follow-up email for a prospect in our market, showing {skills}.",
    "MARKETING": "Draft a campaign brief for a product launch - audience, channels, budget split and measurement - "
                 "showing {skills}.",
    "FINANCE": "From the summarised figures provided (or your own example figures), prepare a reconciliation and a short "
               "variance note, showing {skills}.",
    "HR": "Design a structured hiring plan for one role - job-related criteria, interview questions and a scoring "
          "guide - showing {skills}.",
    "OPERATIONS": "Map the process described in the brief, identify the two biggest bottlenecks and propose measurable "
                  "fixes, showing {skills}.",
    "HEALTHCARE": "Talk through, step by step, how you would handle the written patient scenario provided, showing "
                  "{skills}. Note what you would escalate and why.",
    "EDUCATION": "Plan a 30-minute lesson for the stated class and objective, with one activity and a quick check for "
                 "understanding, showing {skills}.",
    "LEGAL": "Review the short clause provided, list the risks for our side and redraft it, showing {skills}.",
    "LANGUAGE": "Write a short customer reply in the required language and explain one choice of tone, showing {skills}.",
    "GENERAL": "Complete a realistic task from the {title} role that shows {skills}. Explain your approach and the "
               "trade-offs you made.",
}


def _domain(requirement: MatchRequirement) -> str:
    groups = skill_cnf(requirement.description)
    return groups[0][0].domain if groups else "GENERAL"


def anchors_for(subject: str) -> dict[str, str]:
    return {
        "1": f"Does not apply {subject}, or the result does not work or answer the brief.",
        "2": f"Applies {subject} with significant errors or gaps; the result needs substantial rework.",
        "3": f"Applies {subject} correctly to the brief with minor issues; the result is usable as is.",
        "4": f"Applies {subject} skilfully, explains trade-offs and improves on the brief where it matters.",
    }


def generate_rules(request: WorkSampleRequest) -> WorkSampleResponse:
    scored = [req for req in request.requirements if req.type in {"SKILL", "EXPERIENCE"}]
    chosen = ([req for req in scored if req.mandatory] or scored)[:MAX_TASKS * 3]
    count = -(-len(chosen) // 3)  # tasks of two or three requirements each
    sizes = [len(chosen) // count + (1 if index < len(chosen) % count else 0) for index in range(count)] if count else []
    chunks: list[list[MatchRequirement]] = []
    for size in sizes:
        start = sum(len(chunk) for chunk in chunks)
        chunks.append(chosen[start:start + size])
    tasks: list[str] = []
    rubric: list[RubricCriterion] = []
    for chunk in chunks or [[]]:
        names = ", ".join(req.description for req in chunk) or f"the core skills of a {request.jobTitle}"
        domain = _domain(chunk[0]) if chunk else "GENERAL"
        tasks.append(_SCENARIOS.get(domain, _SCENARIOS["GENERAL"]).format(title=request.jobTitle, skills=names))
        rubric.extend(RubricCriterion(requirementId=req.id, criterion=f"Use of {req.description}",
                                      anchors=anchors_for(req.description)) for req in chunk)
    rubric.append(RubricCriterion(requirementId=None, criterion="Clarity of reasoning and communication", anchors={
        "1": "No explanation of the approach, or the explanation contradicts the work.",
        "2": "Explains what was done but not why; key assumptions are missing.",
        "3": "Clear explanation of the approach and main assumptions.",
        "4": "Concise, well-structured explanation that weighs alternatives and names risks.",
    }))
    minutes = max(15, min(45, MINUTES_PER_TASK * len(tasks)))
    lines = [f"You have {minutes} minutes in total. Work through the task(s) below and submit your work as text or a link.", ""]
    for number, task in enumerate(tasks, start=1):
        lines.append(f"Task {number} (about {minutes // len(tasks)} minutes): {task}")
    lines += ["", "Keep it short: we assess the approach and reasoning as much as the result. "
                  "Do not include personal information about yourself in your submission."]
    return WorkSampleResponse(title=f"{request.jobTitle} work sample", instructions="\n".join(lines),
                              timeLimitMinutes=minutes, rubric=rubric, engine="rules")


_LLM_SYSTEM = (
    "You design short, job-related work samples for hiring. Return strict JSON only: {\"title\": string, "
    "\"instructions\": string, \"timeLimitMinutes\": integer 15-45, \"rubric\": [{\"requirementId\": integer|null, "
    "\"criterion\": string, \"anchors\": {\"1\": string, \"2\": string, \"3\": string, \"4\": string}}]}. Use only the "
    "requirement ids given. Anchors describe observable behaviour from 1 (poor) to 4 (excellent). Do not ask for personal "
    "information."
)


def _generate_llm(request: WorkSampleRequest, provider: LLMProvider) -> WorkSampleResponse:
    listing = "\n".join(f"- id {req.id}: [{req.type}] {req.description} ({'must-have' if req.mandatory else 'nice-to-have'})"
                        for req in request.requirements)
    data = provider.complete_json(_LLM_SYSTEM, f"Job title: {request.jobTitle}\nRequirements:\n{listing}")
    ids = {req.id for req in request.requirements}
    rubric = []
    for item in data.get("rubric") or []:
        anchors = item.get("anchors") if isinstance(item, dict) else None
        requirement_id = item.get("requirementId") if isinstance(item, dict) else None
        if not isinstance(anchors, dict) or not all(isinstance(anchors.get(key), str) and anchors[key].strip() for key in "1234"):
            continue
        if requirement_id is not None and requirement_id not in ids:
            continue
        if not isinstance(item.get("criterion"), str) or not item["criterion"].strip():
            continue
        rubric.append(RubricCriterion(requirementId=requirement_id, criterion=item["criterion"].strip()[:200],
                                      anchors={key: anchors[key].strip()[:400] for key in "1234"}))
    minutes = data.get("timeLimitMinutes")
    if not rubric or not isinstance(data.get("instructions"), str) or not data["instructions"].strip() \
            or not isinstance(minutes, int) or not 15 <= minutes <= 45:
        raise LLMError("work sample response failed validation")
    title = data.get("title") if isinstance(data.get("title"), str) and data["title"].strip() else f"{request.jobTitle} work sample"
    return WorkSampleResponse(title=title[:255], instructions=data["instructions"].strip()[:8000], timeLimitMinutes=minutes,
                              rubric=rubric, engine="llm")


def generate_work_sample(request: WorkSampleRequest, settings: Settings | None = None,
                         provider: LLMProvider | None = None) -> WorkSampleResponse:
    provider = provider or get_provider(settings or load_settings())
    if provider is not None:
        try:
            return _generate_llm(request, provider)
        except (LLMError, ValidationError) as exc:
            logger.warning("LLM work-sample generation failed, using rules: %s", exc)
    return generate_rules(request)
