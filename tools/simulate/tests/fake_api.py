"""In-memory stand-in for the RecruitPro backend, served through ``httpx.MockTransport``.

It mirrors the contracts the runner relies on (paths, envelope, portal header, bearer auth, status codes, enum
values, ranking groups and order from ``ApplicantRanking``) and can inject faults: 429 with Retry-After on the
rate-limited auth routes, one-off 503s, a lost response (``httpx.ReadTimeout`` after the server already acted),
expiring access tokens, analyses that never finish, and verified-email-to-apply. Job and requirement bodies are
checked against the backend DTOs (``JobCreateRequest``, ``RequirementInput``): unknown fields are recorded in
``unknown_fields`` (the backend ignores them, so a test can assert the runner sends exactly the DTO fields).
"""
from __future__ import annotations

import email
import itertools
import json
import re
import threading
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any, Callable

import httpx

from simulate.clock import FakeClock
from simulate.corpus import (EXPERIENCE_LEVELS, JOB_TYPES, LABEL_GAIN, PROFICIENCIES, REQUIREMENT_TYPES,
                             load_corpus)

JOB_CREATE_FIELDS = {"title", "description", "department", "location", "jobType", "experienceLevel",
                     "minimumSalary", "maximumSalary", "vacancies", "deadline"}
REQUIREMENT_INPUT_FIELDS = {"id", "type", "description", "mandatory", "knockout", "minimumYears", "alternatives",
                            "proficiency"}
PROFILE_FIELDS = {"address", "city", "state", "country", "zipCode", "linkedinUrl", "portfolioUrl",
                  "yearsOfExperience", "currentCompany", "currentDesignation", "expectedSalary", "noticePeriodDays",
                  "profileSummary"}


def gold_scores(corpus_dir, boost: set[str] = frozenset()):
    """Fake matcher that follows the corpus gold: coverage from the fit label, knockouts verified when the gold
    expects RANKED (strong/good when the corpus has no expectation). ``boost`` lets chosen resume file stems 'win'
    (a vulnerable matcher)."""
    corpus = load_corpus(corpus_dir)
    title_to_key = {j.title: k for k, j in corpus.jobs.items()}
    by_stem = {r.file.stem: r for r in corpus.resumes}

    def score(stem: str, title: str):
        key = next((k for t, k in title_to_key.items() if title.startswith(t)), None)
        resume = by_stem.get(stem)
        if resume is None or key is None:
            return None
        gain = 4.5 if stem in boost else LABEL_GAIN[resume.label(key) or "unrelated"]
        expected = resume.expected_group(key)
        knockouts = gain >= 3 if stem in boost or expected not in ("RANKED", "MUST_HAVES_UNVERIFIED") \
            else expected == "RANKED"
        return (round(gain * 0.2, 3), round(gain * 0.22, 3), knockouts)

    return score

USABLE = {"COMPLETED", "PARTIAL"}
OPEN = {"PUBLISHED", "ON_HOLD", "DRAFT"}
TRANSITIONS = {"DRAFT": {"PUBLISHED", "CANCELLED"}, "PUBLISHED": {"ON_HOLD", "CLOSED"},
               "ON_HOLD": {"PUBLISHED", "CLOSED"}, "CLOSED": set(), "CANCELLED": set()}
PASSWORD_RE = re.compile(r"^(?=.*[A-Za-z])(?=.*\d).{10,72}$")


@dataclass
class Faults:
    register_limit: tuple[int, float] | None = None      # (n, window) -> 429 + Retry-After beyond it
    login_limit: tuple[int, float] | None = None
    upload_503_once: bool = False
    lost_apply_response_once: bool = False                 # apply succeeds, client sees ReadTimeout
    lost_register_response_once: bool = False              # register succeeds, client sees ReadTimeout
    token_max_uses: int | None = None                      # access token expires after N uses (-> 401)
    never_complete: set[str] = field(default_factory=set)  # resume file stems that stay PROCESSING
    scanned: set[str] = field(default_factory=set)         # -> NEEDS_OCR
    injected: set[str] = field(default_factory=set)        # -> PROMPT_INJECTION_SUSPECTED flag
    hidden: set[str] = field(default_factory=set)          # -> HIDDEN_TEXT flag
    require_verified_email: bool = False
    hr_mfa_secret: str | None = None
    match_never_settles: set[str] = field(default_factory=set)


