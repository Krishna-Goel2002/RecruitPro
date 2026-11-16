# HR portal, part A: redesign notes

**Branch:** `redesign` (not committed). **Date:** 16 Nov 2026. **Read with:** [FLOWS-HR-A.md](FLOWS-HR-A.md) (the flows these screens implement), [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md), [FOUNDATION_NOTES.md](FOUNDATION_NOTES.md).
**Scope:** sign-in and account entry, first run, jobs list, job create/edit wizard, job page (header, applicant ranking, work samples, interview kit, fairness, details, audit export), status pages, app navigation. Part B owns the applicant page, interviews, notifications, account/security and admin.

## 1. Results

| Gate | Result |
|---|---|
| Harness, part-A screens (48 screens × light/dark × 1440/390 = 192 captures, `--axe`) | **0** page errors, **0** blank renders, **0** horizontal overflow, **0** axe violations of any impact |
| `python3 tools/check_design_sync.py` | design folders in sync (`src/design` untouched) |
| `tsc --noEmit` + `vite build` | pass (see §7 for where it was run) |
| `pnpm test` | there is no `test` script in `hr-frontend/package.json` (`ERR_PNPM_NO_SCRIPT`), so nothing to break |
| Screenshots | `outputs/design-shots/hr-a/<screen>--<theme>--<width>.png` (192 files) |

