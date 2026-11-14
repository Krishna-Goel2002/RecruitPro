# WP-FE-HR notes — HR, interviewer and admin portal

Status: implemented; gate **G-FE passes** (2026-11-08). Scope: everything under `hr-frontend/`. Plan reference: `IMPLEMENTATION_PLAN.md` Sections 1, 3.4, 5 and 7.

## Verification

| Check | Result |
|---|---|
| `pnpm build` (`tsc --noEmit && vite build`) in a scratch copy | passes, 0 type errors (~393 kB JS / 117 kB gzip, 57 kB CSS) |
| Clean re-copy of `hr-frontend/` (no `node_modules`) → `pnpm install --frozen-lockfile` → `pnpm build` | passes |
| Runtime smoke test: the bundle in jsdom against a mocked API with plan-shaped fixtures, development build (StrictMode, React warnings count as failures) | 34/34 scenarios: every route for each role, role-based navigation and route guards, applicant grouping and rank numbers, blind vs revealed applicant, decision dialog (focus moves in, background `inert`, required comment enforced, correct POST body, focus/inert restored), compile → suggestions → accept, work-sample and kit editors, fairness table, scorecard validation, admin override offers only allowed targets |
| Runtime checks against a production build | concurrent 401s share **one** refresh and each request retries once with the new token; rejected refresh signs out to `/login` with a notice; polling only while a PENDING applicant exists (2 s, backing off, none when nothing is pending), stops once two minutes have passed; session restore is `POST /auth/refresh`; local/session storage stay empty |
| Not verified here | rendering in a real browser (no browser can be downloaded in this environment, so layout at 768 px was checked by reading the CSS only) and calls against the running backend |

Tooling note: `npx pnpm@9 install` fails on this repository because `pnpm-workspace.yaml` uses the pnpm 10 `allowBuilds` key ("packages field missing"). The scratch builds therefore used `npx pnpm@10`, the same major version as the Dockerfile. The lockfile format is still `9.0`: a copy without `pnpm-workspace.yaml` also installs with `pnpm@9 install --frozen-lockfile` and builds.

The smoke tests (jsdom 25 + esbuild IIFE bundles + a fixture API) were run from a scratch directory and are not part of the repository; adding a proper test runner (Vitest + Testing Library or Playwright) is a WP-INT follow-up.

Added dependencies: `react-router-dom@^6.30.6`, `@tanstack/react-query@^5.107.1`. Nothing else.

## Routes

| Route | Guard | Screen |
|---|---|---|
| `/login` | public (signed-in users are redirected) | sign-in; shows why the last session ended |
| `/signup` | public | create organisation + first admin |
| `/accept-invite?token=` | public | invitation preview, then name + password |
| `/forgot-password` | public | always answers the same way |
| `/reset-password?token=` | public | new password |
| `/` | signed in | redirects by role: ADMIN `/admin`, INTERVIEWER `/interviews`, others `/jobs` |
| `/jobs` | ADMIN, HR_MANAGER, RECRUITER | list with status filter, debounced title search, pagination, five stat counters |
| `/jobs/new`, `/jobs/:jobId/edit` | ADMIN, HR_MANAGER | job form, requirement rows, "Draft requirements from description" |
| `/jobs/:jobId?tab=applicants\|work-samples\|interview-kit\|fairness\|details` | ADMIN, HR_MANAGER, RECRUITER (Fairness tab: managers) | header with status actions + "Export audit CSV"; tabs |
| `/jobs/:jobId/applicants/:applicationId` | ADMIN, HR_MANAGER, RECRUITER | blind-first applicant review (C3) |
| `/interviews`, `/interviews/:interviewId` | every staff role | my interviews; kit, focus requirements, scorecard form |
| `/admin`, `/admin/users`, `/admin/invitations`, `/admin/organization`, `/admin/jobs`, `/admin/audit`, `/admin/fairness` | ADMIN | administration |
| anything else | signed in | not-found page |

A route a role may not use shows a "not available for your role" page; navigation only lists what the role can open. Filters, page, sort and tab are kept in the URL.

## API calls per screen

All paths are relative to `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`). Every call sends `credentials: 'include'` and, when signed in, `Authorization: Bearer <access token>`.

