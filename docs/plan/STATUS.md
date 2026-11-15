# Plan v2 status, 16 Nov 2026

Branch `Kshitij`, HEAD `2fedafb` plus uncommitted demo patches and the review fixes of 16 Nov evening (nothing
committed). Plan: [IMPLEMENTATION_PLAN_V2.md](IMPLEMENTATION_PLAN_V2.md). How to run and click through everything:
[RUN_AND_TEST_GUIDE.md](../guides/RUN_AND_TEST_GUIDE.md). Detailed explanations: [docs/handbook/](../handbook/).
The older WP-INT status (audit findings C1–L13) follows further down and is still valid except where this section says
otherwise.

## A. What is built, per work package

| WP | What exists | Notes |
|---|---|---|
| WP-AG | `agent-service/` (FastAPI, port 8100, `X-Internal-Service-Token`): ownership check (DNS TXT, well-known file, page code, profile bio), code (GitHub/GitLab), web, document, Open Badge (OB2 hosted, OB3 VC-JWT) and reference verifiers; SSRF-guarded fetching; ownership cap, probe questions, de-identification, citation validator; offline demo fixtures (`demo-*`); eval harness | [WP-AG-NOTES.md](WP-AG-NOTES.md) |
| WP-VBE | Backend Claim Ledger (V21, V22): sources with consent, ownership checks, GitHub OAuth, runs via the job queue, evidence with SHA-256 and snapshots, append-only verdicts, candidate Share/Hide/Re-check, references with a public referee page, staff view (blind-first), per-organisation reviews and the CORROBORATED lift into matching, SHADOW/VISIBLE mode, connectors, shadow labelling queue and metrics, export/deletion, demo seed | [WP-VBE-NOTES.md](WP-VBE-NOTES.md) |
| WP-PLAT | Staff MFA (TOTP, recovery codes, org requirement), OIDC SSO for staff (Google, Microsoft, generic; PKCE, nonce, JWKS), multi-instance safety (job dedupe key, notification row claims, database rate-limit store), `X-Request-Id`, Prometheus, structured logs, security headers and nginx CSP, ICS invitations, scorecard editing with revisions, work-sample file uploads and HR cancellation, fairness date range, demo assessment seed (V23, V24) | [WP-PLAT-NOTES.md](WP-PLAT-NOTES.md) |
| WP-FE-CAND2 / WP-FE-HR2 | Candidate "Strengthen your application" (`/verification`) and referee page (`/reference/:token`); HR applicant Verification tab, admin Verification page, account security (MFA), MFA and SSO sign-in, `/sso/complete`, work-sample attachments and cancellation, fairness range | [WP-FE-FINISH-NOTES.md](WP-FE-FINISH-NOTES.md) |
| Demo patches (uncommitted) | Demo assessment seed in its own transaction; scorecard **Edit** button; require-MFA and SSO-domain controls on the organisation page; compose passes SSO/LLM/`AGENT_GITHUB_TOKEN`; `.env.example` key fix; `tools/totp.py` | [RUN_AND_TEST_GUIDE.md](../guides/RUN_AND_TEST_GUIDE.md) section 0.2 |

## B. Review fixes, 16 Nov 2026 (evening)

