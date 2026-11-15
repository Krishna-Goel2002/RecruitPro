# RecruitPro — Guide-to-Branch Verification Audit Report

**Audit Target Repository:** `C:\Projects\RecruitPro-Kshitij-Audit`  
**Expected Branch:** `Kshitij`  
**Baseline Branch:** `master` (`b488a65`)  
**Audit Document Evaluated:** `docs/guides/RUN_AND_TEST_GUIDE.md`  
**Audit Execution Date:** 2026-11-14  

> **Update 2026-11-15:** The run guide has since been updated by its author for branch `Kshitij` (finding 1 and Sections 1 and 5 describe the guide as it was on 2026-11-14; note that the copy of `docs/guides/RUN_AND_TEST_GUIDE.md` at commit `223f3d8` still shows the old `audit-remediation` instructions, so make sure the updated guide is committed). The backend has now passed `mvn verify` on the teammate's machine: **239 tests, 0 failures, 0 errors, 6 skipped**. The AI service suite gives **176 passed, 1 skipped** (177 collected), and both portals build. Finding 3 (compilation and the missing `mvn verify`) is therefore historical; finding 4 (browser-test harnesses not committed) still stands. The findings below are otherwise kept as written, with technical details (file paths, endpoints, table names, behaviour) corrected against the source code on 2026-11-15.

---

## Executive Summary

This audit compares the claims, instructions, and assertions in `docs/guides/RUN_AND_TEST_GUIDE.md` (authored by Kshitij) against the checked-out source code on branch `Kshitij`. 

Key findings:
1. **Branch Identity Discrepancy:** The guide instructs the author to commit remediation work to a branch named `audit-remediation` and instructs testers to clone and check out `audit-remediation`. However, the repository contains no branch named `audit-remediation`. The work was directly committed to branch `Kshitij` (`363c7d7`) with the identical commit message suggested by the guide.
2. **Feature Completeness:** All 14 evaluated architectural and functional features are **IMPLEMENTED** in the source tree across the database migrations, backend services, AI service, and frontend portals.
3. **Compilation Claim Divergence:** The guide states that *"The Java backend, however, has never been compiled"*. However, inspection of the filesystem revealed `backend/target/classes` and `backend/target/test-classes` containing **485 compiled `.class` files** timestamped `2026-11-14 23:13`. While compilation was executed locally, no packaged JAR file or Surefire test execution reports exist, confirming that compilation occurred but full Maven verification (`mvn verify`) remains uncompleted.
4. **Unverifiable Testing Harnesses:** The guide states that both portals *"passed simulated-browser tests"*. However, project documentation (`docs/plan/WP-FE-CAND-NOTES.md` and `docs/plan/WP-FE-HR-NOTES.md`) confirms that the browser simulation test harnesses (Puppeteer + mock server for candidate portal, and jsdom 25 + IIFE bundles for HR portal) were **never committed to the repository** and ran only in scratch directories. These claims are therefore **UNVERIFIABLE** from the repository source code.

---

## 1. Branch and Commit Verification (Task 1)

| Verification Item | Guide Claim | Actual Repository State | Status |
|---|---|---|---|
| **Active Checked-out Branch** | Mentions `audit-remediation` (Part 1, lines 29, 32, 42) | `Kshitij` (`refs/heads/Kshitij` tracking `origin/Kshitij`) | **DISCREPANCY** |
| **HEAD Commit Hash** | N/A (Instructions to commit pending changes) | `363c7d762ca420c32a64e7ff83fbd559ad8a705f` | **COMMITTED** |
| **HEAD Commit Message** | `"Audit remediation: evidence platform, tenancy, async AI, new portals"` | `"Audit remediation: evidence platform, tenancy, async AI, new portals"` | **EXACT MATCH** |
| **HEAD Commit Author** | N/A | `Kshitij <ksingh@mondee.com>` (Sat Nov 14 22:56:05 2026 +0530) | **CONFIRMED** |
| **Latest `master` Commit (Baseline)** | Master baseline | `b488a65e7c1084767de66ebf2627aeabc2dcf625` (`feat: initialize spring boot backend and python ai service modules` by `Krishna-Goel2002`) | **CONFIRMED** |
| **Remediation Work Committed?** | Guide claims *"the remediation work is not committed yet"* (line 26) | Working tree is completely clean (`nothing to commit, working tree clean`). The remediation work is committed in `363c7d7` (`git show --shortstat 363c7d7`: 616 files changed, 54,949 insertions, 13,025 deletions against its parent `b488a65`). | **COMMITTED** |

