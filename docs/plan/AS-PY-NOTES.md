# AS-PY notes - HR assistant planner (ai-service)

Status: **done** (17 Nov 2026), branch `wip/assistant-and-polish`. Scope: `ai-service/**` plus the new env vars in
`docker-compose.yml`, `docker-compose.ai-egress.yml` and `.env.example`. Plan reference: `ASSISTANT_PLAN.md` sections 1-3.
The first run was cut off by a usage limit after the code and tests had landed in commit `ada5ff0`; the resume run
reviewed it, fixed the gaps listed under "Resume run", and wrote the docs and env wiring.

## Verification

| Check | Result |
|---|---|
| `cd ai-service && python3 -B -m pytest -q -p no:cacheprovider` | 434 pass (95 in `tests/test_assistant.py`, 68 in `tests/test_backend_contract.py`; no network) |
| `python3 -B -m eval.run --check` | CHECK PASSED (unchanged resume/matching metrics) |
| `python3 -B -m eval.assistant_intents --check` | 204 utterances, offline intent accuracy 1.000 (threshold 0.9); also asserted in pytest |

The intent set is written by the team (plain, Indian-English, Hinglish, chat shorthand, typos), not sampled from
users: treat it as a regression guard, not a real-world accuracy figure.

## 1. Module layout

```
ai-service/app/api/v1/assistant.py          POST /api/v1/assistant/step (sync def: an LLM call blocks, runs in the pool)
ai-service/app/models/assistant.py          pydantic request/response contract and size limits
ai-service/app/assistant/planner.py         routing: offline | LLM (+ deterministic guided flows) | fallback; meta
ai-service/app/assistant/offline.py         OfflinePlanner: intents -> tool_call / ask / propose / answer
ai-service/app/assistant/intents.py         detect_intent(): rules, slots (codes, job phrase, decision, status, range ...)
ai-service/app/assistant/text.py            normalisation, typo repair, application codes, numbers, dates and times
ai-service/app/assistant/jobpost.py         job-posting slot filling, edits, questions, description, requirements, preview
ai-service/app/assistant/compose.py         answers + blocks from observation data
ai-service/app/assistant/catalogue.py       the user's tool catalogue: arg-schema parsing, building, validation, aliases
ai-service/app/assistant/llm_planner.py     strict-JSON prompt, scrubbing, step validation, job-description writer
ai-service/app/assistant/help_topics.py     built-in help text (coverage, evidence ladder, gates, blind review ...)
ai-service/app/assistant/reference_catalogue.py  the tool catalogue the planner is designed and tested against
ai-service/app/core/config.py               ASSISTANT_* / GEMINI_* / GROQ_* settings and validation
ai-service/app/llm/provider.py              GeminiProvider, GroqProvider (OpenAI-compatible), get_assistant_provider()
ai-service/eval/assistant_intents.py        the intent evaluation set and --check
```

## 2. Providers and settings

| Variable | Default | Notes |
|---|---|---|
| `ASSISTANT_LLM_PROVIDER` | `none` | `none`, `gemini`, `groq`, `anthropic`, `openai_compatible`. Independent of `LLM_PROVIDER` |
| `ASSISTANT_LLM_MODEL` | - | required when a provider is set; no default model name |
| `ASSISTANT_LLM_TIMEOUT_SECONDS` | `20` | 0 < t <= 120 |
| `ASSISTANT_MAX_OUTPUT_TOKENS` | `1024` | 64-8192 |
| `GEMINI_API_KEY` / `GEMINI_BASE_URL` | - / `https://generativelanguage.googleapis.com/v1beta/openai` | Bearer key; `POST {base}/chat/completions` |
| `GROQ_API_KEY` / `GROQ_BASE_URL` | - / `https://api.groq.com/openai/v1` | Bearer key; `POST {base}/chat/completions` |

