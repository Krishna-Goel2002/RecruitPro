# RecruitPro HR workspace

React 18 + TypeScript (strict) + Vite single-page app for organisation staff: administrators, HR managers, recruiters and interviewers. The candidate portal lives in `../frontend`.

Stack: `react-router-dom` 6, `@tanstack/react-query` 5, no UI library. Plan reference: `docs/plan/IMPLEMENTATION_PLAN.md` Sections 5 (API contract) and 7 (screens); implementation notes in `docs/plan/WP-FE-HR-NOTES.md`.

## Run it

```powershell
pnpm install
pnpm dev        # http://localhost:5174 (strict port)
pnpm build      # tsc --noEmit && vite build
```

The Dockerfile builds with pnpm 10 (the `pnpm-workspace.yaml` uses the pnpm 10 `allowBuilds` key) and serves `dist/` from nginx with an SPA fallback.

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080/api/v1` | API base URL, compiled into the bundle. Put overrides in `.env.local`. |

The backend must list this origin in `app.cors.allowed-origins` (default includes `http://localhost:5174`) because every request uses `credentials: 'include'`.

With the backend's demo seed (`APP_SEED_DEMO=true`) you can sign in as `admin@acme.test`, `hr@acme.test`, `recruiter@acme.test` or `interviewer@acme.test` (password `ChangeMe-123!` unless `app.seed.password` says otherwise).

## Sessions and security

- The access token (15 minutes) is kept **in memory only**; nothing is written to local or session storage.
- The refresh token is the httpOnly `rp_refresh_staff` cookie. Every request sends `X-RecruitPro-Portal: staff`, so the backend uses this portal's own cookie and a candidate session in the same browser is left alone. On page load the app calls `POST /auth/refresh` to restore the session.
- On a 401 the client refreshes once (single flight shared by all concurrent requests) and retries each request once. If the refresh is rejected the session is cleared and the user lands on `/login` with a notice; after signing in again they return to the page they were on (not after an explicit sign-out).
- Candidate accounts are refused by the server (403 on sign-in, refresh and invitation acceptance); the client also drops any non-staff session it receives.
- Every staff role has an in-app notification inbox (`/notifications`, unread badge in the header and sidebar) and a change-password page (`/account/password`); changing the password signs out other sessions and keeps this one.
- Role checks in the UI only hide actions; the server authorises every request.

## Roles

| Role | Navigation | Can do |
|---|---|---|
| ADMIN | everything | all of the below plus staff, invitations, organisation settings, job overrides, audit log, organisation fairness |
| HR_MANAGER | Jobs, My interviews | create/edit/publish jobs, requirements, decisions, reveal, work samples, interview kits, scheduling, job fairness, job audit export |
| RECRUITER | Jobs, My interviews | read jobs and applicants, review notes, verify claims, score work samples, download revealed resumes |
| INTERVIEWER | My interviews | see assigned interviews, submit scorecards |

## Code map

```
src/
  api/          client.ts (fetch, envelope, refresh, downloads), types.ts (every API shape), one module per area, queryKeys.ts
  auth/         AuthContext (session state), permissions (role → capability), guards (route protection)
  components/   Layout, Dialog (focus trap, inert background), Notices (aria-live), Tabs, Field/Toggle, ChipsInput,
                RatingScale, CoverageBars, Badges, Pagination, States, Icons
  lib/          format (Intl dates in local time, labels), hooks (debounce, pending-only polling), useSearchState (URL state)
  pages/        auth/, jobs/, applicants/, interviews/, admin/
  styles/       base.css (tokens + sign-in), workspace.css (shell, cards, tables, forms), components.css, pages.css
```

Conventions:

- **All API shapes live in `src/api/types.ts`.** Fields the current backend does not send yet are optional there.
- **Stale responses:** every filter, page and sort is part of the React Query key, and lists never show placeholder data from another key, so fast filter changes cannot render an older response (L7).
- **Polling:** applicant lists and details poll only while some applicant has `matchStatus: PENDING`, starting at 2 s and backing off to 15 s, and stop after about two minutes with a "check again" button (L8).
- **Times:** shown with `Intl` in the viewer's local time; sent as `toISOString()` (UTC).
- **Filters, page and tab** are kept in the URL, so Back and shared links restore the same view.
- **Accessibility:** labelled inputs with linked hints/errors, focus moved into dialogs and returned afterwards, Escape closes, the page behind a dialog is `inert`, tabs support arrow keys, outcomes are announced through `aria-live` regions, a skip link, and focus moves to the main region on navigation.
- The visual language of the original sign-in page and workspace is kept (teal palette, Manrope/DM Sans, soft cards); font sizes were raised by about 2 px for legibility. Desktop-first; at 760 px and below the sidebar becomes a scrollable navigation strip.
