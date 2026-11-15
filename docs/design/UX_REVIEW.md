# UX review: candidate portal and HR portal

**Date:** 16 Nov 2026 · **Branch:** `redesign` · **Reviewer:** principal product designer
**Scope:** every route in `docs/handbook/04-portals-and-user-flows.md`, the verification UX in `03-claim-verification.md`, and the current code in `frontend/src/**` and `hr-frontend/src/**`.
**Companion documents:** [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) (the spec) and [REDESIGN_PLAN.md](REDESIGN_PLAN.md) (the build order).

## 0. Method and evidence

- Both portals were built from this branch (`pnpm build`) and rendered in headless Chromium at **1440×900** and **390×844** against fixture API answers, using the harness in `tools/ui-snapshots/` (38 screens and states, 76 captures; a curated 32 are kept in `docs/design/screenshots/before/`). That harness is now the acceptance gate for the redesign.
- Every finding cites a **screen** (`docs/design/screenshots/before/<portal>-<screen>-<width>.png`) and an **element** (CSS class or component).
- Accessibility: axe-core 4.10 (WCAG 2.0/2.1 A + AA rules) ran on every screen at 1440 px. Contrast ratios in this document were calculated with the WCAG formula, not estimated.
- Code facts (colour counts, radii, font sizes) were counted from the four CSS files of each portal.

**Severity.** **Blocking**: breaks a demo-critical task or a stated requirement (WCAG 2.1 AA, dark mode). **Major**: slows or misleads a primary task, or undermines trust. **Minor**: polish and consistency.

### Numbers that frame the review

| | Candidate | HR |
|---|---|---|
| Screens that crash to a blank page | **2** (`/resumes`, `/verification`) | 0 |
| axe serious violations (1440 px, light) | 0 | **17 of 17 screens** (`color-contrast` 1–73 nodes per screen; `aria-prohibited-attr` ×4) |
| Unique colour literals in CSS | 87 | **293** |
| Distinct `border-radius` values | 12 | 13 |
| Font sizes in use | 10–25 px (13 sizes) | **8–48 px** (15 sizes; 11 px ×44, 12 px ×84, ≤10 px ×9) |
| Fonts | DM Sans / Manrope named but **not loaded**: OS fallback | DM Sans named, not loaded |
| Dark mode | none | none |
| Accent | green `#176B55` | teal `#127D78` |

---

## 1. Executive summary: the ten things to fix first

| # | Finding | Severity | Where |
|---|---|---|---|
| C-0 | **"Strengthen your application" and "Resumes" render a blank page.** `refetchInterval` reads `polling` before its `const` is initialised (TDZ). The production build throws `Cannot access 'c' before initialization`. | **Blocking** | `pages/verification/SourcesSection.tsx:41,45`; `pages/resumes/useResumeAnalysis.ts:25,30`; screenshot `candidate-verification-1440.png` (white) |
| H-1 | **Applicant detail: the evidence table slides under the side column at 1440 px.** The "Evidence and explanation" column is clipped beneath "Candidate identity". This is the most important screen in the product. | **Blocking** | `.applicant-layout` (`minmax(0,1fr) 340px`) with an unwrapped wide table; `hr-applicant-blind-1440.png` |
| H-2 | **HR text contrast fails AA on every screen.** `--muted #718087` is 4.09:1 on white and 3.80:1 on the page background. The sidebar footer `#7E9A97` and eyebrow `#43897F`/`#4C9186` also fail. | **Blocking** (AA) | axe `color-contrast` on `.sidebar-version`, `.user-name > small`, `.eyebrow`, `.back-link` |
| X-1 | **No dark mode, no theme tokens.** HR has no colour tokens at all (293 literals), so theming is impossible without a token layer. | **Blocking** (owner requirement) | `hr-frontend/src/styles/*.css` |
| X-2 | **Two products, not one.** The portals use different greens, radii, type scales, icon approaches (Unicode glyphs vs SVG), dialogs (native `<dialog>` vs custom portal), toasts and spacing. | Major | compare `candidate-jobs-1440` and `hr-jobs-1440` |
| X-3 | **Status meaning rides on font glyphs that do not render everywhere.** `◐` showed as `‹`, `🔒` as an empty box, and the candidate nav icons (`⌕ ▣ ▤ ◆ ✎ ◷ ◉ ◈`) differ by OS. Contradicted is `✕` for candidates but `!` for HR. | Major (trust) | `lib/verification.ts` (both), `components/Layout.tsx` (candidate), `Verification.tsx:21` |
| H-3 | **The ranking cannot be scanned.** Each applicant is a 75–100 px card. Two 250 px bars repeat the same labels on every row, and nothing shows *which* requirements are evidenced. A recruiter must open every applicant to compare. | Major | `hr-job-applicants-1440.png`, `.applicant-row` |
| X-4 | **"Card soup".** Boxes sit three to four levels deep (section card › claim card › verdict card › grey callout), each with its own border, radius and padding. This is the main reason the UI "feels generated". | Major | `hr-applicant-verification-1440.png`, `candidate-evidence-1440.png` |
| C-1 | **The next step is buried.** Application detail puts **Withdraw** (destructive) beside the status. The pending work sample ("start by Nov 18") and the interview sit at the bottom of the page. | Major | `candidate-application-detail-1440.png` |
| C-2 | **Referee page is 2,069 px tall on a phone** for three claims: four 60 px radio cards plus a comment box per claim. Referees are busy strangers, and every extra scroll costs answers. | Major | `candidate-referee-390.png` |