`anthropic` reuses `ANTHROPIC_API_KEY`/`ANTHROPIC_BASE_URL`; `openai_compatible` reuses `OPENAI_BASE_URL`/`OPENAI_API_KEY`.
Validation (`validate_assistant_settings`) runs at start-up through `validate_settings`: an unknown provider, a provider
without model or key, or a non-http(s) base URL stops the service. `get_assistant_provider()` re-validates and returns
None (offline) on any problem. `/health` reports `assistantPlanner` (`offline` or the provider name). The resume and
requirement LLM (`LLM_PROVIDER`, `get_provider`) is unchanged; a test asserts the assistant settings do not affect it.
Requests use `temperature: 0` and `response_format: json_object`. A hosted model needs the
`docker-compose.ai-egress.yml` override (the compose AI service has no internet otherwise).

## 3. Contract as implemented (`POST /api/v1/assistant/step`)

Request (`StepRequest`): as plan 3.1. Limits: 1-20 messages of at most 4000 chars; at most 40 tools (name
`^[a-z][a-z0-9_]{0,63}$`, description <= 600, args schema <= 20 entries / 4000 bytes); at most 12 observations of at most
64 KiB data and 4 KiB args each (`error` <= 1000 chars); draft <= 32 KiB. An unknown `context.page` becomes `other`.
Over-limit requests are 422. Additive optional context fields: `applicationId` (used when it belongs to
`applicationCode`) and `today` (`YYYY-MM-DD`, the user's local date for relative dates; the server date otherwise).

Argument schemas in `tools[].args` are the backend's `ArgSpec.describe()` texts (`"integer, required, 1.."`,
`"string, optional, one of A|B|C, hint"`, `"application code like A-3014, required"`, `"list of application codes,
required"`, `"ISO-8601 date-time, optional, ..."`, `"string or list of strings, optional"`, `"list of {type, ...}"`),
short forms (`"array of integer, required"`) or objects (`{"type": "integer", "required": true, "enum": [...],
"format": "date-time", "minimum": 1}`). The catalogue in the request is the contract (section 9): every emitted tool
call or proposal uses only its tool names and argument names, converted to the declared type and format.

Response: exactly one of the four step types plus `meta: {planner, model, fallback}`:

- `tool_call {tool, args, thought, draft}` - read tools only;
- `ask {text, field, options[], draft}`;
- `propose {action, args, summary, blocks[], draft}` - writes only;
- `answer {text, blocks[], draft}`.

`meta.planner` is `offline` or the provider name (`gemini`, `groq`, ...); `model` is the configured model or null;
`fallback` is true when an LLM step was attempted and the offline planner answered instead.

### Draft shapes (kept by the backend and sent back unchanged on the next step)

- Job posting: `{"kind": "job_posting", "fields": {...}, "pending": "<field>|null", "asked": [...],
  "stage": "collecting|review", "inferred": [...]}`. `fields` holds the plan's fields (`title`, `department`,
  `location`, `jobType`, `experienceLevel`, `openings`, `deadline`, `salaryMin`, `salaryMax`, `summary`,
  `responsibilities`, `requirements` once in review) plus working fields: `city`, `workMode`
  (`REMOTE|HYBRID|ONSITE`), `noDeadline`, `skills[{name, mandatory, knockout, level, years, hardYears}]`,
  `minYears`, `minYearsHard`, `extraRequirements[]`, `skillsSkipped`, `sourceDescription` (a pasted JD, contacts
  scrubbed) and `userText` (the last 10 user messages of the flow, contacts scrubbed, used for the wording check).
- Pending action slot: `{"kind": "action", "intent": "<intent>", "fields": {<slots>}, "pending": "applicationCode|
  applicationCodes|job|reason|startsAt|<arg>"}`. The next message fills the slot; an unrelated request replaces it.
- `draft: null` in a response means "clear the draft" (cancel, finished action, plain answers outside a flow).

### `create_job` proposal

