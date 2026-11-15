# Redesign implementation plan

**For:** two frontend engineers. **Engineer A** owns `frontend/` (candidate + referee); **Engineer B** owns `hr-frontend/`.
**Inputs:** [UX_REVIEW.md](UX_REVIEW.md) (findings by id, e.g. H-1) and [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) (§ references below).
**Gate for every step:** `tools/ui-snapshots` (README there). Branch `redesign`. App behaviour and API calls do not change unless a step says so.

---

## 0. Order of work at a glance

| Day | Engineer A (candidate) | Engineer B (HR) |
|---|---|---|
| 0.5 | **Fix C-0** (TDZ crash) · Phase 1 foundation (shared) | Phase 1 foundation (shared) |
| 1–2 | Shared components, set A (below) | Shared components, set B (below) |
| 2 | Auth pages (login, register, forgot/reset, verify-email) | Auth pages (login + MFA + enrol, signup, invite, SSO complete) |
| 3 | Jobs list → Job detail + Apply | Jobs list ("dashboard") → Job header |
| 4 | Strengthen your application + verdict drawer | Applicants ranking table |
| 5 | Referee page | Applicant detail (Evidence tab + decisions) |
| 6 | Applications + application detail | Applicant Verification tab + review |
| 7 | My evidence: Resume + Claims | My interviews + interview detail (scorecard) |
| 8 | Work samples, interviews, inbox | Job form + requirement editor |
| 9 | Profile, privacy, 404, polish | Work samples / kit / fairness tabs, admin verification |
| 10 | Dark-theme pass, motion pass, phone pass | Remaining admin + account, dark/motion/phone pass |

Demo-critical screens (bold in the tables below) must be finished, in both themes, before anything else is started.

---

## Phase 1. Foundation (both engineers, together, first half-day)

1. **C-0 fix (Engineer A, 15 minutes, before anything else).** In `pages/resumes/useResumeAnalysis.ts` and `pages/verification/SourcesSection.tsx` the `refetchInterval` callback reads `polling` before `const polling = usePollingWindow(...)` runs, so the production build throws during render. Fix: keep the window in a ref (`const pollingRef = useRef<PollingWindow>()`), set `pollingRef.current = polling` after the hook, and read `pollingRef.current?.nextInterval(...) ?? false` inside `refetchInterval`. **Accept:** `node snap.mjs --portal candidate --only resumes,verification,verdict-detail` exits 0 with no `blank`.
2. Create `src/design/` per DESIGN_SYSTEM §12 in **one** portal, then copy it to the other:
   - `tokens.css` (Appendix A), `reset.css`, `base.css`, `utilities.css`, `motion.css`, `index.css` (layer order §11);
   - `fonts.ts` (Plex Sans 400/500/600 + Mono 400, latin + latin-ext);
   - `icons.ts` (lucide-react 0.460.0 + the custom icons);
   - `theme.ts` + `public/theme-init.js` (§10), with `<script src="/theme-init.js"></script>` as the first child of `<head>` in both `index.html` files;
   - `brand/` with the `neutral` preset and `vite-plugin-brand.ts` (§2), registered in both `vite.config.ts`.
   Load the old `styles/*.css` as `@layer legacy` (declared first) so nothing breaks.
3. Add `tools/check_design_sync.py` (the code is in DESIGN_SYSTEM §12). Run it before every push.
4. **Accept (Phase 1):** both portals build; every existing screen still renders (the harness exits 0 apart from the existing HR contrast violations); `data-theme` toggles via `localStorage['theme-preference']` with no flash on reload (check by throttling the CPU 6× in DevTools); `grep -r "RecruitPro" src --include=*.tsx` shows only `brand/presets/neutral` and the protocol constants listed in DESIGN_SYSTEM §2.

### Shared components: who builds what (then copy; one owner per file)

