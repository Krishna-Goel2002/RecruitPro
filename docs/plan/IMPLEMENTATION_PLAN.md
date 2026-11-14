# RecruitPro implementation plan — audit remediation and the evidence platform

Status: **approved for implementation** · Date: 2026-11-08 · Source: co-founder audit (44 findings, product direction, target architecture).

This plan turns the audit into buildable work. It is the contract every work package (WP) follows: data model, migrations, API shapes, AI-service contract, UI scope, verification gates. When code and this plan disagree, fix one of them in the same change.

Finding IDs (C1…L13) refer to the audit's bug tables. Section 9 maps every finding to a work package.

---

## 1. Decisions (ADR-lite)

| # | Decision | Why | Consequence |
|---|---|---|---|
| D1 | **PostgreSQL 16** replaces MySQL. V1–V10 are re-expressed in PostgreSQL syntax with the same versions and meaning; V11+ are new. | No production data exists; row-level security, JSONB and pgvector later; migrations are verifiable with `tools/check_migrations_pg.py`. | Existing MySQL databases are left untouched. New local dev uses Docker Compose Postgres. Tests use H2 in `MODE=PostgreSQL`, so migrations stay inside the H2-compatible subset (Section 3.1). |
| D2 | **Spring Boot 3.5.6, Java 21.** springdoc 2.8.9. Spring Boot 4 is a later, separate migration. | Fixes CVE-2025-22228 exposure (Boot 3.2 / Security 6.2 fix is enterprise-only); smallest safe jump; virtual threads. | Developers need JDK 21 (or Docker). |
| D3 | **Multi-tenant by organisation.** Staff users belong to exactly one organisation; candidates are global and can apply to any organisation's published jobs. Tenancy is enforced in the service layer (every staff query is scoped by `organization_id`). RLS is deferred. | A second customer is impossible today. | Every HR/admin repository method takes an organisation id. `ADMIN` means organisation administrator. |
| D4 | **Blind-first review.** Identity, resume file and cover letter are hidden from reviewers until the application is shortlisted (auto-reveal, audited) or an admin disables blind review for the organisation. | Bias reduction is a differentiator; fixes C3 without losing the fairness benefit. | Reviewers see "Candidate #A-482" labels before reveal. |
| D5 | **Evidence Ladder.** Tiers: `SELF_CLAIMED`(0) < `RESUME_STATED`(1) < `CORROBORATED`(2) < `DEMONSTRATED`(3) < `INTERVIEW_VERIFIED`(4). Ranking uses *verified coverage* (tier ≥ 1). Candidate edits become tier-0 claims. | Fixes C1; aligns with selection science. | Two numbers per applicant: verified and claimed coverage. |
| D6 | **Must-haves are ranking gates, never auto-rejections.** `knockout` requirements that are not verified put the applicant in a "must-haves not yet verified" group. | Fixes M14 without false auto-rejection from parser misses. | Humans still decide every status. |
| D7 | **All AI work is asynchronous.** A generic `background_jobs` table (retry with exponential backoff, attempt cap, dead-letter) drives resume parsing and matching. GET endpoints never call the AI service and never write. | Fixes H6, H8, M7. | Match results appear a few seconds after triggers; UI shows "matching…". |
| D8 | **Append-only match runs.** Every run is kept; one is flagged current. Every decision stores the `match_run_id` it was made on. | Fixes M9; audit and EU AI Act logging. | Storage grows linearly; fine at this scale. |
| D9 | **AI service runs offline by default.** A much stronger deterministic pipeline is the baseline; an optional LLM provider (`LLM_PROVIDER=anthropic|openai_compatible`) enhances extraction, requirement compiling and generation. Every LLM output must cite verbatim spans; outputs failing validation fall back to rules. | Works with no keys, no data leaves the machine by default; LLM is an upgrade, not a dependency. | Quality without a key is good, not best. |
| D10 | **Own skills taxonomy (multi-domain, ~600 skills) with ESCO hooks.** ESCO bulk data is not downloadable from this environment; `taxonomy/import_esco.py` imports the official ESCO CSV when available. | Fixes H1 now; ESCO later without code changes. | Taxonomy is curated JSON under version control. |
| D11 | **Refresh tokens in an httpOnly cookie**, 15-minute access tokens kept in memory, rotation with reuse detection and a 30-second grace window for concurrent tabs. | Fixes S2, M6. | Frontends call the API with `credentials: 'include'`; CORS allows credentials for configured origins. |
| D12 | **Integrate, don't build** offers, onboarding and HR analytics. The lifecycle ends at `SELECTED` (hand-off). | Focus. | Empty `hiring`, `onboarding`, `analytics` packages are removed. |
| D13 | **Notifications through an outbox.** Email via SMTP (Mailpit in dev) plus an in-app inbox. WhatsApp is an interface with no provider yet. | Candidate experience; reliable delivery. | `notifications` table; a scheduled sender. |
| D14 | **Voluntary equal-opportunity data is stored apart**, consented, deletable, never shown to reviewers, reported only in aggregates with groups smaller than 5 suppressed. Date of birth and gender are removed from the profile. | M15, DPDP purpose limitation, adverse-impact monitoring. | Fairness reports need survey responses to be meaningful. |
| D15 | **All timestamps are UTC.** JVM default zone UTC, Hibernate `jdbc.time_zone=UTC`, JSON timestamps serialised with a trailing `Z`; inputs with an offset are converted to UTC. | Fixes M12. | Frontends always send ISO strings with offset (`toISOString()`). |

---

## 2. Environment constraints and verification gates

The implementation environment has Python 3.10, Node 22 and a pip-installable PostgreSQL 16 (`pgserver`), but **no JDK 17/21 and no Maven Central access**. Therefore:

| Gate | Tool | Required for |
|---|---|---|
| G-PY | `cd ai-service && python -m pytest -q` (all pass) and `python -m eval.run --check` (thresholds pass) | WP-PY |
| G-SQL | `python tools/check_migrations_pg.py` (all migrations apply to PostgreSQL 16) | every WP that adds migrations |
| G-JAVA-STATIC | `python tools/java_static_check.py backend/src` → 0 errors (syntax, imports, unresolved types) | every backend WP |
| G-JAVA-REVIEW | a reviewer pass that reads every changed Java file for method/field/generic correctness, Lombok usage and Spring wiring | every backend WP |
| G-FE | `npm run build` (tsc + vite) in `frontend/` and `hr-frontend/` | frontend WPs |
| G-JAVA-REAL | `./mvnw verify` with JDK 21 (developer machine or CI) | **must run before merging; cannot run here** |

Java code is written to compile, but the first real compile happens on a developer machine or in CI. The CI workflow added in WP-BE-A runs `mvn verify`.

---

## 3. Data model and migrations

### 3.1 SQL dialect rules (PostgreSQL that H2 `MODE=PostgreSQL` also accepts)

- Primary keys: `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`.
- Types: `VARCHAR(n)`, `TEXT`, `BOOLEAN`, `INTEGER`, `BIGINT`, `DECIMAL(p,s)`, `DATE`, `TIMESTAMP` (UTC by convention; entities use `LocalDateTime`).
- Constraints: named `CONSTRAINT … CHECK/UNIQUE/FOREIGN KEY` inside `CREATE TABLE`; `ALTER TABLE … ADD CONSTRAINT`, `ALTER TABLE … DROP CONSTRAINT name`, `ALTER TABLE … ADD COLUMN`, `ALTER TABLE … ALTER COLUMN c SET NOT NULL`, `ALTER TABLE … DROP COLUMN`.
- Indexes: `CREATE INDEX name ON table (col, col DESC)`. No partial, expression or GIN indexes. No `ON UPDATE`, no `AUTO_INCREMENT`, no inline `INDEX`, no JSONB (JSON is stored in `TEXT`), no extensions, no `ENUM` types.
- Every table has `created_at TIMESTAMP NOT NULL` and `updated_at TIMESTAMP NOT NULL` (JPA auditing fills them) unless stated.
- Profiles: `dev`/`prod` run on PostgreSQL with `ddl-auto: validate`. The `test` profile runs Flyway on H2 (`jdbc:h2:mem:recruitpro;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1`) with `ddl-auto: none`, because H2 reports `TEXT` as CLOB and would fail Hibernate's validator; schema/entity agreement is checked on PostgreSQL in CI instead.

