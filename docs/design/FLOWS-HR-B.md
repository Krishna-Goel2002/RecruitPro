# HR portal, Part B: user flows (current → optimal)

**Scope (Part B):** applicant detail (evidence, claims, work samples, interviews, Verification tab, decisions, reveal, review note, history), My interviews + interview detail + scorecard, notifications inbox, account (password, two-step verification), administration (overview, staff, invitations, organisation, job oversight, audit, verification incl. shadow labelling and trusted lists).
**Part A** owns sign-in, jobs, the ranking table and navigation; where a Part B flow starts on a Part A screen it is marked *(A)*.
**Inputs:** UX_REVIEW §4 and §6 (flows D–G), handbook 04 §4.3.7–4.3.11, 03 §4.7–4.8 and §7.3, 02 §6, WP-AG-NOTES "Trust fixes".

Click counts are pointer activations on the happy path. "Kbd" lists the keyboard route where it differs from Tab/Enter.

---

## 0. Rules every Part B flow follows

| Rule | What it means on screen |
|---|---|
| **Evidence first, decision last** | On the applicant page the evidence is read top to bottom; the decision bar is *sticky at the bottom* of the main column, so it is always reachable but never above the evidence that justifies it (fixes the "decision buttons above the evidence" finding). |
| **One next action** | Each state has one primary button: SUBMITTED → *Shortlist*; SHORTLISTED → *Schedule interview*; INTERVIEWING → *Select*; a submitted work sample → *Score with rubric*; a waiting scorecard → *Submit scorecard*; shadow queue → *Save and next*. Everything else is secondary or in "⋯". |
| **Blind before reveal** | Unrevealed applicants show the `EyeOff` avatar and "Candidate #A-…". Evidence items and candidate notes are never requested by the UI before reveal; the Verification tab shows `evidenceCount` ("2 evidence items · sources appear after identity is revealed"). Reveal is always an explicit, audited act with a dialog that names what becomes visible and to whom. |
| **Neutral wording** | "Not found in connected sources" (hollow chip), "Not checkable by agents", "Not progressed", integrity notes under "Worth asking about — observations for a conversation, not accusations". Coverage is "share of weighted requirements with evidence, not a probability of success". |
| **Optimistic where safe, undo where safe** | Safe: marking notifications read, labelling a shadow verdict, a review note, a decision that has a server inverse (Shortlist ↔ Undo shortlist, Reject ↔ Reopen) — these get a toast with **Undo** for 8 s. Not optimistic: reveal (irreversible), verdict review and claim verification (the server re-matches; the UI waits and then animates the tier lift), cancelling an assignment or interview (notifies people). |
| **States** | Every region has a layout-matching skeleton (after 150 ms), an inline error with *Try again* that keeps stale data visible, an empty state that says why and offers the next action, and an in-place success (row changes + toast). |
| **Deep links** | `?tab=` on the applicant page, `?tab=` and `?page=` on admin verification, `?page=` and `?filter=` on the inbox; notifications link to their subject. |
| **Keyboard** | `[` / `]` previous / next applicant, `1`–`6` applicant tabs, `N` focuses the review note, `?` lists shortcuts. Ratings: `1`–`4` on a focused rubric/scorecard question; shadow queue: `1`–`5` verdict, `Enter` save and next, `J`/`K` move. Shortcuts never fire inside a text field or with a modifier key. |

---

## 1. Reviewing an applicant end to end (recruiter; manager decides)

**Today** (UX_REVIEW Flow D/E): about 3 clicks + long scrolls per applicant; a single "Evidence and assessments" tab of ~2,700 px; decision buttons above the evidence; the gate row has to be hunted; claims verified in a separate section far from the requirement; no previous/next; the side column overlaps the table at 1440 px (H-1).

**Optimal**