### Differences Between Guide and Repository:
1. **Branch Name**: The guide instructs `git checkout -b audit-remediation` and `git checkout audit-remediation`. The remote and local branches are named `Kshitij`.
2. **Commit State**: The guide's Part 1 contains an uncommitted warning intended for the author before sharing. In this repository, the commit has already been finalized and pushed.
3. **Reference to Kshitij**: The name `Kshitij` does not appear anywhere in `RUN_AND_TEST_GUIDE.md`; the guide exclusively refers to `audit-remediation`.

---

## 2. Feature Implementation Matrix (Task 2)

Each of the 14 specified features was audited against database migrations, domain entities, service implementations, controller endpoints, and frontend components.

| # | Feature | Status | Source File Paths & Evidence |
|---|---|---|---|
| 1 | **PostgreSQL integration** | **IMPLEMENTED** | - `backend/pom.xml` (lines 78–83: `org.postgresql:postgresql`, line 92: `flyway-database-postgresql`)<br>- `backend/src/main/resources/application-dev.yml` (lines 11–15: `org.postgresql.Driver`, JDBC URL `jdbc:postgresql://localhost:5432/recruitpro`, Hikari pool, `ddl-auto: validate`)<br>- `docker-compose.yml` (lines 11–25: service `postgres`, image `pgvector/pgvector:pg16`)<br>- `tools/check_migrations_pg.py` (PostgreSQL 16 validation) |
| 2 | **Twenty Flyway migrations** | **IMPLEMENTED** | Exactly 20 SQL migration scripts in `backend/src/main/resources/db/migration/`:<br>1. `V1__init_foundation.sql`<br>2. `V2__create_users_and_authentication_tables.sql`<br>3. `V3__create_refresh_tokens.sql`<br>4. `V4__create_hr_profiles_jobs_and_job_requirements.sql`<br>5. `V5__create_candidate_profiles.sql`<br>6. `V6__create_candidate_resumes.sql`<br>7. `V7__create_job_applications.sql`<br>8. `V8__create_resume_intelligence.sql`<br>9. `V9__persist_job_match_runs.sql`<br>10. `V10__add_hr_application_decision_lifecycle.sql`<br>11. `V11__organizations_and_tenancy.sql`<br>12. `V12__auth_hardening.sql`<br>13. `V13__audit_events.sql`<br>14. `V14__background_jobs_and_notifications.sql`<br>15. `V15__requirements_v2.sql`<br>16. `V16__applications_v2.sql`<br>17. `V17__evidence_ladder.sql`<br>18. `V18__work_samples.sql`<br>19. `V19__interviews.sql`<br>20. `V20__fairness_and_privacy.sql` |
| 3 | **Java 21 configuration** | **IMPLEMENTED** | - `backend/pom.xml` (line 26: `<java.version>21</java.version>`)<br>- `backend/Dockerfile` (line 4: `FROM maven:3.9-eclipse-temurin-21`, line 11: `FROM eclipse-temurin:21-jre`)<br>- `.github/workflows/ci.yml` (lines 23, 70: `java-version: "21"`, distribution `temurin`) |
| 4 | **Docker Compose with six services** | **IMPLEMENTED** | `docker-compose.yml` defines exactly six services:<br>1. `postgres` (`pgvector/pgvector:pg16`, port 5432)<br>2. `mailpit` (`axllent/mailpit:latest`, ports 8025, 1025)<br>3. `ai-service` (`./ai-service`, port 8000)<br>4. `backend` (`./backend`, port 8080)<br>5. `candidate-portal` (`./frontend`, port 5173:80)<br>6. `hr-portal` (`./hr-frontend`, port 5174:80)<br>Volumes: `postgres-data`, `resume-uploads` |
| 5 | **Multi-tenant organisation support** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V11__organizations_and_tenancy.sql` (`organizations` table, `organization_id` FK on `users` and `jobs`)<br>- `backend/src/main/java/com/recruitpro/organization/entity/Organization.java`<br>- `backend/src/main/java/com/recruitpro/organization/service/OrganizationService.java`<br>- `backend/src/main/java/com/recruitpro/organization/controller/OrganizationSignupController.java`<br>- `backend/src/main/java/com/recruitpro/organization/controller/AdminOrganizationController.java`<br>- `backend/src/main/java/com/recruitpro/hr/service/HrJobAccessService.java` (org scoping) |
| 6 | **Email verification and password reset** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V12__auth_hardening.sql` (`user_tokens` table with `VERIFY_EMAIL` and `RESET_PASSWORD`)<br>- `backend/src/main/java/com/recruitpro/auth/entity/UserToken.java`<br>- `backend/src/main/java/com/recruitpro/auth/service/UserTokenService.java`<br>- `backend/src/main/java/com/recruitpro/auth/controller/AuthController.java` (`POST /api/v1/auth/verify-email`, `/api/v1/auth/forgot-password`, `/api/v1/auth/reset-password`)<br>- `frontend/src/App.tsx` & `frontend/src/pages/auth/` (`VerifyEmailPage.tsx`, `ForgotPasswordPage.tsx`, `ResetPasswordPage.tsx`)<br>- `hr-frontend/src/App.tsx`, `hr-frontend/src/pages/auth/PasswordPages.tsx` (exports `ForgotPasswordPage` and `ResetPasswordPage`), `hr-frontend/src/pages/account/ChangePasswordPage.tsx` |
| 7 | **Asynchronous AI processing and retry** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V14__background_jobs_and_notifications.sql` (`background_jobs` table)<br>- `backend/src/main/java/com/recruitpro/jobs/entity/BackgroundJob.java`<br>- `backend/src/main/java/com/recruitpro/jobs/service/BackgroundJobService.java` (job types `PARSE_RESUME`, `MATCH_APPLICATION`; statuses `PENDING`/`RUNNING`/`DONE`/`FAILED`/`DEAD`; conditional-UPDATE claim; backoff `min(15 s x 2^attempts, 30 min)`; 5 attempts; RUNNING jobs locked over 10 min released)<br>- `backend/src/main/java/com/recruitpro/jobs/service/BackgroundJobWorker.java` (polls every 2 s, batch of 5; on DEAD calls the handler's `onDeadLetter`, which for `PARSE_RESUME` marks the analysis FAILED; nobody is notified)<br>- `backend/src/main/java/com/recruitpro/ai/service/ResumeParseJobHandler.java`<br>- `backend/src/main/java/com/recruitpro/evidence/service/MatchApplicationJobHandler.java` |
| 8 | **Evidence-based matching and blind review** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V17__evidence_ladder.sql` (creates `candidate_claims`; adds `evidence_tier`/spans to `resume_analysis_facts`; makes `job_match_runs` (created in V9) append-only with `verified_coverage`, `claimed_coverage`, `knockouts_verified`, `is_current`. There is no `match_evidence` table: per-requirement evidence is stored in `job_match_runs.requirement_results_json`)<br>- `backend/src/main/java/com/recruitpro/evidence/entity/EvidenceTier.java` (`SELF_CLAIMED`, `RESUME_STATED`, `CORROBORATED`, `DEMONSTRATED`, `INTERVIEW_VERIFIED`)<br>- `backend/src/main/java/com/recruitpro/evidence/service/MatchingService.java`<br>- `backend/src/main/java/com/recruitpro/evidence/service/MatchRequestBuilder.java`<br>- `backend/src/main/java/com/recruitpro/hr/service/ApplicantQueryService.java` (blind name `Candidate #A-<applicationId>`, identity hidden until revealed; reveal on shortlist is done in `ApplicationDecisionService.java`)<br>- `ai-service/app/matching/engine.py` (evidence ladder matching engine) |
| 9 | **Work samples and scoring** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V18__work_samples.sql` (`work_samples` with the rubric stored as JSON in `rubric_json`, and `work_sample_assignments`; there is no `rubric_criteria` table. Sample statuses `DRAFT`/`ACTIVE`/`ARCHIVED`; assignment statuses `ASSIGNED`/`STARTED`/`SUBMITTED`/`SCORED`/`EXPIRED`/`CANCELLED`; scores 1–4)<br>- `backend/src/main/java/com/recruitpro/assessment/entity/WorkSample.java`, `WorkSampleAssignment.java`<br>- `backend/src/main/java/com/recruitpro/assessment/service/WorkSampleService.java` (generation via `POST /api/v1/hr/jobs/{jobId}/work-samples/generate`, which calls the AI service), `CandidateWorkSampleService.java` (start until `dueAt`; submit until `startedAt` + time limit + 5 min grace), `WorkSampleExpirySweeper.java`<br>- `backend/src/main/java/com/recruitpro/assessment/service/WorkSampleEvidenceContributor.java` (requirement-linked criteria scored 3–4 become DEMONSTRATED evidence)<br>- `ai-service/app/generate/work_samples.py`<br>- `frontend/src/pages/WorkSamplesPage.tsx`, `frontend/src/pages/WorkSampleResponsePage.tsx`, `hr-frontend/src/pages/jobs/WorkSamplesTab.tsx`, `hr-frontend/src/pages/applicants/WorkSampleSection.tsx` |
| 10 | **Interview scheduling and scorecards** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V19__interviews.sql` (`interview_kits`, `interviews`, `interview_scorecards`)<br>- `backend/src/main/java/com/recruitpro/interview/entity/Interview.java`, `InterviewKit.java`, `InterviewScorecard.java`<br>- `backend/src/main/java/com/recruitpro/interview/service/InterviewService.java` (first interview moves SHORTLISTED → INTERVIEWING; email and in-app notifications, no calendar invites; ratings 1–4; recommendation `STRONG_NO`/`NO`/`YES`/`STRONG_YES`), `InterviewKitService.java` (kit generated via `POST /api/v1/hr/jobs/{jobId}/interview-kit/generate`, which calls the AI service)<br>- `backend/src/main/java/com/recruitpro/interview/service/InterviewEvidenceContributor.java` (requirement-linked questions rated 3–4 become INTERVIEW_VERIFIED evidence)<br>- `ai-service/app/generate/interview_kits.py`<br>- `frontend/src/pages/InterviewsPage.tsx`, `hr-frontend/src/pages/interviews/InterviewDetailPage.tsx` |
| 11 | **Admin invitations and audit exports** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V11__organizations_and_tenancy.sql` (`invitations` table)<br>- `backend/src/main/resources/db/migration/V13__audit_events.sql` (`audit_events` table)<br>- `backend/src/main/java/com/recruitpro/organization/service/InvitationService.java`<br>- `backend/src/main/java/com/recruitpro/organization/controller/AdminOrganizationController.java`<br>- `backend/src/main/java/com/recruitpro/audit/service/AuditService.java` (`exportCsvForCurrentOrganization`, served at `GET /api/v1/admin/audit-export`)<br>- `backend/src/main/java/com/recruitpro/hr/service/JobAuditExportService.java` (`exportCsv`, served at `GET /api/v1/hr/jobs/{jobId}/audit-export`)<br>- `hr-frontend/src/pages/admin/AdminInvitationsPage.tsx`, `AdminAuditPage.tsx` |
| 12 | **Fairness reports** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V20__fairness_and_privacy.sql` (`eeo_responses` table: stored separately from the profile, not encrypted, never shown to reviewers)<br>- `backend/src/main/java/com/recruitpro/fairness/entity/EeoResponse.java`; voluntary survey with consent on the candidate Privacy page (`EqualOpportunityController.java`, `/api/v1/candidates/me/equal-opportunity`)<br>- `backend/src/main/java/com/recruitpro/fairness/service/FairnessReportService.java`<br>- `backend/src/main/java/com/recruitpro/fairness/service/FairnessCalculator.java` (groups with fewer than 5 applicants are suppressed: count and rate returned as null; adverse-impact ratio = lowest / highest rate, flagged below 0.80)<br>- `backend/src/main/java/com/recruitpro/fairness/controller/FairnessReportController.java`<br>- `hr-frontend/src/pages/jobs/FairnessReportView.tsx`, `hr-frontend/src/pages/admin/AdminFairnessPage.tsx` |
| 13 | **Candidate data export and deletion** | **IMPLEMENTED** | - `backend/src/main/java/com/recruitpro/candidate/privacy/CandidatePrivacyController.java` (`GET /api/v1/candidates/me/export`, `DELETE /api/v1/candidates/me` with the password in the body; a wrong password returns 400, not 401)<br>- `backend/src/main/java/com/recruitpro/candidate/privacy/CandidateDataExportService.java` (JSON attachment with account, profile, resumes, facts, claims, applications, work samples, interviews, EEO answers and notifications)<br>- `backend/src/main/java/com/recruitpro/candidate/privacy/CandidateAccountDeletionService.java` (verifies the password; deletes resume files, facts, claims, the EEO survey, notifications and one-time tokens, revokes refresh tokens; anonymises the user as `deleted-user-<id>@deleted.invalid` / "Deleted Candidate" and clears profile fields; keeps applications (open ones withdrawn), decision history, match runs, scores, interviews and audit rows)<br>- `frontend/src/pages/PrivacyPage.tsx` |
| 14 | **In-app and email notifications** | **IMPLEMENTED** | - `backend/src/main/resources/db/migration/V14__background_jobs_and_notifications.sql` (`notifications` table)<br>- `backend/src/main/java/com/recruitpro/notification/entity/Notification.java`<br>- `backend/src/main/java/com/recruitpro/notification/entity/NotificationChannel.java` (`IN_APP`, `EMAIL`)<br>- `backend/src/main/java/com/recruitpro/notification/service/NotificationService.java`, `NotificationSender.java`<br>- `backend/src/main/java/com/recruitpro/notification/controller/NotificationController.java` (`/api/v1/me/notifications`, `/unread-count`)<br>- `frontend/src/pages/NotificationsPage.tsx`, `hr-frontend/src/pages/account/NotificationsPage.tsx` |

