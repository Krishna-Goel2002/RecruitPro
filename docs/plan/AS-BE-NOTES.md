# AS-BE: backend HR assistant — notes

Work package AS-BE of `docs/plan/ASSISTANT_PLAN.md` (sections 1–3). Branch `wip/assistant-and-polish`. The first run
(commit `ada5ff0`) wrote the package; the resume run checked every call against the real classes, finished the
contract (see section 8), added tests and wrote these notes.

Code: `backend/src/main/java/com/recruitpro/assistant/**`, `ai/dto/AiAssistantStep{Request,Response}.java`,
`AiServiceClient.assistantStep`, migration `V29__assistant.sql`. Tests: `backend/src/test/java/com/recruitpro/assistant/`.

Gates in this sandbox (no JDK 21 / Maven here): `tools/java_static_check.py backend/src` 0 errors,
`tools/check_entity_schema.py` 0/0, `tools/check_migrations_pg.py` V1–V29 applied. **Run `./mvnw verify` on JDK 21
before merging** (the static check does not resolve methods).

## 1. Storage (V29)

| Table / column | Notes |
|---|---|
| `organizations.assistant_external_model BOOLEAN NOT NULL DEFAULT FALSE` | the privacy switch; `acme-labs` (demo) set to TRUE by the migration and by `DemoDataSeeder` on a fresh database |
| `assistant_conversations (id, user_id, organization_id, title, draft_json, created_at, updated_at)` | `draft_json` is extra: the planner's slot-filling draft (the planner is stateless) |
| `assistant_messages (id, conversation_id, role user/assistant, text, blocks_json, planner, status_trace, created_at)` | `status_trace` is extra (the short "thinking" trace, `Message.status`) |
| `assistant_actions (id, conversation_id, user_id, organization_id, action, args_json, preview_json, status, expires_at, result_json, created_at, updated_at)` | status PENDING / CONFIRMED / CANCELLED / EXPIRED / FAILED (CHECK constraint) |

FKs cascade from conversations (messages, actions), users and organisations. Timestamps are UTC (`UtcTime.now()`,
auditing via `utcDateTimeProvider`).

**Names are never stored.** `revealedName` in candidate items and action previews is written as `null`
(`blocks_json`, `preview_json`) and resolved when the message is read, for the reader, from the application's current
state (`ApplicationAccess.visible`): a later anonymisation, a lost permission or a later reveal applies to old
conversations too. Free text the user typed is stored as typed.

Retention (`RetentionPurgeService`, rule `assistant_conversations`): conversations whose `updated_at` is older than
`app.retention.assistant-conversation-days` (180) are deleted with their messages and actions.

## 2. Configuration (`application.yml`)

```yaml
app:
  assistant:
    step-timeout-ms: ${ASSISTANT_STEP_TIMEOUT_MS:25000}          # read time-out of one planner call (AiServiceClient)
    max-steps: 6                                                 # planner calls per user message
    message-budget-ms: 60000                                     # wall clock per user message, tools included
    max-messages-per-hour: ${ASSISTANT_MAX_MESSAGES_PER_HOUR:30} # per user; 429 + Retry-After; 0 = off
    action-ttl-minutes: 30
    history-messages: 20
  retention:
    assistant-conversation-days: 180
```

Invalid values stop start-up (`AssistantSettings`, `RetentionSettings`). The rate limit uses the shared
`RateLimiterStore` (memory or database, key `assistant-messages|user:<id>`), independent of `app.rate-limit.enabled`.

## 3. Endpoints (`/api/v1/assistant`, roles ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER)

All responses use the usual envelope `{"success": true, "message": ..., "data": ...}`; below is `data`. Times are
ISO-8601 UTC. Another user's conversation or action (same organisation included) is **404**; candidates get 403.

### `POST /conversations` — body `{}` (ignored)

```json
{"id": 41, "createdAt": "2026-11-17T09:00:00Z"}
```

### `GET /conversations?page=0&size=20` (size 1..50; most recently active first)

```json
{"content": [{"id": 41, "title": "How is the Backend Engineer posting doing?",
              "createdAt": "2026-11-17T09:00:00Z", "updatedAt": "2026-11-17T09:02:10Z"}],
 "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true}
```

