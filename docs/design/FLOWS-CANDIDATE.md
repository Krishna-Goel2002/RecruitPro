# Candidate portal: user flows, current and redesigned

**Status:** design for implementation on `redesign` (16 Nov 2026). **Owner:** candidate-portal engineer.
**Read with:** [UX_REVIEW.md](UX_REVIEW.md) (finding ids such as C-1, X-16), [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) (§ references), [REDESIGN_PLAN.md](REDESIGN_PLAN.md) (A1–A11), handbook chapter 04 (routes and APIs) and chapter 03 (verification rules).

This document maps every candidate feature from the first visit to the referee's answer. For each flow it lists what a candidate does today (clicks counted on the happy path, from the code on this branch), the friction, and the redesigned flow. Only existing endpoints are used; where a better flow would need a new API, the redesign works around it and the gap is listed in §14.

---

## 0. Principles applied to every flow

| Principle | What it means in this portal |
|---|---|
| **One obvious next action** | Every screen has at most one primary button, and it is the thing the candidate most likely needs next. Overview, application detail and the Strengthen page each open with a "next step" block computed from live data. |
| **Fewest steps** | Uploads start on drop or choose (no second "Upload" click). Apply can upload a resume inside the dialog. Ownership proof and the first check continue in the same drawer as connecting a source. |
| **Progressive disclosure** | Summary first (chip, ladder, count); the why (reasons, quotes, fingerprints) one interaction away in a drawer or row expansion; never more than one level (DESIGN_SYSTEM principle 4). |
| **Inline validation** | Fields validate on blur and on submit; errors sit under the field (`Field` wires `aria-describedby`); server field errors map per field; focus moves to the first invalid field. Buttons are never disabled to mean "not valid yet". |
| **Optimistic where safe** | Mark-as-read, verdict share/hide status, claim creation and the first-run checklist update in place immediately and roll back on error. Destructive server actions (withdraw, revoke, delete claim, delete account) keep a confirmation, because the server re-checks applications or deletes evidence and cannot undo. Undo is offered only where the action is local (hiding the checklist). |
| **Autosave drafts** | Cover letter (per job) and work-sample response (per assignment) are saved to `localStorage` as the candidate types, with a "Draft saved" indicator, and restored after a reload or an accidental close. Cleared on successful submit. |
| **Resumable wizards** | The add-source drawer keeps its step in the URL (`?add=source&step=prove&source=42`), so a reload, the GitHub OAuth round trip or a later visit lands on the same step. An unproven source row offers "Prove ownership", which reopens the drawer at that step. |
| **Deep links** | Every notification links to its subject (application, work sample, interview, results to review). `?review=first` on `/verification` opens the first result waiting for review. |
| **Empty states lead on** | Each empty state says why it is empty and offers the one action that fills it (DESIGN_SYSTEM §9.25). |
| **States** | Every data region has a layout-matched skeleton (after 150 ms), an inline error with **Try again**, an empty state and a success state, each with a harness fixture. |
| **Keyboard** | The primary task of every screen works with the keyboard alone; dialogs and drawers trap focus, close on Escape and return focus; after navigation focus lands on the H1; the verdict drawer supports `J`/`K` for next/previous result. |
| **Motion explains change** | Route entrance (M1), list stagger on first load only (M2), skeletons (M3), count-ups on the Overview and Strengthen stats (M4), ladder fill (M5), verdict reveal when a check finishes (M6), dialogs and drawers (M8, M9), toasts (M10). All honour `prefers-reduced-motion`. |

---

## 1. Sign-up → email verification → first-run onboarding

### Today (5 pages, 8 clicks before anything useful happens)

| # | Step | Friction |
|---|---|---|
| 1 | `/register`: 5 fields, live checklist with `✓/○` glyphs, **Create account** | Glyphs render differently per OS (X-3); nothing says what happens next. |
| 2 | Lands on `/jobs` with an amber verify-email banner | No guidance; the banner dominates; "Not now" lasts one page load (X-16). |
| 3 | Opens email → `/verify-email?token` → **Continue to jobs** | Always returns to Jobs, never to where the candidate was. |
| 4 | Discovers a resume is required only inside the apply dialog | A dead end at the most motivated moment. |

### Redesigned (3 pages, 5 clicks to a ready profile)

