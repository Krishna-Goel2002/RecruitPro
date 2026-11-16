"""Answers composed from tool observations: short text plus metrics / table / candidates / link / notice blocks.

Rules: only numbers present in the observation are shown (counts of a returned list are the one derived figure);
candidates are referred to by application code; coverage is described as evidenced requirements, never as a
probability. Observation data is untrusted: it is rendered as data and never interpreted as instructions.
The expected observation shapes are documented in docs/plan/AS-PY-NOTES.md; unknown shapes get a generic rendering.
"""
from __future__ import annotations

import re
from typing import Any

from app.assistant.text import code_to_id

MAX_ROWS = 10
MAX_CELL = 160
COVERAGE_NOTE = "Coverage counts requirements with evidence; it is not a probability of success."
GROUP_LABELS = {"RANKED": "Ranked", "MUST_HAVES_UNVERIFIED": "Must-haves unverified", "UNSCORED": "Unscored"}
STATUS_ORDER = ("SUBMITTED", "SHORTLISTED", "INTERVIEWING", "SELECTED", "REJECTED", "WITHDRAWN")
_LABELS = {
    "SUBMITTED": "Submitted", "SHORTLISTED": "Shortlisted", "INTERVIEWING": "Interviewing", "SELECTED": "Selected",
    "REJECTED": "Rejected", "WITHDRAWN": "Withdrawn", "DRAFT": "Draft", "PUBLISHED": "Published", "ON_HOLD": "On hold",
    "CLOSED": "Closed", "CANCELLED": "Cancelled", "MATCHED": "Matched", "UNCLEAR": "Unclear", "NOT_OBSERVED": "Not observed",
}
_DIGEST_LABELS = {
    "newApplicants": "New applicants", "newApplications": "New applicants", "unscoredWorkSamples": "Work samples to score",
    "workSamplesToScore": "Work samples to score", "interviewsWithoutScorecards": "Interviews without scorecards",
    "missingScorecards": "Interviews without scorecards", "expiringDeadlines": "Deadlines this week",
    "deadlinesSoon": "Deadlines this week", "verdictsAwaitingReview": "Verdicts awaiting review",
    "pendingVerdicts": "Verdicts awaiting review", "upcomingInterviews": "Upcoming interviews",
    "claimsToVerify": "Claims to verify", "mustHavesUnverified": "Must-haves unverified",
}


def get(data: Any, *keys: str, default: Any = None) -> Any:
    if not isinstance(data, dict):
        return default
    for key in keys:
        if key in data and data[key] is not None:
            return data[key]
    return default


def label(value: Any) -> str:
    text = str(value)
    return _LABELS.get(text, text.replace("_", " ").capitalize() if text.isupper() else text)


def cell(value: Any) -> str:
    if value is None:
        return "–"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, float):
        return f"{value:.1f}".rstrip("0").rstrip(".")
    if isinstance(value, (list, tuple)):
        return ", ".join(cell(item) for item in value[:5])[:MAX_CELL]
    if isinstance(value, dict):
        return ", ".join(f"{k}: {cell(v)}" for k, v in list(value.items())[:4])[:MAX_CELL]
    text = " ".join(str(value).split())
    return text if len(text) <= MAX_CELL else text[:MAX_CELL - 1] + "…"


def pct(value: Any) -> str | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return f"{value:.1f}".rstrip("0").rstrip(".") + "%"


def safe_href(value: Any) -> str | None:
    """Only same-app relative links: a link from data must never point elsewhere."""
    if isinstance(value, str) and re.fullmatch(r"/[A-Za-z0-9/_\-?=&.%]{0,300}", value) and not value.startswith("//"):
        return value
    return None


def link(label_text: str, href: str | None) -> dict[str, Any] | None:
    return {"kind": "link", "label": label_text, "href": href} if href else None


def notice(text: str, tone: str = "info") -> dict[str, Any]:
    return {"kind": "notice", "tone": tone, "text": text}


def _list(data: Any, *keys: str) -> list[Any]:
    if isinstance(data, list):
        return data
    value = get(data, *keys)
    return value if isinstance(value, list) else []


def applicant_href(item: dict[str, Any], job_id: Any) -> str | None:
    href = safe_href(get(item, "link", "href"))
    if href:
        return href
    app_id = get(item, "applicationId", "id") or code_to_id(get(item, "code", "applicationCode"))
    job = get(item, "jobId") or job_id
    return f"/jobs/{job}/applicants/{app_id}" if app_id and job else None