The title is the first user message (80 chars), "New conversation" until then.

### `GET /conversations/{id}`

```json
{"id": 41, "title": "...", "createdAt": "...", "updatedAt": "...", "messages": [Message, ...]}
```

### `POST /conversations/{id}/messages`

Request (`text` 1..2000; `context` optional; ids the user may not see are ignored):

```json
{"text": "Tell me about A-3014", "context": {"page": "applicant", "jobId": 12, "applicationId": 3014}}
```

`page`: job | applicant | jobs | admin | interviews | other (anything else = other). Response: the stored user message
and the assistant reply.

```json
{"messages": [
  {"id": 101, "role": "user", "text": "Tell me about A-3014", "blocks": [], "status": null, "planner": null,
   "createdAt": "2026-11-17T09:02:09Z"},
  {"id": 102, "role": "assistant", "text": "A-3014 has verified evidence for 4 of 5 must-haves ...",
   "blocks": [{"kind": "candidates", "items": [{"applicationId": 3014, "code": "A-3014", "revealedName": null,
               "status": "SUBMITTED", "jobId": 12, "verified": 72.5, "claimed": 80.0, "group": "MUST_HAVES_UNVERIFIED",
               "gates": ["Java"], "link": "/jobs/12/applicants/3014"}]}],
   "status": "Looking up A-3014", "planner": "offline", "createdAt": "2026-11-17T09:02:10Z"}]}
```

`Message = {id, role, text, blocks, status, planner, createdAt}` (nulls serialised). `planner`: `offline`, `gemini`,
`groq`, ... from `meta.planner`, `"<name> (fallback)"` when the planner fell back, `unavailable` when it could not be
reached, null for user and confirm messages. `status`: the tool trace joined with " · " (assistant messages).

Errors: 400 (blank or > 2000 chars), 404 (not the caller's conversation), 429 with `Retry-After` (31st message within
an hour; the refused message is not stored).

### `POST /actions/{actionId}/confirm` — body `{}` or `{"mode": "DRAFT" | "PUBLISH"}`

`mode` applies to `create_job` (the preview card's "Save as draft" / "Publish"; omitted = what was proposed, draft
by default); other values are 400; other actions ignore it.

```json
{"action": {"kind": "action", "actionId": 77, "id": 77, "conversationId": 41, "action": "create_job",
            "summary": "Create the job \"Senior Data Analyst\" (Pune) as a draft or publish it",
            "preview": {"summary": "...", "title": "Senior Data Analyst", "...": "..."},
            "status": "CONFIRMED", "expiresAt": "2026-11-17T09:32:10Z", "result": {"kind": "job", "id": 55, "link": "/jobs/55"},
            "error": null, "createdAt": "...", "updatedAt": "..."},
 "result": {"kind": "job", "id": 55, "link": "/jobs/55"},
 "message": {"id": 105, "role": "assistant", "text": "Saved the job \"Senior Data Analyst\" as a draft with 1 requirements.",
             "blocks": [{"kind": "notice", "tone": "success", "text": "..."}, {"kind": "link", "label": "Open", "href": "/jobs/55"},
                        {"kind": "action", "actionId": 77, "status": "CONFIRMED", "...": "..."}],
             "status": null, "planner": null, "createdAt": "..."}}
```

`result.kind`: job | application | interview | work_sample | work_sample_assignment | interview_kit. When the
re-validation or the service refuses, the call is still **200** with `action.status = "FAILED"`,
`result = {"kind": "failed", "error": "A-3014 changed from SUBMITTED to SHORTLISTED since this was proposed"}`,
`action.error` = the same text, and a warning notice message; nothing changed. **409** when the action is not PENDING
any more (already confirmed, failed, cancelled) or has expired (it is then marked EXPIRED). 404 for another user's
action.

### `POST /actions/{actionId}/cancel` — body `{}` (ignored)

```json
{"action": {"kind": "action", "actionId": 77, "status": "CANCELLED", "...": "..."}}
```

409 when it is no longer PENDING or expired.

### `DELETE /conversations/{id}`

