# 4. Portals and user flows

This chapter describes RecruitPro's two web portals: the **candidate portal** (`frontend/`) and the **HR / staff portal** (`hr-frontend/`). It covers every screen, every user action and the API calls behind them.

**How this chapter was checked.** Every statement was checked against the source at commit `2fedafb` (branch `Kshitij`), mainly `frontend/src/**` and `hr-frontend/src/**`. Where the plan notes (`docs/plan/WP-FE-*.md`, `STATUS.md`) disagree with the code, the code wins and the difference is listed in Section 4.7. Nobody clicked through the portals in a browser or ran them against a live backend for this chapter. `WP-FE-FINISH-NOTES.md` says the same about the latest frontend work: no browser or end-to-end run, and no live backend run. **Rehearse the demo on `docker compose up` before presenting.**

Paths in this chapter are relative to the repository root. Unless a path says otherwise, API paths are relative to `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`).

---

## 4.0 The two portals at a glance

| | Candidate portal | HR / staff portal |
|---|---|---|
| Folder | `frontend/` | `hr-frontend/` |
| Package name | `recruitpro-candidate-portal` | `recruitpro-hr-portal` |
| Local URL (Compose) | `http://localhost:5173` | `http://localhost:5174` |
| Who uses it | People looking for jobs; referees, through one public page | Staff in four roles: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `INTERVIEWER` |
| `X-RecruitPro-Portal` header | `candidate` | `staff` |
| Refresh cookie (httpOnly, set by the server) | `rp_refresh_candidate` | `rp_refresh_staff` |
| Wrong account type | Staff accounts are refused: "This portal is for candidate accounts. Staff members sign in on the HR portal." | Candidate accounts are refused: "This workspace is for organisation staff. Candidates sign in on the candidate portal." |
| Landing page after sign-in | `/jobs` | `ADMIN` → `/admin`, `INTERVIEWER` → `/interviews`, everyone else → `/jobs` (`permissions.ts › homePath`) |
| Production bundle (FINISH notes) | 370.6 kB JS / 33.1 kB CSS | 449.5 kB JS / 63.0 kB CSS |

Each portal has its own cookie, so one browser can be signed in to both portals at the same time without the two sessions interfering (STATUS §2.2).

---

## 4.1 Frontend architecture

### 4.1.1 Stack and versions

The two `package.json` files declare the same dependencies.

| Package | Version range | Role |
|---|---|---|
| `react`, `react-dom` | `^18.3.1` | UI |
| `react-router-dom` | `^6.30.6` | Routing (`BrowserRouter` with the `v7_startTransition` and `v7_relativeSplatPath` future flags) |
| `@tanstack/react-query` | `^5.107.1` | Server state: caching, polling, invalidation |
| `typescript` | `^5.6.3` | Type checking (`tsc --noEmit` runs as part of the build) |
| `vite` / `@vitejs/plugin-react` | `^5.4.8` / `^4.3.2` | Dev server and bundler |
| `@types/react` / `@types/react-dom` | `^18.3.12` / `^18.3.1` | Types |

Neither portal uses a UI kit, CSS framework, form library, state library, i18n library or QR-code library. Both have the same two scripts: `dev` (`vite`) and `build` (`tsc --noEmit && vite build`). Neither has a test runner or an ESLint configuration (WP-FE-CAND notes).

In development the HR portal pins Vite to `localhost:5174` with `strictPort` (`hr-frontend/vite.config.ts`). The candidate portal uses Vite's default port, 5173.

### 4.1.2 Folder structure

```
frontend/src/                          hr-frontend/src/
├─ main.tsx      (mount + CSS)         ├─ main.tsx      (QueryClient, Router, Notice + Auth providers)
├─ App.tsx       (QueryClient, routes) ├─ App.tsx       (routes + capability guards)
├─ api/                                ├─ api/
│  ├─ client.ts   fetch wrapper        │  ├─ client.ts   fetch wrapper
│  ├─ endpoints.ts one fn per call     │  ├─ auth.ts, account.ts, jobs.ts, review.ts,
│  │              + normalisers        │  │  assessments.ts, admin.ts, verification.ts
│  ├─ queryKeys.ts                     │  ├─ queryKeys.ts (qk)
│  └─ types.ts                         │  └─ types.ts
├─ auth/  AuthProvider.tsx, guards.tsx ├─ auth/  AuthContext.tsx, guards.tsx, permissions.ts
├─ components/ Badges, ClaimForm,      ├─ components/ Badges, ChipsInput, CoverageBars, Dialog,
│   Consent, CopyButton, Dialog,       │   Field (+Toggle), Icons, Layout (+PageHeader), Notices,
│   Feedback, Form, Layout, Notices,   │   Pagination, RatingScale, States, Tabs, Verification
│   PageHeader, VerdictChip            ├─ lib/ dateRange, format, hooks (polling), useSearchState,
├─ lib/ download, files, focus, format,│   verification
│   hooks, labels, password, polling,  ├─ pages/
│   url, verification                  │  ├─ auth/ (Login, Signup, AcceptInvite, Password pages,
├─ pages/ *.tsx (one per route)        │  │   SsoComplete, AuthLayout)
│  ├─ auth/ (Login, Register, Forgot,  │  ├─ jobs/ (list, form, detail, tabs, requirements editor)
│  │   Reset, VerifyEmail, AuthLayout) │  ├─ applicants/ (detail + sections + VerificationTab)
│  ├─ jobs/ApplyDialog.tsx             │  ├─ interviews/ (list, detail, ScorecardView)
│  ├─ resumes/ (ResumeCard,            │  ├─ account/ (Notifications, ChangePassword, Security)
│  │   AnalysisPanel, useResumeAnalysis)│ ├─ admin/ (8 pages)
│  └─ verification/ (Sources, Connect, │  └─ StatusPages.tsx (Forbidden, NotFound)
│      Ownership, Ledger, References)  └─ styles/ base, workspace, components, pages .css
└─ styles/ base, layout, components, pages .css
```

### 4.1.3 Routing

#### Candidate portal (`frontend/src/App.tsx`)

| Path | Component | Guard | In sidebar | Purpose |
|---|---|---|---|---|
| `/login` | `auth/LoginPage` | `PublicOnly` (inside `AuthLayout`) | — | Sign in |
| `/register` | `auth/RegisterPage` | `PublicOnly` | — | Create a candidate account |
| `/forgot-password` | `auth/ForgotPasswordPage` | `PublicOnly` | — | Request a reset link |
| `/reset-password?token=` | `auth/ResetPasswordPage` | Public (works signed in or out) | — | Set a new password from the email link |
| `/verify-email?token=` | `auth/VerifyEmailPage` | Public | — | Confirm the email address |
| `/reference/:token` | `RefereePage` | Public, outside both layouts, no auth calls | — | Referee answers a reference request |
| `/` | redirects to `/jobs` | `RequireAuth` | — | — |
| `/jobs` | `JobsPage` | `RequireAuth` + `Layout` | "Find jobs" | Search published jobs |
| `/jobs/:id` | `JobDetailPage` (+ `jobs/ApplyDialog`) | same | — | Job details, apply |
| `/applications` | `ApplicationsPage` | same | "Applications" | List of the candidate's applications |
| `/applications/:id` | `ApplicationDetailPage` | same | — | Timeline, verification status, work samples, interviews |
| `/resumes` | `ResumesPage` | same | "Resumes" | Upload resumes, review the analysis |
| `/evidence` | `EvidencePage` | same | "Evidence" | Claims (self-reported evidence) |
| `/verification` | `VerificationPage` | same | "Strengthen your application" | Sources, ownership, results, references |
| `/work-samples` | `WorkSamplesPage` | same | "Work samples" | Assigned timed tasks |
| `/work-samples/:assignmentId` | `WorkSampleResponsePage` | same | — | Start, answer and submit a task |
| `/interviews` | `InterviewsPage` | same | "Interviews" | Scheduled interviews |
| `/notifications` | `NotificationsPage` | same | "Notifications" (with unread badge) | In-app inbox |
| `/profile` | `ProfilePage` | same | "Profile" | Profile details, password change |
| `/privacy` | `PrivacyPage` | same | "Privacy" | Survey, data export, account deletion |
| `*` | `NotFoundPage` | same | — | "Page not found" (signed-out visitors go to `/login` first) |

**Guard behaviour** (`auth/guards.tsx`):

- `RequireAuth` shows "Restoring your session…" while the session is being restored. A signed-out visitor is redirected to `/login`, and the page they asked for is remembered in `state.from`.
- `PublicOnly` sends a signed-in user on to `redirectTarget(state)`. That is the remembered page, used only if it is a safe in-app path (it starts with `/`, does not start with `//` and is not `/login`); otherwise the user goes to `/jobs`.

**Jobs are behind sign-in.** The backend's `SecurityConfig` does not make `GET /jobs` public either, so an anonymous visitor cannot browse jobs.

#### HR portal (`hr-frontend/src/App.tsx`)

| Path | Component | Guard (capability) | Roles allowed | Sidebar group / label | Purpose |
|---|---|---|---|---|---|
| `/login` | `auth/LoginPage` | `PublicOnly` | — | — | Password sign-in, MFA step, forced MFA enrolment, SSO buttons |
| `/signup` | `auth/SignupPage` | `PublicOnly` | — | — | Create an organisation and its first admin |
| `/forgot-password` | `auth/PasswordPages › ForgotPasswordPage` | `PublicOnly` | — | — | Request a reset link |
| `/reset-password?token=` | `ResetPasswordPage` | Public | — | — | Set a new password |
| `/accept-invite?token=` | `auth/AcceptInvitePage` | Public | — | — | Join an organisation from an invitation |
| `/sso/complete` | `auth/SsoCompletePage` | Public | — | — | Finish single sign-on (exchange the cookie for an access token) |
| `/` | `HomeRedirect` | signed in | all staff | — | Sends the user to `homePath(role)` |
| `/jobs` | `jobs/JobsListPage` | `jobs.view` | A, M, R | Workspace / "Jobs" | Job list with five counters |
| `/jobs/new` | `jobs/JobFormPage` | `jobs.manage` | A, M | — | Create a job (saved as a draft) |
| `/jobs/:jobId?tab=` | `jobs/JobDetailPage` | `jobs.view` | A, M, R | — | Tabs: `applicants`, `work-samples`, `interview-kit`, `fairness` (managers only), `details` |
| `/jobs/:jobId/edit` | `JobFormPage` | `jobs.manage` | A, M | — | Edit a job and its requirements |
| `/jobs/:jobId/applicants/:applicationId?tab=` | `applicants/ApplicantDetailPage` | `jobs.view` | A, M, R | — | Blind-first review; `tab=verification` opens the Verification tab |
| `/interviews` | `interviews/InterviewsPage` | `interviews.view` | all staff | Workspace / "My interviews" | Interviews assigned to me |
| `/interviews/:interviewId` | `interviews/InterviewDetailPage` | `interviews.view` | all staff (server: assigned interviewer or managers) | — | Interview kit and scorecard |
| `/notifications` | `account/NotificationsPage` | `account` | all staff | Account / "Notifications" (with unread badge) | In-app inbox |
| `/account/password` | `account/ChangePasswordPage` | `account` | all staff | Account / "Change password" | Change password |
| `/account/security` | `account/SecurityPage` | `account` | all staff | Account / "Two-step verification" | MFA settings |
| `/admin` | `admin/AdminDashboardPage` | `admin` | A | Administration / "Overview" | Counts and service health |
| `/admin/users` | `AdminUsersPage` | `admin` | A | "Staff accounts" | Change roles, enable or disable accounts |
| `/admin/invitations` | `AdminInvitationsPage` | `admin` | A | "Invitations" | Invite staff, revoke invitations |
| `/admin/organization` | `AdminOrganizationPage` | `admin` | A | "Organisation" | Blind review, sharing, MFA requirement, SSO domains |
| `/admin/organisation` | redirect to `/admin/organization` | — | — | — | British-spelling alias |
| `/admin/jobs` | `AdminJobsPage` | `admin` | A | "Job oversight" | Override a job's status (a reason is required) |
| `/admin/audit` | `AdminAuditPage` | `admin` | A | "Audit log" | Browse the audit log, export CSV |
| `/admin/fairness` | `AdminFairnessPage` | `admin` | A | "Fairness" | Organisation-wide adverse-impact report |
| `/admin/verification` | `AdminVerificationPage` | `verification.queue` | A, M | "Verification" | Mode and connectors (ADMIN only), metrics, shadow queue |
| `*` | `StatusPages › NotFoundPage` | signed in | — | — | "Page not found" |

Key: A = ADMIN, M = HR_MANAGER, R = RECRUITER.

**Guard behaviour** (`hr-frontend/src/auth/guards.tsx`):

- `RequireAuth` shows "Restoring your session…" while restoring. A signed-out user is redirected to `/login` with `state.from`, except right after they pressed Sign out.
- A user without the required capability sees `ForbiddenPage`: "This area is not available for your role… The server checks every action, whatever this page shows."

Filters, page number, sort order and tab are kept in the URL by `lib/useSearchState.ts`, so Back/Forward and shared links restore the same view. Changing any key other than `page` resets `page`.

### 4.1.4 API client

Both portals have their own `api/client.ts`. The two clients are close but not identical.

