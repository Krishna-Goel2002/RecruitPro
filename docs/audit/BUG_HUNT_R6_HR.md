# Bug hunt R6: HR / staff portal (`hr-frontend/`)

**Branch:** `Kshitij-redesign` (not committed). **Date:** 16 Nov 2026. **Scope:** everything under `hr-frontend/**`,
plus `tools/ui-snapshots/fixtures/hr-a.mjs`, plus one shared change in `src/design` that was mirrored to `frontend/`.
**Method:**
- read every API module against the Spring controllers, DTO records and Jackson settings (`application.yml`: `non_null`, UTC `Z`);
- read every page, component and hook;
- had a second reviewer read the pages not covered by the first pass;
- added Vitest;
- ran the screenshot harness on all 113 HR screens.

The last column says whether the same defect also exists on the demo branch `Kshitij`, which has the pre-redesign UI.

## Results

| Gate | Result |
|---|---|
| `npx --yes pnpm@10 install --frozen-lockfile && npx --yes pnpm@10 build && npx --yes pnpm@10 test`, run on a clean `/tmp` copy with no `node_modules` | Pass. `tsc` is clean, the Vite build passes, and all **112 tests in 15 files** pass. |
| Harness (`--theme both --axe`, 1440 and 390 px), all 113 HR screens including the 3 new ones | 452 captures. **0** page errors, **0** blank, **0** horizontal overflow, **0** axe violations of any impact, **0** unmatched API calls. |
| `python3 tools/check_design_sync.py` | In sync. |
| Screenshots of what changed | `outputs/design-shots/r6/`: the work-sample assignment menu for manager and recruiter, the cancel dialog, the job Work samples tab and the admin overview. |

No `node_modules` or `.pnpm-store` was left in the repo. Every install, build, test and harness run happened in `/tmp/r6`.

## Task 0: Cancel and Download on the job's Work samples tab (missing feature)

The new module `src/pages/workSamples/assignmentActions.tsx` is shared by the applicant page and the job page. It provides:
- `useCancelAssignment`: a confirmation with a required reason (1–1000 characters, trimmed). Cancel is focused first. After a cancel it invalidates the job's assignment lists, the applicant detail and the ranking.
- `useSubmissionDownload`: the audited `GET /hr/work-sample-assignments/{id}/files/{fileId}`. The server names the file. A success toast says "Recorded in the audit log".
- `canCancelAssignment`: true for ASSIGNED, STARTED, SUBMITTED and SCORED.

The job tab's Assignments table (`pages/jobs/WorkSamplesTab.tsx:347`) now has a **Files** count column and a row "⋯" menu with:
- **Open application**;
- **Download <file> (size)** for each submitted file, shown to ADMIN, HR_MANAGER and RECRUITER;
- **Cancel assignment…**, shown to ADMIN and HR_MANAGER on cancellable statuses only.

Blind-first: assignments exist only for SHORTLISTED or INTERVIEWING applications, and shortlisting reveals the identity. So the candidate's own file names are never shown before a reveal; this is documented in the module.

The applicant tab now uses the same hooks instead of its own copy. Harness screens: `job-ws-assignment-menu`, `job-ws-assignment-menu-recruiter`, `job-ws-cancel-dialog`.

## Findings and fixes

Severity: **H** = wrong data or decision, a privacy leak, or lost work. **M** = a broken flow or a misleading UI. **L** = polish, robustness or a11y. Line numbers are in the fixed files.