| # | Step | Detail |
|---|---|---|
| 1 | `/register` | Split auth layout (form left, quiet evidence-ladder specimen right on ≥ 900 px). Names side by side; email; password with show/hide and an SVG checklist; confirm. Inline validation on blur. Under the button: "Next: confirm your email, then add your resume." |
| 2 | **Create account** → `/home` (new Overview) | A toast confirms the account. The Overview opens with a **Getting started** checklist: *Confirm your email · Add your resume · Review what we read · Strengthen with a source · Apply to a role*. Each step is ticked from live data; the current step is expanded with its one action. |
| 3 | Step "Add your resume" | A dropzone **inside the checklist**: drop or choose → uploads immediately → the step ticks with the ladder-fill motion and the next step ("Review what we read") expands with a link to the analysis. |
| 4 | Email link → `/verify-email` | Success shows the address and **Continue** to the Overview (signed in) or **Sign in** (signed out). Failure keeps "Links expire after 24 hours and work once" and offers **Send a new link** in place. |
| 5 | Verify-email banner | One line; **Resend link** and dismiss. Dismissal is remembered for the browser session (`sessionStorage`), and the checklist carries the same step, so nothing is lost. |

Checklist rules: steps 1, 2, 4 and 5 are derived from the API (`emailVerified`, resumes, sources, applications). Step 3 ("Review what we read") has no server flag, so it is ticked when the candidate has opened the analysis of a completed resume (per-viewer `localStorage`, listed as an API gap). The checklist hides itself when every step is done and can be hidden earlier with **Hide checklist** (with **Undo** in the toast).

---

## 2. Profile

| | Today | Redesigned |
|---|---|---|
| Layout | 13 fields in one long grid at equal weight; identity card boxed | Settings layout: sections **Work**, **Location**, **Links**, **Expectations**, **About you**, each with a one-line description on the left (≥ 900 px) and fields on the right. Identity (avatar, name, email, verification state) is a plain header row. |
| Save | **Save profile** at the bottom of the long form | A sticky save bar appears only when something changed ("Unsaved changes · Discard · Save changes"). `Ctrl/⌘ + S` saves. Leaving with unsaved changes asks first (`beforeunload`). |
| Validation | On submit only | On blur per field (URLs, number ranges) and on submit; ₹ affix on salary. |
| Password | Separate form below | Same, as its own section with show/hide fields and the SVG checklist. |
| Clicks to change one field | scroll + 1 | 1 (the sticky bar is always in reach) |

---

## 3. Resume upload → analysis → fact review

### Today (crashed before the TDZ fix; 3 clicks to upload, facts in a card grid)

Choose file → **Upload file** → the list card expands → status text polls → facts appear as cards grouped by type → **Suggest a correction** opens a textarea in the card.

### Redesigned (1 action to upload; review in place)

| # | Step | Detail |
|---|---|---|
| 1 | Drop or choose a file | `FileDropzone` checks type and size on the client, then uploads at once. The new file appears at the top of the list in an "uploading" state, then "Queued", "Reading your resume". |
| 2 | Analysis | Master–detail at ≥ 900 px: files on the left (status per file, newest first), the selected file's analysis on the right (kept in `?resume=`). While analysing: a skeleton of the facts and an info line that the page updates by itself; after 2 minutes the stalled message with **Check again**. |
| 3 | "What we read" | Facts grouped (Skills, Experience, Education, Certifications, Contact) as rows: value, ladder chip ("On your resume"), the quoted line from the file and a provenance line ("Resume · Experience section"). Rows stagger in on first load. |
| 4 | Correct a fact | **Suggest a correction** expands the row (M12) with a prefilled field; **Save as my claim** (Enter) saves; the row then shows both the text read and "Your claim" with a dashed rung. |
| 5 | Next | Under the facts: "Looks right? Strengthen these with a source" → `/verification?add=source`. |
| Flags | "Notes about this file" collapsed into one disclosure with a count, in neutral words. |
| Failure states | FAILED: reason + **Retry analysis**. NEEDS_OCR / UNSUPPORTED_FORMAT: what to upload instead, with the dropzone right there. |

---

## 4. Job discovery → job detail → apply

### Today (Flow A in UX_REVIEW: 14 clicks, 5 pages)

Four stacked filters (the first phone screen is all filters), cards with a duplicate ↗ tab stop, no "Applied" marker; job detail with red must-haves and no sense of fit; apply dialog without visible steps; no resume means leaving the dialog.

### Redesigned