| Aspect | Candidate (`frontend/src/api/client.ts`) | HR (`hr-frontend/src/api/client.ts`) |
|---|---|---|
| Base URL | `VITE_API_BASE_URL` (fixed at build time), default `http://localhost:8080/api/v1` | same |
| Access token | A module variable only. Nothing goes to `localStorage` or `sessionStorage` (audit S2). | same |
| Cookies | `credentials: 'include'` on every call | same |
| Portal header | `X-RecruitPro-Portal: candidate` on every call | `X-RecruitPro-Portal: staff` |
| Response envelope | Unwraps `{success, message, data, errors}`. Failures throw `ApiError(message, status, fieldErrors)`. A network failure gives `status 0`. | Same envelope. `errorMessage()` joins field errors as "Field: message · …". |
| Single-flight refresh | One shared `refreshInFlight` promise for `POST /auth/refresh` | same |
| On a 401 | If another request has already refreshed the token, retry with that token. Otherwise wait for the shared refresh and retry **once**. | same |
| Refresh refused | 400, 401 or 403 from the refresh: clear the token, `expireSession()`, and listeners sign the user out with the reason "expired". | Any other non-OK answer (not 5xx or 429): `setSession(null)`, outcome `unauthorized`. |
| Refresh hits a network error, 5xx or 429 | The promise rejects. At start-up this shows "We could not reach the server…" with a **Try again** button; the user is never signed out. | Outcome `network`, error `ApiError(0)`. The session is kept. |
| Second 401 after the retry | Returned to the caller as an ordinary error | The session is cleared (sign-out) |
| After account deletion | `blockRefreshUntilSignIn()`: no refresh calls until the next sign-in | n/a |
| Uploads | `FormData` bodies; the browser sets the multipart boundary | none (the HR portal never uploads) |
| Downloads | `apiDownload()` returns `{blob, filename}` taken from `Content-Disposition` (`filename*` preferred). `lib/download.ts › saveBlob` saves it under `safeFilename(...)`; the object URL is revoked after 2 s. | `downloadFile(path, fallback)` saves directly; the file name is sanitised; the object URL is revoked after 30 s. |
| Dates | Bodies go through `JSON.stringify`, so `Date` values become ISO strings. `datetime-local` inputs are converted with `toISOString()`. | same (`lib/format.ts › fromLocalInput`) |
| Public calls | `auth: false`: no bearer token and never a refresh (login, register, referee page…) | same |

**Default error messages** when the server sends none:

| Status | Candidate portal | HR portal |
|---|---|---|
| 401 | "Please sign in to continue." | "Your session has ended. Sign in again to continue." |
| 403 | "You do not have access to this." | "Your role does not allow this action." |
| 404 | "We could not find what you were looking for." | "This item was not found, or it belongs to another organisation." |
| 409 | "This conflicts with the current state…" | "This item changed in the meantime…" |
| 429 | "Too many attempts…" | "Too many attempts…" |
| 413 | "The file is too large." | — |

The candidate portal's `api/endpoints.ts` also has small **normalisers** that turn missing fields into `null` or `[]`. They exist because the backend serialises with `non_null`, so absent fields are simply left out of the JSON. One of them caught a real bug: unread notifications arrive without a `readAt` field.

### 4.1.5 TanStack Query usage

**Defaults**

| | Candidate (`App.tsx`) | HR (`main.tsx`) |
|---|---|---|
| `staleTime` | 30 s | 10 s |
| Retries | Up to 2, only for network errors and 5xx | 1, never for 4xx |
| `refetchOnWindowFocus` | off (except the unread count) | off (except the unread count) |
| Mutations | never retried | never retried |

**Query keys.** The first element of every key is the resource name, so one call can invalidate a whole group. In the HR portal the keys carry every request parameter, so a slow response can never overwrite the screen for a newer filter (audit L7).

| Candidate (`queryKeys`) | HR (`qk`) |
|---|---|
| `['jobs', params]`, `['job', id]` | `['jobs','list',params]`, `['jobs','stats',status]`, `['jobs','detail',id]` |
| `['profile']`, `['resumes']`, `['resume-analysis', id]`, `['claims']` | `['applicants', jobId, 'list', params]`, `['applicants', jobId, 'detail', appId]` |
| `['applications', page]`, `['application', id]` | `['workSamples', jobId]`, `['workSamples', jobId, 'assignments', status]` |
| `['work-samples']`, `['interviews']` | `['interviewKit', jobId]`, `['interviewers']` |
| `['notifications', page]`, `['unread-count']` | `['fairness','job',jobId,from,to]`, `['fairness','organization',from,to]` |
| `['equal-opportunity']` | `['interviews','mine']`, `['interviews','detail',id]` |
| `['verification','sources' \| 'ledger' \| 'references']`, `['verification','verdict',id]` | `['admin', 'dashboard' \| 'users' \| 'invitations' \| 'organization' \| 'jobs' \| 'audit', …]` |
| `['public-reference', token]` | `['verification','application',appId]`, `['verification','settings' \| 'metrics']`, `['verification','shadowQueue',page]` |
| | `['auth','ssoProviders']`, `['account','mfa']`, `['notifications','page',n]`, `['notifications','unread']` |

**Polling.** Every poll is time-boxed (audit L8: the old code polled every 2.5 s forever).

| What is polled | Portal | Polls while | Interval | Stops | Afterwards |
|---|---|---|---|---|---|
| Resume analysis (`useResumeAnalysis`) | Candidate | status is `PENDING` or `PROCESSING` | fixed 3 s (`usePollingWindow`, no backoff) | After 120 s, or when the tab is hidden (`refetchIntervalInBackground: false`) | Banner "This is taking longer than usual…" with **Check again**, which restarts the window |
| Verification sources / runs (`SourcesSection`) | Candidate | any source's `lastRun` is `PENDING` or `RUNNING` | 3 s × 1.3 backoff, at most 15 s | After 120 s | "Still checking" with **Check again**. When a run finishes, the ledger is invalidated and the notice "A verification finished…" appears. |
| Unread notification count | both | always | every 120 s, plus refetch on window focus | never | — |
| Applicant list (`ApplicantsTab`) | HR | any row on the page has `matchStatus === 'PENDING'` | `pollDelay`: 2 s + elapsed/8, rounded to whole seconds, at most 15 s | After 120 s; the window restarts when the job, filter or page changes | "Still matching" pill and **Check again**. **Re-run matching** also restarts the window. |
| Applicant detail | HR | `matchStatus === 'PENDING'` | same | After 120 s | "Matching is slow — check again" button |

**Invalidation after mutations (main cases)**

| Mutation | Invalidates or updates |
|---|---|
| Candidate: apply | `['applications']` |
| Candidate: withdraw | `['applications']`, `['application', id]`, then focus moves to the page heading |
| Candidate: add or delete a claim, or correct a fact | `['claims']` and every `['application', …]` (matching re-runs on the server); a fact correction also invalidates `['resume-analysis', id]` |
| Candidate: start or submit a work sample | `setQueryData` on `['work-samples']`, then invalidate it and `['application']` |
| Candidate: connect or revoke a source | `setQueryData` on sources; revoke invalidates all of `['verification']` |
| Candidate: share, hide or re-check a verdict | `setQueryData(verdict)`; invalidate the ledger (and sources after a re-check) |
| HR: save a job | `setQueryData(job)`, invalidate `['jobs']`; also `['applicants', jobId]` when the requirements of a live job changed |
| HR: decision or reveal | the applicant detail, `['applicants', jobId]`, `['jobs','detail',jobId]` and `['verification','application',appId]` (a reveal unlocks evidence) |
| HR: verify a claim, score a work sample, cancel an assignment, schedule or cancel an interview | the applicant detail and `['applicants', jobId]`; plus assignments or `['interviews']` as relevant |
| HR: confirm or overrule a verdict | the applicant's verification, the applicant detail and the applicant list (the backend re-matches) |
| HR: label a shadow verdict | `setQueryData` on the queue page, invalidate metrics |
| HR: change a user's role or status | `['admin','users']`, `['interviewers']` |

### 4.1.6 Authentication context, guards and the HR permission model

**Candidate `AuthProvider`** (`frontend/src/auth/AuthProvider.tsx`):

- **States:** `loading`, `authenticated` or `anonymous`. When signed out, a reason is kept and shown on the sign-in page:

  | Reason | Sign-in page text |
  |---|---|
  | `signed-out` | "You have signed out." |
  | `expired` | "Your session has ended. Please sign in again." |
  | `staff-session` | "This browser is signed in to the HR portal…" |
  | `unreachable` | "We could not reach the server to restore your session." (with a Try again button) |
  | `account-deleted` | "Your account has been deleted…" |
  | `password-reset` | "Your password was changed. Please sign in with your new password." |

- **Session restore:** one `POST /auth/refresh` when the app loads. React StrictMode runs effects twice, but the single-flight refresh makes that safe.
- **Defence in depth:** a non-`CANDIDATE` session created on this portal is logged out at once (`POST /auth/logout`) and the user sees the 403 message.

**HR `AuthContext`** (`hr-frontend/src/auth/AuthContext.tsx`):

- **States:** `restoring`, `authenticated` or `anonymous`. `endedReason` is shown on the login page.
- **`acceptSession(auth)`** adopts a session returned by login, sign-up, accepting an invitation, MFA verification or a password change. It rejects non-staff accounts and revokes the cookie the server has just set.
- **`completeExternalSignIn()`** is used by `/sso/complete`. It refreshes with the staff header and returns the user, or `null`.
- **`can(capability)`** reads `permissions.ts`.

**HR permission matrix** (`hr-frontend/src/auth/permissions.ts`). The code says: "The server stays the authority; this only hides actions a role cannot perform."

| Capability | ADMIN | HR_MANAGER | RECRUITER | INTERVIEWER | What it controls in the UI |
|---|:-:|:-:|:-:|:-:|---|
| `jobs.view` | ✓ | ✓ | ✓ | | Jobs list, job detail, applicant detail |
| `jobs.manage` | ✓ | ✓ | | | Create/edit job, publish/hold/close/cancel/delete |
| `applicants.decide` | ✓ | ✓ | | | Shortlist / Move to interview / Select / Undo / Reopen / Reject |
| `applicants.reveal` | ✓ | ✓ | | | "Reveal identity" |
| `applicants.comment` | ✓ | ✓ | ✓ | | Review note |
| `applicants.verifyClaims` | ✓ | ✓ | ✓ | | Verify / Not verified on candidate claims |
| `applicants.downloadResume` | ✓ | ✓ | ✓ | | Download the resume (after reveal) |
| `applicants.retryAnalysis` | ✓ | ✓ | | | "Retry analysis" |
| `applicants.rematch` | ✓ | ✓ | | | "Re-run matching" |
| `workSamples.manage` | ✓ | ✓ | | | Generate, edit, activate and archive work samples; assign them |
| `workSamples.score` | ✓ | ✓ | ✓ | | "Score with rubric" |
| `workSamples.cancel` | ✓ | ✓ | | | "Cancel assignment" |
| `workSamples.downloadFiles` | ✓ | ✓ | ✓ | | Download submitted files |
| `interviewKit.manage` | ✓ | ✓ | | | Generate, regenerate and edit the interview kit |
| `interviews.schedule` | ✓ | ✓ | | | Schedule, reschedule and cancel interviews |
| `interviews.view` | ✓ | ✓ | ✓ | ✓ | "My interviews" |
| `fairness.job` | ✓ | ✓ | | | Job Fairness tab |
| `audit.jobExport` | ✓ | ✓ | | | "Export audit CSV" on a job |
| `verification.view` | ✓ | ✓ | ✓ | | Applicant Verification tab |
| `verification.review` | ✓ | ✓ | ✓ | | Confirm / Overrule a verdict |
| `verification.queue` | ✓ | ✓ | | | `/admin/verification` (metrics, shadow queue) |
| `verification.settings` | ✓ | | | | Verification mode and connector toggles |
| `admin` | ✓ | | | | Every other `/admin/*` page |
| `account` | ✓ | ✓ | ✓ | ✓ | Notifications, password, two-step verification |

**What each role sees in the sidebar**

| Role | Sidebar items |
|---|---|
| ADMIN | Everything |
| HR_MANAGER | Jobs, My interviews, Administration › Verification, and the three Account items |
| RECRUITER | Jobs, My interviews, and the three Account items |
| INTERVIEWER | My interviews and the three Account items |

**Only managers can decide.** A recruiter reads applicants, comments, verifies claims, scores work samples and reviews verdicts, but cannot shortlist or reject. The decision buttons are also filtered by the server's `allowedActions` for that application.

### 4.1.7 Forms and validation

- **Validation order.** Forms use `noValidate`: the client checks the fields first, then the server's field errors are mapped back onto the form. The candidate portal maps them per field with `fieldErrorMap`. The HR portal joins them into one message with `errorMessage()`.
- **Labels, hints and errors** are connected to the input with `aria-describedby` (candidate `components/Form.tsx › Field`; HR `components/Field.tsx`, which clones the input to add `id`, `aria-invalid` and `aria-describedby`). The HR job form, work-sample editor, kit editor and score dialog move focus to the first invalid field.
- **Password policy:** 10–72 characters with at least one letter and one digit.
  - Candidate portal: `lib/password.ts` shows a live checklist ("✓ / ○"), counts any Unicode letter or digit, and also caps the UTF-8 size at 72 bytes.
  - HR portal: `passwordProblem()` accepts only ASCII letters `[A-Za-z]`. This is a small inconsistency.
  - Both portals have show/hide password toggles.
- **URLs** must be absolute `http(s)` (`isHttpUrl` / `safeHttpUrl`). Only `http(s)` links are rendered as anchors, and external links open with `rel="noopener noreferrer nofollow"`.

**Upload checks.** The server repeats every check.

| Upload | Allowed types | Size limit | Number of files |
|---|---|---|---|
| Resume | PDF, DOC, DOCX | 10 MB | 1 |
| Verification document | PDF, DOCX, PPTX | 15 MB | 1 |
| Work-sample attachment | PDF, ZIP, DOCX, PPTX, PNG, JPG | 20 MB each | up to 3 |

**Times.** `datetime-local` inputs are interpreted in the user's local time and sent as UTC ISO strings. The fairness date range is sent as inclusive UTC `YYYY-MM-DD` days; `lib/dateRange.ts` rejects impossible dates such as 2026-02-30.

### 4.1.8 Accessibility patterns