### 3.2 Migrations

V1–V10 keep their file names and meaning, rewritten in PostgreSQL syntax (identity columns; `ON UPDATE CURRENT_TIMESTAMP` dropped because auditing sets `updated_at`; inline indexes moved to `CREATE INDEX`). The system_metadata insert in V1 must supply `created_at`/`updated_at` via defaults (`DEFAULT CURRENT_TIMESTAMP` is allowed on those two columns).

| Version | WP | Content |
|---|---|---|
| V11__organizations_and_tenancy | BE-A | `organizations(id, name VARCHAR(200) NOT NULL, slug VARCHAR(80) NOT NULL UNIQUE, blind_review BOOLEAN NOT NULL DEFAULT TRUE, share_verification_status BOOLEAN NOT NULL DEFAULT TRUE, created_at, updated_at)`; `users` + `organization_id BIGINT NULL FK organizations`, `email_verified_at TIMESTAMP NULL`, `failed_login_count INTEGER NOT NULL DEFAULT 0`, `locked_until TIMESTAMP NULL`, `password_changed_at TIMESTAMP NULL`, `deleted_at TIMESTAMP NULL`; `jobs` + `organization_id BIGINT` (backfill: create organisation `default` if any non-candidate user exists, attach staff users and their jobs, then `SET NOT NULL`); `invitations(id, organization_id FK, email VARCHAR(255) NOT NULL, role VARCHAR(30) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE, invited_by_user_id FK users NULL, expires_at TIMESTAMP NOT NULL, accepted_at NULL, revoked_at NULL, created_at, updated_at)`; indexes `jobs(organization_id, status)`, `users(organization_id)`, `invitations(organization_id, created_at)`. |
| V12__auth_hardening | BE-A | `refresh_tokens` + `family_id VARCHAR(36)` (backfill `'legacy-' || id`, then NOT NULL), `replaced_at TIMESTAMP NULL`, `revoked_reason VARCHAR(30) NULL`, index `(family_id)`; `user_tokens(id, user_id FK users ON DELETE CASCADE, purpose VARCHAR(30) NOT NULL CHECK IN ('VERIFY_EMAIL','RESET_PASSWORD'), token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at, used_at NULL, created_at, updated_at)`. |
| V13__audit_events | BE-A | `audit_events(id, organization_id BIGINT NULL, actor_user_id BIGINT NULL, actor_email VARCHAR(255), action VARCHAR(80) NOT NULL, target_type VARCHAR(60), target_id VARCHAR(80), details TEXT, ip_address VARCHAR(64), created_at, updated_at)`; indexes `(organization_id, created_at DESC)`, `(action)`. No foreign keys (audit outlives rows). |
| V14__background_jobs_and_notifications | BE-A | `background_jobs(id, job_type VARCHAR(40) NOT NULL, entity_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL CHECK IN ('PENDING','RUNNING','DONE','FAILED','DEAD'), attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 5, run_after TIMESTAMP NOT NULL, locked_at NULL, last_error VARCHAR(500) NULL, created_at, updated_at)`; indexes `(status, run_after)`, `(job_type, entity_id)`; `notifications(id, organization_id NULL, recipient_user_id BIGINT NULL FK users ON DELETE SET NULL, recipient_email VARCHAR(255) NOT NULL, channel VARCHAR(20) NOT NULL CHECK IN ('EMAIL','IN_APP'), template VARCHAR(60) NOT NULL, subject VARCHAR(255) NOT NULL, body TEXT NOT NULL, status VARCHAR(20) NOT NULL CHECK IN ('PENDING','SENT','FAILED','SKIPPED'), attempts INTEGER NOT NULL DEFAULT 0, sent_at NULL, read_at NULL, created_at, updated_at)`; indexes `(recipient_user_id, created_at DESC)`, `(status, created_at)`. Existing `resume_analyses` rows in `PENDING` get a `PARSE_RESUME` job. |
| V15__requirements_v2 | BE-B | `job_requirements` + `alternatives_json TEXT NULL`, `knockout BOOLEAN NOT NULL DEFAULT FALSE` (backfill `= is_mandatory`), `proficiency VARCHAR(20) NULL CHECK IN ('BASIC','INTERMEDIATE','ADVANCED','EXPERT')`; `jobs` + `requirements_version INTEGER NOT NULL DEFAULT 1`, `closed_reason VARCHAR(60) NULL`. |
| V16__applications_v2 | BE-B | `applications`: replace status check with `('SUBMITTED','SHORTLISTED','INTERVIEWING','SELECTED','REJECTED','WITHDRAWN')`, + `identity_revealed_at TIMESTAMP NULL`, `identity_revealed_by BIGINT NULL FK users`, `status_changed_at TIMESTAMP NULL`; `application_decision_history`: replace action check with `('SHORTLIST','REJECT','COMMENT_UPDATED','UNDO_SHORTLIST','REOPEN','MOVE_TO_INTERVIEW','SELECT','WITHDRAW','IDENTITY_REVEALED')`, + `match_run_id BIGINT NULL FK job_match_runs`. |
| V17__evidence_ladder | BE-B | `resume_analysis_facts` + `evidence_tier VARCHAR(30) NOT NULL DEFAULT 'RESUME_STATED'`, `span_start INTEGER NULL`, `span_end INTEGER NULL`, `attributes_json TEXT NULL`; fact-type check gains `'LINK'`; `resume_analyses` + `flags_json TEXT NULL`, `timeline_json TEXT NULL`; `candidate_claims(id, candidate_id FK candidate_profiles ON DELETE CASCADE, claim_type VARCHAR(30) NOT NULL CHECK IN ('SKILL','CERTIFICATION','EDUCATION','EXPERIENCE','OTHER'), claim_value VARCHAR(1000) NOT NULL (`value` is reserved in H2), evidence_url VARCHAR(500) NULL, source_fact_id BIGINT NULL, tier VARCHAR(30) NOT NULL DEFAULT 'SELF_CLAIMED', verified_by_user_id BIGINT NULL FK users, verified_at NULL, verification_note VARCHAR(500) NULL, created_at, updated_at)`, index `(candidate_id)`; `job_match_runs`: drop `uk_job_match_runs_application`, + `is_current BOOLEAN NOT NULL DEFAULT TRUE`, `verified_coverage DECIMAL(5,1) NULL`, `claimed_coverage DECIMAL(5,1) NULL`, `knockouts_verified BOOLEAN NULL`, `requirements_version INTEGER NOT NULL DEFAULT 1`, `trigger_reason VARCHAR(40) NULL`; backfill `verified_coverage = observed_evidence_coverage`; indexes `(job_id, is_current, verified_coverage DESC)`, `(application_id, is_current)`. |
| V18__work_samples | BE-C | `work_samples(id, job_id FK jobs, title VARCHAR(255) NOT NULL, instructions TEXT NOT NULL, time_limit_minutes INTEGER NOT NULL CHECK > 0, rubric_json TEXT NOT NULL, status VARCHAR(20) NOT NULL CHECK IN ('DRAFT','ACTIVE','ARCHIVED'), created_by_user_id FK users, created_at, updated_at)`; `work_sample_assignments(id, work_sample_id FK, application_id FK applications, status VARCHAR(20) NOT NULL CHECK IN ('ASSIGNED','STARTED','SUBMITTED','SCORED','EXPIRED','CANCELLED'), assigned_at, due_at NULL, started_at NULL, submitted_at NULL, response_text TEXT NULL, response_url VARCHAR(500) NULL, scores_json TEXT NULL, total_score DECIMAL(5,2) NULL, scored_by_user_id NULL FK users, scored_at NULL, created_at, updated_at, UNIQUE(work_sample_id, application_id))`. |
| V19__interviews | BE-C | `interview_kits(id, job_id FK UNIQUE, questions_json TEXT NOT NULL, generated_by VARCHAR(40), created_at, updated_at)`; `interviews(id, application_id FK, interviewer_user_id FK users, scheduled_at TIMESTAMP NOT NULL, duration_minutes INTEGER NOT NULL, location VARCHAR(500), status VARCHAR(20) NOT NULL CHECK IN ('SCHEDULED','COMPLETED','CANCELLED'), created_by_user_id FK users, created_at, updated_at)`; `interview_scorecards(id, interview_id FK UNIQUE, ratings_json TEXT NOT NULL, recommendation VARCHAR(20) NOT NULL CHECK IN ('STRONG_NO','NO','YES','STRONG_YES'), summary TEXT, submitted_at TIMESTAMP NOT NULL, created_at, updated_at)`; indexes `interviews(application_id)`, `interviews(interviewer_user_id, scheduled_at)`. |
| V20__fairness_and_privacy | BE-C | `eeo_responses(id, candidate_id FK candidate_profiles ON DELETE CASCADE UNIQUE, gender VARCHAR(40), age_band VARCHAR(20), disability VARCHAR(20), social_category VARCHAR(40), consent_given_at TIMESTAMP NOT NULL, created_at, updated_at)`; `candidate_profiles` drop `date_of_birth`, `gender`. |

