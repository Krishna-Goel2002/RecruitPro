"""Structured interview-kit generator (plan Section 6.2 item 10).

Rules baseline: per requirement one behavioural question, plus a technical/situational question
for must-have skills and experience; each with follow-up probes and four behavioural anchors.
When ``focusRequirementIds`` is given (typically the must-haves not yet verified) those
requirements come first; then must-haves, then nice-to-haves. At most 20 questions.
"""
from __future__ import annotations

import logging

from pydantic import ValidationError

from app.core.config import Settings, load_settings
from app.llm.provider import LLMError, LLMProvider, get_provider
from app.models.authoring import InterviewKitRequest, InterviewKitResponse, InterviewQuestion
from app.models.matching import MatchRequirement

logger = logging.getLogger(__name__)
MAX_QUESTIONS = 20
FOLLOW_UPS = [
    "What exactly was your part, as opposed to the team's?",
    "How did you know it worked - what did you measure or observe?",
    "Looking back, what would you do differently?",
]


def _behavioural_anchors(subject: str) -> dict[str, str]:
    return {
        "1": "No relevant example, or only general statements about what they would do.",
        "2": "Gives an example, but is vague about their own actions or the outcome.",
        "3": f"A specific example with their own actions and a clear result that shows {subject}.",
        "4": f"Several specific examples; explains trade-offs, results and lessons, and could coach others in {subject}.",
    }


_TECHNICAL_ANCHORS = {
    "1": "Cannot describe a workable approach.",
    "2": "A workable approach with major gaps or misconceptions.",
    "3": "A sound approach covering the main steps and the most important risk.",
    "4": "An excellent approach that anticipates edge cases, weighs alternatives and justifies the choices.",
}


def _questions_for(requirement: MatchRequirement) -> list[tuple[str, list[str], dict[str, str]]]:
    subject = requirement.description
    years = f" over the last {requirement.minimumYears}+ years" if requirement.minimumYears else ""
    if requirement.type == "OTHER":
        return [(f"Tell me about a situation that shows {subject}. What did you do, and what happened?",
                 FOLLOW_UPS[:2], _behavioural_anchors(subject))]
    if requirement.type == "EDUCATION":
        return [(f"How has your study for '{subject}' shaped the way you work? Give a concrete example.",
                 FOLLOW_UPS[1:2], _behavioural_anchors("applied learning"))]
    if requirement.type == "CERTIFICATION":
        return [(f"What did preparing for {subject} teach you that you have since used at work?",
                 ["Can you share the certificate ID or verification link?"], _behavioural_anchors(subject))]
    questions = [(f"Tell me about a recent piece of work where you used {subject}{years}. What was the situation, what "
                  "did you do yourself, and what was the result?", list(FOLLOW_UPS), _behavioural_anchors(subject))]
    if requirement.mandatory:
        prompt = (f"Walk me through how you would approach a typical problem in this role using {subject}. "
                  "What would you check first, and what could go wrong?" if requirement.type == "SKILL" else
                  f"Describe the most substantial responsibility you have had in {subject}: its scope, your role, and how it "
                  "changed over time.")
        questions.append((prompt, ["Why that approach rather than an alternative?", "How would you test or verify it?"],
                          dict(_TECHNICAL_ANCHORS)))
    return questions


def _ordered(request: InterviewKitRequest) -> list[MatchRequirement]:
    focus = list(dict.fromkeys(request.focusRequirementIds or []))
    by_id = {requirement.id: requirement for requirement in request.requirements}
    first = sorted((by_id[rid] for rid in focus if rid in by_id), key=lambda req: not req.mandatory)
    rest = sorted((req for req in request.requirements if req.id not in set(focus)), key=lambda req: not req.mandatory)
    return first + rest


def generate_rules(request: InterviewKitRequest) -> InterviewKitResponse:
    questions: list[InterviewQuestion] = []
    for requirement in _ordered(request):
        for text, follow_ups, anchors in _questions_for(requirement):
            if len(questions) >= MAX_QUESTIONS:
                break
            questions.append(InterviewQuestion(id=f"q{len(questions) + 1}", requirementId=requirement.id, question=text,
                                               followUps=follow_ups, anchors=anchors))
    if not questions:
        questions.append(InterviewQuestion(
            id="q1", requirementId=None,
            question=f"Tell me about the piece of work you are proudest of that is relevant to the {request.jobTitle} role.",
            followUps=list(FOLLOW_UPS), anchors=_behavioural_anchors("the core skills of the role")))
    return InterviewKitResponse(questions=questions, engine="rules")


_LLM_SYSTEM = (
    "You write structured, job-related interview questions. Return strict JSON only: {\"questions\": [{\"requirementId\": "
    "integer|null, \"question\": string, \"followUps\": [string], \"anchors\": {\"1\": string, \"2\": string, \"3\": string, "
    "\"4\": string}}]}. One or two questions per requirement (behavioural and technical), focus requirements first, at most "
    "20 questions. Anchors describe observable answers from 1 (poor) to 4 (excellent). Never ask about age, family, health, "
    "religion, caste, nationality or other protected characteristics."
)


def _generate_llm(request: InterviewKitRequest, provider: LLMProvider) -> InterviewKitResponse:
    listing = "\n".join(f"- id {req.id}: [{req.type}] {req.description} ({'must-have' if req.mandatory else 'nice-to-have'})"
                        for req in _ordered(request))
    focus = ", ".join(map(str, request.focusRequirementIds or [])) or "none"
    data = provider.complete_json(_LLM_SYSTEM, f"Job title: {request.jobTitle}\nFocus requirement ids: {focus}\n"
                                               f"Requirements:\n{listing}")
    ids = {req.id for req in request.requirements}
    questions: list[InterviewQuestion] = []
    items = data.get("questions")
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict) or not isinstance(item.get("question"), str) or not item["question"].strip():
            continue
        anchors = item.get("anchors")
        requirement_id = item.get("requirementId")
        if isinstance(requirement_id, bool) or (requirement_id is not None and not isinstance(requirement_id, int)):
            continue  # true/false or "3" are not requirement ids
        if not isinstance(anchors, dict) or not all(isinstance(anchors.get(key), str) and anchors[key].strip() for key in "1234"):
            continue
        if requirement_id is not None and requirement_id not in ids:
            continue
        raw_follow_ups = item.get("followUps") if isinstance(item.get("followUps"), list) else []
        follow_ups = [text.strip()[:300] for text in raw_follow_ups if isinstance(text, str) and text.strip()][:5]
        questions.append(InterviewQuestion(id=f"q{len(questions) + 1}", requirementId=requirement_id,
                                           question=item["question"].strip()[:600], followUps=follow_ups,
                                           anchors={key: anchors[key].strip()[:400] for key in "1234"}))
        if len(questions) >= MAX_QUESTIONS:
            break
    if not questions:
        raise LLMError("interview kit response failed validation")
    return InterviewKitResponse(questions=questions, engine="llm")


def generate_interview_kit(request: InterviewKitRequest, settings: Settings | None = None,
                           provider: LLMProvider | None = None) -> InterviewKitResponse:
    provider = provider or get_provider(settings or load_settings())
    if provider is not None:
        try:
            return _generate_llm(request, provider)
        except (LLMError, ValidationError) as exc:
            logger.warning("LLM interview-kit generation failed, using rules: %s", exc)
        except Exception:  # noqa: BLE001 - malformed model output must fall back to the rules kit
            logger.warning("LLM interview-kit generation returned unusable output, using rules", exc_info=True)
    return generate_rules(request)
