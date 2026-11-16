"""Tool catalogues the planner is tested against (docs/plan/AS-PY-NOTES.md section 4).

``REFERENCE_TOOLS`` is the backend's catalogue verbatim (``backend/.../assistant/tool/**``: names, descriptions and
``ArgSpec.describe()`` texts, as ``AssistantTool.catalogueEntry`` sends them; ``capability`` is extra and only used
here to derive the per-role lists). ``tests/test_backend_contract.py`` fails when it drifts from
``tests/fixtures/backend_tools.json``, which in turn is checked against the Java sources.

The planner itself never relies on this module: it uses the catalogue each request carries. ``LEGACY_TOOLS`` is an
alias-named catalogue (``applicationId``, ``startsAt``, decision ``INTERVIEW``, ``dueInDays``, ``range``) kept to test
that arguments are mapped to whatever names a catalogue declares.
"""
from __future__ import annotations

from typing import Any

_ROLES = {
    "ANY_STAFF": {"ADMIN", "HR_MANAGER", "RECRUITER", "INTERVIEWER"},
    "JOBS_READ": {"ADMIN", "HR_MANAGER", "RECRUITER"},
    "JOBS_MANAGE": {"ADMIN", "HR_MANAGER"},
}

_BACKEND_TOOLS: list[dict[str, Any]] = [
    {"name": "list_jobs", "capability": "JOBS_READ", "writes": False,
     "description": "List the organisation's jobs (newest first, at most 20) with status, location and applicant counts. Use it to find a job id by title.",
     "args": {
         "status": "string, optional, one of DRAFT|PUBLISHED|ON_HOLD|CLOSED|CANCELLED",
         "search": "string, optional, part of the title",
     }},
    {"name": "job_insights", "capability": "JOBS_READ", "writes": False,
     "description": "How a job posting is doing: pipeline counts per status, ranking groups, top applicants by verified coverage (application codes), must-have requirements most often unverified, pending matching, work samples awaiting scoring, average days in the current stage.",
     "args": {
         "jobId": "integer, required, 1..",
     }},
    {"name": "applicant_insights", "capability": "ANY_STAFF", "writes": False,
     "description": "Everything about one applicant by application code: evidence per requirement (tier, status, quoted evidence), ranking gates, verification verdicts, work sample and interview results, open questions. Interviewers: only applicants they interview.",
     "args": {
         "applicationCode": "application code like A-3014, required",
     }},
    {"name": "compare_applicants", "capability": "JOBS_READ", "writes": False,
     "description": "Compare 2 to 4 applicants of the same job requirement by requirement (status and evidence tier), with verified and claimed coverage, ranking group and status.",
     "args": {
         "applicationCodes": "list of application codes, required",
     }},
    {"name": "attention_digest", "capability": "JOBS_READ", "writes": False,
     "description": "What needs attention today: new applicants awaiting a first decision, work samples awaiting a score, pending matching, application deadlines in the next 7 days, your interviews without a scorecard and your interviews in the next 7 days.",
     "args": {}},
    {"name": "my_interviews", "capability": "ANY_STAFF", "writes": False,
     "description": "Interviews assigned to me (soonest first): application code, job, time, status and whether my scorecard is submitted.",
     "args": {
         "upcomingOnly": "boolean, optional, only scheduled interviews in the future",
     }},
    {"name": "fairness_summary", "capability": "JOBS_MANAGE", "writes": False,
     "description": "Fairness (adverse impact) summary of a job, or of the whole organisation when jobId is omitted (administrators): flagged stages, impact ratios, survey response rate.",
     "args": {
         "jobId": "integer, optional, 1..",
     }},
    {"name": "explain", "capability": "ANY_STAFF", "writes": False,
     "description": "Short built-in help on how RecruitPro works (verified coverage, evidence tiers, ranking groups, knockouts, blind review, work samples, interview kits, fairness, verification, the assistant).",
     "args": {
         "topic": "string, required, one of VERIFIED_COVERAGE|EVIDENCE_TIERS|RANKING_GROUPS|KNOCKOUTS|BLIND_REVIEW|WORK_SAMPLES|INTERVIEW_KITS|FAIRNESS_REPORT|VERIFICATION|ASSISTANT",
     }},
    {"name": "create_job", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Create a job posting from the draft fields (shown as a preview with wording warnings; the user chooses Save as draft or Publish). Ask for missing required fields first.",
     "args": {
         "title": "string, required",
         "department": "string, required",
         "location": "string, required",
         "jobType": "string, required, one of FULL_TIME|PART_TIME|CONTRACT|INTERNSHIP",
         "experienceLevel": "string, required, one of ENTRY|MID|SENIOR|LEAD|EXECUTIVE",
         "salaryMin": "number, optional",
         "salaryMax": "number, optional",
         "openings": "integer, optional, 1..10000, default 1",
         "deadline": "ISO-8601 date-time, optional, application deadline in the future",
         "summary": "string, optional",
         "responsibilities": "string or list of strings, optional",
         "description": "string, optional, full description; built from summary and responsibilities when omitted",
         "requirements": "list of {type, description, mandatory, knockout, minimumYears, alternatives, proficiency}, optional",
         "publish": "boolean, optional, default false (save as draft)",
     }},
    {"name": "update_job_status", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Change a job's status: PUBLISHED (publish or resume), ON_HOLD, CLOSED or CANCELLED (drafts only).",
     "args": {
         "jobId": "integer, required, 1..",
         "status": "string, required, one of PUBLISHED|ON_HOLD|CLOSED|CANCELLED",
     }},
    {"name": "decide_application", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Record a decision on an application: SHORTLIST, REJECT (reason required), MOVE_TO_INTERVIEW or SELECT.",
     "args": {
         "applicationCode": "application code like A-3014, required",
         "decision": "string, required, one of SHORTLIST|REJECT|MOVE_TO_INTERVIEW|SELECT",
         "reason": "string, optional, required for REJECT",
     }},
    {"name": "assign_work_sample", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Assign an active work sample of the job to a shortlisted or interviewing applicant (the job's only active sample when workSampleId is omitted).",
     "args": {
         "applicationCode": "application code like A-3014, required",
         "workSampleId": "integer, optional, 1..",
         "dueAt": "ISO-8601 date-time, optional, latest start time, in the future",
     }},
    {"name": "schedule_interview", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Schedule an interview for a shortlisted or interviewing applicant at a future time with a staff member (interviewer: name or e-mail, or interviewerUserId; default: me); moves a shortlisted application to interviewing.",
     "args": {
         "applicationCode": "application code like A-3014, required",
         "scheduledAt": "ISO-8601 date-time, required, in the future",
         "interviewerUserId": "integer, optional, 1..",
         "interviewer": "string, optional, staff name or e-mail; default: me",
         "durationMinutes": "integer, optional, 5..480, default 45",
         "location": "string, optional",
     }},
    {"name": "generate_work_sample", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Draft a work sample (task with an anchored 1-4 rubric) for a job; saved as a draft to review and activate.",
     "args": {
         "jobId": "integer, required, 1..",
     }},
    {"name": "generate_interview_kit", "capability": "JOBS_MANAGE", "writes": True,
     "description": "Draft an interview kit (structured questions with anchored ratings per requirement) for a job; replaces the job's current kit.",
     "args": {
         "jobId": "integer, required, 1..",
     }},
]


def tools_for(role: str) -> list[dict[str, Any]]:
    """The catalogue the backend sends to a user of ``role`` (``ToolRegistry.catalogueFor``)."""
    return [{key: tool[key] for key in ("name", "description", "args", "writes")}
            for tool in _BACKEND_TOOLS if role in _ROLES[tool["capability"]]]


REFERENCE_TOOLS = tools_for("HR_MANAGER")
RECRUITER_TOOLS = tools_for("RECRUITER")
INTERVIEWER_TOOLS = tools_for("INTERVIEWER")

LEGACY_TOOLS: list[dict[str, Any]] = [
    {"name": "list_jobs", "description": "Jobs of the user's organisation with status, applicant count and deadline.",
     "args": {"status": "string, optional, one of DRAFT|PUBLISHED|ON_HOLD|CLOSED|CANCELLED", "query": "string, optional"},
     "writes": False},
    {"name": "job_insights", "description": "Pipeline counts, ranking groups, top applicants, most-missing requirements.",
     "args": {"jobId": "integer, required"}, "writes": False},
    {"name": "applicant_insights", "description": "Evidence per requirement, gates, verification, work samples, interviews.",
     "args": {"applicationId": "integer, required"}, "writes": False},
    {"name": "compare_applicants", "description": "Side-by-side requirement evidence for 2-4 applicants.",
     "args": {"applicationIds": "array of integer, required"}, "writes": False},
    {"name": "attention_digest", "description": "What needs the user's attention now.", "args": {}, "writes": False},
    {"name": "my_interviews", "description": "The user's interviews.", "args": {"range": "string, optional, one of today|week|upcoming"},
     "writes": False},
    {"name": "fairness_summary", "description": "Adverse-impact summary for a job.", "args": {"jobId": "integer, optional"},
     "writes": False},
    {"name": "explain", "description": "Built-in help text for a topic.", "args": {"topic": "string, required"}, "writes": False},
    {"name": "create_job", "description": "Create a job (draft or publish chosen at confirmation).",
     "args": {"title": "string, required", "department": "string, required", "location": "string, required",
              "jobType": "string, required", "experienceLevel": "string, required", "vacancies": "integer, optional",
              "deadline": "string, optional", "minimumSalary": "number, optional", "maximumSalary": "number, optional",
              "summary": "string, optional", "responsibilities": "array, optional", "description": "string, optional",
              "requirements": "array, optional"}, "writes": True},
    {"name": "update_job_status", "description": "Publish, hold, close or cancel a job.",
     "args": {"jobId": "integer, required", "status": "string, required, one of PUBLISHED|ON_HOLD|CLOSED|CANCELLED"},
     "writes": True},
    {"name": "decide_application", "description": "Shortlist, reject (with reason), move to interview or select.",
     "args": {"applicationId": "integer, required", "decision": "string, required, one of SHORTLIST|REJECT|INTERVIEW|SELECT",
              "reason": "string, optional"}, "writes": True},
    {"name": "assign_work_sample", "description": "Assign the job's work sample to an applicant.",
     "args": {"applicationId": "integer, required", "dueInDays": "integer, optional"}, "writes": True},
    {"name": "schedule_interview", "description": "Schedule a structured interview.",
     "args": {"applicationId": "integer, required", "startsAt": "string, required (local ISO date-time)",
              "durationMinutes": "integer, optional"}, "writes": True},
    {"name": "generate_work_sample", "description": "Generate a work sample from the job's requirements.",
     "args": {"jobId": "integer, required"}, "writes": True},
    {"name": "generate_interview_kit", "description": "Generate an interview kit from the job's requirements.",
     "args": {"jobId": "integer, required"}, "writes": True},
]