### 3.3 Enumerations (Java enums, stored as strings)

- `Role`: ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER, CANDIDATE.
- `ApplicationStatus`: SUBMITTED, SHORTLISTED, INTERVIEWING, SELECTED, REJECTED, WITHDRAWN.
- `ApplicationDecisionAction`: SHORTLIST, REJECT, COMMENT_UPDATED, UNDO_SHORTLIST, REOPEN, MOVE_TO_INTERVIEW, SELECT, WITHDRAW, IDENTITY_REVEALED.
- `EvidenceTier`: SELF_CLAIMED, RESUME_STATED, CORROBORATED, DEMONSTRATED, INTERVIEW_VERIFIED (ordinal = tier).
- `BackgroundJobType`: PARSE_RESUME (entity = resume_analysis id), MATCH_APPLICATION (entity = application id).
- `ResumeFactType`: SKILL, EDUCATION, EXPERIENCE, CERTIFICATION, EMAIL, PHONE, LINK.

### 3.4 State machines

Application (HR actions; candidate may withdraw from SUBMITTED, SHORTLISTED, INTERVIEWING):

| From | Action | To | Rule |
|---|---|---|---|
| SUBMITTED | SHORTLIST | SHORTLISTED | auto-reveals identity (audited) |
| SUBMITTED | REJECT | REJECTED | |
| SHORTLISTED | UNDO_SHORTLIST | SUBMITTED | comment required; identity stays revealed |
| SHORTLISTED | MOVE_TO_INTERVIEW | INTERVIEWING | also happens when the first interview is scheduled |
| SHORTLISTED, INTERVIEWING | REJECT | REJECTED | |
| INTERVIEWING | SELECT | SELECTED | terminal in RecruitPro (hand-off) |
| REJECTED | REOPEN | SUBMITTED | comment required |

Job (owner HR, HR manager or admin within the organisation): DRAFT → PUBLISHED | CANCELLED; PUBLISHED → ON_HOLD | CLOSED; ON_HOLD → PUBLISHED | CLOSED. CLOSED and CANCELLED are terminal for everyone, including the admin override (H7). A scheduled task closes PUBLISHED jobs whose deadline has passed (`closed_reason = 'DEADLINE_PASSED'`). Text fields, salary, vacancies and deadline are editable in DRAFT, PUBLISHED and ON_HOLD; requirement edits on a PUBLISHED/ON_HOLD job increment `requirements_version`, are audited and enqueue re-matching for every application.

---

## 4. Backend design (cross-cutting)

Packages under `com.recruitpro` (new ones in bold): `common`, `config`, `security`, **`organization`**, `auth`, `user`, **`audit`**, **`jobs`** (background jobs), **`notification`**, `hr`, `candidate`, `application`, `ai`, **`evidence`**, `assessment` (work samples), `interview`, **`fairness`**, `admin`. Empty `hiring`, `onboarding`, `analytics` packages are deleted (D12).

**Time (D15).** `RecruitProApplication.main` sets `TimeZone.setDefault(UTC)`; `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`; a Jackson module writes `LocalDateTime` as `2026-11-08T10:15:30Z` and reads values with or without an offset (converting to UTC).