| # | Finding | Fix | Tests |
|---|---|---|---|
| 1 | GitHub OAuth state not bound to the browser (login-CSRF / account linking), no PKCE | `start` sets an httpOnly, `SameSite=Lax`, `Secure` (per `app.auth.cookie-secure`) cookie `rp_oauth_github` scoped to the callback path, holding a random nonce; its SHA-256 is stored with the state (V25). The public callback requires the cookie, compares in constant time, refuses with `?error=BROWSER_MISMATCH` (state not consumed) and always clears the cookie. PKCE S256: the verifier is stored encrypted with SecretCipher (context `voauth:<stateHash>`) and sent as `code_verifier` | `GitHubOAuthIntegrationTest` (cookie attributes, hash stored, challenge = S256(verifier), no cookie / wrong cookie refused, attacker's state in the victim's browser refused, cookie cleared) |
| 2 | Backdated commits in fresh repositories reached VERIFIED | agent-service counts committer dates (author dates only when absent), each clamped to [repository `created_at`, now]; a repository created < 30 days ago cannot supply a 30-day span. New integrity note `BACKDATED_HISTORY` | `tests/test_code.py` (+6), `test_demo.py`; eval cases `code-backdated-*` (4, gold PARTLY_VERIFIED) |
| 3 | `/ownership/check` PROFILE_BIO_CODE without `provider:` gave HTTP 500 | 422 `{detail:{code:"BAD_TARGET", message}}` for a missing/unknown provider or invalid login | `tests/test_ownership.py` |
| 4 | Sign-up verification e-mail links to the HR portal's `/verify-email`, which had no route | `hr-frontend/src/pages/auth/VerifyEmailPage.tsx` (same endpoint and behaviour as the candidate portal) and the route | HR build |
| 5 | Shadow queue ignored disabled connectors | `ShadowEvaluationService.queue` leaves them out (as the staff view does) | `AdminVerificationIntegrationTest.theShadowQueueLeavesOutDisabledConnectors` |
| 6 | Referee consent says "first name", e-mail used the full name | The REFERENCE_REQUEST e-mail now uses the first name only (privacy-preserving; the referee page already did) | `ReferenceIntegrationTest` |
| 7 | Verdicts of a disappeared subject stayed in use | Deleting a claim, replacing it with the extracted value, or changing its value marks that claim's current verdicts WITHDRAWN (`ClaimVerdictWithdrawal`). Verdicts whose subject is no longer current (fact of a re-analysed resume, claim rejected by a recruiter) are left out of the staff view, reviews, the shadow queue and matching (`VerificationStaffSupport.withCurrentSubjects`) | `StaffVerificationIntegrationTest.verdictsOfSubjectsThatDisappearedAreWithdrawnOrLeftOut` |
| 8 | Org requires MFA but `APP_ENCRYPTION_KEY` missing: MFA silently skipped | Staff sign-in now fails closed with 503 `MFA_NOT_CONFIGURED` | `MfaNotConfiguredIntegrationTest` |

## C. What has been verified, and how

| Check | Command | Result (16 Nov 2026, after the review fixes) |
|---|---|---|
| Java syntax, imports, types (static) | `python3 tools/java_static_check.py backend/src` | 577 files, 0 errors |
| Entity ↔ schema | `python3 tools/check_entity_schema.py` | 476 columns of 41 entities vs V1–V25, 0 errors, 0 warnings |
| Migrations on PostgreSQL 16 | `python3 tools/check_migrations_pg.py` | V1–V25 applied |
| agent-service | `cd agent-service && python3 -m pytest -q` / `python3 -m eval.run --check` | 176 passed / exit 0: 83 cases (30 adversarial), false-corroboration rate 0.0, kappa 1.0 |
| ai-service | `cd ai-service && python3 -m pytest -q` / `python3 -m eval.run --check` | 178 passed / CHECK PASSED |
| Candidate portal | clean copy, `npx --yes pnpm@10 install --frozen-lockfile && npx --yes pnpm@10 build` | passed |
| HR portal | same | passed |
| Java logic | read-through reviews by independent agents during WP-VBE and WP-PLAT | 2 test bugs (WP-VBE) and 4 minor runtime issues (WP-PLAT) found and fixed |
| Core platform `9ad341a` | `./mvnw verify` on JDK 21 + `docker compose up --build` (teammate, 15 Nov) | 239 tests, 0 failures, 6 skipped; stack started |

Python ran on 3.10 in the sandbox (CI uses 3.12). All tests are offline (no network).

## D. Not compiled or not run yet

- **No Java written after `9ad341a` has been compiled**: `2fedafb` (WP-VBE, WP-PLAT), the demo patches and the review
  fixes above. The sandbox has JDK 11 and no Maven Central. First step on a JDK 21 machine: `cd backend && ./mvnw verify`
  (RUN_AND_TEST_GUIDE 4.1). Expect a few small compile or test fixes.
- The Docker images of agent-service and of the new backend have not been built.
- No flow of plan v2 has been clicked through against a live stack; flows were traced through the code and the agent
  fixtures were run in-process.
- Real GitHub OAuth, real identity providers (Google, Entra), real SMTP with ICS in mail clients and a Prometheus scrape
  were not exercised.

## E. Known limitations (design limits, not fixed)

- **Referee employer domain is typed by the candidate.** A referee mailbox on a domain the candidate controls, entered as
  the employer domain, anchors a reference to `VERIFIED` (free-mail domains never count). Recruiters see the employer
  name/domain and must review before confirming; independent domain evidence is future work.
