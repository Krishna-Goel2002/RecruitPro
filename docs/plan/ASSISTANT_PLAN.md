# HR Assistant, HR fixes, landing site and motion: plan and contracts

Date: 17 Nov 2026 · Branch `Kshitij-redesign` · Owner decisions:

- The assistant uses **Gemini, Groq or offline** (rule-based) understanding. It is pluggable, with no default model.
- It **always confirms before any change**.
- The landing page is a **new public site**.

## 1. Product

An HR assistant built on top of the whole staff portal. It is useful and fast, never flashy. Every task it does can
still be done through the normal screens. It is available on every HR page:

- a command bar (`Ctrl/⌘ + K`, or the "Ask" button in the top bar), which opens a right-side panel;
- a full page at `/assistant`.

It knows the page the user is on: the current job, the current applicant, the admin area.

What it does (v1):

| Kind | Examples | Writes? |
|---|---|---|
| Insights | "How is the Backend Engineer posting doing?" (pipeline counts, ranking groups, top applicants by verified coverage, most-missing requirements, verification status, time in stage); "Tell me about A-3014" (evidence per requirement, gates, verification verdicts, work sample and interview results, open questions); "Compare A-3001 and A-3014"; "What needs my attention today?" (new applicants, unscored work samples, interviews without scorecards, expiring deadlines, verdicts awaiting review) | read |
| Job posting | "Post a job for a senior data analyst in Pune": guided slot filling (asks for only what is missing), drafts the description, suggests requirements via the existing requirement compiler, warns about biased wording, then shows a full **preview card**; the user picks **Save as draft** or **Publish** | confirm |
| Pipeline actions | shortlist / reject (with reason) / move to interview / select; assign a work sample; schedule an interview; generate a work sample or interview kit | confirm |
| Navigation | "Open the fairness report for this job" → a link card | – |
| Help | "How does verified coverage work?" → short answer from built-in help text | – |

Guardrails:

- **Permissions.** The assistant acts strictly as the signed-in user: it uses the same services, the same role checks
  and the same organisation scoping. An INTERVIEWER gets interview-related answers only.
- **Blind-first.** Candidates are referred to by application code (`A-3014`) until the identity is revealed. Names,
  emails, phones and URLs are never sent to an external model.
- **Confirm before change.** A write is always a **proposed action** with a preview. It runs only when the user
  presses Confirm, which calls a separate endpoint. Proposed actions expire after 30 minutes. Each one is audited with
  `via = ASSISTANT`.
- **Prompt injection.** Candidate-supplied text (resumes, cover letters, claims) reaches the model only as quoted data
  inside tool observations. The model can only choose from the tool whitelist, and every argument is validated on the
  server.
- **Privacy switch.** Organisation setting `assistantExternalModel` (default **false** for new organisations; the demo
  organisation is true). When false, only the offline planner is used, even if a provider is configured.
- **Cost and abuse.** At most 30 user messages per user per hour, 6 tool steps per message, and token limits per call.

## 2. Architecture

```
HR portal (panel/page) ──► backend /api/v1/assistant/**  (orchestrator, tools, permissions, audit, storage)
                                │  step request (de-identified context + observations)
                                ▼
                           ai-service /api/v1/assistant/step  (planner: offline rules | gemini | groq | anthropic | openai_compatible)
```

- The **backend owns everything stateful**: conversations, tool execution with the user's permissions, observations,
  proposed actions, confirmation, audit. It calls the planner in a loop of at most 6 steps per user message.
- The **ai-service planner is stateless**. It gets the conversation (de-identified), the tool catalogue for this
  user, the page context and the observations so far. It returns exactly one next step. Offline mode is
  deterministic (keyword/intent rules plus slot filling) and always available. LLM modes use strict-JSON prompting:
  provider-agnostic, with no native tool calling needed.
- LLM providers live in `ai-service/app/llm/provider.py`:
  - `gemini` (OpenAI-compatible endpoint `https://generativelanguage.googleapis.com/v1beta/openai/`, key
    `GEMINI_API_KEY`);
  - `groq` (`https://api.groq.com/openai/v1`, key `GROQ_API_KEY`);
  - the existing `anthropic` and `openai_compatible`.