| Set A (Engineer A) | Set B (Engineer B) |
|---|---|
| Button, IconButton, Field, Input, Textarea, Select, Checkbox, Radio, RadioCard, Switch, SegmentedControl | DataTable (sorting, sticky header, selection, density, group rows), Tabs, Pagination, Breadcrumb |
| Dialog, Drawer (+ bottom sheet), Toast, Alert/Banner | Badge, Tag, CoverageMeter, ProvenanceLine, Meter, Stat, Avatar |
| VerdictChip, EvidenceLadder (chip/cell/legend), CodeField, FileDropzone | Tooltip, Menu/Popover, Section, Panel, PageHeader, EmptyState, Skeleton |
| AppShell (sidebar/rail/drawer, top bar), AccountMenu, ThemeSwitch, Stepper, Timeline | `motion.ts` (countUp, flip, stagger, withViewTransition), `lib/format.ts`, `lib/fieldErrors.ts`, `lib/labels.ts` |

**Accept (components):** each component has a story-like demo route `/__design` (dev only, `import.meta.env.DEV`) rendering every variant and state in both themes. Add a `design` screen to both fixture files and capture it at 1440 and 390, light and dark. axe shows no serious/critical violations on `/__design`.

---

## Definition of done (every screen)

1. Built only from `src/design` components and tokens. Page CSS lives in `@layer pages` with no hex, px font sizes or durations (stylelint passes).
2. **Harness:** `node snap.mjs --portal <p> --dist <dist> --theme both --axe --only <screen…>` exits **0**: no page error, no blank render, no horizontal overflow at 390, no serious/critical axe violation, in light **and** dark.
3. **States:** loading (skeleton, captured with a `delayed` fixture), empty, error and the main success state each have a fixture screen and a screenshot.
4. **Keyboard:** the primary task can be done with the keyboard alone; focus is always visible; Escape closes overlays and returns focus; after navigation focus lands on the H1 (candidate) or `main` (HR), unified to the **H1** in the redesign.
5. **Reduced motion:** with `prefers-reduced-motion: reduce` nothing translates or scales; the signature motions (M4–M7) show their end state immediately.
6. **Copy:** findings in UX_REVIEW §5 applied; brand name only via `brand.name`.
7. Screenshots copied to `docs/design/screenshots/after/<portal>-<screen>-<theme>-<width>.png` and compared side by side with `before/`.

---

## Phase 2. Candidate portal (Engineer A), in priority order