| Pattern | Candidate | HR |
|---|---|---|
| Modal dialogs | Native `<dialog>` with `showModal()`, so the page behind is inert and focus stays inside. Focus moves to the first control, or a chosen one. Escape and a backdrop click close the dialog unless it is busy. Focus goes back to the opening element. | Custom portal dialog (`role="dialog"`, `aria-modal`). Tab is trapped, `#root` gets `inert` + `aria-hidden` (with a counter for nested dialogs), Escape closes unless busy, and focus goes back to the opener. |
| Confirmations | `ConfirmDialog` always focuses **Cancel** first | Focuses Cancel for dangerous actions and Confirm otherwise. `TextPromptDialog` asks for a comment, note or reason and can make it required. |
| Live regions | `NoticeProvider`: polite region for success/info (auto-dismiss after 6 s), assertive region for errors (stay until dismissed). At most 2 notices; a notice raised within 1.5 s before a navigation survives it. | Toasts are portalled **outside `#root`**, so they are still announced while a modal makes the page inert. Errors stay 9 s, others 6 s; at most 4 at once. |
| Focus after navigation | Moves to the page's `<h1 tabIndex=-1 data-page-heading>`, and back to it after actions that remove the focused control (withdraw, delete, submit) | Moves to `<main id="main-content" tabIndex=-1>` and scrolls to the top |
| Skip link | "Skip to main content" | "Skip to content" |
| Mobile navigation | Below 960 px the sidebar becomes a full-screen menu: `aria-expanded`, Escape closes it, the page behind is `inert` | Below the breakpoint the sidebar becomes a horizontally scrolling strip of links |
| Tabs | — | WAI-ARIA tabs with roving focus (arrow keys, Home, End). Only the active panel is rendered. |
| Status that does not rely on colour | Verdict chips combine a symbol and words (✓ ◐ ○ ✕ –); tier badges carry a description for screen readers | same; tier badges show a 5-step indicator; coverage bars include a text value |
| Timer | `role="timer"`. Only crossings of 10, 5 and 1 minutes are announced; "Time is up" and "window closed" are `role="alert"`. | — |
| Counts and badges | The unread badge text is `aria-hidden`, with "…, N unread" for screen readers | same |
| Reduced motion | `prefers-reduced-motion` respected (`base.css`) | same (`base.css`) |
| Contrast | Every colour pair measured at ≥ 4.5:1 (WP-FE-CAND notes) | Not documented |

### 4.1.9 Styling

Both portals use plain CSS with no preprocessor or framework, imported once in `main.tsx`.

| | Candidate | HR |
|---|---|---|
| Files | `styles/base.css` (394 lines), `layout.css` (499), `components.css` (715), `pages.css` (1135) | `styles/base.css` (768), `workspace.css` (1332), `components.css` (1260), `pages.css` (1916) |
| Approach | Mobile-first: `min-width` breakpoints at 600 px and 960 px | Desktop-first: `max-width` breakpoints at 1100, 1050, 900, 760, 700, 480 and 380 px |
| Look | Light theme, sidebar "CANDIDATE SPACE", monogram "R" | Light theme, split-screen sign-in ("HIRING, ON EVIDENCE" panel), "HR WORKSPACE" brand, tables and cards |
| Not present | Dark mode. The DM Sans and Manrope fonts are referenced but not loaded, so system fonts are used (candidate notes). | Dark mode |

### 4.1.10 Build and serving

`frontend/Dockerfile` and `hr-frontend/Dockerfile` are byte-identical, and so are the two `nginx.conf` files.

1. **Build stage** (`node:22-alpine`, `pnpm@10`): `pnpm install --frozen-lockfile`, then `pnpm build`. The build argument `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`) is **compiled into the bundle**, so pointing the portal at another API means rebuilding it.
2. **Serve stage** (`nginx:1.27-alpine`): `nginx.conf` is copied as `/etc/nginx/templates/default.conf.template`. At container start-up the official image runs `envsubst`, which fills `${API_ORIGIN}` (default `http://localhost:8080`). Docker Compose maps container port 80 to 5173 (candidate) and 5174 (HR).

**nginx behaviour**

| Location | Behaviour |
|---|---|
| `/assets/` | `Cache-Control: public, max-age=31536000, immutable` (fingerprinted files); `try_files $uri =404` |
| `= /index.html` | `Cache-Control: no-cache`, so new deployments are picked up |
| `/` | **SPA fallback:** `try_files $uri $uri/ /index.html`. Deep links such as `/jobs/12`, `/reference/<token>`, `/accept-invite?token=…` and `/sso/complete` serve the app. |

**Security headers.** Every location repeats the full set, because an `add_header` inside a location replaces the server-level list.

