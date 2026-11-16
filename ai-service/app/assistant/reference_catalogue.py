"""The tool catalogue the planner is designed and tested against (docs/plan/AS-PY-NOTES.md section 4).

The backend owns the real registry and sends the user's filtered catalogue with every step; argument names
here are the recommended ones (the planner also accepts common aliases, see ``catalogue._ALIASES``).
"""
from __future__ import annotations

from typing import Any

REFERENCE_TOOLS: list[dict[str, Any]] = [
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
    {"name": "create_job", "description": "Create a job (draft or publish chosen at confirmation).", "args": {}, "writes": True},
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

INTERVIEWER_TOOLS = [tool for tool in REFERENCE_TOOLS if tool["name"] in {"my_interviews", "explain"}]
RECRUITER_TOOLS = [tool for tool in REFERENCE_TOOLS if tool["name"] not in {"create_job", "update_job_status",
                                                                            "generate_work_sample", "generate_interview_kit"}]