| Screen | Calls |
|---|---|
| App start / any 401 | `POST /auth/refresh` (single flight) |
| Sign-in / sign-out | `POST /auth/login`, `POST /auth/logout` |
| Sign-up | `POST /organizations/signup` |
| Accept invitation | `GET /auth/invitations/{token}`, `POST /auth/accept-invite` |
| Forgot / reset password | `POST /auth/forgot-password`, `POST /auth/reset-password` |
| Jobs list | `GET /jobs?page&size=20&status&search&sort=createdAt&direction=DESC`; counters: `GET /jobs?page=0&size=1[&status=PUBLISHED\|DRAFT\|ON_HOLD\|CLOSED]` |
| Job form | `GET /jobs/{id}`, `POST /jobs`, `PUT /jobs/{id}`, `PUT /jobs/{id}/requirements` (only when the rows changed), `POST /jobs/compile-requirements` |
| Job header | `GET /jobs/{id}`, `PATCH /jobs/{id}/status`, `DELETE /jobs/{id}` (draft), `GET /hr/jobs/{id}/audit-export` (blob) |
| Applicants tab | `GET /hr/jobs/{id}/applicants?page&size=25&status&sort=rank\|appliedAt`, `POST /hr/jobs/{id}/rematch` |
| Work samples tab | `GET /hr/jobs/{id}/work-samples`, `POST …/work-samples/generate`, `PUT …/work-samples/{wsId}`, `PATCH …/work-samples/{wsId}/status`, `GET /hr/jobs/{id}/work-sample-assignments?status` |
| Interview kit tab | `GET /hr/jobs/{id}/interview-kit` (404 = no kit yet), `POST …/interview-kit/generate`, `PUT …/interview-kit` |
| Fairness tab | `GET /hr/jobs/{id}/fairness` |
| Applicant detail | `GET /hr/jobs/{j}/applicants/{a}`, `GET /jobs/{j}` (title, requirement names), `POST …/{a}/decisions`, `POST …/{a}/reveal`, `PATCH …/{a}/analysis/retry`, `PUT …/{a}/review-comment`, `POST …/{a}/claims/{c}/verification`, `GET …/{a}/resume` (blob), `GET /hr/jobs/{j}/work-samples`, `POST …/{a}/work-samples`, `POST /hr/work-sample-assignments/{id}/score`, `GET /hr/interviewers`, `POST …/{a}/interviews`, `PATCH /interviews/{id}` (`{status:'CANCELLED'}`), `GET /hr/jobs/{j}/interview-kit` (question text for scorecards) |
| My interviews | `GET /interviews/mine` |
| Interview detail | `GET /interviews/{id}`, `POST /interviews/{id}/scorecard` |
| Admin overview | `GET /admin/dashboard` |
| Staff accounts | `GET /admin/users?page&size&search&role&enabled`, `PATCH /admin/users/{id}/role`, `PATCH /admin/users/{id}/status` |
| Invitations | `GET /admin/invitations`, `POST /admin/invitations`, `DELETE /admin/invitations/{id}` |
| Organisation | `GET /admin/organization`, `PATCH /admin/organization` (changed fields only) |
| Job oversight | `GET /admin/jobs?page&size&status&search`, `PATCH /admin/jobs/{id}/status` `{status, reason}` |
| Audit log | `GET /admin/audit-logs?page&size&action`, `GET /admin/audit-export?from&to` (blob) |
| Organisation fairness | `GET /admin/fairness` |

Not called (data arrives inside `ApplicantDetail` instead): `GET …/{a}/history`, `GET …/{a}/interviews`, the legacy `…/shortlist` and `…/reject` aliases, `POST /jobs/{id}/requirements`, `PUT/DELETE /jobs/{id}/requirements/{rid}`.

## Backend status and contract gaps (as of this package)

Checked against `backend/src/main/java` while WP-BE-B was landing in parallel.

