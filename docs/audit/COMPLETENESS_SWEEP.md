# Completeness sweep: everyday basics

Branch `wip/assistant-and-polish`, 17 Nov 2026. The trigger was the owner's report: "you cannot delete the draft of a
work sample... find more things like this". For every object a user creates or manages, this sweep checked the full
lifecycle in the API and in the UI: create, view, edit, delete or archive, duplicate, cancel or undo, status changes,
confirmation of destructive actions, success and error feedback, empty, loading and disabled states (with a reason),
unsaved-changes warnings, keyboard access, and whether every API action is reachable in the UI. It also checked small
polish issues: truncation, alignment, button labels, dead links and pagination.

Legend: ✅ was already complete · 🔧 fixed in this sweep · ⏸ deferred (reason given in §3).

## 1. Inventory

### HR portal and backend

| Object | Create | View | Edit | Delete / archive | Duplicate | Status / undo | Confirm / feedback | Unsaved changes | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Job (draft, published, on hold, closed, cancelled) | ✅ wizard | ✅ | ✅ | ✅ delete draft (UI) · 🔧 now audited (`JOB_DELETED`), refused with "close instead" when not a draft or when it has applications | 🔧 `POST /jobs/{id}/duplicate`, "Duplicate as draft" in every job menu (also how a closed role is re-opened) | ✅ hold and resume with Undo, the rest confirmed | 🔧 "Keep draft" instead of a second "Cancel" | 🔧 wizard: in-app links now ask "Leave without saving?" (before: only a tab close) | |
| Requirements | ✅ | ✅ | ✅ | ✅ remove with Undo | via job copy | ✅ re-match confirm on live jobs | ✅ | 🔧 (wizard guard) | 🔧 "Add requirement" at the limit of 100 now says why |
| Work sample (draft, active, archived) | ✅ generate · 🔧 **write by hand** (`POST …/work-samples`); also offered when generation fails | ✅ · 🔧 assignment count in the row | ✅ drafts | 🔧 **delete a draft** (`DELETE …/work-samples/{id}`, HR manager or admin, never-assigned drafts only, audited `WORK_SAMPLE_DELETED`, confirmation); for active ones "Delete…" explains why and offers **Archive instead**; 🔧 archive a draft | 🔧 `POST …/{id}/duplicate` (the way to change an active one) | 🔧 **Move back to draft** while nobody was assigned it (the API allowed it and its error message told people to do it, but the UI had no way) | ✅ / 🔧 toasts for every action | 🔧 the editor drawer asks before Escape, backdrop or Cancel throws edits away | 🔧 status badges aligned in one column |
| Work-sample assignment | ✅ assign | ✅ | n/a | ✅ cancel with reason | n/a | ⏸ re-assigning the same sample after a cancellation (§3); 🔧 the applicant page now explains the duplicate route | 🔧 "Keep assignment" | n/a | ✅ score, download (audited) |
| Interview kit and questions | ✅ generate | ✅ | ✅ | ✅ remove question with Undo; regenerate confirmed | n/a | ✅ | ✅ | 🔧 Cancel with edits asks; switching tab or following a link asks | 🔧 disabled Regenerate now says why |
| Interview | ✅ schedule | ✅ | ✅ reschedule | ✅ cancel (confirmed) | n/a | ✅ | 🔧 "Keep interview" next to "Cancel interview" | ✅ | |
| Scorecard | ✅ | ✅ | ✅ author until decided | n/a (kept for audit) | n/a | n/a | ✅ | ✅ device draft | 🔧 **earlier versions**: `GET /interviews/{id}/scorecard/revisions` existed but was unreachable; now "Show earlier versions" (author, admin, HR manager) |
| Review note | ✅ | ✅ history | ✅ | n/a (history) | n/a | ✅ device draft | ✅ | ✅ | |
| Decisions | ✅ | ✅ | n/a | n/a | n/a | ✅ 5 s Undo, reopen | ✅ | ✅ | |
| Invitation | ✅ | ✅ | n/a | ✅ revoke (confirmed) | n/a | 🔧 **Resend** (pending) and **Invite again** (expired or revoked); the server retires the old link | 🔧 "Keep invitation" | ✅ | |
| Staff user | via invitation | ✅ search, filters, paging | ✅ role (confirmed) | ✅ disable (history kept) | n/a | ✅ enable again | ✅ | n/a | |
| Own account | n/a | ✅ | 🔧 **own name** (`PATCH /api/v1/me`, audited `ACCOUNT_NAME_CHANGED`, new "Your name" page) · ✅ password · ✅ MFA | n/a | n/a | n/a | ✅ | 🔧 | |
| Organisation settings | n/a | ✅ | ✅ save bar | n/a | n/a | ✅ discard | ✅ | 🔧 leaving with unsaved changes asks | |
| Verification connectors and trust lists | n/a | ✅ | ✅ save bar | ✅ | n/a | ✅ discard | ✅ | 🔧 tab switch or link asks | |
| Assistant conversation | ✅ | ✅ | n/a | ✅ delete (confirmed) | n/a | ✅ | ✅ | ✅ composer keeps text | 🔧 long titles get a tooltip |
| Notifications (inbox) | n/a | ✅ paging | n/a | ⏸ delete (§3) | n/a | ✅ mark read, mark all read | ✅ | n/a | |
| Saved views | n/a | ✅ in the URL (copy link) | ✅ | n/a | n/a | n/a | ✅ | n/a | |
| Audit log | n/a | ✅ paging, filter, CSV | n/a | n/a | n/a | n/a | ✅ | n/a | 🔧 filter now lists `JOB_STATUS_CHANGED`, `ASSISTANT_ACTION_CONFIRMED`, `RETENTION_PURGE` and the new actions |