| # | Step | Detail |
|---|---|---|
| 1 | `/jobs` | Search field with an icon (debounced 300 ms into the URL) plus Department, Location and Job type inline on desktop. **Phone:** search + **Filters** button with a count badge that opens a bottom sheet. Results start above the fold on a 390 px phone. |
| 2 | Results | Hairline-separated rows, each a single link (one tab stop): title, organisation · location · type · level, salary, "3 must-haves", "Closes in 12 days" (absolute date in a tooltip), and an **Applied** badge when the candidate already applied (joined with the applications list). Skeleton rows while loading; "Updating…" keeps the old rows dimmed; filtered-empty shows **Clear filters**. |
| 3 | `/jobs/:id` | Breadcrumb back to the same search. Reading column (description, requirements with must-have / nice-to-have markers and the gate lock, never red) and a sticky right rail: key facts, **Apply** (or **View your application** when already applied), and **How your evidence matches**: "3 of 5 requirements found in your resume and claims", one ladder chip per requirement, and "Strengthen your evidence" when a must-have is missing or only self-claimed. The match is a client-side exact/alternative text match, labelled as an estimate. Phone: a sticky bottom bar with **Apply**. |
| 4 | **Apply** → dialog step 1 "Resume" | Stepper *Resume · Review · Sent*. The newest analysed resume is preselected (radio cards with analysis state). No resume? An upload dropzone **inside the dialog**; the uploaded file is selected automatically. Cover letter auto-grows from 5 rows, counts characters and autosaves a draft per job. |
| 5 | Step 2 "Review" | Role, resume, cover-letter state, contact email, and **Evidence that goes with it**: the requirement match chips and the number of verification results you have shared. One line on blind review: "Your name and cover letter stay hidden until the employer shortlists you." **Submit application**. |
| 6 | Step 3 "Sent" | The step ticks; "What happens next" (a 3-item timeline: blind review → shortlist → tasks or interview). **View application**; **Strengthen this application** when a must-have is not evidenced. A 409 (already applied) shows "You already applied" with a link to that application instead of an error. |

Clicks, first visit with a resume: **today 4** (card, Apply now, Review, Submit) → **redesigned 4** with less reading; **without a resume: today 9 + a page change** (close dialog, Resumes, choose, Upload, back, Apply, Review, Submit) → **redesigned 5** (Apply, choose file, Review, Submit, plus the card).

---

## 5. Application tracking → work sample → interview

### Applications list

| | Today | Redesigned |
|---|---|---|
| Rows | Cards: title, organisation, applied date, resume name, status | Table (stacked rows on phone): Role + organisation · Status badge · **Next step** ("Start work sample by Wed 18 Nov", "Interview Thu 19 Nov, 10:15", "With the employer", "Nothing more needed") · Updated (relative, absolute in a tooltip). Whole row opens the detail; ↑/↓/Enter work. |
| Empty | "No applications yet" + Browse jobs | Same sentence plus **Find jobs**; if no resume yet, the action is **Add your resume** first. |

The list endpoint has no work-sample or interview data, so the next step is derived by joining `/candidates/me/work-samples` and `/candidates/me/interviews` by job title (API gap: a `nextStep` field or `applicationId` on those lists).

### Application detail (C-1)

| # | Today | Redesigned |
|---|---|---|
| Header | "APPLICATION #3001" eyebrow, **Withdraw** as the only button | Breadcrumb · H1 job title + status badge · organisation · applied date. Actions: **View job** and a "⋯" menu holding **Withdraw application** (confirm kept: names the job and says it cannot be undone). |
| Next step | Work samples and interviews at the bottom | A **Next step** panel directly under the header: the pending work sample ("Design an idempotent refunds endpoint · 90 minutes · start by Wed 18 Nov" + **Open work sample**), or the next interview (date, time with zone name, **Join** for a meeting link, **Add to calendar** .ics), or a plain sentence for the status. |
| Progress | Past statuses only | `Timeline` with done, current and future steps (Submitted → Shortlisted → Interview → Decision), so "what's next" is visible; a withdrawn or not-progressed application ends the line honestly. |
| Requirements | Badges "Verified / Your claim — unverified / Not yet verified" + "+ Add evidence" | Rows with must-have markers and a status word; unverified rows offer **Add evidence** (drawer with the claim form, prefilled) and **Verify with a source** (→ Strengthen). |
| What you sent | Long cover letter inline | Resume link and a "Cover letter" disclosure. |