`data: null`, message "Conversation deleted"; messages and actions go with it. 404 for anybody else's.

### Blocks

Planner blocks are untrusted: only `metrics`, `table`, `candidates`, `link`, `notice`, `question` are kept, sizes are
capped (12 blocks, 50 rows/items, 12 columns, 300-char cells), links must be portal-relative paths (`/jobs/12`;
`https://...`, `//...`, `javascript:` are dropped), tones are info|success|warning|error|danger. Candidate items are
re-resolved on the server: applications the user may not see are dropped; `status`, `jobId`, `link` and
`revealedName` come from the database (`link` is `/interviews` for an INTERVIEWER). `job_preview` and `action`
blocks are made by the backend only:

```json
{"kind": "job_preview", "actionId": 77, "draft": {"title": "...", "department": "...", "location": "...",
  "jobType": "FULL_TIME", "experienceLevel": "SENIOR", "salaryMin": null, "salaryMax": null, "openings": 2,
  "deadline": "2026-12-31T23:59:59Z", "summary": "...", "responsibilities": ["..."], "description": "...",
  "requirements": [{"type": "SKILL", "description": "SQL", "mandatory": true, "knockout": true, "minimumYears": 3,
                    "alternatives": [], "proficiency": null}],
  "requirementsSuggested": false, "warnings": [{"phrase": "rockstar", "kind": "EXCLUSIONARY", "suggestion": "..."}],
  "compilerNote": null, "modes": ["DRAFT", "PUBLISH"], "defaultMode": "DRAFT"}}
{"kind": "action", "actionId": 77, "action": "create_job", "summary": "...", "preview": {...},
 "status": "PENDING", "expiresAt": "2026-11-17T09:32:10Z"}
{"kind": "question", "field": "location", "text": "Which location?", "options": ["Pune", "Remote"]}
{"kind": "notice", "tone": "warning", "text": "..."}
{"kind": "link", "label": "Open job", "href": "/jobs/12"}
```

An action block's `status` is refreshed from the action row every time messages are read (expired → EXPIRED).

## 4. Orchestration