| id | sev | file:line | problem | fix | test | on Kshitij? |
|---|---|---|---|---|---|---|
| R6-01 | M | pages/jobs/WorkSamplesTab.tsx:347 | The job Work samples tab had no Cancel or Download for assignments or files (missing feature). | Added both through the shared `assignmentActions.tsx`; permissions follow `@PreAuthorize`. | `WorkSamplesTab.test.tsx` (4) | yes (missing there too) |
| R6-02 | H | components/Shortcuts.tsx:89 | A confirm dialog opened on top of the quick-peek drawer still received the `s`, `r`, `j`, `k` and Space shortcuts. Pressing `r` while "Shortlist A-3001?" was open replaced it with a reject confirm, and `j` moved the drawer underneath. | Keys allowed inside a dialog now work only while exactly one dialog is open. | `Shortcuts.test.tsx`, `ApplicantsTab.test.tsx` "never reach the page while the confirmation is open" | no (redesign) |
| R6-03 | M | components/Shortcuts.tsx:69 | Space on a focused row-selection checkbox (or radio, switch or menu item) was taken by the "peek" shortcut and `preventDefault`ed, so the checkbox no longer toggled. | Added an `ACTIVATABLE` selector; Space and Enter on those controls keep their native behaviour. | `Shortcuts.test.tsx` | no |
| R6-04 | M | pages/jobs/ApplicantsTab.tsx:184 | Peek shortcuts stayed active while the decision dialog or the shortcut sheet was open. | Peek shortcuts are enabled only when neither is open (`decisions.isOpen` is exported). | `ApplicantsTab.test.tsx` | no |
| R6-05 | H | lib/deferredCommit.ts:25,101 | The tab-close guard was attached to the ranking component and removed when it unmounted. After navigating away while a decision was still in its 5 s window, closing the tab lost the decision without warning. Several mounted instances also removed each other's listener. The doc claimed a flush that did not exist. | The guard is registered while anything is pending and is independent of components. `flushDeferredCommits()` now returns a Promise. `pendingCommitCount()` was added and the doc corrected. | `deferredCommit.test.tsx` (6), including "asks before the tab closes … even after unmount" | no |
| R6-06 | M | auth/AuthContext.tsx:97 | Signing out during an Undo window sent the decision after logout, so it failed with 401 and showed an error toast; the confirmed decision was lost. | `signOut` flushes pending commits first, while the session is still valid. | `deferredCommit.test.tsx` "flushDeferredCommits" | no |
| R6-07 | M | pages/jobs/listDecisions.tsx:147 + ApplicantsTab.tsx | Optimistic rows dropped their override after a fixed 4 s. A slow refetch flashed the old status back. | New `applyOverride`: a sent override stays until the list shows the server's status; a 15 s cleanup handles statuses changed by someone else. | `ApplicantsTab.test.tsx` | no |
| R6-08 | M | pages/jobs/listDecisions.tsx:76 | Shortlisting from the list reveals the identity, but cached Verification views stayed blind for up to `staleTime`. | Invalidates `qk.applicantVerification(id)` for each decided row. | n/a (invalidation) | no |
| R6-09 | M | components/ActionDialog.tsx:64 | "Cancel focused first" never worked. React's `autoFocus` renders no `[autofocus]`, so `useModal` focused the first field. Destructive confirms (close job, reject, cancel assignment) and irreversible ones (reveal, shortlist) started on the textarea. Already listed in HR-A notes as known issue 2. | Cancel is focused in an effect after the dialog's own initial focus. No change to `src/design`. | `AdminVerificationPage.test.tsx`, `WorkSamplesTab.test.tsx` (focus asserted) | no |
| R6-10 | M | api/client.ts:40 | Errors whose message starts with a code (`MFA_NOT_CONFIGURED: …` from `MfaService.notConfigured` and `OrganizationService`) were shown raw: on the login enrolment, the Security page and the Organisation page. | `ApiError.code` plus `splitCodedMessage`, with friendly copy for `MFA_NOT_CONFIGURED` and `SSO_NOT_CONFIGURED`. Applied to JSON and download errors. | `client.test.ts` "splits message-prefixed codes" | partly (raw message shown) |
| R6-11 | M | pages/auth/LoginPage.tsx:300,390 | An MFA lockout is a 401 "Too many failed attempts", the same status as a wrong code. The code step said "That code did not match", and enrolment said "step expired". | New `isLockout()`. A lockout returns to the password step with its own message. Enrolment on a server without a key shows an explanation instead of a useless "Try again". | `LoginPage.test.tsx` (9): password-only, mfaRequired, recovery code, wrong code, lockout, expired, forced enrolment with codes, MFA_NOT_CONFIGURED, SSO buttons and `?ssoError` | yes (same 401 handling) |
| R6-12 | L | api/types.ts:595 | `AUDIT_ACTIONS` was missing 18 server actions (MFA, SSO, work-sample cancel/download, scorecard update, verification), so they could not be chosen in the audit filter. | Added all of them. | n/a | yes |
| R6-13 | L | api/types.ts:303,183 | `DecisionEvent.actorName` and `actorUserId`, `JobStaff.createdByEmail`, and other nullable fields were typed non-null. The server omits nulls (`non_null`). History rows for system events had an empty actor. | Types made optional and nullable. History shows "System" or "A former team member"; the job facts and admin job owner have fallbacks. | `tsc` | yes |
| R6-14 | M | pages/jobs/JobFormPage.tsx:56 | The device draft of a new job was stored under one key per browser (`draft:job-new`). Another staff member on the same computer was offered the previous user's title and description. A corrupted or old-shape draft crashed the form on Resume (`.trim()` of undefined). Drafts never expired. | Key is now per user (`job-new:<userId>`). `readNewJobDraft` validates each field, repairs it or falls back, expires drafts after 30 days, and handles blocked storage. | `jobWizard.test.ts` (device draft ×4) | no (no device draft) |
| R6-15 | H | pages/jobs/requirementDrafts.ts:62 + JobFormPage.tsx:239 | The server keeps no requirement order (`findByJobIdOrderById`). Reordering a live job's requirements still counted as a change, so saving created a new requirements version, wrote an audit entry and re-matched every applicant, and the order was lost anyway. New rows also never took the server's ids, so the next autosave deleted and re-created them, cutting work-sample and kit links. | `sameRequirements` ignores order. New `adoptServerIds` gives new drafts their server ids after a replace. | `jobWizard.test.ts` "a reorder alone is not a change", "new rows take the ids…" | yes (order-sensitive compare and replace) |
| R6-16 | M | pages/jobs/JobFormPage.tsx:226 | Autosave could run two PUTs or requirement replaces at once. An older body could land last. | Saves run one after another in a promise chain. | n/a (timing) | no |
| R6-17 | M | pages/jobs/JobFormPage.tsx:538 | Publish from the Review step did not validate. An invalid job was sent and came back as a generic 400 toast. | Validates basics and requirements first and jumps to the step with the error. | n/a | no |
| R6-18 | L | pages/jobs/JobFormPage.tsx:317 | Titles of 201–255 characters made "Suggest requirements" fail: the compile request allows only 200, the job allows 255. | Title sliced to 200 and description to 20 000 for the compile request. Also listed under backend fixes. | n/a | yes |
| R6-19 | L | pages/jobs/JobFormPage.tsx:301 | Closing the tab within the 1.5 s before a draft autosave lost the last edits without warning. | Unsaved changes in a draft also count while the state is `dirty`. | n/a | no |
| R6-20 | L | requirementDrafts.ts:39, JobFormPage.tsx:334 | Compiler output could exceed 10 alternatives or push the list past 100 requirements, which the server answers with a 400. | Capped at 10 alternatives and at `MAX_REQUIREMENTS`. | `jobWizard.test.ts` | yes |
| R6-21 | H | components/VerdictParts.tsx:114,130,139 | Blind-first relied on the server alone. If evidence, the employer domain or the candidate's note ever arrived before reveal, all three were rendered. The shadow queue shares this component. | Each is shown only when `revealed`; otherwise the evidence count or "shown after reveal". | `VerificationTab.test.tsx` "blind … even if the server sent them" (checks the whole DOM, attributes included) | no (old component shows evidence when present) |
| R6-22 | M | pages/applicants/ClaimsTab.tsx:28 | Claim evidence links were rendered whenever the URL was present, regardless of reveal. | Shown only after reveal; otherwise "Link hidden until identity is revealed". | n/a | yes (same pattern in EvidenceSection) |
| R6-23 | L | pages/applicants/VerificationTab.tsx:241 | "Change review" cleared the earlier note, so overruling again meant retyping the reason. | The note is prefilled and the error state reset. | `VerificationTab.test.tsx` "changing a review keeps the earlier note" | no |
| R6-24 | M | pages/admin/AdminVerificationPage.tsx:123 | Switching SHADOW and VISIBLE, or saving connectors or trusted lists, left every cached applicant Verification tab and ranking stale (claims empty, or results still shown). | Invalidates `['verification','application']` and `['applicants']`. | `AdminVerificationPage.test.tsx` (5): not-ready "anyway" confirm, ready confirm, cancel, HR manager view, trusted-list validation | yes (only metrics invalidated) |
| R6-25 | M | pages/admin/AdminVerificationPage.tsx:277 | On phones the queue detail sits in a bottom sheet, but its `1`–`5` and Enter shortcuts were active while the sheet was closed. Enter on the page saved a label nobody was looking at. | Shortcuts enabled only while the detail is visible (wide panel, or the open sheet). | n/a | no |
| R6-26 | L | pages/admin/AdminVerificationPage.tsx:258 | Labelling on page 2+ left the tab count (page 0) stale. | Invalidates page 0. | n/a | no |
| R6-27 | M | pages/admin/AdminOrganizationPage.tsx:56 | Turning blind review on left revealed names cached in applicant lists and interviews, and interviews were never refetched. Renaming the organisation left the old name in the sidebar and dashboard. | Turning blind review on removes those caches; any other change invalidates them, interviews included. `updateUser({organizationName})`. | n/a | yes (no invalidation) |
| R6-28 | M | pages/jobs/RequirementsEditor.tsx:47 | Undo after removing a requirement restored the list as it was at removal, so edits made during the 6 s toast were thrown away. | Undo inserts into the current list (ref). | n/a | no (no Undo there) |
| R6-29 | L | pages/jobs/RequirementsEditor.tsx:158 | Alt+↓ on the type `<select>` (the Windows "open list" key) reordered the row. | Ignored on selects. | n/a | no |
| R6-30 | M | pages/account/ChangePasswordPage.tsx:51 | Every 400 or 401 was shown as "That is not your current password", including "new password must be different", policy errors and expired sessions. | Mapped by the server message; everything else is shown as it is. | n/a | no |
| R6-31 | M | pages/jobs/InterviewKitTab.tsx:161 | Removing q3, adding a question (which got `q3`) and then pressing Undo produced duplicate ids. The server refuses the save and React keys collide. | Removed ids stay reserved; Undo skips a restore whose id already exists. | n/a | partly (same id scheme, no Undo) |
| R6-32 | L | pages/jobs/InterviewKitTab.tsx:38,50,151 | Generate and Edit were offered on cancelled jobs (server 400). No limits on questions, follow-ups or anchors (server 400). Interview pages kept the old kit. | `canManage` excludes CANCELLED. `maxLength` on fields; follow-ups capped at 10 and questions at 60. Saving or generating invalidates interviews. | n/a | yes (limits) |
| R6-33 | L | InterviewKitTab.tsx:66, WorkSamplesTab.tsx:62 | "Add requirements first" sat in a tooltip on a **disabled** button, which never gets hover or focus, so it was never shown. | The reason is now visible text next to the button. | harness | no |
| R6-34 | M | pages/account/NotificationsPage.tsx:105 | The Unread filter is per page. With no unread items on the current page, the empty state hid the pagination, so unread items on other pages could not be reached. | Pagination stays visible; the empty state explains and links back. | n/a | no (no filter) |
| R6-35 | L | pages/account/NotificationsPage.tsx:56 | Optimistic mark-read could be overwritten by the sidebar's unread poll, and mark-all updated only the current page. | `cancelQueries` in `onMutate`; `onSettled` invalidates every page. | n/a | no |
| R6-36 | L | pages/account/NotificationsPage.tsx:31 | `/\evil.com` was accepted as an in-app link; browsers resolve it as `//evil.com`. The backend sends no `link` today. | Same-origin paths only; backslashes refused. | `policies.test.ts` | no |
| R6-37 | M | pages/interviews/InterviewDetailPage.tsx:156,269 | Scorecard drafts were stored per browser, not per user. Cancelling an edit kept the abandoned draft, and the next Edit loaded it. A stored draft of an unexpected shape could crash. | Key includes the user id. Cancel clears the draft. The stored shape is checked. | n/a | no |
| R6-38 | L | pages/applicants/IdentityRail.tsx:136 | The review-note device draft was per browser, so on a shared computer one user could see and save a colleague's unsaved note. | Key includes the user id. | n/a | no |
| R6-39 | L | components/OtpInput.tsx:54 | `maxLength=6` cut a pasted "123 456" or "123-456" to "12345" before the digit filter ran, so auto-submit never fired. | Room for separators; `onChange` keeps the digits only. | n/a | no |
| R6-40 | L | components/PasswordChecklist.tsx:12 | Letters from the astral planes ticked "A letter", but the server checks per UTF-16 unit and rejects them. The unused ASCII-only `passwordProblem` contradicted the policy. | Only BMP letters and digits count. `passwordProblem` removed. | `policies.test.ts` | yes (`passwordProblem` used there) |
| R6-41 | L | pages/auth/VerifyEmailPage.tsx:36 | A successful verification did not update the signed-in user, so the Get-started checklist and sidebar count kept "Confirm your email" open. | `AuthContext.updateUser`, applied once both the session and the result are known. | n/a | no |
| R6-42 | L | lib/useSearchState.ts:34,42 + JobsListPage:53, AdminUsersPage:27, AdminJobsPage:37 | A hand-edited `?status=FOO` or `?role=` went to the server (enum 400). `?enabled=foo` meant false. A page past the end showed "No jobs yet" with no way back. | `oneOf` whitelists; `useClampPage` jumps back to the last page. | n/a | yes |
| R6-43 | L | pages/applicants/EvidenceTab.tsx:334,363 | Enter in the claim note could send twice while pending and fired during IME composition. The note id was duplicated when a claim appeared twice. | Pending guard, `isComposing` check, `useId`. | n/a | no |
| R6-44 | L | pages/admin/AdminDashboardPage.tsx:80 | "Staff" showed `totalHrUsers` (HR managers and recruiters only). "Candidates on the platform" counted candidates who applied to this organisation. | Uses `totalUsers` and the label "Candidates who applied". | n/a | yes |
| R6-45 | L | design/components/EvidenceLadder.tsx:145 (both portals) | Two sibling keys were both `0` (rungs and label): a React duplicate-key warning on every ladder chip and unstable reconciliation on tier change. | Prefixed keys, mirrored to `frontend/src/design`; design sync OK. | caught by the new tests' console output | no (design system is new) |
| R6-46 | L | jobActions.tsx:179, ApplicantsTab.tsx:372 | "Copy link" did nothing without the Clipboard API (http or insecure context), and the ranking version swallowed failures. | A warning toast shows the URL instead. | n/a | n/a |
| R6-47 | L | components/RecoveryCodes.tsx:49 | A failed copy was silent, and the reset timer was not cleared on unmount. | Visible "use Download instead" status; timer cleared. | n/a | no |
| R6-48 | L | components/AnchoredRating.tsx:44 | `aria-invalid` sat on a plain `<fieldset>`, where screen readers ignore it. | `role="radiogroup"`. | harness axe | no |
| R6-49 | L | pages/applicants/InterviewsTab.tsx:106, InterviewKitTab.tsx:261 | Every row's menu was named "More actions", and "Remove follow-up 1" repeated across questions. | Labels name the interview time and the question. | n/a | no |
| R6-50 | L | pages/jobs/FairnessReportView.tsx:30,55 | An unknown dimension gave "undefined table" in `aria-label` and the caption. A null response rate showed "0%". | Label fallback; "—" when null. | `FairnessReportView.test.tsx` (3): invalid URL falls back to all time, from > to refused, valid range in the URL and the request | no |
| R6-51 | L | pages/jobs/WorkSamplesTab.tsx:24 | The rubric editor did not enforce server limits (anchors ≤ 1000, criterion ≤ 500, instructions ≤ 20 000, at most 30 criteria), so users got a generic 400. Criterion input was capped at 255 although the server allows 500. | Limits enforced with messages and `maxLength`. | n/a | partly |
| R6-52 | L | api/auth.ts | The logout comment claimed the bearer token is sent; it is not (the cookie and portal header suffice). | Comment corrected. | n/a | n/a |