| # | Step | Screen detail | Clicks |
|---|---|---|---|
| 1 | *(A)* Ranking table → row | Opens `/jobs/:id/applicants/:appId`. The list's filters travel in router state; prev/next uses the cached ranking order. | 1 |
| 2 | **Evidence at a glance** | Header: blind avatar + "Candidate #A-3014", status badge, "Rank 2 of 5 ranked", position "3 of 9" with ‹ › (`[` `]`). Under it the **evidence strip**: one ladder cell per requirement (must-haves first, open gates outlined with a lock), plus Verified / With claims coverage counting up (M4). | 0 |
| 3 | **Per-requirement drill-down** | Evidence tab (default) lists requirements as rows: marker (must/nice/gate) · requirement · ladder chip (fills on view, M5) · the cited quote with a provenance line. **Open-gate rows are pinned first and expanded.** Clicking a strip cell scrolls to and expands its row (M12). Expanded: explanation, "To verify" hint, and the **related claim inline** with *Verify claim* / *Not verified* (a popover with a note; `Enter` submits). | 0–1 |
| 4 | **Verification proof** | Tab "Verification" (count). Rows per claim with the org tier ladder and verdict chips; expand → Why / Worth asking about / Suggested question / Evidence / Candidate's note / domain trust. **Confirm** or **Overrule** inline (segmented control; note required to overrule) → *Save review*. When the server re-matches and the tier rises, the ladder lifts (M5) in place. | 2–3 |
| 5 | Review note | Right rail; draft kept on this device as you type, *Save note* (or `Ctrl/⌘ Enter`) writes one history entry. | 1 |
| 6 | **Decide** (manager) | Sticky decision bar: summary on the left ("Verified 79% · gate open: Java 17"), actions on the right: **primary** for the status, secondary actions in "⋯", **Reject** as a quiet danger button after a divider. | 1 |
| 6a | Shortlist (auto-reveal) | Dialog: "Shortlist and reveal identity?" lists exactly what becomes visible (name, contact, resume file, cover letter, claim links, verification sources) and that the reveal is audited with your name; optional comment → **Shortlist and reveal**. The identity card crossfades to the revealed facts (M13); the history gains an entry; toast with **Undo** (sends *Undo shortlist* with an automatic audit comment). | 2 |
| 6b | Reject with reason | Dialog: quick reasons (chips that fill the comment) + comment; "The candidate is notified as *Not progressed*." → **Reject**; toast with **Undo** (sends *Reopen*). | 2–3 |
| 6c | Move to interview | For SHORTLISTED the primary is **Schedule interview** (scheduling moves the status); *Move to interview* stays in "⋯". | 1 |
| 6d | Select | Dialog shows a last check: coverage, tiers per requirement, scorecards (recommendations) and work-sample score → **Select candidate**. Final state: "Selected — handed off to your offer process." | 2 |
| 6e | Undo / reopen later | "⋯ → Undo shortlist / Reopen" (comment required, as the server demands). | 2 |
| 7 | Next applicant | `]` or "Next ›" keeps list order and filters. | 1 |

**Result:** 1 click per applicant to open, 0 scrolling to find the gate, decisions 2 clicks, review and verification inline. Reveal and decisions never happen by accident (dialogs), but routine actions never leave the page.

### Reveal (explicit, without shortlisting)

| # | Step | Detail |
|---|---|---|
| 1 | Header "Reveal identity" (managers, when `revealAllowed`) — **one** place only (UX_REVIEW: duplicate button removed) | |
| 2 | Dialog: "Reveal this candidate's identity?" Visible to: everyone reviewing this job. Becomes visible: the list. "Recorded in the audit log with your name and the time. It cannot be undone." Cancel is focused first. | |
| 3 | **Reveal identity** → identity crossfades in (M13); verification evidence unlocks (the Verification tab refetches); toast "Identity revealed · recorded in the audit log". | 2 clicks |

---

## 2. Work sample: assign and score with an anchored rubric

**Today:** assign dialog OK; scoring dialog with a 1–4 radio row per criterion, anchors in a list, submission collapsed; no progress.

| # | Step | Detail | Clicks |
|---|---|---|---|
| 1 | Work samples tab → **Assign work sample** (managers; SHORTLISTED/INTERVIEWING) | Dialog: active samples as radio cards (title · time limit · criteria count), optional "Start by" date-time with quick picks (+2 days, +5 days). Disabled with the reason when nothing is assignable ("Activate a work sample on the job's Work samples tab"). | 3 |
| 2 | Candidate does it (candidate portal) | Row status updates: Assigned → Started → Submitted, with times. | — |
| 3 | **Score with rubric** (primary on a submitted row) | A 640 px drawer: the submission (text, link, files with *Download*, audited) on top; then each criterion with **four anchored option cards** (1–4, anchor text on each card) + an optional note; `1`–`4` keys rate the focused criterion. Sticky footer: "2 of 3 criteria rated · average 3.5" + **Save scores** (enabled; validates and jumps to the first unrated). | 1 + n + 1 |
| 4 | After save | Row shows "3.5 / 4 · scored by you"; linked requirements re-match; when the tier becomes Demonstrated the ladder on the Evidence tab lifts. | — |
| alt | Cancel | Row "⋯ → Cancel assignment" → dialog with required reason; "The candidate is told it is no longer needed; a submission and its files are kept." | 3 |