- Assistant settings are `ASSISTANT_LLM_PROVIDER` (default `none` = offline) and `ASSISTANT_LLM_MODEL` (**required**
  when a provider is set; no default model name, because model names change: check the provider's model list).
  `ASSISTANT_LLM_TIMEOUT_SECONDS` defaults to 20.
- The ai-service has no internet in compose. Using a hosted model needs the existing `docker-compose.ai-egress.yml`
  override (document it).

## 3. Contracts

### 3.1 ai-service `POST /api/v1/assistant/step` (internal token, like the other ai-service routes)

Request:
```json
{
  "messages": [{"role": "user|assistant", "text": "..."}],            // last 20, de-identified
  "context": {"page": "job|applicant|jobs|admin|other", "jobId": 12, "jobTitle": "...", "applicationCode": "A-3014", "role": "HR_MANAGER"},
  "tools": [{"name": "job_insights", "description": "...", "args": {"jobId": "integer, required"}, "writes": false}],
  "observations": [{"tool": "job_insights", "args": {...}, "ok": true, "data": {...}}],   // this turn's tool results, de-identified
  "draft": {"kind": "job_posting", "fields": {...}} | null,           // slot-filling state kept by the backend
  "allowExternalModel": true
}
```
Response (exactly one of):
```json
{"type": "tool_call", "tool": "job_insights", "args": {"jobId": 12}, "thought": "short, shown as status text"}
{"type": "ask", "text": "Which location?", "field": "location", "options": ["Pune", "Remote"], "draft": {...}}
{"type": "propose", "action": "create_job", "args": {...}, "summary": "...", "draft": {...}}
{"type": "answer", "text": "markdown-lite", "blocks": [{"kind": "table|cards|link|metrics", ...}]}
```
`meta` on every response: `{"planner": "offline|gemini|groq|...", "model": "...|null", "fallback": false}`. If an LLM
call fails or returns invalid JSON, or a tool or argument is outside the catalogue, the planner falls back to offline
and sets `fallback: true`.

### 3.2 Backend (role: any staff; tools filter by role)

| Method + path | Body / result |
|---|---|
| `POST /api/v1/assistant/conversations` | `{}` → `{id, createdAt}` |
| `GET /api/v1/assistant/conversations?page` | the user's conversations (title, updatedAt) |
| `GET /api/v1/assistant/conversations/{id}` | `{id, title, messages:[Message]}` |
| `POST /api/v1/assistant/conversations/{id}/messages` | `{text, context:{page, jobId?, applicationId?}}` → `{messages:[Message...new]}` (the user message and the assistant reply, with steps run synchronously, at most 6, 60 s total) |
| `POST /api/v1/assistant/actions/{actionId}/confirm` | `{}` → `{action, result:{kind, id?, link?}, message: Message}`; 409 if already done or expired; re-validates everything and runs as the user |
| `POST /api/v1/assistant/actions/{actionId}/cancel` | `{}` → `{action}` |
| `DELETE /api/v1/assistant/conversations/{id}` | delete the conversation (own only) |

`Message = {id, role: "user|assistant", text, blocks:[Block], status?: "thinking-trace"?, planner, createdAt}`.

`Block` is one of:
- `{kind:"metrics", items:[{label, value, hint?}]}`
- `{kind:"table", columns:[...], rows:[[...]], caption?}`
- `{kind:"candidates", items:[{applicationId, code, revealedName|null, status, verified, claimed, group, gates, link}]}`
- `{kind:"job_preview", draft:{...full job + requirements + warnings}}`
- `{kind:"action", actionId, action, summary, preview, status:"PENDING|CONFIRMED|CANCELLED|EXPIRED|FAILED", expiresAt}`
- `{kind:"question", field, text, options:[...]}`
- `{kind:"link", label, href}`
- `{kind:"notice", tone, text}`

Tools (backend registry; each declares role capability, arg schema and `writes`):
- **Read:** `list_jobs`, `job_insights`, `applicant_insights`, `compare_applicants`, `attention_digest`,
  `my_interviews`, `fairness_summary`, `explain` (help topics).
- **Write** (always becomes a proposed action): `create_job` (draft or publish), `update_job_status`,
  `decide_application` (shortlist, reject+reason, interview, select), `assign_work_sample`, `schedule_interview`,
  `generate_work_sample`, `generate_interview_kit`.

The job-posting draft (`draft.fields`): title, department, location, jobType, experienceLevel, salaryMin/Max
(optional), openings, deadline, summary, responsibilities, requirements (list of `{type, description, mandatory,
knockout, minimumYears, alternatives, proficiency}`). The planner asks for missing required fields one or two at a time,
then calls the existing requirement compiler, then shows `job_preview` plus a `create_job` action.

Storage (migration **V29**): `assistant_conversations` (id, user_id, organization_id, title, created_at, updated_at),
`assistant_messages` (id, conversation_id, role, text, blocks_json, planner, created_at), `assistant_actions` (id,
conversation_id, user_id, organization_id, action, args_json, preview_json, status, expires_at, result_json,
created_at, updated_at). `organizations.assistant_external_model BOOLEAN DEFAULT FALSE`. Retention: conversations
older than 180 days are purged (extend the retention job).

## 4. HR portal fixes and upgrades (same workstream as the assistant UI)

1. **Back navigation everywhere.** A consistent page header with a back control (to the logical parent, not browser
   history: applicant → job applicants, job tabs → jobs list, admin subpages → admin overview, interview → my
   interviews, account pages → previous or home) plus breadcrumbs. Keyboard: `Alt + ←` works natively.
2. **Work-sample description scroll.** Textareas and long rich fields in drawers and dialogs must scroll (no
   `overflow: hidden` clipping). Check every textarea and drawer body.
3. **Status bar.** The "Applications by status" stacked bar must fill its track exactly: no inset at the left, a 100%
   segment reaching the right end, rounded only on the outer ends, segments separated by 1 px hairlines.
4. **Main pages redesign pass.** These are the pages people land on and use most: HR Overview/dashboard, Jobs list,
   Job → Applicants ranking, Applicant detail, My interviews. Clearer hierarchy, meaningful summaries, empty states
   that lead to the next action, and a visible entry point to the assistant.
5. **Motion** (both portals, professional, all honouring `prefers-reduced-motion`):
   - route transitions (fade and 8 px rise, 180 ms);
   - staggered list and table row entrance;
   - skeleton shimmer;
   - number count-ups and bar or ladder grow on first view (IntersectionObserver);
   - hover lift and press feedback on interactive cards and buttons;
   - drawer and dialog spring;
   - toast slide and stack;
   - tab indicator slide;
   - focus-ring transition;
   - the assistant panel open/close and "thinking" indicator (subtle);
   - a theme-switch transition.
   The shared motion utilities live in `src/design/motion*` (HR workstream owns `src/design`).

## 5. Landing site (new `site/` app)

- **Stack:** Vite + React + TS. Static, served by nginx on **port 5170** as compose service `site`. It uses the same
  design system (`site/src/design` is a synced copy; extend `tools/check_design_sync.py` to three folders) and the
  same brand presets.
- **Sections:**
  1. Hero: one sentence on what it is, an animated evidence-ladder visual, and CTAs "For companies → HR portal"
     and "For candidates → find jobs".
  2. The problem.
  3. How it works: evidence ladder → verification → recruiter decision.
  4. For HR teams: ranking, assistant, work samples, interviews, fairness.
  5. For candidates: strengthen your application, consent, preview.
  6. Trust and privacy: blind-first, consent, de-identified AI, audit.
  7. FAQ.
  8. Footer.
- **Quality bar:** professional, crisp, light and dark, fully responsive, accessible, fast (no external fonts or
  scripts; strict CSP).
- **Motion:** scroll-reveal sections, an animated ladder, and a hero product preview. Every animation is subtle and
  respects reduced motion.
- Portal URLs come from build args (`VITE_CANDIDATE_URL`, `VITE_HR_URL`).

## 6. Work packages

| WP | Owns |
|---|---|
| AS-PY | ai-service planner, providers (gemini, groq), offline rules, tests and an intent eval set |
| AS-BE | backend `assistant` package, V29, tools, actions, audit, retention extension, org setting, tests |
| HR-UX | hr-frontend: assistant UI (command bar, panel, `/assistant` page, block renderers, action confirm), back navigation, the scroll and status-bar fixes, main-page pass, motion system in `src/design` (and its synced copy to `frontend/`) |
| SITE-CAND | `site/` (new), compose service `site`, CI build job, design sync for three folders; candidate-portal main pages pass (Overview, Jobs, Job detail, Applications) consuming the motion utilities |
