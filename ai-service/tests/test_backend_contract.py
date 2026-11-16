"""Planner <-> backend tool contract.

The backend's tool registry (``backend/src/main/java/com/recruitpro/assistant/tool/**``) is the source of truth for
tool names, argument names and argument types. ``tests/fixtures/backend_tools.json`` is its parsed form; these tests

* fail when the Java source and the fixture differ (re-generate the fixture, see ``test_fixture_matches_java_source``);
* keep ``app/assistant/reference_catalogue.py`` equal to what the backend sends;
* run every planner intent that produces a tool call or a proposal (offline, through a simulated backend loop, for
  every staff role's catalogue, plus the whole intent evaluation set and LLM replies that use synonyms) and assert
  each emitted step names a tool of the request's catalogue with arguments the backend accepts unchanged.
"""
from __future__ import annotations

import json
from dataclasses import replace
from typing import Any

import httpx
import pytest

from app.assistant.catalogue import Catalogue, parse_arg_spec
from app.assistant.planner import plan_step
from app.assistant.reference_catalogue import INTERVIEWER_TOOLS, RECRUITER_TOOLS, REFERENCE_TOOLS
from app.core.config import load_settings
from app.llm.provider import get_assistant_provider
from app.models.assistant import StepRequest
from eval.assistant_intents import CASES
from tests.backend_contract import FIXTURE, TOOL_DIR, catalogue, load_fixture, parse_java_tools, validate

BACKEND = load_fixture()
ROLES = ("ADMIN", "HR_MANAGER", "RECRUITER", "INTERVIEWER")
TODAY = "2026-11-17"
OFFLINE = load_settings({})
JOB = {"page": "job", "jobId": 12, "jobTitle": "Backend Engineer"}
APPLICANT = {"page": "applicant", "jobId": 12, "jobTitle": "Backend Engineer", "applicationCode": "A-3014"}
NONE: dict = {}

# What the backend's tools return (shapes as in AS-PY-NOTES section 4), used to continue multi-step plans.
DATA: dict[str, Any] = {
    "list_jobs": {"jobs": [{"id": 12, "title": "Backend Engineer", "status": "PUBLISHED", "applicationCount": 9},
                           {"id": 13, "title": "Data Analyst", "status": "DRAFT", "applicationCount": 0}]},
    "job_insights": {"job": {"id": 12, "title": "Backend Engineer", "status": "PUBLISHED"}, "total": 9,
                     "pipeline": {"SUBMITTED": 5, "SHORTLISTED": 2, "INTERVIEWING": 1, "REJECTED": 1},
                     "groups": {"RANKED": 4, "MUST_HAVES_UNVERIFIED": 3, "UNSCORED": 2},
                     "top": [{"applicationId": 3014, "code": "A-3014", "status": "SHORTLISTED", "verified": 82.5, "claimed": 90.0,
                              "group": "RANKED"},
                             {"applicationId": 3001, "code": "A-3001", "status": "SUBMITTED", "verified": 70.0, "claimed": 70.0,
                              "group": "RANKED"},
                             {"applicationId": 3020, "code": "A-3020", "status": "SUBMITTED", "verified": 55.0, "claimed": 75.0,
                              "group": "RANKED"}],
                     "mostMissing": [{"description": "Kafka", "notObserved": 6, "knockout": True}]},
    "applicant_insights": {"code": "A-3014", "jobId": 12, "status": "SUBMITTED", "group": "RANKED", "verified": 60.0,
                           "claimed": 80.0, "requirements": [{"description": "Java", "status": "MATCHED", "tier": "RESUME_STATED"}]},
    "compare_applicants": {"applicants": [{"code": "A-3014", "verified": 82.5}, {"code": "A-3001", "verified": 70.0}],
                           "requirements": [{"description": "Java", "results": {"A-3014": {"status": "MATCHED"},
                                                                                "A-3001": {"status": "UNCLEAR"}}}]},
    "attention_digest": {"newApplicants": 4, "unscoredWorkSamples": 1, "interviewsWithoutScorecards": 0},
    "my_interviews": {"interviews": [{"scheduledAt": "2026-11-18T10:00:00Z", "code": "A-3014", "jobTitle": "Backend Engineer",
                                      "status": "SCHEDULED", "scorecardSubmitted": False}]},
    "fairness_summary": {"jobId": 12, "adverseImpactRatio": 0.9, "flagged": False, "groups": []},
    "explain": {"text": "Verified coverage counts requirements with evidence at RESUME_STATED or higher."},
}
# Replies to the planner's questions (by the ask's ``field``), so a scenario can reach its proposal.
REPLIES = {"reason": "Not enough relevant experience", "startsAt": "tomorrow 3 pm", "job": "Backend Engineer",
           "applicationCode": "A-3014", "applicationCodes": "A-3001 and A-3014", "title": "Senior data analyst",
           "location": "Pune", "jobType": "full time, 2", "deadline": "in 3 weeks", "requirements": "SQL and Python; Tableau is a plus",
           "department": "Analytics", "experienceLevel": "senior", "openings": "2"}


