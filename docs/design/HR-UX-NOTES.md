# HR-UX notes: assistant UI, back navigation, fixes, main-page pass, motion

Work package HR-UX of `docs/plan/ASSISTANT_PLAN.md` (sections 1, 3.2 block types, 4). Branch `wip/assistant-and-polish`.
The first run stopped at commit `ada5ff0`; this run finished it. Owner feedback addressed: no back button, work-sample
description could not be scrolled, the "Applications by status" bar did not fill its track, main pages needed to be
better, and motion was too sparse.

## 1. Assistant UI (`hr-frontend/src/assistant/`)

| Piece | Where | Notes |
|---|---|---|
| Top-bar **Ask** + `Ctrl/⌘ + K` | `AssistantPanel.tsx` (`AskButton`), `AssistantProvider.tsx` | Toggles a right-side panel (bottom sheet under 600 px). Escape closes and returns focus. The panel is resizable (keyboard-operable handle) and expands to `/assistant`. |
| `/assistant` page | `AssistantPage.tsx` | History on the left (new, open, delete with confirmation), `?c=` keeps the open conversation in the URL, the context of the page you came from is carried over until cleared. |
| Page context | `pageContext.ts` | `job`, `applicant`, `jobs`, `admin`, `interviews` (backend accepts it, AS-BE deviation 2), `other`. The context chip shows the job title as soon as the job is cached (subscribes to the job query, never fetches). |
| Suggested prompts | `pageContext.ts` `suggestionsFor` | Per page and role; interviewers only get interview prompts. Also "Ask next" chips under the latest answer. |
| Composer | `Conversation.tsx` | Enter sends, Shift + Enter new line, IME-safe, length hint, disabled while busy, failed sends keep the text with Try again. |
| Thinking indicator | `.ui-thinking` (motion.css) | Three calm dots (opacity only) plus the server's status trace; static under reduced motion. |
| Block renderers | `blocks.tsx` | `metrics` (count-up), `table` (sortable, numeric columns right-aligned), `candidates` (blind code until revealed, status, group, gates — boolean, count, `{open,total}`, sentence or the backend's list of open gate names — and labelled Verified / With claims meters), `job_preview` (full posting, requirement markers, wording warnings, "Suggested from the description", compiler note, **Save as draft** / **Publish** with a second "Publish now?" step), `action` (preview, live expiry countdown, Confirm / Cancel, PENDING / CONFIRMED / CANCELLED / EXPIRED / FAILED with the server's error), `question` (chips; disabled in older messages), `link` (internal via router, http(s) in a new tab, anything else as text), `notice` (tones incl. the backend's `error` → danger). |
| Markdown-lite | `markdown.tsx` | Bold, italics, code, lists, safe links only; no HTML is ever interpreted. |
| Accessibility | | Conversation is a `role="log"` live region wrapping an `<ol role="list">` (axe `listitem` fix); every icon button is named; label-in-name respected. |

Confirm sends `{mode: "DRAFT" | "PUBLISH"}` for `create_job` (AS-BE deviation 3); a 409 refreshes the conversation and
explains; FAILED (200) shows the refusal on the card.

## 2. Back navigation

- `src/lib/backNav.ts` resolves the **logical parent** for every route in `App.tsx` (table in the file header and in
  `backNav.test.ts`): applicant → that job's applicant list *with the view, sort and page you left*; job (any tab) →
  Jobs with its filters; new/edit job → Jobs / the job; interview → My interviews; admin subpages and first run →
  Overview (HR managers on `/admin/verification` → their home); inbox, account pages, assistant → the previous in-app
  page, else home; unknown routes → home. Top-level destinations have none.
- `AppLayout` provides it through `BackNavProvider`; every `PageHeader` shows "← Back to …" above the breadcrumb
  without per-page wiring. Restore points live in `sessionStorage` (`rp-back-memory`).

## 3. Fixes

- **Scroll**: `textarea.ui-input` always `overflow-y: auto`, `resize: vertical`, capped at `min(70vh, 720px)`; auto-grow
  never sets `overflow: hidden`; the thumb uses the stronger `--scrollbar-hover` colour so a scrollable field looks
  scrollable; drawer bodies reserve a stable gutter; dialog and drawer bodies scroll. (`Textarea.test.tsx`)
- **Stacked bar** (`design/components/StackedBar.tsx`): zero segments are skipped, widths sum to exactly 100 % (rounding
  goes to the last segment), the first segment starts at the left edge, a 100 % segment reaches the right end, only
  the outer corners are rounded, 1 px hairlines separate segments in both themes, and the bar grows in on first view.
  (`StackedBar.test.tsx`)

## 4. Main-page pass

New shared component **`SummaryStrip`** (`design/components/SummaryStrip.tsx`): 2–5 numbers in one bordered band,
count-up on first view, stagger, hairline separators, "attention" tiles with a marker and a tinted rule (never colour
alone), tiles can be filter toggles (`aria-pressed`) or links.

| Page | Change |
|---|---|
| Login (all auth screens) | Right column: a product preview card (blind ranking: rungs fill, coverage bars grow, a live dot) above a compact ladder legend, on a quiet dotted field. |
| Overview (admin) | "Needs attention" cards first (lift on hover, count-up), organisation stats, fixed status bars, service status; "Ask what needs attention". |
| Jobs | Summary strip: Open jobs / New applicants / Closing soon / Drafts / On hold (tiles filter the list); "Ask about jobs"; empty state offers New job and "Draft it with the assistant". |
| Applicants ranking | Summary strip: Applicants (+ new since last visit) / Ranked / Must-haves to verify / Unscored / Best verified coverage ("on this page" when paged); "Summarise" sends to the assistant. |
| Applicant detail | Back to the list state, breadcrumb, a labelled **Ask** button, glance strip. |
| My interviews | Summary strip: Scorecards waiting / Today / Next 7 days / Submitted; "Ask about my interviews"; Next-up card. |

## 5. Motion (`src/design/motion.ts`, `motion.css`)

Route entrance (fade + 8 px, 180 ms), list/table stagger (first load only), skeleton shimmer, count-ups and bar /
ladder / meter grow on first view (IntersectionObserver), hover lift and press, dialog spring and drawer glide, toast
slide and stack, tab indicator slide, focus-ring settle, theme switch (circular view transition, crossfade fallback),
assistant panel slide and message rise, reveal-on-view sections (`<Reveal>`). Every one has a reduced-motion
override (fade ≤ 120 ms or none), including the assistant panel and the login live dot.

## 6. Tests (Vitest, hr-frontend: 26 files, 200 tests)

Renderers for every block kind, composer, confirm/cancel/409 flows, draft vs publish (incl. the backend's linked
`actionId` shape), page context and suggestions, panel open/close/`Ctrl+K`/resize, back targets per route and restore
points, stacked bar widths, textarea/drawer/dialog scroll styles, `SummaryStrip`.

## 7. Harness (`tools/ui-snapshots`)

`fixtures/hr-ux.mjs` covers the panel (job, applicant, interviewer), the page (empty, insights, posting, publish
step, actions, delete), the main pages, empty/calm states and the long work sample. `snap.mjs` changes: full-page
captures now grow the viewport first and capture without Chromium's beyond-viewport path (it painted finished fades
and masks half-transparent), finished animations are settled before capture, and a screen can ask for
`viewport: true` (fixed panels). All HR-UX screens pass at 1440 and 390, light and dark, with `--axe` (0 serious).

## 8. Shared design system

`hr-frontend/src/design` is copied byte-identically to `frontend/src/design` and `site/src/design`;
`python3 tools/check_design_sync.py` passes. The candidate portal builds and tests green with the new copy.

## Known limits

- Ranking group counts in the summary strip come from the loaded page (labelled "on this page" when paged); a
  per-job stats endpoint would make them exact.
- The jobs summary's "new applicants" and "closing soon" are computed from the current list page (same reason).