- **Badge issuer trust is not established.** An OB2 hosted badge or an OB3 `did:web`/JWKS issuer on a domain the candidate
  controls verifies (signatures and recipients are checked; `did:key` issuers are capped at PARTLY_VERIFIED). An issuer
  allow-list is future work.
- **No data-retention purge.** Evidence snapshots, verdicts, audit events and match runs are kept until the candidate
  deletes the account; there is no time-based purge. Snapshot deletion on revoke is after-commit and best-effort.
- **ai-service Docker image has no LibreOffice or OCR.** `.doc` falls back to the olefile reader; scanned PDFs return
  NEEDS_OCR. OCR exists only as an optional extra (`requirements-ocr.txt`, `OCR_ENABLED=true`).
- **MFA and the encryption key.** Without `APP_ENCRYPTION_KEY`, MFA enrolment, SSO and GitHub OAuth are unavailable; an
  organisation that requires MFA now refuses staff sign-in (503 `MFA_NOT_CONFIGURED`) instead of skipping MFA. SSO
  sign-ins count as MFA-satisfied by the identity provider.
- **GitHub OAuth binding cookie needs the API and the portal on one site** (like the refresh cookies), and the callback
  host must be the API host the portal calls (`GITHUB_OAUTH_REDIRECT_URI`). A cookie blocked by the browser gives
  `BROWSER_MISMATCH`. The OAuth token is deleted on revoke but not revoked at GitHub.
- **Accuracy figures are synthetic.** The agent eval (kappa 1.0) is near-tautological; real numbers must come from shadow
  labelling (≥ 150 labels, FCR ≤ 2 %, kappa ≥ 0.70). Readiness for VISIBLE is shown, not enforced.
- **Re-check un-shares:** a new verdict starts as AWAITING_CANDIDATE and the earlier review does not carry over.
- Public verification rate limits are per instance (memory); the shadow queue is paged in memory; REFERENCE_REQUEST
  e-mails survive account deletion; uploaded documents are stored unencrypted (like resumes); de-identification is
  lexical; no QR code for MFA enrolment (secret and `otpauth://` URI as text); no candidate resume-delete endpoint.

---

# RecruitPro status after the audit remediation (WP-INT)

Date: 2026-11-08 · Scope: all work packages of [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) (WP-PY, WP-BE-A, WP-BE-B, WP-BE-C, WP-FE-CAND, WP-FE-HR) plus the integration package WP-INT.

> **Update 15 Nov 2026:** the backend now compiles and `./mvnw verify` passed on a teammate's Windows machine (JDK 21): 239 tests, 0 failures, 0 errors, 6 skipped; the Docker Compose stack started with all services up. Two test fixtures were adjusted to get there (`ScreeningFixtures`, a pytest id list). After that run, legacy `.doc` resumes were changed to be queued for parsing (M8), so re-run `./mvnw verify` once.

Status values: **Fixed** (implemented end to end), **Partly** (implemented with a stated limitation), **Deferred** (not done, with the reason in Section 4).

## 1. Audit findings

### Critical

| ID | Finding | Status | Where |
|---|---|---|---|
| C1 | Candidate edits inflate the score and show as "Candidate Verified"; editable after applying and after a decision | Fixed | Evidence ladder (D5): a fact correction becomes a SELF_CLAIMED claim, the extracted value is never overwritten, ranking uses verified coverage (tier ≥ RESUME_STATED) and shows claimed coverage separately. WP-BE-B `evidence/service/MatchRequestBuilder`, `CandidateClaimService`, `ResumeIntelligenceService.reviewFact`; WP-PY `app/matching/engine.py` tiers; WP-FE-CAND correction labelled "unverified until a recruiter checks it" |
| C2 | "Sort by evidence coverage" sorts only the 20 rows on screen while ranks look global | Fixed | Server-side ranking over the whole filtered list (RANKED / MUST_HAVES_UNVERIFIED / UNSCORED, rank only in RANKED): WP-BE-B `hr/service/ApplicantRanking`, `ApplicantQueryService`; WP-FE-HR `pages/jobs/ApplicantsTab.tsx` |
| C3 | HR never sees who applied: no name, contact, resume or cover letter; nothing follows "Shortlist" | Fixed | Blind-first review (D4): auto-reveal on shortlist and manager reveal (audited), revealed candidate block, cover letter, staff resume download (audited), full lifecycle SHORTLISTED → INTERVIEWING → SELECTED. WP-BE-B `ApplicationDecisionService`, `ApplicationReviewController`; WP-FE-HR `pages/applicants/*` |