`args`: `{title, department, location, jobType (FULL_TIME|PART_TIME|CONTRACT|INTERNSHIP), experienceLevel
(ENTRY|MID|SENIOR|LEAD|EXECUTIVE), openings, deadline (ISO date, omitted for no deadline), salaryMin?, salaryMax?
(annual INR), summary, responsibilities[], description (full text), requirements[{type, description, mandatory,
knockout, minimumYears, alternatives, proficiency}]}`. If the catalogue declares other argument names
(`vacancies`, `minimumSalary`, `maximumSalary`) they are added; if it declares a single object argument (`job`,
`draft` or `posting`) the job is nested under it. Draft vs publish is chosen at confirmation (backend).
`blocks`: one `job_preview` (`draft` = the args plus `requirements[].rationale`, `warnings[{phrase, kind, suggestion}]`,
`inferred[]`, `descriptionSource: template|llm|user`) and `notice` blocks (changes applied, assumptions, wording
warnings, missing salary).

## 4. Tool catalogue and observation shapes

`reference_catalogue.py` holds the backend's catalogue verbatim (`REFERENCE_TOOLS` = HR_MANAGER/ADMIN, 15 tools;
`RECRUITER_TOOLS` 7; `INTERVIEWER_TOOLS` = `applicant_insights`, `my_interviews`, `explain`), kept equal to
`tests/fixtures/backend_tools.json` by a test: reads `list_jobs {status?, search?}`, `job_insights {jobId}`,
`applicant_insights {applicationCode}`, `compare_applicants {applicationCodes[2-4]}`, `attention_digest {}`,
`my_interviews {upcomingOnly?: boolean}`, `fairness_summary {jobId?}`, `explain {topic: VERIFIED_COVERAGE|...}`;
writes `create_job {...}`, `update_job_status {jobId, status}`, `decide_application {applicationCode, decision:
SHORTLIST|REJECT|MOVE_TO_INTERVIEW|SELECT, reason?}`, `assign_work_sample {applicationCode, workSampleId?, dueAt?}`,
`schedule_interview {applicationCode, scheduledAt, interviewerUserId?, interviewer?, durationMinutes? 5..480,
location?}`, `generate_work_sample {jobId}`, `generate_interview_kit {jobId}` (full schemas: AS-BE-NOTES section 5).
`LEGACY_TOOLS` is the earlier alias-named catalogue (`applicationId`, `startsAt`, decision `INTERVIEW`, `dueInDays`,
`range`), kept only to test that a catalogue's own names are always used.
A tool missing from the catalogue is never used: the planner says the action isn't available to the user's role
(an INTERVIEWER catalogue of `my_interviews` + `explain` gets interview answers only).

Observation `data` the composers understand (first listed key wins; unknown shapes get a generic table):

| Tool | Shape |
|---|---|
| `list_jobs` | `{jobs|items|content: [{id, title, status, applicants|applicationCount, deadline}], total?}` or a list |
| `job_insights` | `{job: {id, title, status}, total, pipeline|statusCounts: {STATUS: n} or [{status, count}], groups: {RANKED, MUST_HAVES_UNVERIFIED, UNSCORED}, top: [{applicationId, code, status, verified, claimed, group, gates?, link?}], mostMissing: [{description, notObserved, knockout}], verification?: {STATUS: n}, timeInStage?: {STATUS: days}, link?}` |
| `applicant_insights` | `{code, jobId, status, group, verified, claimed, requirements: [{description, status, tier, mandatory, knockout}], unverifiedKnockouts?: [..], workSamples?/interviews?/verification?: [{title, status, score, rating, verdict, completedAt, scheduledAt}], openQuestions?: [text], link?}` |
| `compare_applicants` | `{jobId?, applicants: [candidate items], requirements?: [{description, results: {"A-3014": {status, tier}}}]}` |
| `attention_digest` | `{newApplicants, unscoredWorkSamples, interviewsWithoutScorecards, expiringDeadlines, verdictsAwaitingReview, ...}`: numbers or lists of `{code, jobTitle, title, status, dueAt, deadline, scheduledAt}` |
| `my_interviews` | `{interviews|items: [{startsAt, code, jobTitle, status, scorecardSubmitted}]}` |
| `fairness_summary` | `{jobId?, adverseImpactRatio, flagged, groups: [{group, applicants, selected, rate}], link?}` |
| `explain` | `{text}` |