### Work sample: start → timer → submit with files

| # | Today | Redesigned |
|---|---|---|
| 1 | Notification (not a link) → Work samples → card → "Open →" | Notification links straight to `/work-samples/:id`; the Overview and the application's Next step link there too. |
| 2 | Rules list (outdated file copy) + **Start the work sample** → confirm | Rules as a checklist with the corrected attachment copy; the button reads **Start, 90-minute timer begins**; the confirm repeats that the timer cannot be paused. |
| 3 | Timer card at the top; long form below | A **sticky timer bar** (time left, end time, `Meter` of elapsed time) that stays in view while writing; warning tone from 5 minutes; the numeral pulses in the last minute (M16; colour only under reduced motion); screen readers hear 10/5/1-minute marks only. |
| 4 | Response textarea, link, file input + list | Response auto-grows and **autosaves a draft** every few seconds (restored after reload, "Draft saved 12:41"); attachments in a `FileDropzone` (≤ 3, type/size checked); link field. |
| 5 | **Submit response** → confirm | Same confirm with the number of files; success replaces the form with "Submitted" and the downloadable file list; focus to the H1. |
| Expired / cancelled | Plain alerts | Same content; the unsent text is shown read-only to copy, with a **Copy** button. |

Clicks from notification to started timer: **today 5** → **redesigned 3** (notification, Start, confirm).

### Interviews

Upcoming and past lists with a date block, "Thu 19 Nov, 10:15 – 11:00 · IST (UTC+5:30)", **Join** for http(s) locations and **Add to calendar** (client-side .ics). Empty: "No interviews yet" + what happens when one is scheduled.

---

## 6. Evidence / claims

| | Today | Redesigned |
|---|---|---|
| Ladder explainer | Collapsed `<details>` | The compact `EvidenceLadder` legend always visible at the top (five rungs, candidate labels). |
| Add a claim | Form in a boxed column beside the list | **Add a claim** opens a drawer (bottom sheet on phone) with type, statement, optional link; the new claim is inserted at the top optimistically with the entering motion; focus returns to the button. |
| List | Cards with a red **Delete** on each | Rows: ladder chip, type, statement, link, review state ("Not checked yet" / "Confirmed by a recruiter on …"). Delete lives in a row "⋯" menu; the confirm explains that applications are re-checked without it. |
| Bridge | none | Each unverified claim row offers **Verify with a source** (→ Strengthen with the add-source drawer open). |

---

## 7. Strengthen your application (connect → consent → prove → run → review → share; references)

### Today (four long sections; crashed before the TDZ fix)

How it works (static) → Sources → Connect a source (radio list, consent, button) → Results (ledger) → References (long form). Verdict details in a modal that hides the ledger; share is deep in that modal; no sense of progress.

### Redesigned page (`/verification`, nav "My evidence › Strengthen")

```
Strengthen your application                         [Ask for a reference] [Add a source]
Connect places that show your work. Checkers read them, and you decide which results employers may see.

Claims with outside evidence 2 of 5 · Waiting for your review 2 · Shared with employers 1 · Sources 2   (count-up once)

Waiting for your review (2)                                          [Review all]
  PostgreSQL           ◐ Partly verified        github.com/meera-iyer   2 h ago   ›
  Kubernetes           ○ Not found in connected sources  github.com/meera-iyer     ›

Sources
  GitHub  github.com/meera-iyer   KeyRound Ownership proven · GitHub sign-in   Checked 26 h ago   [Run check] ⋯
  Website meera.dev               Ownership not proven yet                     Never checked     [Prove ownership] ⋯

All your claims                                   "Not found in connected sources" is neutral…
  Java          ▁▃ On your resume     ✓ Verified · Shared
  PostgreSQL    ▁▃ On your resume     ◐ Partly verified · Awaiting your review
  …
References  (1 of 5 open)                                          [Ask for a reference]
```

Empty (no sources): the four steps of how it works as a horizontal sequence with **Add your first source**.

### Add a source: one drawer, four steps (Stepper *Choose · Connect · Prove · Check*)