### High

| ID | Finding | Status | Where |
|---|---|---|---|
| H1 | Only 50 software skills recognised; other skill requirements always "Not observed" | Fixed | 847 curated skills in 15 domains, ESCO importer: WP-PY `app/taxonomy/skills.json`, `taxonomy.py`, `import_esco.py` |
| H2 | "X or Y" requirements evaluated as "X and Y" | Fixed | Alternatives in the engine (WP-PY `matching/engine.py`), stored per requirement (V15 `alternatives_json`, WP-BE-B), edited as chips (WP-FE-HR) |
| H3 | Common words trigger skills ("CV", "go live", "Spring 2023", "Oracle", "SWIFT payments") | Fixed | Ambiguous names count only in skills lists or with context words: WP-PY `taxonomy.py`; asserted by `tests/test_audit_probes.py` |
| H4 | Unrecognised headings inherit the previous section | Fixed | Unknown headings start an OTHER section: WP-PY `pipeline/sections.py` |
| H5 | Month ranges not parsed, years not summed, "Present" hard-coded to 2026 | Fixed | Date ranges, merged intervals, per-skill months, present = today: WP-PY `pipeline/dates.py`, `pipeline/timeline.py` |
| H6 | Applicant-list GET holds a read-write transaction while calling the AI service, and writes on read | Fixed | Matching is a MATCH_APPLICATION background job; GET endpoints never call the AI service and never write (test "GETs never write"): WP-BE-B `evidence/service/MatchApplicationJobHandler`, `MatchingService` |
| H7 | Admin override skips the job state machine and can reopen CLOSED/CANCELLED jobs | Fixed | Explicit transition table: WP-BE-A `admin/service/AdminJobService` (`AdminJobOverrideTransitionTest`); HR UI offers only allowed targets |
| H8 | Two concurrent first loads insert the same match run; one GET returns 409 | Fixed | Append-only runs written only by the queue worker, `is_current` flag, unique constraint dropped (V17), stale-run guard: WP-BE-B |

### Security and platform

| ID | Finding | Status | Where |
|---|---|---|---|
| S1 | No rate limiting or lockout; no email verification; no password reset | Fixed | WP-BE-A `security/filter/RateLimitFilter`, lockout in `UserAccountService`, `UserTokenService` (verify 24 h, reset 1 h); both portals have the screens |
| S2 | 24-hour access tokens in local/session storage; logout leaves them valid; no reuse detection | Fixed | 15-minute access tokens in memory, rotating refresh tokens in an httpOnly cookie with family reuse detection and a 30 s grace window (WP-BE-A `RefreshTokenService`); clients keep nothing in storage (WP-FE-*); **WP-INT: one refresh cookie per portal** (Section 2) |
| S3 | AI-service token checked only after the multipart body is parsed; Starlette/python-multipart CVEs; CPU-bound parsing on the event loop | Fixed | Pure ASGI token middleware before the body, upload size limit, fastapi 0.121.3 / starlette 0.50.0 / python-multipart 0.0.22, process pool: WP-PY `app/core/auth.py`, `app/core/workers.py` |
| S4 | Spring Boot 3.2.5 past OSS support; Spring Security 6.2.x in CVE-2025-22228's range | Fixed (pending G-JAVA-REAL) | `backend/pom.xml` Spring Boot 3.5.6, Java 21, springdoc 2.8.9 |
| S5 | No supported way to create the first admin/HR user in production | Fixed | Organisation sign-up, bootstrap from environment, invitations, demo seed: WP-BE-A `organization/*` |

### Medium