Fixture screens live in `tools/ui-snapshots/fixtures/hr-a.mjs` (auto-loaded extension file; all data overrides are screen-level, so part B's routes are never shadowed). Re-run: `node snap.mjs --portal hr --dist <dist> --theme both --axe --only <names>`.

## 2. Screens and flows: what changed

Step counts are pointer activations or deliberate key presses on the happy path (method in FLOWS-HR-A §0).

| Screen / flow | Before | After | Steps before → after |
|---|---|---|---|
| **Sign in** (`/login`) | Decorative gradient panel with filler copy, wordmark wrapping, SSO buried, one MFA text field, no QR at enrolment, plain recovery list | Split layout: form column (max 400 px) + a product-true specimen (the real `EvidenceLadder` legend, one sentence, the "not a probability" line) on a flat sunken surface. SSO directly under the primary. Caps-Lock hint, show/hide password, "Forgot your password?" carries the typed email. One `Alert` slot for session-ended / SSO / failure messages | password 1 → 1 |
| MFA code | Text field + button; recovery as a second full-width button | `OtpInput`: six drawn boxes over **one** `autocomplete="one-time-code"` input (paste/autofill work), **auto-submit on the sixth digit**, single shake on a wrong code (none under reduced motion), live expiry countdown with a one-click restart, recovery code as a link | 2 → 1 + typing |
| Forced enrolment | "Start set-up" → typed 32-character key → code → plain codes list | Starts by itself; 3-step `Stepper` (Scan · Confirm · Save codes); **QR code** (part B's lazy `QrCode`), setup key and link in `CodeField`s behind "Can't scan?"; auto-submitting code; recovery codes with Download .txt, Copy all and a required "I have saved them" tick (part B's `RecoveryCodes`) | 5 + key typing → 3 |
| Sign-up, accept invitation, forgot / reset password, verify email, SSO complete | Old split layout, joined errors | Same `AuthLayout`; per-field errors; **live password checklist** that mirrors the server policy (Unicode letter + digit, 10 characters, 72 UTF-8 bytes); invitation shown as facts (organisation, role, email, expiry) with an "invitation cannot be used" state; every outcome has a next action | — |
| **First run** (`/welcome`, new) | New admin landed on an empty overview | **Get started** checklist (sidebar item with the open-step count until done or hidden): workspace · confirm email (resend in place) · two-step verification · "check how reviews work" (current blind-review/sharing/MFA values inline, **Looks right** or **Change**) · invite team · first job · publish. Each step is detected from real data; the next step is the only primary; progress meter | hunting across 4 admin pages → 1 click per step |
| **Jobs list** | Five counter cards (five icons in five tints) + status select + table with "Open →" | Status **tabs with counts** that are the filter; debounced search (`/` focuses it); whole-row link; applicants "38 · 4 new" (since *your* last visit); "Closes in 12 days" with the date in a tooltip (warning tone under 3 days); row "⋯" for Edit / Hold / Resume / Close / Export / Copy link; per-role empty states, filtered empty with **Clear filters**, first-job empty state without an empty table; 8-row skeleton; phone rows stack | put on hold 4 → 2 |
| **Job page header** | "JOB #101 · REQUIREMENTS V3" eyebrow, five equal buttons with a red Close beside Edit | Breadcrumb · H1 + status badge + "Requirements v3" tag · meta line · **Edit** + the one next status action (Publish / Resume publishing / Put on hold) + "⋯" (Close, Cancel/Delete draft, Export audit CSV, Copy link). Draft / on-hold / closed-reason banners. Tabs with counts (Applicants 38 · Work samples 2 · Interview kit 4 · Fairness · Details) | export audit 1 → 2 (moved into "⋯", no longer competing with Close) |
| **Applicant ranking** | ~10 card rows per screen, two labelled 250 px bars per row, nothing per requirement, decisions only on the applicant page | Compact table (≥ 20 rows at 1440 × 900) grouped Ranked / Must-haves not yet verified / Unscored with the plain sentences; **evidence strip** (one ladder cell per requirement, must-haves first, gap before nice-to-haves, open gates outlined in warning) whose cells fill bottom-up when the details arrive; Verified / With claims as count-up numbers + 48 px bars (hatch for claims) with the "not a probability" tooltip and a one-time dismissible hint; *Blind*, *New*, resume-note markers; saved **views** (All · To review · Shortlisted · Interviewing · More) and order in the URL; density toggle; **Copy link to this view**; **keyboard triage** (`j`/`k`, Enter, Space peek, `x`, `s`, `r`, `c`, `?`) with a hint bar and a shortcut sheet; **quick-peek drawer** (`?peek=`); **compare 2–3** (`?compare=`); **bulk** shortlist / not progressed; shortlist and reject **confirm, then 5 s Undo before anything is sent**; rows glide on re-rank (M7) and wash when they change group; matching progress line inside the strip | screen 10, shortlist 2: ≈ 24 + scrolling → ≈ 14 key presses, 0 page loads |
| **Job wizard** (`/jobs/new`, `/jobs/:id/edit?step=`) | One long page, 7 controls per requirement row, non-sticky save, nothing saved until the end | **Basics → Requirements → Review** with a step bar (non-linear for existing jobs). New jobs keep typing **on the device** (resume/start-over prompt), **Continue** creates the draft; drafts then **autosave to the server** 1.5 s after the last valid change ("Saved · just now" / "Couldn't save · Retry"). The **requirement compiler runs automatically** on the first visit to step 2 (skeleton "Reading your description…"): wording warnings with rewrites, all suggestions pre-selected with their rationale quote, **Use N suggestions** / Add / Replace. Compact editor rows: drag handle (or Alt+↑/↓, FLIP glide), type, text, Must-have, Gate (disabled with a reason for nice-to-haves and *Other*), a "More" disclosure whose label summarises years/proficiency/alternatives, remove with **Undo**. **Review**: how coverage weights each requirement (bars, "not a probability"), pre-publish **checks** with Fix links (no scored requirements, no must-haves, gates explained, > 10 years on entry/mid roles, open wording warnings, past deadline), "What candidates see" preview, **Publish**. Live jobs: no autosave, sticky "Unsaved changes" bar, version/re-match confirmation | ≈ 9 + 7 per requirement → ≈ 5 |
| Work samples tab | Cards with buttons; inline editor | Rows with expand (M12) showing instructions and a **1–4 anchor grid**; Generate opens the **editor drawer** with the draft; after save a toast offers **Activate**; assignments with a segmented filter (To score · In progress · Not started · Scored · All) linking to the applicant's work-sample tab | generate → active 4 → 3 |
| Interview kit tab | Cards, heading collision | Numbered questions, "Probes" with the requirement marker, follow-ups, 1–4 anchor grid; **coverage line** ("Every must-have has a question" or a warning naming the unprobed must-haves); editor with reorder, remove with Undo and a sticky save bar | — |
| Fairness tab (and `/admin/fairness`, shared view) | Two date inputs + Apply; long paragraph | Presets **Last 30 / 90 days · This year · All time · Custom** (`?from&to`, invalid URL → all time with a note); stat row (scope, response rate, smallest group, stages flagged); two-line explainer + "How to read" disclosure; flagged rows with a warning rule, the word *Flagged*, and an impact-ratio bar against the **0.80** tick; suppressed "<5" cells with screen-reader text | — |
| Details tab | Card with "✓" glyph bullets | Description at reading width; requirements in ranking order with must/nice/gate markers; a sticky "Role facts" rail (₹ salary in en-IN) | — |
| Status pages | Card with "!" and "?" glyphs | `PageHeader` + `EmptyState` (Not found / Not available for your role) | — |
| Navigation | — | Admins get **Get started** (count) at the top of Workspace until done or hidden; everything else unchanged | — |

## 3. Components and helpers added (generic ones in `src/components/`)

| File | What | Notes for part B / later |
|---|---|---|
| `components/OtpInput.tsx` | One-time-code input (six drawn boxes, one native input, `onComplete`, `errorKey` shake), joins a `Field` | Usable on the security page |
| `components/PasswordChecklist.tsx` | `PasswordChecklist`, `passwordRules`, `passwordError` mirroring backend `PasswordPolicy` (Unicode) | `lib/format.passwordProblem` is still ASCII-only (X-25); switch callers to `passwordError` |
| `components/Shortcuts.tsx` | `Kbd`, `ShortcutSheet`, `useShortcuts` (ignores typing targets, modifiers, open dialogs unless allowed; prevents the key from typing into a field the action focuses) | |
| `components/StatusBadges.tsx` | `JobStatusBadge`, `ApplicationStatusBadge` (REJECTED = "Not progressed", neutral), `WorkSampleStatusBadge` per §3.8 | |
| `components/SaveIndicator.tsx` | Autosave status line | |
| `components/hra.css` | Styles for the above, in the `components` layer | |
| `lib/deferredCommit.ts` | `useDeferredCommit`: optimistic change now, request after an Undo window; warns on tab close while pending | |
| `lib/lastSeen.ts` | Per-viewer "new since your last visit" and boolean flags (storage wrapped) | API gap, see §5 |
| `lib/limit.ts` | Promise pool (4 concurrent background fetches) | |
| `pages/home/setup.ts` | `useSetupProgress` (first-run detection) used by the sidebar and `/welcome` | |
| `pages/lazyRoutes.tsx` | Route-level code splitting for the wizard, first run and secondary auth pages | |
| `pages/jobs/jobActions.tsx`, `listDecisions.tsx`, `ranking.tsx`, `ApplicantPeek.tsx`, `ApplicantCompare.tsx`, `JobDetailsTab.tsx`, `wizard.css`, `jobs.css`, `pages/auth/auth.css`, `pages/home/welcome.css` | Page-level pieces | |

Reused from part B: `ActionDialog`, `QueryError`, `QrCode` (lazy), `RecoveryCodes`, `lib/localDraft`.
Kept for compatibility: `LoginPage` still exports `MfaSecret`, `RecoveryCodesList`, `ssoErrorMessage`; `InterviewKitTab` exports `fetchKit`, `requirementName`; `WorkSamplesTab` exports `RubricEditor` and now `AnchorGrid`; `FairnessReportView` keeps `FairnessReportView` + `useFairnessRange()` (returns `{range, filter}` plus `invalidUrl`).

**App-level edits** (`App.tsx`, minimal): one `/welcome` route, and the seven part-A imports now come from `pages/lazyRoutes`. `components/Layout.tsx`: the Get-started nav item.

## 4. Motion (all with reduced-motion fallbacks; harness captures run reduced)

Evidence-strip cells fill bottom-up with a 30 ms left-to-right stagger when per-requirement tiers arrive · coverage numbers count up and bars sweep (claims hatch 80 ms later) · rows glide on re-rank and wash when they change group (M7) · peek drawer and compare dialog (M8/M9) · send-window progress line under a pending status badge · row expand (M12) for work samples and requirement details · requirement rows glide on drag/Alt-arrow reorder (FLIP) · compiler suggestions and the first-run steps stagger in · weight bars grow on the review step · OTP caret blink and wrong-code shake · auth step crossfade with focus moved to the new heading · the specimen's ladder legend fills on view (M5).

## 5. API gaps (no backend change made)

| Gap | Workaround in the UI | Proposed |
|---|---|---|
| No per-requirement tiers in `ApplicantSummary` | The ranking fetches each visible applicant's detail in the background (4 at a time, cached 60 s, same query key as the applicant page) | add `requirements: [{requirementId, status, tier}]` to the list item |
| No job counts endpoint | Six `size=1` list calls in parallel, cached 60 s | `GET /jobs/stats` |
| No bulk decisions | One `POST …/decisions` per applicant after one confirm + one Undo window | `POST /hr/jobs/{id}/decisions:bulk` |
| No `lastViewedAt` | "New" markers stored per browser | per user and job |
| Ordinary job status changes are not audited (handbook 02 §2.2) | — | audit `JOB_STATUS_CHANGED` |
| `proficiency` is stored but not used by matching (handbook 02 §2.5) | The editor says "Shown to reviewers; not used in matching yet" | — |

## 6. Known issues and follow-ups

1. **JS budget.** Main chunk is 179 kB gzip after splitting the part-A routes (145.8 kB before the redesign; the budget is +15 kB). Part B's pages and the new shared components account for the rest; `FairnessReportView` and `InterviewKitTab` stay in the main chunk because part-B pages import them statically (move `fetchKit` to a small module and lazy-load the admin pages to recover ~20 kB).
2. **`ActionDialog` "Cancel first".** React's `autoFocus` does not render an `[autofocus]` attribute, so `useModal` focuses the first field instead of Cancel in destructive/irreversible confirmations (seen in the shortlist dialog). Owner: part B (`components/ActionDialog.tsx`) or the design system's `useModal`.
3. **In-app navigation away from a dirty live-job edit is not blocked** (the app uses `BrowserRouter`; `useBlocker` needs a data router). Tab close/reload is guarded; the sticky bar keeps the state visible.
4. **`/welcome` "Looks right"** is stored per browser (there is no server flag for "settings reviewed").
5. The compare view's "strongest evidence" highlight compares tier ordinals only; it says nothing about quality beyond the tier.
6. Legacy CSS: rules used only by the old part-A screens were removed (`job-table*`, `job-form`, `req-row*`, `req-field`, `req-checks`, `req-type*`, `suggestion`, `status-filter`, `item-meta`, `muted-copy`, plus the auth, counter, ranking and editor rules part B's pass had already dropped). The remaining `styles/*.css` belongs to legacy components still used by part B (`Badges`, `Dialog`, `States`, `Verification`).
7. **Build location.** In this sandbox `pnpm install` cannot complete inside the Windows-mounted `hr-frontend/node_modules` (it stalls on the mount and leaves only `.pnpm/`). The exact commands (`npx --yes pnpm@10 install --frozen-lockfile && npx --yes pnpm@10 build`) were run on a byte-identical copy of `hr-frontend` (same lockfile) in `/tmp`, where they pass. Run them once on the host before committing.

## 7. How to check by hand

- Sign in → wrong password → MFA (type six digits, it submits) → "Use a recovery code instead" → back.
- `/jobs/101?tab=applicants`: `j` `k` `Space` (peek, `j` inside it) `x` `j` `x` `c` (compare) `s` (confirm, then Undo in the toast) `?`.
- `/jobs/new`: type a title, reload (resume prompt), Continue, watch the suggestions arrive, Use them, drag a row, open "More", Review, Publish.
- `/jobs/101?tab=fairness`: switch presets; paste `?from=2026-02-30` to see the invalid-URL note.
