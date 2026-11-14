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
| GET | `/health` | Public. `{status, version, llmProvider, ocrAvailable}` |
| POST | `/api/v1/resume/parse` | Multipart `file` (PDF, DOCX, DOC) -> `ParseResponse` (facts, flags, timeline) |
| POST | `/api/v1/match` | `{requirements, facts, timeline}` -> `MatchResponse` (`evidence-ladder-3`) |
| POST | `/api/v1/match/batch` | `{requirements, candidates:[{applicationId, facts, timeline}]}` (max 50) |
| POST | `/api/v1/requirements/compile` | `{title, description, experienceLevel?}` -> requirements + wording warnings |
| POST | `/api/v1/work-samples/generate` | `{jobTitle, jobDescription?, requirements}` -> task, time limit, anchored rubric |
| POST | `/api/v1/interview-kits/generate` | `{jobTitle, requirements, focusRequirementIds?}` -> structured questions |

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
   two-column layout, read left column then right (`TWO_COLUMN_LAYOUT`). Characters that are white
   (unless drawn on a dark fill), under 4 pt or off the page are excluded (`HIDDEN_TEXT`). DOCX is read
   paragraph by paragraph from the XML (body, tables, text boxes, headers, footers); runs hidden by
   `w:vanish`, white colour or a tiny size - directly or through styles - are excluded. Legacy `.doc`
   is converted with LibreOffice when `soffice` exists (keeping hidden-text detection), otherwise read
   from the Word 97 piece table with `olefile` (`LEGACY_DOC`). Scanned documents return `NEEDS_OCR`
   unless OCR is installed and enabled (`OCR_USED`).