**Errors (M2).** `GlobalExceptionHandler` maps `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `MissingServletRequestPartException`, `HandlerMethodValidationException` → 400; `HttpMediaTypeNotSupportedException` → 415; `PessimisticLockingFailureException`, `ObjectOptimisticLockingFailureException` → 409; `ResponseStatusException` → its status; new `TooManyRequestsException` → 429. `IllegalArgumentException` returns a generic "Invalid request" (no internal messages).

**Current user.** `security.CurrentUser` (record: userId, email, role, organizationId) resolved from `CustomUserDetails`; `SecurityUtils.currentUser()` / `requireOrganizationId()`. The JWT carries `uid`, `role`, `org` claims but the user row is still loaded per request (immediate disable/role change). The filter parses the token once and treats an unknown user as unauthenticated (no 500).

**Tokens (D11, S2, M6).** Access token 15 min (`app.jwt.access-token-expiry-ms: 900000`), refresh 14 days. Refresh token only in an httpOnly cookie (SameSite=Strict, Path=`/api/v1/auth`, Secure=`app.auth.cookie-secure`), one per portal (WP-INT): both portals send `X-RecruitPro-Portal: candidate|staff`, which selects `rp_refresh_candidate` or `rp_refresh_staff` (plain `rp_refresh` without the header) and limits login/refresh/accept-invite/signup/register to the portal's roles (candidate portal: CANDIDATE; staff portal: every other role; otherwise 403). Rotation keeps `family_id`; reusing a token rotated less than 30 s ago returns a new access token without a new cookie (concurrent tabs); reuse after that revokes the whole family (`REUSE_DETECTED`). Logout revokes the family and clears the cookie. Password reset/change revokes all of the user's tokens. `app.auth.expose-refresh-token-in-body` (default false) exists only for API clients and tests.

**Abuse controls (S1).** In-memory sliding-window rate limiter filter on `/api/v1/auth/**` and `/api/v1/organizations/signup` keyed by client IP + route (login 10/min, register 5/min, refresh 30/min, forgot-password 5/15 min, accept-invite 10/min, signup 3/h), `app.rate-limit.enabled`. Lockout: 5 failed logins → `locked_until = now + 15 min`; a locked account gets the same 401 as a wrong password. Email verification token (24 h) on register; `app.auth.require-verified-email-to-apply` (dev false, prod true). Password reset token (1 h); forgot-password always returns 200.

**Organisations (D3, S5, M1).** Staff users carry `organization_id`; candidates have none. Every staff query is scoped by the current organisation; a foreign-organisation id behaves as 404. ADMIN and HR_MANAGER manage every job in their organisation; RECRUITER reads every job and applicant in the organisation and can comment but not decide; INTERVIEWER sees only interviews assigned to them. First account paths: (a) `POST /api/v1/organizations/signup` (`app.organizations.self-signup-enabled`, default true), (b) `BootstrapRunner` creating the first organisation + admin from `BOOTSTRAP_ORG_NAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` when no organisation exists (any profile), (c) invitations (7-day token, emailed link `${app.frontend.hr-url}/accept-invite?token=`). `DevSeedRunner` (`app.seed.demo=true`) creates organisation "Acme Labs" with admin@acme.test, hr@acme.test, recruiter@acme.test, interviewer@acme.test, candidate@demo.test (password `app.seed.password`, default `ChangeMe-123!`) and two published jobs with requirements; idempotent. The console provisioners are removed.

**Audit (M10).** `audit.AuditService.record(action, targetType, targetId, details)` persists an `AuditEvent` with organisation, actor and client IP. Actions: USER_STATUS_CHANGED, USER_ROLE_CHANGED, INVITATION_CREATED, INVITATION_REVOKED, INVITATION_ACCEPTED, ORGANIZATION_UPDATED, ADMIN_JOB_STATUS_OVERRIDE, JOB_REQUIREMENTS_CHANGED, JOB_AUTO_CLOSED, APPLICATION_DECISION, IDENTITY_REVEALED, RESUME_DOWNLOADED_BY_STAFF, CLAIM_VERIFIED, WORK_SAMPLE_SCORED, SCORECARD_SUBMITTED, AUDIT_EXPORTED, CANDIDATE_ACCOUNT_DELETED, LOGIN_LOCKED. Details never contain passwords, tokens or resume text.

**Background jobs (D7, M7).** `jobs.BackgroundJobService.enqueue(type, entityId)` (no duplicate PENDING/RUNNING job for the same type and entity). `BackgroundJobWorker` (`@Scheduled` every 2 s, `app.background-jobs.enabled`) claims up to 5 due jobs with a conditional update, dispatches to `BackgroundJobHandler` beans by type, marks DONE; on exception `attempts+1`, then DEAD at `max_attempts`, else PENDING with `run_after = now + min(15 s × 2^attempts, 30 min)`; RUNNING jobs locked for more than 10 minutes return to PENDING. Handlers: `PARSE_RESUME` (BE-A, replaces `ResumeAnalysisWorker`), `MATCH_APPLICATION` (BE-B).

**Notifications (D13).** `NotificationService.notify(recipient, template, params)` writes an EMAIL row and, for registered users, an IN_APP row. `NotificationSender` (`@Scheduled`) sends PENDING emails through `JavaMailSender` when `app.mail.enabled=true`; otherwise marks them SKIPPED and logs subject + link at INFO (dev). Templates (plain text, `NotificationTemplates`): VERIFY_EMAIL, PASSWORD_RESET, INVITATION, APPLICATION_RECEIVED, APPLICATION_STATUS_CHANGED, WORK_SAMPLE_ASSIGNED, INTERVIEW_SCHEDULED, INTERVIEW_ASSIGNED.

**Health (M11).** `SystemHealthService`: database `SELECT 1`, AI service `GET /health` (2 s timeout), queue counts (pending, failed, dead).

**Other fixes.** Admin job override uses an explicit transition table (H7). Last-admin guard locks the organisation's enabled admins with `PESSIMISTIC_WRITE` before demote/disable (L3). LIKE patterns escape `%`, `_` and `\` (L4). `/actuator/**` except health requires ADMIN; Swagger is off in prod (L5). H2 is test-scoped (L6).

---

## 5. API contract

All responses use the existing envelope `{ success, message?, data?, errors?, timestamp }`; pages use `{ content, page, size, totalElements, totalPages, first, last }`. Timestamps are ISO-8601 UTC strings ending in `Z`. Types below use TypeScript notation; `?` = may be absent/null.

### 5.1 Shared types

```ts
type Role = 'ADMIN' | 'HR_MANAGER' | 'RECRUITER' | 'INTERVIEWER' | 'CANDIDATE'
type EvidenceTier = 'SELF_CLAIMED' | 'RESUME_STATED' | 'CORROBORATED' | 'DEMONSTRATED' | 'INTERVIEW_VERIFIED'
type ApplicationStatus = 'SUBMITTED' | 'SHORTLISTED' | 'INTERVIEWING' | 'SELECTED' | 'REJECTED' | 'WITHDRAWN'
type DecisionAction = 'SHORTLIST' | 'REJECT' | 'UNDO_SHORTLIST' | 'REOPEN' | 'MOVE_TO_INTERVIEW' | 'SELECT'
type RequirementType = 'SKILL' | 'EDUCATION' | 'CERTIFICATION' | 'EXPERIENCE' | 'OTHER'
type Proficiency = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT'

type AuthUser = { id: number; email: string; firstName: string; lastName: string; role: Role;
  organizationId: number | null; organizationName: string | null; emailVerified: boolean }
type AuthResponse = { accessToken: string; tokenType: 'Bearer'; expiresInSeconds: number; user: AuthUser }

type Requirement = { id: number; type: RequirementType; description: string; mandatory: boolean; knockout: boolean;
  minimumYears: number | null; alternatives: string[]; proficiency: Proficiency | null }
type RequirementInput = Omit<Requirement, 'id'> // knockout defaults to mandatory, alternatives to []

type JobStaff = { id: number; organizationId: number; title: string; description: string; department: string;
  location: string; jobType: 'FULL_TIME'|'PART_TIME'|'CONTRACT'|'INTERNSHIP'; experienceLevel: 'ENTRY'|'MID'|'SENIOR'|'LEAD'|'EXECUTIVE';
  minimumSalary: number | null; maximumSalary: number | null; vacancies: number;
  status: 'DRAFT'|'PUBLISHED'|'ON_HOLD'|'CLOSED'|'CANCELLED'; publishedAt: string | null; deadline: string | null;
  closedReason: string | null; requirementsVersion: number; createdByEmail: string; createdByName: string;
  createdAt: string; updatedAt: string; requirements: Requirement[]; applicationCount: number }
type JobPublic = { id: number; organizationName: string; title: string; description: string; department: string;
  location: string; jobType: string; experienceLevel: string; minimumSalary: number | null; maximumSalary: number | null;
  vacancies: number; deadline: string | null; publishedAt: string | null; requirements: Requirement[] }
```

### 5.2 Public and account endpoints

| Method | Path | Body → Data |
|---|---|---|
| POST | /api/v1/auth/register | `{firstName,lastName,email,password}` → `AuthResponse` (201) + cookie; sends VERIFY_EMAIL |
| POST | /api/v1/auth/login | `{email,password}` → `AuthResponse` + cookie |
| POST | /api/v1/auth/refresh | cookie → `AuthResponse` (+ rotated cookie) |
| POST | /api/v1/auth/logout | cookie → `null`; cookie cleared |
| POST | /api/v1/auth/verify-email | `{token}` → `AuthUser` |
| POST | /api/v1/auth/resend-verification | (authenticated) → `null` |
| POST | /api/v1/auth/forgot-password | `{email}` → `null` (always 200) |
| POST | /api/v1/auth/reset-password | `{token,newPassword}` → `null` |
| GET | /api/v1/auth/invitations/{token} | → `{organizationName,email,role,expiresAt}` |
| POST | /api/v1/auth/accept-invite | `{token,firstName,lastName,password}` → `AuthResponse` + cookie |
| POST | /api/v1/organizations/signup | `{organizationName,firstName,lastName,email,password}` → `AuthResponse` (role ADMIN) + cookie |
| GET | /api/v1/me | → `AuthUser` |
| POST | /api/v1/me/password | `{currentPassword,newPassword}` → `null` (revokes other sessions) |
| GET | /api/v1/me/notifications?page&size | → `Page<{id,template,subject,body,createdAt,readAt}>` |
| GET | /api/v1/me/notifications/unread-count | → `{count}` |
| PATCH | /api/v1/me/notifications/{id}/read | → `null` |
| POST | /api/v1/me/notifications/read-all | → `null` |

Password policy: 10–72 characters, at least one letter and one digit; email normalised to lower case.

### 5.3 Jobs and requirements

| Method | Path | Who | Body → Data |
|---|---|---|---|
| POST | /api/v1/jobs | ADMIN, HR_MANAGER | job fields (as today) → `JobStaff` (DRAFT) |
| GET | /api/v1/jobs?page&size&status&department&location&jobType&search&sort&direction | staff → `Page<JobStaff>` (own organisation); CANDIDATE → `Page<JobPublic>` (PUBLISHED, deadline open, all organisations) |
| GET | /api/v1/jobs/{id} | staff → `JobStaff`; candidate → `JobPublic` (404 unless open) |
| PUT | /api/v1/jobs/{id} | ADMIN, HR_MANAGER | job fields → `JobStaff` (DRAFT, PUBLISHED, ON_HOLD) |
| PATCH | /api/v1/jobs/{id}/status | ADMIN, HR_MANAGER | `{status}` → `JobStaff` |
| DELETE | /api/v1/jobs/{id} | ADMIN, HR_MANAGER | DRAFT only |
| POST | /api/v1/jobs/{id}/requirements | ADMIN, HR_MANAGER | `RequirementInput` → `Requirement` |
| PUT | /api/v1/jobs/{id}/requirements/{rid} | ADMIN, HR_MANAGER | `RequirementInput` → `Requirement` |
| DELETE | /api/v1/jobs/{id}/requirements/{rid} | ADMIN, HR_MANAGER | → `null` |
| PUT | /api/v1/jobs/{id}/requirements | ADMIN, HR_MANAGER | `{requirements: RequirementInput[]}` (replace all) → `JobStaff` |
| POST | /api/v1/jobs/compile-requirements | ADMIN, HR_MANAGER | `{title,description,experienceLevel?}` → `CompileResult` |

```ts
type CompileResult = { engine: 'rules' | 'llm';
  requirements: (RequirementInput & { rationale: string })[];
  warnings: { phrase: string; kind: 'AGE_CODED'|'GENDER_CODED'|'DEGREE_INFLATION'|'EXCLUSIONARY'|'VAGUE'|'EXCESSIVE_EXPERIENCE'; suggestion: string }[] }
```

Requirement edits on PUBLISHED/ON_HOLD jobs increment `requirementsVersion`, write `JOB_REQUIREMENTS_CHANGED` and enqueue re-matching.

### 5.4 HR applicant review

```ts
type ApplicantSummary = { applicationId: number; displayName: string; identityRevealed: boolean;
  applicationStatus: ApplicationStatus; appliedAt: string; statusChangedAt: string | null;
  analysisStatus: string | null; matchStatus: 'READY'|'PENDING'|'NO_REQUIREMENTS'|'ANALYSIS_UNAVAILABLE'|'FAILED';
  verifiedCoverage: number | null; claimedCoverage: number | null; knockoutsVerified: boolean | null;
  unverifiedKnockouts: number[]; rankGroup: 'RANKED'|'MUST_HAVES_UNVERIFIED'|'UNSCORED'; rank: number | null;
  flags: string[]; workSampleStatus: string | null; interviewCount: number }
type RequirementEvidence = { requirementId: number; type: RequirementType; description: string; mandatory: boolean;
  knockout: boolean; minimumYears: number | null; alternatives: string[];
  status: 'MATCHED'|'UNCLEAR'|'NOT_OBSERVED'; tier: EvidenceTier | null; evidence: string | null;
  sourceSection: string | null; explanation: string; verificationHint: string | null }
type CandidateClaim = { id: number; claimType: string; value: string; evidenceUrl: string | null; tier: EvidenceTier;
  verifiedAt: string | null; verificationNote: string | null; createdAt: string }
type DecisionEvent = { id: number; applicationId: number; previousStatus: ApplicationStatus; newStatus: ApplicationStatus;
  actionType: string; actorUserId: number; actorName: string; timestamp: string; comment: string | null; matchRunId: number | null }
type ApplicantDetail = ApplicantSummary & { requirements: RequirementEvidence[]; matchRunId: number | null;
  algorithmVersion: string | null; calculatedAt: string | null; claims: CandidateClaim[];
  candidate: { fullName: string; email: string; city: string|null; country: string|null; linkedinUrl: string|null;
    portfolioUrl: string|null; yearsOfExperience: number|null; currentCompany: string|null; currentDesignation: string|null } | null;
  coverLetter: string | null; resumeAvailable: boolean; resumeFilename: string | null;
  workSamples: WorkSampleAssignment[]; interviews: Interview[]; history: DecisionEvent[]; allowedActions: DecisionAction[] }
```

`displayName` is `"Candidate #A-<applicationId>"` until revealed, then the full name. `candidate`, `coverLetter` and resume access are null/false until revealed. Ranking (`sort=rank`, default): group RANKED (all knockouts verified) by `verifiedCoverage` desc, `claimedCoverage` desc, `appliedAt` asc; then MUST_HAVES_UNVERIFIED in the same order; then UNSCORED by `appliedAt`. `rank` = 1-based position over the whole filtered list, set only in RANKED. Ranking is computed on the server across all pages.

| Method | Path | Who | Body → Data |
|---|---|---|---|
| GET | /api/v1/hr/jobs/{jobId}/applicants?page&size&status&sort=rank\|appliedAt | ADMIN, HR_MANAGER, RECRUITER | → `Page<ApplicantSummary>` (size ≤ 100) |
| GET | /api/v1/hr/jobs/{jobId}/applicants/{applicationId} | same | → `ApplicantDetail` |
| POST | …/{applicationId}/decisions | ADMIN, HR_MANAGER | `{action: DecisionAction, comment?}` → `DecisionEvent` |
| POST | …/{applicationId}/shortlist, …/reject | ADMIN, HR_MANAGER | legacy aliases of decisions |
| PUT | …/{applicationId}/review-comment | ADMIN, HR_MANAGER, RECRUITER | `{comment}` → `DecisionEvent` |
| GET | …/{applicationId}/history | staff | → `DecisionEvent[]` |
| POST | …/{applicationId}/reveal | ADMIN, HR_MANAGER | → `ApplicantDetail` (status ≠ SUBMITTED, or blind review off) |
| GET | …/{applicationId}/resume | ADMIN, HR_MANAGER, RECRUITER | file (revealed only; audited) |
| PATCH | …/{applicationId}/analysis/retry | ADMIN, HR_MANAGER | → `null` |
| POST | …/{applicationId}/claims/{claimId}/verification | ADMIN, HR_MANAGER, RECRUITER | `{verified: boolean, note?}` → `CandidateClaim` |
| POST | /api/v1/hr/jobs/{jobId}/rematch | ADMIN, HR_MANAGER | → `{enqueued: number}` |
| GET | /api/v1/hr/jobs/{jobId}/audit-export | ADMIN, HR_MANAGER | `text/csv` |
| GET | /api/v1/hr/jobs/{jobId}/fairness | ADMIN, HR_MANAGER | → `FairnessReport` |
| GET | /api/v1/hr/interviewers | staff | → `{userId,name,email,role}[]` |

### 5.5 Candidate

| Method | Path | Body → Data |
|---|---|---|
| GET/PUT | /api/v1/candidates/me | profile without `dateOfBirth`/`gender` |
| POST/GET | /api/v1/candidates/me/resumes | as today |
| GET | /api/v1/candidates/me/resumes/{id}/download, /analysis | as today; facts gain `tier`, `claimedValue` |
| PATCH | /api/v1/candidates/me/resumes/{id}/analysis/facts/{factId} | `{value}` → fact; a changed value creates or updates a SELF_CLAIMED claim linked to the fact; the extracted value is never overwritten |
| GET/POST | /api/v1/candidates/me/claims | `{claimType,value,evidenceUrl?}` → `CandidateClaim` |
| DELETE | /api/v1/candidates/me/claims/{id} | → `null` |
| POST | /api/v1/jobs/{jobId}/applications | `{resumeId, coverLetter?}` → `Application` |
| GET | /api/v1/candidates/me/applications?page&size | → `Page<Application>` |
| GET | /api/v1/candidates/me/applications/{id} | → `ApplicationDetail` |
| PATCH | /api/v1/candidates/me/applications/{id}/withdraw | → `Application` (SUBMITTED, SHORTLISTED, INTERVIEWING) |
| GET | /api/v1/candidates/me/work-samples | → `CandidateWorkSample[]` |
| POST | /api/v1/candidates/me/work-samples/{assignmentId}/start | → `CandidateWorkSample` (instructions revealed, timer starts) |
| POST | /api/v1/candidates/me/work-samples/{assignmentId}/submit | `{responseText, responseUrl?}` → `CandidateWorkSample` |
| GET | /api/v1/candidates/me/interviews | → `{id,jobTitle,scheduledAt,durationMinutes,location,status}[]` |
| GET/PUT/DELETE | /api/v1/candidates/me/equal-opportunity | `{gender?,ageBand?,disability?,socialCategory?,consent:true}` |
| GET | /api/v1/candidates/me/export | JSON attachment with every record about the candidate |
| DELETE | /api/v1/candidates/me | `{password}` → anonymises the account, deletes files, claims, facts and survey; keeps anonymised applications and decisions for audit |

```ts
type Application = { id: number; jobId: number; jobTitle: string; organizationName: string; resumeId: number;
  resumeFilename: string; status: ApplicationStatus; coverLetter: string | null; appliedAt: string; updatedAt: string;
  statusChangedAt: string | null }
type ApplicationDetail = Application & { timeline: { status: ApplicationStatus; at: string }[];
  requirements: { requirementId: number; type: RequirementType; description: string; mandatory: boolean;
    verification: 'VERIFIED' | 'CLAIMED' | 'NOT_VERIFIED' }[] | null;   // null when the organisation does not share it
  workSamples: CandidateWorkSample[]; interviews: { id: number; scheduledAt: string; durationMinutes: number; location: string|null; status: string }[] }
```

Survey values: gender `WOMAN|MAN|NON_BINARY|SELF_DESCRIBE|PREFER_NOT`; ageBand `UNDER_25|25_34|35_44|45_54|55_PLUS|PREFER_NOT`; disability `YES|NO|PREFER_NOT`; socialCategory `GENERAL|OBC|SC|ST|EWS|OTHER|PREFER_NOT`.

### 5.6 Work samples and interviews

```ts
type RubricCriterion = { requirementId: number | null; criterion: string; anchors: { '1': string; '2': string; '3': string; '4': string } }
type WorkSample = { id: number; jobId: number; title: string; instructions: string; timeLimitMinutes: number;
  rubric: RubricCriterion[]; status: 'DRAFT'|'ACTIVE'|'ARCHIVED'; createdAt: string; updatedAt: string }
type WorkSampleAssignment = { id: number; workSampleId: number; workSampleTitle: string; applicationId: number;
  status: 'ASSIGNED'|'STARTED'|'SUBMITTED'|'SCORED'|'EXPIRED'|'CANCELLED'; assignedAt: string; dueAt: string | null;
  startedAt: string | null; submittedAt: string | null; responseText: string | null; responseUrl: string | null;
  scores: { requirementId: number | null; criterion: string; score: number; note: string | null }[];
  totalScore: number | null; scoredAt: string | null; scoredByName: string | null }
type CandidateWorkSample = { assignmentId: number; jobTitle: string; title: string; instructions: string | null;
  timeLimitMinutes: number; status: string; dueAt: string | null; startedAt: string | null; submittedAt: string | null;
  deadlineAt: string | null }
type InterviewQuestion = { id: string; requirementId: number | null; question: string; followUps: string[];
  anchors: { '1': string; '2': string; '3': string; '4': string } }
type InterviewKit = { jobId: number; questions: InterviewQuestion[]; generatedBy: string; updatedAt: string }
type Scorecard = { ratings: { questionId: string; requirementId: number | null; rating: 1|2|3|4; notes: string | null }[];
  recommendation: 'STRONG_NO'|'NO'|'YES'|'STRONG_YES'; summary: string | null; submittedAt: string }
type Interview = { id: number; applicationId: number; jobId: number; jobTitle: string; candidateName: string;
  interviewerUserId: number; interviewerName: string; scheduledAt: string; durationMinutes: number;
  location: string | null; status: 'SCHEDULED'|'COMPLETED'|'CANCELLED'; scorecard: Scorecard | null }
type InterviewDetail = Interview & { kit: InterviewQuestion[]; focusRequirements: RequirementEvidence[] }
```

| Method | Path | Who | Body → Data |
|---|---|---|---|
| POST | /api/v1/hr/jobs/{jobId}/work-samples/generate | ADMIN, HR_MANAGER | → `WorkSample` (DRAFT, from AI service) |
| GET | /api/v1/hr/jobs/{jobId}/work-samples | staff | → `WorkSample[]` |
| PUT | /api/v1/hr/jobs/{jobId}/work-samples/{id} | ADMIN, HR_MANAGER | `{title,instructions,timeLimitMinutes,rubric}` → `WorkSample` |
| PATCH | /api/v1/hr/jobs/{jobId}/work-samples/{id}/status | ADMIN, HR_MANAGER | `{status}` → `WorkSample` |
| POST | /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/work-samples | ADMIN, HR_MANAGER | `{workSampleId, dueAt?}` → `WorkSampleAssignment` (application SHORTLISTED or INTERVIEWING; sample ACTIVE) |
| GET | /api/v1/hr/jobs/{jobId}/work-sample-assignments?status | staff | → `WorkSampleAssignment[]` |
| POST | /api/v1/hr/work-sample-assignments/{id}/score | ADMIN, HR_MANAGER, RECRUITER | `{scores:[{requirementId,criterion,score,note?}]}` → `WorkSampleAssignment` (SUBMITTED only) |
| POST | /api/v1/hr/jobs/{jobId}/interview-kit/generate | ADMIN, HR_MANAGER | → `InterviewKit` |
| GET/PUT | /api/v1/hr/jobs/{jobId}/interview-kit | staff / ADMIN, HR_MANAGER | `{questions}` → `InterviewKit` |
| POST | /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews | ADMIN, HR_MANAGER | `{interviewerUserId,scheduledAt,durationMinutes,location?}` → `Interview` (moves SHORTLISTED → INTERVIEWING) |
| GET | /api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews | staff | → `Interview[]` |
| PATCH | /api/v1/interviews/{id} | ADMIN, HR_MANAGER | `{scheduledAt?,durationMinutes?,location?,status?:'CANCELLED'}` → `Interview` |
| GET | /api/v1/interviews/mine | any staff | → `Interview[]` (assigned to me) |
| GET | /api/v1/interviews/{id} | assigned interviewer, ADMIN, HR_MANAGER | → `InterviewDetail` |
| POST | /api/v1/interviews/{id}/scorecard | assigned interviewer | `Scorecard` minus `submittedAt` → `Interview` (COMPLETED) |

Evidence: a rubric criterion linked to a requirement and scored ≥ 3 makes that requirement DEMONSTRATED; an interview rating ≥ 3 on a requirement-linked question makes it INTERVIEW_VERIFIED. Both enqueue re-matching. Instructions are hidden from the candidate until they press Start; submissions after `startedAt + timeLimit + 5 min` grace are rejected.

### 5.7 Fairness and administration

```ts
type FairnessReport = { scope: 'JOB' | 'ORGANIZATION'; jobId: number | null; minimumGroupSize: 5; surveyResponseRate: number;
  dimensions: { dimension: 'gender'|'ageBand'|'disability'|'socialCategory';
    stages: { stage: 'APPLIED'|'SHORTLISTED'|'INTERVIEWING'|'SELECTED';
      groups: { group: string; count: number | null; selectionRate: number | null }[];
      adverseImpactRatio: number | null; flagged: boolean }[] }[]; note: string }
```

Selection rate of group g at stage S = applicants in g who ever reached S ÷ applicants in g. Groups with fewer than 5 applicants are suppressed (count and rate null). Adverse-impact ratio = lowest ÷ highest rate among unsuppressed groups; `flagged` when below 0.8. "PREFER_NOT" and non-respondents are excluded.

| Method | Path | Body → Data |
|---|---|---|
| GET/PATCH | /api/v1/admin/organization | `{name?,blindReview?,shareVerificationStatus?}` → `{id,name,slug,blindReview,shareVerificationStatus}` |
| GET | /api/v1/admin/dashboard | metrics (organisation-scoped) + `health {backend,database,aiService,queue:{pending,failed,dead}}` |
| GET | /api/v1/admin/users?page&size&search&role&enabled | organisation staff |
| PATCH | /api/v1/admin/users/{id}/status, /role | as today; roles limited to staff roles |
| GET/POST | /api/v1/admin/invitations | `{email,role}` → `{id,email,role,expiresAt,acceptedAt,revokedAt,createdAt,invitedByName,inviteUrl?}` (`inviteUrl` only when `app.invitations.expose-link=true`, dev) |
| DELETE | /api/v1/admin/invitations/{id} | revoke |
| GET | /api/v1/admin/hr-team, /admin/jobs | as today, organisation-scoped |
| PATCH | /api/v1/admin/jobs/{id}/status | `{status,reason}`; allowed PUBLISHED→ON_HOLD/CLOSED, ON_HOLD→CLOSED, DRAFT→CANCELLED |
| GET | /api/v1/admin/audit-logs?page&size&action | → `Page<{id,action,actorEmail,actorUserId,targetType,targetId,details,ipAddress,createdAt}>` |
| GET | /api/v1/admin/audit-export?from&to | `text/csv` |
| GET | /api/v1/admin/fairness | → `FairnessReport` (organisation) |

---

## 6. AI service v2 (internal contract and design)

All `/api/*` routes require `X-Internal-Service-Token`; an ASGI middleware checks it **before** the body is read (S3). `GET /health` is public: `{status, version, llmProvider, ocrAvailable}`. CPU-bound parsing runs in a process pool, never on the event loop. Dependencies: FastAPI ≥ 0.115 with Starlette ≥ 0.40, python-multipart ≥ 0.0.18 (CVE fixes), pdfplumber, python-docx, olefile; optional `rapidocr-onnxruntime` for OCR.

### 6.1 Endpoints

```ts
// POST /api/v1/resume/parse  (multipart "file")
type ParsedFact = { type: 'SKILL'|'EDUCATION'|'EXPERIENCE'|'CERTIFICATION'|'EMAIL'|'PHONE'|'LINK'; value: string;
  normalizedValue: string; evidence: string; sourceSection: string; sourceReference: string | null;
  spanStart: number | null; spanEnd: number | null;               // offsets in the normalised document text
  attributes: { skillId?: string; domain?: string; title?: string; employer?: string; startDate?: string; endDate?: string;
    current?: boolean; months?: number; degreeLevel?: 'DIPLOMA'|'BACHELOR'|'MASTER'|'DOCTORATE'; field?: string; url?: string } }
type Timeline = { totalMonths: number; roles: { title: string|null; employer: string|null; startDate: string|null;
  endDate: string|null; current: boolean; months: number|null; skills: string[] }[]; skillMonths: Record<string, number> }
type ParseResponse = { status: 'COMPLETED'|'PARTIAL'|'NEEDS_OCR'|'FAILED'; errorCode: string | null; parserVersion: string;
  facts: ParsedFact[]; flags: { code: 'HIDDEN_TEXT'|'PROMPT_INJECTION_SUSPECTED'|'TWO_COLUMN_LAYOUT'|'OCR_USED'|'LEGACY_DOC'|'LOW_TEXT_DENSITY'; detail: string }[];
  timeline: Timeline | null }

// POST /api/v1/match   and   POST /api/v1/match/batch {requirements, candidates:[{applicationId, facts, timeline}]}
type MatchRequirement = { id: number; type: RequirementType; description: string; mandatory: boolean; knockout: boolean;
  minimumYears: number | null; alternatives: string[]; proficiency: string | null }
type MatchFact = { id: string;            // origin-tagged: "fact:12", "claim:3", "ws:4:r9", "iv:7:r9"
  type: string; value: string; evidence: string; sourceSection: string; tier: EvidenceTier; attributes?: object }
type MatchRequest = { requirements: MatchRequirement[]; facts: MatchFact[]; timeline: Timeline | null }
type RequirementResult = { requirementId: number; status: 'MATCHED'|'UNCLEAR'|'NOT_OBSERVED'; tier: EvidenceTier | null;
  evidence: string | null; factIds: string[]; sourceSection: string | null;
  provenance: 'RESUME'|'CANDIDATE_CLAIM'|'CORROBORATED'|'WORK_SAMPLE'|'INTERVIEW' | null;
  explanation: string; verificationHint: string | null }
type MatchResponse = { algorithmVersion: 'evidence-ladder-3'; scoreLabel: string; verifiedCoverage: number | null;
  claimedCoverage: number | null; knockoutsVerified: boolean; unverifiedKnockouts: number[];
  score: number | null;  // = verifiedCoverage, kept for compatibility
  requirements: RequirementResult[] }

// POST /api/v1/requirements/compile   {title, description, experienceLevel?} -> CompileResult (Section 5.3)
// POST /api/v1/work-samples/generate  {jobTitle, jobDescription?, requirements: MatchRequirement[]}
//      -> { title, instructions, timeLimitMinutes, rubric: RubricCriterion[], engine }
// POST /api/v1/interview-kits/generate {jobTitle, requirements: MatchRequirement[], focusRequirementIds?: number[]}
//      -> { questions: InterviewQuestion[], engine }
```

### 6.2 Pipeline (offline baseline; LLM optional, D9)

1. **Ingest.** PDF: pdfplumber words with positions; two-column detection by x-gap clustering → read left column then right; per-character colour/size checks flag white (≥ 0.95 luminance) or < 4 pt text and off-page text as `HIDDEN_TEXT` and exclude it. DOCX: paragraphs, tables, headers; runs with `w:vanish`, white colour or < 4 pt flagged and excluded. DOC: `olefile` WordDocument/text-stream extraction (best effort, `LEGACY_DOC` flag) and LibreOffice conversion when `soffice` exists. OCR via `rapidocr-onnxruntime` when installed and `OCR_ENABLED=true`, else `NEEDS_OCR`. Limits as today.
2. **Injection screen.** Regex families ("ignore (all|previous) instructions", "you are an? (AI|assistant|recruiter)", "rank (this|me) (first|highest)", "system prompt", hidden text containing job-description keywords) → `PROMPT_INJECTION_SUSPECTED`. Resume text is only ever data.
3. **De-identify** (before any LLM call and for all evidence returned): names from the header block, emails, phones, URLs (kept as LINK facts only), street addresses, ages/dates of birth, gendered honorifics and pronouns replaced with neutral placeholders.
4. **Sections.** Large heading lexicon (skills, technical skills, tools, experience, work history, employment, internships, projects, education, academics, certifications, licences, courses, achievements, awards, publications, summary, profile, objective, languages, interests, hobbies, volunteering, references); unknown headings start an `OTHER` section (never inherit, H4).
5. **Skills.** `taxonomy/skills.json`: ~600 skills across software, data, cloud, design, product, sales, marketing, finance/accounting (incl. Tally, GST, Ind AS), HR, operations/supply chain, healthcare, education, legal, languages; fields `id, name, aliases[], domain, parents[], distinctions[], ambiguous (bool), contextWords[]`. Ambiguous aliases (Go, C, R, Spring, React, Swift, Oracle, Excel?, ML, DL, CV removed) count only inside a skills/tools section list, or when a context word is within a 6-token window (H3).
6. **Dates and timeline.** Parses `Jan 2019 – Mar 2021`, `01/2019 - 03/2021`, `2019 – 2021`, `2019 – Present/Current/Now/Till date`, `Jan '19`, en/em dashes and "to"; "present" = today (no hard-coded year, H5). Roles are segmented in experience sections (title/employer line + date line + bullets); skills mentioned in a role block accrue that role's months; intervals are merged so overlaps are not double counted.
7. **Education.** Degree level map (B.Tech/BE/BSc/BCA/BCom/BA/BBA → BACHELOR; M.Tech/ME/MS/MSc/MCA/MBA/MA/MCom → MASTER; PhD/DPhil → DOCTORATE; Diploma/Polytechnic → DIPLOMA) and field families (computer science ≈ CSE ≈ computer engineering ≈ IT ≈ software engineering).
8. **Matching v3** (`evidence-ladder-3`). Per requirement: alternatives (explicit list, or "X or Y" / "X/Y" detected in the description) are satisfied by any one alternative (H2); skill hierarchy (child satisfies parent); EXPERIENCE uses `timeline.skillMonths` or `totalMonths` against `minimumYears × 12`; EDUCATION compares levels and fields; CERTIFICATION uses normalised token overlap ≥ 0.8; OTHER is "needs human review", status NOT_OBSERVED and **excluded from the coverage denominator**. The best tier among matching facts is reported. Weights: must-have 2, nice-to-have 1. `verifiedCoverage` counts MATCHED with tier ≥ RESUME_STATED; `claimedCoverage` counts MATCHED at any tier; `knockoutsVerified` = every knockout MATCHED at tier ≥ RESUME_STATED. Lexical fallback only compares facts of the requirement's own type and cites the fact that actually contains the terms (L12). `verificationHint` suggests what to probe for every requirement not verified.
9. **Compiler.** Splits bullets/sentences, detects must vs nice ("must", "required", "should have" vs "nice to have", "preferred", "bonus"), years ("3+ years"), alternatives, requirement type; warnings for age-coded ("young", "digital native", "recent graduate", "under 30"), gender-coded ("rockstar", "ninja", "he will"), degree inflation (degree required where the role lists no degree-dependent duties is out of scope: flag every hard degree requirement as "consider skills-based alternative"), excessive experience (> 10 years for MID or below), vague ("good communication").
10. **Generators.** Work sample: one task per 2–3 must-have skills, 15–45 minutes, rubric with four behaviourally anchored levels per criterion. Interview kit: 1–2 questions per requirement (behavioural + technical), follow-ups, four anchors; unverified must-haves first when `focusRequirementIds` is given.
11. **LLM provider** (`LLM_PROVIDER=none|anthropic|openai_compatible`, `LLM_MODEL` required when enabled, `ANTHROPIC_API_KEY` or `OPENAI_BASE_URL` + `OPENAI_API_KEY`). JSON-schema prompts; every extracted item must carry a `quote` found verbatim in the de-identified text or it is dropped; any error/time-out falls back to the rules result. Only de-identified text is sent.

### 6.3 Evaluation harness (`ai-service/eval/`)

A seeded synthetic gold set (≥ 200 resumes generated from templates across 8 domains, with known skills, dated roles, education; ≥ 20 jobs with requirement labels; name/gender counterfactual pairs). `python -m eval.run` prints skill-extraction precision/recall/F1, date accuracy, requirement agreement (Cohen's kappa over MATCHED/UNCLEAR/NOT_OBSERVED), ranking NDCG@10, name-swap flip rate and adverse-impact ratio on synthetic pools; `--check` exits 1 below thresholds (skill F1 ≥ 0.85, kappa ≥ 0.70, NDCG@10 ≥ 0.80, flip rate = 0, AIR ≥ 0.80). The README states plainly that synthetic data overstates real accuracy and that a recruiter-labelled real set must replace it.

---

## 7. Frontends

Shared conventions for both apps: `react-router-dom` 6 routes, `@tanstack/react-query` 5 for server state, a typed client in `src/api/` (envelope unwrapping, access token kept in memory only, single-flight refresh via the cookie with `credentials: 'include'`, one retry on 401, logout on refresh failure — fixes M6 and S2 on the client), no tokens in local/session storage, times shown with `Intl` in local time and sent with `toISOString()`, polling only while something is actually pending and the queue reports it (L8), components split by page, the existing visual language kept.

**Candidate portal (WP-FE-CAND).** Routes: `/login`, `/register`, `/forgot-password`, `/reset-password`, `/verify-email`; `/jobs` (filters always mounted, 300 ms debounce, results-only loading state — M4), `/jobs/:id` with an apply dialog (resume picker + cover letter — M5); `/applications`, `/applications/:id` (status timeline, requirement verification list with "add evidence", work samples, interviews, withdraw with confirmation); `/resumes` (upload, analysis, facts; "suggest a correction" creates a claim labelled *unverified until a recruiter checks it* — C1; flags such as hidden text explained); `/evidence` (claims with links and tier badges); `/work-samples` (start → timed response page → submit); `/interviews`; `/notifications` (+ unread badge); `/profile` (no date of birth or gender); `/privacy` (voluntary survey with plain-language purpose and consent, data export, account deletion with password).

**HR portal (WP-FE-HR).** Routes: `/login`, `/signup` (organisation), `/accept-invite`, `/forgot-password`, `/reset-password`; `/jobs`, `/jobs/new`, `/jobs/:id/edit` (job form + "Draft requirements from description" → suggestions and wording warnings → accept; requirement rows with alternatives chips, must-have/knockout toggles, minimum years, proficiency); `/jobs/:id` tabs **Applicants** (server-ranked, grouped Ranked / Must-haves not yet verified / Unscored, verified vs claimed coverage bars, flags, status filter — C2), **Work samples**, **Interview kit**, **Fairness**, plus "Export audit CSV"; `/jobs/:id/applicants/:applicationId` (blind header until reveal, allowed decision buttons with comment dialog incl. undo/reopen, evidence-ladder table with tier badges and verification hints, claims with verify/reject, revealed candidate details, resume download, cover letter, assign/score work samples with the rubric, schedule interviews and read scorecards, decision history — C3); `/interviews` and `/interviews/:id` (kit, focus requirements, scorecard form) for every staff role; admin `/admin` (dashboard with real health and queue), `/admin/users`, `/admin/invitations`, `/admin/organization` (blind review, share verification status), `/admin/jobs` (only allowed override targets), `/admin/audit` (filter + CSV export), `/admin/fairness`. Navigation by role: ADMIN everything; HR_MANAGER jobs + interviews; RECRUITER jobs (read/comment) + interviews; INTERVIEWER interviews only. Stale responses are ignored by query keys (L7).

---

## 8. Delivery and operations

- `docker-compose.yml`: `postgres` (pgvector/pgvector:pg16), `mailpit` (SMTP 1025, UI 8025), `ai-service`, `backend` (profile `dev`, `APP_SEED_DEMO=true`), `candidate-portal` (5173), `hr-portal` (5174). One command: `docker compose up --build`.
- Dockerfiles: backend multi-stage (Maven + Temurin 21 → JRE 21), ai-service `python:3.11-slim`, frontends Node 22 build → nginx with SPA fallback.
- `.github/workflows/ci.yml`: backend `./mvnw -B verify` (JDK 21); ai-service pytest + `eval.run --check`; `tools/check_migrations_pg.py`; `tools/java_static_check.py`; both frontends build.
- `.env.example` files updated; README rewritten around Docker Compose and the manual alternative; the missing-launcher references removed (L11).

---

## 9. Work packages

| WP | Scope | Findings closed | Depends on | Gates |
|---|---|---|---|---|
| WP-PY | Section 6: ingest, injection screen, de-identification, sections, taxonomy, timeline, education, matching v3, compiler, generators, LLM provider, middleware auth, process pool, dependency upgrades, eval harness, tests | H1, H2 (engine), H3, H4, H5, M8 (parsing), M14 (engine), S3, L12 | — | G-PY |
| WP-BE-A | D1, D2, D3, D11, D13, D15 and Section 4: pom, PostgreSQL migrations V1–V14, UTC, errors, current user, tokens/cookies/rotation, rate limits, lockout, verification, reset, organisations, invitations, signup, bootstrap, seed, audit, background jobs + PARSE_RESUME, notifications, health, admin fixes, security config, provisioning removal, Docker, CI, README; updated tests | S1, S2 (server), S4, S5, M1, M2, M7, M10, M11, M12, H7, L3, L4, L5, L6, L9, L11, L13 | — | G-SQL, G-JAVA-STATIC, G-JAVA-REVIEW |
| WP-BE-B | V15–V17; requirements v2 + compile proxy + bulk replace + edit after publish; candidate job filter + JobPublic + auto-close; application lifecycle, decisions, reveal, staff resume download, ApplicantDetail, server ranking; claims (candidate + HR verification); fact review → claim; MATCH_APPLICATION handler, AI client v3 DTOs, append-only runs, triggers, `EvidenceContributor` extension point; notifications on submit/status change; candidate ApplicationDetail; tests | C1, C2, C3 (API), H2 (data), H6, H8, M3, M8 (grouping), M9, M13, M14 (data), L1, L2, L10 | WP-BE-A | G-SQL, G-JAVA-STATIC, G-JAVA-REVIEW |
| WP-BE-C | V18–V20; work samples (HR + candidate), interview kits, interviews, scorecards, interviewer endpoints, EvidenceContributors for tiers 3–4, fairness survey + reports, job and organisation audit export, candidate export/delete, profile field removal; tests | M15; D14; Phase-3 features | WP-BE-B | G-SQL, G-JAVA-STATIC, G-JAVA-REVIEW |
| WP-FE-CAND | Section 7 candidate portal | M4, M5, M6, S2 (client), C1 (UI), L8 | contracts in Section 5 | G-FE |
| WP-FE-HR | Section 7 HR portal | C2, C3 (UI), L7, L8 | contracts in Section 5 | G-FE |
| WP-INT | contract reconciliation across all packages, docs, audit tracker update | — | all | all gates |

---

## 10. Deferred, with reasons

| Item | Why deferred | Next step |
|---|---|---|
| Spring Boot 4 / Jackson 3 | Large breaking upgrade; cannot compile here | Separate PR after Boot 3.5 is green in CI |
| PostgreSQL row-level security | Service-layer scoping first; RLS needs per-request `SET` plumbing | Add once tenancy tests are green |
| pgvector semantic matching | Needs an embedding model download, unavailable here | Embedding provider behind the AI-service interface |
| ESCO import | Bulk ESCO download unreachable from this environment | `ai-service/taxonomy/import_esco.py` with the official CSV |
| SSO (Google, Microsoft) and MFA | Need OAuth client credentials and real IdP testing | Spring Security OAuth2 login + TOTP for staff |
| Calendar integration, HRMS and job-board connectors | Need partner credentials | Interview emails carry time and location today |
| WhatsApp notifications | Needs a provider account (business API) | `NotificationChannel` interface is ready |
| Outcome data (90-day performance) | Too little data for 12+ months | Capture fields when the first pilot hires |
| Real labelled gold set | Needs recruiters' labels on consented resumes | Replace synthetic set before quoting accuracy |