def _check_step(result: dict[str, Any], tools: list[dict[str, Any]]) -> None:
    names = {tool["name"]: tool for tool in tools}
    if result["type"] == "tool_call":
        assert result["tool"] in names and not names[result["tool"]]["writes"], result
        assert validate(BACKEND, result["tool"], result["args"]) is None, (validate(BACKEND, result["tool"], result["args"]), result)
    elif result["type"] == "propose":
        assert result["action"] in names and names[result["action"]]["writes"], result
        assert validate(BACKEND, result["action"], result["args"]) is None, (validate(BACKEND, result["action"], result["args"]), result)


def converse(turns: list[str], context: dict[str, Any], tools: list[dict[str, Any]], *, follow: int = 6) -> list[dict[str, Any]]:
    """Runs ``turns`` like the backend's orchestrator (tool loop up to 6 steps per message, draft kept and cleared as
    the backend does), answering up to ``follow`` planner questions from ``REPLIES``; returns every emitted step."""
    history: list[dict[str, str]] = []
    draft: dict[str, Any] | None = None
    emitted: list[dict[str, Any]] = []
    pending = list(turns)
    while pending:
        text = pending.pop(0)
        history.append({"role": "user", "text": text})
        observations: list[dict[str, Any]] = []
        for _ in range(6):
            request = StepRequest(messages=history[-20:], tools=tools, observations=observations, draft=draft,
                                  context={**context, "role": "HR_MANAGER", "today": TODAY})
            result = plan_step(request, OFFLINE)
            _check_step(result, tools)
            if result.get("draft") is not None:
                draft = result["draft"]
            elif result["type"] != "tool_call":
                draft = None
            if result["type"] == "tool_call":
                emitted.append(result)
                observations.append({"tool": result["tool"], "args": result["args"], "ok": True, "data": DATA[result["tool"]]})
                continue
            emitted.append(result)
            history.append({"role": "assistant", "text": result.get("text") or result.get("summary") or "…"})
            if result["type"] == "ask" and not pending and follow > 0 and result["field"] in REPLIES:
                follow -= 1
                pending.append(REPLIES[result["field"]])
            break
    return emitted


# Every intent that leads to a tool call or a proposal, in the phrasing a user would use (with the follow-up answers
# the planner asks for). Expected: the tools the scenario must end up using with the HR_MANAGER catalogue.
SCENARIOS: list[tuple[list[str], dict, set[str]]] = [
    (["list my jobs"], NONE, {"list_jobs"}),
    (["show closed jobs"], NONE, {"list_jobs"}),
    (["How is the Backend Engineer posting doing?"], NONE, {"list_jobs", "job_insights"}),
    (["how's this job doing"], JOB, {"job_insights"}),
    (["Tell me about A-3014"], NONE, {"applicant_insights"}),
    (["summary of this candidate"], APPLICANT, {"applicant_insights"}),
    (["Compare A-3001 and A-3014"], NONE, {"compare_applicants"}),
    (["compare the top 3"], JOB, {"job_insights", "compare_applicants"}),
    (["compare A-3001 with someone"], NONE, {"compare_applicants"}),
    (["What needs my attention today?"], NONE, {"attention_digest"}),
    (["my upcoming interviews"], NONE, {"my_interviews"}),
    (["my interviews this week"], NONE, {"my_interviews"}),
    (["fairness summary for this job"], JOB, {"fairness_summary"}),
    (["is our hiring fair across groups?"], NONE, {"fairness_summary"}),
    (["Shortlist A-3014"], JOB, {"decide_application"}),
    (["reject A-3001"], NONE, {"decide_application"}),
    (["reject A-3001 because no SQL experience"], NONE, {"decide_application"}),
    (["move A-3014 to interview"], NONE, {"decide_application"}),
    (["select A-3014"], NONE, {"decide_application"}),
    (["shortlist this candidate please"], JOB, {"decide_application"}),
    (["schedule an interview with A-3014"], NONE, {"schedule_interview"}),
    (["schedule an interview with A-3014 tomorrow at 3 pm with Priya Sharma on Zoom for 30 minutes"], NONE, {"schedule_interview"}),
    (["schedule an interview with A-3014 on 20 Nov at 11:30 for 60 minutes, location: Room 4B"], NONE, {"schedule_interview"}),
    (["schedule an interview with A-3014 tomorrow at 3 pm for 600 minutes"], NONE, {"schedule_interview"}),
    (["assign the work sample to A-3014"], NONE, {"assign_work_sample"}),
    (["assign work sample #7 to A-3014 in 5 days"], NONE, {"assign_work_sample"}),
    (["assign the take-home to this candidate, due by 30 Nov"], APPLICANT, {"assign_work_sample"}),
    (["generate a work sample for this job"], JOB, {"generate_work_sample"}),
    (["generate an interview kit for the Backend Engineer job"], NONE, {"list_jobs", "generate_interview_kit"}),
    (["close this job"], JOB, {"update_job_status"}),
    (["put it on hold"], JOB, {"update_job_status"}),
    (["publish the Data Analyst job"], NONE, {"list_jobs", "update_job_status"}),
    (["I want to post a new job"], NONE, {"create_job"}),
    (["hire 2 react developers in hyderabad, hybrid, full time, mid level, deadline in 3 weeks, salary 8-12 LPA, "
      "must know TypeScript"], NONE, {"create_job"}),
    (["Post a job for a rockstar developer in Pune, full time, 1 opening, mid level, young and energetic, need Python, "
      "no deadline", "add Docker as nice to have", "change openings to 3", "make SQL mandatory"], NONE, {"create_job"}),
    (["Post a job", "Senior Accountant - Mumbai\nWe are hiring a Senior Accountant for our finance team.\nRequirements:\n"
      "- 5+ years of experience in accounting\n- Tally is required\n- GST filing\nNice to have:\n- SAP\n"
      "Location: Mumbai. Full time, 1 opening, deadline 30 Dec 2026."], NONE, {"create_job"}),
]