2. *Injection screen* - instruction-like text ("ignore previous instructions", "you are an AI
   recruiter", "rank this candidate first", "system prompt") and hidden keyword stuffing raise
   `PROMPT_INJECTION_SUSPECTED`. Resume text is only ever data.
3. *Sections* - ~200 heading variants; a heading-like line that is not in the lexicon starts an
   `OTHER` section, so nothing inherits the previous section (hobbies never become certifications).
4. *De-identification* - the header name, emails, phones, URLs, addresses and postcodes, dates of
   birth and ages, personal-detail lines, gendered honorifics (-> "Mx.") and pronouns (-> they/them)
   are replaced. All evidence and non-contact values are rendered from the de-identified text; the
   contact itself appears only in the `EMAIL`/`PHONE`/`LINK` fact. Skill mentions that overlap a
   redaction are dropped (a candidate called Ruby does not "know Ruby").
5. *Skills* - `app/taxonomy/skills.json`: 847 curated skills in 15 domains (software, data, cloud,
   design, product, sales, marketing, finance and accounting incl. Tally, GST, TDS, Ind AS, HR,
   operations, healthcare, education, legal, spoken languages, general) with aliases, parents,
   distinctions, `ambiguous` flags and context words. Ambiguous names (Go, C, R, Spring, React, Swift,
   Oracle, Excel, Tally, ...) count only as whole items of a list, or with their exact casing next to
   a context word or another same-domain skill. Interests/hobbies never produce skills.
6. *Dates and timeline* - month-name, numeric, ISO, apostrophe-year, year-only and open-ended ranges;
   "Present" is today. Experience sections are cut into roles (title line + date line, single-line,
   parenthesised and date-first layouts, wrapped date lines rejoined). Intervals are merged so
   overlapping roles count once; undated roles with a stated duration add on top. Skills mentioned in
   a role accrue its months (`skillMonths`).
7. *Education* - degree levels (DIPLOMA < BACHELOR < MASTER < DOCTORATE) and field families
   (computer science ~ CSE ~ IT ~ software engineering, commerce ~ accounting ~ finance, ...).

**Matching v3 - `evidence-ladder-3`** (`app/matching/engine.py`). Facts carry an origin-tagged id
(`fact:12` resume, `claim:3` candidate, `ws:4:r9` work sample, `iv:7:r9` interview) and a tier
(`SELF_CLAIMED` < `RESUME_STATED` < `CORROBORATED` < `DEMONSTRATED` < `INTERVIEW_VERIFIED`). Per requirement:
alternatives (explicit, or "X or Y" / "X/Y" / "such as X, Y" in the text) are any-of; a child skill
satisfies its parent (MySQL -> SQL) while the parent alone is only UNCLEAR; EXPERIENCE compares merged
role months (or stated durations) with `minimumYears x 12`; EDUCATION compares levels and field
families; CERTIFICATION needs >= 80 % normalised token overlap (acronyms expanded); OTHER always needs
human review and is excluded from coverage. The reported tier is the highest tier at which the
requirement is still matched (the weakest link of a conjunction). Must-haves weigh 2, nice-to-haves 1.
`verifiedCoverage` (= `score`) counts matches at `RESUME_STATED` or better; `claimedCoverage` also
counts the candidate's own claims; `knockoutsVerified`/`unverifiedKnockouts` gate ranking but never
reject. Every requirement that is not verified gets a `verificationHint`. The lexical fallback only
compares facts of the requirement's own type and cites the fact whose value holds the terms.

**Compiler and generators** (`app/compile/`, `app/generate/`). Rules baseline: must/nice from headings
and cue words, `minimumYears`, alternatives, types, knockouts on must/required wording, and warnings for
age-coded, gender-coded, exclusionary and vague wording, hard degree requirements and excessive
experience. Work samples: one task per two or three must-haves, 15-45 minutes, four behavioural anchors
per rubric criterion. Interview kits: behavioural plus technical questions, follow-ups and anchors, focus
requirements first.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `AI_SERVICE_TOKEN` | - (required) | Shared secret with the backend; without it every `/api/*` call is 503 |
| `LLM_PROVIDER` | `none` | `none`, `anthropic` or `openai_compatible` |
| `LLM_MODEL` | - | Required when a provider is enabled (the service refuses to start otherwise) |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL` | -, `https://api.anthropic.com` | Anthropic Messages API |
| `OPENAI_BASE_URL`, `OPENAI_API_KEY` | - | Any OpenAI-compatible `/chat/completions` server (e.g. a local one) |
| `LLM_TIMEOUT_SECONDS` | `20` | Per LLM call; on time-out the rules result is used |
| `PARSE_WORKERS` | `2` | Parser process-pool size |
| `PARSE_TIMEOUT_SECONDS` | `60` | Per document; returns `FAILED/PARSE_TIMEOUT` and recycles the pool |
| `MAX_UPLOAD_BYTES` | `10485760` | Largest accepted file |
| `OCR_ENABLED` | `false` | Use rapidocr when `requirements-ocr.txt` is installed |
| `SOFFICE_PATH` | auto-detect | LibreOffice for `.doc`; empty disables conversion |
| `TAXONOMY_EXTRA_PATH` | - | Extra skills file (e.g. an ESCO import) |

### Offline vs LLM mode

*Offline* (`LLM_PROVIDER=none`) is the default and needs no keys; nothing leaves the machine.
*LLM mode* adds skills the rules missed (each with a verbatim quote from the de-identified resume),
drafts requirements (each quoting the job description) and drafts kits; responses must be strict JSON.
Any invalid item is dropped, and any error, time-out or empty result falls back to the rules output
(`engine: "rules"`, parser version without `+llm`). Contacts are scrubbed from job texts before sending.

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

## Tests and evaluation

```bash
pip install -r requirements-dev.txt
python -m pytest -q                 # unit, document, API, LLM (mocked httpx) and audit-probe tests
python -m eval.run --check          # synthetic evaluation; exits 1 below a threshold
python ../audit/probe_matching.py   # the audit's matching probes, now behaving correctly
```

The evaluation harness (`eval/`) generates a seeded synthetic gold set - 208 resumes across eight
domains (software, data, finance, sales, marketing, HR, healthcare, education), each with a
name/gender counterfactual twin, rendered to real DOCX, single-column PDF and two-column PDF files
(some with hidden keyword stuffing or hidden prompt injections) - plus 24 labelled jobs. It reports
skill-extraction precision/recall/F1, date accuracy, flag accuracy, requirement agreement (Cohen's
kappa over MATCHED/UNCLEAR/NOT_OBSERVED), ranking NDCG@10, the name-swap flip rate and the adverse-impact
ratio. `--check` thresholds: skill F1 >= 0.85, kappa >= 0.70, NDCG@10 >= 0.80, flip rate = 0,
adverse-impact ratio >= 0.80, date accuracy >= 0.90. A smaller seeded run is part of `pytest`.

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
