# HR portal, part A: user flows (current → optimal)

**Scope:** staff sign-in and account entry, first run for a new organisation, jobs (list, create/edit, publish/hold/close), the job page and its tabs (applicant ranking, work samples, interview kit, fairness, details, audit export), status pages and the app navigation.
**Out of scope (part B):** applicant detail and its tabs, decisions on the applicant page, interviews pages, notifications, account/security, admin pages.
**Read with:** [UX_REVIEW.md](UX_REVIEW.md) (findings by id), [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md), handbook chapters 02 and 04.

Conventions in this document:

- **Steps** count pointer activations or deliberate key presses on the happy path, starting from the screen named in the flow title. Typing into a field is not a step; submitting is.
- **Deep link** = the state survives a reload and can be pasted to a colleague.
- Every flow lists its **states** (loading, empty, error, success) and its **guard rails** (what can never happen by accident).
- Coverage is always described as *the share of weighted requirements with evidence*. It is never shown, named or animated as a probability, score of fitness or likelihood.

---

## 0. Principles for these flows

1. **One obvious next action per screen.** The primary button is the thing most people do next; everything else is secondary, in a "⋯" menu, or a keyboard shortcut.
2. **Undo beats confirm, except where the action leaves the building.** Reversible, internal actions happen immediately with an Undo toast. Actions that notify a candidate, reveal an identity, or cannot be reversed by the server (close, cancel, delete, publish) are confirmed first, naming the object and the consequence. Decisions taken from the ranking list get **both**: a confirm (because they notify or reveal) and a 5-second send delay with Undo (because people mis-press keys in a fast list).
3. **Everything you can see, you can link to.** Tabs, filters, page, sort, the quick-peek drawer, the compare view, the wizard step and the fairness date range are all in the URL.
4. **Never lose typing.** Job drafts autosave (on the device before the first save, on the server after it). Leaving a dirty live-job edit asks first.
5. **State is shown where the user is looking.** A status change updates the row or header in place (optimistic, then reconciled). Toasts confirm; they never carry the only copy of a result.
6. **Keyboard first for the daily screens, pointer friendly for the rest.** The ranking can be worked through without a mouse. Every shortcut has a visible hint and a `?` help sheet.

---

## 1. Staff sign-in

### 1.1 Current

