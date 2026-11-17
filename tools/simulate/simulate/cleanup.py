"""Best-effort undo of a run, from ``<runs-dir>/<run-id>/state.json``.

What it does:
* every application the run created is withdrawn by its candidate (``PATCH /candidates/me/applications/{id}/withdraw``);
* with ``--delete-accounts`` every candidate account is deleted (``DELETE /candidates/me``: the account is
  anonymised and its resumes, facts and claims are deleted). This route is limited to 5 calls per 15 minutes per IP,
  so it is paced (about 3 minutes per account) unless the backend runs with ``RATE_LIMIT_ENABLED=false``;
* jobs **created** by the run are closed (PUBLISHED/ON_HOLD -> CLOSED) or, when still a draft without applicants,
  deleted. Jobs the run only reused are left alone (``--close-reused`` closes them too).

What cannot be removed through the API (by design, for audit and fairness history): closed jobs, applications
(they stay as WITHDRAWN), decision history, audit events, match runs, and user rows (deletion anonymises them).
Without ``--delete-accounts`` the candidate accounts and their uploaded resumes stay. For a clean slate locally use
``docker compose down -v`` (drops the database volume; the demo data is seeded again on the next start).
"""
from __future__ import annotations

from typing import Any, Callable

import httpx

from .api import CANDIDATE, STAFF, ApiError, ApiSession, ApiStats, RateGate, RetryPolicy
from .clock import Clock
from .state import RunState


def cleanup(*, base_url: str, run_id: str, runs_dir: str, hr_email: str | None, hr_password: str | None,
            hr_totp_secret: str | None = None, delete_accounts: bool = False, close_reused: bool = False,
            pacing: bool = True, transport: httpx.BaseTransport | None = None, clock: Clock | None = None,
            out: Callable[[str], None] = print) -> dict[str, Any]:
    clock = clock or Clock()
    state = RunState.open(runs_dir, run_id, create=False)
    stats = ApiStats()
    gate = RateGate(clock, enabled=pacing, stats=stats)
    summary: dict[str, Any] = {"withdrawn": 0, "alreadyClosed": 0, "accountsDeleted": 0, "jobsClosed": 0,
                               "jobsDeleted": 0, "skipped": [], "errors": []}
    with httpx.Client(base_url=base_url.rstrip("/") + "/", transport=transport, timeout=60) as client:
        def session(portal: str, email: str | None, password: str | None, totp: str | None = None) -> ApiSession:
            return ApiSession(client, portal=portal, gate=gate, clock=clock, stats=stats, retry=RetryPolicy(),
                              email=email, password=password, totp_secret=totp)

        for resume_id, cand in sorted(state.data.get("candidates", {}).items()):
            if cand.get("deleted"):
                continue
            if not cand.get("email") or not cand.get("password"):
                continue
            s = session(CANDIDATE, cand["email"], cand["password"])
            try:
                s.login()
            except ApiError as exc:
                summary["errors"].append(f"{resume_id}: sign-in failed ({exc.status} {exc.message})")
                continue
            for job_key, app_id in (cand.get("applications") or {}).items():
                if cand.get("withdrawn", {}).get(job_key):
                    continue
                try:
                    s.request("PATCH", f"candidates/me/applications/{app_id}/withdraw")
                    summary["withdrawn"] += 1
                except ApiError as exc:
                    if exc.status == 400:  # already withdrawn, rejected or selected
                        summary["alreadyClosed"] += 1
                    else:
                        summary["errors"].append(f"{resume_id}/{job_key}: withdraw {exc.status} {exc.message}")
                        continue
                cand.setdefault("withdrawn", {})[job_key] = True
                state.save()
            if delete_accounts:
                try:
                    s.request("DELETE", "candidates/me", json={"password": cand["password"]})
                    cand["deleted"] = True
                    state.save()
                    summary["accountsDeleted"] += 1
                    out(f"deleted account {cand['email']}")
                except ApiError as exc:
                    summary["errors"].append(f"{resume_id}: delete account {exc.status} {exc.message}")

        jobs = state.data.get("jobs", {})
        targets = {k: j for k, j in jobs.items() if j.get("id") and (j.get("created") or close_reused)}
        for k, j in jobs.items():
            if k not in targets:
                summary["skipped"].append(f"job {k} #{j.get('id')} was reused, left open")
        if targets and not (hr_email and hr_password):
            summary["skipped"].append("jobs not closed: pass --hr-email and --hr-password")
        elif targets:
            hr = session(STAFF, hr_email, hr_password, hr_totp_secret)
            hr.login()
            for key, j in targets.items():
                try:
                    job = hr.request("GET", f"jobs/{j['id']}")
                    status = job.get("status")
                    if status == "DRAFT" and not job.get("applicationCount"):
                        hr.request("DELETE", f"jobs/{j['id']}")
                        summary["jobsDeleted"] += 1
                        j["status"] = "DELETED"
                    elif status in ("PUBLISHED", "ON_HOLD"):
                        hr.request("PATCH", f"jobs/{j['id']}/status", json={"status": "CLOSED"})
                        summary["jobsClosed"] += 1
                        j["status"] = "CLOSED"
                    elif status == "DRAFT":
                        hr.request("PATCH", f"jobs/{j['id']}/status", json={"status": "CANCELLED"})
                        j["status"] = "CANCELLED"
                        summary["jobsClosed"] += 1
                    state.save()
                except ApiError as exc:
                    if exc.status == 404:
                        j["status"] = "DELETED"
                        state.save()
                    else:
                        summary["errors"].append(f"job {key}: {exc.status} {exc.message}")
    summary["api"] = stats.as_dict()
    out(f"cleanup {run_id}: withdrawn {summary['withdrawn']} (already closed {summary['alreadyClosed']}), "
        f"accounts deleted {summary['accountsDeleted']}, jobs closed {summary['jobsClosed']}, "
        f"jobs deleted {summary['jobsDeleted']}, errors {len(summary['errors'])}")
    for line in summary["skipped"] + summary["errors"]:
        out(f"  - {line}")
    return summary