---

## 2. Cross-cutting findings (both portals)

### 2.1 Visual language and "AI-made" signals

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-2 | Two accents (`#176B55` vs `#127D78`), two sidebar styles (light vs dark teal), 12–13 radii each (5, 6, 7, 8, 9, 10, 11, 12, 14, 16, 20 px, 999), 20 different HR shadows. | Major | CSS counts above | One token set (`src/design/tokens.css`, copied to both portals). Radii 2/4/6/8 only, and shadows only on floating layers (DESIGN_SYSTEM §3–5). |
| X-4 | Nested bordered boxes. A verdict on the HR Verification tab is a section card › claim card › verdict card › grey "Suggested interview question" box. | Major | `hr-applicant-verification-1440` | One surface per region. Group with hairline dividers, spacing and a 2 px left rule for quotes. A box is only allowed when the thing is independently actionable (a dialog, a selectable row, a dropzone). |
| X-5 | Generic decoration: the HR sign-in has a radial gradient, concentric rings, a glass "EVIDENCE LADDER" card and filler copy ("PEOPLE FIRST · ALWAYS", "Built for teams that grow with purpose"). The candidate sign-in has a blurred green blob. | Major | `hr-login-1440`, `candidate-login-1440` | Replace with a product-true visual: a live-looking **evidence-ladder specimen** drawn in tokens (§7 of DESIGN_SYSTEM), on a flat canvas. No gradients, blobs or rings. Cut the filler copy. |
| X-6 | Uppercase letter-spaced eyebrows on almost every page ("CANDIDATE PORTAL", "HIRING OPERATIONS", "JOB #101 · REQUIREMENTS V3", "SKILL · FROM THE RESUME"). Some are 11 px teal at under 4.5:1. | Minor (Major where it fails contrast) | `hr-jobs-1440`, `hr-applicant-verification-1440` | At most one overline per screen, in `--fg-muted`, 11/16, +0.06em. Replace "Job #101 · Requirements v3" with a breadcrumb plus a version chip. |
| X-7 | HR applies letter-spacing to body text: requirement names in the evidence table ("J a v a 1 7…") and verifier names are tracked, which hurts reading. | Minor | `hr-applicant-blind-1440` (Requirement column), `hr-admin-verification-1440` (By verifier) | Tracking only on the overline style. Requirement names use body-strong. Machine ids use mono. |
| X-8 | Pill badges everywhere (status, type, must-have, tier, flags), all `border-radius: 20px`, five to seven per row. | Minor | `hr-job-applicants-1440`, `candidate-job-detail-1440` | Badges use 4 px radius. Only **status** gets a filled badge. Type ("SKILL") becomes plain small-caps text. Must-have becomes a leading marker (§2.4). |

### 2.2 Information architecture and navigation

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-9 | Candidate nav is 10 flat items. Three of them (Resumes, Evidence, Strengthen your application) are the same mental object, "proof of what I can do", split by mechanism. "Strengthen your application" wraps onto two lines. | Major | `candidate-jobs-1440` sidebar | Group into **Jobs · Applications · Tasks** (work samples + interviews) · **My evidence** (tabs: Resume, Claims, Verification, References) · Inbox, then Profile and Privacy under the account menu. That is 5 primary items. |
| X-10 | HR on phones: the sidebar becomes a horizontally scrolling chip strip. "Notifications" ends 269 px off screen, and job tabs "Fairness" and "Details" are cut off with no affordance. | Major | harness overflow report `hr-job-applicants@390` | Phone: a top bar with a **Menu** button that opens a left drawer (same component as the candidate portal). Tabs become a horizontally scrollable tablist with edge fade masks and scroll-into-view of the active tab. |
| X-11 | Notifications are not links in either portal; the inbox is a dead end. | Major | handbook 4.5.2; `*-notifications-1440` | Each item links to its subject (application, work sample, interview, verdict). The backend `template` + ids are enough for a client-side route map; if ids are missing, ask the backend to add `link`. |
| X-12 | "Sign out" is a top-level control in the HR top bar and in the candidate sidebar footer; the user's name is truncated to "Meera …" in the candidate footer. | Minor | `hr-jobs-1440`, `candidate-jobs-1440` | An account menu (avatar button) with name, email, role, Theme (System/Light/Dark), Profile/Security, Sign out. |
| X-13 | No way back to "where I was" across detail pages. Applicant detail has no previous/next applicant, and the candidate's application detail has no link to the task it is waiting on. | Major (HR) | `hr-applicant-*` | Previous/next buttons in the applicant header (keyboard `[` `]`). The position comes from the list query in the URL. |

### 2.3 States: first-run, empty, loading, error, success

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-14 | Loading is a spinner plus a sentence ("Loading jobs…") in an empty panel, so the layout jumps when data arrives. | Major | `LoadingBlock`, `LoadingPanel` | Skeletons that match the final layout: table rows, card lines, the header block. Show them only after 150 ms so fast responses never flash. |
| X-15 | Errors are a generic box with **Try again**. The candidate jobs error re-tries silently twice before showing anything (≈ 6 s with no feedback). | Minor | `candidate-error-jobs-1440` | Inline error in place of the content: what failed, whether the data shown is stale, Try again, and a status-page link for staff. |
| X-16 | First-run candidate: after sign-up the user lands on Find jobs with a 70 px (desktop) / 160 px (phone) verify-email banner. Nothing says "upload your resume first", yet applying requires one, and the user only learns that inside the apply dialog. | Major | `candidate-jobs-390` | A first-run checklist card on Jobs (Verify email · Upload resume · Review what we read), dismissible, collapsing as steps complete. Shrink the banner to one line. "Not now" should hide it for the session, not just this page load. |
| X-17 | Success is mostly a toast. Durable outcomes (application sent, verdict shared, reference requested) are not reflected in place. | Minor | handbook 4.1.8 | Optimistic in-place change plus a toast. For example, the shared verdict row flips its status tag with the verdict-reveal motion (DESIGN_SYSTEM §8). |
| X-18 | Empty states are an icon plus a sentence; some have no next action ("No interviews scheduled."). | Minor | `*-interviews` | The empty-state pattern: one line saying why it is empty, one primary action, no illustration (DESIGN_SYSTEM §9.24). |