| # | Screen | What happens | Friction |
|---|---|---|---|
| 1 | `/login` | Email + password, **Sign in to HR workspace** | Decorative left panel with filler copy (X-5); the wordmark wraps (B1); SSO buttons are below the security note and look like links. |
| 2 | MFA step | One 6-digit text field, **Verify and sign in** | No auto-submit; no paste-friendly boxes; no idea how long the step is valid; "Use a recovery code" is a full-width secondary button with the same weight as the primary. |
| 2b | Recovery | Same field, different label | Fine. |
| 3 | Forced enrolment | **Start set-up** → a secret in 4-char groups → code → **Confirm** | **No QR code**: the user types a 32-character key on a phone (handbook 4.5.2 #1). Three sub-steps without a progress indicator. |
| 4 | Recovery codes | Plain list; **I have saved them, continue** | No copy or download; nothing stops a user who did not save them. |
| 5 | SSO | "Continue with Okta" link → IdP → `/sso/complete` → home | Errors come back as `?ssoError=` and are shown well. |

Steps today: password-only 1 · with MFA 2 · forced enrolment 5 (+ typing the key).

### 1.2 Optimal

| # | Step | Design |
|---|---|---|
| 1 | **Sign in** | Split layout: form column (max 400 px) on the left, a quiet specimen of the evidence ladder on the right (≥ 900 px only). Email + password, caps-lock hint on the password field, primary **Sign in** (40 px, full width). SSO providers sit *directly under the primary* as full-width secondary buttons after an "or" rule, so SSO users never scroll. Footer: Forgot password · Create a workspace. |
| 2 | **Enter your code** | Six segmented boxes backed by **one** `<input autocomplete="one-time-code" inputmode="numeric">` (paste and SMS/OTP autofill work; the boxes are drawing only). **Auto-submits on the sixth digit** (no button press needed; the button stays for keyboard users). A quiet countdown "This step expires in 4:12" turns into "Expired — enter your password again" with a one-click restart that keeps the email. "Use a recovery code instead" is a link, not a second button. Wrong code: boxes shake 4 px once (reduced motion: none), clear, and keep focus. |
| 2b | **Recovery code** | One mono text field, hint "Each code works once"; same submit. |
| 3 | **Set up two-step verification** (forced) | A 3-step `Stepper` (Scan · Confirm · Save codes). Step 1 starts automatically (no "Start set-up" press): **QR code** (lazy-loaded) next to the setup key in a `CodeField` with copy, and "Can't scan? Use the setup link". Step 2 is the same six-box input with auto-submit. |
| 4 | **Save your recovery codes** | Codes in a two-column mono grid, **Copy all** and **Download .txt**; a required checkbox "I have stored these codes somewhere safe"; **Continue to workspace**. Pressing Continue without the tick shows an inline error on the checkbox (the button is never disabled). |
| 5 | **SSO** | Unchanged mechanics. `/sso/complete` shows a calm progress state; errors return to `/login` with a danger `Alert` that names the problem and the next step. |

Steps after: password-only **1** · with MFA **1 + typing** (auto-submit) · forced enrolment **3** (scan, type code, tick + continue).

**States.** Busy buttons keep their label and width (spinner replaces the icon). Session-ended reasons and SSO errors appear as one `Alert` above the form, never both. Network failure: "We could not reach the server. Your details are kept; try again."
**Guard rails.** The MFA token's expiry is enforced client-side as well; the password is cleared after any failed attempt; recovery codes are never stored in the browser.
**Deep link.** `/login?ssoError=…` is read once and removed from the URL.

### 1.3 Related account-entry flows

| Flow | Current | Optimal |
|---|---|---|
| Create a workspace (`/signup`) | 6 fields, generic error banner, lands on `/admin` | Same fields with a **live password checklist** (SVG ticks), per-field errors, "You become the administrator; next we'll help you set up" under the button. On success the new admin lands on **Get started** (§2), not on an empty admin overview. |
| Accept invitation | Preview text, form, lands on home | The invitation is a definition list (Organisation · Role · Email · Expires). Expired/used/revoked: an `EmptyState`-style message with "Ask your administrator for a new invitation". |
| Forgot / reset password | Two plain forms | Same layout; reset shows the live checklist; success links straight to sign-in with the email pre-filled. |
| Verify email | Plain states | Success / failure / missing-token states; signed-in failure offers **Send a new link** in place. |

---

## 2. First run for a new organisation admin (setup checklist)

### 2.1 Current

Sign-up → `/admin` (counts of zero, service health). Nothing says what to do next; inviting the team, MFA, blind-review settings and the first job are four separate places the admin must discover. A new HR manager who is invited lands on an empty Jobs list.

### 2.2 Optimal: **Get started** (`/welcome`, admins)

A single page, also linked from the sidebar as **Get started** with the number of open steps, until every step is done or the admin hides it. Each step is detected from real data, so it ticks itself when done elsewhere.

| # | Step | Done when | Action |
|---|---|---|---|
| 1 | Workspace created | always | — |
| 2 | Confirm your email | `user.emailVerified` | **Send link again** (in place, shows "Sent") |
| 3 | Turn on two-step verification | `GET /me/mfa` → `enabled` | **Set up** → `/account/security` |
| 4 | Check how reviews work | the admin pressed **Looks right** (stored per viewer) | Shows the current values inline: Blind review **on**, Share verification status **on/off**, Require two-step for staff **off** — **Looks right** or **Change** → `/admin/organization` |
| 5 | Invite your team | an invitation exists or the org has > 1 user | **Invite people** → `/admin/invitations` |
| 6 | Create your first job | jobs total > 0 | **Create a job** → `/jobs/new` |
| 7 | Publish it | a PUBLISHED job exists | **Open draft** → the newest draft |

A progress meter ("4 of 7 done") fills with the count-up motion; the next open step is marked **Next** and its action is the only primary button on the page. Completed steps collapse to one muted line with a check. **Hide checklist** stores the choice per viewer. Steps: sign-up → first published job = **5 page visits, each one click from the checklist**, no hunting.

New HR managers (not admins) instead get the **Jobs** empty state with "Create your first job" as the primary action and one sentence on how ranking works.

---

## 3. Jobs list (the home screen for managers and recruiters)

### 3.1 Current

Five counter cards (five requests, same icon in five tints), a filter row, a table with a redundant **Open →** column. Status is filtered by a select that duplicates the counters. On phones the table overflows by 62 px.

### 3.2 Optimal

| Element | Design |
|---|---|
| Header | H1 "Jobs"; primary **New job** (managers). No eyebrow, no subtitle. |
| Status bar | `Tabs` with counts **All · Published · Drafts · On hold · Closed** (counts in tabular numerals, count-up on first load). The tab *is* the filter (`?status=`). Five `size=1` requests fired in parallel and cached 60 s until a `GET /jobs/stats` exists (API gap). |
| Search | Debounced 300 ms, `?q=`, `/` focuses it. "No jobs match 'zzz'" + **Clear search**. |
| Table | Whole row is the link (Enter / click). Columns: **Title** (type · level under it) · Department · Location · **Status** badge · **Applicants** ("38 · 4 new", new = arrived since *you* last opened that job's applicants, stored per viewer) · **Closes** ("in 12 days", the date in a tooltip; "Closed"/"—") · Created (relative). |
| Row actions | "⋯" per row for managers: Edit · Publish / Put on hold / Resume · Close job. Hold and resume happen immediately with **Undo**; publish and close ask first. |
| Phone | Rows stack (title, status, applicants · closes). |

**States.** Skeleton of 8 rows (only after 150 ms). Error in place of the table with **Try again**. Empty per role (manager: "Create your first job"; recruiter: "Jobs your HR managers create appear here"). Filtered empty with **Clear filters**.
**Steps.** Find and open a job: **1** (was 1 but with hunting through counters + filter). Put a job on hold: **2** (⋯ → Put on hold) instead of 4 (open job → button → confirm → back).
**Deep link.** `/jobs?status=PUBLISHED&q=engineer&page=2`.

---

## 4. Creating a job (wizard)

### 4.1 Current

One long page: 10 position fields, then a requirements editor with seven controls per row (≈ 900 px for six rows), an optional "Draft requirements from description" panel, and a non-sticky save bar. The job is saved only at the end; a crash or a closed tab loses everything. Saving always creates a DRAFT; publishing is a second visit to the job page. Steps: **≈ 9 + 7 per requirement** (each row: type, must-have, gate, years, proficiency, alternatives, move).

### 4.2 Optimal: three steps, autosaved

`/jobs/new` → after step 1: `/jobs/:id/edit?step=requirements` (refresh-safe, deep-linkable).

| Step | Content | Saving |
|---|---|---|
| **1 Basics** | Title · Department · Location (two columns ≥ 600 px) · Employment type and Experience level as compact selects · Openings · Salary min/max with a **₹ affix and en-IN grouping on blur** · Deadline (date + time, "your local time") · Description (auto-grow, counter). Inline validation on blur; errors never appear before the user has touched a field. | Typing autosaves **on this device** every second ("Draft saved on this device"). Returning to `/jobs/new` offers **Resume draft from 10 min ago** or **Start over**. **Continue** validates, creates the DRAFT on the server and moves on. |
| **2 Requirements** | On arrival with no requirements and a usable description, the **AI compiler runs automatically** (skeleton "Reading your description…"). Suggestions appear in a panel: each with type, text, must-have / gate / years / proficiency, alternatives and the **rationale quote**, all pre-selected; **Wording to reconsider** warnings above them (age-coded, gender-coded, degree inflation, exclusionary, vague, excessive experience) with the suggested rewrite. **Add selected** (or **Replace**) moves them into the editor with a short slide. The editor is a compact list: drag handle · type · requirement text · **Must-have** switch · **Gate** switch (only for must-haves, tooltip "Holds an applicant in *Must-haves not yet verified* until verified. Never rejects.") · "⋯" disclosure for minimum years, proficiency and **alternatives** chips · remove (with **Undo**). Reorder by drag, or Alt+↑/↓ on the focused row. | Autosaves to the server 1.5 s after the last change (DRAFT only). Header indicator: "Saved · just now" / "Saving…" / "Couldn't save · Retry". |
| **3 Review & publish** | **Evidence preview**: requirements in ranking order (gates first) with their weight as a share of the total ("must-haves count double"), so the manager sees exactly how coverage will be counted — and a sentence that coverage is a share of weighted requirements with evidence, not a probability of success. **Checks** (each links back to its step): no requirements → applicants cannot be ranked; an *Other* requirement marked as a gate → every applicant would stay unverified (it can never be matched; we switch it off automatically and say so); years > 10 on an entry/mid role; unresolved wording warnings; deadline in the past. **What candidates see**: the requirements as the candidate portal lists them (must-have / nice-to-have markers, no gate information). | **Publish now** (primary; confirm names the consequence: "Candidates can find and apply right away") or **Keep as draft**. |

**Inline validation rules** (client mirrors the server): title, department, location, description required; openings ≥ 1; salary min ≤ max, both ≥ 0; deadline in the future; requirement text 1–500 characters; years 0–50 whole; at most 10 alternatives of ≤ 200 characters; a gate must be a must-have; at most 100 requirements.

**Steps after:** basics (typing) **1** → requirements with AI suggestions **1–2** (accept all, maybe tweak) → publish **2** (Publish + confirm). Typical new job: **≈ 5 activations** instead of ≈ 9 + 7 × N.

**Failure paths.** Job created but requirements failed to save: stay on step 2 with the rows intact and "Couldn't save · Retry". AI compiler unavailable (503): "Suggestions are unavailable right now. Add requirements by hand; you can try again later." No suggestions: "We could not find requirements in this description."

### 4.3 Editing, publishing, holding, closing

| Situation | Optimal flow |
|---|---|
| Edit a **draft** | Same wizard, every step reachable from the stepper (non-linear), autosave on. |
| Edit a **live** job (published / on hold) | Same three steps, **no autosave**. A sticky footer shows a change summary ("2 requirements changed · re-matches 38 applicants") and **Save changes**. Requirement changes confirm first: "Saving creates requirements version 4, is recorded in the audit log and re-matches 38 applicants." Closing or reloading the tab with unsaved changes asks first (in-app navigation cannot be blocked with the current `BrowserRouter`; the sticky bar keeps the unsaved state visible). |
| Closed / cancelled | "This job can no longer be edited" empty state with **Back to job**. |
| Publish a draft | Job header primary **Publish** → confirm with a pre-publish summary (N requirements, M gates, closes on …). Draft with no requirements: the confirm warns that applicants cannot be ranked until requirements exist. |
| Put on hold / resume | Header secondary button or list row menu; immediate, **Undo** in the toast. |
| Close | "⋯" → **Close job** → confirm ("stops applications and cannot be reopened"); danger button only inside the dialog. |
| Cancel / delete a draft | "⋯" → confirm. Delete returns to the jobs list with a toast. |

---

## 5. Job page

Header (B3): breadcrumb **Jobs / Senior Backend Engineer** · H1 + status badge + "Requirements v3" tag · meta line (department · location · type · level · closes in 12 days · 2 openings) · actions: **Edit** · the one primary status action (Publish / Resume publishing; *Put on hold* as secondary for published jobs) · "⋯" (Close job, Export audit CSV, Copy link, Cancel/Delete draft).
Tabs (`?tab=`): **Applicants 38 · Work samples 1 · Interview kit 6 · Fairness · Details**. They scroll horizontally with edge fades on phones.

---

## 6. Applicant ranking (the core daily flow)

### 6.1 Current

Card rows (75–100 px each, ~10 per screen) with two 250 px bars repeating their labels; nothing shows *which* requirements are evidenced; the open gate is a sentence. Filters: status and order selects. To compare two applicants the recruiter opens both pages in tabs. Per applicant: open (1), read, back (1), find your place. Decisions only on the applicant page.

### 6.2 Optimal

**Layout.** A compact ranking table (≥ 20 rows visible at 1440 × 900) grouped by the server's ranking groups, each group header with its count and one plain sentence:

| Group | Sentence |
|---|---|
| **Ranked** | Every gate requirement is verified. Ordered by verified coverage, then claimed coverage, then application date. |
| **Must-haves not yet verified** | At least one gate is not verified yet. Not rejected: check the evidence, a claim, a work sample or an interview. |
| **Unscored** | No match result yet: the resume is still being read, could not be read, or the job has no requirements. |

**Columns.** select · **#** (rank, tabular; "—" + "Not ranked" for screen readers outside Ranked) · **Applicant** (blind id or name, *Blind* tag, *New* tag if applied since your last visit, a flag icon with a tooltip for parser flags) · **Status** badge · **Evidence** — a strip of one ladder cell per requirement, must-haves first, a 6 px gap before nice-to-haves, open gates outlined in warning with a lock; hover/focus a cell: "Java 17 and Spring Boot · Corroborated · gate" · **Verified** (number + 48 px bar) · **With claims** (number + hatch bar) · **Applied** (relative). The two coverage columns carry the "share of weighted requirements with evidence, not a probability of success" tooltip on their headers; a one-time dismissible hint repeats it once.

The per-requirement strip needs per-requirement tiers, which the list endpoint does not return (API gap). Until a `requirements` summary is added to `ApplicantSummary`, the page fetches the detail of each visible applicant in the background (4 at a time, cached and shared with the applicant page, so opening an applicant is then instant). Gates are drawn from `unverifiedKnockouts` immediately; tiers fill in as details arrive (cells show a neutral skeleton meanwhile).

**Views and filters (all in the URL).** Saved views as a segmented control: **To review** (`status=SUBMITTED`) · **Shortlisted** · **Interviewing** · **All**; order **Evidence ranking / Newest first**; density toggle (per viewer). **Copy link to this view**. Page size 25, page in the URL.

**Keyboard triage** (hints in a footer bar; `?` opens the full sheet):

| Key | Action |
|---|---|
| `j` / `k` (or ↓ / ↑) | next / previous applicant |
| `Enter` | open the full applicant review (keeps list position for Back and for prev/next on the applicant page) |
| `Space` | quick peek (drawer) |
| `x` | select / unselect |
| `s` | shortlist (managers; SUBMITTED only) |
| `r` | reject / not progressed (managers) |
| `c` | compare the selected 2–3 |
| `?` / `Esc` | the shortcut sheet / close any overlay |

**Shortlist / reject from the list.** `s` or the row menu → a compact confirm: shortlist says *what becomes visible and to whom* (name, contact details, resume, cover letter; audited); reject offers an optional comment and says the candidate is notified. Confirm → the row updates **in place immediately** (badge flips, a thin progress line under the row) and a toast "Shortlisting Candidate #A-3014 · Undo" counts down **5 s before the request is sent**. Undo cancels it with no server call and no audit noise. On failure the row reverts and an error toast explains why (for example "This application changed in the meantime").

**Bulk.** With a selection the toolbar becomes "3 selected · Compare · Shortlist · Not progressed · Clear". There is no bulk endpoint (API gap), so the client sends one decision per applicant after the same single confirm + undo window, with a progress toast ("Shortlisting 3 of 3…") and a per-row result.

**Quick peek** (`?peek=<applicationId>`). A right drawer (bottom sheet on phones) over the list: name or blind id, status, rank and group, the coverage meter, every requirement with its ladder chip, status word (Matched / Unclear / Not observed), the evidence quote, and the "To verify" hint; flags explained; claims, work sample and interview counts. `j`/`k` move to the next applicant *inside* the drawer; **Open full review** is the primary; Shortlist and Not progressed are secondary for managers.

**Compare** (`?compare=3001,3014[,3022]`). A wide dialog: one column per applicant (2–3), one row per requirement (gates first), each cell a ladder chip and status word; coverage meters on top; differences are easy to spot because identical tiers are drawn quieter. Built client-side from the cached details. **Open** under each column.

**Re-rank motion.** After **Re-run matching** (or any background change picked up by polling) rows glide to their new positions (FLIP, 280 ms); a row that changed group gets a 1.2 s accent-subtle wash; the rank number crossfades. Reduced motion: no glide, a static highlight.

**States.** Skeleton rows that match the table. Matching in progress: a quiet status line "Matching 2 applicants…" and an indeterminate line inside those rows' strip cells; polling backs off and stops after 2 minutes with **Check again**. Empty: "No applicants yet" + "Applications appear here as candidates apply" (published) or **Publish job** (draft). Filtered empty: "No shortlisted applicants" + **Show all**. Error: in place with **Try again**.

**Steps.** Screening ten applicants and shortlisting two: today ≈ 10 × (open + back + scroll) + 2 × (shortlist + confirm) = **≈ 24 + scrolling**. After: `j`/`Space` through the list (no page loads), `s` + Enter twice = **≈ 14 key presses, zero page loads**.

---

## 7. Work samples tab

| | Current | Optimal |
|---|---|---|
| List | Cards with buttons per card | Rows: title, status badge, time limit, criteria count, updated. Expand (M12) shows instructions and the rubric with anchors in a **four-column grid (1–4)** and the linked requirement marker. |
| Generate | Button → draft appears → inline editor | **Generate from requirements** (needs requirements; disabled reason in a tooltip) → the editor opens in a **drawer** with the generated draft → **Save** → toast with **Activate** as its action (one click to the next step). |
| Edit | Inline form inside a card | Drawer (640 px): title, time limit (5–480, "+5 minutes grace"), instructions, rubric criteria with requirement link and four anchors (all required; errors per field). |
| Activate / archive | Confirm dialogs | Same confirms (activation freezes edits so every candidate gets the same task). |
| Assignments | Table with a status select | Table with a segmented filter **To score · In progress · Scored · All**, rows link to the applicant (`?tab=work-samples` there). Empty per filter. |

## 8. Interview kit tab

| | Current | Optimal |
|---|---|---|
| View | Cards; heading collides with the first card | Numbered question list; each question shows "Probes" with the requirement marker, follow-ups as a list, anchors in a four-column grid. A coverage line: "Every must-have has a question" or "No question yet for: Kubernetes, AWS certification". |
| Generate / regenerate | Buttons | **Generate kit** (empty state primary). Regenerate asks first ("replaces your edits; submitted scorecards keep their ratings"). |
| Edit | Whole-kit inline form | Same, with a sticky save bar ("Unsaved changes · Cancel · Save kit"), per-question reorder and remove with Undo. |

## 9. Fairness tab (managers)

| | Current | Optimal |
|---|---|---|
| Range | Two date fields + Apply + All time | Presets **Last 30 days · Last 90 days · This year · All time** plus **Custom** (from/to, inclusive, UTC days, validated). `?from&to` in the URL; invalid URLs fall back to all time with a note. |
| Report | Summary cards + one long explainer paragraph + tables | A stat row (scope, survey response rate, smallest group shown) · a two-line explainer with "How to read this report" disclosure · one table per dimension: rows are stages, columns are groups ("62% · 18 reached"), the impact ratio with a small bar against the **0.80** tick. Flagged rows: warning left rule + "Flagged" text badge + "a reason to look closer, not proof of bias". Suppressed groups "<5" with a screen-reader explanation. |
| Empty | Sentence | "No survey responses in this period" + **Show all time**. |

## 10. Audit export (job)

Current: a header button among five. Optimal: job header "⋯" → **Export audit CSV** → the download starts at once; a toast "job-101-audit.csv downloaded. The export itself is recorded in the audit log." Errors stay as a danger toast with **Try again**. **1 step** (was 1, but now not competing with Close).

## 11. Navigation and status pages

- Sidebar (unchanged groups, capability-filtered): Workspace › **Get started** (admins, until done or hidden; count of open steps) · Jobs · My interviews · Inbox; Administration › … (part B).
- Page not found / not available for your role: left-aligned empty state with the reason and **Go to your home screen**; for 403 the sentence "The server checks every action, whatever this page shows" stays.
- Every page sets `document.title` to "{Page} · {brand}" and moves focus to its H1 after navigation.

---

## 12. API gaps found while designing (no backend change made)

| Gap | Effect today | Proposed endpoint |
|---|---|---|
| Per-requirement tiers in the applicant list | The ranking fetches each visible applicant's detail in the background to draw the evidence strip | Add `requirements: [{requirementId, status, tier}]` to `ApplicantSummary` |
| Job counts by status | Five `size=1` list calls | `GET /jobs/stats` → `{ALL, DRAFT, PUBLISHED, ON_HOLD, CLOSED, CANCELLED}` |
| Bulk decisions | One `POST …/decisions` per applicant | `POST /hr/jobs/{id}/decisions:bulk {applicationIds, action, comment}` |
| "New since last visit" | Stored per viewer in the browser (lost across devices) | `lastViewedAt` per user and job |
| Ordinary job status changes are not audited (handbook 02 §2.2) | Only admin overrides and auto-close appear in the audit log | Audit `JOB_STATUS_CHANGED` |