| ID | Finding | Status | Where |
|---|---|---|---|
| M1 | RECRUITER reads every job's applicants but a non-owner HR_MANAGER cannot | Fixed | Organisation-wide roles in `hr/service/HrJobAccessService` (ADMIN/HR_MANAGER manage, RECRUITER reads, INTERVIEWER only own interviews) |
| M2 | Bad requests return 500 | Fixed | `GlobalExceptionHandler` maps type mismatch, missing parameters/parts, media type, locking conflicts, `ResponseStatusException`, 429 |
| M3 | Candidates see expired jobs and learn only at "Apply" | Fixed | `JobPublic` lists only PUBLISHED jobs with an open deadline; `JobDeadlineCloser` closes expired ones (WP-BE-B) |
| M4 | Job search loses focus per keystroke and refetches everything | Fixed | Filters always mounted, 300 ms debounce, results-only loading (WP-FE-CAND `pages/JobsPage.tsx`) |
| M5 | Cover letter supported by the API but has no input | Fixed | Apply dialog with resume picker and cover letter (WP-FE-CAND `pages/jobs/ApplyDialog.tsx`) |
| M6 | Two tabs refreshing wipe each other's tokens; a failed refresh leaves the UI "signed in" | Fixed | Single-flight refresh + one retry, sign-out on refused refresh (both portals' `api/client.ts`), server grace window |
| M7 | A brief AI outage marks resumes FAILED for good | Fixed | `background_jobs` queue: retries with exponential backoff, attempt cap, dead letter, stale-lock recovery (WP-BE-A `jobs/*`, PARSE_RESUME handler) |
| M8 | DOC uploads never parsed; scanned PDFs need OCR and sort last with an empty score | Partly | DOC parsed via LibreOffice or the olefile reader (the backend queued only PDF/DOCX until 15 Nov 2026; `ResumeIntelligenceService.createFor` now queues `.doc` too); scans return NEEDS_OCR and are grouped as UNSCORED / ANALYSIS_UNAVAILABLE instead of silently sorting last (WP-PY, WP-BE-B). OCR exists only as the optional extra (`requirements-ocr.txt`, `OCR_ENABLED=true`) and was tested with a fake engine, not real scans |
| M9 | Match results overwritten; decisions do not record the evidence shown | Fixed | Append-only `job_match_runs` with `is_current`; `application_decision_history.match_run_id`; job audit CSV shows the run's algorithm and coverages |
| M10 | Admin audit log is an in-memory buffer lost on restart | Fixed | `audit_events` table, `AuditService`, organisation CSV export (WP-BE-A) and per-job CSV (WP-BE-C) |
| M11 | Dashboard health hard-coded "UP" | Fixed | `SystemHealthService`: `SELECT 1`, AI `/health` with 2 s timeout, queue counts; shown on `/admin` |
| M12 | `LocalDateTime` everywhere; browser-local deadlines read in server time; MySQL TIMESTAMP 2038 | Fixed | UTC JVM zone, Hibernate `jdbc.time_zone=UTC`, `UtcTimeModule` (trailing `Z`, offsets converted); PostgreSQL 16; portals send `toISOString()` |
| M13 | Rejections irreversible; published jobs not editable; ON_HOLD job past its deadline can never reopen | Fixed | REOPEN and UNDO_SHORTLIST with comment; edits in DRAFT/PUBLISHED/ON_HOLD with re-matching; ON_HOLD jobs are not auto-closed and their deadline can be extended (WP-BE-B) |
| M14 | Must-haves only weighted ×2, never knockouts | Fixed | Knockout requirements are ranking gates (MUST_HAVES_UNVERIFIED group), never auto-rejections (D6): WP-PY engine, WP-BE-B ranking, WP-FE-HR toggles |
| M15 | Profile collects date of birth and gender with no purpose; no deletion or export | Fixed | V20 drops both columns; separate, consented, deletable equal-opportunity survey reported only in suppressed aggregates; JSON data export; account deletion with password (WP-BE-C `fairness/*`, `candidate/privacy/*`; WP-FE-CAND `/privacy`) |

### Low

