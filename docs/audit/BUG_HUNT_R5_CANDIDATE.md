# Bug hunt R5: candidate portal (`frontend/`)

**Branch:** `Kshitij-redesign` (not committed). **Scope:** `frontend/**` and `tools/ui-snapshots/fixtures/candidate.mjs`. **Date:** 16 Nov 2026.

## Summary

| Gate | Result |
|---|---|
| Contract check | Every call in `src/api/endpoints.ts` was compared with the Spring controllers and DTOs: paths, methods, query names, body fields, multipart part names (`file`, `files`, `responseText`, `responseUrl`, `consent`), enums, `non_null` and `ALWAYS` serialisation, and error codes. The mismatches are fixed below (C-xx). The calls and their shapes were otherwise correct. |
| Issues fixed | 37: 3 high, 16 medium and 18 low. That is 31 bugs (B-xx) and 6 contract mismatches (C-xx), plus fixture fixes (F-01). |
| Tests | New: Vitest 2.1.9, Testing Library (react 16.1.0, dom 10.4.0, user-event 14.5.2, jest-dom 6.6.3) and jsdom 25.0.1, all pinned. The `test` script runs 14 files with **108 tests**, all passing. Two of them fail on the old `polling.ts`. |
| Clean gate | Run on a `/tmp` copy without `node_modules`: `npx pnpm@10 install --frozen-lockfile && pnpm build && pnpm test` passes. No `node_modules` or `.pnpm-store` was left in the repo. |
| Harness | **76 screens × light/dark × 1440/390 = 304 captures with `--axe`:** 0 page errors, 0 blank renders, 0 horizontal overflow, 0 axe violations of any impact, 0 unanswered API calls. |
| Screens added | `home-error`, `error-sources`, `jobs-past-end`, `add-source-connect-nokind`, `reference-drawer-limit` |
| PNGs checked | In the session outputs under `design-shots/r5/` |
| Design sync | `src/design` was **not changed** by R5. `check_design_sync.py` reports "in sync". |
| Consent wording, protocol constants | Unchanged: consent sentences, `X-RecruitPro-Portal` and `recruitpro-verification`. Tests pin the constants. |

Files under `frontend/` that R5 did not change but someone else did during this run: `Dockerfile`, `nginx.conf`, `.dockerignore` and `src/design/components/EvidenceLadder.tsx`.

## Findings

Severity: **H** means a broken flow, data loss or a security issue; **M** means a wrong result or state, or a contract mismatch a user would hit; **L** means polish, an edge case or defence in depth. Line numbers refer to the fixed files. The last column says whether the demo branch `Kshitij` (pre-redesign) has the same defect.