---

## 3. Software Requirements Matrix (Task 3)

Comparison of versions declared in the run guide against the actual configuration files:

| Software / Tool | Guide Specified Version | Actual Repo Configuration Source | Match? | Discrepancies & Notes |
|---|---|---|---|---|
| **Java (JDK)** | **21** (Temurin 21 recommended) | `backend/pom.xml` (`<java.version>21</java.version>`), `backend/Dockerfile` (`eclipse-temurin-21`), `.github/workflows/ci.yml` (`java-version: "21"`) | **MATCH** | Exact alignment on JDK 21. |
| **Maven** | Not in Part 2 table; uses wrapper `./mvnw.cmd` | `backend/.mvn/wrapper/maven-wrapper.properties` (`distributionUrl` = Maven 3.9.16), `backend/Dockerfile` (`maven:3.9-eclipse-temurin-21`) | **MATCH** | Guide relies on the Maven Wrapper (3.9.16) instead of requiring manual Maven installation. |
| **PostgreSQL** | Port 5432 / PostgreSQL 16 | `docker-compose.yml` (`pgvector/pgvector:pg16`), `backend/src/main/resources/application.yml` ("PostgreSQL 16"), `tools/check_migrations_pg.py` (pgserver PG16) | **MATCH** | The Docker image specifies `pgvector/pgvector:pg16`. |
| **Python** | **3.11** (3.10 also works) | `ai-service/Dockerfile` (`python:3.11-slim`), `.github/workflows/ci.yml` (`python-version: "3.11"`), `ai-service/requirements.txt` | **MATCH** | Core dependencies target Python 3.11. |
| **Node.js** | **22 LTS** | `frontend/Dockerfile` (`node:22-alpine`), `hr-frontend/Dockerfile` (`node:22-alpine`), `.github/workflows/ci.yml` (`node-version: "22"`) | **MATCH** | Both Dockerfiles and GitHub Actions CI use Node 22. |
| **pnpm** | **10** (`pnpm@10`) | `frontend/Dockerfile` (`pnpm@10`), `hr-frontend/Dockerfile` (`pnpm@10`), `.github/workflows/ci.yml` (`version: 10`), `frontend/pnpm-workspace.yaml`, `hr-frontend/pnpm-workspace.yaml` | **MATCH** | Note: Lockfiles are version `9.0` (standard for pnpm 9/10), but both `pnpm-workspace.yaml` files use `allowBuilds` which requires pnpm 10. |
| **Docker Desktop** | Recent, with Compose v2 (6 GB RAM min) | `docker-compose.yml` (Compose specification without legacy `version:` attribute) | **MATCH** | Minimum 6 GB memory recommendation aligns with building 4 images concurrently. |
| **Mailpit** | Port 8025 / 1025 | `docker-compose.yml` (`axllent/mailpit:latest`, ports 8025:8025, 1025:1025) | **MATCH** | Used as the local development mail sink. |
| **Python Test Packages** | `pgserver`, `psycopg2-binary`, `tree-sitter`, `tree-sitter-java` | `tools/check_migrations_pg.py`, `tools/java_static_check.py`, `tools/check_entity_schema.py`, `.github/workflows/ci.yml` | **MATCH** | Used for local standalone verification scripts without Docker. |