| ID | Finding | Status | Where |
|---|---|---|---|
| L1 | Candidates' job API returns the HR creator's email | Fixed | `JobPublicResponse` has no creator, status or counts |
| L2 | N+1 queries in job list, admin jobs, HR team and applicant list | Fixed (not measured) | Entity graphs and batch projections (`ApplicantRow`, `RunSummary`, extension extras by page); no query-count test |
| L3 | Last-admin guard can race | Fixed | `UserRepository.lockEnabledByOrganizationAndRole` (`PESSIMISTIC_WRITE`) before demote/disable |
| L4 | LIKE wildcards not escaped | Fixed | `common/util/LikePatterns` in admin users/jobs and job search |
| L5 | `/actuator/metrics` readable by candidates; Swagger public in production | Fixed | `/actuator/**` except health requires ADMIN (`SecurityConfig`); springdoc disabled in `application-prod.yml` |
| L6 | H2 in compile scope ships in the production jar; mail starter and devtools unused | Fixed | H2 `test` scope; mail starter now used by the outbox; devtools `runtime` + `optional` (excluded from the boot jar) |
| L7 | HR lists have no stale-response guard | Fixed | Query keys carry every parameter (WP-FE-HR `api/queryKeys.ts`) |
| L8 | Both portals poll every 2.5 s forever | Fixed | Poll only while something is PENDING, with backoff, stop after 2 minutes (both portals) |
| L9 | JWT parsed three times plus a user load per request | Fixed | `JwtAuthenticationFilter` parses once; the user row is still loaded once so disable/role changes apply immediately (by design) |
| L10 | Recursive `findByResume` fallback is dead code that would overflow the stack | Fixed | Removed with the synchronous matching path (WP-BE-B) |
| L11 | README's demo launcher is not in the repository | Fixed | README rewritten around Docker Compose and manual setup (WP-BE-A, updated by WP-INT) |
| L12 | A non-dictionary requirement matches only when it shares a line with a known skill and cites that other fact | Fixed | Own-type lexical fallback that cites the fact containing the terms: WP-PY `matching/engine.py`; probes P6/P6b in `audit/probe_matching.py` and `tests/test_audit_probes.py` |
| L13 | Provisioning zeroes the password array after converting it to a String | Fixed | Console provisioners removed; first accounts come from sign-up, bootstrap, invitations or the seed |

Totals: 44 findings; 42 Fixed (backend verified by `mvn verify` on 15 Nov 2026), 1 Fixed but not measured (L2), 1 Partly (M8), 0 Deferred.

## 2. Integration (WP-INT) changes

### 2.1 Contract reconciliation

Every call in `frontend/src/api` and `hr-frontend/src/api` was compared with the controllers, request/response records and Jackson settings (`default-property-inclusion: non_null` for WP-A/B responses; WP-BE-C records use `@JsonInclude(ALWAYS)` and send explicit nulls). The HR portal reads optional fields with `== null`, `??` or optional chaining only (no `=== null` on API data); the candidate portal fills absent fields in its normalisers. Both shapes are therefore safe.

| # | Area | Mismatch | Fix |
|---|---|---|---|
| 1 | Requirements bulk replace (`PUT /jobs/{id}/requirements`) | The HR job form sent rows without `id`, so every save deleted and re-created all requirements. That broke work-sample rubric and interview-kit links and orphaned match evidence. The backend supports in-place updates by `id`. | Frontend: drafts keep the server `id` and send it (`hr-frontend/src/pages/jobs/requirementDrafts.ts`, `api/types.ts`) |
| 2 | Fairness report `count` | Shown as "N people" (group size); WP-BE-C defines it as applicants of the group who reached the stage | Frontend: "N reached", explainer and caption updated (`FairnessReportView.tsx`) |
| 3 | Work-sample `dueAt` | Labelled "Due by" / "DUE" (HR) and "due" (candidate application page); the backend means the latest **start** time | Frontend: "Start by" everywhere, with the time-limit explanation |
| 4 | Interview duration | HR form allowed 10–480 minutes; the backend accepts 5–480 | Frontend aligned to 5–480 |
| 5 | `PATCH /interviews/{id}` reschedule | Supported by the API, no UI | HR applicant view: Reschedule dialog that sends only changed fields (`location: ""` clears) |
| 6 | `/me/notifications*`, `/me/password` | Not used by the HR portal | HR inbox with unread badge and change-password page (Section 2.3) |
| 7 | INTERVIEW_CANCELLED template (new in WP-BE-C) | No label | Labelled in the HR inbox; the candidate inbox shows subject and body only |
| 8 | Candidate work-sample submit after the grace period | 400 + assignment EXPIRED were shown as a generic error; the page stayed on the timer | Refetch, switch to the expired state, keep the unsent text read-only for copying; a refused start also refetches |
| 9 | Candidate account deletion | The server clears the cookie, but a pending request could still trigger `POST /auth/refresh` | Client blocks refresh after deletion until the next sign-in |
| 10 | Refresh cookie | One `rp_refresh` for both portals (cookies ignore ports) | Per-portal cookies (Section 2.2) |
| 11 | CORS | Allowed headers did not include the new portal header | `X-RecruitPro-Portal` added (`config/WebConfig`) |