- `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' ${API_ORIGIN}; frame-ancestors 'none'; base-uri 'self'; form-action 'self' ${API_ORIGIN}; object-src 'none'`
- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`.
- The SSO buttons and "Sign in with GitHub" are top-level navigations, which CSP does not restrict.

---

## 4.2 Candidate portal screens

Every screen inside the signed-in layout has:

- **Sidebar:** the 10 items listed in 4.1.3, the user's initials, name and email, and **Sign out** (`POST /auth/logout`).
- **Mobile bar:** brand, notification bell with count, **Menu** button.
- **Unread count:** `GET /me/notifications/unread-count`.
- **Verify-email banner** while the email is unverified: "Please verify your email address… Some employers accept applications only from verified addresses." It offers **Resend link** (`POST /auth/resend-verification`) and **Not now**, which hides it for this page load only.

Shared state components:

| Component | What it shows |
|---|---|
| `LoadingBlock` | Spinner with a label (`role="status"`) |
| `ErrorBlock` | Error message with **Try again** (`role="alert"`) |
| `EmptyState` | Icon, title, text and an optional action |
| `Pagination` | "N total · Page x of y" with Previous / Next |

### 4.2.1 Sign-up (`/register`)

- **Fields:** first name, last name, email, password (with a live checklist), confirm password.
- **Client checks:** names are not empty; the email has a basic `x@y.z` form; the password meets the policy; the two passwords match. If any check fails: "Please correct the highlighted fields."
- **Call:** `POST /auth/register` `{firstName, lastName, email (lower-cased), password}`. It returns a session, so the user is signed in straight away.
- **Success:** the notice "Your account is ready. We sent you a link to verify your email address." and a move to `/jobs`.
- **Errors:** field errors from the server appear under the matching fields; any other error appears below the form. A staff account gets the 403 message.

### 4.2.2 Email verification (`/verify-email?token=`)

Each token is sent **once** (a ref guards against StrictMode's double effect). Call: `POST /auth/verify-email {token}`.

| State | What the page shows |
|---|---|
| No token | "This link is incomplete" |
| Checking | "Checking your link…" |
| Verified | "Email address verified", then **Continue to jobs** (signed in) or **Go to sign in** |
| Failed | The server's message plus "Links expire after 24 hours and work once." A signed-in, unverified user also gets **Send a new link** (`POST /auth/resend-verification`); a signed-out user is asked to sign in first. |

### 4.2.3 Sign-in (`/login`)

- **Fields:** email and password (Show/Hide). Call: `POST /auth/login`.
- **Notices:** the signed-out reason from 4.1.6, with **Try again** when the server was unreachable.
- **Links:** "Forgot your password?" and "New here? Create an account".
- **After sign-in:** the user returns to the page that sent them to sign-in, if any.

### 4.2.4 Password reset (`/forgot-password`, `/reset-password?token=`)

- **Forgot:** `POST /auth/forgot-password {email}`. The answer is always the same: "If an account exists for … The link is valid for one hour."
- **Reset:**
  - No token: "This link is incomplete" with a link to request a new one.
  - Form: new password (checklist) and confirm, then `POST /auth/reset-password {token, newPassword}`.
  - Success: "Your password has been changed and every session was signed out." If the user was signed in, the local session ends with the reason `password-reset`.

### 4.2.5 Find jobs (`/jobs`)

- **Filters:** "Job title or keyword", Department, Location and Job type (Full time, Part time, Contract, Internship), plus **Clear filters**.
  - The inputs stay mounted while results update, so focus is never lost.
  - The URL updates **300 ms** after typing stops (debounced), and the query follows the URL.
- **Results area:**
  - "N jobs found", announced with `role="status"`.
  - "Updating results…" while typing or fetching. The previous results stay on screen, dimmed (`keepPreviousData`).
- **Each job card:** department tag, organisation, title link, location · type · experience level, a 3-line description, salary range, "N must-have requirements", and "Apply by <date>" or "Applications open".
- **Pagination:** 10 per page.
- **Call:** `GET /jobs?page&size=10&search&department&location&jobType` (published jobs with an open deadline only, per audit M3).

| State | What the page shows |
|---|---|
| Loading | "Loading jobs…" |
| Error with no data | `ErrorBlock` |
| Error with earlier data | "Showing earlier results" above the old list |
| Empty | "No open roles match these filters" |

### 4.2.6 Job detail and the apply dialog (`/jobs/:id`)

**Job page.** `GET /jobs/{id}`.

- **Back link:** "← All jobs" restores the previous search.
- **Header:** organisation, title, location · type · level · department, and **Apply now →**.
- **Body:**
  - "About the role" (the description).
  - Key facts: Organisation, Salary, Openings, Apply by, Published.
  - Requirements, split into **Must-haves** and **Nice to have**. Each shows its type badge and, where set, "N+ years", "<level> level" and "Also accepted: …". The intro says: "A missing must-have does not reject you automatically; a person reviews every application."
- **Sticky button:** "Apply for this role".
- **404:** "This job is not open any more."

**Apply dialog** (`jobs/ApplyDialog.tsx`, audit M5). It has three steps, and focus moves to each step's heading.

1. **Form.**
   - **Resume to send:** a radio list, newest first; the newest is selected by default. It shows file name, upload date and size, and comes from `GET /candidates/me/resumes`. With no resumes: "You need a resume before you can apply. Upload a resume…", linking to `/resumes`.
   - **Cover letter (optional):** at most 10,000 characters, with a counter. The hint says: "Recruiters see your cover letter after your application is shortlisted, together with your name."
   - **Review application** is disabled until a resume is chosen.
2. **Check before you submit.**
   - A summary of Role, Resume, Cover letter ("Included (N characters)" or "Not included") and Contact email.
   - "After submitting you can follow the application and withdraw it, but you cannot change the resume or cover letter."
   - **Back** or **Submit application**, which calls `POST /jobs/{id}/applications {resumeId, coverLetter|null}`.
3. **Done.** "Your application for <job> was sent." with **View application** (goes to `/applications/:id`).

A duplicate application shows the server's 409 message: there is no "already applied" indicator. While the submit is in flight, the dialog cannot be closed.

### 4.2.7 Applications (`/applications`) and application detail (`/applications/:id`)

**List.** `GET /candidates/me/applications?page&size=10`.

- **Header pill:** "N total".
- **Each row:** job title link, organisation, "Applied <date>", resume file name, and a status badge.
- **Status labels:** Submitted, Shortlisted, Interviewing, Selected, **"Not progressed"** (REJECTED) and Withdrawn.
- **Empty:** "No applications yet" with **Browse jobs**.

**Detail.** `GET /candidates/me/applications/{id}`.

| Section | Content |
|---|---|
| Header | "APPLICATION #id", job title, organisation, the applied date and time, a status badge, and **Withdraw** (only for SUBMITTED, SHORTLISTED or INTERVIEWING). Withdraw opens a confirmation: "…You cannot undo this…", then `PATCH /candidates/me/applications/{id}/withdraw`. |
| Progress | A timeline of statuses with times and a plain-language line for each, such as "Selected. The employer will contact you about next steps." If the server sends no timeline, one is derived from `appliedAt` and `statusChangedAt`. |
| What you sent | Resume (links to `/resumes`), "View job posting", the cover letter or "No cover letter was included." |
| Requirements and verification | Each requirement shows its type, Must-have or Nice to have, and a badge: **Verified**, **"Your claim — unverified"** or **"Not yet verified"**. Requirements that are not verified get **+ Add evidence** (hidden when withdrawn), which opens `ClaimForm` in a dialog with the type and text pre-filled (`POST /candidates/me/claims`). If `requirements` is null, the text reads "This employer does not share verification status with candidates" (active application) or "Verification status is shown while an application is active." |
| Work samples | Title link (to `/work-samples/:id`), status label, time limit, and "start by <date>" while ASSIGNED |
| Interviews | Local date and time, duration, status and location, with the note "Times are shown in <time zone>" |

### 4.2.8 Resumes and analysis review (`/resumes`)

**Upload panel.**

- A file input ("PDF, DOC or DOCX · up to 10 MB", checked on the client) and **Upload file**. Call: `POST /candidates/me/resumes` (multipart `file`).
- On success: "<file> uploaded. Analysis has started; earlier resumes stay attached to their applications."

**Resume list.** Newest first; the first card is expanded. Each card shows:

- the file extension as an icon, the file name, size and upload date;
- an analysis status label: Queued for analysis, Analysing, Analysis ready, Partly analysed, Analysis failed, Scanned document or Format not supported;
- **↓ Download**: `GET /candidates/me/resumes/{id}/download`, saved under the original file name;
- **Review details / Hide details**.

A hidden `role="status"` announces status changes even when the card is collapsed.

**Analysis panel** (`resumes/AnalysisPanel.tsx`). The analysis is polled from `GET /candidates/me/resumes/{id}/analysis` (see 4.1.5).

| Status | Banner |
|---|---|
| PENDING / PROCESSING | "Queued for analysis" or "Reading your resume" — "The page updates by itself." After 2 minutes: "This is taking longer than usual…" with **Check again**. |
| COMPLETED | "Analysis ready — If something is wrong, suggest a correction." |
| PARTIAL | "Some details were read — Add anything missing on the Evidence page." |
| NEEDS_OCR | "This looks like a scanned document… Recruiters can still open the file." |
| UNSUPPORTED_FORMAT | "This format cannot be analysed… Upload a PDF or .docx copy." |
| FAILED | A plain-language reason (for example "The file is password-protected…") and **Retry analysis** (`PATCH …/analysis/retry`) |

**"Notes about this file"** explains parser flags in neutral words. The six flags are HIDDEN_TEXT, PROMPT_INJECTION_SUSPECTED, TWO_COLUMN_LAYOUT, OCR_USED, LEGACY_DOC and LOW_TEXT_DENSITY. For example: "Wording that looks like instructions to software… They were read as ordinary text and do not change how your resume is assessed."

**Facts.** These appear when the status is COMPLETED or PARTIAL. They are grouped into Skills, Experience, Education, Certifications, "Contact details and links" and "Other details". Each fact card shows:

- the type and an evidence-tier badge;
- the **extracted value**;
- the quoted evidence text;
- the resume section it came from.

**Corrections become claims.** Skills, experience, education and certification facts have **Suggest a correction**; contact details and "Other details" do not.

- The textarea is labelled "Your correction". Its hint reads: "Your claim — recruiters see it as unverified until they check it. The text read from your resume is kept as it is."
- **Save claim** calls `PATCH /candidates/me/resumes/{resumeId}/analysis/facts/{factId} {value}`. The backend creates or updates a SELF_CLAIMED claim and never overwrites the extracted value (audit C1).
- The card then shows the extracted value **and**, below it, "Your claim — recruiters see it as unverified until they check it: <value>". The claim is shown only when it differs from the extracted value.

### 4.2.9 Evidence / claims (`/evidence`)

- **"How employers weigh evidence"** (expandable) lists the five tiers in plain language. The candidate portal labels them: Your claim → On your resume → Corroborated → Demonstrated → Verified in interview. It also says: "Rankings use verified evidence (resume or better)."
- **Add a claim** (`components/ClaimForm.tsx`):
  - Type: Skill, Experience, Education, Certification or Other.
  - "What you are claiming": required, at most 1,000 characters.
  - "Evidence link": optional, must be `http(s)`, at most 500 characters.
  - Call: `POST /candidates/me/claims {claimType, value, evidenceUrl}`.
  - Notice: "Claim added. Recruiters see it as unverified until they check it."
- **Your claims** (`GET /candidates/me/claims`), newest first. Each claim shows its type, tier, value, evidence link and "Added <date>", followed by its review state: "Not checked yet", "Confirmed by a recruiter on <date>" or "Checked on <date>; the recruiter could not confirm it". It also shows a recruiter note when there is one.
- **Delete** asks for confirmation ("…Applications are re-checked without it."), then calls `DELETE /candidates/me/claims/{id}`.

### 4.2.10 Work samples (`/work-samples`, `/work-samples/:assignmentId`)

**List.** `GET /candidates/me/work-samples`.

- **"To do"** holds ASSIGNED and STARTED tasks; **"Finished"** holds the rest.
- **Status labels:** Not started, In progress, Submitted, Reviewed (SCORED), Expired and Cancelled.
- **Each card:** job title, time limit, "start by <date>", and **Open → / Continue → / View →**.
- **Empty:** "No work samples yet."

**Response page.** The task is read from the list query.

| Status | What the candidate sees and can do |
|---|---|
| ASSIGNED | "Before you start" rules: instructions appear only after Start; the timer cannot be paused; there is a 5-minute grace; start by <date>. A warning appears if the start-by time has passed. **Start the work sample** asks "Start now? The timer starts as soon as you confirm and runs even if you close this page." and then calls `POST …/work-samples/{id}/start`. A refusal (400 or 409) refetches the task, which may now show as expired. |
| STARTED | A **TIME LEFT** timer (low under 5 minutes; "Ends at <time>"). Then the **Instructions**, and the **Your response** form: response text (at most 20,000 characters; required unless a file is attached), "Link to your work" (optional), and "Attach files" (up to 3: PDF, ZIP, DOCX, PPTX, PNG or JPG, at most 20 MB each, each removable). **Submit response** asks for confirmation and then calls `POST …/work-samples/{id}/submit` (multipart `responseText`, `responseUrl`, `files`). The browser warns before leaving the page while text is typed. At 0: "Time is up. Submit now…". At limit + 5 minutes: "The submission window has closed…", and the page refetches once. |
| SUBMITTED / SCORED | "Submitted <date>. The employer reviews every response against the same scoring guide." The **Attached files** list has a Download button per file (`GET …/work-samples/{id}/files/{fileId}`). The score itself is never shown to the candidate. |
| EXPIRED | Explains whether the time ran out or the start-by time passed. Any text the candidate could not send is shown read-only so it can be copied. |
| CANCELLED | "The employer cancelled this work sample on <date>. Nothing more is needed from you." and the reason given |

### 4.2.11 Interviews (`/interviews`)

`GET /candidates/me/interviews`.

- **Upcoming:** SCHEDULED interviews that have not ended yet.
- **Past and cancelled:** everything else.
- **Each card:** status, job title, local start and end time with duration, and location. A location that is an `http(s)` URL is rendered as a link.
- **Time zone:** the header states the candidate's time zone.
- **Empty:** "No interviews scheduled."

### 4.2.12 Notifications (`/notifications`)

- **List:** `GET /me/notifications?page&size=20`. Unread items have a dot and "Unread:" for screen readers; times are relative (for example "2 hours ago").
- **Actions:** **Mark as read** per item (`PATCH /me/notifications/{id}/read`) and **Mark all as read** (`POST /me/notifications/read-all`; disabled when nothing is unread).
- **Content:** items show only a subject and a body. They are not links to the item they describe.

### 4.2.13 Profile (`/profile`)

- **Identity row:** initials, name, email, and an "Email verified" or "Email not verified" tag. The name cannot be changed; there is no endpoint for it.
- **Professional details** (`GET` / `PUT /candidates/me`), all optional:
  - Text: current job title, current employer, city, state or region, country, postal code, address, LinkedIn URL, portfolio, "About you".
  - Numbers: years of experience, notice period (days), expected annual salary (₹).
  - URL and number ranges are checked on the client.
  - The form says: "We do not ask for your date of birth or gender." (audit M15).
  - The page description says: "Recruiters see these details only after your application is shortlisted."
- **Change password:** current, new (with checklist) and confirm. Call: `POST /me/password`. The returned session is adopted. Notice: "Password changed. You were signed out on your other devices."

### 4.2.14 Privacy (`/privacy`)

1. **Equal-opportunity survey (voluntary).**
   - **"Why we ask":** answers are stored separately; individual answers are never shown to recruiters, interviewers or employers; results are reported only as totals; groups smaller than 5 are hidden; answering has no effect on any application; answers can be changed or deleted.
   - **Questions:** four selects, each defaulting to "Not answered": Gender, Age group, Disability and Social category (General, OBC, SC, ST, EWS, Other, Prefer not to say).
   - **Saving:** a consent checkbox is required ("I agree that RecruitPro stores these answers for fairness monitoring…") and at least one answer must be given. Calls: `GET` / `PUT /candidates/me/equal-opportunity`, with `consent: true` sent on every save. The box is unticked after saving.
   - **Delete my answers** (with confirmation) calls `DELETE /candidates/me/equal-opportunity`.
2. **Download your data.** `GET /candidates/me/export` returns a JSON file. The file name comes from the server; the fallback is `recruitpro-my-data-<date>.json`.
3. **Delete your account.**
   - The page lists the consequences: profile, resumes, facts, claims and survey are removed; past applications and decisions are kept in anonymised form; deletion cannot be undone.
   - The user enters their password and confirms ("Delete your account permanently?"). Call: `DELETE /candidates/me {password}`.
   - Afterwards the app goes to `/login` with "Your account has been deleted…" and blocks refresh calls until the next sign-in.

### 4.2.15 Strengthen your application (`/verification`)

**Page purpose:** "Connect places that show your work. Checkers read them, and you decide which results employers may see."

**Arrival message.** On return from GitHub the page reads `?connected=github` or `?error=<CODE>`. It shows the outcome once and then removes the parameters from the URL. There are 10 known error codes, for example STATE_EXPIRED: "…took more than 10 minutes and expired". OAUTH_NOT_CONFIGURED adds a hint to connect with a username instead.

**"How it works"** lists four steps:

1. Connect a source and agree to what will be read.
2. Prove the source is yours. An unproven source can give at most "Partly verified".
3. Run a verification. Each result names the evidence behind it.
4. Review every result. Nothing is shown to employers until you choose "Share with employers".

**A. Your connected sources** (`GET /candidates/me/verification/sources`, polled; see 4.1.5)

- **Each source card:**
  - its kind (GitHub, GitLab, Website, Document or Open Badge);
  - its ownership status: "Ownership not proven yet", "Ownership check in progress", "Ownership proven · <method>" or "Ownership check did not succeed";
  - its label and identifier;
  - for documents and badges, why there is no ownership check;
  - the last ownership-check detail;
  - "Last verification: Waiting to start / Checking now / Finished / Did not finish · <time>", with a plain-language error when it failed (for example "The verification service was unavailable…").
- **Actions:**
  - **Check ownership** (unproven GitHub, GitLab or Website sources) opens the ownership dialog.
  - **Run verification** calls `POST …/sources/{id}/runs`. A 409 means "A verification of this source is already running"; a 429 means "You have reached the limit of 10 verifications in 24 hours".
  - **Revoke** asks for confirmation ("…delete the evidence collected from it and withdraw its results, including any you shared with employers") and calls `DELETE …/sources/{id}`.
- **Empty:** "No sources connected yet."

**B. Connect a source**

- **Kinds:** the radio list offers GitHub (the default), GitLab, Website, Document and Open Badge.
- **Consent:** every kind requires a consent tick, worded for that kind. For example: "I agree that RecruitPro may read my public GitHub profile and repositories to check the claims on my resume. I can revoke this at any time." If it is missing: "Please tick the consent box first. Nothing is connected without your consent."

| Kind | How the candidate connects it |
|---|---|
| GitHub | **Sign in with GitHub** calls `GET …/oauth/github/start` and then `window.location.assign(authorizeUrl)`. GitHub sends the browser back to `/verification?connected=github`, with ownership already proven. If the server answers 503 OAUTH_NOT_CONFIGURED, the form switches to a username field with an explanation. "Connect with my username instead" switches manually. |
| GitLab | Username, then `POST …/sources {kind, identifier, consent: true}` |
| Website | Full `https://` URL, same call |
| Document | PDF, DOCX or PPTX, at most 15 MB. Calls `POST …/sources/documents` (multipart `file` + `consent=true`). The form notes: "Ownership of a document cannot be proven." |
| Open Badge | "A link to the badge" (a URL) or "Paste the credential" (JSON is parsed when the text starts with `{`; otherwise it is sent as a token string) |

- A 409 means "This source is already connected…".
- After connecting, a source that can prove ownership opens the ownership dialog automatically.

**C. Ownership dialog** (`OwnershipDialog.tsx`)

- **Title:** "Prove <kind> is yours"; the source label is shown underneath.
- **Methods**, each with exact values and **Copy** buttons (`CopyField`, which announces "Copied"):

| Source | Method | What to publish |
|---|---|---|
| GitHub / GitLab | Code in the profile bio | the challenge code |
| Website | DNS TXT record | Host, and TXT value `recruitpro-verification=<code>` |
| Website | Small text file | `https://<host>/.well-known/recruitpro-verification.txt` containing only the code |
| Website | Code on the page | the code as visible text, or `<meta name="recruitpro-verification" content="<code>">` |

- **Check now** calls `POST …/sources/{id}/ownership-check {method}`.
  - Proven: "Ownership proven. Results from this source can now be 'Verified'…"
  - Pending: "The check is still in progress…"
  - Otherwise: "We could not find the code yet… Changes can take a few minutes to appear."
  - A 429 is the hourly limit; a 503 means the checking service is unavailable.
- **Later** closes the dialog.

**D. Your results — the Claim Ledger** (`GET …/verification/ledger`)

- **Standing note:** "'Not found in connected sources' is neutral. It only means the connected sources did not mention the claim; it never counts against you."
- **Each claim:** type, tier and value, followed by its verdict rows (or "Not checked yet"). A verdict row shows a **verdict chip**, the source label and the candidate's own status tag.
  - Verdict chips: ✓ Verified · ◐ Partly verified · ○ Not found in connected sources · ✕ Contradicted · – Not checkable by agents.
  - Status tags: Awaiting your review, Shared, Hidden, Withdrawn, or Replaced by a newer check.
- **Clicking a row** opens **Result details** (`GET …/verdicts/{id}`). It shows:
  - the chip with its description, the status tag and the claim;
  - **Source**, **Ownership** ("Proven", or "Not proven (results are at most 'Partly verified')") and **Checked**;
  - "This result was replaced or withdrawn…" when it is no longer current;
  - **Why**: reasons with quotes;
  - **Evidence**: title, URL, "Retrieved <time> · fingerprint <first 12 hex characters>…", and an excerpt;
  - **Notes for context**: integrity notes, introduced as "…They are not accusations and do not change the result; an interviewer may simply ask about them.";
  - **"An interviewer may ask"**: the probe question;
  - the candidate's earlier note.
- **Actions on a current result:**
  - "Note for employers" (optional, at most 1,000 characters).
  - **Re-check** (not offered for reference results), **Hide**, and **Share with employers**. Call: `POST …/verdicts/{id}/response {action: SHARE|HIDE|RECHECK, note}`.
  - Hide is disabled when the result is already hidden and no note was typed; Share works the same way when it is already shared.
  - Notices: "Shared. Employers you applied to can now see this result." / "Hidden…" / "Re-check started…".

**E. References** (`GET` / `POST …/verification/references`)