### 2.4 Trust cues for evidence and verification

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-3 | Glyph symbols carry meaning (✓ ◐ ○ ✕ –, 🔒). Some render as tofu. Contradicted is `✕` for candidates and `!` for HR. | Major | `hr-applicant-verification-1440` ("Ownership proven" with an empty box) | One `VerdictChip` with SVG icons (lucide plus one custom half-circle). Shape cues: solid, half, hollow, outline-triangle, dashed. Text label always visible. |
| X-19 | The tier ladder is drawn three different ways: a 5-bar mini meter (HR), a text pill (candidate), and "Claimed → Resume → Verified" (login art). The candidate and HR **labels differ** ("On your resume" vs "Resume"). Different audience copy is fine, but the visual is not shared. | Major | `hr-applicant-blind-1440` tier column, `candidate-evidence-1440` | One `EvidenceLadder` component: 5 rungs, filled up to the tier, Self-claimed shown as a dashed hollow rung, plus a label from an audience map (`labels.candidate` / `labels.staff`). |
| X-20 | "Must-have" uses the **danger** palette on the candidate job page (`#A4402F` on pink), so requirements read as warnings. | Major | `candidate-job-detail-1440` | Must-have = a solid 6 px square marker plus "Must-have" in `--fg-default`. Nice-to-have = a hollow square. No red. |
| X-21 | Provenance is prose ("Source: claim checked by a recruiter") at 12 px under the quote. Who/what/when is the single strongest trust cue and it is the weakest text on the screen. | Major | `hr-applicant-blind-1440` | A **provenance line** component: icon + source kind + actor/verifier + relative time, in `body-sm` and `--fg-muted`, always in the same position under the quote. |
| X-22 | Fingerprints and hashes show as truncated prose ("sha256:9f2c4e1ab07d…"). | Minor | `hr-applicant-verification-1440` | `CodeField`: mono, first 12 hex chars plus a copy button; the full value goes in a tooltip and the copy. |

### 2.5 Forms, validation and microcopy

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-23 | HR joins server field errors into one message ("Field: message · …"), while the candidate portal maps them per field. | Major | handbook 4.1.7 | Port `fieldErrorMap` to HR. Errors go under fields, with focus moved to the first error and a summary only if there are three or more. |
| X-24 | Money is shown raw in HR inputs ("2800000"). Deadlines show odd precision ("Deadline Nov 28, 2026, 4:46 AM"). | Minor | `hr-job-form-1440`, `hr-job-applicants-1440` | `Intl.NumberFormat('en-IN')` on blur with a ₹ prefix inside the field. Deadlines as dates; the time appears only if it is not 23:59. |
| X-25 | Password rules differ (HR is ASCII-only). | Minor | handbook 4.7 #10 | Share `lib/password.ts` through `src/design/` utilities, or at least copy it. |
| X-26 | Copy issues: "1 results"; "Incl. claims"; "Review again: confirm"; "Matching is slow — check again" as a button label; "Asia/Calcutta" shown raw; work-sample rules say "add a link if the task asks for a file". | Minor | various | See the microcopy table in §5. |

### 2.6 Accessibility (WCAG 2.1 AA)

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| H-2 | Contrast failures in HR on all 17 screens. | **Blocking** | axe report | Token ramp in DESIGN_SYSTEM §3; every text token is listed with its ratio on every surface it may sit on. |
| H-4 | `aria-label="Not ranked"` on a non-interactive `<span>` (prohibited attribute). | Minor | axe `aria-prohibited-attr` ×4, `.applicant-rank` | Visible text "—" plus `<span class="sr-only">Not ranked</span>`. |
| X-27 | `outline: none` appears 5× in the candidate CSS and 5× in HR. `:focus-visible` is used only 8× (candidate) and 3× (HR), so the focus treatment is inconsistent. | Major | `grep outline` | One global `:focus-visible` ring token (2 px `--focus` + 2 px offset). Components never remove outlines. |
| X-28 | Touch targets: HR icon buttons (requirement move/remove arrows) are about 20 px; chip "×" is about 14 px. | Major (phone) | `hr-job-form-1440` | Minimum 32 px hit area on desktop and 44 px on coarse pointers (`@media (pointer: coarse)`). |
| X-29 | Type at 8–11 px in HR (sidebar "HR WORKSPACE" 8 px, badges 10–11 px). | Major | CSS counts | Minimum 12 px. Badges 12/16 medium. |
| X-30 | Reduced motion is respected (good), but only because there is almost no motion. The redesign adds motion, so every animation needs a reduced alternative. | — | `base.css` | DESIGN_SYSTEM §8.6. |
| X-31 | Colour-only signals: the coverage bars' claimed bar is distinguished from verified only by colour and hatching. Amber "1 ranking gate not yet verified" carries meaning by colour. | Minor | `hr-job-applicants-1440` | Labels already exist; add the icon (lock-open) to gate warnings. The claimed segment keeps the hatch pattern **and** a value label. |