Checked and matching (no change): applicant list and detail (all summary/detail fields, `revealAllowed`, `provenance`, staff claim view), decisions and legacy aliases, reveal, claims and claim verification, HR work samples (generate, edit, status, assign, assignments filter, score), candidate work samples (list, start, submit), interview kit (404 = none), interviews and scorecards, job and organisation audit exports (ISO instants with `Z` parse; `Content-Disposition` exposed), equal-opportunity survey (404 → none, consent, values), data export (raw JSON attachment) and deletion (wrong password = 400), compile-requirements, invitations (`status`, `inviteUrl`), organisation settings, dashboard health (`UP`/`DOWN`/`UNCONFIGURED` + queue counts), notifications, interviewers, staff users and job oversight.

### 2.2 Separate sessions per portal

- Both portals send `X-RecruitPro-Portal: candidate` / `staff` on every request (`api/client.ts`).
- Backend `auth/web/Portal`: the header selects the refresh cookie `rp_refresh_candidate` / `rp_refresh_staff`; without the header the original `rp_refresh` is used (API clients, CI smoke test). A portal reads only its own cookie. An unknown header value is 400.
- Role gate, 403 with a message naming the right portal, **before** any session is created: login (after the password check; failure counters untouched), refresh (before rotation; the cookie is cleared, the token stays valid for its own portal), accept-invite and organisation sign-up on the candidate portal, registration on the staff portal.
- Logout, password change and account deletion read/write/clear only the calling portal's cookie.
- CORS allows the header. Tests: `auth/PortalSessionIntegrationTest` (both sessions coexist, isolation, 403 paths, legacy fallback, unknown value, password change, CORS preflight) and `auth/web/PortalTest`. Existing tests use no header and keep exercising `rp_refresh`.

### 2.3 UI gaps closed

- HR portal: notifications inbox (`/notifications`, mark read / all read, pagination) with an unread badge in the header and sidebar; change-password page (`/account/password`) that adopts the new session the server returns; interview reschedule form; fairness "reached" label; start-by labels.
- Candidate portal: expired work samples (late submit or start → expired state with the unsent text), "start by" on the application page, no refresh calls after account deletion.

### 2.4 Cross-package Java review

A dedicated review read every caller of `AuditService`, `NotificationService`, `BackgroundJobService`, `HrJobAccessService`, `SecurityUtils`, `EvidenceContributor`, `ApplicationDetailExtension`, `AiServiceClient` and the shared DTOs, and every test that touches them. A tree-sitter based resolver checked about 5,400 member calls (names, arity, record accessors vs Lombok getters, static vs instance, enum constants, JPQL constructor expressions, derived query names) and 122 beans (missing or ambiguous beans, constructor cycles), plus all 53 `@Value` keys against the yml files. **No compile-level, wiring or configuration defect was found.** Two `@Transactional` self-invocations (`RefreshTokenService.rotate → issue`, `OrganizationService.updateCurrent → requireCurrentOrganization`) are harmless because they already run inside the caller's transaction. WP-INT's own `RefreshTokenService.rotate(String)` → `rotate(String, Portal)` overload is the same, with identical annotations.

### 2.5 Other

- `backend/.env.example` documents the scheduler switches and `AUTH_EXPOSE_REFRESH_TOKEN_IN_BODY`; CI also runs `tools/check_entity_schema.py`.
- `README.md` rewritten for the final state; architecture doc and plan Section 4 updated for per-portal cookies.

## 3. New capabilities (summary)

Organisations and invitations; hardened auth (rotation, lockout, rate limits, verification, reset, per-portal sessions); persistent audit with CSV export; background job queue; mail outbox and in-app inboxes; real health checks; PostgreSQL 16 with UTC; requirements v2 (alternatives, knockouts, proficiency) with an AI requirement compiler and biased-wording warnings; evidence ladder with claims and recruiter corroboration; asynchronous, append-only matching (evidence-ladder-3) with server ranking; blind-first review with audited reveal; full application lifecycle with undo/reopen; work samples with anchored rubrics; structured interview kits and scorecards (tiers 3 and 4 feed matching); voluntary equal-opportunity survey and adverse-impact reports; candidate data export and account deletion; AI service v2 (847-skill taxonomy, two-column/hidden-text/injection handling, de-identification, timelines, optional LLM and OCR) with a synthetic evaluation harness.