- **Ask for a reference** form:
  - their name and email (hint: "A work address helps: its domain is compared with the employer.");
  - "How do they know your work?": Manager, Colleague, Someone who reported to you, Client, Teacher or lecturer, Other;
  - employer or organisation (optional) and employer website domain (optional);
  - **Claims to confirm**: checkboxes from the ledger, 1 to 20;
  - consent: "…may email this person with my first name and the claims I chose, and store their answers."
- **Send request** calls `POST …/references {…, claims:[{subjectType, subjectId}], consent:true}`. Notice: "Request sent to X. The link is valid for 14 days."
- **Limit:** with 5 open requests, the form is replaced by an explanation. A server 429 gives the same message.
- **List:** each request shows its status (Waiting for an answer, Answered, Declined, Expired or Cancelled), email, relationship, employer, claim count, sent date, expiry and answered date, and "N results in 'Your results'".
- **Cancel request** (with confirmation) calls `POST …/references/{id}/cancel`.

### 4.2.16 Public referee page (`/reference/:token`)

- **Access:** no sign-in. The token in the link is the only credential. Calls use `auth: false` and never trigger a refresh. The page has its own card shell with the label "RECRUITPRO · REFERENCE REQUEST".
- **Load:** `GET /public/references/{token}`.

| Answer | What the referee sees |
|---|---|
| 404 | "This link is not valid" |
| 410 | "This request is closed" (expired, cancelled or already answered) |
| Other errors | `ErrorBlock` |

- **Form:**
  - **Heading:** "Can you confirm <first name>'s work?"
  - **Intro:** "<first name> named you as their manager at <employer>… 'Cannot confirm' is a perfectly good answer. This link works until <date>."
  - **For each claim:** a radio group (**Confirm** "I know this is true", **Partly**, **Cannot confirm**, **Disagree**) and an optional comment (at most 2,000 characters).
  - **Overall comment** (optional).
  - **Required identity checkbox:** "I confirm that I am the person this request was sent to, and that my answers are honest and based on my own knowledge."
- **Send answers** calls `POST /public/references/{token} {answers:[{claimId, answer, comment}], refereeComment, confirmIdentity:true}`. The page then shows "Thank you…The candidate will see the results, not your comments word for word, and decides whether to share them."
- **"I would rather not answer"** asks for confirmation, then calls `POST {decline: true}`. The page shows "Request declined".

---

## 4.3 HR portal screens

**Shell.**

- **Sidebar:** the brand, three groups (WORKSPACE, ADMINISTRATION, ACCOUNT) filtered by capability, and the organisation name at the bottom.
- **Header:** organisation name, bell with unread count, the user's avatar initial, name and role label ("Administrator", "HR manager", "Recruiter", "Interviewer"), and **Sign out**.
- **Narrow screens:** a horizontally scrolling strip of navigation links.

Shared state components:

| Component | What it shows |
|---|---|
| `LoadingPanel` | Spinner with a label |
| `ErrorPanel` | Error message with **Try again**. A 403 shows "Not available for your role" with no retry. |
| `EmptyPanel` | Title, text and an optional action |

### 4.3.1 Sign-in (`/login`): password, MFA, forced enrolment and SSO

The page is split into two halves. The left panel is decorative (`aria-hidden`): "Make room for better hiring." with an "EVIDENCE LADDER" card. The right panel holds the form.

1. **Password step.**
   - Fields: "Work email" and "Password" (with reveal). Call: `POST /auth/login`.
   - **401:** "Email or password is incorrect, or the account is locked for a few minutes."
   - The answer is either a session (`AuthResponse`) or an **MFA challenge** `{mfaRequired, mfaEnrollmentRequired, mfaToken, expiresInSeconds}`.
   - Notices shown on this step: why the last session ended, and `?ssoError=` messages (all 11 backend codes, such as `domain_not_allowed`: "Your organisation does not allow single sign-on for this email domain."). The `ssoError` parameter is removed from the URL after it has been read.
   - Footer: "Forgot your password?" and "New organisation? Create a workspace". Security note: "Authorised staff only. The server checks your role for every action."
2. **SSO buttons.** `GET /auth/sso/providers` returns the configured identity providers. For each one there is a "Continue with <name>" link to `<API>/auth/sso/<id>/start` (a top-level navigation). If no providers are configured, nothing is shown.
3. **MFA step** ("Enter your code").
   - A 6-digit code field: numeric only, `autocomplete="one-time-code"`.
   - Or **Use a recovery code**: at least 10 characters, ignoring spaces and dashes.
   - Call: `POST /auth/mfa/verify {mfaToken, code | recoveryCode}`.
   - If the token has expired (checked on the client, or a 401 mentioning expiry): "The sign-in step expired. Please enter your password again."
   - **Back to sign in** returns to the password step.
4. **Forced enrolment.** This appears when the organisation requires MFA and the user has not set it up.
   - **Start set-up** calls `POST /me/mfa/enroll {mfaToken}`, authorised by the token rather than a bearer token.
   - The page shows the **secret** in groups of 4 characters, plus the `otpauth://` URI under "Show the setup link instead". **There is no QR code.**
   - The user enters the 6-digit code; `POST /me/mfa/confirm {mfaToken, code}` returns the recovery codes and a session.
   - **"Save your recovery codes"** shows them once. **"I have saved them, continue"** adopts the session.
5. **SSO completion (`/sso/complete`).**
   - The backend's SSO callback sets `rp_refresh_staff` and redirects here (errors go to `/login?ssoError=`).
   - The page shows "Signing you in…", calls `POST /auth/refresh` with the staff header, then navigates to `homePath(role)`.
   - Failure: "Single sign-on did not create a session…" with **Back to sign in**.

### 4.3.2 Organisation sign-up, invitations and password pages

- **`/signup`** ("Create your workspace"):
  - Fields: organisation name, first name, last name, work email, password and confirm.
  - Call: `POST /organizations/signup`. The new user becomes the organisation's ADMIN and lands on `/admin`.
  - A 403 or 404 means "Self-service sign-up is turned off on this server…"; a 409 means "An account with this email already exists."
  - The backend also sends this admin an email-verification link. That link is broken; see 4.7.
- **`/accept-invite?token=`:**
  - `GET /auth/invitations/{token}` shows "Join <org>", the role, the email address and the expiry.
  - An invalid, expired, used or revoked invitation shows one combined message.
  - The form asks for first name, last name, password and confirm, then calls `POST /auth/accept-invite` and moves to `homePath(role)`.
- **`/forgot-password`** always gives the same answer. **`/reset-password?token=`** says: "Other sessions are signed out when your password changes."

### 4.3.3 "Dashboards": admin overview and jobs counters

There is no dashboard shared by all roles. ADMIN lands on the **Organisation overview** (`/admin`). HR managers and recruiters land on **Jobs**, which has five counters at the top. Interviewers land on **My interviews**.

**Organisation overview** (`GET /admin/dashboard`, with a **Refresh** button):

| Area | Content |
|---|---|
| Counts | Staff accounts, Applicants, Jobs, Applications |
| Service health | "Checked live when this page loads". Cards for Backend API, Database, AI service, and **Verification agents** (only when the server reports `agentService`). Each shows UP, DOWN or UNCONFIGURED, with a hint when it is not UP. The **Background queue** card shows Pending, Failed and Dead; when Dead is above 0 it explains that affected resumes show "analysis failed" and can be retried. |
| Breakdowns | "Applications by status" (with "N received recently") and "Jobs by status", as proportional bars |

### 4.3.4 Jobs list (`/jobs`)

- **Counters:** five cards (Total, Published, Drafts, On hold, Closed). Each is its own `GET /jobs?page=0&size=1[&status]` request.
- **Filters:** title search (debounced 300 ms, kept as `?q=`) and a Status filter. The table is sorted newest first, 20 per page.
- **Table columns:** job title (with type and level), department, location, status badge, applicants, created, and **Open**. **Create job** appears for managers only.
- **Call:** `GET /jobs?page&size=20&status&search&sort=createdAt&direction=DESC`.

| Situation | Empty-state text |
|---|---|
| A filter is set | "No matching jobs" |
| No jobs, manager | "Create your first job draft…" |
| No jobs, recruiter | "Jobs created by your HR managers appear here." |

### 4.3.5 Create or edit a job (`/jobs/new`, `/jobs/:jobId/edit`)

**Position details.**

| Field | Rule |
|---|---|
| Job title | required |
| Department | required |
| Location | required |
| Employment type | required |
| Experience level | required. Hint: "Also used to flag excessive experience demands." |
| Open positions | required, at least 1 |
| Minimum / maximum salary | optional; maximum ≥ minimum |
| Application deadline | optional; must be in the future; entered in local time |
| Job description | required |

**Requirements editor** (`RequirementsEditor.tsx`). The section intro says: "Must-haves weigh double; a ranking gate holds an applicant out of the ranked group until it is verified — it never rejects anyone automatically." Each row has:

| Control | Details |
|---|---|
| Type | Skill, Experience, Education, Certification or Other |
| Requirement text | required, at most 500 characters |
| Order | Move up / Move down / Remove (each with an `aria-label`) |
| **Alternatives** | Chips input "Alternatives (any one satisfies it)": Enter or comma adds a chip, Backspace removes the last; at most 10 |
| Minimum years | 0–50, optional |
| Proficiency | Basic, Intermediate, Advanced or Expert |
| **Must-have** | checkbox |
| **Ranking gate** | checkbox, enabled only for a must-have; the knockout flag is sent as `mandatory && knockout` |

**Add requirement** adds a row that defaults to must-have, not a gate.

**Requirement compiler.** **Draft requirements from description** needs a title and at least 20 characters of description. It calls `POST /jobs/compile-requirements {title, description, experienceLevel}` and opens the **Suggested requirements** panel:

- **Engine:** "language-model assistant (checked against your text)" or "rules engine".
- **"Wording to reconsider (N)":** each warning shows its kind (Age-coded, Gender-coded, Degree inflation, Exclusionary, Vague or Excessive experience), the phrase, and a suggested rewrite.
- **Suggestions:** all pre-selected. Each shows its type, description, must-have / ranking-gate / years tags, proficiency, alternatives and a **rationale**.
- **Accept selected**, or with existing rows **Add selected** (skips duplicates by description) or **Replace current requirements**. **Dismiss** closes the panel. "Nothing is saved until you accept and save the job."

**Saving.**

