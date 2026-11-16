# Candidate portal redesign: notes

**Status:** done on `redesign` (16 Nov 2026), not committed. **Scope:** `frontend/` (candidate + referee). **Read with:** [FLOWS-CANDIDATE.md](FLOWS-CANDIDATE.md) (the flows this implements), [FOUNDATION_NOTES.md](FOUNDATION_NOTES.md), [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

Every candidate screen now runs on `src/design` components and tokens. The legacy stylesheets (`src/styles/*.css`, 2,450 lines) and the old page components (`Badges`, `CopyButton`, `VerdictChip`, `PageHeader`, the old `Dialog`, `ResumeCard`, five verification sections, the notice region) are deleted. `src/design/` is untouched (`tools/check_design_sync.py`: in sync).

## 1. Results

| Check | Result |
|---|---|
| Build (`pnpm install --frozen-lockfile && pnpm build`, clean copy of `frontend/` with the committed lockfile) | passes (`tsc --noEmit` + `vite build`). No test runner exists in `frontend/`. |
| Harness, 70 candidate screens × light/dark × 1440/390 (280 captures, `--axe`) | **0** page errors, **0** blank renders, **0** horizontal overflow, **0** axe violations of any impact |
| `--motion` run (login, overview, Strengthen, verdict drawer, apply sent, design-system) | 0 errors |
| First-load JS (gzip) | **93.2 kB** (was 125.9 kB after the foundation): every route is now `lazy()`; the largest page chunk is Strengthen at 15.5 kB |
| CSS (gzip, main) | **15.5 kB** (was 20.1 kB); page CSS loads with its route |
| Design sync | in sync |
| Screenshots | 280 PNGs in the session outputs (`design-shots/candidate-v2/`); a curated 88 (22 screens × 2 themes × 2 widths, 128 colours) in `docs/design/screenshots/after/candidate-*` |

## 2. What changed, screen by screen

| Screen | Change |
|---|---|
| **Shell / navigation** | New **Overview** item; groups *My evidence* (Resume · Claims · Strengthen) and *Tasks* (Work samples · Interviews) with live counts (results waiting for review, work samples to do); Inbox with unread count. Verify-email banner hidden for the browser session (was one page load). Routes are code-split with a skeleton fallback inside the shell. |
| **Auth** (login, register, forgot, reset, verify email) | Split layout: form column (max 400 px, 40 px controls) and, from 900 px, a product specimen on a sunken canvas: three claims on the evidence ladder, one verdict chip that resolves from "Checking…" to "Verified" after load (M6), and the ladder legend. Theme toggle in the corner. Inline validation on blur, focus to the first invalid field, show/hide password inside the field, SVG password checklist, "Next: confirm your email, then add your resume." Success/failure states with a tone icon. Brand only via `brand.name`. |
| **Overview** (`/home`, new; default landing) | *Get set up* checklist (confirm email · add resume with an inline dropzone · review what we read · strengthen with a source · apply), segmented progress bar that fills step by step, only the current step expanded, hide with Undo. *Needs your attention* (work samples by urgency, upcoming interviews, results to review). *Your applications* (3 most recent with status and next step). *Your evidence*: claims per ladder rung, bars and numbers count up when scrolled into view. A brand-new account sees the checklist alone. |
| **Find jobs** | Search with icon + inline Department / Location / Type; phone: search + **Filters** sheet with a count badge. Hairline rows, each a single link (one tab stop, hover wash bleeds past the text so hairlines stay aligned), **Applied** badge, salary, must-have count, "Closes in 12 days" (warning colour under 5 days, absolute date in the title and for screen readers). Row stagger on first load only; dimmed rows while updating; skeleton; filtered-empty with Clear filters; error with Try again (and "may be out of date" when stale). |
| **Job detail** | Breadcrumb back to the same search; reading column (description, requirements with must-have / nice-to-have markers and "Checked first" lock, never red); sticky rail with key facts and Apply (or **View your application**); **How your evidence matches**: "3 of 5 requirements found", a segmented bar (self-claims hollow), one ladder chip per requirement, **Strengthen your evidence** when a must-have is weak, labelled as an estimate. Phone: sticky bottom bar. Closed job (404) is an empty state. |
| **Apply dialog** | Stepper *Resume · Review · Sent*; newest resume preselected as radio cards; **no resume → upload inside the dialog** and auto-select; cover letter auto-grows, counts, and **autosaves per job**; review step lists the evidence that goes with the application and the blind-review rule; 409 shows "You already applied" with a link; *Sent* draws a check, shows "What happens next" (timeline) and offers **Add a source** when must-haves are not evidenced. |
| **Applications** | `DataTable` (stacked rows on phone, ↑/↓/Enter): Role · Status · **Next step** · Applied. Empty state points to the resume first if there is none. |
| **Application detail** | Breadcrumb, H1 + status, **View job** and a "⋯" menu with *Withdraw application…* (confirm names the job). **Next step panel** under the header: open/continue the work sample, or the interview with **Add to calendar** (.ics) and **Join** (primary in the 15 minutes before), or a calm status line. Timeline with future steps. Requirements with status badges and **Add evidence** (drawer) / **Verify with a source**. Rail: what you sent (cover letter disclosure), work samples, interviews with "IST (UTC+5:30)". |
| **Resume** | Dropzone uploads on drop/choose; files on the left (status per file, polled; selected in `?resume=`), *What we read* on the right: status alert, "Notes about this file" disclosure, facts grouped with quote + provenance line + ladder chip, **Suggest a correction** expands the row (grid-rows M12), Enter saves, Escape cancels, focus returns; "Looks right? Strengthen with a source". Reading → skeleton facts; stalled → Check again; failed → Retry analysis. Opening a finished analysis ticks the Overview step. |
| **Claims** | Candidate-voice ladder legend always visible; rows with ladder chip, type, dates, review state, link; **Verify with a source** on self-claims; Delete in a row menu (confirm kept); **Add a claim** drawer with optimistic insert and a highlight on the new row. |
| **Strengthen your application** | Stats with count-up (claims with outside evidence, waiting for review, shared, sources); **Waiting for your review** rows with **Review all**; Sources as rows (ownership with KeyRound/KeySquare, last check, inline progress line while running, Run again / Prove ownership, Revoke in a menu); **All your claims** with ladder chips and one chip per source — chips show "Checking…" while that source runs and **morph into the verdict** when the run finishes (same element stays mounted, M6); References with "N of 5 open". First visit: four-step *How it works* + **Add your first source**. Arrival message after GitHub OAuth kept (messages unchanged). |
| **Add a source** (drawer, new) | Stepper *Choose · Connect · Prove · Check*, step in the URL (`?add=source&step=…&kind=…&source=…`), so reloads and the GitHub round trip resume (`?connected=github` opens *Check* for the new source). Kind radio cards with icons; consent sentence directly above the connect button (wording unchanged); GitHub sign-in or username (503 OAUTH_NOT_CONFIGURED switches automatically); document dropzone; badge link or pasted credential. *Prove*: method segmented control, numbered steps, `CodeField`s with copy, inline outcome, **Skip for now**. *Check*: run, live progress, then "3 results are ready" with verdict chips revealed in sequence and **Review results**. Actions stick to the bottom of the drawer; phones get a bottom sheet with a compact stepper. |
| **Verdict drawer** (replaces the modal) | Title = claim; header chips (verdict + status) and "2 of 3 waiting" with previous/next (also **J/K**); verdict meaning, ownership line, Why (quotes), Evidence (title, quote, provenance with View source, fingerprint `CodeField`), Notes for context (intro unchanged), An interviewer may ask, **What employers see if you share**; note on demand; sticky footer Re-check · Hide · **Share with employers**. Share/Hide update the ledger optimistically (rolled back on error) and advance to the next waiting result. |
| **References** | Two-step drawer *Who → What to confirm* (claims with ladder chips and a live count, consent sentence unchanged); list with status badges; Cancel in a row menu with confirm. |
| **Work samples** | Rows with status badges and start-by (warning under 2 days). Detail: rules as a checklist with the corrected attachment copy; **Start, 90-minute timer begins** + confirm; while running a **sticky timer bar** (numeral in mono, elapsed bar, warning from 5 min, danger at 0, pulse in the last minute; announcements at 10/5/1 min only), instructions, response that **autosaves a draft**, link, `FileDropzone` (≤ 3 files). Submitted shows downloadable files; Expired shows the unsent draft with a copy button. |
| **Interviews** | Date tile, "Thu 19 Nov, 10:15 – 11:00", zone in words, **Add to calendar** (.ics generated in the browser), **Join** for meeting links. |
| **Inbox** | Every row links to its subject (application by job title, work sample by title, interviews, `/verification?review=first`, references); opening marks it read **optimistically**; per-row Mark as read; Mark all as read (optimistic); grouped Today / Earlier; kind icons. |
| **Profile** | Settings layout (section label + description left, fields right), inline validation on blur, ₹ and "days" affixes, sticky floating save bar only when dirty (Discard / Save changes, Ctrl/⌘+S, leave-page warning); password section with the checklist. |
| **Privacy** | Survey with "Why we ask" in the section column, two-column selects, consent sentence unchanged; export row; the danger zone is the only bordered panel. |
| **Referee page** | Brand mark + "Reference request" + theme toggle; heading, intro, validity line, one helper line for the four answers; per claim a 4-option `SegmentedControl` and **Add a comment** disclosure; identity checkbox; sticky footer "N of M answered" (bar) + **Send answers** (missing answers: scroll to and focus the first) + "I would rather not answer". **1,024 px tall at 390 px for three claims** (was 2,069 px). Invalid / closed / thank-you / declined are state pages with an icon. |
| **404** | Empty state with **Go to your overview**. |

## 3. Step counts, before → after

| Flow | Before | After |
|---|---|---|
| Register → resume uploaded | 8 clicks, 4 pages, resume requirement discovered in the apply dialog | 5 clicks, 2 pages, guided by the checklist |
| Apply without a resume | 9 clicks and leaving the dialog | 5 clicks inside the dialog |
| Apply with a resume | 4 clicks | 4 clicks, with the evidence summary |
| Upload a resume | choose + Upload (2 actions) | 1 action |
| Connect GitHub → share the first result | 8+ clicks with scrolling | 5 clicks in one drawer flow |
| Website: connect → prove → first check | 7 clicks across a form, a dialog and a separate Run | 5 clicks in one drawer |
| Notification → start a work sample | 5 clicks | 3 clicks |
| See an application's next step | open + scroll to the bottom | in the list and at the top of the detail |
| Referee, 3 claims, phone | ≈ 2,069 px | ≈ 1,024 px |

## 4. Components and helpers added (outside `src/design`)

Candidates for the shared system are marked ★.

| File | What |
|---|---|
| `components/Feedback.tsx` | ★ `ErrorState` (inline error + Try again, stale note), `FormError`, `FullPageLoading`, ★ `Facts` (definition list rows) |
| `components/Form.tsx` | ★ `PasswordInput` (reveal inside the field), ★ `PasswordChecklist` (SVG), `fieldErrorMap`, ★ `focusFirstInvalid` |
| `components/Dialog.tsx` | ★ `ConfirmDialog` on the design `Dialog` (alert role, Cancel first and focused for destructive) |
| `components/Consent.tsx` | `ConsentCheckbox` on the design `Checkbox` |
| `components/Notices.tsx` | `useNotify` now wraps `useToast` (optional body and one action, e.g. Undo / Review) |
| `components/ClaimForm.tsx` | claim form on design fields, optimistic insert, sticky actions in drawers |
| `pages/common.css` | ★ hairline rows, ★ whole-row link with bleed hover, ★ main + sticky rail split, meta lines |
| `lib/drafts.ts` | ★ `useDraft(key)` autosaved drafts with "saved at" |
| `lib/calendar.ts` | ★ `timeZoneLabel()` ("IST (UTC+5:30)"), `timeRange()`, `downloadInterviewIcs()` |
| `lib/match.ts` | requirement ↔ evidence text match (estimate) |
| `lib/nextStep.ts` | application next step from status + tasks |
| `lib/notificationLink.ts` | notification → route + icon |
| `lib/queries.ts` | shared `queryOptions` (resumes, claims, ledger, sources, references, tasks, recent applications) |
| `lib/storage.ts` | ★ guarded local/session storage |
| Page patterns | sticky timer bar, sticky save bar slot, indeterminate progress line, step reveal list, checklist panel |

Icons used are all from `design/icons.ts`; no extra lucide imports were needed.

## 5. API gaps (designed around)

1. Applications list has no next step; work samples and interviews have no `applicationId`. Joined by job title.
2. Notifications carry no link or subject id; matched by the title in the subject.
3. No server flag for "reviewed the analysis"; stored per browser (`resume-analysis-reviewed`).
4. No match preview for a job before applying; client-side exact/alternative text match, labelled as an estimate.
5. Which claims a run will check is not exposed; "Checking…" chips go on skills for code hosts and on every claim otherwise.
6. No upload progress from `fetch`; uploads show a fixed partial bar.
7. Drafts (cover letters, work-sample responses) are browser-only.
8. A reference request's answered results cannot be linked to their verdicts (only `verdictCount`).

## 6. Known issues and follow-ups

- **In-repo install:** on this machine `pnpm install` inside the Windows-mounted `frontend/` did not finish within the tool time limit (filesystem speed). The identical command on a clean copy of `frontend/` with the committed lockfile passes; a partial `frontend/node_modules/` (git-ignored) may remain — rerun `pnpm install` locally.
- Full-page harness captures show sticky footers (job detail, referee, save bar) and drawers mid-page; this is the capture method, not the layout.
- Consent sentences and the survey consent still contain the literal product name, deliberately (versioned server-side). Handbook 03 §7.5 still names `ConnectSection.tsx` as the home of the source consent wording; it now lives in `pages/verification/AddSourceDrawer.tsx` (`consentText`, unchanged).
- Optimistic undo is offered only for local actions (hiding the checklist); server-side deletes keep confirmations by design.
- The Self-claimed rung renders as a tiny dashed shape at 1× pixel density (design-system component, unchanged).
- Notification links use title matching, so two applications with the same job title open the first one.
- New fixture screens (all in `tools/ui-snapshots/fixtures/candidate.mjs`): overview (default, new account, reading, loading), add-source steps (choose, connect, connect error, prove, check, running), reference drawer, Strengthen (empty, running, loading, error), apply (dialog, review, sent, no resume), job detail (new, closed), filters sheet, applications empty, application (interview, withdrawn, withdraw confirm, loading), resume (reading, failed, empty, correction, loading), claims (empty, add), work sample (started, final minute, submitted, expired, empty), interviews and inbox empty, profile dirty, 404, referee (invalid, missing answers), auth (login validation, forgot, reset, verify failed).