def candidate_items(items: list[Any], job_id: Any = None) -> list[dict[str, Any]]:
    result = []
    for item in items[:MAX_ROWS]:
        if not isinstance(item, dict):
            continue
        code = get(item, "code", "applicationCode")
        app_id = get(item, "applicationId") or code_to_id(code)
        if not code and app_id:
            code = f"A-{app_id}"
        gates = get(item, "gates", "knockoutsVerified", "unverifiedKnockouts")
        result.append({
            "applicationId": app_id, "code": code, "revealedName": None,
            "status": get(item, "status"), "verified": get(item, "verified", "verifiedCoverage"),
            "claimed": get(item, "claimed", "claimedCoverage"), "group": get(item, "group", "rankGroup"),
            "gates": gates, "link": applicant_href(item, job_id),
        })
    return result


def _metrics(pairs: list[tuple[str, Any, str | None]]) -> dict[str, Any] | None:
    items = [{"label": name, "value": value, **({"hint": hint} if hint else {})}
             for name, value, hint in pairs if value is not None and not isinstance(value, (dict, list))]
    return {"kind": "metrics", "items": items[:8]} if items else None


def _blocks(*blocks: Any) -> list[dict[str, Any]]:
    return [block for block in blocks if block]


def _count_dict(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return {str(k): v for k, v in value.items() if isinstance(v, (int, float)) and not isinstance(v, bool)}
    if isinstance(value, list):  # [{"status": "SUBMITTED", "count": 4}]
        result = {}
        for item in value:
            key = get(item, "status", "group", "name", "label")
            count = get(item, "count", "value", "total")
            if key is not None and isinstance(count, (int, float)):
                result[str(key)] = count
        return result
    return {}


# ------------------------------------------------------------------------------------------- composers
def compose_list_jobs(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    jobs = [job for job in _list(data, "jobs", "items", "content") if isinstance(job, dict)]
    if not jobs:
        return "No jobs match that.", _blocks(link("Open jobs", "/jobs"))
    rows = [[cell(get(job, "title")), label(get(job, "status", default="–")),
             cell(get(job, "applicants", "applicationCount", "applications")), cell(get(job, "deadline"))] for job in jobs[:MAX_ROWS]]
    total = get(data, "total", "totalElements")
    shown = f"{len(jobs)}" if not isinstance(total, int) or total == len(jobs) else f"{min(len(jobs), MAX_ROWS)} of {total}"
    text = f"Here are your jobs ({shown})."
    return text, _blocks({"kind": "table", "columns": ["Job", "Status", "Applicants", "Deadline"], "rows": rows},
                         link("Open all jobs", "/jobs"))


def compose_job_insights(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    job = get(data, "job", default={}) if isinstance(get(data, "job"), dict) else {}
    job_id = get(job, "id", "jobId") or get(data, "jobId") or getattr(context, "jobId", None)
    title = get(job, "title") or get(data, "title") or getattr(context, "jobTitle", None) or "This job"
    status = get(job, "status") or get(data, "status")
    pipeline = _count_dict(get(data, "pipeline", "statusCounts", "counts", "byStatus"))
    groups = _count_dict(get(data, "groups", "rankingGroups", "byGroup"))
    total = get(data, "total", "applicants", "totalApplicants", "applicationCount")
    if not isinstance(total, (int, float)) or isinstance(total, bool):
        total = None
    parts = [f"**{cell(title)}**" + (f" is {label(status).lower()}" if status else "")]
    if total is not None:
        parts.append(f"with {total} applicant{'s' if total != 1 else ''}")
    text = " ".join(parts) + "."
    if groups:
        text += " Ranking: " + ", ".join(f"{groups[key]} {GROUP_LABELS.get(key, label(key)).lower()}" for key in
                                         [k for k in GROUP_LABELS if k in groups] + [k for k in groups if k not in GROUP_LABELS]) + "."
    metric_pairs: list[tuple[str, Any, str | None]] = [("Applicants", total, None)]
    for key in [k for k in STATUS_ORDER if k in pipeline] + [k for k in pipeline if k not in STATUS_ORDER]:
        metric_pairs.append((label(key), pipeline[key], None))
    blocks: list[Any] = [_metrics(metric_pairs)]
    if groups:
        blocks.append(_metrics([(GROUP_LABELS.get(key, label(key)), value, None) for key, value in groups.items()]))
    top = _list(get(data, "top", "topApplicants", "candidates", "applicants"))
    if top:
        blocks.append({"kind": "candidates", "items": candidate_items(top, job_id)})
        text += f" Top {min(len(top), MAX_ROWS)} by verified coverage are listed below. {COVERAGE_NOTE}"
    missing = [item for item in _list(get(data, "mostMissing", "missingRequirements", "missing")) if isinstance(item, dict)]
    if missing:
        rows = [[cell(get(item, "description", "requirement")), cell(get(item, "notObserved", "count", "missingCount")),
                 "yes" if get(item, "knockout") else "–"] for item in missing[:MAX_ROWS]]
        blocks.append({"kind": "table", "columns": ["Requirement", "Applicants without evidence", "Gate"], "rows": rows,
                       "caption": "Most often not observed (this does not mean applicants lack it)"})
    verification = _count_dict(get(data, "verification", "verificationStatus"))
    if verification:
        blocks.append(_metrics([(f"Verification: {label(key).lower()}", value, None) for key, value in verification.items()]))
    stage = get(data, "timeInStage", "avgDaysInStage")
    if isinstance(stage, dict) and stage:
        rows = [[label(key), cell(value)] for key, value in list(stage.items())[:MAX_ROWS]]
        blocks.append({"kind": "table", "columns": ["Stage", "Average days"], "rows": rows})
    href = safe_href(get(data, "link")) or (f"/jobs/{job_id}" if job_id else None)
    blocks.append(link(f"Open {cell(title)}", href))
    if groups.get("MUST_HAVES_UNVERIFIED"):
        blocks.append(notice("Unverified must-haves are ranking gates, not rejections: a work sample, an interview or "
                             "verifying a claim can clear them."))
    return text, _blocks(*blocks)


def compose_applicant(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    code = get(data, "code", "applicationCode") or getattr(context, "applicationCode", None) or "This applicant"
    job_id = get(data, "jobId") or getattr(context, "jobId", None)
    status, group = get(data, "status"), get(data, "group", "rankGroup")
    verified, claimed = get(data, "verified", "verifiedCoverage"), get(data, "claimed", "claimedCoverage")
    text = f"**{cell(code)}**"
    if status:
        text += f" is {label(status).lower()}"
    if pct(verified):
        text += f"; verified coverage {pct(verified)}" + (f" (claimed {pct(claimed)})" if pct(claimed) else "")
    text += "."
    if group:
        text += f" Ranking group: {GROUP_LABELS.get(str(group), label(group))}."
    blocks: list[Any] = [_metrics([("Status", label(status) if status else None, None),
                                   ("Verified coverage", pct(verified), "evidence at RESUME_STATED or higher"),
                                   ("Claimed coverage", pct(claimed), "includes the candidate's own claims"),
                                   ("Group", GROUP_LABELS.get(str(group), label(group)) if group else None, None)])]
    requirements = [item for item in _list(get(data, "requirements", "evidence", "results")) if isinstance(item, dict)]
    if requirements:
        rows = [[cell(get(item, "description", "requirement")), label(get(item, "status", default="–")),
                 cell(get(item, "tier")), "gate" if get(item, "knockout") else ("must" if get(item, "mandatory") else "nice")]
                for item in requirements[:MAX_ROWS + 5]]
        blocks.append({"kind": "table", "columns": ["Requirement", "Result", "Evidence tier", "Weight"], "rows": rows})
    unverified = get(data, "unverifiedKnockouts", "unverifiedGates")
    if isinstance(unverified, list) and unverified:
        names = ", ".join(cell(get(item, "description") if isinstance(item, dict) else item) for item in unverified[:5])
        blocks.append(notice(f"Must-have gate{'s' if len(unverified) != 1 else ''} not yet verified: {names}. This is a "
                             "ranking gate, not a rejection.", "warning"))
    for key, title_text in (("workSamples", "Work samples"), ("interviews", "Interviews"), ("verification", "Verification")):
        items = [item for item in _list(get(data, key)) if isinstance(item, dict)]
        if items:
            columns = [column for column in ("title", "status", "score", "rating", "verdict", "completedAt", "scheduledAt")
                       if any(column in item for item in items)][:4]
            if columns:
                blocks.append({"kind": "table", "caption": title_text, "columns": [label(c) if c.isupper() else c[:1].upper() + c[1:]
                                                                                   for c in columns],
                               "rows": [[cell(item.get(column)) for column in columns] for item in items[:MAX_ROWS]]})
    questions = [cell(get(item, "text", "question", "hint") if isinstance(item, dict) else item)
                 for item in _list(get(data, "openQuestions", "hints", "probes"))][:5]
    if questions:
        text += "\n\nOpen questions to probe:\n" + "\n".join(f"- {question}" for question in questions)
    if pct(verified):
        text += f"\n\n{COVERAGE_NOTE}"
    href = safe_href(get(data, "link")) or applicant_href({"code": code}, job_id)
    blocks.append(link(f"Open {cell(code)}", href))
    return text, _blocks(*blocks)


def compose_compare(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    applicants = [item for item in _list(data, "applicants", "items") if isinstance(item, dict)]
    job_id = get(data, "jobId") or getattr(context, "jobId", None)
    if not applicants:
        return "I could not find those applicants to compare.", []
    codes = [cell(get(item, "code", "applicationCode")) for item in applicants]
    blocks: list[Any] = [{"kind": "candidates", "items": candidate_items(applicants, job_id)}]
    matrix = [item for item in _list(get(data, "requirements", "matrix")) if isinstance(item, dict)]
    if matrix:
        rows = []
        for item in matrix[:MAX_ROWS + 5]:
            results = get(item, "results", "byApplicant", default={})
            row = [cell(get(item, "description", "requirement"))]
            for code in codes:
                value = results.get(code) if isinstance(results, dict) else None
                if isinstance(value, dict):
                    value = f"{label(get(value, 'status', default='–'))}" + (f" ({get(value, 'tier')})" if get(value, "tier") else "")
                row.append(label(value) if value else "–")
            rows.append(row)
        blocks.append({"kind": "table", "columns": ["Requirement", *codes], "rows": rows})
    else:
        rows = [[cell(get(item, "code", "applicationCode")), GROUP_LABELS.get(str(get(item, "group")), cell(get(item, "group"))),
                 pct(get(item, "verified", "verifiedCoverage")) or "–", pct(get(item, "claimed", "claimedCoverage")) or "–",
                 label(get(item, "status", default="–"))] for item in applicants]
        blocks.append({"kind": "table", "columns": ["Applicant", "Group", "Verified", "Claimed", "Status"], "rows": rows})
    text = f"Comparison of {', '.join(codes)} by requirement evidence. {COVERAGE_NOTE}"
    return text, _blocks(*blocks)


def compose_digest(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    if not isinstance(data, dict) or not data:
        return "Nothing needs your attention right now.", []
    counts: list[tuple[str, Any, str | None]] = []
    tables: list[Any] = []
    for key, value in data.items():
        name = _DIGEST_LABELS.get(key, key[:1].upper() + re.sub(r"(?<!^)(?=[A-Z])", " ", key[1:]).lower())
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            counts.append((name, value, None))
        elif isinstance(value, list):
            counts.append((name, len(value), None))
            rows = [item for item in value if isinstance(item, dict)][:5]
            if rows:
                columns = [c for c in ("code", "jobTitle", "title", "status", "dueAt", "deadline", "scheduledAt", "startsAt")
                           if any(c in row for row in rows)][:4]
                if columns:
                    tables.append({"kind": "table", "caption": name,
                                   "columns": [c[:1].upper() + re.sub(r"(?<!^)(?=[A-Z])", " ", c[1:]).lower() for c in columns],
                                   "rows": [[cell(row.get(c)) for c in columns] for row in rows]})
    busy = [(name, value) for name, value, _ in counts if value]
    if not busy:
        return "Nothing needs your attention right now.", _blocks(_metrics(counts))
    text = "Here is what needs your attention: " + "; ".join(f"{value} {name.lower()}" for name, value in busy) + "."
    return text, _blocks(_metrics(counts), *tables)


def compose_interviews(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    items = [item for item in _list(data, "interviews", "items") if isinstance(item, dict)]
    if not items:
        return "You have no interviews in that period.", _blocks(link("Open my interviews", "/interviews"))
    rows = [[cell(get(item, "startsAt", "scheduledAt", "start")), cell(get(item, "code", "applicationCode")),
             cell(get(item, "jobTitle", "job")), label(get(item, "status", default="–")),
             "yes" if get(item, "scorecardSubmitted") else ("no" if get(item, "scorecardSubmitted") is False else "–")]
            for item in items[:MAX_ROWS]]
    text = f"You have {len(items)} interview{'s' if len(items) != 1 else ''}."
    pending = sum(1 for item in items if get(item, "scorecardSubmitted") is False and label(get(item, "status", default="")) == "Completed")
    if pending:
        text += f" {pending} completed interview{'s' if pending != 1 else ''} still need{'s' if pending == 1 else ''} a scorecard."
    return text, _blocks({"kind": "table", "columns": ["When", "Applicant", "Job", "Status", "Scorecard"], "rows": rows},
                         link("Open my interviews", "/interviews"))


def compose_fairness(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    job_id = get(data, "jobId") or getattr(context, "jobId", None)
    ratio = get(data, "adverseImpactRatio", "impactRatio", "ratio")
    flagged = get(data, "flagged", "belowThreshold")
    groups = [item for item in _list(get(data, "groups", "rates")) if isinstance(item, dict)]
    text = "Fairness summary"
    if isinstance(ratio, (int, float)) and not isinstance(ratio, bool):
        text += f": lowest adverse-impact ratio {ratio:.2f}"
        text += " — below the 0.8 four-fifths guideline, worth a review." if ratio < 0.8 else " (at or above the 0.8 guideline)."
    else:
        text += ": not enough data to compute an adverse-impact ratio yet."
    blocks: list[Any] = []
    if groups:
        rows = [[cell(get(item, "group", "name")), cell(get(item, "applicants", "total")), cell(get(item, "selected", "advanced")),
                 cell(get(item, "rate", "selectionRate"))] for item in groups[:MAX_ROWS]]
        blocks.append({"kind": "table", "columns": ["Group", "Applicants", "Advanced", "Rate"], "rows": rows,
                       "caption": "Voluntary self-reported groups; small groups are suppressed"})
    if flagged:
        blocks.append(notice("At least one group falls below the four-fifths guideline. This is a monitoring signal for "
                             "review, not a legal finding.", "warning"))
    href = safe_href(get(data, "link")) or (f"/jobs/{job_id}?tab=fairness" if job_id else "/admin/fairness")
    blocks.append(link("Open the fairness report", href))
    return text, _blocks(*blocks)


def compose_explain(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    text = get(data, "text", "answer", "body") if isinstance(data, dict) else (data if isinstance(data, str) else None)
    return (cell(text) if text and len(str(text)) <= MAX_CELL else str(text)[:2000]) if text else "I have no help text for that topic.", []


def compose_generic(data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    blocks: list[Any] = []
    if isinstance(data, dict):
        blocks.append(_metrics([(key, value, None) for key, value in data.items()
                                if isinstance(value, (int, float, str)) and not isinstance(value, bool) and len(str(value)) <= 40]))
        lists = [value for value in data.values() if isinstance(value, list) and value and isinstance(value[0], dict)]
        rows_source = lists[0] if lists else []
    else:
        rows_source = [item for item in data if isinstance(item, dict)] if isinstance(data, list) else []
    if rows_source:
        columns = list(dict.fromkeys(key for row in rows_source[:MAX_ROWS] for key in row if key not in {"link", "href"}))[:6]
        blocks.append({"kind": "table", "columns": columns, "rows": [[cell(row.get(c)) for c in columns] for row in rows_source[:MAX_ROWS]]})
    return "Here is what I found.", _blocks(*blocks)


COMPOSERS = {
    "list_jobs": compose_list_jobs, "job_insights": compose_job_insights, "applicant_insights": compose_applicant,
    "compare_applicants": compose_compare, "attention_digest": compose_digest, "my_interviews": compose_interviews,
    "fairness_summary": compose_fairness, "explain": compose_explain,
}


def compose(tool: str, data: Any, context: Any) -> tuple[str, list[dict[str, Any]]]:
    composer = COMPOSERS.get(tool, compose_generic)
    try:
        return composer(data, context)
    except Exception:  # noqa: BLE001 - an unexpected data shape must still give an answer
        return compose_generic(data, context)