| # | Screen | Findings fixed | Acceptance criteria (in addition to the definition of done) |
|---|---|---|---|
| **A1** | **Auth: login, register, forgot, reset, verify-email** | X-5, X-16 | Shared `AuthLayout` (form column max 400 px; on ≥ 900 px a right column with a static `EvidenceLadder variant="legend"` specimen and one sentence). Brand from `brand.Mark` + `brand.name`. Signed-out reasons shown as an `Alert`. Register shows "Next: verify your email, then add a resume." Password checklist uses SVG icons. Phone: the form only, primary button 40 px. |
| **A2** | **Find jobs** | X-32, Jobs "Applied", X-14 | Phone: search + **Filters** button (bottom sheet, count badge); results start above y = 260 at 390 px. Each card is a single link (one tab stop). An **Applied** tag appears when an application exists for the job (join `['applications']`). Deadline as "Closes in N days" with the absolute date in a tooltip. A skeleton of 4 cards. Filtered-empty shows "No open roles match these filters" + **Clear filters**. |
| **A3** | **Job detail + Apply** | X-20 | Must-have marker per §9.9 (no red). Right rail sticky at ≥ 900 px with facts + **Apply** (and on a phone, a sticky bottom bar). If the candidate has an analysed resume: "From your resume: N of M requirements found", with a ladder chip per requirement (derived client-side from the latest analysis facts compared with requirement text: exact or alternative match only; otherwise omit the block). Apply dialog: stepper (Resume · Review · Sent), auto-growing textarea, success step with "Strengthen this application →" when any must-have is not verified. |
| **A4** | **Strengthen your application** (`/verification`, nav "My evidence › Verification") | C-0, X-3, X-19 | Order: progress header → **Results waiting for your review** → Sources → References. Verdict details in a `Drawer` (480 px) with sticky **Share with employers** / Hide / Re-check and **Next result →**. Add source = a drawer with 5 kind tiles; consent sits directly above the connect button. Ownership methods as tabs with `CodeField`s. While a run is active, the affected ledger rows show `VerdictChip state="checking"`, which morphs (M6) when results arrive. "Not found…" standing note kept verbatim. Fixtures to add: `verification-empty` (no sources), `verification-running` (lastRun RUNNING), `verification-drawer` (verdict open), `verification-website-ownership`. |
| **A5** | **Referee page** (`/reference/:token`) | C-2 | Per claim: `SegmentedControl` with 4 options + "Add a comment" disclosure; one helper line at the top; sticky footer "N of M answered" + **Send answers**; decline as a tertiary link. **Height at 390 px for 3 claims < 1,000 px** (measure from the full-page PNG). Closed (410) and invalid (404) states are styled as empty states with the brand mark. Theme toggle in the corner. |
| A6 | Applications list + detail | C-1, X-11 | List as a table/rows with a **Next step** column. Detail: a header "next step" panel (pending work sample or interview with its CTA); Withdraw moved to the "⋯" menu (confirm dialog kept); interviews show "Thu 19 Nov, 10:15–11:00 IST" with a **Join** link button; timeline component; "Not yet verified" requirements get "Add evidence" and "Verify with a source →". |
| A7 | My evidence › Resume (`/resumes`) | C-0 | `FileDropzone` uploads immediately on choose/drop; analysis status as an `Alert`; facts grouped with ladder chips; "Suggest a correction" inline (row expand M12); flags explained in plain words under a "Notes about this file" disclosure. |
| A8 | My evidence › Claims (`/evidence`) | X-19 | `EvidenceLadder variant="legend"` always visible (compact). Claims list as rows; Delete in a row menu with a confirm dialog (keep the confirm: deletion re-checks applications). Add claim form in a drawer on phone, inline at ≥ 900 px. |
| A9 | Work samples list + response | handbook 4.7 #8 | Rules as a checklist with corrected file copy; Start button "Start (90-minute timer begins)"; a sticky timer `Meter` while STARTED (M16); attachments via `FileDropzone` (max 3). |
| A10 | Interviews, Notifications, Profile, Privacy, 404 | X-11, X-18 | Notifications link to their subject (route map by `template`; fallback to unlinked). Interviews: "Add to calendar" (.ics generated client-side). Privacy danger zone is the only bordered panel. |
| A11 | IA change | X-9 | Sidebar: Jobs · Applications · Tasks (Work samples + Interviews as tabs) · My evidence (Resume · Claims · Verification · References) · Inbox; Profile, Privacy, Theme, Sign out in the account menu. **Old routes keep working** (`/resumes`, `/evidence`, `/verification`, `/work-samples`, `/interviews` stay valid as tab URLs), so email links never break. |

## Phase 3. HR portal (Engineer B), in priority order