| # | Step | Detail |
|---|---|---|
| 1 | **Choose** | Five radio cards with icons (GitHub, GitLab, Website, Document, Open Badge) and one line each. Enter/Next continues. |
| 2 | **Connect** | Only the field the kind needs (username, https URL, file dropzone, badge link or pasted credential). The **consent checkbox sits directly above the connect button**, with the per-kind sentence unchanged (versioned server-side). GitHub: **Continue with GitHub** (OAuth; ownership proven on return) or "Use my username instead"; a 503 OAUTH_NOT_CONFIGURED switches to the username field automatically. 409 → "already connected". |
| 3 | **Prove** (GitHub by username, GitLab, Website) | Method tabs (DNS TXT · Text file · Page code; or Profile bio) with `CodeField`s and copy buttons; **Check now** → inline outcome (proven → the step ticks and advances; not yet → "We could not find the code yet…"; pending → try again). **Skip for now** is allowed and says unproven results are capped at "Partly verified". Documents and badges skip this step with the reason. |
| 4 | **Check** | **Run the first check**: the drawer shows live progress (indeterminate line, "Reading github.com/meera-iyer… usually under a minute"; polling with backoff, 2-minute window, then **Check again**). On completion the result counts reveal ("3 results: 1 Verified, 1 Partly verified, 1 Not found") and **Review results** opens the review drawer at the first new result. The candidate may close the drawer at any time; the page keeps polling and the source row shows the same progress. |

The step lives in the URL, so the GitHub round trip returns to step 4 (`?connected=github` → drawer opens on *Check* for the newly connected GitHub source), and **Prove ownership** on any source row reopens step 3.

### Run → review → share

| # | Step | Detail |
|---|---|---|
| 1 | Run (from a source row or step 4) | The row shows a progress line; ledger rows that this source will check show **Checking…** chips. |
| 2 | Finish | The ledger refetches; each checking chip **morphs** into its verdict (M6) in reading order, 60 ms apart; a toast says how many results are ready, with **Review**. |
| 3 | Review | **Review all** or any row opens the **verdict drawer** (480 px; bottom sheet on phone; the list stays visible): verdict chip and status, claim, source and ownership, **Why** (reasons with quotes), **Evidence** (title, link, provenance line, fingerprint in a `CodeField`), **Notes for context** (intro unchanged), **An interviewer may ask**, and **What employers see if you share** (verdict, reasons and notes; links and your note only after they reveal your identity). |
| 4 | Decide | Sticky footer: **Hide** · **Re-check** (not for references) · **Share with employers** (primary). The status updates in place immediately (optimistic, rolled back on error) and the drawer advances to the **next result waiting** (`J`/`K` or the arrows in the header). An optional "Add a note for employers" expands on demand. |
| 5 | Done | When nothing is left to review: "All results reviewed" with the counts and **Close**. |

Clicks to share the first result after connecting GitHub by sign-in: **today 8+** with scrolling (Sign in, approve, scroll to the source, Run, wait, scroll to results, open, Share) → **redesigned 5** (Continue with GitHub, approve, Run the first check, Review results, Share).

### References

**Ask for a reference** opens a two-step drawer: *Who* (name, email with the work-address hint, relationship, optional employer and domain) → *What to confirm* (checkbox list of claims with ladder chips, 1–20, with a live count) + the consent sentence unchanged → **Send request**. The list shows status badges, "2 of 5 open", the expiry and "N results in your review list"; **Cancel request** sits in a row menu with a confirm. At the limit the button is replaced by the explanation.

---

## 8. Notifications

| | Today | Redesigned |
|---|---|---|
| Items | Subject + body, not links; per-item **Mark as read** | Each row links to its subject: application status → `/applications/:id` (matched by job title from the subject), work sample → `/work-samples/:id` (matched by title), interview → `/interviews`, results ready → `/verification?review=first`, reference answered → `/verification#references`. Opening marks it read (optimistic). An icon per kind; unread dot + "Unread" for screen readers; grouped *Today* / *Earlier*. |
| Bulk | **Mark all as read** | Same, optimistic, in the header. |
| Bell | Count badge | Same, plus the Overview's "Needs your attention" list. |

Clicks from bell to the work sample: **today 4** (bell, read, nav, card) → **redesigned 2**.

---

## 9. Privacy (survey, export, delete)

| | Today | Redesigned |
|---|---|---|
| Survey | Long boxed panel | Section with "Why we ask" as a short list beside the four selects (2 columns from 900 px), the consent sentence unchanged, **Save answers**; stored answers show "Saved on …" with **Delete my answers** (confirm). |
| Export | Boxed panel | One row: what the file contains + **Download my data** (loading state, toast). |
| Delete | Red panel with password form | The only bordered box on the page (`Panel tone="danger"`), consequences as a list, password field, **Delete my account…** → confirm dialog naming the consequence; Cancel focused first. |