## 3. Interviews: schedule, reschedule, scorecard

| # | Step | Detail | Clicks |
|---|---|---|---|
| 1 | **Schedule interview** (decision bar primary when SHORTLISTED, or the Interviews tab) | Dialog: interviewer (name · role · email), date and time (local; zone named "India Standard Time (UTC+5:30)"), duration as quick picks 30/45/60/90 + custom (5–480), location or link. → **Schedule**. The status moves to Interviewing; both people are notified. | 4–5 |
| 2 | Reschedule | Row "Reschedule" opens the same dialog prefilled; only changed fields are sent; "To change the interviewer, cancel and schedule again." | 2–3 |
| 3 | Cancel | Row "⋯ → Cancel interview" → confirm naming the time and interviewer. | 2 |
| 4 | Interviewer: **My interviews** | Three groups: *Waiting for your scorecard* (first, counted), *Upcoming* (Today / Tomorrow / date), *Completed and cancelled*. Whole row opens the interview. | 1 |
| 5 | Interview detail | Header with time range, **Join** (when a link), "Open the application" (not for interviewers). "Focus on these requirements" as compact rows with ladder chips and hints. Each **question card** has the follow-ups, four anchored rating cards and notes directly below (no scrolling between question and form). | 0 |
| 6 | Fill the scorecard | Keyboard: Tab into a question, `1`–`4` rates, Tab to notes. **Draft autosaves on this device** ("Draft saved · 10:42") and is restored after a reload. Sticky footer: "4 of 6 rated" progress + recommendation chips (Strong no … Strong yes) + **Submit scorecard**. | n + 2 |
| 7 | Submit | Confirm: "Ratings of 3 or 4 on requirement-linked questions count as interview-verified evidence. You can edit it until the application is decided." → read-only scorecard with "Submitted · {time}". | 1 |
| 8 | Edit | **Edit scorecard** (author, until decided) → same form prefilled → *Save changes*; "Edited N times; earlier versions are kept for the audit trail." | 2 |

## 4. Confirming or overruling verification verdicts

| # | Step | Detail |
|---|---|---|
| 0 | SHADOW mode | The tab shows one neutral line: "Shadow mode. Verification is in shadow mode for your organisation; results are hidden until an admin switches it on." Admins/managers get "Open verification settings →". |
| 1 | VISIBLE | Header: "3 claims with shared results · 1 needs your review" + filter (All / Needs review). |
| 2 | Row → expand (M12) | Verdict chip(s), ownership (*Ownership proven* `KeyRound` / *not proven* `KeySquare`, "capped at Partly verified"), **domain trust** chip ("Employer domain trusted by automated checks" / "Trusted by your organisation" / "Employer domain not established" with reasons on hover/focus), provenance line (source · verifier · time). |
| 3 | Review | Segmented **Confirm / Overrule**; note optional for Confirm, required for Overrule (inline error, focus moves to the note). **Save review** → the row shows "Confirmed by you · now"; the server re-matches; when the org tier lifts to Corroborated the ladder animates and the Evidence tab follows. |
| 4 | Change a review | "Change review" re-opens the same inline form (no dialog). |

## 5. Admin: shadow → visible