| # | Screen | Findings fixed | Acceptance criteria (in addition to the definition of done) |
|---|---|---|---|
| **B1** | **Auth: login (password, MFA, recovery, forced enrolment), signup, accept-invite, SSO complete** | X-5, sign-in wordmark | The same `AuthLayout` as candidate (copied component). Wordmark never wraps (`white-space: nowrap`; the mark alone below 360 px). SSO buttons are full-width secondary with the label vertically centred. MFA code input: 6 segmented boxes backed by one `<input autocomplete="one-time-code" inputmode="numeric">`. Enrolment shows a **QR code** (lazy-loaded `qrcode`, ≤ 12 kB gz, route chunk) plus the grouped secret in a `CodeField`. Fixtures to add: `login-mfa`, `login-enrol`, `login-recovery-codes`. |
| **B2** | **Jobs list (the "dashboard" for managers and recruiters)** | Jobs counters, X-34 | The five counter cards are replaced by a **status tab bar with counts** that drives `?status=` (Tabs component; counts in tabular numerals with count-up on first load, M4). Request: ask the backend for `GET /jobs/stats` (one call); until it exists keep the five `size=1` calls but fire them in parallel and cache 60 s. Table: whole-row link, applicants column "38 · 4 new" (new = since the viewer's last visit, stored per viewer in localStorage), skeleton of 8 rows. Phone: stacked rows. Empty states per role, as today. |
| **B3** | **Job detail header + tabs** | X-33, X-6 | Breadcrumb; H1 + status badge + "Requirements v3" tag; at most two visible actions (Edit · primary status action) plus "⋯" (Close, Export audit CSV, Delete draft). Tabs scroll horizontally on a phone with fades. |
| **B4** | **Applicants ranking** | H-3, H-4, X-31 | `DataTable` ranking specialisation (DESIGN_SYSTEM §9.6): group header rows (Ranked / Must-haves not yet verified / Unscored) with counts and the one-line explanation; **evidence strip** column; Verified % and With claims % as numbers with 64 px meters; sticky header; density toggle (default compact: **≥ 20 rows visible at 1440×900**); ↑/↓/Enter keyboard; open ranking gates outlined in warning with a lock icon; "Matching…" rows show an indeterminate progress line in the strip cell. **Re-run matching** → when the polled list changes order, rows FLIP (M7). Coverage intro moved to a header tooltip plus a one-time dismissible hint. Fixtures to add: `job-applicants-rematch` (two list versions served in sequence), `job-applicants-loading`, `job-applicants-empty` (both already exist). |
| **B5** | **Applicant detail: header, Evidence tab, decisions** | **H-1**, X-13, X-7, X-21 | **No overlap at 1200, 1440 and 1920** (every wide child wrapped in `overflow-x:auto`; the harness overflow report is clean, and the screenshot shows the evidence column fully visible). Header: blind id/name + Blind tag, status, rank, **← Prev / Next →** (`[` `]`) keeping list order from the URL; decision bar right-aligned (primary per `allowedActions`, Reject as danger-ghost after a divider). A single **Reveal identity** (in the header). Tabs: Evidence · Claims · Work samples · Interviews · Verification (with counts). Evidence table: Requirement (body-strong, markers) · Ladder chip · Evidence (quote + `ProvenanceLine` + "To verify" hint); the open gate row is pinned first and expanded. **Verify claim** / **Not verified** inline on the related row with a note popover. Right rail 320 px (identity, review note with autosave, history `Timeline`) from 1200 px; below that it becomes a tab. Tier upgrade animates (M5). Identity reveal crossfades (M13). The shortlist dialog lists what becomes visible. |
| **B6** | **Applicant detail: Verification tab** | X-4, X-3, X-6 | One row per claim: claim · ladder chip · verdict chips; expand (M12) for Why / Evidence / Notes / Probe / Candidate note in a two-column definition layout, with no nested boxes. Confirm/Overrule as a `SegmentedControl` + note (required for Overrule) + **Save review**; after save the ladder lifts if the rule allows (M5) and the list rank refreshes. Shadow mode = one `Alert` line. Empty = the neutral sentence kept verbatim. Fixtures to add: `applicant-verification-shadow`, `applicant-verification-empty`, `applicant-verification-blind` (evidence count only). |
| B7 | My interviews + interview detail | Flow F | Interviewer view: question cards with an inline 1–4 `SegmentedControl` (anchors visible on focus/hover and always on phone) and notes; sticky "N of M rated" + **Submit scorecard**; confirm copy kept. Fixture to add: `interview-detail` with `role: 'INTERVIEWER'` (scorecard form visible). |
| B8 | Job form + requirement editor | X-24, job form | Requirement rows as a compact editable table (drag handle, type, text, Must-have, Gate with a tooltip, "⋯" expand for years/proficiency/alternatives). Salary inputs with a ₹ affix and en-IN grouping on blur. Sticky footer with a change summary and Save. Field errors mapped per field (X-23). The compiler panel becomes a drawer. |
| B9 | Work samples, Interview kit, Fairness, Details tabs | kit spacing | Anchors in a 4-column grid; fairness tables with flagged rows (warning rule + "Flagged" text). |
| B10 | Admin verification | Flow G | Readiness `Meter`s ×3 with targets; connectors as a 2-column switch list; Save enabled only when dirty; the shadow queue as list-detail with keyboard 1–5 + Enter. |
| B11 | Admin overview, staff, invitations, organisation, job oversight, audit, fairness | X-26 | `DataTable` + toolbar everywhere; health as dot + word; audit export hint fixed; pluralisation helper. |
| B12 | Notifications, change password, two-step verification | X-11, X-25 | Linked notifications; shared password rules (Unicode letters). |
| B13 | Phone navigation | X-10 | Drawer navigation replaces the chip strip; **no element overflows at 390** on any HR screen (harness). |

---

## Phase 4. Cross-portal passes (both, last two days)

1. **Dark theme pass:** walk every screen in dark at 1440 and 390. Check: no pure-white surfaces, no shadows doing the elevation alone, chips readable, focus ring visible, `theme-color` meta matches the canvas.
2. **Motion pass** (`--motion` captures plus a manual run): M1–M17 present where specified; check with DevTools "Animations" at 10% speed that enter/exit easing and durations match §8.2; reduced-motion end states correct.
3. **Brand-swap rehearsal:** change `src/design/brand/active.ts` to `plumbline`, then `assay`, then `gradus`; rebuild both portals; the harness exits 0 in both themes (the plugin's contrast gate passes); screenshots of login, ranking and the verification drawer for each preset go into `docs/design/screenshots/brand-<preset>/`. Revert to `neutral` (or to the chosen name).
4. `python3 tools/check_design_sync.py` passes; JS/CSS budgets (DESIGN_SYSTEM §13) checked from the `vite build` output.

---

## Screenshot checklist

Capture with `--theme both` at 1440 and 390 unless marked. ✓ = fixture exists today; + = add it to the fixture file.

### Candidate (`fixtures/candidate.mjs`)

| Screen | States |
|---|---|
| login, register | ✓ default · + error (wrong password) · + `unreachable` reason |
| jobs | ✓ list · ✓ empty (`jobs-empty`) · ✓ loading (`jobs-loading`) · ✓ error (`error-jobs`) · + filters sheet (390) |
| job-detail | ✓ default · ✓ apply dialog step 1 · + step 2 review · + step 3 sent · + 404 closed job |
| applications, application-detail | ✓ default · + empty · + withdrawn · + work-sample next step |
| verification | ✓ default · ✓ verdict drawer (`verdict-detail`) · + empty · + running · + website ownership · + reference form · + reference limit reached |
| resumes | ✓ default (COMPLETED) · + PENDING · + FAILED · + NEEDS_OCR · + correction open |
| evidence | ✓ default · + empty · + add-claim error |
| work-samples, work-sample-start | ✓ default · + STARTED (timer) · + final 5 minutes · + SUBMITTED · + EXPIRED |
| interviews, notifications, profile, privacy | ✓ default · + empty (interviews, notifications) |
| referee | ✓ default · ✓ closed (410) · + invalid (404) · + submitted · + declined |
| design | + `/__design` component gallery |

### HR (`fixtures/hr.mjs`)

| Screen | States |
|---|---|
| login | ✓ password · + MFA code · + enrolment (QR) · + recovery codes · + SSO error |
| jobs | ✓ manager · + recruiter (no Create) · + empty · + filtered empty |
| job-applicants | ✓ default · ✓ loading · ✓ empty · + rematch (reordered) · + status filter |
| job-details, job-interview-kit, job-form | ✓ default · + work-samples tab · + fairness tab (flagged row) · + live-job requirement change confirm |
| applicant-blind, applicant-revealed | ✓ default · ✓ shortlist dialog · + reveal dialog · + claim verify popover · + score-with-rubric dialog · + 1200 px width (`--widths 1200`) |
| applicant-verification | ✓ visible · + shadow · + empty · + blind (evidence count only) · + review saved |
| interviews, interview-detail | ✓ manager view · + interviewer view (`role: 'INTERVIEWER'`) with scorecard form · + submitted scorecard |
| notifications, security | ✓ default · + MFA off · + recovery codes ≤ 2 |
| admin-overview, admin-users, admin-verification | ✓ default · + invitations · + organisation · + audit · + job oversight · + fairness · + shadow queue item labelled |
| design | + `/__design` component gallery |

**Signature motion clips** (manual, record with DevTools at 25% speed and attach to the PR): coverage count-up (M4) on the ranking table; tier upgrade (M5) after verifying a claim; verdict reveal (M6) when a run completes; rank reorder (M7) after re-matching; theme switch (M14).