Links taken from data must be same-app relative paths (`/jobs/12/applicants/3014`); anything else is dropped.
Candidate blocks always carry `revealedName: null` (the backend fills names for users allowed to see them).

## 5. Routing and safety

- Offline when `ASSISTANT_LLM_PROVIDER=none`, when the provider is misconfigured, or when `allowExternalModel` is false.
- LLM mode: job posting slot filling, pending action slots, cancel, help, navigation and greetings stay deterministic;
  the model only writes the job summary and responsibilities there (validated, contacts scrubbed, rejected when it
  triggers a wording warning; the template is used otherwise). Everything else is planned by the model and validated
  (`validate_step`); any error falls back to offline with `fallback: true`.
- Model args are normalised first (`Catalogue.normalise_args`: synonyms renamed to the catalogue's names, values
  converted, unknown arguments dropped). Validation then rejects: tools/actions outside the catalogue, a read used as a
  write or vice versa, values that cannot be converted (bad enum, out-of-range integer, not an application code),
  missing required args, repeated identical tool calls, a proposal when the
  user's latest message does not ask for a change (prompt injection in observations cannot cause a write),
  `create_job` from the model, answers with numbers absent from the inputs, and coverage described as a probability.
- The prompt carries the de-identified context, the catalogue, the last 10 messages (<= 1200 chars each) and
  observations (<= 6000 chars total) with emails, phones and URLs scrubbed again and identity-like keys
  (`name`, `email`, `phone`, `linkedin`, `gender`, ...) removed.

## 6. Deviations from the plan

1. `propose` and `ask`/`tool_call`/`answer` all carry `draft`, and `propose` carries `blocks` (the `job_preview` card
   and notices). The plan listed `draft` only on ask/propose and `blocks` only on answer. The backend DTO
   (`AiAssistantStepResponse`) already has both fields on every type.
2. `draft.fields` contains working fields beyond the plan's list (section 3, "Draft shapes"); the `create_job` args
   contain only the plan's fields plus `description`.
3. `context.today` and `context.applicationId` are optional additions.
4. Default `ASSISTANT_MAX_OUTPUT_TOKENS` is 1024 (the plan names the variable, not a value).
5. In LLM mode the guided flows (section 5) are deterministic by design; the model never proposes `create_job`.
6. One decision per proposal: "shortlist A-1 and A-2" proposes A-1 and says to ask again for A-2 (one confirm, one
   audited change).

## 7. Resume run (what changed after `ada5ff0`)

- Intents: Indian-English headcount phrasing is now a job posting ("need 3 freshers for customer support",
  "require 2 resources for SAP FICO", "we require a java dev", "Opening for 2 Python developers at Bangalore");
  "is there any bias in this job" is a fairness summary; "which is better A-1 or A-2" is a comparison;
  Hinglish "kitne jobs open hain" lists jobs. 12 utterances added to the eval set (204 total).
- Job posting: a generic head noun gets a real title (`3 freshers for customer support` -> "Customer Support Trainee",
  `resources for SAP FICO` -> "SAP FICO Consultant", "java dev" -> "Java Developer"); "closing in 2 weeks" is a
  deadline; a short answer that clearly fills another field ("mid", "full time", "no deadline") is no longer taken as
  the title, city or department that was being asked for. Tests added for both.
- Env wiring in compose, the egress override comment and `.env.example`; README section and these notes.

## 8. Limitations

- Offline intent rules are English-first with some Hinglish; unusual phrasing falls to a helpful "I'm not sure"
  answer with examples. The LLM mode covers free-form questions when the organisation allows it.
- The planner cannot verify that an application code belongs to the user's organisation or job; the backend checks
  every argument and permission again when it runs a tool and when an action is confirmed.