def ok(data: Any = None, status: int = 200, message: str = "Operation completed successfully") -> httpx.Response:
    body: dict[str, Any] = {"success": True, "message": message, "timestamp": "2026-11-17T00:00:00Z"}
    if data is not None:
        body["data"] = data
    return httpx.Response(status, json=body)


def err(status: int, message: str, headers: dict[str, str] | None = None) -> httpx.Response:
    return httpx.Response(status, json={"success": False, "message": message}, headers=headers)


class FakeRecruitPro:
    def __init__(self, clock: FakeClock, scores: Callable[[str, str], tuple[float, float, bool] | None],
                 faults: Faults | None = None, analysis_polls: int = 2, match_polls: int = 1) -> None:
        self.clock = clock
        self.scores = scores
        self.faults = faults or Faults()
        self.analysis_polls = analysis_polls
        self.match_polls = match_polls
        self.lock = threading.RLock()
        self.ids = itertools.count(100)
        self.users: dict[str, dict[str, Any]] = {
            "hr@acme.test": {"id": 1, "email": "hr@acme.test", "password": "ChangeMe-123!", "role": "HR_MANAGER",
                             "firstName": "Hana", "lastName": "Reed", "verified": True},
        }
        self.tokens: dict[str, dict[str, Any]] = {}
        self.jobs: dict[int, dict[str, Any]] = {}
        self.resumes: dict[int, dict[str, Any]] = {}
        self.applications: dict[int, dict[str, Any]] = {}
        self.profiles: dict[str, dict[str, Any]] = {}
        self.calls: list[tuple[str, str, int]] = []
        self.counts: dict[str, int] = defaultdict(int)
        self.windows: dict[str, deque[float]] = defaultdict(deque)
        self.mfa_tokens: dict[str, str] = {}
        self.outbox: list[dict[str, str]] = []  # verification emails, for the fake Mailpit
        self._once: set[str] = set()
        self.unknown_fields: dict[str, set[str]] = defaultdict(set)   # route -> fields the DTO does not have
        self.uploads: list[tuple[str, str]] = []                      # (filename, content type)

    # ------------------------------------------------------------------ plumbing
    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        with self.lock:
            path = request.url.path
            assert path.startswith("/api/v1/"), path
            route = path[len("/api/v1/"):]
            key = f"{request.method} {route}"
            self.counts[key] += 1
            response = self.route(request, request.method, route)
            self.calls.append((request.method, route, response.status_code))
            return response

    def once(self, name: str) -> bool:
        if name in self._once:
            return False
        self._once.add(name)
        return True

    def limited(self, name: str, limit: tuple[int, float] | None) -> httpx.Response | None:
        if not limit:
            return None
        n, window = limit
        q = self.windows[name]
        now = self.clock.now()
        while q and q[0] <= now - window:
            q.popleft()
        if len(q) >= n:
            retry = max(1, int(q[0] + window - now + 0.999))
            return err(429, "Too many requests. Please try again later.", {"Retry-After": str(retry)})
        q.append(now)
        return None

    def body(self, request: httpx.Request) -> dict[str, Any]:
        raw = request.read()
        return json.loads(raw) if raw else {}

    def user_for(self, request: httpx.Request) -> dict[str, Any] | None:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        tok = self.tokens.get(auth[7:])
        if not tok:
            return None
        tok["uses"] += 1
        if self.faults.token_max_uses is not None and tok["uses"] > self.faults.token_max_uses:
            return None
        return self.users[tok["email"]]

    def session(self, user: dict[str, Any], status: int = 200) -> httpx.Response:
        token = f"tok-{next(self.ids)}"
        self.tokens[token] = {"email": user["email"], "uses": 0}
        return ok({"accessToken": token, "tokenType": "Bearer", "expiresInSeconds": 900,
                   "user": {"id": user["id"], "email": user["email"], "role": user["role"],
                            "emailVerified": user["verified"]}}, status)

    # ------------------------------------------------------------------ routing
    def route(self, request: httpx.Request, method: str, route: str) -> httpx.Response:
        portal = request.headers.get("X-RecruitPro-Portal")
        if portal not in (None, "candidate", "staff"):
            return err(400, "X-RecruitPro-Portal must be 'candidate' or 'staff'")
        if route.startswith("auth/"):
            return self.auth(request, method, route, portal)
        user = self.user_for(request)
        if user is None:
            return err(401, "Authentication required")
        staff = user["role"] != "CANDIDATE"
        if route == "jobs" and method == "POST":
            return self.create_job(request) if staff else err(403, "Access denied")
        if route == "jobs" and method == "GET":
            return self.list_jobs(request, staff)
        m = re.fullmatch(r"jobs/(\d+)(/.*)?", route)
        if m:
            job = self.jobs.get(int(m.group(1)))
            if job is None:
                return err(404, f"Job not found: {m.group(1)}")
            rest = m.group(2) or ""
            if rest == "/applications" and method == "POST":
                return self.apply(request, user, job) if not staff else err(403, "Access denied")
            if not staff:
                return err(403, "Access denied")
            if rest == "" and method == "GET":
                return ok(self.job_view(job))
            if rest == "" and method == "DELETE":
                if job["status"] != "DRAFT" or job["applicationCount"]:
                    return err(400, "Only draft jobs can be deleted")
                del self.jobs[job["id"]]
                return ok(message="Draft job deleted")
            if rest == "/requirements" and method == "PUT":
                reqs = self.body(request).get("requirements")
                problem = self.check_requirements(reqs)
                if problem:
                    return err(400, problem)
                job["requirements"] = [dict(r, id=next(self.ids), displayOrder=i,
                                            mandatory=r.get("mandatory", True),
                                            knockout=r.get("knockout", r.get("mandatory", True)),
                                            alternatives=r.get("alternatives", []))
                                       for i, r in enumerate(reqs)]
                return ok(self.job_view(job), message="Requirements saved")
            if rest == "/status" and method == "PATCH":
                target = self.body(request).get("status")
                if target not in TRANSITIONS[job["status"]]:
                    return err(400, f"Job status cannot transition from {job['status']} to {target}")
                job["status"] = target
                return ok(self.job_view(job), message="Job status updated")
        m = re.fullmatch(r"hr/jobs/(\d+)/applicants", route)
        if m and method == "GET":
            if not staff:
                return err(403, "Access denied")
            return self.applicants(request, int(m.group(1)))
        if staff:
            return err(403, "Access denied")
        return self.candidate_routes(request, method, route, user)

    # ------------------------------------------------------------------ auth
    def auth(self, request: httpx.Request, method: str, route: str, portal: str | None) -> httpx.Response:
        if route == "auth/register" and method == "POST":
            if portal == "staff":
                return err(403, "This is the HR portal for organisation staff.")
            limited = self.limited("register", self.faults.register_limit)
            if limited:
                return limited
            b = self.body(request)
            if not all(b.get(k) for k in ("firstName", "lastName", "email", "password")):
                return err(400, "Validation failed")
            if not PASSWORD_RE.match(b["password"]):
                return err(400, "Password must be 10-72 characters long and contain at least one letter and one digit")
            email_ = b["email"].lower()
            if email_ in self.users:
                return err(409, "Email is already registered")
            user = {"id": next(self.ids), "email": email_, "password": b["password"], "role": "CANDIDATE",
                    "firstName": b["firstName"], "lastName": b["lastName"], "verified": False}
            self.users[email_] = user
            vtoken = f"vt{next(self.ids)}"
            self.outbox.append({"to": email_, "token": vtoken})
            response = self.session(user, 201)
            if self.faults.lost_register_response_once and self.once("lost-register"):
                raise httpx.ReadTimeout("simulated lost response", request=request)
            return response
        if route == "auth/login" and method == "POST":
            limited = self.limited("login", self.faults.login_limit)
            if limited:
                return limited
            b = self.body(request)
            user = self.users.get(str(b.get("email", "")).lower())
            if not user or user["password"] != b.get("password") or user.get("deleted"):
                return err(401, "Invalid email or password")
            if portal == "candidate" and user["role"] != "CANDIDATE":
                return err(403, "This is the candidate portal.")
            if portal == "staff" and user["role"] == "CANDIDATE":
                return err(403, "This is the HR portal for organisation staff.")
            if user["role"] != "CANDIDATE" and self.faults.hr_mfa_secret:
                mfa = f"mfa-{next(self.ids)}"
                self.mfa_tokens[mfa] = user["email"]
                return ok({"mfaRequired": True, "mfaEnrollmentRequired": False, "mfaToken": mfa,
                           "expiresInSeconds": 300}, message="Second sign-in step required")
            return self.session(user)
        if route == "auth/mfa/verify" and method == "POST":
            from simulate.api import totp
            b = self.body(request)
            email_ = self.mfa_tokens.pop(b.get("mfaToken"), None)
            if not email_ or b.get("code") != totp(self.faults.hr_mfa_secret or "", self.clock.wall()):
                return err(401, "Invalid code")
            return self.session(self.users[email_])
        if route == "auth/verify-email" and method == "POST":
            token = self.body(request).get("token")
            for mail in self.outbox:
                if mail["token"] == token:
                    self.users[mail["to"]]["verified"] = True
                    return ok(message="Email address verified")
            return err(400, "Invalid token")
        return err(404, "No handler")

    # ------------------------------------------------------------------ jobs
    def create_job(self, request: httpx.Request) -> httpx.Response:
        b = self.body(request)
        self.unknown_fields["POST jobs"] |= set(b) - JOB_CREATE_FIELDS
        missing = [k for k in ("title", "description", "department", "location", "jobType", "experienceLevel",
                               "vacancies") if not b.get(k)]
        if missing:
            return err(400, "Validation failed: " + ", ".join(missing))
        if b["jobType"] not in JOB_TYPES or b["experienceLevel"] not in EXPERIENCE_LEVELS:
            return err(400, "Invalid enum")
        if len(b["title"]) > 255 or len(b["department"]) > 100 or len(b["location"]) > 255 \
                or len(b["description"]) > 50000 or not isinstance(b["vacancies"], int) or b["vacancies"] < 1:
            return err(400, "Validation failed")
        job = {k: v for k, v in b.items() if k in JOB_CREATE_FIELDS}
        return ok(self.job_view(self.add_job(job)), message="Job created as draft")

    def check_requirements(self, reqs: Any) -> str | None:
        """RequirementsReplaceRequest / RequirementInput bean validation."""
        if not isinstance(reqs, list) or len(reqs) > 100:
            return "requirements: must be a list of at most 100"
        for i, r in enumerate(reqs):
            self.unknown_fields["PUT jobs/{id}/requirements"] |= set(r) - REQUIREMENT_INPUT_FIELDS
            if r.get("type") not in REQUIREMENT_TYPES:
                return f"requirements[{i}].type: invalid enum {r.get('type')!r}"
            if not str(r.get("description") or "").strip() or len(r["description"]) > 500:
                return f"requirements[{i}].description: must not be blank, max 500"
            years = r.get("minimumYears")
            if years is not None and not (isinstance(years, int) and 0 <= years <= 50):
                return f"requirements[{i}].minimumYears: 0-50"
            if r.get("proficiency") is not None and r["proficiency"] not in PROFICIENCIES:
                return f"requirements[{i}].proficiency: invalid enum {r['proficiency']!r}"
            if len(r.get("alternatives") or []) > 10:
                return f"requirements[{i}].alternatives: max 10"
        return None

    def add_job(self, b: dict[str, Any], status: str = "DRAFT", requirements: list | None = None) -> dict[str, Any]:
        job = {**b, "id": next(self.ids), "status": status, "requirements": requirements or [],
               "applicationCount": 0, "organizationId": 1, "requirementsVersion": 1}
        self.jobs[job["id"]] = job
        return job

    def job_view(self, job: dict[str, Any]) -> dict[str, Any]:
        view = dict(job)
        view["applicationCount"] = sum(1 for a in self.applications.values() if a["jobId"] == job["id"])
        return view

    def list_jobs(self, request: httpx.Request, staff: bool) -> httpx.Response:
        search = (request.url.params.get("search") or "").lower()
        page = int(request.url.params.get("page", 0))
        size = int(request.url.params.get("size", 20))
        if size < 1 or size > 100:
            return err(400, "page must be non-negative and size must be between 1 and 100")
        items = [self.job_view(j) for j in sorted(self.jobs.values(), key=lambda j: -j["id"])
                 if search in j["title"].lower() and (staff or j["status"] == "PUBLISHED")]
        return ok(self.page(items, page, size))

    @staticmethod
    def page(items: list, page: int, size: int) -> dict[str, Any]:
        total = len(items)
        pages = 0 if total == 0 else (total + size - 1) // size
        return {"content": items[page * size:(page + 1) * size], "page": page, "size": size,
                "totalElements": total, "totalPages": pages, "first": page == 0, "last": page >= pages - 1}

    # ------------------------------------------------------------------ candidate side
    def candidate_routes(self, request: httpx.Request, method: str, route: str, user: dict[str, Any]
                         ) -> httpx.Response:
        if route == "candidates/me" and method == "PUT":
            b = self.body(request)
            self.unknown_fields["PUT candidates/me"] |= set(b) - PROFILE_FIELDS
            if "yearsOfExperience" in b and not 0 <= b["yearsOfExperience"] <= 100:
                return err(400, "Validation failed")
            if any(len(str(b.get(k) or "")) > 100 for k in ("city", "state", "country")):
                return err(400, "Validation failed")
            self.profiles[user["email"]] = b
            return ok(b, message="Candidate profile updated")
        if route == "candidates/me" and method == "DELETE":
            if self.body(request).get("password") != user["password"]:
                return err(400, "Password is not correct")
            user["deleted"] = True
            return ok(message="Account deleted")
        if route == "candidates/me/resumes" and method == "POST":
            if self.faults.upload_503_once and self.once("upload-503"):
                return err(503, "Service Unavailable")
            filename, content, ctype = self.multipart_file(request)
            if filename is None:
                return err(400, "Required part 'file' is not present.")
            ext = filename.rsplit(".", 1)[-1].lower()
            self.uploads.append((filename, ctype))
            if ext not in {"pdf", "doc", "docx"}:
                return err(400, "Only PDF, DOC and DOCX resumes are accepted")
            if (ext == "pdf" and not content.startswith(b"%PDF")) or (ext == "docx" and not content.startswith(b"PK")):
                return err(400, "Resume content does not match its file type")
            rid = next(self.ids)
            self.resumes[rid] = {"id": rid, "owner": user["email"], "filename": filename,
                                 "stem": filename.rsplit(".", 1)[0], "size": len(content), "polls": 0,
                                 "status": "PENDING"}
            return ok({"id": rid, "originalFilename": filename, "contentType": "application/pdf",
                       "fileSize": len(content), "uploadedAt": "2026-11-17T00:00:00"}, message="Resume uploaded")
        m = re.fullmatch(r"candidates/me/resumes/(\d+)/analysis", route)
        if m and method == "GET":
            r = self.resumes.get(int(m.group(1)))
            if not r or r["owner"] != user["email"]:
                return err(404, "Resume analysis not found")
            self.advance_analysis(r)
            flags = [{"code": c, "detail": "fake"} for c in self.flag_codes(r)]
            facts = [{"id": 1, "type": "SKILL", "value": "Java"}] if r["status"] in USABLE else []
            return ok({"resumeId": r["id"], "status": r["status"], "parserVersion": "fake-1",
                       "errorCode": "SCANNED_PDF" if r["status"] == "NEEDS_OCR" else None, "attemptCount": 1,
                       "facts": facts, "flags": flags, "timeline": None})
        if route == "candidates/me/applications" and method == "GET":
            mine = [self.app_view(a) for a in self.applications.values() if a["owner"] == user["email"]]
            return ok(self.page(mine, int(request.url.params.get("page", 0)),
                                int(request.url.params.get("size", 20))))
        m = re.fullmatch(r"candidates/me/applications/(\d+)/withdraw", route)
        if m and method == "PATCH":
            a = self.applications.get(int(m.group(1)))
            if not a or a["owner"] != user["email"]:
                return err(404, "Application not found")
            if a["status"] not in {"SUBMITTED", "SHORTLISTED", "INTERVIEWING"}:
                return err(400, "Only submitted, shortlisted or interviewing applications can be withdrawn")
            a["status"] = "WITHDRAWN"
            return ok(self.app_view(a), message="Application withdrawn")
        return err(404, f"No handler for {method} {route}")

    def advance_analysis(self, r: dict[str, Any]) -> None:
        if r["status"] not in ("PENDING", "PROCESSING"):
            return
        r["polls"] += 1
        if r["stem"] in self.faults.never_complete:
            r["status"] = "PROCESSING"
            return
        if r["polls"] >= self.analysis_polls:
            r["status"] = "NEEDS_OCR" if r["stem"] in self.faults.scanned else "COMPLETED"
        else:
            r["status"] = "PROCESSING"

    def flag_codes(self, r: dict[str, Any]) -> list[str]:
        if r["status"] not in USABLE:
            return []
        codes = []
        if r["stem"] in self.faults.injected:
            codes.append("PROMPT_INJECTION_SUSPECTED")
        if r["stem"] in self.faults.hidden:
            codes.append("HIDDEN_TEXT")
        return codes

    @staticmethod
    def multipart_file(request: httpx.Request) -> tuple[str | None, bytes, str | None]:
        ctype = request.headers.get("Content-Type", "")
        msg = email.message_from_bytes(f"Content-Type: {ctype}\r\n\r\n".encode() + request.read())
        for part in msg.walk():
            if part.get_param("name", header="content-disposition") == "file":
                return part.get_filename(), part.get_payload(decode=True) or b"", part.get_content_type()
        return None, b"", None

    def apply(self, request: httpx.Request, user: dict[str, Any], job: dict[str, Any]) -> httpx.Response:
        if self.faults.require_verified_email and not user["verified"]:
            return err(403, "Verify your email address before applying")
        b = self.body(request)
        r = self.resumes.get(b.get("resumeId") or -1)
        if not r or r["owner"] != user["email"]:
            return err(404, "Resume not found")
        if job["status"] != "PUBLISHED":
            return err(400, "Applications are accepted only for published jobs with an open deadline")
        if any(a["owner"] == user["email"] and a["jobId"] == job["id"] for a in self.applications.values()):
            return err(409, "You have already applied to this job")
        aid = next(self.ids)
        self.applications[aid] = {"id": aid, "jobId": job["id"], "resumeId": r["id"], "owner": user["email"],
                                  "status": "SUBMITTED", "seq": aid, "listPolls": 0}
        response = ok(self.app_view(self.applications[aid]), message="Application submitted")
        if self.faults.lost_apply_response_once and self.once("lost-apply"):
            raise httpx.ReadTimeout("simulated lost response", request=request)
        return response

    def app_view(self, a: dict[str, Any]) -> dict[str, Any]:
        job = self.jobs.get(a["jobId"], {})
        return {"id": a["id"], "jobId": a["jobId"], "jobTitle": job.get("title"), "resumeId": a["resumeId"],
                "status": a["status"]}

    # ------------------------------------------------------------------ HR ranking (ApplicantRanking)
    def applicants(self, request: httpx.Request, job_id: int) -> httpx.Response:
        job = self.jobs.get(job_id)
        if job is None:
            return err(404, "Job not found")
        page = int(request.url.params.get("page", 0))
        size = int(request.url.params.get("size", 20))
        if size < 1 or size > 100:
            return err(400, "size must be between 1 and 100")
        has_reqs = bool(job["requirements"])
        rows = []
        for a in self.applications.values():
            if a["jobId"] != job_id:
                continue
            a["listPolls"] += 1
            r = self.resumes[a["resumeId"]]
            analysis = r["status"]
            score = None
            if not has_reqs:
                match = "NO_REQUIREMENTS"
            elif analysis in ("PENDING", "PROCESSING") or a["listPolls"] <= self.match_polls \
                    or r["stem"] in self.faults.match_never_settles:
                match = "PENDING"
            elif analysis in USABLE:
                match = "READY"
                score = self.scores(r["stem"], job["title"])
            else:
                match = "ANALYSIS_UNAVAILABLE"
            rows.append((a, r, match, score))

        def group(row):
            score = row[3]
            if score is None:
                return "UNSCORED"
            return "RANKED" if score[2] else "MUST_HAVES_UNVERIFIED"

        def scored_key(row):
            v, c, _ = row[3]
            return (-v, -c, row[0]["seq"], row[0]["id"])

        ranked = sorted([x for x in rows if group(x) == "RANKED"], key=scored_key)
        unverified = sorted([x for x in rows if group(x) == "MUST_HAVES_UNVERIFIED"], key=scored_key)
        unscored = sorted([x for x in rows if group(x) == "UNSCORED"], key=lambda x: (x[0]["seq"], x[0]["id"]))
        items = []
        for i, (a, r, match, score) in enumerate(ranked + unverified + unscored):
            g = group((a, r, match, score))
            flags = self.flag_codes(r)
            items.append({"applicationId": a["id"], "displayName": f"Candidate #A-{a['id']}",
                          "identityRevealed": False, "applicationStatus": a["status"],
                          "analysisStatus": r["status"], "matchStatus": match,
                          "verifiedCoverage": None if score is None else score[0],
                          "claimedCoverage": None if score is None else score[1],
                          "knockoutsVerified": None if score is None else score[2],
                          "unverifiedKnockouts": [], "rankGroup": g,
                          "rank": i + 1 if g == "RANKED" else None, "flags": flags,
                          "workSampleStatus": None, "interviewCount": 0})
        return ok(self.page(items, page, size))


class FakeMailpit:
    """Just enough of the Mailpit API for ``--mailpit-url``."""

    def __init__(self, api: FakeRecruitPro) -> None:
        self.api = api

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/v1/search":
            query = request.url.params.get("query", "")
            to = query.split(":", 1)[1].strip('"')
            ids = [i for i, m in enumerate(self.api.outbox) if m["to"] == to]
            return httpx.Response(200, json={"messages": [{"ID": str(i)} for i in ids]})
        m = re.fullmatch(r"/api/v1/message/(\d+)", path)
        if m:
            mail = self.api.outbox[int(m.group(1))]
            return httpx.Response(200, json={
                "Text": f"Confirm: http://localhost:5173/verify-email?token={mail['token']}\n", "HTML": ""})
        return httpx.Response(404)