# ------------------------------------------------------------------------------------------------ fixture
class TestFixture:
    @pytest.mark.skipif(not TOOL_DIR.is_dir(), reason="backend sources not present (ai-service built on its own)")
    def test_fixture_matches_java_source(self):
        """Fails when a tool or argument (name, type, required, values, limits, hint, description, capability) changes
        in the backend without the fixture. Re-generate it with:
        ``python3 -B -c "import json; from tests.backend_contract import *; FIXTURE.write_text(json.dumps(parse_java_tools(), indent=2, ensure_ascii=False) + '\\n', encoding='utf-8')"``
        then update ``app/assistant/reference_catalogue.py`` and the planner if names changed."""
        java = parse_java_tools()
        assert [tool["name"] for tool in java] == [tool["name"] for tool in BACKEND]
        for parsed, fixed in zip(java, BACKEND):
            assert [arg["name"] for arg in parsed["args"]] == [arg["name"] for arg in fixed["args"]], parsed["name"]
            assert parsed == fixed, parsed["name"]

    def test_fixture_is_the_full_registry(self):
        assert len(BACKEND) == 15 and len({tool["name"] for tool in BACKEND}) == 15
        assert [tool["name"] for tool in catalogue(BACKEND, "INTERVIEWER")] == ["applicant_insights", "my_interviews", "explain"]
        assert len(catalogue(BACKEND, "RECRUITER")) == 7 and len(catalogue(BACKEND, "HR_MANAGER")) == 15
        assert json.loads(FIXTURE.read_text(encoding="utf-8")) == BACKEND

    def test_reference_catalogue_is_what_the_backend_sends(self):
        assert REFERENCE_TOOLS == catalogue(BACKEND, "HR_MANAGER") == catalogue(BACKEND, "ADMIN")
        assert RECRUITER_TOOLS == catalogue(BACKEND, "RECRUITER")
        assert INTERVIEWER_TOOLS == catalogue(BACKEND, "INTERVIEWER")

    def test_every_backend_arg_schema_is_understood(self):
        kinds = {"INTEGER": ("integer", ""), "NUMBER": ("number", ""), "STRING": ("string", ""), "BOOLEAN": ("boolean", ""),
                 "ENUM": ("string", ""), "DATE_TIME": ("string", "date_time"), "APPLICATION_CODE": ("string", "application_code"),
                 "APPLICATION_CODES": ("array", "application_codes"), "STRING_LIST": ("array", "string_list"),
                 "REQUIREMENTS": ("array", "requirements")}
        for entry, tool in zip(catalogue(BACKEND), BACKEND):
            for arg in tool["args"]:
                spec = parse_arg_spec(arg["name"], entry["args"][arg["name"]])
                assert (spec.type, spec.format) == kinds[arg["type"]], (tool["name"], arg["name"], spec)
                assert spec.required == arg["required"], (tool["name"], arg["name"])
                assert spec.enum == tuple(arg["values"]), (tool["name"], arg["name"])
                if arg["type"] == "INTEGER":
                    assert (spec.minimum, spec.maximum) == (arg["min"], arg["max"]), (tool["name"], arg["name"])