---

## 4. Testing Claims Verification (Task 4)

| Guide Claim | Claimed Value | Actual Repository Evidence | Verdict | Detailed Audit Findings |
|---|---|---|---|---|
| **Python Tests** | 177 automated tests | `ai-service/tests/` contains 9 test files with 120 `def test_` functions, expanding to 177 test cases via parametrization | **PARTIALLY VERIFIED / HISTORICAL** | `docs/plan/WP-PY-NOTES.md` and `docs/plan/STATUS.md` record `177 passed (~7 s)`. (Update 2026-11-15: the teammate's run gives 176 passed, 1 skipped; the skipped test needs LibreOffice.) In this audit session, tests could not be dynamically executed due to sandboxed environment restrictions on python runtimes. |
| **PostgreSQL Migrations** | 20 migrations | `backend/src/main/resources/db/migration/` contains `V1` through `V20` | **VERIFIED** | All 20 SQL files exist on disk, follow Flyway versioning conventions, and are covered by `tools/check_migrations_pg.py`. |
| **Backend Compilation** | "Backend never compiled" | `backend/target/classes/` and `backend/target/test-classes/` exist with 485 `.class` files | **REFUTED / INCONSISTENT** | The guide claims the backend has never been compiled. However, `backend/target` contains 485 `.class` files compiled on `2026-11-14 23:13`. While compilation was executed, no packaged `.jar` or test surefire reports exist, meaning `mvn verify` has not run to completion. (Update 2026-11-15: `mvn verify` has since passed on the teammate's machine: 239 tests, 0 failures, 6 skipped.) |
| **Frontend Builds** | Both frontend builds passed | `frontend/dist` and `hr-frontend/dist` DO NOT exist; `node_modules` DO NOT exist | **UNVERIFIED IN WORKSPACE** | `docs/plan/WP-FE-CAND-NOTES.md` and `docs/plan/WP-FE-HR-NOTES.md` document that `pnpm build` passed in scratch copies on 2026-11-08. Neither build output was committed (correctly gitignored). (Update 2026-11-15: both portals now build on the teammate's machine.) |
| **Browser Simulation Tests** | "Passed simulated-browser tests" | Test harness scripts are completely missing from the git repository | **UNVERIFIABLE (HARNESS NOT COMMITTED)** | `docs/plan/WP-FE-CAND-NOTES.md` explicitly notes: *"The harness (mock server + puppeteer script) is not committed: it depends on a downloaded Chromium build."* `docs/plan/WP-FE-HR-NOTES.md` notes: *"The smoke tests (jsdom 25 + esbuild IIFE bundles + a fixture API) were run from a scratch directory and are not part of the repository."* |

---

## 5. Guide Inconsistencies and Omissions

1. **Incorrect Branch Name in Instructions**:
   - `RUN_AND_TEST_GUIDE.md` (Part 1, lines 29, 32, 42) directs the reader to switch to branch `audit-remediation`. The actual branch on the repository is `Kshitij`. Following the guide verbatim results in `error: pathspec 'audit-remediation' did not match any file(s) known to git`.
2. **Outdated Commit Warning**:
   - The guide instructs the repository owner: *"the remediation work is not committed yet. In the project folder run: git checkout -b audit-remediation... git commit -m ...".* This was written prior to committing. The commit has already been finalized as commit `363c7d7` on branch `Kshitij`.
3. **Backend Compilation Claim Contradiction**:
   - The guide emphasizes heavily in Part 0 and Part 3.3 that the Java backend has never been compiled. Yet the repository working directory contained freshly generated `.class` files in `backend/target` (dated 23:13, Nov 14, 2026). The guide should clarify that while class compilation has been performed, full integration testing and packaging via `mvn verify` remains the open gate.
4. **Missing Simulated Browser Test Scripts**:
   - The guide assures the reader that simulated-browser tests passed, but no automated command is provided in Part 3 or anywhere in the repository to reproduce them because the Puppeteer and JSDOM test fixtures were omitted from git.
5. **Maven Tool Listing**:
   - The table in Part 2 omits Maven from the tools table, yet Step 3.3 instructs the user to execute `.\mvnw.cmd verify`. While using the wrapper is standard practice, explicitly noting that Maven 3.9 is downloaded automatically avoids confusion.

---

## 6. Deferred Items and Architectural Boundaries (from `docs/plan/STATUS.md` & `docs/plan/IMPLEMENTATION_PLAN.md`)

The following capabilities were intentionally deferred during this remediation work package (`docs/plan/STATUS.md`, Section 4.2):
- **Spring Boot 4 / Jackson 3**: Large breaking upgrade, left for a separate change after Boot 3.5 is green in CI.
- **PostgreSQL Row-Level Security (RLS)**: Scoping is currently enforced at the application service layer (`HrJobAccessService`, `OrganizationRepository`). Database-level RLS requires per-request connection variable plumbing.
- **pgvector Semantic Matching**: Currently uses lexical and taxonomy-based matching; vector embeddings deferred until embedding model provider integration.
- **ESCO Import**: Importer script exists (`ai-service/app/taxonomy/import_esco.py`), but the official ESCO CSV has not been imported.
- **SSO (OAuth2 / OIDC) and Multi-Factor Authentication (MFA)**: Auth is currently email/password with rate-limiting, lockout, and rotating refresh tokens.
- **External Integrations**: Calendar, HRMS and job-board connectors and WhatsApp notifications. Interviews are communicated by email and in-app notifications only (no calendar invites).
- **Real Labelled Gold Set**: The evaluation harness uses synthetic resumes; a recruiter-labelled real set must replace it before accuracy is quoted.
- **Outcome Data**: 90-day performance data is not captured yet.

Related known limitation (not in the deferred list): OCR for scanned documents is an optional extra using RapidOCR (`ai-service/requirements-ocr.txt`, `OCR_ENABLED=true`) and was tested only with a fake engine (finding M8 is "Partly" fixed in `STATUS.md`). Tesseract is not used. Also, the backend accepts `.doc` uploads but marks them `UNSUPPORTED_FORMAT` and never queues them for parsing (`ResumeIntelligenceService.createFor`), although the AI service can read `.doc`.

---

## 7. Recommended Safe Installation and Testing Sequence

To test the branch safely in accordance with the findings:

### Phase 1: Environment & Tool Verification (Read-Only)
```powershell
git branch --show-current     # Verify you are on branch 'Kshitij'
git log -n 1 --oneline        # Verify HEAD is 363c7d7
java -version                 # Ensure JDK 21 (Temurin 21) is active
node --version                # Ensure v22.x
pnpm --version                # Ensure 10.x
python --version              # Ensure 3.11.x
docker compose version        # Ensure Compose v2
```

### Phase 2: Offline / Static Verification
```powershell
# 1. Verify all 20 Flyway migrations against PostgreSQL 16
python -m pip install pgserver psycopg2-binary tree-sitter tree-sitter-java
python -B tools\check_migrations_pg.py

# 2. Run schema and AST static verification
python -B tools\check_entity_schema.py
python -B tools\java_static_check.py backend\src

# 3. AI Service Tests
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
python -m pytest -q
python -m eval.run --check
python ..\audit\probe_matching.py
deactivate
cd ..
```

### Phase 3: Portal Clean Builds
```powershell
# Candidate portal build
cd frontend
pnpm install --frozen-lockfile
pnpm build
cd ..

# HR portal build
cd hr-frontend
pnpm install --frozen-lockfile
pnpm build
cd ..
```

### Phase 4: Backend Compile and Test Verification
```powershell
cd backend
.\mvnw.cmd clean verify *> ..\backend-build.log
cd ..
Select-String -Path backend-build.log -Pattern "BUILD SUCCESS","BUILD FAILURE","ERROR" | Select-Object -First 30
```

### Phase 5: Containerized Stack Launch
```powershell
docker compose up --build
```
Verify the six services (`docker compose ps`):
- `postgres` (port 5432)
- `mailpit` (port 8025 / 1025)
- `ai-service` (port 8000)
- `backend` (port 8080)
- `candidate-portal` (port 5173)
- `hr-portal` (port 5174)
Execute the 17 interactive test scenarios (T1–T17) documented in Part 5 of `RUN_AND_TEST_GUIDE.md`.

