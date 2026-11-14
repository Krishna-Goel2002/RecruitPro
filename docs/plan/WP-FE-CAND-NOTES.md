# WP-FE-CAND notes — candidate portal

Status: **done, gate G-FE green** (2026-11-08). Scope: everything under `frontend/`. Plan reference:
`IMPLEMENTATION_PLAN.md` Sections 1 (D11, D14, D15), 5 and 7. Audit findings closed on the client: M4, M5, M6,
S2 (client), C1 (UI), L8.

## Verification

| Check | Result |
|---|---|
| G-FE `pnpm build` (`tsc --noEmit && vite build`) on a clean copy of the repo folder | passes (≈ 318 kB JS / 31 kB CSS before gzip) |
| No `any` in the API layer | `src/api` has no `any` keyword, and a TypeScript-compiler check found no `any`-typed expression in `src/api` (one real escape, `Array.isArray(unknown)` → `any[]`, was fixed) |
| Browser smoke test (headless Chromium, 360×740) | **70/70 checks pass** against an in-memory mock of the Section 5 contract that also emulates the backend's `non_null` JSON. Covers: redirect to login; no tokens in storage; verify banner + resend; 300 ms debounce (one request for "acc"), focus kept in the filter, results-only loading; job detail + apply dialog (focus in, resume preselected, review step, submit, Escape, focus back); reload restores the session with exactly one `/auth/refresh`; expired token → one refresh for concurrent requests and one retry; refused refresh → clean sign-out with notice, then back to the requested page after sign-in; unread badge updates; flags explained; facts grouped; correction shown with the C1 label; download uses the original file name; polling while PENDING, stop when not pending, stop after the time limit with "Check again"; retry after FAILED; claim add (URL validation) and delete (focus on Cancel); verification list; add evidence prefilled; withdraw; work-sample instructions hidden until Start, countdown, submit with link; local interview times; profile without date of birth/gender (PUT omits both keys); survey requires consent; export download; account deletion; register password checklist; verify/reset error states; **no horizontal overflow at 360 px on 12 screens**; no uncaught page errors. The harness (mock server + puppeteer script) is not committed: it depends on a downloaded Chromium build. |
| Contrast | every text/background pair in the stylesheets computed at ≥ 4.5:1 (lowest 4.6:1, browser placeholder) |
| Real backend | **not run** (no JDK in this environment). Contracts were compared by reading the controllers and DTOs (below). |

Tooling note: `pnpm@9` refuses the existing `pnpm-workspace.yaml` ("packages field missing"); it uses pnpm 10 settings
(`allowBuilds`). The scratch builds therefore used `pnpm@10`, which is also what CI and the Dockerfile use. The
lockfile stays at format 9.0. `pnpm-workspace.yaml` is unchanged.

## Structure

- `src/api/client.ts` — fetch wrapper: envelope unwrap → `ApiError(status, message, fieldErrors)`; access token in a
  module variable only; `credentials: 'include'` on every call; `refreshSession()` is single-flight; on 401 a request
  reuses a token another request already refreshed, otherwise awaits the shared refresh, then retries **once**; a
  refused refresh (400/401/403) clears the token and notifies `AuthProvider` (sign-out with "session ended"); network
  errors and 5xx never sign the user out. JSON bodies go through `JSON.stringify`, so `Date`s are `toISOString()`.
- `src/api/types.ts` — every request/response shape (plan Section 5 + deviations noted inline).
- `src/api/endpoints.ts` — one function per call plus small normalisers that map absent fields to `null`/`[]`.
- `src/auth/AuthProvider.tsx` — restores the session on load, login/register/logout, staff-account handling.
- React Query defaults: retry only network/5xx errors (max 2), `staleTime` 30 s, no refetch on focus except the
  unread count.

## Routes and API calls

| Route | Calls (method + path under `/api/v1`) |
|---|---|
| app start | `POST /auth/refresh` (cookie) |
| `/login` | `POST /auth/login`; staff account → `POST /auth/logout` |
| `/register` | `POST /auth/register` |
| `/forgot-password` | `POST /auth/forgot-password` |
| `/reset-password?token=` | `POST /auth/reset-password` |
| `/verify-email?token=` | `POST /auth/verify-email` (once per token); `POST /auth/resend-verification` when signed in |
| layout (every signed-in page) | `GET /me/notifications/unread-count`; banner `POST /auth/resend-verification`; sign out `POST /auth/logout` |
| `/jobs` | `GET /jobs?page&size&search&department&location&jobType` |
| `/jobs/:id` | `GET /jobs/{id}`; apply dialog `GET /candidates/me/resumes`, `POST /jobs/{id}/applications` `{resumeId, coverLetter?}` |
| `/applications` | `GET /candidates/me/applications?page&size` |
| `/applications/:id` | `GET /candidates/me/applications/{id}`; `PATCH /candidates/me/applications/{id}/withdraw`; add evidence `POST /candidates/me/claims` |
| `/resumes` | `GET /candidates/me/resumes`; `POST /candidates/me/resumes` (multipart `file`); `GET …/resumes/{id}/download`; `GET …/resumes/{id}/analysis` (polled); `PATCH …/resumes/{id}/analysis/retry`; `PATCH …/resumes/{id}/analysis/facts/{factId}` `{value}` |
| `/evidence` | `GET /candidates/me/claims`; `POST /candidates/me/claims` `{claimType, value, evidenceUrl?}`; `DELETE /candidates/me/claims/{id}` |
| `/work-samples`, `/work-samples/:assignmentId` | `GET /candidates/me/work-samples`; `POST …/work-samples/{id}/start`; `POST …/work-samples/{id}/submit` `{responseText, responseUrl?}` |
| `/interviews` | `GET /candidates/me/interviews` |
| `/notifications` | `GET /me/notifications?page&size`; `PATCH /me/notifications/{id}/read`; `POST /me/notifications/read-all` |
| `/profile` | `GET /candidates/me`; `PUT /candidates/me`; `POST /me/password` `{currentPassword, newPassword}` |
| `/privacy` | `GET/PUT/DELETE /candidates/me/equal-opportunity`; `GET /candidates/me/export`; `DELETE /candidates/me` `{password}` |