# ------------------------------------------------------------------------------------------------ offline planner
class TestOfflinePlannerContract:
    @pytest.mark.parametrize("turns, context, expected", SCENARIOS, ids=[turns[0][:50] for turns, _, _ in SCENARIOS])
    def test_scenario_emits_only_backend_valid_steps(self, turns, context, expected):
        emitted = converse(turns, context, catalogue(BACKEND, "HR_MANAGER"))
        used = {step.get("tool") or step.get("action") for step in emitted if step["type"] in {"tool_call", "propose"}}
        assert expected <= used, (expected, used, emitted[-1])

    @pytest.mark.parametrize("role", ROLES)
    def test_every_role_catalogue(self, role):
        tools = catalogue(BACKEND, role)
        allowed = {tool["name"] for tool in tools}
        for turns, context, _ in SCENARIOS:
            for step in converse(turns, context, tools):  # converse() validates every step against ``tools``
                assert (step.get("tool") or step.get("action") or "") in allowed | {""}

    def test_every_write_tool_and_every_read_tool_but_explain_is_exercised(self):
        used: set[str] = set()
        for turns, context, _ in SCENARIOS:
            used |= {step.get("tool") or step.get("action") for step in converse(turns, context, catalogue(BACKEND))
                     if step["type"] in {"tool_call", "propose"}}
        # explain: the offline planner answers help questions from its own text; the LLM path is covered below.
        assert used == {tool["name"] for tool in BACKEND} - {"explain"}

    def test_every_intent_evaluation_utterance(self):
        """The 200+ utterances of ``eval/assistant_intents.py`` through the simulated loop: nothing invalid is emitted."""
        tools = catalogue(BACKEND)
        for text, context, _ in CASES:
            converse([text], context, tools, follow=1)

    def test_out_of_range_and_unknown_values_are_left_out_not_sent(self):
        steps = converse(["schedule an interview with A-3014 tomorrow at 3 pm for 600 minutes"], NONE, catalogue(BACKEND))
        assert steps[-1]["type"] == "propose" and "durationMinutes" not in steps[-1]["args"]

    def test_a_catalogue_without_the_tool_is_never_used(self):
        tools = [tool for tool in catalogue(BACKEND) if tool["name"] not in {"decide_application", "list_jobs"}]
        for text in ("Shortlist A-3014", "list my jobs", "How is the Backend Engineer posting doing?"):
            steps = converse([text], NONE, tools)
            assert all(step["type"] in {"answer", "ask"} for step in steps)


# ------------------------------------------------------------------------------------------------ LLM planner
GEMINI = replace(OFFLINE, assistant_llm_provider="gemini", assistant_llm_model="m", gemini_api_key="k")


def _llm(reply: dict[str, Any]):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps(reply)}}]})
    return get_assistant_provider(GEMINI, httpx.MockTransport(handler))


