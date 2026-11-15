# WP-FE-FINISH notes: finishing the interrupted frontend work

Date: 2026-11-16. Scope: `frontend/` and `hr-frontend/` only (backend and agent-service untouched, nothing committed).

## Build gate

Clean copy of each portal (no `node_modules`/`dist`), `npx pnpm@10 install --frozen-lockfile && npx pnpm@10 build`
(`tsc --noEmit && vite build`):

| Portal | Result |
|---|---|
| `frontend` (candidate) | green, 370.6 kB JS / 33.1 kB CSS |
| `hr-frontend` (staff) | green, 449.5 kB JS / 63.0 kB CSS |

No dependencies were added; the lockfiles are unchanged. Not run against a live backend or in a browser.

## Routes added

| Portal | Route | Guard | Notes |
|---|---|---|---|
| candidate | `/verification` | signed in (inside `Layout`) | Nav link "Strengthen your application". Handles `?connected=github` / `?error=<CODE>` (already in `VerificationPage`). |
| candidate | `/reference/:token` | public, outside `AuthLayout`/`Layout` | Referee page (`RefereePage.tsx`). |
| HR | `/sso/complete` | public | Refreshes with the staff portal header via `AuthContext.completeExternalSignIn()`, then goes to `homePath(role)`. |
| HR | `/admin/verification` | `verification.queue` (ADMIN, HR_MANAGER) | Nav "Verification" under Administration. Settings block only for `verification.settings` (ADMIN). |
| HR | `/account/security` | `account` (all staff) | Nav "Two-step verification". |

## Done

1. **Candidate verification** (`frontend/src/pages/verification/`): `SourcesSection` (ownership status/method, last run,
   polling while PENDING/RUNNING with `usePollingWindow` 3 s → 15 s backoff, 2-minute window then "Check again"; ledger
   refreshed when a run finishes; Check ownership / Run verification / Revoke with confirm; 409/429 messages via
   `shared.ts`), `ConnectSection` (kind picker, required consent, "Sign in with GitHub" → `startGitHubOAuth` →
   `window.location.assign`, falls back to the username form on 503 `OAUTH_NOT_CONFIGURED`; website URL; document
   upload with `validateDocumentFile`; Open Badge by URL or pasted credential), `OwnershipDialog` (`challengeSteps` with
   `CopyField`, method choice, Check now, server `ownershipInstructions` fallback), `LedgerSection` (tier + `VerdictChip`
   + `CandidateStatusTag`; detail dialog with reasons, evidence title/URL/retrieved time/`shortHash`, integrity notes with
   `INTEGRITY_NOTE_INTRO`, probe question, Share / Hide / Re-check + optional note), `ReferencesSection` (form with
   claims from the ledger, consent, `MAX_OPEN_REFERENCES` gate, list, cancel with confirm). CSS for verdict chips,
   review tags, copy fields and fieldsets added to `styles/pages.css`.
2. **Referee page**: loading, 404 "link not valid", 410 "closed", per-claim `REFEREE_ANSWER_OPTIONS` + comment, overall
   comment, identity confirmation, decline with confirm, success state.
3. **HR build errors fixed properly**: `useFairnessRange()` in `FairnessReportView.tsx` (date inputs validated with
   `lib/dateRange.ts`, applied range kept in `?from&to`, invalid URL values fall back to all time) used by
   `AdminFairnessPage` and the job Fairness tab; query keys now include the range. `LoginPage` handles `LoginResult`:
   MFA code step (`autocomplete="one-time-code"`, recovery-code toggle, `verifyMfa`, then `acceptSession` like a normal
   login; expired token → back to password), forced enrolment (`enrollWithToken` → secret + otpauth URI as text →
   `confirmWithToken` → recovery codes shown once → adopt `session`), `?ssoError=` messages (all 11 codes), SSO buttons
   from `/auth/sso/providers` (hidden when empty).
4. **HR admin Verification page**: settings (mode SHADOW/VISIBLE, six connector toggles, confirm dialog when switching
   to VISIBLE while `readyForVisible` is false), metrics (labelled, agreement, false-corroboration rate, kappa,
   readiness checklist, by-verifier and by-verdict tables), paginated shadow queue (`?page`) with the 5-verdict
   `VerdictPicker` + note, label saved in place and metrics refetched. Admin dashboard shows "Verification agents"
   health when `health.agentService` is present. Added the missing CSS for the existing `Verification.tsx` /
   `VerificationTab.tsx` components (verdict chips, picker, evidence list, mode banner), which had none.
5. **Account security** (`/account/security`): status, enrol (secret + otpauth URI) → confirm → recovery codes shown
   once, regenerate (confirm), disable with password + code (blocked when the organisation requires MFA),
   "not available" state when `available=false`.
6. **Work samples**: candidate submit with up to 3 attachments (`validateWorkSampleFiles`, remove before submit, text
   optional when a file is attached), download of submitted files, cancellation date/reason shown. HR applicant page:
   submitted files with Download (`workSamples.downloadFiles`), "Cancel assignment" with required reason
   (`workSamples.cancel`, ADMIN/HR_MANAGER, statuses ASSIGNED/STARTED/SUBMITTED/SCORED), cancellation details shown.

## Not done

- No QR code for MFA enrolment (secret + otpauth URI as selectable text only; no new dependency before the demo).
- The job Work samples tab assignment table has no cancel/download buttons (they are on the applicant page).
- Candidate `/work-samples` list does not show attachment counts (the response page does).
- No browser or end-to-end test run; no live backend run.
- HR_MANAGER on `/admin/verification` does not see the current mode (the settings GET is ADMIN-only by design).

## Frontend ↔ backend checks

Checked against the controllers/DTOs: candidate verification paths and `consent` form field, `normaliseLogin`
(username, `@login` or profile URL accepted), badge `credential` string-or-object, public reference GET/POST,
`VerificationMetricsResponse` (rates are 0..1; the HR `formatPercent` helper expects 0..100, so the page multiplies),
`ShadowQueueItem`/`LabelView`, fairness `from`/`to` ISO dates, work-sample multipart `responseText`/`responseUrl`/`files`
and `CancelAssignmentRequest {reason ≤ 1000}`. No mismatches requiring code changes on either side were found.