- Salary parsing assumes INR; city recognition covers major Indian cities and a few global ones (others are taken
  verbatim from "location: X" or as the answer to the location question).
- Relative dates use `context.today` when sent, otherwise the server date (UTC in the container).

## 9. Backend tool contract (integration fix, 17 Nov 2026)

The backend sends its registry's names and `ArgSpec.describe()` schemas (AS-BE-NOTES section 5); the planner had been
built against its own reference names, and `interviewer`, `workSampleId`, `dueAt`, `upcomingOnly` (AS-BE-NOTES 6.12)
had no mapping. Now:

- **Schema parsing** (`catalogue.parse_arg_spec`) understands every backend type label: application code(s), ISO-8601
  date-time, string-or-list, requirements list, `one of A|B` followed by a hint, and integer ranges (`5..480`, `1..`).
- **Conversion** (`coerce`): application codes are emitted as `"A-3014"` (from a code or a numeric id), code lists
  deduplicated and capped, date-times checked as ISO dates / date-times, integers range-checked (an out-of-range
  optional value is left out, e.g. a 600-minute interview), string lists from a string, requirement objects reduced to
  the backend's seven keys. Enums map exact, then synonyms (`INTERVIEW` -> `MOVE_TO_INTERVIEW`, `HOLD` -> `ON_HOLD`,
  help keys such as `evidence_ladder` -> `EVIDENCE_TIERS`), then a unique `_<word>` suffix, then a shared stem.
- **Slots -> names** (`_ALIASES`, `Catalogue.arg_for`): new slots `upcomingOnly` ("upcoming", "next", "tomorrow"),
  `dueAt` ("in 5 days", "within a week", "due by 30 Nov" -> ISO date, never in the past; `days` still fills
  `dueInDays`-style names), `workSampleId` ("work sample #7", "WS-7"), `interviewer` ("with Priya Sharma",
  "interviewer: Rahul"; never `[name]`/`[email]` placeholders, codes, weekdays or "me" — the backend defaults to the
  user) and `location` ("location: Room 4B", "on Zoom", "in conference room 2"). `applicationCode` <-> `applicationId`
  and the list forms convert both ways.
- **Last line of defence**: `OfflinePlanner.tool_call`/`propose` refuse a tool absent from the request's catalogue (or
  of the wrong kind) with the "isn't available" answer and pass every argument set through `normalise_args`;
  `validate_step` does the same for the model, whose prompt now names the exact-argument rule and value formats.
  Unknown model arguments are dropped instead of failing the step (a test that expected a rejection was changed).
- Time zone: `scheduledAt` is still the user's local time without an offset (`2026-11-20T15:00`); the backend reads
  an offset-less time as UTC. The request carries no time zone; adding one is a backend + planner follow-up.

Contract test (`tests/test_backend_contract.py`, helper `tests/backend_contract.py`):

- `parse_java_tools()` reads `backend/src/main/java/com/recruitpro/assistant/tool/**` (tool name, description,
  capability, read/write, every `ArgSpec` with enum values resolved from the Java enums, `HelpTopics` and constants).
  `test_fixture_matches_java_source` fails when the Java source and `tests/fixtures/backend_tools.json` differ (skipped
  only when the backend tree is absent); its docstring has the one-line re-generation command.
- `validate()` is a strict port of `ToolArgs.validate` (unknown keys, canonical values, REJECT needs a reason).
- 36 scenarios covering every intent that calls a tool or proposes a change (multi-turn: job lookup via `list_jobs`,
  asked reasons/times/applicants, four job-posting conversations) run through a simulated backend loop (6 steps,
  draft kept/cleared like the orchestrator) for every role's catalogue; every one of the 14 tools the offline planner
  uses is exercised (`explain` is answered from built-in help offline and covered on the LLM path), plus all 204
  intent-evaluation utterances, plus 15 model replies written with synonyms (one per tool except `create_job`).