### Checked and fine

These areas need no change:
- **Refresh and errors:** single-flight refresh is shared by concurrent 401s; a second 401 ends the session; network and 5xx errors keep it; the portal header is on every call; every envelope error becomes an `ApiError`.
- **SSO and roles:** `/sso/complete` shares the refresh with the session restore. The permissions matrix equals the backend `@PreAuthorize` rules exactly; a test now pins all 24 capabilities × 4 roles.
- **Links and blind display:** every server-supplied `href` goes through `safeHttpUrl` (http/https only). `displayName`, `documentTitle`, `aria-label`s and tooltips use the blind display name before reveal.
- **Interview scheduling:** only changed fields are sent; datetime-local converts to and from UTC `Z`.
- **Fairness URL:** `?from&to` is validated, and an invalid link falls back to all time with a note.
- **Downloads:** file names are sanitised.

## Tests added (Vitest 2.1.9, jsdom 25.0.1, Testing Library 16.1.0 / user-event 14.5.2 / jest-dom 6.6.3; exact pins, lockfile updated)

`pnpm test` runs `vitest run`. Configuration is in `vitest.config.ts`. Test helpers are in `src/test/`:
- `setup.ts`: polyfills for `<dialog>`, `matchMedia` (reduced motion), the observers and `animate`;
- `render.tsx`: QueryClient, MemoryRouter, ToastProvider and a fixed `AuthContext` per role, plus a location probe for URL state;
- `fixtures.ts`.