## Contract check against the backend code as of this package

Matches the plan (read from the controllers/DTOs): auth, `/me`, password change (returns the new session, WP-BE-A
deviation 1 — the client adopts it), notifications, `JobPublic` with organisation name and requirements
(`knockout`, `alternatives`, `proficiency`), `Application` with `organizationName`/`statusChangedAt`,
`ApplicationDetail` (timeline, requirements with `VERIFIED|CLAIMED|NOT_VERIFIED`), claims, resume analysis with
`flags {code, detail}`, `tier`, `claimedValue`.

Differences and gaps found, and how the client handles them:

1. **`non_null` serialisation** (`spring.jackson.default-property-inclusion`). Any `T | null` field can be absent.
   Normalisers fill `null`; this caught a real bug (unread notifications have no `readAt`).
2. **Claims carry extra fields** (`verificationStatus` UNVERIFIED/VERIFIED/REJECTED, `hasEvidenceUrl`,
   `sourceFactId`, `updatedAt`) and the candidate view never includes `verificationNote`. Typed as optional; the
   Evidence page uses `verificationStatus` ("Confirmed by a recruiter" / "the recruiter could not confirm it").
3. **`ApplicationDetail.requirements` is also null when the application is no longer active** (not only when the
   organisation does not share). The page words the empty state accordingly.
4. **`ResumeAnalysisFact.claimedValue` equals the reviewed value**, which can equal the extracted value when a fact
   was only confirmed. The UI shows a claim only when it differs from the extracted value. Facts also carry
   `claimId`, `spanStart/End`, `attributes` (only `claimId` is typed); the analysis carries `timeline` (unused).
5. **Not yet present in the backend (WP-BE-C):** candidate work samples (list/start/submit), candidate interviews,
   equal-opportunity GET/PUT/DELETE, data export, `DELETE /candidates/me`. `ApplicationDetail.workSamples` and
   `interviews` are typed `List<Object>` in WP-BE-B. Until BE-C lands those pages show the server's 404 message
   with a retry button; nothing else breaks.
6. **Assumptions for BE-C** (please match or tell WP-INT):
   - `GET /candidates/me/equal-opportunity` with nothing stored → 404 or `data: null`; a stored response includes
     `consentGivenAt`. `PUT` requires `consent: true` and is sent on every save (the box is unticked after saving).
   - `DELETE /candidates/me` with a wrong password should answer **400**, not 401. A 401 makes the client refresh
     and retry once (the error is still shown, but a refresh-token rotation is wasted).
   - `GET /candidates/me/export` returns an attachment with `Content-Disposition`; fallback name
     `recruitpro-my-data-<date>.json`.
   - `CandidateWorkSample.deadlineAt` is the end of the time limit (fallback `startedAt + timeLimitMinutes`);
     `instructions` is null until started. The 5-minute grace is only explained in the UI; the server enforces it.
   - The profile `PUT` no longer sends `dateOfBirth`/`gender`. With today's `CandidateProfileUpdateRequest`, which
     still has both, the service sets them to null — consistent with D14, but V20 should drop the columns and fields.
7. **One refresh cookie for both portals.** Cookies ignore ports, so `localhost:5173` and `:5174` share
   `rp_refresh`. Signing in on one portal replaces the other portal's session cookie. The candidate portal never
   revokes a restored staff session (it shows "this browser is signed in to the HR portal"); a staff login on the
   candidate portal is immediately logged out again. Suggestion for WP-INT: distinct cookie names or paths per
   portal, or separate hostnames in production.
8. **`SameSite=Strict`** requires the portal and the API to be same-site; documented in `frontend/README.md`.

## Accessibility and layout

Mobile-first CSS split into `styles/{base,layout,components,pages}.css`, keeping the existing palette, radii and
type; the sidebar becomes a menu below 960 px (Escape closes it; the page behind is `inert`). Labelled inputs with
hints/errors tied by `aria-describedby`; native `<dialog>` with focus in/out management and Cancel-first
confirmations; skip link; focus moves to the page `<h1>` after navigation and after actions that remove the
focused control; polite/assertive live regions for notices; the work-sample timer announces only at 10/5/1 minutes;
`prefers-reduced-motion` respected; all colour pairs ≥ 4.5:1.

## Removed

`src/ResumeReviewSection.tsx` and `src/style.css` (replaced by `pages/resumes/*` and `styles/*`); the old 257-line
`App.tsx` became the router. Refresh tokens are no longer read from or written to `localStorage`.

## Not done / follow-ups

- No unit-test runner or ESLint config in the package (none existed); verification relied on the build gate, the
  `any` check and the browser smoke test described above.
- Not exercised against the real backend; WP-INT should run the portal against `docker compose up` once BE-C lands.
- No "already applied" indicator on job pages (no endpoint); a duplicate application shows the server's 409 message.
- Work-sample drafts are lost on reload by design (nothing stored in the browser); the page warns before unload.
- Candidates cannot change their name (no endpoint). No internationalisation.
- The DM Sans / Manrope fonts are referenced but not loaded (unchanged from before; system fonts are used).