## 4. What remains

### 4.1 Must do before merging

- **Run `cd backend && ./mvnw verify` with JDK 21** and fix what it reports. Watch-list from the package notes: `@MockitoBean` with the subclass mock maker, JPQL constructor expressions, `@JsonInclude(ALWAYS)` on records, `@EntityGraph` on the overridden `findAll(Specification, Pageable)`, `@Convert` on `List<String>`, V1–V20 on H2 (only ever applied to PostgreSQL), deprecations (`@SpyBean`, `new DaoAuthenticationProvider()`).
- Run both portals against `docker compose up` once and click through the main flows; the portals were smoke-tested only against mocked APIs.

### 4.2 Deferred (plan Section 10)

| Item | Why deferred | Next step |
|---|---|---|
| Spring Boot 4 / Jackson 3 | Large breaking upgrade; cannot compile here | Separate PR after Boot 3.5 is green in CI |
| PostgreSQL row-level security | Service-layer scoping first; RLS needs per-request `SET` plumbing | Add once tenancy tests are green |
| pgvector semantic matching | Needs an embedding model download | Embedding provider behind the AI-service interface |
| ESCO import | Bulk ESCO download unreachable here | `ai-service/app/taxonomy/import_esco.py` with the official CSV |
| Calendar, HRMS and job-board connectors | Need partner credentials | Interview emails carry time and place today (UTC) |
| WhatsApp notifications | Needs a provider account | `NotificationChannel` interface is ready |
| Outcome data (90-day performance) | Too little data | Capture fields when the first pilot hires |
| Real labelled gold set | Needs recruiters' labels on consented resumes | Replace the synthetic set before quoting accuracy |

### 4.3 Known gaps and risks

- Not built: fairness by intersectional groups, per-question interviewer notes visible to other interviewers. (Work-sample file uploads, HR cancellation, scorecard editing, fairness by date range, the demo seed for work samples/kits/interviews, staff MFA and SSO, and the multi-instance safeguards were added in plan v2; see the top of this file.)
- A deleted candidate's match runs keep the AI's de-identified evidence quotes for audit; the equal-opportunity survey is not versioned, so one answer applies to all of a candidate's applications.
- After this change, users signed in before the deployment have only the old `rp_refresh` cookie and must sign in once more on each portal.
- No automated UI test runner is committed for either portal (the WP-FE smoke harnesses lived outside the repository).

## 5. Verification evidence (WP-INT, 2026-11-08)

| Gate | Command | Result |
|---|---|---|
| G-PY tests | `cd ai-service && python3 -B -m pytest -q -p no:cacheprovider` | 177 passed |
| G-PY evaluation | `python3 -B -m eval.run --check` | CHECK PASSED (skill F1 0.998, date accuracy 0.992, kappa 0.993, NDCG@10 0.990, flip rate 0.0, AIR 1.0) |
| G-SQL | `python3 -B tools/check_migrations_pg.py` | 20 migrations applied to PostgreSQL 16 |
| Entity/schema | `python3 -B tools/check_entity_schema.py` | 300 mapped columns of 26 entities, 0 errors, 0 warnings |
| G-JAVA-STATIC | `python3 -B tools/java_static_check.py backend/src` | 387 files, 0 errors |
| G-FE candidate | clean copy → `npx --yes pnpm@10 install --frozen-lockfile` → `npx --yes pnpm@10 build` | passed (`tsc --noEmit` + `vite build`) |
| G-FE HR | same | passed |
| WP-INT UI smoke (not committed) | esbuild IIFE bundles of both portals in jsdom against a mocked API | HR 12/12: portal header on every request, inbox + header/sidebar badge, mark read, INTERVIEW_CANCELLED label, change password body + notice, reschedule PATCH with only changed fields, fairness "N reached", no console errors. Candidate 9/9: portal header, late submit → expired state with the unsent text, refetch, account deletion → `/login` notice and no `/auth/refresh` afterwards, no console errors |
| G-JAVA-REVIEW | cross-package review (Section 2.4) | no defects found |
| **G-JAVA-REAL** | `cd backend && ./mvnw verify` | passed 15 Nov 2026 (239 tests, 6 skipped); re-run after the `.doc` change |