# Model replies using the names a model is likely to pick (or the old reference names) for every tool but create_job
# (the guided flow), with the user message that makes the step legitimate.
MODEL_REPLIES: list[tuple[str, dict[str, Any], dict[str, Any]]] = [
    ("list the backend jobs", {"type": "tool_call", "tool": "list_jobs", "args": {"query": "backend", "status": "open", "limit": 5}},
     {"search": "backend", "status": "PUBLISHED"}),
    ("how is job 12 doing", {"type": "tool_call", "tool": "job_insights", "args": {"job_id": "12", "verbose": True}}, {"jobId": 12}),
    ("tell me about A-3014", {"type": "tool_call", "tool": "applicant_insights", "args": {"applicationId": 3014}},
     {"applicationCode": "A-3014"}),
    ("compare A-3001 and A-3014", {"type": "tool_call", "tool": "compare_applicants", "args": {"applicationIds": [3001, "a3014"]}},
     {"applicationCodes": ["A-3001", "A-3014"]}),
    ("what needs my attention", {"type": "tool_call", "tool": "attention_digest", "args": {"range": "today"}}, {}),
    ("my upcoming interviews", {"type": "tool_call", "tool": "my_interviews", "args": {"upcoming": "true", "range": "week"}},
     {"upcomingOnly": True}),
    ("fairness for job 12", {"type": "tool_call", "tool": "fairness_summary", "args": {"job": 12}}, {"jobId": 12}),
    ("remind me about the tiers thing once more", {"type": "tool_call", "tool": "explain", "args": {"topic": "evidence_ladder"}},
     {"topic": "EVIDENCE_TIERS"}),
    ("close job 12", {"type": "propose", "action": "update_job_status", "summary": "Close job 12",
                      "args": {"id": 12, "newStatus": "close"}}, {"jobId": 12, "status": "CLOSED"}),
    ("reject A-3001, no SQL", {"type": "propose", "action": "decide_application", "summary": "Reject A-3001",
                               "args": {"applicationId": 3001, "decision": "REJECTED", "comment": "No SQL"}},
     {"applicationCode": "A-3001", "decision": "REJECT", "reason": "No SQL"}),
    ("move A-3014 to interview", {"type": "propose", "action": "decide_application", "summary": "Move A-3014 on",
                                  "args": {"code": "#A-3014", "decision": "INTERVIEW"}},
     {"applicationCode": "A-3014", "decision": "MOVE_TO_INTERVIEW"}),
    ("assign work sample 7 to A-3014 due 30 Nov", {"type": "propose", "action": "assign_work_sample", "summary": "Assign",
                                                   "args": {"applicationId": 3014, "sampleId": 7, "dueDate": "2026-11-30",
                                                            "dueInDays": 13}},
     {"applicationCode": "A-3014", "workSampleId": 7, "dueAt": "2026-11-30"}),
    ("schedule an interview with A-3014 on 20 Nov 3pm with Priya", {"type": "propose", "action": "schedule_interview",
                                                                    "summary": "Schedule",
                                                                    "args": {"applicationId": 3014, "startsAt": "2026-11-20T15:00",
                                                                             "interviewerName": "Priya", "duration": 45,
                                                                             "venue": "Zoom", "timezone": "IST"}},
     {"applicationCode": "A-3014", "scheduledAt": "2026-11-20T15:00", "interviewer": "Priya", "durationMinutes": 45,
      "location": "Zoom"}),
    ("generate a work sample for job 12", {"type": "propose", "action": "generate_work_sample", "summary": "Generate",
                                           "args": {"jobId": 12}}, {"jobId": 12}),
    ("generate an interview kit for job 12", {"type": "propose", "action": "generate_interview_kit", "summary": "Generate",
                                              "args": {"posting_id": 12}}, {"jobId": 12}),
]


class TestLlmPlannerContract:
    @pytest.mark.parametrize("text, reply, expected", MODEL_REPLIES, ids=[reply.get("tool") or reply.get("action") for _, reply, _ in MODEL_REPLIES])
    def test_model_steps_are_mapped_to_backend_names(self, text, reply, expected):
        request = StepRequest(messages=[{"role": "user", "text": text}], tools=catalogue(BACKEND), allowExternalModel=True,
                              context={"page": "other", "role": "HR_MANAGER", "today": TODAY})
        result = plan_step(request, GEMINI, _llm(reply))
        assert result["meta"]["planner"] == "gemini", result
        assert result["args"] == expected
        _check_step(result, catalogue(BACKEND))

    @pytest.mark.parametrize("reply", [
        {"type": "tool_call", "tool": "job_insights", "args": {"jobId": 12}},  # not in an INTERVIEWER's catalogue
        {"type": "propose", "action": "decide_application", "summary": "x", "args": {"applicationCode": "A-1", "decision": "SELECT"}},
        {"type": "tool_call", "tool": "applicant_insights", "args": {"applicationCode": "Priya"}},
        {"type": "tool_call", "tool": "my_interviews", "args": {"upcomingOnly": "sometimes"}},
    ])
    def test_tools_outside_the_catalogue_and_bad_values_are_refused(self, reply):
        request = StepRequest(messages=[{"role": "user", "text": "select A-1 and tell me about job 12"}],
                              tools=catalogue(BACKEND, "INTERVIEWER"), allowExternalModel=True,
                              context={"page": "other", "role": "INTERVIEWER", "today": TODAY})
        result = plan_step(request, GEMINI, _llm(reply))
        assert result["meta"]["fallback"] is True
        _check_step(result, catalogue(BACKEND, "INTERVIEWER"))

    def test_catalogue_normalisation_is_idempotent_on_backend_args(self):
        cat = Catalogue(StepRequest(messages=[{"role": "user", "text": "x"}], tools=catalogue(BACKEND)).tools)
        for _, _, expected in MODEL_REPLIES:
            for tool in BACKEND:
                if set(expected) <= {arg["name"] for arg in tool["args"]} and validate(BACKEND, tool["name"], expected) is None:
                    assert cat.normalise_args(tool["name"], expected) == (expected, None)