| # | Step | Detail |
|---|---|---|
| 1 | Administration › Verification | Status block on top: **Mode: Shadow** + readiness ("2 of 3 targets met"), primary *Switch to visible* (ADMIN). Tabs: **Readiness · Labelling queue (n unlabelled) · Connectors · Trusted lists**. |
| 2 | Readiness | Three meters with target ticks: labelled 118 / 150, false corroboration 1.7% (≤ 2%), kappa 0.74 (≥ 0.70), each with met/unmet icon and word. By-verifier and by-verdict tables (empty state if no labels). |
| 3 | Labelling queue | List–detail: the left list (claim, agent verdict, labelled ✓), the right detail with reasons, notes, question and blind evidence count; **Your verdict** as a 5-option segmented control (`1`–`5`), note, **Save and next** (`Enter`). Saving is optimistic (the item flips to labelled, the next opens), metrics refetch. Phones: the detail opens in a bottom sheet. |
| 4 | Readiness met | The status block turns success: "Ready for visible mode". |
| 5 | Switch | *Switch to visible* → dialog repeating the three meters; if not ready, the button reads **Switch to visible anyway** and the risk is named. Saving re-ranks reviewed candidates (said in the dialog). |
| 6 | Connectors | 2-column switch list with descriptions; a sticky "Unsaved changes · Discard · Save" bar appears only when dirty. |
| 7 | Trusted lists (new) | *Trusted employer domains* and *Trusted badge issuers*: domain list inputs (paste several, normalised as you type: scheme, path and `www.` removed), server errors shown on the list. Same save bar. |

HR_MANAGER: Readiness and Labelling queue only (settings are ADMIN-only on the server); the status block says "Mode is managed by an administrator".

## 6. Two-step verification enrolment

| # | Step | Detail | Clicks |
|---|---|---|---|
| 1 | Account › Two-step verification (off) | Explanation + "Required by your organisation" notice when relevant → **Set up two-step verification**. | 1 |
| 2 | Step 1 *Scan* | Stepper (Scan · Confirm · Save codes). A real **QR code rendered locally** from the `otpauth://` URI (SVG, no network), with the secret in 4-character groups in a copyable code field for manual entry. | 0 |
| 3 | Step 2 *Confirm* | One-time-code field (6 digits, numeric keyboard, paste-friendly; submits when 6 digits are typed) → **Turn on**. Wrong code: inline error, field cleared and focused. | 1 |
| 4 | Step 3 *Save codes* | Recovery codes in a mono grid; **Download .txt**, **Copy all**; "I have saved these codes" checkbox enables **Done**. Leaving without confirming asks first. | 2–3 |
| On | Status | Turned on date · recovery codes left (meter; warning at ≤ 2) · *Create new recovery codes* (confirm) · *Turn off* (danger zone; replaced by an explanation when the organisation requires it). | |

Change password: current, new (live checklist: 10–72 characters, a letter — any language — and a digit, differs from current), confirm; show/hide; success alert "Password changed. Other sessions were signed out."

## 7. Inviting staff

| # | Step | Detail | Clicks |
|---|---|---|---|
| 1 | Staff accounts or Invitations → **Invite staff** | A drawer: work email, role as radio cards with what each role can do. | 1 |
| 2 | **Send invitation** | The invitation appears at the top of Invitations with *Pending* and the expiry; if the server returns a link (dev setups), *Copy link* is offered in the success state. | 1 |
| 3 | Revoke | Row *Revoke* → confirm. | 2 |

Staff accounts: role changes in place (select → confirm dialog naming the change), Disable/Enable (confirm). Own account and the last admin are protected (explained, not just disabled).

## 8. Organisation settings

One form in sections (Organisation · Review · Security · Single sign-on). Switches change local state; a **sticky save bar** shows "2 unsaved changes · Discard · Save changes" and only the changed fields are sent. Turning **blind review off** asks for confirmation with the bias explanation. *Require two-step verification* explains what happens at next sign-in. SSO domains use the same domain list input (exact domains, no wildcards, at most 20; empty turns SSO off).

## 9. Notifications → deep links

Rows grouped Today / Earlier; unread rows carry a dot and bold subject. Clicking a row marks it read (optimistic) and opens its subject: the server's `link` when present, otherwise a route by template (interview templates → My interviews, application templates → Jobs). *Mark all as read* is optimistic. Filter All / Unread kept in `?filter=`.

## 10. Administration, other screens

| Screen | Optimal flow |
|---|---|
| Overview | Service status list (dot + word + hint for Backend, Database, AI service, **Agent service**, Queue), a stat row that counts up once, applications and jobs by status as labelled proportional bars with values. Each block links to the relevant admin page. |
| Job oversight | Table with search + status; *Override status* only where a transition is allowed (otherwise "Final"); dialog with the allowed statuses as radios and a required reason. |
| Audit log | Action filter, compact table (when, action, actor, target, details, IP), CSV export panel with From/To prefilled to the last 30 days and the corrected hint. |