---

## 10. Referee public page (`/reference/:token`)

### Today (C-2): about 2,069 px on a phone for three claims

Four 60 px radio cards per claim, a comment box per claim, the identity checkbox and the button at the very bottom.

### Redesigned (target < 1,000 px at 390 px for three claims)

| # | Step | Detail |
|---|---|---|
| 1 | Open the link | Brand mark (from the brand preset), a theme toggle, H1 "Can you confirm Meera's work?", two-line intro (relationship, employer, the "Cannot confirm is a perfectly good answer" line) and the validity date as a small tag. |
| 2 | Answer | Once at the top, the helper line explaining the four answers. Per claim: the claim text and a four-option `SegmentedControl` (Confirm · Partly · Can't confirm · Disagree; native radios, arrow keys) with **Add a comment** as a disclosure. |
| 3 | Send | A sticky footer: "2 of 3 answered" + **Send answers**; pressing it with gaps scrolls to and focuses the first unanswered claim. The identity checkbox sits just above the footer. "I would rather not answer" is a quiet link with a confirm. |
| 4 | Outcome | "Thank you" with "they see results, not your words" kept; "Request declined"; closed (410) and invalid (404) are empty states with the brand mark. |

---

## 11. Overview (`/home`, new landing page)

Signed-in candidates land here (it replaces `/jobs` as the default target; `/jobs` stays one click away).

1. **Getting started** checklist (§1) until done or hidden.
2. **Needs your attention**: a list built from live data, most urgent first: work samples to start (with start-by), a work sample in progress (time left), the next interview (Join), results waiting for review, references answered. Empty: "Nothing needs you right now."
3. **Your applications**: the three most recent with status and next step; **All applications**.
4. **Your evidence**: counts per ladder rung across resume facts and claims (a five-row ladder histogram with count-ups), "2 results shared with employers", and **Strengthen** as the action.

---

## 12. Navigation

Sidebar: **Overview · Find jobs · Applications** · *My evidence*: **Resume · Claims · Strengthen** (count = results waiting) · *Tasks*: **Work samples** (count = to start) · **Interviews** · **Inbox** (unread). Profile, Privacy, Theme and Sign out are in the account menu. All old URLs keep working.

---

## 13. Step counts, before → after

| Flow | Before | After |
|---|---|---|
| Register → ready to apply (resume uploaded) | 8 clicks, 4 pages, resume requirement discovered late | 5 clicks, 2 pages, guided |
| Apply without a resume | 9 clicks + leave the dialog | 5 clicks, in one dialog |
| Apply with a resume | 4 clicks | 4 clicks, evidence summary included |
| Upload a resume | 2 actions after the picker | 1 action (drop or choose) |
| Connect GitHub → share first result | 8+ clicks with scrolling | 5 clicks in one drawer flow |
| Website: connect → prove → first check | 2 forms + a dialog + a separate Run button (7 clicks) | 1 drawer, 5 clicks |
| Notification → start a work sample | 5 clicks | 3 clicks |
| Find the next step of an application | open + scroll to the bottom | visible in the list and at the top of the detail |
| Referee, 3 claims on a phone | ≈ 2,069 px, 4 radio cards per claim | < 1,000 px, one segmented control per claim |

## 14. API gaps (designed around; not implemented)

1. **Next step on the applications list**: the list has no work-sample or interview data; the client joins by job title. A `nextStep` (or `applicationId` on work samples and interviews) would make it exact.
2. **Notification links**: notifications carry `template`, `subject` and `body` only; the client matches the job or task title in the subject. A `link` or subject id per notification would make every link exact.
3. **"Reviewed the analysis"** has no server state; it is kept per browser.
4. **Requirement match on job detail** is a client-side text match against resume facts and claims; the server's match result is only available after applying. A `GET /jobs/{id}/match-preview` would replace the estimate.
5. **Which claims a run will check** is not exposed; "Checking…" chips are shown on the claims a source can check (skills for code hosts, all claims otherwise).
6. **Upload progress**: `fetch` gives no upload progress; uploads show an indeterminate bar.
7. **Draft persistence** for cover letters and work-sample responses is client-side (`localStorage`); a server draft would survive a device change.