`AssistantOrchestrator.send`: ownership → rate limit → store the user message → up to `max-steps` (6) planner calls
within `message-budget-ms` (60 s; the planner call runs on a bounded pool and is abandoned at the deadline; each call
also has `step-timeout-ms`). Each step request: last 20 messages (scrubbed, ≤ 3900 chars each), context
`{page, role, today, jobId?, jobTitle?, applicationCode?}`, the user's catalogue, this turn's observations, the draft
and `allowExternalModel` (= the organisation's `assistantExternalModel`; the planner must stay offline when false).

| Step | Backend |
|---|---|
| `tool_call` of a read tool | validates args, runs as the user, adds a de-identified observation (errors become `{ok:false, error}`); unknown or not-allowed tool → `{ok:false, error:"Unknown tool ..."}` |
| `tool_call` of a write tool, or `propose` | validates args, `prepare` as the user (permission + current state, nothing changes), stores a PENDING action (30 min), replies with the tool's blocks (`job_preview`), the planner's validated notice/link blocks and the `action` block |
| `ask` | `question` block |
| `answer` | text + validated blocks |
| planner unavailable / timeout / unusable reply | `planner: "unavailable"`, a warning notice and links to the portal pages for the role (this job, this applicant, Jobs, My interviews, Administration); nothing changes |
| 6 steps without a final step | notice "I could not finish this within 6 steps" |

Draft: a non-null `draft` from any step is stored on the conversation (an object with `kind` is kept whole —
`fields`, `pending`, `stage`, ...; ≤ 30 000 chars) and sent back with every step; `draft: null` clears it on
ask/propose/answer (cancel, finished flow), not on `tool_call`. A confirmed `create_job` clears it.

**De-identification** (`Deidentifier`, one per message): e-mails → `[email]`, URLs → `[link]`, phone numbers (≥ 9
digits, not ISO dates) → `[phone]`, and names → `[name]` of every candidate whose identity a member of the organisation
can know (revealed, or all when blind review is off) plus every candidate a tool touches in this message (full name
case-insensitively, name parts ≥ 3 letters when capitalised). Applied to history, draft and observations. Tools emit
codes (`A-3014`), never names, e-mails or the candidate's URLs; candidate-supplied text appears only as scrubbed quotes
of ≤ 300 chars. Observations are kept under the ai-service limits (data ≤ 48 000 chars: lists cut to 20/10/5 items,
then replaced by a note; args ≤ 3 500 chars).

**Confirm** (`AssistantActionService`): owner check → atomic claim PENDING→CONFIRMED (`UPDATE ... WHERE status =
'PENDING' AND expires_at > now`; a second confirm or a confirm racing a cancel gets 409) → the tool must still be in
the user's catalogue (current role) → args re-validated → `checkUnchanged` (decide_application: the application's
status; update_job_status: the job's status — must equal what the preview showed) → `execute` through the existing
service as the user (which checks role, organisation and state again) → audit `ASSISTANT_ACTION_CONFIRMED`
(target = the created/changed object; details `via=ASSISTANT; action=...; assistantActionId=...; conversationId=...;
[mode=DRAFT|PUBLISH; ]...`, no personal data) in the same transaction as the change (AI drafting tools: right after,
since they must not hold a transaction during the AI call). Any refusal → FAILED with the reason; nothing changed.
The services' own audits (`APPLICATION_DECISION`, `JOB_STATUS_CHANGED`, ...) and notifications run as usual.

## 5. Tools

Capabilities (`ToolCapability`, mirroring `HrJobAccessService`): `ANY_STAFF` (ADMIN, HR_MANAGER, RECRUITER,
INTERVIEWER), `JOBS_READ` (ADMIN, HR_MANAGER, RECRUITER), `JOBS_MANAGE` (ADMIN, HR_MANAGER). The catalogue sent to
the planner is `{name, description, args: {name: "type, required|optional[, one of A|B][, min..max][, hint]"}, writes}`.

| Tool | Cap. | Args | Runs |
|---|---|---|---|
| `list_jobs` | JOBS_READ | `status?` DRAFT\|PUBLISHED\|ON_HOLD\|CLOSED\|CANCELLED, `search?` (≤ 100) | `JobService.listForStaff` (20 newest) |
| `job_insights` | JOBS_READ | `jobId` | `JobService.getForStaff`, `ApplicantQueryService.list` (rank order, ≤ 500): pipeline, ranking groups, top 5 RANKED, most-unverified must-haves, pending matching, work samples awaiting score, average days in stage |
| `applicant_insights` | ANY_STAFF | `applicationCode` | readers: `ApplicantQueryService.detail` + `StaffVerificationService.view`; INTERVIEWER: only applications they interview (`InterviewService.mine/detail`, focus requirements) |
| `compare_applicants` | JOBS_READ | `applicationCodes` (2–4, same job) | `ApplicantQueryService.detail` per applicant |
| `attention_digest` | JOBS_READ | – | newest 10 published/on-hold jobs: awaiting first decision, unscored work samples, pending matching, deadlines in 7 days; own interviews without scorecard / next 7 days |
| `my_interviews` | ANY_STAFF | `upcomingOnly?` boolean | `InterviewService.mine` (no candidate/interviewer names, no location) |
| `fairness_summary` | JOBS_MANAGE | `jobId?` (omitted = organisation; the service allows that to ADMIN) | `FairnessReportService.forJob/forOrganization` |
| `explain` | ANY_STAFF | `topic` VERIFIED_COVERAGE\|EVIDENCE_TIERS\|RANKING_GROUPS\|KNOCKOUTS\|BLIND_REVIEW\|WORK_SAMPLES\|INTERVIEW_KITS\|FAIRNESS_REPORT\|VERIFICATION\|ASSISTANT | built-in help (`HelpTopics`) |
| `create_job` | JOBS_MANAGE | `title`, `department`, `location`, `jobType` FULL_TIME\|PART_TIME\|CONTRACT\|INTERNSHIP, `experienceLevel` ENTRY\|MID\|SENIOR\|LEAD\|EXECUTIVE; `salaryMin?`, `salaryMax?`, `openings?` (1), `deadline?`, `summary?`, `responsibilities?` (string or list ≤ 30), `description?` (else built from summary + responsibilities), `requirements?` (≤ 100 `{type, description, mandatory, knockout, minimumYears, alternatives, proficiency}`), `publish?` (false) | preview: `RequirementCompilerService.compile` (wording warnings; suggested requirements when none given; unavailable → `compilerNote`). Confirm: `JobService.create` (DTO `JobCreateRequest`) + `replaceRequirements` (requirements v2) + `transition(PUBLISHED)` for PUBLISH, one transaction |
| `update_job_status` | JOBS_MANAGE | `jobId`, `status` PUBLISHED\|ON_HOLD\|CLOSED\|CANCELLED | `JobService.transition` (state checked at proposal and unchanged at confirm) |
| `decide_application` | JOBS_MANAGE | `applicationCode`, `decision` SHORTLIST\|REJECT\|MOVE_TO_INTERVIEW\|SELECT, `reason` (required for REJECT, ≤ 2000) | `ApplicationDecisionService.decide` (status unchanged since the proposal) |
| `assign_work_sample` | JOBS_MANAGE | `applicationCode`, `workSampleId?` (the job's only ACTIVE sample when omitted), `dueAt?` | `WorkSampleService.assign` (SHORTLISTED/INTERVIEWING) |
| `schedule_interview` | JOBS_MANAGE | `applicationCode`, `scheduledAt` (future), `interviewerUserId?` or `interviewer?` (staff e-mail/name; default: the user), `durationMinutes?` 5..480 (45), `location?` | `InterviewService.schedule` (moves SHORTLISTED to INTERVIEWING, notifies) |
| `generate_work_sample` | JOBS_MANAGE | `jobId` | `WorkSampleService.generate` (DRAFT sample; AI call, not transactional) |
| `generate_interview_kit` | JOBS_MANAGE | `jobId` | `InterviewKitService.generate` (replaces the kit; AI call) |

Argument handling (`ToolArgs`): unknown keys dropped; application codes accept `A-3014`, `a3014`, `#A-3014`, `3014`;
enums are case-insensitive and also accept a unique near form (`INTERVIEW` → MOVE_TO_INTERVIEW, `HOLD` → ON_HOLD,
`PUBLISH` → PUBLISHED, `SHORTLISTED` → SHORTLIST); date-times are ISO-8601 (offset converted to UTC); a plain date
means 23:59:59 UTC that day.

## 6. Deviations from the plan

1. **Extra columns**: `assistant_conversations.draft_json` (planner draft) and `assistant_messages.status_trace`.
2. **Message context** accepts page `interviews` too (sent to the planner as-is; the ai-service maps unknown pages to
   `other`); the planner context gets `today` (UTC date) and `role`.
3. **Confirm body** `{"mode": "DRAFT"|"PUBLISH"}` (the plan's `{}` still works) — the preview card's two buttons.
4. **Action shape**: `action` in confirm/cancel responses is a superset of the `action` block (`kind`, `actionId`,
   `id`, `conversationId`, `result`, `error`, `createdAt`, `updatedAt`).
5. **FAILED is 200**, not an error status: the confirm ran and was refused; 409 is reserved for "not pending /
   expired" as the plan says.
6. **Names not stored** (section 1): resolved at read time.
7. **Stricter confirm**: decisions and job status changes fail when the status differs from the preview, even if the
   new status would still allow the change.
8. **schedule_interview** takes an interviewer by e-mail/name or defaults to the user (the plan named no interviewer
   argument; user ids are not something people type). Staff names (not candidates') can appear in planner input when
   the user types them.
9. **attention_digest** has no "verdicts awaiting review" (no per-organisation queue outside the admin verification
   page); it covers the newest 10 published/on-hold jobs.
10. **fairness_summary** is JOBS_MANAGE (the fairness report's own rule); RECRUITER does not get it.
11. **Planner `propose.blocks`**: the planner's `job_preview` is replaced by the backend's (built from validated args
    and the backend's requirement compiler); its notice/link blocks are kept.
12. The ai-service's own reference catalogue (`ai-service/app/assistant/reference_catalogue.py`, AS-PY) uses some
    other argument names (`applicationId`, `startsAt`, decision `INTERVIEW`, `dueInDays`, `range`); the planner maps
    slots to the catalogue the backend sends (`catalogue._ALIASES`), and the backend accepts the near enum forms.
    `interviewer`, `workSampleId`, `dueAt`, `upcomingOnly` have no alias there yet (all optional).

## 7. Tests

`AssistantIntegrationTest` (`@SpringBootTest`, `@ActiveProfiles("test")`, MockMvc; the planner is
`@MockitoBean AiServiceClient`, `assistantStep` stubbed):

- conversations: create, list, get, title, delete; another user of the same organisation gets 404 on read, send and
  delete; a candidate gets 403;
- catalogue per role (HR_MANAGER 15 tools in order, RECRUITER 7, INTERVIEWER `applicant_insights`, `my_interviews`,
  `explain`); arg descriptions;
- an INTERVIEWER naming a write tool gets "Unknown tool"; `applicant_insights` for an application they do not
  interview fails; nothing proposed;
- tool loop: context (page, jobId, jobTitle, applicationCode), observations, and **no candidate name, e-mail, phone or
  URL in any planner request** (asserted on the serialised requests), placeholders present; candidate blocks dropped
  for another organisation, name only when revealed, evil link and planner `job_preview` dropped; names absent from
  stored `blocks_json` and resolved on read;
- create_job: ask → draft kept → propose (preview with compiler warning, PENDING action, nothing created) → another
  user's confirm 404 → confirm creates a DRAFT job with requirements in the organisation, audit `via=ASSISTANT` +
  `mode=DRAFT`, draft cleared → second confirm 409;
- confirm with `mode=PUBLISH` publishes (+ `JOB_STATUS_CHANGED`), compiler unavailable only adds `compilerNote`, an
  invalid mode is 400;
- expired action → 409 and EXPIRED; cancel (owner only) → CANCELLED; confirm/cancel after cancel → 409;
- decisions: REJECT without reason not proposed; state changed to REJECTED → FAILED with the reason, 409 afterwards,
  no assistant audit; MOVE_TO_INTERVIEW confirmed (+ `APPLICATION_DECISION`); preview name not stored, resolved in the
  response;
- state changed but still allowed (SUBMITTED → SHORTLISTED before a REJECT; PUBLISHED → ON_HOLD before a CLOSE) →
  FAILED, nothing changed;
- schedule_interview: interviewer defaults to the user, resolves by e-mail; confirm schedules and moves to
  INTERVIEWING; the INTERVIEWER then sees that applicant through `applicant_insights` (de-identified);
- lost permission (role changed to RECRUITER) → FAILED;
- rate limit: 31st message in an hour 429 + Retry-After, not stored; other users unaffected;
- planner unavailable (exception, null reply) → `planner: "unavailable"`, warning notice, portal links;
- at most 6 planner calls per message;
- organisation setting: default false → `allowExternalModel=false`; PATCH `/api/v1/admin/organization`
  `{"assistantExternalModel": true}` → true; back to false → false;
- retention: a conversation inactive for 200 days is purged with its messages and actions; a 10-day-old one stays.

`AssistantPlannerTest` (unit): a slow planner is abandoned at the remaining budget (timed out), no budget → no call,
unknown step type / exception → unavailable.

## 8. Resume run (after `ada5ff0`)

Verified every service/DTO call of the package against the real signatures. Changes:

- `WriteTool.checkUnchanged` + implementations (decision, job status) and the call in confirm.
- Candidate names no longer persisted (`withoutNames` on write, `resolveNames` on read); `toResponse(s)` take the user.
- `ActionResponse` gained `kind`, `actionId`, `error`; `job_preview` carries `actionId`.
- Planner limits of the ai-service model respected (history text, observation and args size, draft size); `today` in
  the context; drafts kept whole (`kind`, `pending`, `stage`, ...) and `draft: null` clears; the planner's propose
  notices kept.
- `ToolArgs`: plain dates = end of day; near enum forms.
- `create_job` stores and previews exactly the validated arguments.
- `schedule_interview`: interviewer by e-mail/name, default the user.
- Tests above (`AssistantPlannerTest` new; three integration tests added, two extended).