### Candidate portal and landing site

The candidate portal and site were swept in parallel; see `frontend/src/pages/__tests__/completeness.test.tsx`.

| Object | Gap | Status |
|---|---|---|
| Work-sample response (running) | Draft could not be discarded; the link was not kept on reload | 🔧 "Discard draft" (confirmed); link kept |
| Work-sample response (expired or cancelled) | Unsent draft stayed in the browser and was not shown | 🔧 shown with copy buttons and "Delete draft" |
| Work-sample response (submitted) | Leftover local draft | 🔧 cleared |
| Apply dialog | Saved cover letter could not be cleared; only 4 resumes listed with no note | 🔧 "Clear cover letter" with Undo; "4 newest of N" |
| Resume corrections | Undoing one meant retyping the original | 🔧 "Remove my correction" |
| Claim and evidence drawers | Escape or Cancel threw typed text away | 🔧 "Discard this claim?" |
| Referee form, reference drawer, add-source step, claim form, survey | No warning before a reload or tab close loses input | 🔧 beforeunload guard |
| Result drawer | Greyed-out Hide/Share with no reason | 🔧 reason shown |
| Inbox link to `/verification#references` | Landed at the top | 🔧 scrolls to the section |
| Interviews, work-sample header | No link back to the application | 🔧 |
| Resume list, apply dialog | Long names cut without a tooltip | 🔧 |
| Profile | Name could not be corrected | 🔧 "Name" section (`PATCH /me`) |
| Site | Unknown paths showed the landing page with broken anchors | 🔧 404 view; a test checks every link resolves |

## 2. Polish fixed across the HR portal

- Dismiss buttons no longer read like the action: `ActionDialog` takes `cancelLabel` ("Keep interview", "Keep
  assignment", "Keep draft", "Keep invitation", "Keep editing").
- Disabled controls say why where a tooltip cannot appear: Re-run matching (no requirements), Regenerate kit, Generate
  work sample, Add requirement (limit), Compare ("pick 2–3" also with one selected).
- Truncated text gets a `title`: assistant history, account-menu name, current breadcrumb, audit details, submitted file
  names, job location, work-sample titles.
- Stacked phone tables no longer show a stray "·" for a row whose cell is empty (`td:empty`, design system).
- Work-sample rows: status badges line up in one column whatever actions a row has.
- Design system: icons `Archive` and `Undo2` added (synced to the candidate portal and site).

New shared helpers: `hr-frontend/src/lib/leaveGuard.tsx` (`useLeaveGuard`: beforeunload plus a confirmation for in-app
links and tabs, replaying the click on "Leave without saving"; `useDiscardGuard`: "Discard your changes?" for drawers
and inline editors).

## 3. Deferred, with reasons

| Item | Reason |
|---|---|
| Browser Back while a form is dirty | The apps use `BrowserRouter`; holding Back needs a move to a data router (`createBrowserRouter` + `useBlocker`). Tab close/reload and in-app links are covered. |
| Assign the same work sample again after a cancellation | `work_sample_assignments` has a unique (sample, application) constraint; a partial unique index is not portable to the H2 test profile. The UI explains the route: duplicate the sample and activate the copy. |
| Delete or archive notifications | Read state already clears the inbox; low value next to the schema change. |
| Candidate: decline or ask to reschedule an interview; edit a claim (delete and re-add works); reference reminders; server-side work-sample drafts | New domain flows (notifications to staff, rate limits), not everyday gaps of existing objects. |
| Paging for "My interviews" and the candidate claims list | Lists stay short (per interviewer, per candidate). |

## 4. Tests and gates

- Backend (JUnit, H2): `hr/DraftLifecycleIntegrationTest` (draft work-sample delete and audit, active refused with
  "archive it instead", assigned sample cannot go back to draft or be deleted, assignment counts, write by hand,
  duplicate, tenancy and roles; draft job delete and audit, published refused, job duplicate with requirements),
  `auth/MeNameIntegrationTest` (own name, trimmed, validated, audited once). `java_static_check.py` 0 errors,
  `check_entity_schema.py` 0/0. No migration.
- HR portal (Vitest 29 files, 216 tests): work-sample lifecycle (delete, archive instead, back to draft, duplicate,
  discard guard, recruiter view), leave and discard guards, invitations resend, own name, scorecard versions.
- Candidate portal 153 tests, site 31 tests; all three apps build.
- Harness: `tools/ui-snapshots/fixtures/hr-sweep.mjs` (13 screens) at 1440 and 390, light and dark, `--axe`: no errors,
  no overflow, no serious violations. Shots in `outputs/design-shots/sweep/`.