`AuthContext` is now exported so tests can provide a session.

| File | Covers |
|---|---|
| `api/client.test.ts` (14) | Portal header and credentials on every call, the bearer token, single-flight refresh for 3 concurrent 401s, refresh rejected (session cleared and listeners told), refresh unreachable (session kept), no refresh loop, public calls never refresh, envelope and field errors, `success:false` in a 200, coded messages, network failure, lockout detection, download file name and sanitising, download errors, Content-Disposition forms |
| `auth/permissions.test.ts` (26) | Full matrix: 24 capabilities × ADMIN, HR_MANAGER, RECRUITER and INTERVIEWER, plus CANDIDATE and null; `isStaff`; home path per role |
| `pages/jobs/ApplicantsTab.test.tsx` (6) | `j` focus; `s` confirm (lists what a reveal exposes), optimistic status, Undo sends nothing; send after 5 s with a quick reason; no s↔r switching under a confirm; no shortlist on a shortlisted row; recruiters cannot decide; `x` + `c` puts compare in the URL |
| `lib/deferredCommit.test.tsx` (6) | Window timing, Undo, sends after unmount, revert on failure, tab-close guard after unmount, flush |
| `components/Shortcuts.test.tsx` (5) | Not while typing or in a menu, modifiers ignored, checkbox Space, one-dialog rule |
| `pages/applicants/DecisionBar.test.tsx` (6) | Primary per status, Schedule for SHORTLISTED, Reopen, recruiter summary, final states, never an action the server did not allow |
| `pages/applicants/VerificationTab.test.tsx` (6) | Shadow mode; blind (no identity anywhere in the DOM even if sent); revealed; overrule needs a note; confirm without one; change review |
| `pages/auth/LoginPage.test.tsx` (9) | Branching: password, mfaRequired, recovery, wrong code, lockout, expired, enrolment with QR and codes before the session, MFA_NOT_CONFIGURED, SSO, `?ssoError` read once |
| `pages/auth/SsoCompletePage.test.tsx` (3) | Success goes to the role's home; no session; unreachable server |
| `pages/admin/AdminVerificationPage.test.tsx` (5) | Switch to VISIBLE when not ready ("anyway", Cancel focused) and when ready; cancel; HR manager view; trusted-list validation, normalisation and save |
| `pages/jobs/FairnessReportView.test.tsx` + `lib/dateRange.test.ts` (5) | Calendar dates, from ≤ to, invalid URL falls back, a valid range reaches the URL and the request |
| `pages/jobs/jobWizard.test.ts` (12) | Basics validation, body mapping, device draft per user, stale, corrupted and blocked-storage drafts, requirement validation, order-insensitive compare, server-id adoption, compiler caps, review checks |
| `pages/jobs/WorkSamplesTab.test.tsx` (4) | Audited download, recruiters cannot cancel, cancel needs a reason (trimmed) and refreshes the lists, cancelled rows have no cancel |
| `components/policies.test.ts` (5) | `safeHttpUrl` (javascript:, data:, protocol-relative), notification links, password policy (Unicode and astral), domain normalisation and public suffixes |