| ID | Sev | File:line | Problem | Fix | Test | Kshitij? |
|---|---|---|---|---|---|---|
| B-01 | **H** | `lib/polling.ts:36` (+ `useSources.ts`, `useResumeAnalysis.ts`) | The `refetchInterval` callback had a side effect (`polls.current += 1`). TanStack Query v5 evaluates it on **every render** (`QueryObserver.setOptions`). Each re-render therefore pushed the backoff towards its maximum and reset the interval timer, so polling slowed to 15 s or starved. | `nextInterval(isActive, fetchCount)` is now pure: the delay comes from `dataUpdateCount + errorUpdateCount`. `pollDelay()` is exported. | `polling.test.tsx` (fails on the old code) | yes |
| B-02 | **H** | `lib/polling.ts:36` | The polling window started when the page mounted. A run started later (Re-check from the verdict drawer, or a second run) was reported as "Still checking … stopped refreshing" at once, and polling never started. | A new window opens whenever `active` turns from false to true. | `polling.test.tsx` (fails on the old code) | yes |
| B-03 | **H** | `pages/verification/VerdictDrawer.tsx:253` | The "View source" link took its `href` straight from agent evidence `url`, so a `javascript:` URL ran when clicked. | `safeHref()` allows only http(s). | `verdict.test.tsx` | yes (`LedgerSection.tsx:164`) |
| B-04 | M | `auth/AuthProvider.tsx:94` | Public pages (the referee link `/reference/:token`, `/__design`) called `POST /auth/refresh` on load. A referee opening the link in a browser where a candidate was signed in rotated that candidate's cookie. | Session restore is skipped on public routes (`isPublicPath`). It runs if the visitor moves on to an app route. | `auth.test.tsx`, `referee.test.tsx` | yes |
| B-05 | M | `lib/verification.ts:221`, `AddSourceDrawer.tsx:231` | `isOAuthNotConfigured` returned true for **any** 503. A gateway outage switched the candidate to "use your username" with a misleading "not available on this server". | The check now requires the `OAUTH_NOT_CONFIGURED` code. The authorise URL must also be https before `location.assign`. | `verification.test.ts`, `addSource.test.tsx` | yes |
| B-06 | M | `pages/ProfilePage.tsx:141` | Any refetch of the profile (reconnect, another tab) overwrote unsaved edits through `useEffect([profile])`. | The form is replaced only when it has no changes. After a save, edits typed while the request was in flight stay. | `pages.test.tsx` | yes |
| B-07 | M | `pages/ProfilePage.tsx:183` | Ctrl/⌘+S sent an unchanged profile, and a second press while saving sent it twice. | Saves only when there are changes and nothing is pending. | `pages.test.tsx` | no |
| B-08 | M | `pages/JobsPage.tsx:74` | Back/Forward or the "Find jobs" nav link changed the URL but not the search inputs. The box kept the old words, "Updating…" spun for ever and the results did not match the box. A trailing space also left "Updating…" on screen. | The inputs follow the URL when it changes from outside, and filters are compared after the URL's normalisation. | `pages.test.tsx` | yes |
| B-09 | M | `pages/HomePage.tsx:52` | A failed resumes, sources, applications or ledger load rendered the brand-new-account checklist ("Add your resume" for someone who has one). Sources were also not waited for, so the "Strengthen" step flashed. | An `ErrorState` with Try again appears, and the overview waits for sources too. | `pages.test.tsx`, harness `home-error` | no |
| B-10 | M | `pages/HomePage.tsx:124` | The checklist line "We are reading your resume" never updated, because there was no polling. | Polls every 3 s while the analysis is PENDING or PROCESSING (at most about 2 min). | harness `home-reading` | no |
| B-11 | M | `pages/VerificationPage.tsx:200` | When the sources request failed, the page showed the first-visit "How it works" as if nothing were connected. | Shows an `ErrorState` with Try again. | `pages.test.tsx`, harness `error-sources` | no |
| B-12 | M | `pages/verification/AddSourceDrawer.tsx:628` | If `startRun` answered with a finished run (FAILED or COMPLETED), the Check step spun for ever. | Moves to "done" or "failed" at once. | `addSource.test.tsx` | no |
| B-13 | M | `pages/verification/VerdictDrawer.tsx:99` | The optimistic Share/Hide did not cancel a ledger refetch already in flight, whose older answer could overwrite it. | `cancelQueries` before `setQueryData`. Rollback on error is kept. | `verdict.test.tsx` (optimistic + rollback) | no |
| B-14 | M | `pages/resumes/AnalysisPanel.tsx:209` | Escape, Cancel and Save in "Suggest a correction" dropped focus on `<body>` (the button had re-mounted), which contradicted the notes. | `closeEditor()` puts focus back on "Suggest a correction". | `resumes.test.tsx` | no |
| B-15 | M | `pages/resumes/AnalysisPanel.tsx:231` | Enter calls `requestSubmit()`, which a busy button cannot block, so pressing it twice sent the correction twice. | Guarded with `save.isPending`. | `resumes.test.tsx` | no |
| B-16 | M | `pages/WorkSampleResponsePage.tsx:409` | Choosing more than 3 files silently dropped the extras. After removing a file, its error stayed on screen. | `mergeWorkSampleFiles()` reports files over the limit and duplicates, and the set is re-validated on remove. | `files.test.ts`, `workSample.test.tsx` | partly |
| B-17 | M | `components/Form.tsx:75` | `focusFirstInvalid` focused a `<fieldset aria-invalid>` (the reference drawer's claim list). A fieldset cannot take focus, so nothing was focused. | Focuses the first control inside the group. | `addSource.test.tsx` (consent focus) | no |
| B-18 | L | `lib/format.ts:125` | `closesIn` rounded days up: a deadline at 18:00 today read "Closes tomorrow". | Counts local calendar days, and a deadline that has passed reads "Closed". | `format.test.ts` | no |
| B-19 | L | `pages/EvidencePage.tsx:128` | A claim's `evidenceUrl` was rendered as a link without a protocol check. The server validates it; this adds defence in depth. | Rendered as a link only for http(s), otherwise as text. | `format.test.ts` (`safeHref`) | yes |
| B-20 | L | `pages/EvidencePage.tsx:63` | Deleting a claim made from a resume correction left "Your claim" on the resume fact. | The resume analyses are invalidated too. | n/a | no |
| B-21 | L | `pages/VerificationPage.tsx:94` | `?review=abc`, `?source=abc` and `?kind=FOO` were used unchecked: a NaN verdict fetch and a blank consent sentence. | `parseId()`, and `kind` is checked against the known kinds. | `pages.test.tsx` | no |
| B-22 | L | `pages/verification/AddSourceDrawer.tsx:99` | `step=connect` without a kind showed an empty drawer. | Falls back to Choose. | `addSource.test.tsx`, harness `add-source-connect-nokind` | no |
| B-23 | L | `pages/auth/AuthLayout.tsx:39` | The lazy auth routes (register, forgot, reset, verify) had no `Suspense` boundary. | A boundary with a status line. | harness `register` etc. | no |
| B-24 | L | `pages/jobs/ApplyDialog.tsx:93` | "Back" from Review dropped focus inside the dialog. | The Resume step is a focusable group and takes focus. | `apply.test.tsx` | no |
| B-25 | L | `pages/ApplicationDetailPage.tsx:93` | Withdrawing left the application's tasks in the Overview, nav counts and task pages. | Work samples and interviews are invalidated. | n/a | no |
| B-26 | L | `pages/InterviewsPage.tsx:89` | A past interview still marked SCHEDULED showed the badge "Scheduled". | Shows "Ended". | n/a | no |
| B-27 | L | `lib/notificationLink.ts:24` | Matching "first title contained in the subject" sent "Update on your application for Senior Backend Engineer" to "Backend Engineer". | Exact title first, then the longest contained title. | `format.test.ts` | no |
| B-28 | L | `lib/calendar.ts:29` | The .ics file kept a bare CR inside values and did not fold lines over 75 octets (RFC 5545). | `escapeIcsText` and `foldIcsLine`, and the file ends with CRLF. | `format.test.ts` | no |
| B-29 | L | `lib/drafts.ts:49` | Text typed less than 800 ms before closing the apply dialog or leaving the page was lost. | The pending draft is written when the key changes or the page unmounts. | `resumes.test.tsx` (`useDraft`) | no |
| B-30 | L | `pages/verification/References.tsx:255` | At 5 open requests the drawer opened without saying it would be refused (429). | A warning sits at the top of the drawer. | harness `reference-drawer-limit` | no |
| B-31 | L | `pages/JobsPage.tsx:178` | A `?page=` past the last page said "No open roles right now". | "This page of results is empty" with a link to the first page. | `pages.test.tsx`, harness `jobs-past-end` | yes |
| C-01 | M | `pages/WorkSampleResponsePage.tsx:32` | The response was capped at 20,000 characters; the server allows 50,000 (`WorkSampleSubmitRequest @Size(max=50000)`). | 50,000. | `workSample.test.tsx` | yes |
| C-02 | M | `api/client.ts:24` | Coded messages (`OAUTH_NOT_CONFIGURED: …`) were shown with the code, and the `Retry-After` header on 429 was ignored. | `ApiError.code` holds the code and the message is cleaned. `retryAfterSeconds` is parsed and `errorMessage` says "try again in N seconds". | `client.test.ts` | yes |
| C-03 | L | `pages/verification/References.tsx:265` | The referee email field allowed 320 characters; the server limit is 255. | 255. | n/a | yes |
| C-04 | L | `pages/JobsPage.tsx:132` | The search was labelled "Job title or keyword", but `GET /jobs` `search` matches the **title only**. Inputs had no `maxLength` (100/255). | Labelled "Job title", with `maxLength` set. | `pages.test.tsx` | yes |
| C-05 | L | `pages/ProfilePage.tsx:67` | `expectedSalary` is `@Digits(fraction=2)`, which the client did not check. | Inline check. | `pages.test.tsx` | no |
| C-06 | L | `pages/resumes/AnalysisPanel.tsx:220` | Sending the extracted text confirms the fact and **deletes** the correction claim, yet the toast said "Saved as your claim". | Says "Correction removed". | `resumes.test.tsx` | no |
| F-01 | L | `tools/ui-snapshots/fixtures/candidate.mjs` | The fixtures sent `null` for fields the backend omits (`non_null`). They used a non-existent evidence kind `REPO_FILE`, and `/jobs` ignored `page`. | `nn()` strips nulls for NON_NULL DTOs, `REPOSITORY`, paging, and 5 new screens. | harness | n/a |

**Checked, no change needed:**
- Refresh is single-flight, with one retry and no 401 loop. 400, 401 and 403 from refresh sign the user out.
- `auth:false` on the public and auth calls.
- Portal header and `credentials: 'include'` on every call (all covered by `client.test.ts`).
- Multipart part names. The verdict-review optimistic rollback. Referee 404/410/429.
- Delete account, including the refresh block afterwards.
- Theme init (try/catch, no flash). The verdict chips' text plus symbol, so meaning never relies on colour.
- `dangerouslySetInnerHTML`: none outside the gallery.
- Dates are UTC on the wire and formatted locally with `Intl`.

## Backend fixes needed (for R1–R3; not edited)

Paths are under `backend/src/main/java/com/recruitpro/`. The backend was being edited during this review, so line numbers may shift.

1. **`auth/controller/MeController.java:48`.** `authService.changePassword(...)` runs before `Portal.from(httpRequest)`. An invalid `X-RecruitPro-Portal` header changes the password and revokes every session, then answers 400 without the new session. Validate the header first.
2. **`candidate/privacy/CandidatePrivacyController.java:61-63`.** Same order problem: the account is deleted, then `Portal.from` can throw 400, and the cookie is left in place.
3. **429s without `Retry-After`.** These come from `verification/service/PublicEndpointRateLimiter.java`, the run limit in `VerificationRunService` and the open-reference limit in `ReferenceService` (`TooManyRequestsException`). Only `RateLimitFilter` sets the header. The open-reference limit is a business rule, so 409 would fit better than 429.
4. **`hr/service/JobService.java` `specification(...)` (~:328).** For candidates, `department` is an exact, case-insensitive match, but it is a free-text box. Either match "contains" (like `location`) or expose the list of departments for a select. The candidate UI now hints at this.
5. **`verification/controller/VerificationOAuthCallbackController.java`.** It only catches `TooManyRequestsException`, so any other exception returns a 500 JSON envelope to the browser instead of the promised 302 to `/verification?error=CONNECT_FAILED`.
6. **`candidate/service/CandidateProfileService`.** `PUT /candidates/me` returns `updatedAt` from before the flush, so it is stale.
7. **`candidate/privacy/CandidateDataExport`.** It promises explicit nulls, but the nested `profile` and `claims` are NON_NULL.
8. **API gaps the client works around.** Neither is a bug:
   - Notifications carry no subject id; links are matched by title (B-27).
   - Work samples and interviews have no `applicationId`.

## Notes for R6 (hr-frontend)

- `src/design` was not changed.
- `design/components/ProvenanceLine.tsx` puts `href` into `<a>` unchecked. R5 sanitises at the call site (`safeHref`). If HR renders evidence URLs through it, sanitise there too, or add an http(s) check inside the component in both portals.
- The polling bug B-01/B-02 is a pattern in `lib/polling.ts`. If `hr-frontend` has a copy that increments a counter inside `refetchInterval`, it has the same bug.

## How to run

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm test          # vitest run (jsdom)
pnpm build
```

`vitest.config.ts` is separate from `vite.config.ts`, so the brand plugin is not involved. The test helpers are in `src/test/`:
- `mockApi()` answers `/api/v1` calls in the backend envelope.
- `renderPage()` renders with the app's providers behind `RequireAuth`.
