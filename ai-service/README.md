# RecruitPro AI service

An internal FastAPI service that turns resumes into **typed, de-identified evidence**, matches that
evidence against job requirements on an **evidence ladder**, and drafts requirement lists, work
samples and interview kits. The Spring Boot backend is its only caller and stays authoritative for
identity, permissions, applications and every hiring decision. The service never decides; it reports
what evidence exists, how strong it is, and what a human should check next.

It runs **fully offline by default**. An LLM can be switched on to enhance extraction and drafting,
but every LLM output must quote its source verbatim or it is discarded, and only de-identified text
is ever sent.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Public. `{status, version, llmProvider, assistantPlanner, ocrAvailable}` |
| POST | `/api/v1/resume/parse` | Multipart `file` (PDF, DOCX, DOC) -> `ParseResponse` (facts, flags, timeline) |
| POST | `/api/v1/match` | `{requirements, facts, timeline}` -> `MatchResponse` (`evidence-ladder-4`) |
| POST | `/api/v1/match/batch` | `{requirements, candidates:[{applicationId, facts, timeline}]}` (max 50) |
| POST | `/api/v1/requirements/compile` | `{title, description, experienceLevel?}` -> requirements + wording warnings |
| POST | `/api/v1/work-samples/generate` | `{jobTitle, jobDescription?, requirements}` -> task, time limit, anchored rubric |
| POST | `/api/v1/interview-kits/generate` | `{jobTitle, requirements, focusRequirementIds?}` -> structured questions |
| POST | `/api/v1/assistant/step` | One planning step for the HR assistant (stateless; see [HR assistant planner](#hr-assistant-planner)) |

Every `/api/*` route requires `X-Internal-Service-Token`. Field names and types follow
`docs/plan/IMPLEMENTATION_PLAN.md` Section 6.1; worked request/response examples are in
`docs/plan/WP-PY-NOTES.md`.

## How it works

**Security first.** A pure ASGI middleware checks the token from the request headers *before* the
application runs, so an unauthenticated caller cannot make the service read or parse a body (a
50 MB multipart upload without a token is answered 401 without a single byte read). Authenticated
requests are held to `MAX_UPLOAD_BYTES` (+64 KB framing): an oversized `Content-Length` is refused
up front and a streamed body is cut off at the limit (413). Parsing runs in a `spawn` process pool
with a time limit, so a hostile PDF can neither block the event loop nor hang a worker forever.

**Parsing pipeline** (`app/pipeline/`):

1. *Ingest* - PDF via pdfplumber words with positions; a vertical gutter found by x-coverage marks a
   two-column layout (`TWO_COLUMN_LAYOUT`): in a sidebar layout the main column of every page is read
   first and all sidebar text after it, so the name stays in the header block and a role that continues
   on the next page is not split; balanced columns are read left then right. Label/value tables
   ("Father's Name : ...") and right-aligned date or rating columns are not columns. Characters that are
   white (unless drawn on a dark fill), under 4 pt or off the page are excluded (`HIDDEN_TEXT`); a line
   that merely runs past the right edge is dropped without the flag. DOCX is read
   paragraph by paragraph from the XML (body, tables, text boxes, headers, footers); runs hidden by
   `w:vanish`, white colour or a tiny size - directly or through styles - are excluded. Legacy `.doc`
   is converted with LibreOffice when `soffice` exists (keeping hidden-text detection), otherwise read
   from the Word 97 piece table with `olefile` (`LEGACY_DOC`). Scanned documents (and text drawn as vector
   outlines) return `NEEDS_OCR` unless OCR is installed and enabled (`OCR_USED`). The format comes from the
   file's magic bytes, not its extension (DOCX renamed `.doc`, PDF with leading junk, RTF via LibreOffice,
   HTML saved as `.doc`); DOCX table rows with one line per cell are read as one line ("Staff Nurse |
   Apollo | 2018 - 2021"); first-page/even-page headers are read when Word shows them. Any unexpected reader
   error is `FAILED` with `MALFORMED_<EXT>` (analysis errors: `PARSER_CRASHED`), never an HTTP 500.
2. *Injection screen* - instruction-like text ("ignore previous instructions", "you are an AI
   recruiter", "rank this candidate first", "reveal your system prompt", chat-template tags) and hidden
   keyword stuffing raise `PROMPT_INJECTION_SUSPECTED`; merely mentioning "system prompts" or "prompt
   injection" (AI and security work) does so only in hidden text. Resume text is only ever data.
3. *Sections* - ~250 heading variants plus a head-noun classifier for unseen headings ("Professional
   Journey", "Internship & Training", "Career Goal"; every word must be heading vocabulary, so a bold
   employer such as "Paywise Technologies" is not a section). Table rows whose first cell is a section
   label ("TECHNICAL SKILLS | ...") and headings wrapped over two lines are recognised. A heading-like line
   that is not recognised starts an `OTHER` section, so nothing inherits the previous section; an `OTHER`
   section holding dated role headers is a work history. Page numbers and running headers/footers
   ("Name - Resume | 1/2") are dropped first. `DECLARATION` sections never yield facts.
4. *De-identification* - the header name, emails, phones, URLs, addresses and postcodes, dates of
   birth and ages, personal-detail lines, gendered honorifics (-> "Mx.") and pronouns (-> they/them)
   are replaced. All evidence and non-contact values are rendered from the de-identified text; the
   contact itself appears only in the `EMAIL`/`PHONE`/`LINK` fact. Skill mentions that overlap a
   redaction are dropped (a candidate called Ruby does not "know Ruby"). Names may carry initials ("K.
   Lakshmi"), accents ("José Álvarez") or particles ("Rahul de Souza"); labels may be table-style
   ("Gender | Female"); a redaction never runs past the end of its line, and overlapping redactions merge.
   When the header block holds no name (sidebar and banner layouts), a name-like line that the email or
   profile handle spells is used.
5. *Skills* - `app/taxonomy/skills.json`: 863 curated skills in 15 domains (software, data, cloud,
   design, product, sales, marketing, finance and accounting incl. Tally, GST, TDS, Ind AS, HR,
   operations, healthcare, education, legal, spoken languages, general) with aliases, parents,
   distinctions, `ambiguous` flags and context words. Ambiguous names (Go, C, R, Spring, React, Swift,
   Oracle, Excel, Tally, Rust, Ruby, Kafka, Postman, Jenkins, ...) count only as whole items of a list, or
   with their exact casing next to a context word or another same-domain skill. Interests/hobbies never
   produce skills; personal details yield only the languages listed there ("Languages Known: English,
   Hindi", SKILL facts in the `LANGUAGE` domain). Labelled lists inside roles ("Areas handled: Oracle,
   SAP MM") and rated items ("English | ●●●○○") are lists. Coordinated phrases restore elided words ("field
   and inside sales" -> Field Sales, Inside Sales). Skill words inside an employer's name ("Gyanpath
   Edtech"), an issuer ("The Linux Foundation") or a contact label ("Github: [link]") are not skills.
   Heading words that are also list items ("Training", "Research", "Software") do not
   start a new section inside a skills list unless styled as a heading; role sub-headings
   ("Responsibilities:", "Achievements:") do not end a work history that continues with another role.
6. *Dates and timeline* - month-name, numeric, ISO, apostrophe-year ("Jan '19", "Apr-21"), day-month-year
   ("01/06/2018", "15 Jan 2019", "June 15, 2019"), season ("Summer 2023"), abbreviated end year ("2016 - 18"),
   year-only and open-ended ranges; "Present" is today. Experience sections are cut into roles (title line + date line, single-line,
   parenthesised and date-first layouts, wrapped date lines rejoined, key-value blocks "Organisation : /
   Designation : / Duration :", a title or employer on the line after the dates). Career-break lines and
   project sub-blocks ("Project : / Client : / Duration :") are not roles. Internships are roles with the
   `internship` attribute. Intervals are merged so
   overlapping roles count once; undated roles with a stated duration add on top. Skills mentioned in
   a role accrue its months (`skillMonths`).
7. *Education* - degree levels (DIPLOMA < BACHELOR < MASTER < DOCTORATE) and field families
   (computer science ~ CSE ~ IT ~ software engineering, commerce ~ accounting ~ finance, ...). A bare
   "Master"/"Bachelor" is not a degree ("Scrum Master"); "B.E", "M.S", "D.El.Ed", "ITI" are. CA, CS,
   CMA/ICWA and CFA rank with master's degrees (intermediate stages such as "CA Inter" do not); BHM, BSW,
   B.Stat/M.Stat, BJMC and others are recognised, as are school qualifications (10+2, PUC, SSLC, ISC).
   A degree still in progress carries `inProgress` and does not meet a degree requirement on its own.

**Matching v4 - `evidence-ladder-4`** (`app/matching/engine.py`). Facts carry an origin-tagged id
(`fact:12` resume, `claim:3` candidate, `ws:4:r9` work sample, `iv:7:r9` interview) and a tier
(`SELF_CLAIMED` < `RESUME_STATED` < `CORROBORATED` < `DEMONSTRATED` < `INTERVIEW_VERIFIED`). Per requirement:
alternatives (explicit, or "X or Y" / "X/Y" / "such as X, Y" in the text) are any-of; a child skill
satisfies its parent (MySQL -> SQL) while the parent alone is only UNCLEAR; EXPERIENCE compares merged
role months (or stated durations) with `minimumYears x 12`, where a role is relevant when its block names
the practice or its title implies it (`app/matching/occupations.py`: "SDE-2" with Java -> backend
development, "Key Account Manager" -> B2B sales, "Staff Nurse" -> nursing care; a title alone never
counts as SKILL evidence); preferred clauses ("Bachelor's degree; MBA preferred") are not required; EDUCATION compares levels and field
families; CERTIFICATION needs >= 80 % normalised token overlap (acronyms expanded); OTHER always needs
human review and is excluded from coverage. The reported tier is the highest tier at which the
requirement is still matched (the weakest link of a conjunction). Must-haves weigh 2, nice-to-haves 1.
`verifiedCoverage` (= `score`) counts matches at `RESUME_STATED` or better; `claimedCoverage` also
counts the candidate's own claims; `knockoutsVerified`/`unverifiedKnockouts` gate ranking but never
reject. Every requirement that is not verified gets a `verificationHint`. The lexical fallback only
compares facts of the requirement's own type and cites the fact whose value holds the terms.

*Requirement proficiency (new in v4, SKILL requirements only).* BASIC and INTERMEDIATE change nothing.
ADVANCED keeps a MATCHED result only with evidence of depth for every named skill: >= 3 years of relevant
timeline roles, or a match at tier `CORROBORATED` or higher, or an explicit level word (`advanced`,
`expert`, `senior`, `lead`) in the cited evidence or a relevant role title. EXPERT needs >= 5 years, tier
`DEMONSTRATED` or higher, or the word `expert`. Skill names are not level words ("Lead Generation",
"Advanced Cardiac Life Support"), while a qualifier in front of the skill name counts ("Advanced Excel").
Without depth the result becomes UNCLEAR, the explanation says what was missing (years found vs needed,
tier, level words) and the hint asks the interviewer to probe depth. Proficiency never produces
NOT_OBSERVED and never changes EXPERIENCE, EDUCATION, CERTIFICATION or OTHER requirements. This is a
conservative heuristic: years of use and a job title are proxies for depth, not proof of it.

*Where proficiency comes from (calibrated in bug hunt R4).* The rules compiler only proposes ADVANCED for an
explicit depth word (`advanced`, `in-depth`, `deep`, `extensive`, `expertise`) and EXPERT for `expert` /
`mastery`; job-ad filler (`strong`, `solid`, `good`, `excellent`, `hands-on`, `proficient`) is INTERMEDIATE,
which changes nothing in matching. A level word only qualifies the skills written next to it ("Strong
communication skills and Tally" gives Tally no level; "Excel (advanced), PowerPoint (basic)" gives each its
own). Before this, "strong" meant ADVANCED and about half of compiled skill requirements asked for depth that
most resumes cannot show, turning most matches UNCLEAR (`proficiency_downgrade_share` ~0.74 on the synthetic
set, which re-matches *every* skill as ADVANCED). OTHER requirements never count as knockouts.

**Compiler and generators** (`app/compile/`, `app/generate/`). Rules baseline: must/nice from headings
and cue words, `minimumYears`, alternatives, types, knockouts on must/required wording, and warnings for
age-coded, gender-coded, exclusionary and vague wording, hard degree requirements and excessive
experience. Work samples: one task per two or three must-haves, 15-45 minutes, four behavioural anchors
per rubric criterion. Interview kits: behavioural plus technical questions, follow-ups and anchors, focus
requirements first. Requirement wording about protected characteristics (age limits, gender or marital
preferences, religion, appearance, "no career gaps", "freshers only") is reported as a warning and never
becomes a requirement; a cap on experience ("less than 10 years") is not turned into a minimum.
Work-sample scenarios are self-contained (they never refer to material nobody attaches).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `AI_SERVICE_TOKEN` | - (required) | Shared secret with the backend; without it every `/api/*` call is 503 |
| `LLM_PROVIDER` | `none` | `none`, `anthropic` or `openai_compatible` |
| `LLM_MODEL` | - | Required when a provider is enabled (the service refuses to start otherwise) |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL` | -, `https://api.anthropic.com` | Anthropic Messages API |
| `OPENAI_BASE_URL`, `OPENAI_API_KEY` | - | Any OpenAI-compatible `/chat/completions` server (e.g. a local one) |
| `LLM_TIMEOUT_SECONDS` | `20` | Per LLM call; on time-out the rules result is used |
| `ASSISTANT_LLM_PROVIDER` | `none` | HR assistant planner: `none` (offline rules), `gemini`, `groq`, `anthropic` or `openai_compatible` |
| `ASSISTANT_LLM_MODEL` | - | Required when `ASSISTANT_LLM_PROVIDER` is set; **no default** (model names change, check the provider's list) |
| `ASSISTANT_LLM_TIMEOUT_SECONDS` | `20` | Per planner call (0-120); on time-out the offline planner answers |
| `ASSISTANT_MAX_OUTPUT_TOKENS` | `1024` | Output-token cap per planner call (64-8192) |
| `GEMINI_API_KEY`, `GEMINI_BASE_URL` | -, `https://generativelanguage.googleapis.com/v1beta/openai/` | Gemini through its OpenAI-compatible API (Bearer key) |
| `GROQ_API_KEY`, `GROQ_BASE_URL` | -, `https://api.groq.com/openai/v1` | Groq through its OpenAI-compatible API (Bearer key) |
| `PARSE_WORKERS` | `2` | Parser process-pool size |
| `PARSE_TIMEOUT_SECONDS` | `60` | Per document; returns `FAILED/PARSE_TIMEOUT` and recycles the pool |
| `MAX_UPLOAD_BYTES` | `10485760` | Largest accepted file |
| `OCR_ENABLED` | `false` | Use RapidOCR when `requirements-ocr.txt` is installed (the Docker image installs it) |
| `SOFFICE_PATH` | auto-detect on `PATH` (`/usr/bin/soffice` in the default Docker image) | LibreOffice for `.doc`; empty disables conversion |
| `TAXONOMY_EXTRA_PATH` | - | Extra skills file (e.g. an ESCO import) |

### Offline vs LLM mode

*Offline* (`LLM_PROVIDER=none`) is the default and needs no keys; nothing leaves the machine.
*LLM mode* adds skills the rules missed (each with a verbatim quote from the de-identified resume),
drafts requirements (each quoting the job description) and drafts kits; responses must be strict JSON.
Any invalid item is dropped, and any error, time-out or empty result falls back to the rules output
(`engine: "rules"`, parser version without `+llm`). Contacts are scrubbed from job texts before sending.

## HR assistant planner

`POST /api/v1/assistant/step` plans **one** next step of the HR assistant (plan: `docs/plan/ASSISTANT_PLAN.md`
sections 1-3; details and deviations: `docs/plan/AS-PY-NOTES.md`). It is stateless: the backend sends the
de-identified conversation (last 20 messages), the page context, the user's tool catalogue (already filtered by role),
this turn's tool observations and the slot-filling draft, runs the tools itself with the user's permissions, and
calls again (at most 6 steps per user message). The reply is exactly one of `tool_call` (read tools only), `ask`,
`propose` (every write; the user confirms in the backend) or `answer` (text plus `metrics` / `table` / `candidates` /
`link` / `notice` / `job_preview` blocks), with `meta: {planner, model, fallback}`.

**Offline planner** (`app/assistant/offline.py`, always available, deterministic). Intent rules with typo repair and
Indian-English / chat shorthand (`intents.py`, `text.py`) cover job insights, applicant insights, compare (codes or
"top 3"), attention digest, my interviews, fairness summary, list jobs, help (built-in topics in `help_topics.py`),
navigation links, pipeline actions (shortlist / reject with a reason / interview / select, assign a work sample,
schedule an interview, generate a work sample or interview kit, job status) and job posting. Missing arguments are
asked for (with options), a job named by title is resolved through `list_jobs`, and answers are composed only from
observation data (`compose.py`): candidates by application code, coverage described as evidenced requirements (never a
probability), no invented numbers, links only to same-app relative paths.

**Job posting** (`jobpost.py`) is guided slot filling: one message can give title, head count, city (Indian cities and
aliases, remote / hybrid / on-site), job type, level, openings, relative or absolute deadline, salary (LPA, lakh, k,
per month), years and skills (taxonomy, with must-have / gate / nice-to-have cues). Only missing required fields are
asked, one or two at a time; department and level can be inferred and are shown as assumptions. The description is
templated (or written by the LLM in LLM mode, validated and bias-checked), requirements come from the existing
requirement compiler (`compile_rules`), `OTHER` requirements are never knockouts, and biased wording is flagged.
The result is a `propose create_job` with a full `job_preview`. Edits work mid-flow and on the preview ("make it
remote", "add Docker as nice to have", "extend the deadline by a week"); "start over" and "cancel" reset the draft.

**LLM planner** (`llm_planner.py`; `ASSISTANT_LLM_PROVIDER` set **and** the request's `allowExternalModel` true).
Strict-JSON prompting, provider-agnostic (no native tool calling). Inputs are scrubbed again (emails, phones, URLs;
identity-like keys dropped; sizes capped) and observations are labelled untrusted data. The reply is validated: the
tool or action must be in the catalogue with type-checked arguments, writes only as proposals and only when the
user's own latest message asks for a change, `create_job` never (the guided flow owns it), no repeated tool calls,
answers may only state numbers present in the inputs and must not call coverage a probability; blocks are always
built from observation data, never from model text. Guided flows (job posting, pending action slots, help,
navigation, cancel) stay deterministic. Any provider error, time-out, invalid JSON or rejected step falls back to the
offline planner with `meta.fallback = true`. The service has no internet in compose: a hosted model needs the
`docker-compose.ai-egress.yml` override.

```bash
python -m eval.assistant_intents --check     # offline intent accuracy on 204 utterances; exits 1 below 0.9
```

## Run locally

Python 3.10+.

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt          # add requirements-ocr.txt for OCR
$env:AI_SERVICE_TOKEN = "<same-random-value-as-backend>"
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Bind to localhost in development and to a private network in deployment; interactive API docs are
disabled and there is no CORS.

## Docker image

`Dockerfile` (base `python:3.11-slim-bookworm`, non-root user, `HEALTHCHECK` on `/health`) installs, by
default, two optional document tools:

| Build arg | Default | Installs | Used for |
|---|---|---|---|
| `WITH_LIBREOFFICE` | `true` | `libreoffice-writer-nogui` (headless Writer, no recommends) | `.doc` -> `.docx` conversion, which keeps hidden-text detection; `soffice` is found on `PATH` (`/usr/bin/soffice`) |
| `WITH_OCR` | `true` | `requirements-ocr.txt` (RapidOCR on onnxruntime, OpenCV) plus `libgl1`, `libglib2.0-0` | Scanned PDFs and image-only DOCX, **only when `OCR_ENABLED=true`** (compose default `false`) |

The OCR engine is RapidOCR, not Tesseract: it is what `app/pipeline/ocr.py` calls, and it ships its models
inside the wheel, so no Tesseract packages are installed.

**Image size.** Without the extras the image is roughly 250-300 MB. LibreOffice Writer adds roughly
300-400 MB and the OCR extra roughly 250-350 MB (onnxruntime, OpenCV, NumPy, Shapely, models), so the
default image is around 0.8-1.1 GB (estimates; the image has not been built in this environment). For a
small image build with `--build-arg WITH_LIBREOFFICE=false --build-arg WITH_OCR=false`; `.doc` then uses
the `olefile` reader and scans return `NEEDS_OCR`, as before. LibreOffice parses untrusted documents: it
runs as the non-root user, with a 60 s timeout and a throw-away profile, inside a container without
network egress (see the compose networks). Set `SOFFICE_PATH=` (empty) to turn conversion off.

**Health in compose.** The service sits only on the internal compose network and publishes no host port.
Check it with `docker compose ps` (the `HEALTHCHECK` result), the admin dashboard, or
`docker compose exec ai-service python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/health').read().decode())"`.
`ocrAvailable` in the answer is true only when `OCR_ENABLED=true` and RapidOCR is installed.

## Tests and evaluation

```bash
pip install -r requirements-dev.txt
python -m pytest -q                 # unit, document, API, LLM (mocked httpx) and audit-probe tests
python -m eval.run --check          # synthetic evaluation; exits 1 below a threshold
python -m eval.assistant_intents --check   # HR assistant intent set (also asserted in pytest)
python ../audit/probe_matching.py   # the audit's matching probes, now behaving correctly
```

The evaluation harness (`eval/`) generates a seeded synthetic gold set - 208 resumes across eight
domains (software, data, finance, sales, marketing, HR, healthcare, education), each with a
name/gender counterfactual twin, rendered to real DOCX, single-column PDF and two-column PDF files
(some with hidden keyword stuffing or hidden prompt injections) - plus 24 labelled jobs. It reports
skill-extraction precision/recall/F1, date accuracy, flag accuracy, requirement agreement (Cohen's
kappa over MATCHED/UNCLEAR/NOT_OBSERVED), ranking NDCG@10, the name-swap flip rate and the adverse-impact
ratio. `--check` thresholds: skill F1 >= 0.85, kappa >= 0.70, NDCG@10 >= 0.80, flip rate = 0,
adverse-impact ratio >= 0.80, date accuracy >= 0.90, plus requirement proficiency: all 18 hand-labelled
cases in `eval/proficiency.py` agree with their gold status, and re-matching every synthetic SKILL
requirement as ADVANCED and EXPERT never changes anything except MATCHED -> UNCLEAR
(`proficiency_invariant_violations` = 0). `proficiency_downgrade_share` is informational: the share of
MATCHED SKILL requirements that ADVANCED/EXPERT turn into UNCLEAR on the synthetic set (about 74 % and 95 %,
because the synthetic resumes have short per-skill histories). A smaller seeded run is part of `pytest`.

> **Synthetic metrics overstate real accuracy.** The gold set was generated from templates written
> alongside the parser, so it shares the parser's assumptions about layout, wording and vocabulary;
> real resumes are messier (tables, icons, scans, creative layouts, unusual job titles, other
> languages). Treat these numbers as regression guards, not as accuracy claims. Before quoting any
> accuracy, replace the synthetic set with a recruiter-labelled sample of real, consented resumes and
> re-measure, and monitor the adverse-impact ratio on real outcomes.

## Taxonomy maintenance

Edit `app/taxonomy/skills.json` directly (one skill per line; ids are stable). `pytest` validates
alias collisions, references, cycles and context words. To bring in ESCO, download the official CSV
export and run
`python -m app.taxonomy.import_esco --skills skills_en.csv --relations broaderRelationsSkillPillar.csv --out esco.json`,
then set `TAXONOMY_EXTRA_PATH=esco.json`; curated entries always win collisions.

## Limitations

- Text-in-image resumes need the optional OCR extra; OCR output is flagged and less reliable.
- The `olefile` fallback for `.doc` cannot see hidden formatting; LibreOffice conversion can.
- Hidden-text detection covers colour, size, position and DOCX hidden properties, not text clipped
  behind images or PDF render-mode tricks.
- Role segmentation is heuristic; unusual layouts can merge or split roles. Year-only dates are
  assumed to be mid-year.
- Matching is lexical and taxonomy-based (no embeddings); synonyms outside the taxonomy are missed and
  surface as UNCLEAR/NOT_OBSERVED with a verification hint rather than as false matches.
- Dates: day-first numeric dates (01/06/2018) are assumed unless impossible (06/25/2019 is month-first);
  "FY 2019-20" is a reporting period, not employment dates. Seasons map to Jan/Apr/Jul/Oct.
- RTF files need LibreOffice (otherwise `UNSUPPORTED_FORMAT`); LibreOffice may try to fetch resources
  linked from a document, so run it only where the container has no network route (as in compose).