### 2.7 Responsive behaviour

| ID | Finding | Sev. | Evidence | Fix |
|---|---|---|---|---|
| X-32 | Candidate phone Jobs: four stacked filters fill the first screen, and results start around y = 740 px. | Major | `candidate-jobs-390` | Phone: a search field plus a **Filters** button (count badge) that opens a bottom sheet. |
| X-33 | HR job header on phone: five actions wrap into three rows. | Minor | `hr-job-applicants-390` | Primary action plus a "More" menu. |
| X-34 | HR jobs table at 390: the table is 62 px wider than the viewport inside a scroller with no cue. | Minor | report `jobs@390` | At < 600 px, tables become stacked rows (title, status, two key facts). |

---

## 3. Candidate portal, screen by screen

**First impression (2 s).** Calm, readable and honest copy. The eye goes first to the amber verify-email banner, not the task. Cards are well spaced but generic, with no point of view. **What works:** plain-language copy throughout ("Not progressed", "start by", "a person reviews every application"); the apply dialog's review step; neutral wording for absence; focus management; zero axe violations on the screens that render.

| Screen | Works | Problems (severity) | Fix |
|---|---|---|---|
| Sign-in `/login` | Clear and short. The signed-out reasons are excellent. | Generic card on a blurred blob (Minor). The eyebrow "RECRUITPRO · CANDIDATE PORTAL" duplicates the logo (Minor). | Split layout: form left (max 400 px), a quiet product statement right ("Your evidence, your call.") with a static ladder specimen. On a phone, form only. |
| Register | Live password checklist. | The checklist uses "✓ / ○" glyphs (Minor). There is no "what happens next" (Minor). | Checklist with SVG check icons. Under the button: "Next: verify your email, then upload a resume." |
| Find jobs | Debounced URL filters; results stay while updating. | Phone filters take the first screen (Major, X-32). No "Applied" marker (Major). The ↗ button repeats the title link and adds a second tab stop per card (Minor). The description is cut with an orphan "…" line (Minor). | Card = one link (whole card clickable via the title's `::after`). Footer: salary · "3 must-haves" · deadline as "Closes in 12 days". An **Applied** badge when the candidate has an application (join with the applications query). |
| Job detail | Must-have/nice-to-have split; "Also accepted"; the reassurance sentence. | Must-have in danger red (Major, X-20). The facts card and description card are uneven boxes (Minor). There is no "How you match" preview (Major opportunity). | Two columns: description (720 px reading width) and a sticky right rail with key facts plus **Apply**. Requirements are a list with must-have markers. If the candidate has a resume: "From your resume: 3 of 5 requirements found" with per-requirement ladder chips. This is the bridge to "Strengthen". |
| Apply dialog | Resume radio, review step, focus to step heading, "cannot change" warning. | Steps are not visible as steps (Minor). A 400 px textarea pushes the buttons down (Minor). | A three-dot stepper in the dialog header. The textarea auto-grows from 5 rows. Success step shows "What happens next" (blind review explained in one line). |
| Applications | Plain status labels. | A list of cards with no next step per row (Major). | Table on desktop / rows on phone: role, organisation, status, **next step** ("Start work sample by Wed"), updated. |
| Application detail | Timeline copy; verification section; withdraw confirmation. | **C-1:** Withdraw is the only header button (Major). Next steps at the bottom (Major). "Asia/Calcutta" raw and the meeting link shown as plain text (Minor). "+ Add evidence" doesn't mention Strengthen (Minor). | Header: status + **Next step card** (the pending work sample or interview with its CTA). Withdraw moves to a "⋯" menu. Interviews show "Thu 19 Nov, 10:15 – 11:00 (IST)" and **Join** buttons. "Not yet verified" rows get "Add evidence" and "Verify with a source →". |
| Resumes | — | **C-0: crashes (Blocking).** | Fix the TDZ first. Redesign: a dropzone at the top; the analysis shown as "What we read" grouped facts with a ladder chip each; "Suggest a correction" inline. |
| Evidence (claims) | The "How employers weigh evidence" explainer; neutral copy. | Red-outlined **Delete** on every card (Major: destructive prominence). The explainer is collapsed and plain (Minor). The amber "Your claim" pill makes a normal state look like a warning (Minor). | Claims list as rows with a ladder chip; Delete moves into a row "⋯" menu (the confirm dialog stays, because deleting re-checks every application). The explainer becomes the **ladder legend** component, always visible as a compact 5-step strip. |
| Strengthen your application | Strong concepts: consent per kind, ownership, candidate controls sharing, neutral "Not found". | **C-0: crashes (Blocking).** By code: four long sections on one page (Sources, Connect, Results, References) with no progress, and the most valuable action ("Share" a verified result) sits deep in a dialog (Major). | A **guided page**: progress header ("2 of 4 claims have outside evidence · 1 result waiting for you"), then (1) **Results waiting for your review** at the top (the action that matters), (2) Sources as compact rows with ownership state, (3) "Add a source" as a drawer with kind tiles, (4) References. Verdict details open in a **drawer**, not a modal, so the ledger stays visible. |
| Verdict detail | Excellent content: why, evidence with fingerprint, notes framed as "not accusations", probe question. | Modal hides the ledger (Major). The fingerprint is prose (Minor). | Right drawer (480 px). Sticky footer actions: **Share with employers** (primary) · Hide · Re-check. The note field expands on demand. |
| Work samples / start | Clear rules before Start; non-pausable timer stated. | Outdated rule copy about files (Minor). The Start button doesn't restate the time cost (Minor). | Rules as a checklist. Button: "Start — 90 minute timer begins". The timer becomes a sticky top bar during the task; at 5 minutes it turns warning-coloured and announces. |
| Interviews | Time zone stated. | Raw IANA zone; no "Add to calendar" (Minor). | "IST (UTC+5:30)" and an .ics download (client-side). |
| Notifications | Unread marking. | Not links (Major, X-11). | Linked rows; "Mark all as read" in the header. |
| Profile | Clear privacy statement. | Long single column with 13 fields at equal weight (Minor). | Sections: Work, Location, Links, Preferences. Two columns from 900 px. |
| Privacy | Exemplary transparency. | Account deletion is the same visual weight as the survey (Minor). | Danger zone at the bottom with a 1 px `--danger-border` frame (the only bordered box on the page). |
| Referee page | Warm, fair copy ("'Cannot confirm' is a perfectly good answer"). | **C-2:** 2,069 px on a phone for three claims (Major). Four big radio cards per claim (Major). There is no progress ("2 of 3 answered") (Minor). The shell says "RECRUITPRO · REFERENCE REQUEST" (brand must be swappable). | Per claim: a 4-option segmented radio group (Confirm / Partly / Can't confirm / Disagree) with descriptions in a single helper line below. "Add a comment" is a disclosure. A sticky footer shows progress + **Send answers**. Target: under 1,000 px on 390 px for three claims. |

---

## 4. HR portal, screen by screen

**First impression (2 s).** The dark-teal sidebar and the gradient sign-in announce "template". Inside, the eye goes to the five counter cards, which are the least useful thing on the page. **What works:** blind-first is visible everywhere; the grouping into Ranked / Must-haves not yet verified / Unscored is the right model; the copy is careful and never accusatory; URL-kept filters and tabs; ARIA tabs; capability-aware actions.

| Screen | Works | Problems (severity) | Fix |
|---|---|---|---|
| Sign-in | MFA, recovery, forced enrolment, SSO and every error code are handled. | Wordmark wraps to "Recruit / Pro" (Major: brand). "Continue with Okta" text is top-aligned inside its button (Minor). Decorative left panel (X-5). | Same auth layout as candidate (shared component). SSO buttons are full-width secondary buttons with an inline provider icon slot. |
| Jobs list | Debounced search; capability-aware **Create job**. | Five counter cards (same briefcase icon in five tints) cost five requests and duplicate the status filter (Major). The "Open →" column is redundant with the title link (Minor). | Counters become a **status tab bar with counts** ("All 6 · Published 3 · Drafts 1 · On hold 1 · Closed 1") that *is* the filter. Ask the backend for one `GET /jobs/stats`. Table rows: title (+ type · level), department, location, status, **applicants with new count** ("38 · 4 new"), created; whole row clickable. |
| Job detail header | Version, tags, status actions with confirmation. | Five equal-weight buttons, with **Close job** in red beside **Edit job** (Major). Long ID line as an eyebrow (Minor). | Breadcrumb "Jobs / Senior Backend Engineer". Title + status badge + "Requirements v3" chip. Right: **Edit** · primary status action (Publish/Put on hold/Resume) · "⋯" (Close, Export audit CSV, Delete). |
| **Applicants (ranking)** | The ranking model, honest intro copy, polling with a time box, Re-run matching. | **H-3 (Major):** card-rows, repeated bar labels, no per-requirement view, ~10 applicants per screen. "Matching in progress" pill sits next to Re-run (Minor). The rank number is styled like a heading (Minor). | **Ranking table** (DESIGN_SYSTEM §9.6): sticky header; columns = # · Applicant (blind id or name + Blind tag) · Status · **Evidence strip** (one 12 px ladder cell per requirement, must-haves first, gate requirements outlined; hover/focus shows "Java 17 — Corroborated") · Verified % (number + 48 px bar) · Incl. claims % · Applied. Group header rows with counts and the one-line explanation. Compact density shows about 22 rows per screen. Row → applicant page; ↑/↓ moves focus, Enter opens. |
| **Applicant detail** | Blind-first identity card; decision dialogs with comments; audit statements; capability filtering. | **H-1 (Blocking):** overlap. Duplicate **Reveal identity** (header + side) (Minor). Decision buttons are above the evidence that justifies them (Major). One tab holds four long sections (~2,700 px) (Major). No prev/next (Major, X-13). Requirement text is tracked (X-7). | **Three-zone layout:** header (identity/blind state, status, rank, prev/next, decision bar on the right) · main column with **section tabs** *Evidence · Claims · Work samples · Interviews · Verification* (counts in tabs) · a 320 px **right rail** (identity, review note, history) that collapses into the tabs below 1200 px. The evidence table uses fixed columns: Requirement (40%) · Ladder (120 px) · Evidence & provenance (rest). The status word moves into the ladder cell. |
| Verification tab | Shared-only results, ownership, de-identified reasons, "Worth asking about", org review gating the tier. | Three-level nesting (X-4). The claim header "Skill · From the resume" is light teal tracked text (fails AA). The same probe question is repeated per verdict (Minor, fixture). Button copy "Review again: confirm/overrule" (Minor). | One row per claim: claim, current tier ladder, verdict chips. Expanding a row shows Why / Evidence / Notes in a two-column definition layout. **Confirm** / **Overrule** as a two-button segmented action with the note required for Overrule; after confirming, the ladder animates the rung lift (signature motion). |
| Decision dialogs | Comments, audit sentences, Cancel-first for danger. | Shortlist reveals identity, but the dialog doesn't preview *what* becomes visible (Minor). | A short checklist in the dialog: "Name · Contact · Resume file · Cover letter become visible to 4 reviewers." |
| Job form | Live-job versioning warning; compiler; alternatives chips. | Requirements editor: 7 controls per row, 6 rows ≈ 900 px (Major). "Ranking gate" is disabled with no reason (Minor). The save bar isn't sticky (Minor). Raw salary numbers (X-24). | Requirement rows as a **compact editable table**: drag handle · type select (small) · requirement text · Must-have toggle · Gate toggle (tooltip "Only for must-haves") · "⋯" for years/proficiency/alternatives (inline expand). Sticky footer with a change summary ("2 requirements changed — will re-match 38 applicants"). |
| Work samples / Interview kit tabs | Rubric anchors; activation freezes edits. | Kit questions: the "Interview questions" heading collides with the first card (spacing bug) (Minor). Anchor lists are tall (Minor). | Anchors in a 4-column grid (1–4) under each question. |
| My interviews / detail | Waiting-for-scorecard group highlighted; focus requirements. | Interviewer flow: questions and the scorecard are separate regions, so the interviewer scrolls between question and rating (Major for INTERVIEWER). | Scorecard inline: each question card has its rating scale (4 segmented anchors) and notes directly below. A sticky progress bar ("4 of 6 rated") and **Submit scorecard**. |
| Notifications | Unread badge. | Not links (X-11). | As candidate. |
| Account / Security | States are well handled; recovery-code warning. | No QR code (known). Secret shown in 4-char groups is good. | Add a QR code (client-side, e.g. the `qrcode` package, ~12 kB gz, lazy-loaded on this route). |
| Admin overview | Service health with hints; queue explanation. | Proportional bars without values on hover (Minor). Health uses colour plus word (OK). | A "Service status" list with status dots **and** words; counts as a stat row (tabular numerals, count-up once). |
| Staff accounts / Invitations / Org / Job oversight / Audit | Guard rails ("always one admin"). | Tables in cards, inconsistent paddings (Minor). The audit export hint contradicts its defaults (Minor). | Standard `DataTable` with filters in a toolbar. Fix the hint ("Defaults to the last 30 days"). |
| Admin verification | Readiness metrics and a clear explanation of false corroboration. | Connector toggles as six full-width empty boxes (Minor). **Save changes** looks disabled at rest (Major: affordance). Readiness uses glyphs ✓/○ (X-3). "1 results" (Minor). Empty "By verdict" table shows only headers (Minor). | Connectors as a 2-column switch list with the description inline. Readiness as three **progress meters** (118/150, 1.7% ≤ 2%, κ 0.74 ≥ 0.70) with met/unmet icons. Shadow queue item uses the shared verdict row plus the `VerdictPicker` as a 5-option segmented control. |

---

## 5. Microcopy: current → proposed

| Where | Current | Proposed | Why |
|---|---|---|---|
| HR jobs | "HIRING OPERATIONS / Jobs / Create roles, define what evidence matters, and review applicants blind-first." | "Jobs" (no eyebrow, no subtitle; the counts carry the meaning) | Remove filler from a page people visit 20× a day. |
| HR applicants intro | "Coverage is the share of weighted requirements with evidence — not a probability of success. Humans make every decision." | Keep, but move it into an info tooltip on the "Verified %" column header and show it once as a dismissible hint. | Repeated reassurance becomes noise for daily users. |
| HR applicants | "Incl. claims" | "With claims" | Avoid abbreviations. |
| HR applicants | "Matching is slow — check again" (button) | Status text "Matching is taking longer than usual" + button **Check again** | A button label should be the action. |
| HR verification | "Review again: confirm" / "Review again: overrule" | "Change review" (menu: Confirm / Overrule) | Shorter; one control. |
| HR admin | "1 results" | "1 result" | `Intl.PluralRules` helper. |
| HR verification | "Evidence — 2 evidence items. The sources are not available." | "2 evidence items · sources appear after identity is revealed" | Says why. |
| Candidate app detail | "Times are shown in Asia/Calcutta." | "Times in India Standard Time (UTC+5:30)" | `Intl.DateTimeFormat(…, {timeZoneName:'long'})`. |
| Candidate work sample | "…add a link if the task asks for a file." | "…attach up to 3 files (PDF, ZIP, DOCX, PPTX, PNG, JPG · 20 MB each) or add a link." | It is outdated. |
| Candidate jobs | "Published roles from every organisation on RecruitPro." | "Open roles you can apply to now." | Name-agnostic; says what is listed. |
| Candidate nav | "Strengthen your application" | Nav: "My evidence"; page H1 keeps "Strengthen your application" | Fits in one line; the H1 keeps the motivating phrase. |
| Candidate evidence | Badge "Your claim" in amber | Ladder chip "Your claim" (hollow rung, neutral) | Normal state, not a warning. |
| Both | "Not found in connected sources" | Keep (it is the neutral wording the product depends on) | — |
| Referee | Radio descriptions on every option | One helper line under the group: "Confirm = you know it is true · Partly = true with limits · Can't confirm = you don't know enough · Disagree = you believe it is inaccurate" | Cuts about 60% of the height. |
| Consent texts | "I agree that RecruitPro may read…" | "I agree that {brand.name} may read…" — **but** consent wording is versioned server-side (03 §7.5). Change only together with a consent-version bump. | The brand swap must not silently change agreed text. |
| Ownership codes | `recruitpro-verification=<code>`, `/.well-known/recruitpro-verification.txt` | **Do not brand-swap in the UI.** These are protocol constants checked by the agent service. Rename only with a backend change that accepts both prefixes. | Breaking proofs would be a silent failure. |

---

## 6. Every click: primary flows, current vs redesigned

Each table lists what the user does and sees today, the friction, and the redesigned step. "Clicks" counts pointer activations on the happy path.

### Flow A. Candidate: first visit to submitted application (today: 14 clicks, 5 pages; redesigned: 10 clicks, 4 pages)

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | `/register`: 5 fields, checklist, **Create account** | — | Same; button "Create account". Below it: "Next: verify your email, then add a resume." |
| 2 | Lands on `/jobs` with the verify-email banner | No guidance; banner dominates (X-16) | Lands on `/jobs` with a **Getting started** card (3 steps, the first two tickable). Banner becomes a single line inside the card. |
| 3 | Opens the email link → `/verify-email` → **Continue to jobs** | — | Same; then auto-return to the page they came from. |
| 4 | Sidebar → **Resumes** → choose file → **Upload file** (2 clicks after picker) | **Page crashes today (C-0).** Upload is two actions. | Drop or choose a file **in the getting-started card or on My evidence › Resume**; upload starts immediately (one action), with a progress bar and a skeleton of "What we read". |
| 5 | Waits; the analysis polls; facts appear | Status text only | Facts stream in with list stagger; each shows a ladder chip "On your resume". |
| 6 | **Find jobs** → types keyword | — | Same; on a phone, search plus a Filters sheet. |
| 7 | Opens a job card | Two tab stops per card | The whole card is one link. |
| 8 | Reads; **Apply now** | No sense of fit | Right rail: "From your resume: 3 of 5 found" plus **Apply**. |
| 9 | Dialog step 1: resume (pre-selected), cover letter → **Review application** | — | Same, with a step indicator (1 Resume · 2 Review · 3 Sent). |
| 10 | Step 2 → **Submit application** | — | Same. |
| 11 | Step 3 → **View application** | — | Step 3 also offers "Strengthen this application →" if any must-have is not evidenced. |

### Flow B. Candidate: Strengthen your application (today: crash; designed path: 9 clicks)

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Nav → "Strengthen your application" | **Blank page (C-0)**; four long sections | Nav **My evidence → Verification** tab. A progress header shows "Results waiting: 0 · Sources: 0". |
| 2 | Section B: radio list of kinds → consent tick → **Sign in with GitHub** | The consent text and kind list sit far below the explanation | **Add a source** opens a drawer with 5 kind tiles (icon + one line each). Choosing GitHub shows the consent checkbox right above the **Continue with GitHub** button. |
| 3 | GitHub round trip → `?connected=github` notice | — | Return to the drawer's success state: "GitHub connected · ownership proven"; the source row appears with an entrance animation. |
| 4 | **Run verification** on the source card | A separate action that is easy to miss | Offer **Run check now** directly in the success state (primary). |
| 5 | Card says "Checking now"; polling | No sense of progress | The source row shows an indeterminate progress line; the ledger rows that will be checked show "Checking…" chips. When the run finishes, chips **morph** into verdicts (verdict reveal motion). |
| 6 | Scroll to **Your results**; click a verdict row → modal | Results are far below; the modal hides context | **Results waiting for your review** is the first section; click → right drawer. |
| 7 | Read Why / Evidence / Notes; optional note → **Share with employers** | — | Same content, better hierarchy; Share is the sticky primary in the drawer footer; **Next result →** advances without closing. |
| 8 | (Alternative) Website: ownership dialog with DNS/file/page methods, Copy, **Check now** | Good, but the dialog stacks on the page | Same steps in the drawer, as a 3-method tabbed panel with `CodeField`s. "Check now" shows "Checking…" then a clear outcome. |
| 9 | References: fill the form, pick claims, consent → **Send request** | Long form at the bottom | **Ask for a reference** drawer, step 1 "Who" and step 2 "Which claims" (checkbox list with ladder chips). The open-requests counter reads "2 of 5 open". |

### Flow C. Referee (public link): answer a reference (today: 3 claims ≈ 2,069 px on a phone; target under 1,000 px)

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Opens link → "Can you confirm Meera's work?" | Tall intro; brand eyebrow | Brand mark (from config), the heading, a two-line intro, and the validity date as a small tag. |
| 2 | Per claim: four 60 px radio cards + comment box | Long; repetitive descriptions | Per claim: a 4-segment radio group plus an "Add a comment" disclosure; the helper line appears once at the top. |
| 3 | Overall comment; identity checkbox; **Send answers** | Button at the very bottom | Sticky footer: "2 of 3 answered" + **Send answers** (disabled until all are answered; a tooltip says what is missing). The identity checkbox sits above the footer. |
| 4 | "Thank you…" | — | Same, with the "they see results, not your words" reassurance kept. |
| alt | "I would rather not answer" → confirm → declined | — | Same, as a tertiary link in the footer. |

### Flow D. Recruiter: screen applicants with evidence (today: ~3 clicks per applicant plus long scrolls; redesigned: 1 click per applicant, no hunting)

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Jobs → job row **Open →** | — | Click the row. |
| 2 | Applicants tab: card rows grouped | ~10 applicants per screen; no per-requirement view (H-3) | Ranking table, about 22 rows per screen in compact density; the evidence strip shows which requirement is missing. |
| 3 | Picks a "Must-haves not yet verified" applicant → **Review →** | Must scan text to find the gate | The strip outlines the open gate in amber with a lock-open icon; hovering shows "Gate: Java 17 — only claimed". |
| 4 | Applicant page; scroll to Requirement evidence; find the highlighted row | Decision bar first; overlap bug (H-1) | Opens on the **Evidence** tab with the open gate row pinned first and auto-expanded. |
| 5 | Scroll to **Candidate claims** → **Verify** → note dialog → confirm | A separate section, far away | The gate row shows the related claim inline with **Verify claim** / **Not verified** buttons; a note popover replaces the dialog (Enter to submit). |
| 6 | Matching re-runs; return to the list to see the effect | No feedback where you are | In place: the ladder rung animates up to Corroborated; the header rank chip updates ("Moved to Ranked · #4") with a count-up. |
| 7 | Verification tab → **Confirm** → dialog | — | Inline segmented Confirm/Overrule; the note is required only for Overrule. |
| 8 | Review note → **Save note** | — | Right rail; autosave after 1 s idle with a "Saved" indicator (keeps the `PUT`). |
| 9 | Back to list → next applicant | 2 clicks plus finding your place | **Next applicant ]** in the header keeps list order and filters. |

### Flow E. HR manager: decide (shortlist, reveal, reject, select)

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Applicant header **Shortlist** → dialog with comment → **Shortlist** | Doesn't preview what the reveal exposes | Dialog lists exactly what becomes visible and to whom; comment optional; primary "Shortlist and reveal". |
| 2 | Page refreshes; name appears | Abrupt | The identity card flips from "Blind review" to identity with a 240 ms crossfade; the history gains an entry (slide-in). |
| 3 | **Move to interview** / **Schedule interview** in the Interviews section | Two places that do related things | One **Schedule interview** primary in the decision bar for SHORTLISTED (it moves the status automatically); "Move to interview" goes to the ⋯ menu. |
| 4 | **Reject** (red, always visible) | Same visual weight as Shortlist | Reject is a secondary danger-text button, separated by a divider; the comment is suggested ("The candidate is notified"). |
| 5 | **Select** | — | Confirmation shows the evidence summary (coverage, tier counts) as a last check. |

### Flow F. Interviewer: run an interview and submit a scorecard

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Lands on **My interviews**; "Waiting for your scorecard" table | Good | Same; the waiting group gets a count in the nav ("My interviews 1"). |
| 2 | Opens the interview; "Focus on these requirements" | Good content, weak hierarchy | Focus list as compact rows with ladder chips. |
| 3 | Scrolls between questions and the scorecard form | Context switching | Question cards with an inline 1–4 segmented rating (anchors shown on hover/focus and always on phone) and notes; sticky "4 of 6 rated". |
| 4 | Recommendation + summary → **Submit** → confirm | — | Same confirm copy; on success, a read-only scorecard with a "Submitted" stamp line. |

### Flow G. Admin: shadow to visible

| # | Today | Friction | Redesigned |
|---|---|---|---|
| 1 | Administration › Verification; mode radio cards; six toggle boxes; **Save changes** (looks disabled) | Affordance (Save) | Settings in a compact form; Save is enabled only when dirty and looks it. |
| 2 | Agreement metrics: four stat boxes + glyph checklist | Two representations of the same three targets | Three readiness meters with target ticks; the pill "Not ready (1 of 3 targets)". |
| 3 | Shadow queue: read → pick verdict → **Save label** | Long items, one per page of 10 | Queue as a list-detail split: the left list (claim, agent verdict, labelled state), the right detail with the picker; keyboard 1–5 picks a verdict, Enter saves and advances. |
| 4 | Visible → confirm "anyway" | Good guard | Same, with the meters repeated in the dialog. |

---

## 7. Consistency between the portals: what must be identical

1. Tokens, fonts, radii, spacing, focus ring, motion: `src/design/` is a byte-identical copy in both portals (checked by `tools/check_design_sync.py`; see DESIGN_SYSTEM §12).
2. `VerdictChip`, `EvidenceLadder`, `CoverageMeter`, `ProvenanceLine`, `CodeField`: one implementation, with audience labels injected.
3. Dialogs: one component on native `<dialog>` (the candidate approach; inert background for free) replacing the HR custom portal dialog. Toasts: one region, portalled outside `#root` (the HR approach, so they are still announced under modals).
4. Error mapping (`fieldErrorMap`), password rules, date/number/plural formatting: shared utilities in `src/design/lib/`.
5. Auth layout, account menu, theme switch, skip link, page-heading focus: one pattern each.

## 8. What to keep (do not lose in the redesign)

- The copy voice: plain, specific, never accusatory; "Not found … is neutral"; "Not progressed"; "a person reviews every application".
- Blind-first visibility on every surface where identity could leak.
- Candidate control over sharing; consent before any read.
- Time-boxed polling with "Check again".
- URL-kept filters, tabs and pages.
- Native `<dialog>` focus behaviour and Cancel-first destructive confirmations.
- Audit statements at the moment of action ("recorded in the audit log").
