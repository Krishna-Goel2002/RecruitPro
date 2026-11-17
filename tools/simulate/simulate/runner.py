"""The simulation: jobs -> candidates (register, profile, upload, analysis, apply) -> matching -> ranking -> report."""
from __future__ import annotations

import http.cookiejar
import logging
import re
import secrets
import string
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import httpx

from . import metrics
from .api import CANDIDATE, STAFF, ApiError, ApiSession, ApiStats, RateGate, RetryPolicy
from .clock import Clock
from .corpus import BACKEND_RESUME_TYPES, LABEL_GAIN, Corpus, Job, Resume, load_corpus
from .report import write_report
from .state import RunState

log = logging.getLogger("simulate.runner")

TERMINAL_ANALYSIS = {"COMPLETED", "PARTIAL", "FAILED", "NEEDS_OCR", "UNSUPPORTED_FORMAT"}
SETTLED_MATCH = {"READY", "NO_REQUIREMENTS", "ANALYSIS_UNAVAILABLE", "FAILED"}
OPEN_JOB = ("PUBLISHED", "ON_HOLD", "DRAFT")
CONTENT_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "doc": "application/msword",
    "txt": "text/plain",
    "rtf": "application/rtf",
    "odt": "application/vnd.oasis.opendocument.text",
    "png": "image/png",
    "jpg": "image/jpeg",
}


@dataclass
class RunConfig:
    corpus: Path
    base_url: str
    hr_email: str
    hr_password: str
    hr_totp_secret: str | None = None
    jobs: list[str] | None = None
    limit: int | None = None
    concurrency: int = 4
    publish: bool = True
    report_dir: Path | None = None
    runs_dir: Path = Path("runs")
    run_id: str | None = None
    pacing: bool = True
    analysis_timeout: float = 300.0
    match_timeout: float = 900.0
    poll_interval: float = 2.0
    max_poll_interval: float = 15.0
    request_timeout: float = 60.0
    upload_timeout: float = 180.0
    email_domain: str | None = "sim.recruitpro.test"
    mailpit_url: str | None = None
    fresh_jobs: bool = False
    apply_to: str = "target"
    max_attempts: int = 6
    allowed_types: tuple[str, ...] = BACKEND_RESUME_TYPES
    include_unsupported: bool = False


class _NoCookies(http.cookiejar.DefaultCookiePolicy):
    """Sessions are bearer-only; never mix the portals' refresh cookies of many accounts in one shared jar."""

    def set_ok(self, cookie, request):  # noqa: D401 - cookiejar API
        return False


def new_run_id() -> str:
    alphabet = string.ascii_lowercase + string.digits
    return "sim" + "".join(secrets.choice(alphabet) for _ in range(6))


def new_password() -> str:
    # 10-72 chars with a letter and a digit (backend PasswordPolicy); ASCII only so it stays under 72 bytes.
    return "Sim-" + secrets.token_urlsafe(18) + "a7"


def _slug(text: str, limit: int = 30) -> str:
    text = re.sub(r"[^a-z0-9._-]+", ".", (text or "").lower()).strip(".-_")
    return re.sub(r"\.{2,}", ".", text)[:limit].strip(".") or "candidate"


def _norm_requirement(req: dict[str, Any]) -> tuple:
    mandatory = True if req.get("mandatory") is None else bool(req.get("mandatory"))
    knockout = mandatory if req.get("knockout") is None else bool(req.get("knockout"))
    return (str(req.get("type") or "").upper(), " ".join(str(req.get("description") or "").split()), mandatory,
            knockout, req.get("minimumYears"), tuple(req.get("alternatives") or ()), req.get("proficiency"))