| Area | Backend today | Notes for reconciliation |
|---|---|---|
| Auth, sign-up, invitations, `/me`, admin organisation/users/jobs/audit/dashboard | exist (WP-BE-A) | Shapes match `types.ts`. `Invitation.status` (WP-BE-A extra) is used when present, otherwise derived from timestamps. Dashboard uses `health{…queue}` and falls back to the flat `*Health` fields. |
| Jobs + requirements v2, `PUT /jobs/{id}/requirements`, `POST /jobs/compile-requirements` | exist (WP-BE-B, `JobStaffResponse`, `RequirementResponse`, `CompileRequirementsResponse`) | Match the plan, including `applicationCount`, `requirementsVersion`, `closedReason`, `createdByName`. |
| Applicant list/detail, decisions, reveal, resume, claim verification, rematch, interviewers | exist (WP-BE-B: `ApplicantController`, `ApplicationReviewController`, `HrJobToolsController`) | Match the plan. Extras used when present: `ApplicantDetail.revealAllowed` (drives the Reveal button; it knows when blind review is off — fallback is the plan rule "status ≠ SUBMITTED"), `CandidateClaim.hasEvidenceUrl` / `verificationStatus` (UNVERIFIED/VERIFIED/REJECTED), `RequirementEvidence.provenance`. `ApplicantDetail.workSamples`/`interviews` are `List<Object>` filled by WP-BE-C extensions; the UI types them as `WorkSampleAssignment[]` / `Interview[]` from plan 5.6. |
| `GET /hr/jobs/{id}/audit-export`, `GET /hr/jobs/{id}/fairness`, `GET /admin/fairness` | **missing** (WP-BE-C) | UI shows the server's error (404) in place until they exist. Filename is taken from `Content-Disposition` (exposed by CORS); fallback `job-{id}-audit.csv`. |
| Work samples, assignments, scoring, interview kits, interviews, scorecards (`/hr/jobs/{id}/work-samples*`, `/hr/work-sample-assignments/*`, `/hr/jobs/{id}/interview-kit*`, `/hr/jobs/{j}/applicants/{a}/interviews`, `/interviews/*`) | **missing** (WP-BE-C) | Coded exactly to plan 5.6. Assumptions: `GET interview-kit` returns 404 when no kit exists; `PATCH …/work-samples/{id}/status` accepts ACTIVE from DRAFT or ARCHIVED; `POST …/score` sends every rubric criterion `{requirementId, criterion, score 1–4, note?}`; scorecard `notes`/`summary` are sent as `null` when empty. |

Other assumptions:

- Ranking is entirely server-side. The list groups the current page by `rankGroup` (pages are contiguous because the server orders RANKED → MUST_HAVES_UNVERIFIED → UNSCORED) and shows `rank` only for RANKED rows; with `sort=appliedAt` groups are not shown.
- `allowedActions` decides which decision buttons appear (managers only). `UNDO_SHORTLIST` and `REOPEN` require a comment in the dialog; the others accept an optional one.
- Polling uses `matchStatus === 'PENDING'` on the list and the detail.
- Requirement rows send `knockout = mandatory && knockout` (a ranking gate needs a must-have). New manual rows default to must-have without a gate; compiled suggestions keep the engine's values.
- Times: `datetime-local` inputs are converted with `new Date(value).toISOString()`; the audit export sends the start of the local "from" day and the end of the local "to" day as ISO instants (both may be left empty: server default is the last 90 days).
- The applicant page's "Open interview" link is shown to managers and to the assigned interviewer only (`GET /interviews/{id}` rejects others).

## Design decisions worth knowing

- Access token in memory only; the refresh cookie restores sessions. A request that gets 401 waits for the shared refresh and retries once; if the token changed meanwhile it retries without refreshing again. A second 401 or a rejected refresh clears the session. Network errors during refresh do not sign the user out.
- Candidate accounts cannot use the portal: login/sign-up/restore with role CANDIDATE revokes the session (`POST /auth/logout`).
- Work samples can be edited only while DRAFT; activating makes them read-only so every candidate gets the same task.
- Regenerating an interview kit asks for confirmation because it replaces edited questions.
- Destructive confirmations put initial focus on Cancel.
- Old monolithic components (`Workspace.tsx`, `ApplicantIntelligencePanel.tsx`, `AdminViews.tsx`) and the 3 000-line `style.css` were deleted; the reusable parts of the CSS live on in `styles/base.css` and `styles/workspace.css` (dead applicant/admin rules removed, small fonts raised ~2 px).

## Not done / follow-ups

- No in-app notification inbox (`/me/notifications`), change-password or profile screen for staff; none is in the Section 7 screen list.
- `/admin/hr-team` (HR directory) is no longer shown; staff accounts cover it.
- Interviews can be cancelled but not rescheduled from the UI (`PATCH /interviews/{id}` with a new time is supported by the client but has no form).
- Work-sample assignments cannot be cancelled from the UI (no endpoint in the plan).
- No route-level code splitting (single 393 kB bundle; fine for an internal tool, easy to add with `React.lazy`).
- Visual check in a real browser and an end-to-end run against the backend once WP-BE-C lands (WP-INT).
