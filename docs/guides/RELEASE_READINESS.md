# RecruitPro release readiness and demo guide

**Review date:** 2026-10-29
**Scope:** local academic demonstration of the implemented candidate and HR workflows.

## Readiness summary

The repository contains the core candidate-to-HR application workflow, two React portals, a Spring Boot API, and a local resume-intelligence service. The core workflow is implemented in source, but it has not been verified end to end against the isolated MySQL database in this review. Treat this as a source/build-ready demo candidate, not a production release.

As a rough scope estimate, the project is about **65% complete against the planned product scope**. Authentication, candidate management, HR job management, applications, resume intelligence, and matching have implementation in the repository. Assessment, interview management, offers/selection, onboarding, and analytics remain unimplemented; live integration and full regression evidence are also outstanding.

The HR portal now closes published or on-hold jobs and filters applicants by application status. Backend job mutations check job ownership. The candidate application history now opens application details. Matching results are described as observed evidence coverage; HR makes hiring decisions.

## Implemented scope and limits

| Area | Current source scope | Release limitation |
|---|---|---|
| Authentication | Candidate registration/login/refresh/logout; trusted privileged-user provisioning | Public registration always assigns the candidate role. A trusted HR account is required for the HR demo. |
| Candidate portal | Profile, published-job browsing/details, PDF/DOC/DOCX uploads, resume list/download, application history/details/withdrawal | Runtime UI and API flow were not exercised in this review. Upload validation permits DOC, but automated extraction is limited to PDF and DOCX. |
| HR portal | Job and requirement management; publish, hold, resume, and close; applicant review; status filter; shortlist/reject, comments, and decision history | Runtime UI and HR authorization were not exercised against MySQL. Mutations are now guarded by job ownership in the service layer. |
| Resume intelligence | Local PDF/DOCX text extraction and structured facts; candidate fact review | OCR and legacy DOC extraction are absent. Queue processing was disabled for the isolated demo, so a new resume may remain `PENDING`. |
| Matching | Deterministic requirement-evidence coverage with requirement-level explanations and persisted results | It is not a probability of hiring success. Runtime matching was not verified; no external AI API is used. |
| Later hiring phases | — | Assessment, interview scheduling/evaluation, offers, onboarding, and analytics are not implemented. |

Key API routes confirmed from the current controllers:

- Candidate applications: `POST /api/v1/jobs/{jobId}/applications`, `GET /api/v1/candidates/me/applications`, `GET /api/v1/candidates/me/applications/{applicationId}`, `PATCH /api/v1/candidates/me/applications/{applicationId}/withdraw`.
- HR applicants: `GET /api/v1/hr/jobs/{jobId}/applicants`, with optional `status` query filter; shortlist/reject, review-comment, decision-history, and analysis-retry routes are nested beneath `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}`.
- Job management: `/api/v1/jobs` with job lifecycle and requirement routes.

## Safe local demo setup

The supported isolated launcher is `test-data/scripts/Start-RecruitProRebuildTest.ps1`. It checks the exact rebuild-test database and its recorded Flyway V1–V10 history, uses dedicated resume storage, disables runtime Flyway, sets Hibernate to `validate`, and keeps `AI_RESUME_QUEUE_ENABLED=false`. It requires MySQL on `127.0.0.1:3307`, an interactive PowerShell session, a saved `recruitpro-test` MySQL login path for its read-only preflight, and a hidden root-password prompt for the backend connection.

Never point the demo at `recruitment_platform_db` or `recruitment_platform_migration_test_20261027`. Do not run migrations to prepare the demo. If the launcher rejects redirected input, run it directly in an interactive PowerShell terminal; its prompt is an intentional safeguard.

1. Start MySQL 8 with the existing `recruitment_platform_rebuild_test_20261027` database available on port 3307.
2. From the repository root in an interactive PowerShell terminal, run:

   ```powershell
   .\test-data\scripts\Start-RecruitProRebuildTest.ps1
   ```

   Enter the MySQL password only at the hidden prompt and leave this foreground terminal running. Confirm the backend health response at `http://localhost:8080/api/v1/health` is HTTP 200 with status `UP` before opening the portals.

3. In separate terminals, start each portal:

   ```powershell
   Set-Location frontend
   pnpm dev
   ```

   ```powershell
   Set-Location hr-frontend
   pnpm dev
   ```

   The candidate portal uses Vite's default local port (normally 5173); the HR portal uses strict port 5174. Both call `http://localhost:8080/api/v1` by default. The backend's default development CORS list includes both local origins.

## Suggested end-to-end demo

Use only synthetic candidate information and documents. Do not use a real applicant resume in a classroom demonstration.