class Runner:
    def __init__(self, cfg: RunConfig, *, transport: httpx.BaseTransport | None = None,
                 mailpit_transport: httpx.BaseTransport | None = None, clock: Clock | None = None,
                 out: Callable[[str], None] | None = None) -> None:
        self.cfg = cfg
        self.clock = clock or Clock()
        self.out = out or (lambda line: print(line, flush=True))
        self.stats = ApiStats()
        self.gate = RateGate(self.clock, enabled=cfg.pacing, stats=self.stats)
        self.retry = RetryPolicy(max_attempts=cfg.max_attempts)
        limits = httpx.Limits(max_connections=max(8, cfg.concurrency * 2 + 4))
        self.client = httpx.Client(base_url=cfg.base_url.rstrip("/") + "/", transport=transport,
                                   timeout=cfg.request_timeout, limits=limits,
                                   cookies=http.cookiejar.CookieJar(policy=_NoCookies()))
        self.mailpit_transport = mailpit_transport
        self.run_id = cfg.run_id or new_run_id()
        self.state: RunState | None = None
        self.hr: ApiSession | None = None
        self._progress_lock = threading.Lock()
        self._done = 0
        self.skipped: list[dict[str, Any]] = []

    # ------------------------------------------------------------------ sessions
    def session(self, portal: str, email: str | None = None, password: str | None = None,
                totp_secret: str | None = None) -> ApiSession:
        return ApiSession(self.client, portal=portal, gate=self.gate, clock=self.clock, stats=self.stats,
                          retry=self.retry, email=email, password=password, totp_secret=totp_secret)

    def close(self) -> None:
        self.client.close()

    # ------------------------------------------------------------------ main
    def run(self) -> dict[str, Any]:
        cfg = self.cfg
        started_wall = self.clock.wall()
        started = self.clock.now()
        corpus = load_corpus(cfg.corpus)
        for warning in corpus.warnings:
            log.warning("corpus: %s", warning)
        job_keys = self._select_jobs(corpus)
        plan = self._select_resumes(corpus, job_keys)
        self.state = RunState.open(cfg.runs_dir, self.run_id, cfg.base_url)
        report_dir = Path(cfg.report_dir) if cfg.report_dir else Path(cfg.runs_dir) / self.run_id / "report"
        self.out(f"run {self.run_id}: {len(plan)} resumes x {len(job_keys)} jobs against {cfg.base_url}"
                 f" (apply to {cfg.apply_to}, pacing {'on' if cfg.pacing else 'off'},"
                 f" concurrency {cfg.concurrency})")
        if self.skipped:
            by_ext: dict[str, int] = {}
            for item in self.skipped:
                by_ext[item["ext"]] = by_ext.get(item["ext"], 0) + 1
            self.out(f"skipping {len(self.skipped)} resume(s) the backend does not accept "
                     f"({', '.join(f'{n} .{e}' for e, n in sorted(by_ext.items()))}; accepted: "
                     f"{', '.join(cfg.allowed_types)}; --include-unsupported uploads them to test the rejection)")

        self.hr = self.session(STAFF, cfg.hr_email, cfg.hr_password, cfg.hr_totp_secret)
        self.hr.login()
        jobs = self.setup_jobs(corpus, job_keys)
        unpublished = [k for k, j in jobs.items() if j["status"] != "PUBLISHED"]
        if unpublished:
            self.out(f"jobs not published ({', '.join(unpublished)}); candidates cannot apply. "
                     f"Re-run with --publish to publish them. Stopping after job setup.")
            report = self._assemble(corpus, jobs, [], {}, {}, started, started_wall, plan_size=len(plan),
                                    stopped="jobs not published")
            write_report(report, [], report_dir)
            return report

        results = self.process_all(plan, jobs)
        rankings, settled = self.wait_for_matching(jobs, results)
        report = self._assemble(corpus, jobs, results, rankings, settled, started, started_wall,
                                plan_size=len(plan))
        rows = report.pop("_rows")
        paths = write_report(report, rows, report_dir)
        self.out(console_summary(report, paths))
        return report

    def _select_jobs(self, corpus: Corpus) -> list[str]:
        if self.cfg.jobs:
            unknown = [k for k in self.cfg.jobs if k not in corpus.jobs]
            if unknown:
                raise SystemExit(f"unknown job key(s): {', '.join(unknown)}; corpus has {', '.join(corpus.jobs)}")
            return list(self.cfg.jobs)
        return list(corpus.jobs)

    def _targets(self, resume: Resume, job_keys: list[str]) -> list[str]:
        if self.cfg.apply_to == "all":
            return list(job_keys)
        if self.cfg.apply_to == "target" and resume.target_jobs():
            return [k for k in resume.target_jobs() if k in job_keys]
        # "fit", or a corpus without targetJob: every selected job the resume has a gold label for
        return [k for k in job_keys if k in resume.fit]

    def _select_resumes(self, corpus: Corpus, job_keys: list[str]) -> list[tuple[Resume, list[str]]]:
        plan = []
        allowed = {t.lower().lstrip(".") for t in self.cfg.allowed_types}
        self.skipped = []
        for resume in corpus.resumes:
            targets = self._targets(resume, job_keys)
            if not targets:
                continue
            if resume.ext not in allowed and not self.cfg.include_unsupported:
                self.skipped.append({"resumeId": resume.id, "format": resume.format, "ext": resume.ext,
                                     "file": resume.file.name, "jobs": targets,
                                     "reason": f".{resume.ext} is not an accepted resume type"})
                continue
            plan.append((resume, targets))
        if self.cfg.limit is not None:
            plan = plan[: self.cfg.limit]
        return plan

    # ------------------------------------------------------------------ jobs
    def setup_jobs(self, corpus: Corpus, job_keys: list[str]) -> dict[str, dict[str, Any]]:
        assert self.hr and self.state
        suffix = f" [{self.run_id}]" if self.cfg.fresh_jobs else ""
        jobs: dict[str, dict[str, Any]] = {}
        for key in job_keys:
            job = corpus.jobs[key]
            title = (job.title + suffix)[:255]
            saved = self.state.job(key) or {}
            existing = None
            if saved.get("id"):
                try:
                    existing = self.hr.request("GET", f"jobs/{saved['id']}")
                    if existing.get("status") not in OPEN_JOB:
                        existing = None
                except ApiError as exc:
                    if exc.status != 404:
                        raise
            if existing is None:
                existing = self.find_job_by_title(title)
            created = bool(saved.get("created")) and existing is not None and existing.get("id") == saved.get("id")
            if existing is None:
                existing = self.hr.request("POST", "jobs", json=job.create_payload(suffix))
                created = True
                self.out(f"job {key}: created #{existing['id']} '{title}'")
            else:
                self.out(f"job {key}: reusing #{existing['id']} '{existing.get('title')}' ({existing.get('status')})")
            self.state.set_job(key, id=existing["id"], title=title, created=created)
            existing = self.sync_requirements(existing, job)
            status = existing.get("status")
            if self.cfg.publish and status in ("DRAFT", "ON_HOLD"):
                existing = self.hr.request("PATCH", f"jobs/{existing['id']}/status", json={"status": "PUBLISHED"})
                status = existing.get("status")
            self.state.set_job(key, status=status)
            jobs[key] = {"id": existing["id"], "title": existing.get("title", title), "created": created,
                         "status": status, "requirements": len(existing.get("requirements") or [])}
        return jobs

    def find_job_by_title(self, title: str) -> dict[str, Any] | None:
        assert self.hr
        matches = []
        page = 0
        while page < 20:
            data = self.hr.request("GET", "jobs", params={"search": title[:255], "size": 100, "page": page})
            for item in data.get("content") or []:
                if item.get("title", "").strip().casefold() == title.strip().casefold() \
                        and item.get("status") in OPEN_JOB:
                    matches.append(item)
            if data.get("last", True):
                break
            page += 1
        if not matches:
            return None
        matches.sort(key=lambda j: (OPEN_JOB.index(j["status"]), -int(j.get("id") or 0)))
        return matches[0]

    def sync_requirements(self, existing: dict[str, Any], job: Job) -> dict[str, Any]:
        assert self.hr
        wanted = job.requirements()
        have = sorted(existing.get("requirements") or [], key=lambda r: r.get("displayOrder", 0))
        if [_norm_requirement(r) for r in have] == [_norm_requirement(r) for r in wanted]:
            return existing
        updated = self.hr.request("PUT", f"jobs/{existing['id']}/requirements", json={"requirements": wanted})
        self.out(f"job {job.key}: requirements set ({len(wanted)})")
        return updated or existing

    # ------------------------------------------------------------------ candidates
    def process_all(self, plan: list[tuple[Resume, list[str]]], jobs: dict[str, dict[str, Any]]
                    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        total = len(plan)
        with ThreadPoolExecutor(max_workers=max(1, self.cfg.concurrency)) as pool:
            futures = {pool.submit(self.process_resume, resume, targets, jobs): resume for resume, targets in plan}
            for future in as_completed(futures):
                result = future.result()
                results.append(result)
                with self._progress_lock:
                    self._done += 1
                    apps = sum(1 for a in result["applications"].values() if a.get("applicationId"))
                    tail = f" FAILED at {result['stage']}: {result['error']}" if result["status"] != "ok" else ""
                    self.out(f"[{self._done}/{total}] {result['resumeId']} {result.get('analysisStatus') or '-'}"
                             f" applied {apps}/{len(result['targets'])}{tail}")
        order = {resume.id: i for i, (resume, _) in enumerate(plan)}
        results.sort(key=lambda r: order[r["resumeId"]])
        return results

    def process_resume(self, resume: Resume, targets: list[str], jobs: dict[str, dict[str, Any]]
                       ) -> dict[str, Any]:
        assert self.state
        res: dict[str, Any] = {
            "resumeId": resume.id, "targets": targets, "status": "ok", "stage": None, "error": None,
            "email": None, "serverResumeId": None, "analysisStatus": None, "analysisErrorCode": None,
            "flags": [], "factCount": None, "analysisSeconds": None, "applications": {},
            "startedAt": self.clock.now(), "warnings": [],
        }
        saved = self.state.candidate(resume.id)
        stage = "register"
        try:
            session = self.register(resume, res)
            stage = "profile"
            if not saved.get("profileDone"):
                try:
                    self.fill_profile(session, resume)
                    self.state.update_candidate(resume.id, profileDone=True)
                except ApiError as exc:
                    res["warnings"].append(f"profile: {exc.message}")
            stage = "upload"
            if saved.get("serverResumeId"):
                res["serverResumeId"] = saved["serverResumeId"]
            else:
                self.upload(session, resume, res)
            stage = "analysis"
            if saved.get("analysisStatus") in TERMINAL_ANALYSIS:
                for k in ("analysisStatus", "analysisErrorCode", "flags", "factCount", "analysisSeconds"):
                    res[k] = saved.get(k)
            else:
                self.poll_analysis(session, res)
                self.state.update_candidate(resume.id, **{k: res[k] for k in (
                    "analysisStatus", "analysisErrorCode", "flags", "factCount", "analysisSeconds")})
            stage = "apply"
            for key in targets:
                self.apply(session, resume, key, jobs[key]["id"], res)
            failed = [k for k, a in res["applications"].items() if not a.get("applicationId")]
            if failed:
                res["status"], res["stage"] = "failed", "apply"
                res["error"] = "; ".join(f"{k}: {res['applications'][k].get('error')}" for k in failed)
        except ApiError as exc:
            res["status"], res["stage"], res["error"] = "failed", stage, f"{exc.status}: {exc.message}"
        except Exception as exc:  # noqa: BLE001 - a broken resume must not stop the run
            log.exception("resume %s failed at %s", resume.id, stage)
            res["status"], res["stage"], res["error"] = "failed", stage, f"{type(exc).__name__}: {exc}"
        res["finishedAt"] = self.clock.now()
        return res

    def make_email(self, resume: Resume, attempt: int) -> str:
        persona_email = str(resume.persona.get("email") or "")
        local, _, domain = persona_email.partition("@")
        if not local:
            local = f"{resume.persona.get('firstName', '')}.{resume.persona.get('lastName', '')}"
        domain = self.cfg.email_domain or domain or "sim.recruitpro.test"
        tag = f"{self.run_id}-{resume.index}" + (f"-{attempt}" if attempt else "")
        return f"{_slug(local)}+{tag}@{domain}".lower()

    def register(self, resume: Resume, res: dict[str, Any]) -> ApiSession:
        assert self.state
        saved = self.state.candidate(resume.id)
        if saved.get("email") and saved.get("password"):
            session = self.session(CANDIDATE, saved["email"], saved["password"])
            try:
                session.login()
                res["email"] = saved["email"]
                return session
            except ApiError as exc:
                if exc.status not in (400, 401, 403, 404):
                    raise
                res["warnings"].append(f"stored account unusable ({exc.status}); registering a new one")
                for k in ("serverResumeId", "analysisStatus", "profileDone"):
                    saved.pop(k, None)
                saved["applications"] = {}
        password = new_password()
        first = str(resume.persona.get("firstName") or "Sim")[:100]
        last = str(resume.persona.get("lastName") or f"Candidate{resume.index}")[:100]
        start = int(saved.get("emailAttempt", -1)) + 1
        last_error: ApiError | None = None
        for attempt in range(start, start + 3):
            email = self.make_email(resume, attempt)
            session = self.session(CANDIDATE, email, password)
            try:
                data = session.request("POST", "auth/register", auth=False, reauth=False,
                                       json={"firstName": first, "lastName": last, "email": email,
                                             "password": password})
                session.adopt(data)
            except ApiError as exc:
                last_error = exc
                if exc.status == 409:
                    # Our own earlier attempt may have been created before a timeout: try our password.
                    try:
                        session.login()
                    except ApiError:
                        continue
                elif exc.status == 403:
                    raise ApiError(403, f"registration refused: {exc.message} (candidate portal header is sent; "
                                        "is public registration disabled?)") from exc
                else:
                    raise
            self.state.update_candidate(resume.id, email=email, password=password, emailAttempt=attempt)
            res["email"] = email
            return session
        raise last_error or ApiError(409, "could not register a unique email")

    def fill_profile(self, session: ApiSession, resume: Resume) -> None:
        # CandidateProfileUpdateRequest. The profile is never used for matching; it only makes HR screens realistic.
        body: dict[str, Any] = {}
        persona = dict(resume.persona)
        city = str(persona.get("city") or "")
        if "," in city and not persona.get("state"):  # ResumeLab writes "Kolkata, West Bengal"
            persona["city"], _, persona["state"] = (part.strip() for part in city.partition(","))
        for src, dst, limit in (("city", "city", 100), ("state", "state", 100), ("country", "country", 100)):
            if persona.get(src):
                body[dst] = str(persona[src])[:limit]
        years = resume.years_of_experience
        if years is not None:
            body["yearsOfExperience"] = max(0, min(100, years))
        role = resume.current_role()
        for attr, fallback, dst, limit in (("currentTitle", role.get("title"), "currentDesignation", 255),
                                           ("currentCompany", role.get("employer"), "currentCompany", 255),
                                           ("summary", None, "profileSummary", 5000)):
            value = resume.attr(attr) or fallback
            if isinstance(value, str) and value.strip():
                body[dst] = value.strip()[:limit]
        if body:
            session.request("PUT", "candidates/me", json=body)

    def upload(self, session: ApiSession, resume: Resume, res: dict[str, Any]) -> None:
        assert self.state
        if not resume.file.exists():
            res["analysisStatus"] = "FILE_MISSING"
            raise ApiError(None, f"resume file not found: {resume.file}")
        content = resume.file.read_bytes()
        ctype = CONTENT_TYPES.get(resume.ext, "application/octet-stream")
        res["uploadStartedAt"] = self.clock.now()
        try:
            data = session.request("POST", "candidates/me/resumes",
                                   files={"file": (resume.file.name, content, ctype)},
                                   timeout=self.cfg.upload_timeout)
        except ApiError as exc:
            if exc.status in (400, 413, 415):
                res["analysisStatus"] = "UPLOAD_TOO_LARGE" if exc.status == 413 else "UPLOAD_REJECTED"
                res["analysisErrorCode"] = exc.message[:200]
                self.state.update_candidate(resume.id, analysisStatus=res["analysisStatus"])
            raise
        res["serverResumeId"] = data["id"]
        self.state.update_candidate(resume.id, serverResumeId=data["id"])

    def poll_analysis(self, session: ApiSession, res: dict[str, Any]) -> None:
        started = res.get("uploadStartedAt") or self.clock.now()
        deadline = self.clock.now() + self.cfg.analysis_timeout
        interval = self.cfg.poll_interval
        data: dict[str, Any] | None = None
        while True:
            try:
                data = session.request("GET", f"candidates/me/resumes/{res['serverResumeId']}/analysis")
            except ApiError as exc:
                if exc.status != 404:
                    raise
                data = None
            status = (data or {}).get("status")
            if status in TERMINAL_ANALYSIS:
                break
            remaining = deadline - self.clock.now()
            if remaining <= 0:
                status = "TIMEOUT"
                break
            self.clock.sleep(min(interval, remaining))
            interval = min(interval * 1.5, self.cfg.max_poll_interval)
        res["analysisStatus"] = status
        res["analysisSeconds"] = round(self.clock.now() - started, 3)
        if data:
            res["analysisErrorCode"] = data.get("errorCode")
            res["flags"] = [f.get("code") for f in data.get("flags") or [] if isinstance(f, dict)]
            res["factCount"] = len(data.get("facts") or [])
            res["parserVersion"] = data.get("parserVersion")

    def apply(self, session: ApiSession, resume: Resume, job_key: str, job_id: int, res: dict[str, Any]) -> None:
        assert self.state
        saved = self.state.candidate(resume.id).get("applications", {})
        if saved.get(job_key):
            res["applications"][job_key] = {"applicationId": saved[job_key], "appliedAt": self.clock.now(),
                                            "resumed": True}
            return
        entry: dict[str, Any] = {"jobId": job_id}
        res["applications"][job_key] = entry
        verified = False
        while True:
            try:
                data = session.request("POST", f"jobs/{job_id}/applications",
                                       json={"resumeId": res["serverResumeId"]})
                app_id = data["id"]
            except ApiError as exc:
                if exc.status == 409:
                    app_id = self.find_my_application(session, job_id)
                    if app_id is None:
                        entry["error"] = f"409 {exc.message}"
                        return
                elif exc.status == 403 and not verified and self.cfg.mailpit_url:
                    verified = True
                    self.verify_email(session, res["email"])
                    continue
                else:
                    hint = (" (email verification is required: set AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY=false or "
                            "pass --mailpit-url)") if exc.status == 403 else ""
                    entry["error"] = f"{exc.status} {exc.message}{hint}"
                    return
            entry["applicationId"] = app_id
            entry["appliedAt"] = self.clock.now()
            self.state.set_application(resume.id, job_key, app_id)
            return

    def find_my_application(self, session: ApiSession, job_id: int) -> int | None:
        page = 0
        while page < 50:
            data = session.request("GET", "candidates/me/applications", params={"page": page, "size": 100})
            for item in data.get("content") or []:
                if item.get("jobId") == job_id:
                    return item["id"]
            if data.get("last", True):
                return None
            page += 1
        return None

    def verify_email(self, session: ApiSession, email: str) -> None:
        """Dev stacks with AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY=true: read the link from Mailpit, confirm it."""
        assert self.cfg.mailpit_url
        with httpx.Client(base_url=self.cfg.mailpit_url.rstrip("/") + "/", transport=self.mailpit_transport,
                          timeout=15) as mp:
            deadline = self.clock.now() + 60
            token = None
            while token is None:
                found = mp.get("api/v1/search", params={"query": f'to:"{email}"'}).json()
                for msg in found.get("messages") or []:
                    body = mp.get(f"api/v1/message/{msg['ID']}").json()
                    text = (body.get("Text") or "") + (body.get("HTML") or "")
                    match = re.search(r"verify-email\?token=([A-Za-z0-9_\-.~%]+)", text)
                    if match:
                        token = httpx.QueryParams(f"t={match.group(1)}")["t"]
                        break
                if token is None:
                    if self.clock.now() >= deadline:
                        raise ApiError(403, f"no verification email for {email} in Mailpit")
                    self.clock.sleep(2)
        session.request("POST", "auth/verify-email", json={"token": token}, auth=False, reauth=False)

    # ------------------------------------------------------------------ matching and ranking
    def fetch_ranking(self, job_id: int) -> list[dict[str, Any]]:
        assert self.hr
        items: list[dict[str, Any]] = []
        page = 0
        while True:
            data = self.hr.request("GET", f"hr/jobs/{job_id}/applicants",
                                   params={"page": page, "size": 100, "sort": "rank"})
            items.extend(data.get("content") or [])
            total_pages = int(data.get("totalPages") or 0)
            if data.get("last", True) or page + 1 >= total_pages:
                return items
            page += 1

    def wait_for_matching(self, jobs: dict[str, dict[str, Any]], results: list[dict[str, Any]]
                          ) -> tuple[dict[str, list[dict[str, Any]]], dict[int, float]]:
        ours: dict[str, set[int]] = {k: set() for k in jobs}
        for r in results:
            for key, app in r["applications"].items():
                if app.get("applicationId"):
                    ours[key].add(app["applicationId"])
        settled: dict[int, float] = {}
        deadline = self.clock.now() + self.cfg.match_timeout
        interval = self.cfg.poll_interval
        total = sum(len(v) for v in ours.values())
        if total:
            self.out(f"waiting for matching of {total} applications ...")
        while True:
            pending = 0
            for key, ids in ours.items():
                if not ids or ids <= settled.keys():
                    continue
                for item in self.fetch_ranking(jobs[key]["id"]):
                    app_id = item.get("applicationId")
                    if app_id in ids and app_id not in settled and item.get("matchStatus") in SETTLED_MATCH:
                        settled[app_id] = self.clock.now()
                pending += len(ids - settled.keys())
            if pending == 0:
                break
            remaining = deadline - self.clock.now()
            if remaining <= 0:
                self.out(f"matching timeout: {pending} application(s) still PENDING after "
                         f"{self.cfg.match_timeout:.0f}s; reporting them as MATCH_TIMEOUT")
                break
            self.clock.sleep(min(interval, remaining))
            interval = min(interval * 1.5, self.cfg.max_poll_interval)
        rankings = {}
        for key, ids in ours.items():
            full = self.fetch_ranking(jobs[key]["id"]) if ids else []
            rankings[key] = [item for item in full if item.get("applicationId") in ids]
            jobs[key]["applicantsTotal"] = len(full)
        return rankings, settled

    # ------------------------------------------------------------------ assembly
    def _assemble(self, corpus: Corpus, jobs: dict[str, dict[str, Any]], results: list[dict[str, Any]],
                  rankings: dict[str, list[dict[str, Any]]], settled: dict[int, float], started: float,
                  started_wall: float, plan_size: int, stopped: str | None = None) -> dict[str, Any]:
        by_id = {r.id: r for r in corpus.resumes}
        positions: dict[int, tuple[int, dict[str, Any]]] = {}
        for key, items in rankings.items():
            for i, item in enumerate(items, start=1):
                positions[item["applicationId"]] = (i, item)
        rows: list[dict[str, Any]] = []
        resume_rows: list[dict[str, Any]] = []
        failures: list[dict[str, Any]] = []
        for res in results:
            resume = by_id[res["resumeId"]]
            base = {"resumeId": resume.id, "format": resume.format, "template": resume.attr("template"),
                    "quality": resume.attr("quality"), "profession": resume.attr("profession"),
                    "seniority": resume.attr("seniority"), "adversarial": resume.adversarial,
                    "traits": ",".join(resume.traits) or "none", "flagExpected": resume.flag_expected,
                    "twinGroup": resume.twin_group}
            app_settled = []
            my_rows: list[dict[str, Any]] = []
            for key in res["targets"]:
                app = res["applications"].get(key) or {}
                app_id = app.get("applicationId")
                if not app_id:
                    continue
                pos, item = positions.get(app_id, (None, {}))
                match_status = item.get("matchStatus")
                if app_id not in settled:
                    match_status = "MATCH_TIMEOUT" if item else "MISSING"
                match_seconds = None
                if app_id in settled and app.get("appliedAt") is not None and not app.get("resumed"):
                    match_seconds = round(max(0.0, settled[app_id] - app["appliedAt"]), 3)
                    app_settled.append(settled[app_id])
                label = resume.label(key)
                my_rows.append({**base, "jobKey": key, "jobId": jobs[key]["id"], "applicationId": app_id,
                             "label": label, "gain": LABEL_GAIN.get(label or "", 0),
                             "expectedGroup": resume.expected_group(key), "actualGroup": item.get("rankGroup"),
                             "rank": item.get("rank"), "position": pos,
                             "verifiedCoverage": item.get("verifiedCoverage"),
                             "claimedCoverage": item.get("claimedCoverage"),
                             "knockoutsVerified": item.get("knockoutsVerified"),
                             "matchStatus": match_status, "analysisStatus": item.get("analysisStatus")
                             or res.get("analysisStatus"), "flags": item.get("flags") or res.get("flags") or [],
                             "persona": resume.persona, "email": res.get("email"),
                             "metRequirements": (resume.fit.get(key) or {}).get("metRequirements"),
                             "analysisSeconds": res.get("analysisSeconds"), "matchSeconds": match_seconds})
            end = max(app_settled) if app_settled else res.get("finishedAt")
            total = None if res.get("status") != "ok" or end is None else round(end - res["startedAt"], 3)
            for row in my_rows:
                row["totalSeconds"] = total
            rows.extend(my_rows)
            resume_rows.append({**base, "analysisStatus": res.get("analysisStatus"),
                                "analysisErrorCode": res.get("analysisErrorCode"),
                                "analysisSeconds": res.get("analysisSeconds"), "totalSeconds": total,
                                "flags": res.get("flags"), "factCount": res.get("factCount"),
                                "status": res["status"]})
            if res["status"] != "ok":
                failures.append({"resumeId": resume.id, "stage": res["stage"], "error": res["error"],
                                 "email": res.get("email")})
            for warning in res.get("warnings") or []:
                failures.append({"resumeId": resume.id, "stage": "warning", "error": warning,
                                 "email": res.get("email")})
        for row in rows:
            if row["matchStatus"] in ("MATCH_TIMEOUT", "MISSING"):
                failures.append({"resumeId": row["resumeId"], "stage": "match", "jobKey": row["jobKey"],
                                 "error": f"application {row['applicationId']} {row['matchStatus']}"})
        evaluation = metrics.evaluate(rows, resume_rows, failures)
        report = {
            "runId": self.run_id,
            "baseUrl": self.cfg.base_url,
            "corpus": str(self.cfg.corpus),
            "startedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_wall)),
            "durationSeconds": round(self.clock.now() - started, 1),
            "planned": plan_size,
            "skipped": list(self.skipped),
            "processed": len(results),
            "succeeded": sum(1 for r in results if r["status"] == "ok"),
            "stopped": stopped,
            "config": {"jobs": list(jobs), "limit": self.cfg.limit, "concurrency": self.cfg.concurrency,
                       "pacing": self.cfg.pacing, "applyTo": self.cfg.apply_to, "freshJobs": self.cfg.fresh_jobs,
                       "allowedTypes": list(self.cfg.allowed_types),
                       "includeUnsupported": self.cfg.include_unsupported,
                       "analysisTimeout": self.cfg.analysis_timeout, "matchTimeout": self.cfg.match_timeout},
            "jobs": jobs,
            "api": self.stats.as_dict(),
            "corpusWarnings": corpus.warnings,
            "corpusMeta": corpus.meta,
            **evaluation,
            "_rows": rows,
        }
        if stopped:
            report.pop("_rows")
        return report


def console_summary(report: dict[str, Any], paths: dict[str, Path]) -> str:
    o = report["overall"]
    ga = o["groupAgreement"]

    def f(v: Any) -> str:
        return "n/a" if v is None else f"{v:.3f}"

    adv, fair, t = report["adversarial"], report["fairness"], report["timings"]
    lines = [
        "",
        f"== simulation {report['runId']}: {report['succeeded']}/{report['processed']} resumes ok, "
        f"{o['applications']} applications, {len(report.get('skipped') or [])} skipped (format), "
        f"{report['durationSeconds']}s",
        f"   NDCG@10 {f(o['ndcg@10'])}  NDCG@all {f(o['ndcg@all'])}  P@5 {f(o['precision@5'])}  "
        f"P@10 {f(o['precision@10'])}  pairwise {f(o['pairwiseAccuracy'])}",
        f"   ranking-group agreement {f(ga['accuracy'])} ({ga['agree']}/{ga['n']})  groups {o.get('groups')}",
        f"   adversarial n={adv['n']} rose {f(adv['roseRate'])} flagged {f(adv['flagRate'])}   "
        f"twins pairs={fair['pairs']} flip {f(fair['flipRate'])}",
        f"   analysis p50 {f(t['analysisSeconds']['p50'])}s  match p50 {f(t['matchSeconds']['p50'])}s  "
        f"failures {len([x for x in report['failures'] if x.get('stage') != 'warning'])}  "
        f"429 retries {report['api']['retries_429']}",
        f"   report: {paths.get('markdown')}",
    ]
    return "\n".join(lines)