## Backend fixes needed (not edited; for R1–R3)

| # | Where | Problem | Suggested fix |
|---|---|---|---|
| B1 | `backend/.../hr/dto/CompileRequirementsRequest.java:9` vs `JobCreateRequest.java:10` | The compile title is limited to 200 characters, a job title to 255. Long titles get a 400 from "Suggest requirements". The frontend slices to 200 for now. | `@Size(max = 255)` on the compile title. |
| B2 | `hr/service/JobService.java:223,384` (`findByJobIdOrderById`); no order column | Requirement order is not stored, so the wizard's drag and Alt+arrow reorder is lost on save. The frontend no longer treats a pure reorder as a change (R6-15). | Add `display_order` (migration), keep request order in the bulk replace, and order by it. |
| B3 | `auth/mfa/MfaService.java:64` (`LOCKED`, thrown at :156, :234, :398); `MfaTokenService.java:39` | Lockout, wrong code and expired step are all 401 and can only be told apart by message text. The frontend matches `/too many/i`. | Use 429, or prefix `MFA_LOCKED:` (the frontend already parses `CODE:` prefixes). |
| B4 | `interview/dto/InterviewResponse.java:13` / `InterviewDetailResponse` | No application status or `scorecardEditable`. The UI keeps "Edit scorecard" after a decision, and the server answers 400 `SCORECARD_LOCKED`. | Add `scorecardEditable` (or `applicationStatus`). |
| B5 | `admin/controller/AdminController.java:106-117` | The audit CSV export has no `Cache-Control: no-store`; the job audit export and file downloads do. | Add `no-store` and `nosniff`, as in `HrJobToolsController`. |
| B6 | `notification/dto/NotificationResponse.java:7` | No `link` field, so the inbox can only route by template to list pages. | Add `link` (an in-app path such as `/jobs/{id}/applicants/{appId}?tab=work-samples`). |
| B7 | `auth/validation/PasswordPolicy.java:18` vs `:35` | The DTO `REGEX` uses code points (astral letters pass), but `isValid` loops over UTF-16 chars (they fail). The two disagree. | Iterate code points in `isValid`, or document BMP-only. The frontend now mirrors `isValid`. |
| B8 | `admin/service/AdminDashboardService.java:38` | `totalHrUsers` counts only HR_MANAGER and RECRUITER, which the name hides. The frontend now shows `totalUsers`. | Rename it, or count all staff roles. |
| B9 | `application.yml:36` (`default-property-inclusion: non_null`) | Nullable fields disappear from responses rather than being `null`, so every nullable TS field must also be optional. Types are fixed where it mattered (R6-13). | Optional: `@JsonInclude(ALWAYS)` on response records, as `WorkSampleAssignmentResponse` and `MfaChallengeResponse` already do. |
| B10 | `interview/controller/InterviewController.java:75-79` | `GET /interviews/{id}/scorecard/revisions` exists but no UI uses it; "edited N times" cannot show history. | Informational; no change required. |

## Known limits (not fixed)

- **In-app navigation** away from a dirty live-job edit is still not blocked. It needs a data router for `useBlocker`. Closing the tab is guarded.
- **Decisions still in their Undo window** are flushed on sign-out, but not when the tab closes. The browser asks first; `fetch` with `keepalive` would be needed to send them on `pagehide`.
- **"New since your last visit"** markers (`hr-jobs-seen`) are per browser, not per user. This is an API gap: there is no `lastViewedAt`.
- **Some `ApplicantSummary` and `ApplicantDetail` fields** are typed `T | null` but can be missing. The UI uses `== null` everywhere, so this has no runtime effect.
