# RecruitPro Candidate Portal

React 18 + TypeScript (strict) + Vite single-page app for candidates: find jobs, apply, follow applications, manage
resumes and evidence, complete work samples, see interviews and notifications, and control personal data.

Stack: `react-router-dom` 6 for routing, `@tanstack/react-query` 5 for server state, plain CSS (no UI library).
The API contract is `docs/plan/IMPLEMENTATION_PLAN.md` Section 5; work-package notes are in
`docs/plan/WP-FE-CAND-NOTES.md`.

## Run

```bash
pnpm install        # pnpm 10 (pnpm-workspace.yaml uses pnpm 10 settings)
pnpm dev            # http://localhost:5173/candidate/ (proxies /api to http://localhost:8080)
pnpm build          # tsc --noEmit && vite build  (gate G-FE)
pnpm exec vite preview   # serve the production build on http://localhost:4173
```

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `/api/v1` (same origin: gateway, `pnpm dev` proxy) | API base URL, compiled into the bundle (`.env.local` or Docker build arg); absolute for the per-port image |
| `VITE_CANDIDATE_URL` | `/candidate` | where this portal lives; its path is the Vite base and router basename (`http://localhost:5173` → base `/`) |
| `VITE_SITE_URL`, `VITE_HR_URL` | `/`, `/hr` | landing site and HR portal for the cross-links (one config: `src/design/lib/links.ts`) |

The backend must list the portal's origin in `CORS_ALLOWED_ORIGINS` (credentials are allowed for configured
origins). Docker: the `gateway` service serves this portal at http://localhost:8088/candidate/; `docker compose --profile ports up --build` also builds this folder's own image (nginx with an SPA fallback, `nginx.conf`) on :5173.

## Sessions and security

- The access token (15 minutes) lives **only in memory**. Nothing is written to `localStorage` or `sessionStorage`.
- The refresh token is the httpOnly `rp_refresh_candidate` cookie (path `/api/v1/auth`, `SameSite=Strict`). Every
  request uses `credentials: 'include'` and sends `X-RecruitPro-Portal: candidate`, so the backend reads and writes
  this portal's own cookie: signing in to the HR portal in the same browser no longer replaces the candidate session.
  Staff accounts are refused by the server with 403. On load the app calls `POST /auth/refresh` to restore the session.
- After the account is deleted the server has already revoked every session and cleared the cookie; the portal shows
  the signed-out page and does not try to refresh again.
- A 401 triggers **one** refresh shared by all concurrent requests, then a single retry. If the refresh is refused,
  the session is cleared and the user is sent to `/login` with "Your session has ended".
- Because the cookie is `SameSite=Strict`, the portal and the API must be on the same site (scheme + registrable
  domain): `localhost:5173` → `localhost:8080` works; in production use e.g. `jobs.example.com` and `api.example.com`.
- Times from the API are UTC and are shown in the viewer's time zone with `Intl`; times sent to the API use
  `toISOString()`.

## Code layout

```
src/
  api/         client.ts (fetch, envelope, token, refresh), endpoints.ts (one function per call + normalisers),
               types.ts (every API shape - reconcile contract changes here), queryKeys.ts
  auth/        AuthProvider (session state, login/register/logout), guards (RequireAuth, PublicOnly)
  components/  Layout (nav, unread badge, verify-email banner), Dialog/ConfirmDialog (native <dialog>),
               Notices (aria-live toasts), Form (Field, PasswordInput, checklist), Badges, ClaimForm, Feedback
  lib/         format (Intl dates, money, durations), labels (status/tier/flag wording), password policy,
               hooks (debounce, title, clock), download, focus, url
  pages/       one file per route; jobs/ApplyDialog, resumes/{ResumeCard, AnalysisPanel, useResumeAnalysis}, auth/*
  styles/      base (tokens, controls, buttons), layout (shell, nav, headers), components, pages - mobile-first
```

## Routes

| Path | Screen |
|---|---|
| `/login`, `/register`, `/forgot-password` | Sign in, create account (password 10–72 characters with a letter and a digit), request reset |
| `/reset-password?token=`, `/verify-email?token=` | Set a new password, confirm the email address |
| `/jobs` | Search with filters that stay mounted (300 ms debounce, results-only loading), pagination |
| `/jobs/:id` | Job detail with organisation, must-have badges, apply dialog (resume picker, cover letter, review) |
| `/applications`, `/applications/:id` | List; detail with timeline, requirement verification, add evidence, work samples, interviews, withdraw |
| `/resumes` | Upload, download, analysis status, parser notes, facts by type, "Suggest a correction" (creates a claim) |
| `/evidence` | Claims with tier badges, add (optional evidence link) and delete |
| `/work-samples`, `/work-samples/:assignmentId` | Assigned samples; timed response page (instructions only after Start) |
| `/interviews` | Upcoming and past interviews in local time |
| `/notifications` | Inbox, mark read / all read (unread badge in the navigation) |
| `/profile` | Professional details (no date of birth or gender) and password change |
| `/privacy` | Voluntary equal-opportunity survey with consent, data export, account deletion |

## Behaviour worth knowing

- **Polling** happens only while a resume analysis is `PENDING`/`PROCESSING`, every 3 s, and stops after about two
  minutes with a "Check again" button. The unread-notification count refreshes on window focus and every two
  minutes while the tab is visible.
- **Corrections are claims.** "Suggest a correction" never changes what was read from the resume; it creates a claim
  labelled "Your claim — recruiters see it as unverified until they check it".
- **Accessibility.** Every input has a label; dialogs use the native `<dialog>` (focus moves in, Escape closes, focus
  returns to the opener); focus moves to the page heading after navigation; notices use `aria-live`; text/background
  pairs are at least 4.5:1; layouts work from 360 px wide without horizontal scrolling.
