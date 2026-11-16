# HR portal, Part B: redesign notes

**Branch:** `redesign` (uncommitted). **Flows:** [FLOWS-HR-B.md](FLOWS-HR-B.md). **Spec:** [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).
**Scope:** applicant review (all tabs, decisions, reveal), My interviews + interview detail + scorecards, inbox, account (password, two-step verification), every `/admin/*` page.

## 1. What changed, screen by screen

| Screen | Now |
|---|---|
| **Applicant detail** (`pages/applicants/*`) | PageHeader with blind avatar, status, Blind tag, prev/next (`[` `]`, position "3 of 9"), one **Reveal identity**. **Evidence at a glance** band: coverage count-up (M4), a clickable ladder-cell strip per requirement (must-haves first, open gates outlined) that jumps to and expands its row, and the rank group / open-gate sentence plus resume-file notes in a popover. Tabs Evidence · Claims · Work samples · Interviews · Verification (counts) · and below 1200 px "Identity & notes". Evidence rows: markers, requirement, ladder chip (fills on view, lifts on tier change, M5), quote peek; expand (M12, grid-rows) for Why / To verify / quote + ProvenanceLine and the **related claim inline with Verify claim / Not verified** (popover note, Enter submits). Open gates pinned first and open. **Sticky decision bar** at the bottom of the main column: coverage + gate summary, one primary per status (Shortlist → Schedule interview → Select; Reopen when rejected), the rest under "More", Reject as danger-ghost behind a divider; recruiters see the summary and "HR managers make decisions"; final states show the outcome. Right rail (≥ 1200): identity (blind card with redaction bars, crossfade + stagger on reveal, M13), review note (draft kept on device, Ctrl/⌘ Enter saves, SaveIndicator), history Timeline (new entry slides in). |
| Decisions | One `ActionDialog`: Shortlist lists exactly what a reveal exposes and to whom; Reject has quick reasons and says the candidate only sees "Not progressed"; Select shows a last check (coverage, scorecards, work-sample scores, every requirement's ladder). Success toast with **Undo** for Shortlist (→ Undo shortlist) and Reject (→ Reopen), sent with an automatic audit comment. |
| Work samples tab | Rows with status badge, timeline meta, score number + bar, expandable submission (text, link, files with audited download), "⋯ → Cancel assignment" (required reason). **Score with rubric** opens a 640 px drawer: submission on top, each criterion as four **anchored option cards** (`AnchoredRating`, keys 1–4), notes, sticky "n of m rated · average". Assign dialog uses radio cards and quick start-by picks. |
| Interviews tab | Rows: slot "Today, 10:15–11:00", interviewer avatar, Join link / room, state (Scheduled / Awaiting scorecard / Scorecard in / Cancelled), recommendation, inline read-only scorecard. Schedule / reschedule dialog: duration quick picks (30/45/60/90/Other), zone named, only changed fields sent. |
| **Verification tab** | Shadow = one neutral Alert (+ link to settings for managers). Visible: one row per claim (org tier ladder, verdict chips, review state), filter All / Needs review. Expanded: verdict chip, ownership line (KeyRound/KeySquare), **domain-trust chip** (`domainTrust`: "Employer domain trusted by automated checks" / "… by your organisation" / "… not established"; reasons, candidate-controlled and lifted-by-organisation notes), provenance, then a definition list Why · Worth asking about (labelled codes, neutral copy) · Suggested question · Evidence (blind: `evidenceCount` only) · Employer domain · Candidate's note. **Inline Confirm / Overrule** (segmented; note required to overrule), optimistic row update, then re-fetch so the ladder lifts. |
| My interviews | Waiting for your scorecard (warning rule + count), Upcoming grouped Today / Tomorrow / date, Completed and cancelled; whole-row links, staggered entrance. |
| Interview detail | Header with slot, zone, Join call (primary when live), Open the application (not for interviewers). Focus requirements in a sticky aside. Each question card: follow-ups disclosure, anchored rating, notes. **Draft autosaves on the device** and returns after reload. Recommendation segmented + summary, sticky progress footer, confirm dialog. Read-only scorecard with rating pips; **Edit scorecard** for the author. |
| Inbox | Grouped by day, unread weight + dot, every row opens its subject (server `link`, else route by template), optimistic mark-read and mark-all, All / Unread filter in the URL. |
| Change password | Show/hide, Part A's live `PasswordChecklist` (Unicode letters), per-field errors, success Alert. |
| Two-step verification | Stepper Scan · Confirm · Save codes; **QR code rendered locally** (`qrcode.react` 4.2.0, lazy chunk 6.4 kB gz, dark-on-white tile in both themes), grouped setup key in a CodeField, Part A's `OtpInput` (auto-submits, shakes on a wrong code). Recovery codes: numbered mono grid, Download .txt, Copy all, "I have saved these codes" gate. On: meter of codes left (warning ≤ 2), regenerate (confirm), danger-zone Panel to turn off (explanation instead when required by the organisation). |
| Admin overview | Service status list (dot + word + consequence) incl. **Verification agents** and the queue; StatRow count-up; status mix as labelled proportional bars. |
| Staff accounts / Invitations | DataTable + toolbar (URL-kept search, role, status), inline role select with a confirm naming the change, Disable as danger-ghost; **Invite staff drawer** (role radio cards with what each role can do, in-place success with the dev link). Invitations: Pending / All, Revoke. |
| Organisation | Sections, switches, `DomainListInput` for SSO domains, **sticky SaveBar** (only when dirty, sends only changed fields), confirm before turning blind review off. |
| Job oversight / Audit | DataTables; override dialog with allowed statuses as radios + required reason; audit export panel (dates default to the last 30 days, corrected hint), compact event table. Fairness keeps Part A's report view under a new header. |
| **Admin verification** | Mode block (Shadow/Visible, who changed it, readiness pill, Switch). Tabs **Readiness** (three Meters with target ticks, met/not-yet line, by-verifier and by-verdict tables), **Labelling queue** (list–detail, keys 1–5 / Enter / J K, optimistic save-and-next, bottom sheet on phones), **Connectors** (2-column switch list + SaveBar), **Trusted domains** (new: `trustedEmployerDomains`, `trustedBadgeIssuers` with the suggested copy and 400 mapping). Switching to visible repeats the meters; "anyway" when not ready. HR managers see Readiness + Queue. |

## 2. New generic components (in `hr-frontend/src/components/`, reusable by Part A)

`ActionDialog` (confirm + optional comment with quick reasons; `tone`, `focusCancel`) · `AnchoredRating` (1–4 anchored cards, digit keys) · `DomainListInput` (+ `normalizeDomain`, `domainProblem`) · `SaveBar` · `QueryError` / `InlineError` · `QrCode` (default export, lazy-load it) · `RecoveryCodes` · `VerdictParts` (`OwnershipLine`, `DomainTrustChip`, `VerdictDetails`, `EvidenceItems`, `SOURCE_ICON`). Styles: `staff-ui.css`, `verdict-parts.css` (`@layer components`). Libraries: `src/lib/localDraft.ts` (device drafts), `src/lib/when.ts` (slots, day labels, zone name). Reused from Part A: `OtpInput`, `PasswordChecklist`, `SaveIndicator`, `Shortcuts` (`useShortcuts`, `ShortcutSheet`), `StatusBadges`.

**For Part A:** the login enrolment can use `QrCode` and `RecoveryCodes` directly. Prev/next on the applicant page reads `location.state.order` (array of application ids) when the ranking table passes it, otherwise the newest cached ranking page that contains the applicant; `state.back` sets the breadcrumb target.

## 3. Additive API / tooling changes

- `api/types.ts`: `StaffVerdict.domainTrust` + `DomainTrust`; `VerificationSettings.trustedEmployerDomains/trustedBadgeIssuers` and the same optional fields on `VerificationSettingsUpdate`; `AppNotification.link` (optional, falls back to template routing).
- `package.json`: `qrcode.react` **4.2.0** (exact), lockfile updated.
- Harness: `snap.mjs` now also loads `fixtures/<portal>-*.mjs` extension files (their routes win); Part B fixtures in `fixtures/hr-b.mjs` (50 screens, roles HR_MANAGER, ADMIN, RECRUITER, INTERVIEWER; loading, error, empty, shadow, blind, dialogs, drawer, queue, enrolment).
- Removed: old `pages/applicants/{Candidate,Evidence,History,Interview,WorkSample}Section.tsx`, `components/Verification.tsx`; **465 dead legacy rules** pruned from `styles/*.css` (base 742→144 lines, workspace 1035→490, components 1170→932, pages 1927→474) by a usage scan over all `src/**/*.ts(x)`. Now unused and left for Part A to delete: `components/{Badges,Dialog,Field,Tabs,Pagination,ChipsInput,Icons,RatingScale,CoverageBars}.tsx`.

## 4. Verification

- `npx --yes pnpm@10 install --frozen-lockfile && npx --yes pnpm@10 build`: passes (tsc clean). JS 192.5 kB gz, CSS 34.9 kB gz for the whole portal (both parts; over the §13 budget, see §5).
- Harness: all 50 Part B screens × light/dark × 1440/390 = **200 captures, 0 page errors, 0 overflow, 0 serious/critical axe violations** (the final re-run of the applicant and queue screens showed no axe findings at any impact). PNGs: `outputs/design-shots/hr-b/` (`before/` holds the pre-redesign applicant page).
- `python3 tools/check_design_sync.py`: in sync (`src/design` untouched). There is no `test` script in either portal, so `pnpm test` has nothing to run.

## 5. Deviations and open points

1. **Review note is not autosaved to the server**: each `PUT review-comment` adds a history entry, so autosave would flood the history. The draft is kept on the device instead and saved explicitly.
2. **Undo** of Shortlist/Reject calls the server inverse with an automatic comment; the candidate may already have been notified of the first decision.
3. Notifications deep-link only to list pages until the backend sends `link` (or ids) per notification.
4. Related-claim matching on evidence rows is a client heuristic (type + words); the Claims tab always lists every claim.
5. Ladder lift after Confirm / Verify relies on the re-match finishing within the applicant page's polling window.
6. Bundle budget: route-level `lazy()` for admin pages would recover most of the overage, but it needs `App.tsx` changes (Part A owns navigation); only the QR encoder is split today.
7. Icons limited to the curated set (no MapPin/Video): rooms use `Building2`, video links `Link2`.