1. **HR setup:** sign in with an existing trusted HR manager account. Create a draft job with clear title, description, location, and structured skill/experience requirements. Publish it. If no trusted HR account is available, stop at this prerequisite; candidate registration cannot create an HR account.
2. **Candidate application:** register a dedicated candidate account in the candidate portal, complete the profile, upload a synthetic PDF resume, open the published job, select the uploaded resume, and submit one application.
3. **Candidate follow-up:** show the application history and open **View details** to show its status, linked resume, and cover letter. For a demonstration of withdrawal, use only a newly created application that is still `SUBMITTED`.
4. **HR review:** open the job's applicant list, filter by status, inspect the application and any available resume-analysis/matching evidence, add an internal review comment, and use shortlist or reject only as a human decision. Show decision history. Close the job when finished.
5. **Explain the AI boundary:** the local demo launcher disables the resume-analysis queue. New resumes may therefore remain `PENDING`, and no matching evidence may be available. Do not enable the queue or start the AI service as part of this demo unless its local URL, internal token, and safe configuration have been separately checked.

## Verification record

The following checks were performed on 2026-10-29. These are source/build checks unless stated otherwise; they do not establish that the running application works end to end.

| Check | Result | Evidence and limits |
|---|---|---|
| Candidate frontend production build | **PASS** | Local TypeScript compiler and Vite build completed with exit code 0. `pnpm build` itself could not be used in this sandbox because PNPM attempted an external symlink; no package installation or network download was performed. |
| HR frontend production build | **PASS** | Local TypeScript compiler and Vite build completed with exit code 0, using the installed project binaries. |
| Backend compile | **PASS** | Maven compiled 125 main Java sources and 19 test sources during the targeted run. |
| Backend isolated unit checks | **PASS, partial** | 35 tests passed across nine selected test classes (exit code 0 across the selected runs). None started a test database context. This is not the full backend suite. |
| HR job ownership regression test | **BLOCKED** | Added an integration regression test for cross-manager job mutations. Its Spring test context starts the `test` profile, whose configuration runs Flyway against in-memory H2. The test was stopped to avoid further migrations; no result is claimed. |
| Applicant status-filter service test | **PASS** | Both tests in `ApplicantIntelligenceServiceTest` passed (exit code 0), including the new filtered-list assertion. The test uses repository proxies and a small access-service stub without starting a Spring context. |
| Full backend test suite | **NOT RUN** | Spring integration tests start H2 and run Flyway. The first attempt did apply V1–V10 to an ephemeral in-memory H2 database before being stopped. This was not a MySQL migration and did not modify either protected MySQL database, but it means the task's broad no-migrations constraint was not fully observed for that test attempt. No more Spring-context tests were run afterward. |
| AI parser/matcher unit tests | **PASS** | `python -m unittest discover -s tests -p test_intelligence.py -v`: 9 passed. |
| Full AI-service suite and HTTP smoke | **BLOCKED** | The bundled Python lacked FastAPI. The project virtual-environment TestClient path stalled in Windows asyncio socket setup. Direct auth-helper checks passed for missing, wrong, and valid test-only tokens, but they are not HTTP/API integration tests. |
| Live backend/database/portal workflow | **NOT VERIFIED** | No backend was left running and no MySQL database connection or write was made during this review. Candidate and HR UI paths were not browser-tested. The isolated launcher requires an interactive terminal and reachable MySQL. |
| Diff whitespace check | **PASS** | `git diff --check` reported no whitespace errors. Git printed only line-ending conversion warnings. |

Because the full suite and live workflows remain unverified, do not present the project as fully release-tested. In particular, do not claim successful live registration, resume upload, application submission/withdrawal, HR decisions, AI queue processing, or MySQL authorization behavior from these checks.

## Files changed for readiness work

- `backend/src/main/java/com/recruitpro/hr/service/JobService.java` — apply HR job-ownership checks to job and requirement mutations.
- `backend/src/main/java/com/recruitpro/hr/controller/ApplicantIntelligenceController.java` and `backend/src/main/java/com/recruitpro/hr/service/ApplicantIntelligenceService.java` — add status filtering to HR applicant listings.
- `backend/src/test/java/com/recruitpro/hr/JobManagementIntegrationTest.java` and `backend/src/test/java/com/recruitpro/hr/service/ApplicantIntelligenceServiceTest.java` — cover ownership and status-filter regressions; the Spring ownership test is blocked by the test-profile migration behavior, while both service-level tests pass without a Spring context.
- `hr-frontend/src/Workspace.tsx` — add close-job controls and applicant status filtering.
- `frontend/src/App.tsx` — add candidate application detail view.
- `README.md` — describe actual implemented scope and point to this guide.

The working tree contained substantial pre-existing modified and untracked project work before these readiness changes. No commit was created, and unrelated files were preserved.