1. **New job:** `POST /jobs` (always created as a DRAFT), then `PUT /jobs/{id}/requirements`. If the second call fails, the user is taken to the edit page with the unsaved rows carried over and the message "The draft was created, but its requirements could not be saved…".
2. **Edit:** `PUT /jobs/{id}`, then `PUT /jobs/{id}/requirements` **only if the rows changed**. Rows keep their server `id`, so rubric, kit and evidence links survive (STATUS §2.1 #1).
3. **Live job with changed requirements** (PUBLISHED or ON_HOLD): a confirmation first — "Change requirements of a live job? Saving creates requirements version N+1, is recorded in the audit log, and re-matches every applicant (count)…".
4. CLOSED and CANCELLED jobs show "This job can no longer be edited".

### 4.3.6 Job detail (`/jobs/:jobId`)

**Header.** `GET /jobs/{id}`.

- "JOB #id · REQUIREMENTS Vn", the title, tags (department, location, type, level, deadline), a status badge, and the closed reason if any.
- **Actions for managers:** **Edit job** (DRAFT, PUBLISHED or ON_HOLD) and status transitions, each behind a confirmation:

| Current status | Actions offered |
|---|---|
| DRAFT | Publish · Cancel draft · Delete draft (`DELETE /jobs/{id}`) |
| PUBLISHED | Put on hold · Close job |
| ON_HOLD | Resume publishing · Close job |

  Status changes call `PATCH /jobs/{id}/status`.
- **Export audit CSV** (managers): `GET /hr/jobs/{id}/audit-export`. The file name comes from `Content-Disposition`; the fallback is `job-{id}-audit.csv`.

**Tabs** (ARIA tabs, kept in `?tab=`).

**Applicants tab** (`GET /hr/jobs/{id}/applicants?page&size=25&status&sort=rank|appliedAt`):

- **Intro:** "Coverage is the share of weighted requirements with evidence — not a probability of success. Humans make every decision."
- **Toolbar:** Status filter, Order ("Evidence ranking" or "Application date"), and a count.
- **Grouping:** with ranking order, rows are grouped by the server's `rankGroup`:
  - **Ranked** — "Every ranking-gate requirement is verified. Ordered by verified coverage, then claimed coverage, then application date."
  - **Must-haves not yet verified** — "…Not rejected — check the evidence, a claim, a work sample or an interview."
  - **Unscored** — "No match result yet…"
- **Each row:**
  - a rank number (`#n`, RANKED rows only);
  - the display name ("Candidate #A-…" while blind) and a **Blind** pill;
  - a status badge, the applied date, the work-sample status and the interview count;
  - resume flag chips;
  - **CoverageBars** showing "Verified" and "Incl. claims" with percentages, and "N ranking gates not yet verified";
  - or, while there is no score, a match note: "Matching…", "No requirements to match against", "Resume not analysed yet" or "Matching failed".
- **Re-run matching** (managers; disabled without requirements) calls `POST /hr/jobs/{id}/rematch` → "Re-matching N applications…".
- **Polling:** see 4.1.5.

**Work samples tab:**

- **List:** `GET /hr/jobs/{id}/work-samples`. Each card shows its title, time limit, number of rubric criteria and last update. **View instructions and rubric** expands the details.
- **Generate work sample** (managers; needs requirements) calls `POST …/work-samples/generate`, creates a DRAFT and opens the editor.
- **Editor** (DRAFT only):
  - Title.
  - Time limit: 5–480 minutes; hint "Submissions are accepted for 5 minutes after the limit."
  - Instructions; hint "Hidden from the candidate until they press Start."
  - **Rubric:** each criterion has a name, an optional "Evidence for requirement" link, and four level anchors (1 = weak … 4 = strong); all four are required.
  - Saving calls `PUT …/work-samples/{wsId}`.
- **Status:**
  - **Activate**: "…can no longer be edited, so every candidate gets the same task."
  - **Archive**: "…Existing assignments and scores are kept."
  - Both call `PATCH …/work-samples/{wsId}/status`.
- **Assignments table:** `GET /hr/jobs/{id}/work-sample-assignments?status`, defaulting to "Submitted (to score)". Columns: application link, work sample, status, start by, submitted, score. There are **no** cancel or download buttons here; those are on the applicant page.

**Interview kit tab:**

- **Load:** `GET /hr/jobs/{id}/interview-kit`. A 404 means "No interview kit yet".
- **Generate kit** calls `POST …/interview-kit/generate`. **Regenerate** asks first: "The current questions, including your edits, are replaced. Submitted scorecards keep their ratings."
- **View:** the source, update time and question count. Each question shows "Probes: <requirement>", its follow-ups and its four rating anchors.
- **Edit questions:**
  - Each question can be reordered and removed. It has its text, a "Probes requirement" link, follow-ups (add or remove) and 4 anchors (all required).
  - Saving calls `PUT …/interview-kit {questions}`.

**Fairness tab** (managers only; `GET /hr/jobs/{id}/fairness?from&to`):

- **Date range:** "From" and "To" date inputs (inclusive, UTC) with **Apply** and **All time**. The range is kept in `?from&to`; an invalid value in the URL falls back to all time.
- **Report:**
  - Scope; survey response rate; "Smallest group shown: 5 people".
  - An explainer:
    - **Selection rate** is the share of a group that reached the stage, and **"N reached"** is a count of people, not the group size.
    - The **adverse-impact ratio** is the lowest rate divided by the highest. Below 0.80 (the four-fifths rule) the row is **flagged**: "a reason to look closer, not proof of bias".
    - Groups smaller than the minimum are shown as "<5".
  - One table per dimension (Gender, Age band, Disability, Social category). Each row is a stage; each column is a group with its rate and "N reached"; the last column is the impact ratio.
  - **Empty:** "No survey responses yet…"

**Details tab:** the description, the requirements with alternatives and tags, and role facts (type, level, salary, openings, deadline, published, created by, last updated). With no requirements: "Applicants cannot be ranked until requirements exist."

### 4.3.7 Applicant detail (`/jobs/:jobId/applicants/:applicationId`)

Calls: `GET /hr/jobs/{j}/applicants/{a}` (polled while matching) and `GET /jobs/{j}` (for the title and requirement names).

**Header.**

- "<JOB> · APPLICATION #A-id", the display name, **Blind review** pill, applied and status-change times, "Rank #n", and a status badge.
- **Decision bar** (role group "Decisions"):
  - "Matching is slow — check again" after the polling window ends.
  - **Retry analysis** when analysis or matching failed (managers): `PATCH …/analysis/retry`.
  - **Reveal identity** (managers): shown when the identity is still hidden and the server's `revealAllowed` is true. If the server does not send `revealAllowed`, the button is shown when the status is not SUBMITTED.
  - **Decision buttons** in the order Shortlist, Move to interview, Select, Undo shortlist, Reopen, Reject — but only the actions in the server's `allowedActions`, and only for managers.
- **Decision dialogs:** each decision opens a `TextPromptDialog` with a comment field, then `POST …/decisions {action, comment}`. **Undo shortlist** and **Reopen** require a comment. The dialog texts:

| Action | Dialog text |
|---|---|
| Shortlist | "Shortlisting reveals the candidate's identity, resume and cover letter to the team. The reveal is audited." |
| Reject | "The candidate is notified. You can reopen it later with a reason." |
| Move to interview | "Scheduling the first interview also does this automatically." |
| Select | "Selected is the final stage in RecruitPro; the hire is handed off to your offer process." |

- **Final states:** SELECTED shows "Selected: handed off to your offer process."; WITHDRAWN shows "The candidate withdrew this application."
- **Reveal identity** asks first: "Name, contact details, resume and cover letter become visible to everyone reviewing this job. The reveal is recorded in the audit log with your name." Then `POST …/reveal`.

**Main column, tab "Evidence and assessments":**

1. **Requirement evidence.**
   - **Summary:** CoverageBars ("must-haves count double"), the rank-group sentence, "Algorithm <version> · calculated <time>", the resume analysis status, and flag chips with explanations.
   - **"How the evidence ladder works"** (expandable). HR tier labels: Claimed → Resume → Corroborated → Demonstrated (work sample scored 3–4) → Interview-verified (rating 3–4).
   - **Table**, one row per requirement:
     - the requirement, its alternatives and tags;
     - a status: **Matched**, **Unclear** or **Not observed**;
     - its tier;
     - the evidence quote with its section, the explanation, the source (resume text, the candidate's own claim, a claim checked by a recruiter, a scored work sample, or a structured interview), and "**To verify:** <hint>".
     - Rows whose ranking gate is still open are highlighted.
2. **Candidate claims.**
   - Each claim shows its type, value and tier.
   - Its evidence link, or "Evidence link hidden until identity is revealed", or "No evidence link".
   - When it was added, and Verified / Not verified with the time checked and the note.
   - **Verify** and **Not verified** (recruiters and managers) open a note dialog (optional, at most 500 characters), then `POST …/claims/{c}/verification {verified, note}`. Verify raises the claim to Corroborated, recorded with the reviewer's name.
3. **Work samples.**
   - **Assign work sample** (managers; status SHORTLISTED or INTERVIEWING; only ACTIVE samples not already assigned). The dialog asks for the work sample and an optional "Start by" time in the future, then calls `POST …/work-samples {workSampleId, dueAt}`.
   - **Each assignment card:**
     - assigned, start-by, started and submitted times; status; "Score x.xx / 4 · scored by …".
     - **View submission**: the response text, the submitted link, and the **submitted files** with a **Download** button each (recruiters and managers; `GET /hr/work-sample-assignments/{id}/files/{fileId}`, audited).
     - **Score with rubric** (SUBMITTED; recruiters and managers). The dialog shows a submission preview and a 1–4 `RatingScale` per criterion with its anchors. Every criterion is required; the note is optional. Call: `POST /hr/work-sample-assignments/{id}/score`.
     - **Cancel assignment** (managers; ASSIGNED, STARTED, SUBMITTED or SCORED). A reason is required (at most 1,000 characters). The dialog says: "The candidate is told the work sample is no longer needed. A submission and its files are kept for the record." Call: `POST …/{id}/cancel {reason}`.
     - Cancellation details: date, by whom, reason. Per-criterion scores and notes.
4. **Interviews.**
   - **Schedule interview** (managers; status SHORTLISTED or INTERVIEWING). The dialog asks for:
     - an interviewer from `GET /hr/interviewers` (name, role, email);
     - a date and time in the future (local time; stored in UTC);
     - a duration of 5–480 minutes (default 45);
     - an optional location or video link.
     Call: `POST …/interviews`. Scheduling the first interview moves the application to INTERVIEWING.
   - **Each interview card:** time and duration, interviewer and location, status.
     - **Open interview** (managers or the assigned interviewer).
     - **Scorecard: <recommendation>** expands the read-only scorecard; the kit is loaded for the question text.
     - **Reschedule** (SCHEDULED; managers) calls `PATCH /interviews/{id}` with changed fields only; an emptied location is sent as `""`. The interviewer cannot be changed: "cancel this interview and schedule a new one".
     - **Cancel interview** calls `PATCH /interviews/{id} {status:'CANCELLED'}`.
   - **Scorecards cannot be edited in the UI** (see 4.5.2).

**Main column, tab "Verification"** (recruiters and managers; `GET /hr/applications/{id}/verification`):

- **SHADOW mode:** only a banner: "Shadow mode. Verification is in shadow mode for your organisation; results are hidden until an admin switches it on."
- **VISIBLE mode:** results the candidate **shared**, grouped by claim (type, "Candidate claim" or "From the resume", value, tier). Each verdict card shows:
  - the verdict chip;
  - **Ownership proven** (🔒) or **Ownership not proven** ("…capped at Partly verified");
  - the source kind, verifier and time;
  - **Why** (reasons with quotes and evidence keys);
  - **Worth asking about** (integrity notes: "Observations for a conversation, not accusations.");
  - **Suggested interview question**;
  - **Evidence**: the items after reveal, otherwise "N evidence items. Reveal the candidate to see sources.";
  - the **Candidate's note**;
  - **Your organisation's review**: "Confirmed / Overruled by … on …", or "Not reviewed by your organisation yet. Only a confirmed, verified result with proven ownership raises the evidence tier."
- **Confirm / Overrule** (or "Review again: …") opens the review dialog:
  - Choices: "Confirm: the result matches what I see" and "Overrule: I disagree with the result".
  - The note is required for Overrule (at most 1,000 characters).
  - Call: `POST /hr/verdicts/{id}/review {decision, note}`. The applicant is then re-matched and all views refresh.
- **Empty:** "No shared verification results… This says nothing for or against the candidate."

**Side column.**

- **Candidate identity.**
  - **While blind:** "Name, contact details, resume file and cover letter stay hidden so the first review rests on evidence alone", with **Reveal identity** where allowed.
  - **After reveal:**
    - name, email (`mailto:`), location, current role, "Experience (self-reported)", LinkedIn and portfolio links;
    - **Download resume (<file>)** (recruiters and managers; `GET …/resume`; "recorded in the audit log");
    - the cover letter.
- **Review note** (recruiters and managers): "Visible to your team; saved in the history." Call: `PUT …/review-comment`.
- **Decision history**, newest first. Each entry shows the action ("Shortlisted", "Identity revealed", "Review note"…), the status transition, the actor and time, "on match run #n" and the comment.

### 4.3.8 My interviews and interview detail

**`/interviews`** (`GET /interviews/mine`) shows three tables:

| Table | Contents |
|---|---|
| **Waiting for your scorecard** (highlighted) | SCHEDULED, start time passed, no scorecard yet |
| **Upcoming** | SCHEDULED, in the future |
| **Completed and cancelled** | Everything else |

Columns: candidate, job, when, where, status ("Completed" when a scorecard exists), and **Open** or **View**.

**`/interviews/:id`** (`GET /interviews/{id}`):

- **Header:** the job, the candidate label, time, duration, interviewer ("you"), location or **Join link ↗**, and status. **Open the application** appears only for roles with `jobs.view`, so not for interviewers.
- **"Focus on these requirements":** requirements where the evidence is weak or only claimed, each with its tier, tags and a verification hint.
- **Scorecard form** (the assigned interviewer only, while SCHEDULED, until a scorecard exists):
  - for each question: the question number and text, follow-ups (collapsible), a **required** 1–4 rating with its anchors, and notes (at most 2,000 characters);
  - **Overall recommendation** (required): Strong no, No, Yes or Strong yes;
  - a summary (at most 4,000 characters).
- **Submitting** asks "Submit your scorecard? … You cannot change it afterwards. Ratings of 3 or 4 on requirement-linked questions count as interview-verified evidence.", then calls `POST /interviews/{id}/scorecard`.
- **Otherwise:** the questions are shown read-only, and the side panel shows the scorecard or why there is none.

### 4.3.9 Notifications inbox (`/notifications`)

- **List:** 20 per page. Each item shows its subject, a template label (Interview assigned, Interview scheduled, Interview cancelled, Application received, Application update, Work sample assigned; anything else shows as "Notice"), the time and the body.
- **Actions:** **Mark as read** and **Mark all as read**.
- **Calls:** `GET /me/notifications`, `PATCH …/{id}/read`, `POST …/read-all`.

### 4.3.10 Account: password and two-step verification

- **`/account/password`:**
  - Fields: current password, new password (must differ from the current one), confirm.
  - Call: `POST /me/password`. The returned session is adopted.
  - Notice: "Password changed. Other sessions were signed out."
- **`/account/security`** (`GET /me/mfa`):

| State | What the page shows and does |
|---|---|
| Not available | "Two-step verification is not available on this server yet (no encryption key is configured)." |
| Off | Optional banner "Your organisation requires two-step verification…". **Set up two-step verification** calls `POST /me/mfa/enroll` and shows the secret and URI (no QR code). A 6-digit code then **Turn on** calls `POST /me/mfa/confirm {code}`, and the recovery codes are shown **once**. |
| On | Turned-on date and "Recovery codes left", with a warning at 2 or fewer. **Create new recovery codes** (with confirmation: "current codes stop working") calls `POST /me/mfa/recovery-codes`. **Turn off** needs the password and a code or recovery code (`POST /me/mfa/disable`). When the organisation requires MFA, the turn-off form is replaced by an explanation. |

### 4.3.11 Administration

**Staff accounts (`/admin/users`):**

- **Filters:** search by name or email (debounced), Role, Status (Enabled or Disabled). 20 per page.
- **Table:** name, email, role, status and joined date.
  - Another user's role is changed with an inline **role select**; the user's own role cannot be changed here.
  - **Disable** / **Enable** buttons (not offered on your own account).
  - Every change is confirmed first, for example "…Their access changes on their next request." or "…is signed out and cannot sign in until re-enabled."
- **Calls:** `PATCH /admin/users/{id}/role`, `PATCH /admin/users/{id}/status`.
- The page states: "An organisation always keeps at least one enabled administrator."

**Invitations (`/admin/invitations`):**

- **Invite form:** work email and role. Each role has a help line, for example RECRUITER: "Reads jobs and applicants, comments, verifies claims, scores work samples." Call: `POST /admin/invitations`.
- **Sent invitations table:** email, invited by, role, status (Pending, Accepted, Revoked or Expired; derived from timestamps if the server does not send it), invited and expires.
- **Copy link** appears when the server returned an `inviteUrl` (development setups).
- **Revoke** (with confirmation) calls `DELETE /admin/invitations/{id}`. Links are valid for 7 days and work once.

**Organisation settings (`/admin/organization`):** `GET` / `PATCH /admin/organization`; only changed fields are sent, and every change is audited.

| Setting | What it does |
|---|---|
| Organisation name | Shown to candidates; the slug identifier appears in the hint |
| **Blind review** | Explained in full on the page, including the bias risk of turning it off |
| **Share verification status with candidates** | Controls the "Requirements and verification" section on the candidate's application page. Candidates "never see scores, ranks, reviewer notes or other applicants." |
| **Require two-step verification for all staff** | Staff without MFA must enrol at their next sign-in; open sessions are not ended. Needs the server's encryption key. |
| **Single sign-on: allowed e-mail domains** | Chips; at most 20; exact domains, no wildcards. "No accounts are created automatically. Leave empty to turn single sign-on off." |

**Job oversight (`/admin/jobs`):**

- **Table:** every job, with owner email, status, application count and created date. Search and status filter.
- **Override status** opens a dialog with the new status and a **required reason** (at most 500 characters), then calls `PATCH /admin/jobs/{id}/status {status, reason}`.
- **Allowed overrides:** PUBLISHED → ON_HOLD or CLOSED; ON_HOLD → CLOSED; DRAFT → CANCELLED. CLOSED and CANCELLED show "Final" (audit H7).

**Audit log (`/admin/audit`):**

- **Export as CSV:** From and To dates (local days), pre-filled with the last 30 days. Call: `GET /admin/audit-export?from&to`. "The export itself is recorded in the audit log."
- **Events table** (`GET /admin/audit-logs?page&size=25&action`): when, action, actor (or "System"), target, details, IP.
- **Action filter:** a fixed list of 22 actions (see 4.7).

**Fairness (`/admin/fairness`):** the same report and date-range filter as the job Fairness tab, for the whole organisation (`GET /admin/fairness?from&to`).

**Verification (`/admin/verification`)** — ADMIN and HR_MANAGER. Three sections:

1. **Mode and connectors** (ADMIN only; `GET` / `PUT /admin/verification/settings`).
   - **Mode:**
     - **Shadow**: "Agents run, results are hidden from reviewers and never change rankings."
     - **Visible**: "Reviewers see results candidates shared. A verified result raises the evidence tier only after a reviewer confirms it."
   - **Connectors:** six toggles (GitHub, GitLab, Website, Document, Open Badge, Reference). "A disabled connector's results are hidden from your reviewers and never affect your rankings. Candidates can still connect it."
   - The page shows when the settings were last changed and by whom.
   - Switching to Visible while metrics say `readyForVisible = false` asks first: "Switch to visible before the targets are met? … at least 150 labels, false-corroboration rate at most 2%, kappa at least 0.70", with the button **Switch to visible anyway**.
2. **Agreement with your reviewers** (`GET /admin/verification/metrics`).
   - A readiness pill: "✓ Ready for visible mode" or "○ Not ready…".
   - Figures: labelled verdicts, agreement, false-corroboration rate and Cohen's kappa. The server sends rates as 0–1; the page shows them as percentages.
   - A readiness checklist with the three targets.
   - The page explains: "False corroboration: the agent said 'Verified' where your reviewer did not… it would raise a candidate unfairly."
   - Tables **By verifier** (labelled, agreement, false corroboration, kappa) and **By verdict** (agent said, reviewers said, both, precision, recall).
3. **Shadow queue** (`GET /admin/verification/shadow-queue?page&size=10`; page kept in `?page`).
   - Shared results from applicants to this organisation's jobs, unlabelled first.
   - Each item shows the claim, the agent's verdict chip, ownership, source, verifier, time and label count, plus the reasons, notes, question and evidence (hidden until the candidate is revealed on their application).
   - **Your verdict** is a 5-option `VerdictPicker`, with an optional note (at most 1,000 characters). **Save label** / **Update label** calls `POST /admin/verification/verdicts/{id}/label`. The item updates in place and the metrics are fetched again.
   - **Known gap:** an HR_MANAGER cannot see the current mode on this page, because the settings GET is ADMIN-only.

---

## 4.4 End-to-end user journeys

Each journey is written as numbered steps with a **lane**, so it can be drawn as a swimlane diagram. Lanes:

| Lane | Who |
|---|---|
| CAN | Candidate (candidate portal) |
| REF | Referee (public page) |
| HRM | HR manager (HR portal) |
| REC | Recruiter (HR portal) |
| INT | Interviewer (HR portal) |
| ADM | Admin (HR portal) |
| SYS | Backend, background queue, AI service or agent service |

### (a) A candidate applies and is selected ("hired")

RecruitPro's last stage is **SELECTED**. Offers are outside the product ("handed off to your offer process").

| # | Lane | Step | Screen → call |
|---|---|---|---|
| 1 | CAN | Creates an account | `/register` → `POST /auth/register` → lands on `/jobs` |
| 2 | SYS | Emails a 24-hour verification link | — |
| 3 | CAN | Opens the link | `/verify-email` → `POST /auth/verify-email` |
| 4 | CAN | Uploads a resume | `/resumes` → `POST /candidates/me/resumes` |
| 5 | SYS | Parses the resume in a queued job (with retries) | — |
| 6 | CAN | Sees the analysis update by itself; may **Suggest a correction**, which becomes a SELF_CLAIMED claim | `GET …/analysis` (polled), `PATCH …/facts/{id}` |
| 7 | CAN | Searches and opens a job | `/jobs` → `/jobs/:id` |
| 8 | CAN | **Apply now**: chooses a resume, adds an optional cover letter, reviews, submits | `POST /jobs/{id}/applications` |
| 9 | SYS | Queues matching; the application gets coverage and a rank group | — |
| 10 | HRM | Opens the job's **Applicants** tab and sees "Candidate #A-…" with a **Blind** pill, coverage bars and the rank group | `GET /hr/jobs/{id}/applicants` |
| 11 | HRM | Opens the applicant and reads the evidence table and claims | `GET /hr/jobs/{j}/applicants/{a}` |
| 12 | HRM | **Shortlist** (optional comment) | `POST …/decisions {SHORTLIST}` |
| 13 | SYS | Reveals the identity automatically (audited) and notifies the candidate | — |
| 14 | HRM | Sees the name, resume download and cover letter. **Assign work sample** with a start-by time (an ACTIVE sample must exist) | `POST …/work-samples` |
| 15 | CAN | Gets a notification; opens `/work-samples/:id`, presses **Start** (the timer begins), writes the answer, attaches files, submits | `POST …/start`, `POST …/submit` |
| 16 | REC/HRM | **Score with rubric** (1–4 per criterion) | `POST /hr/work-sample-assignments/{id}/score` |
| 17 | SYS | Re-matches: requirements linked to a criterion scored 3 or 4 become **Demonstrated** | — |
| 18 | HRM | **Schedule interview** | `POST …/interviews` |
| 19 | SYS | Moves the application to INTERVIEWING; notifies the interviewer (INTERVIEW_ASSIGNED) and the candidate (INTERVIEW_SCHEDULED) | — |
| 20 | CAN | Sees the interview in local time | `/interviews` |
| 21 | INT | Opens **My interviews**, then the interview; reads the "Focus on these requirements" list; after the interview, rates each question and gives a recommendation | `POST /interviews/{id}/scorecard` |
| 22 | SYS | Requirement-linked ratings of 3 or 4 become **Interview-verified** | — |
| 23 | HRM | Reads the scorecard on the applicant page; **Select** | `POST …/decisions {SELECT}` |
| 24 | CAN | Gets a notification; the timeline shows "Selected. The employer will contact you about next steps." | `/applications/:id` |

### (b) A recruiter screens with evidence; a manager decides

| # | Lane | Step | Screen → call |
|---|---|---|---|
| 1 | REC | Opens **Jobs**, then a job. Order is "Evidence ranking" by default | `/jobs/:id?tab=applicants` |
| 2 | REC | Reads the groups: **Ranked** (gates verified), **Must-haves not yet verified** (not rejected), **Unscored** | — |
| 3 | REC | Opens an applicant in the "Must-haves not yet verified" group | applicant detail |
| 4 | REC | In **Requirement evidence**, finds the gate row still open; reads the quote, the explanation and "To verify: …" | — |
| 5 | REC | In **Candidate claims**, opens the evidence link if one is visible (some links stay hidden until reveal); **Verify** with a note ("certificate number confirmed with issuer") | `POST …/claims/{c}/verification {verified:true, note}` |
| 6 | SYS | The claim becomes Corroborated (audited); matching re-runs; the applicant may move into **Ranked** | — |
| 7 | REC | In VISIBLE mode, opens the **Verification** tab; reads the shared verdicts; **Confirm** a Verified result with proven ownership (or **Overrule** with a required note) | `POST /hr/verdicts/{id}/review` |
| 8 | REC | Writes a **Review note** for the team | `PUT …/review-comment` |
| 9 | HRM | Opens the same applicant. **Shortlist**, or **Reject** with a comment | `POST …/decisions` |
| 10 | HRM | Later, if needed, **Reopen** or **Undo shortlist** (a comment is required) | `POST …/decisions` |
| 11 | HRM | **Export audit CSV** for the job: decisions together with the match run and algorithm they were made on | `GET /hr/jobs/{id}/audit-export` |

### (c) A candidate strengthens an application through verification and a reference

| # | Lane | Step | Screen → call |
|---|---|---|---|
| 1 | CAN | Opens **Strengthen your application**; reads "How it works" | `/verification` |
| 2 | CAN | Connect a source: GitHub, ticks consent, **Sign in with GitHub** | `GET …/oauth/github/start` → GitHub |
| 3 | SYS | Callback: the source is created with ownership proven by OAuth; redirect to `/verification?connected=github` | — |
| 3b | CAN | (Alternative) GitHub OAuth not configured: connects by **username**; the ownership dialog opens; copies the code into the GitHub bio; **Check now** | `POST …/sources`, `POST …/ownership-check` |
| 4 | CAN | **Run verification** on the source; the card shows "Checking now" | `POST …/sources/{id}/runs` |
| 5 | SYS | Agent service checks the claims against the source; verdicts are written; the candidate is notified (VERIFICATION_READY) | — |
| 6 | CAN | Sees "A verification finished…". Under **Your results**, opens a verdict and reads Why, Evidence (with fingerprint) and Notes for context | `GET …/ledger`, `GET …/verdicts/{id}` |
| 7 | CAN | **Share with employers**, adding a note if wanted. A "Contradicted" result can be hidden or re-checked with a note instead | `POST …/verdicts/{id}/response {SHARE}` |
| 8 | CAN | **References**: enters the referee's name, work email, relationship and employer; selects claims; ticks consent; **Send request** | `POST …/references` |
| 9 | SYS | Emails the referee a one-time link `<candidate-url>/reference/<token>`, valid 14 days | — |
| 10 | REF | Opens the link without signing in; answers Confirm, Partly, Cannot confirm or Disagree per claim; ticks the identity box; **Send answers** (or declines) | `GET` / `POST /public/references/{token}` |
| 11 | SYS | Creates reference verdicts; notifies the candidate (REFERENCE_ANSWERED) | — |
| 12 | CAN | Sees the request as "Answered · N results"; reviews and shares those results like any other | ledger |
| 13 | HRM/REC | In VISIBLE mode the shared results appear on the applicant's **Verification** tab; sources and evidence are visible only after reveal; **Confirm** raises the tier to Corroborated for this organisation only | `GET /hr/applications/{id}/verification` |

### (d) An admin moves verification from shadow to visible

| # | Lane | Step | Screen → call |
|---|---|---|---|
| 1 | ADM | Opens **Administration › Verification**. Mode is **Shadow**; connectors are toggled as wanted; **Save changes** | `PUT /admin/verification/settings` |
| 2 | SYS | Agents keep running; reviewers see only the shadow-mode banner on applicants; rankings are not affected | — |
| 3 | HRM/ADM | Work through the **Shadow queue**: for each shared result, read reasons and evidence, choose a verdict in the 5-option picker, **Save label** | `POST /admin/verification/verdicts/{id}/label` |
| 4 | SYS | Metrics recompute | `GET /admin/verification/metrics` |
| 5 | ADM | Watches **Agreement with your reviewers**: labelled ≥ 150, false corroboration ≤ 2%, kappa ≥ 0.70; the pill turns "✓ Ready for visible mode"; checks the per-verifier and per-verdict tables | — |
| 6 | ADM | Optionally disables a weak connector (its results stay hidden from reviewers) | settings |
| 7 | ADM | Chooses **Visible**, **Save changes**. If the targets are not met, a confirmation explains the risk ("Switch to visible anyway") | `PUT /admin/verification/settings` |
| 8 | SYS | The change is audited; affected candidates are re-ranked | — |
| 9 | REC/HRM | The applicant **Verification** tab now lists shared results with Confirm / Overrule | — |

---

## 4.5 UX principles and known gaps

### 4.5.1 Principles you can see in the product

| Principle | Where it shows |
|---|---|
| **Blind-first review** | Applicants appear as "Candidate #A-…" with a **Blind** pill. Name, contact details, resume file, cover letter, claim evidence links and verification sources stay hidden until shortlisting (automatic reveal) or a manager's audited **Reveal identity**. The candidate is told the same: "Recruiters see your cover letter after your application is shortlisted." |
| **Evidence over assertion** | Two coverage numbers: **Verified** (resume or stronger) and **Incl. claims**. Corrections become claims, never edits of what was extracted. The five-tier ladder appears in both portals with plain descriptions. |
| **No automatic rejection** | A "Ranking gate" holds an applicant in "Must-haves not yet verified": "Not rejected." The candidate's job page says: "A missing must-have does not reject you automatically; a person reviews every application." Every decision is a human button with a comment and an entry in the history. |
| **Neutral language for absence** | "Not found in connected sources" (○, neutral tone) "never lowers your score or ranking". "Not checkable by agents" is neutral. Parser flags are "not accusations". Integrity notes are "Observations for a conversation, not accusations". An empty Verification tab "says nothing for or against the candidate". The referee is told "'Cannot confirm' is a perfectly good answer". |
| **Consent before reading or storing** | A required consent tick for every source kind, for reference emails and for the survey. The survey is voluntary, separate, shown only in aggregates with groups of 5 or more, and deletable. Revoking a source deletes its evidence and withdraws its results. |
| **Candidate preview and control** | Verification results are "Awaiting your review" and visible only to the candidate until **Share with employers**. The candidate can **Hide**, **Re-check** and add a "Note for employers". The apply dialog has a review step. The work-sample rules are shown before Start. |
| **Plain-language status** | REJECTED is shown as "Not progressed". Work samples say "start by" (not "due"). Times are shown in local time with the time zone stated. "Coverage… not a probability of success". The fairness flag is "not proof of bias". |
| **Confirm destructive or irreversible actions** | Withdraw, delete claim, revoke source, delete account, decisions, reveal, close job, regenerate kit, cancel assignment or interview, role and status changes, switching to visible. Destructive confirmations start with focus on Cancel. |
| **Accountability** | Reveal, resume download, claim verification, scoring, overrides and exports are audited, and the UI says so at the moment of action. |

### 4.5.2 Known UX gaps

The first two groups are stated in the plan notes; the third group was found while writing this chapter.

**Listed as "Not done" in `WP-FE-FINISH-NOTES.md`:**

1. **No QR code for MFA enrolment.** The secret and the `otpauth://` URI are shown as selectable text only.
2. The **job Work samples tab** assignments table has no cancel or download buttons; those are on the applicant page.
3. The candidate **`/work-samples` list** does not show attachment counts; the response page does.
4. **No browser or end-to-end test run, and no live backend run** of the latest frontend work.
5. An **HR_MANAGER on `/admin/verification`** cannot see the current mode, because the settings GET is ADMIN-only.

**Listed in the earlier notes and still true in the code:**

- No "already applied" indicator on jobs; a duplicate application shows the server's 409 message.
- Work-sample drafts are lost on reload; the page warns before leaving.
- Candidates cannot change their name.
- No internationalisation.
- Fonts are referenced but not loaded.
- No route-level code splitting.
- No unit-test runner and no ESLint in either portal.

**Found in the code while writing this chapter:**

- **Scorecard editing has no UI.** The backend `PUT /interviews/{id}/scorecard` and the client function `interviewsApi.updateScorecard` exist, but nothing calls the function. The form says "Scorecards cannot be edited after submission."
- **Staff email-verification link is broken** (details in 4.7).
- **An interview's interviewer cannot be changed**; the user must cancel and schedule again.
- **Notifications are not links** in either inbox: subject and body only.
- **Jobs require sign-in.** There is no public job board.
- **No dark mode** in either portal.
- **The candidate work-sample rules still say** "add a link if the task asks for a file", although file attachments are now supported.
- **Audit export dates** are pre-filled with the last 30 days, but the hint says "Leave both empty for the last 90 days". The audit export uses local days, while the fairness filter uses UTC days.
- **The SSO-domains chips field reuses `ChipsInput`.** Its hidden screen-reader hint says "Any one alternative satisfies the requirement", which is wrong in that context.
- **The HR password check accepts only ASCII letters**; the candidate check accepts any Unicode letter.
- **The Jobs list fires 5 extra counter requests** on every visit (cached for 30 s).

---

## 4.6 Likely demo questions with answers

1. **Why are there two portals instead of one app with roles?**
   Different audiences, and separate sessions. Each portal sends `X-RecruitPro-Portal`, gets its own httpOnly refresh cookie (`rp_refresh_candidate` / `rp_refresh_staff`), and the server refuses the wrong account type with 403 before any session is created. One browser can be signed in to both.

2. **Where is the token stored? Can XSS steal it?**
   The access token lives only in a JavaScript module variable. Nothing is put in local or session storage. The refresh token is in an httpOnly cookie, so scripts cannot read it. The CSP limits scripts to the site's own origin.

3. **What happens when the access token expires during work?**
   The first 401 starts **one** shared refresh. Every waiting request then retries once with the new token. If the refresh is refused, the user is signed out with "Your session has ended". A network error or 5xx never signs anyone out.

4. **Why does the recruiter see "Candidate #A-12" and not a name?**
   Blind-first review. Identity, contact details, the resume file and the cover letter are hidden until shortlisting, which reveals automatically, or until a manager uses the audited **Reveal identity**. An admin can turn blind review off in Organisation settings; the page explains the bias risk of doing so.

5. **Can a recruiter reject someone?**
   No. Decisions belong to ADMIN and HR_MANAGER (`applicants.decide`), and the buttons shown are also limited by the server's `allowedActions`. Recruiters verify claims, score work samples, review verdicts and write notes.

6. **Does a missing must-have reject the candidate automatically?**
   No. A "Ranking gate" only keeps the applicant in the "Must-haves not yet verified" group, labelled "Not rejected". The candidate is told this on the job page.

7. **What do the two coverage bars mean?**
   **Verified** is the share of weighted requirements (must-haves count double) backed by the resume or stronger evidence. **Incl. claims** also counts the candidate's unverified claims. Neither is a probability of success.

8. **If a candidate corrects the AI's extraction, can they inflate their score?**
   No. A correction becomes a SELF_CLAIMED claim, labelled "Your claim — recruiters see it as unverified until they check it". The extracted value is kept, and ranking uses verified coverage.

9. **What does "Not found in connected sources" mean? Is it bad?**
   It is neutral. The connected sources simply did not mention the claim, and it never lowers a score or rank. The ledger states this on the page.

10. **Can employers see a verification result the candidate doesn't like?**
    Only if the candidate shares it. Results start as "Awaiting your review". The candidate can Share, Hide or Re-check, and can add a note. In shadow mode reviewers see nothing at all.

11. **How does a candidate prove a GitHub account is theirs?**
    Either by signing in with GitHub (OAuth proves it immediately), or by pasting a challenge code into the profile bio and pressing **Check now**. A website can be proven with a DNS TXT record, a `.well-known` file or a code on the page. A source whose ownership is not proven can give at most "Partly verified".

12. **What is shadow mode and why have it?**
    Agents run, but reviewers do not see the results and rankings are unaffected. Managers label results in the shadow queue. Visible mode is recommended once there are at least 150 labels, a false-corroboration rate of at most 2% and kappa of at least 0.70. Switching before that needs an explicit "anyway".

13. **Does a "Verified" verdict change the ranking by itself?**
    No. In visible mode a reviewer must **Confirm** a Verified result with proven ownership. Only then does the claim rise to Corroborated, and only for that organisation. Overruling requires a note.

14. **How do references work without the referee having an account?**
    The referee gets a one-time emailed link to `/reference/<token>`, valid for 14 days. They answer per claim, confirm their identity and submit, or decline. An expired or used link shows "This request is closed". A candidate can have at most 5 open requests at once.

15. **How are work samples kept fair?**
    Once activated, a sample cannot be edited, so every candidate gets the same task. The instructions are hidden until Start. The timer cannot be paused, with a 5-minute grace period. Each response is scored 1–4 per criterion against written anchors.

16. **What does the interviewer see?**
    Only their own interviews; there is no applicant list. For each interview they see the kit questions, a "Focus on these requirements" list where the evidence is weak, a rating scale with anchors per question, and a required overall recommendation. Requirement-linked ratings of 3 or 4 count as Interview-verified.

17. **How is fairness monitored without exposing personal data?**
    Through a voluntary, consented and deletable survey that is stored separately. The reports show selection rates per group and stage and the adverse-impact ratio, with rows below 0.80 flagged. Groups smaller than 5 are suppressed. Individual answers are never shown.

18. **What happens if the AI service is down?**
    Parsing and matching wait in the background queue and retry with backoff. The candidate sees "Queued…", and after 2 minutes a "Check again" button. The admin overview shows AI service DOWN and the queue's Pending, Failed and Dead counts. HR can **Retry analysis**.

19. **Is MFA supported? SSO?**
    Yes, for staff. TOTP with recovery codes can be made mandatory per organisation, which forces enrolment at the next sign-in. OIDC SSO buttons appear when providers are configured, limited to the email domains on the admin's allow-list, and no accounts are created automatically. There is no QR code yet; the setup key is typed in or the link is pasted.

20. **Can a candidate delete their data?**
    Yes. Privacy offers a JSON export and account deletion with the password. Profile, resumes, facts, claims and survey answers are removed; past applications and decisions are kept anonymised, because employers must keep hiring records.

21. **Is everything audited? Can I see it?**
    Reveals, resume downloads, decisions, claim verification, scoring, overrides, requirement changes, MFA and SSO events and exports are all recorded. ADMIN browses them in Audit log and exports CSV. HR managers export a per-job CSV that shows which match run each decision was made on.

22. **Is it accessible?**
    Labelled fields with hints and errors linked to them, focus-managed modals, Cancel-first destructive confirmations, live regions for notices, a skip link, focus moved to the new page on navigation, ARIA tabs, verdict and status chips that use symbols as well as colour, and reduced-motion support. Candidate colours were measured at 4.5:1 or better. No formal audit with assistive-technology users has been done.

23. **Does it work on a phone?**
    The candidate portal is mobile-first; it was smoke-tested at 360 px with no horizontal overflow on 12 screens, per the WP-FE-CAND notes. The HR portal is a desktop tool that collapses to a scrolling navigation strip on narrow screens. It was checked by reading the CSS only.

24. **Can HR change requirements after applications arrive?**
    Yes, while the job is DRAFT, PUBLISHED or ON_HOLD. For a live job a confirmation explains that the requirements version increases, the change is audited and every applicant is re-matched. CLOSED and CANCELLED jobs are frozen.

25. **What stops an admin from secretly reopening a closed job?**
    The override table allows only PUBLISHED → ON_HOLD or CLOSED, ON_HOLD → CLOSED and DRAFT → CANCELLED. Each override needs a reason, which is audited. CLOSED and CANCELLED show "Final".

---

## 4.7 Discrepancies and observations found while writing

| # | Finding | Evidence | Effect |
|---|---|---|---|
| 1 | **Broken link: staff email verification.** Organisation sign-up sends the new admin a verification email linking to `<HR portal>/verify-email?token=…`, but the HR portal has no `/verify-email` route. | `OrganizationService` (line 83) → `AuthService.sendVerificationEmail` → `portalFor(user) + "/verify-email"`; `hr-frontend/src/App.tsx` has no such route | The link goes through `/login` and ends on "Page not found"; the admin's email is never verified through that link. A password reset does mark the email verified (`AuthService.resetPassword`). |
| 2 | **Scorecard editing exists on the server and in the client, but not in the UI** | `InterviewController` `@PutMapping("/{interviewId}/scorecard")`, `ScorecardEditAliasController`, `hr-frontend/src/api/assessments.ts › updateScorecard` (never called) | `STATUS.md` §4.3 still lists it as "not built"; the UI says scorecards cannot be edited |
| 3 | **Audit filter list is incomplete.** The HR `AUDIT_ACTIONS` dropdown has 22 actions, but the backend also records MFA_*, SSO_*, WORK_SAMPLE_CANCELLED, WORK_SAMPLE_FILE_DOWNLOADED and SCORECARD_UPDATED. | `hr-frontend/src/api/types.ts` vs `backend/.../audit/service/AuditActions.java` | These events appear under "All actions" but cannot be filtered on |
| 4 | **Plan notes are out of date.** `WP-FE-HR-NOTES.md` says there is no staff inbox, change-password page or reschedule, and that assignments cannot be cancelled. `STATUS.md` §4.3 lists "work-sample file uploads (URL only), HR cancellation…, fairness by date range" as not built. | Code: `/notifications`, `/account/password`, `RescheduleDialog`, `workSamplesApi.cancel`, multipart submit, `useFairnessRange` | Present in code now; the notes predate the WP-INT and WP-FE-FINISH work |
| 5 | **Routes not in the sidebar** (by design) | Candidate: `/register`, `/forgot-password`, `/reset-password`, `/verify-email`, `/reference/:token` (email link only), and the detail routes `/jobs/:id`, `/applications/:id`, `/work-samples/:id`. HR: `/signup`, `/accept-invite`, `/forgot-password`, `/reset-password`, `/sso/complete`, `/jobs/new`, `/jobs/:id/edit`, applicant and interview detail, `/admin/organisation` (alias) | Every one is reachable from a link, an email or a redirect, except that the HR `/signup` page is linked only from the login footer |
| 6 | **Unused client functions** | HR: `jobsApi.addRequirement`, `interviewsApi.forApplicant`, `interviewsApi.updateScorecard`, `authApi.me`. Candidate: `meApi.get`, `verificationApi.run`, `queryKeys.verificationRun` (runs are tracked through the sources list instead) | None; they are dead code |
| 7 | **Shared nginx template comments mention another portal's features**: `/accept-invite` and `/sso/complete` (HR routes) and "QR codes" (no QR exists) | Both `nginx.conf` files are identical | Cosmetic |
| 8 | **Candidate work-sample rules text is outdated** ("add a link if the task asks for a file") | `WorkSampleResponsePage.tsx › StartPanel` | Minor copy issue; attachments of up to 3 files are supported |
| 9 | **Audit-export hint conflicts with its defaults** ("Leave both empty for the last 90 days", but both dates are pre-filled with the last 30 days) | `AdminAuditPage.tsx` | Minor |
| 10 | **Different password rules on the client**: the candidate check accepts Unicode letters; HR `passwordProblem` accepts only `[A-Za-z]` | `frontend/src/lib/password.ts` vs `hr-frontend/src/lib/format.ts` | Staff with non-Latin passwords are blocked by the client |
