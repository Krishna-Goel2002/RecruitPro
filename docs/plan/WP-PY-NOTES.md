# WP-PY notes - AI service v2

Status: **done, gate G-PY green** (2026-11-08). Scope: everything under `ai-service/` plus
`audit/probe_matching.py`. Plan reference: `IMPLEMENTATION_PLAN.md` Sections 1 (D5, D6, D9, D10), 2, 5.3,
5.6, 6 and 9.

## Verification

| Check | Result |
|---|---|
| `cd ai-service && python3 -B -m pytest -q -p no:cacheprovider` | **177 passed** (~7 s) |
| `python3 -B -m eval.run --check` | **CHECK PASSED** (metrics below) |
| `python3 -B ../audit/probe_matching.py` | every audit defect fixed (also asserted in `tests/test_audit_probes.py`) |
| Fresh virtualenv with only `requirements-dev.txt` | installs, 177 passed, eval check passed |
| `requirements-ocr.txt` | resolves (`pip install --dry-run`); not installed here, OCR tested with a fake engine |

## Module layout

```
ai-service/
  app/main.py                    FastAPI app, lifespan (fail fast on LLM misconfig, pool shutdown), /health
  app/core/config.py             Settings from env (read per call), VERSION, PARSER_VERSION, validate_settings
  app/core/auth.py               pure ASGI token middleware on /api/* (before body), body-size limit (413)
  app/core/workers.py            spawn ProcessPoolExecutor for parsing; timeout/crash recycle the pool
  app/api/v1/{resume,matching,authoring}.py   thin routers
  app/models/{common,resume,matching,authoring}.py   pydantic v2 contracts (camelCase per plan 6.1)
  app/pipeline/text.py           RawLine/Line/Flag, normalisation, stemming, content tokens
  app/pipeline/ingest.py         PDF (layout, two-column, hidden chars), DOCX (XML runs, styles), DOC (LibreOffice|olefile)
  app/pipeline/ocr.py            optional rapidocr wrapper
  app/pipeline/injection.py      prompt-injection / keyword-stuffing screen
  app/pipeline/sections.py       heading lexicon, OTHER sections, inline "Label: content"
  app/pipeline/deidentify.py     redaction spans, RedactedText (slice, offset mapping), contacts, scrub_contacts
  app/pipeline/dates.py          date ranges, durations, merged months
  app/pipeline/timeline.py       role segmentation, title/employer split, timeline + skillMonths
  app/pipeline/education.py      degree levels, field families, related fields
  app/pipeline/extract.py        orchestrator: analyse() / parse_resume(), fact building, LLM skill enhancement
  app/taxonomy/skills.json       847 curated skills, 15 domains
  app/taxonomy/taxonomy.py       loader, token n-gram mention finder, ambiguity rules, hierarchy, validation
  app/taxonomy/import_esco.py    ESCO CSV -> taxonomy extension (TAXONOMY_EXTRA_PATH)
  app/matching/engine.py         evidence-ladder-3 matcher + batch
  app/matching/certifications.py certification normalisation (acronym expansion)
  app/compile/requirements.py    JD -> requirements + warnings (rules, LLM)
  app/generate/work_samples.py   work sample + anchored rubric (rules, LLM)
  app/generate/interview_kits.py interview kit (rules, LLM)
  app/llm/provider.py            none | anthropic | openai_compatible over httpx; strict JSON; find_quote
  eval/{domains,generator,render,metrics,run}.py   synthetic gold set, DOCX/PDF rendering, metrics, CLI
  tests/                         177 tests (helpers.py builds PDFs, DOCX and OLE2 .doc files by hand)
```

Removed: `app/services/*`, `app/models/intelligence.py`, `app/api/v1/intelligence.py` and the old tests
(their cases were ported to the new contract in `tests/test_matching.py` and `tests/test_taxonomy.py`).

## Endpoint examples (captured from the running app)

`POST /api/v1/resume/parse` (multipart `file`) - a two-role accountant PDF; the name, email and phone
appear only in the contact facts:

```json
{"status": "COMPLETED", "errorCode": null, "parserVersion": "rule-parser-2",
 "facts": [
  {"type": "EXPERIENCE", "value": "Accountant, Kalinga Steel Ltd", "normalizedValue": "accountant, kalinga steel ltd",
   "evidence": "Accountant, Kalinga Steel Ltd · Jan 2019 - Mar 2021 · Filed GST returns and reconciled bank statements",
   "sourceSection": "experience", "sourceReference": "page:1", "spanStart": 96, "spanEnd": 194,
   "attributes": {"current": false, "title": "Accountant", "employer": "Kalinga Steel Ltd", "startDate": "2019-01",
                  "endDate": "2021-03", "months": 27}},
  {"type": "EDUCATION", "value": "B.Com, Utkal University, 2018", "...": "...",
   "attributes": {"degreeLevel": "BACHELOR", "field": "commerce"}},
  {"type": "SKILL", "value": "Tally", "normalizedValue": "tally", "evidence": "Tally, GST, MS Excel",
   "sourceSection": "skills", "sourceReference": "page:1", "spanStart": 64, "spanEnd": 69,
   "attributes": {"skillId": "tally", "domain": "FINANCE"}},
  {"type": "EMAIL", "value": "priya.sharma@example.test", "evidence": "priya.sharma@example.test", "sourceSection": "contact", "...": "..."},
  {"type": "PHONE", "value": "+91 98765 43210", "normalizedValue": "919876543210", "...": "..."}],
 "flags": [],
 "timeline": {"totalMonths": 95,
  "roles": [{"title": "Accountant", "employer": "Kalinga Steel Ltd", "startDate": "2019-01", "endDate": "2021-03",
             "current": false, "months": 27, "skills": ["GST"]},
            {"title": "Senior Accountant", "employer": "Greenfield Retail Pvt Ltd", "startDate": "2021-04", "endDate": null,
             "current": true, "months": 68, "skills": ["Financial Reporting", "Ind AS", "Tally"]}],
  "skillMonths": {"Financial Reporting": 68, "GST": 27, "Ind AS": 68, "Tally": 68}}}
```

Flags look like `{"code": "HIDDEN_TEXT", "detail": "28 characters of hidden text (white, tiny, off-page or hidden formatting) were excluded from analysis."}`.
Failures stay HTTP 200 with `status: "FAILED"` and an `errorCode`: `UNSUPPORTED_FORMAT`, `EMPTY_DOCUMENT`,
`FILE_TOO_LARGE`, `FILE_TOO_COMPLEX`, `TEXT_LIMIT_EXCEEDED`, `MALFORMED_PDF`, `ENCRYPTED_PDF`, `MALFORMED_DOCX`,
`MALFORMED_DOC`, `ENCRYPTED_DOC`, `PARSE_TIMEOUT`, `PARSER_CRASHED`; scans return `NEEDS_OCR` with
`SCANNED_PDF`/`SCANNED_DOCX`; text without structured facts is `PARTIAL`/`NO_STRUCTURED_FACTS`.

`POST /api/v1/match` - same candidate plus a candidate claim:

```json
{"requirements": [
   {"id": 11, "type": "SKILL", "description": "Tally", "mandatory": true, "knockout": true, "minimumYears": null, "alternatives": [], "proficiency": null},
   {"id": 12, "type": "EXPERIENCE", "description": "GST compliance", "mandatory": true, "knockout": false, "minimumYears": 3, "alternatives": [], "proficiency": null},
   {"id": 13, "type": "CERTIFICATION", "description": "Chartered Accountant", "mandatory": false, "knockout": false, "minimumYears": null, "alternatives": [], "proficiency": null},
   {"id": 14, "type": "OTHER", "description": "Willing to travel to client sites", "mandatory": false, "knockout": false, "minimumYears": null, "alternatives": [], "proficiency": null}],
 "facts": [{"id": "fact:3", "type": "SKILL", "value": "Tally", "evidence": "Tally, GST, MS Excel", "sourceSection": "skills",
            "tier": "RESUME_STATED", "attributes": {"skillId": "tally", "domain": "FINANCE"}},
           {"id": "claim:3", "type": "CERTIFICATION", "value": "Chartered Accountant (CA)", "tier": "SELF_CLAIMED"}, "..."],
 "timeline": {"totalMonths": 95, "roles": ["..."], "skillMonths": {"GST": 27, "...": 0}}}
```

```json
{"algorithmVersion": "evidence-ladder-3",
 "scoreLabel": "Verified requirement evidence coverage (not a probability of success)",
 "verifiedCoverage": 40.0, "claimedCoverage": 60.0, "knockoutsVerified": true, "unverifiedKnockouts": [], "score": 40.0,
 "requirements": [
  {"requirementId": 11, "status": "MATCHED", "tier": "RESUME_STATED", "evidence": "Tally, GST, MS Excel", "factIds": ["fact:3"],
   "sourceSection": "skills", "provenance": "RESUME", "explanation": "Resume evidence shows Tally.", "verificationHint": null},
  {"requirementId": 12, "status": "NOT_OBSERVED", "tier": null,
   "evidence": "Accountant, Kalinga Steel Ltd · Jan 2019 - Mar 2021 · Filed GST returns and reconciled bank statements",
   "factIds": ["fact:0"], "sourceSection": "experience", "provenance": "RESUME",
   "explanation": "Resume evidence states a shorter duration (2.2 years) than the requested minimum (3 years); other experience is not inferred.",
   "verificationHint": "Confirm how many years were spent on GST compliance (minimum 3 years): roles, dates and their own scope of work."},
  {"requirementId": 13, "status": "MATCHED", "tier": "SELF_CLAIMED", "evidence": "Candidate claim: Chartered Accountant (CA)",
   "factIds": ["claim:3"], "sourceSection": null, "provenance": "CANDIDATE_CLAIM",
   "explanation": "The candidate's own claim (not yet verified) lists a certification matching 'Chartered Accountant'.",
   "verificationHint": "Only the candidate's own claim supports 'Chartered Accountant'; check the linked evidence or test it in a work sample or interview."},
  {"requirementId": 14, "status": "NOT_OBSERVED", "tier": null, "evidence": null, "factIds": [], "sourceSection": null, "provenance": null,
   "explanation": "This requirement category is not scored automatically and needs human review.",
   "verificationHint": "Not assessed automatically - evaluate it in the interview or with a reference check."}]}
```

`POST /api/v1/match/batch` takes `{requirements, candidates: [{applicationId, facts, timeline}]}` (max 50) and
returns `{candidates: [{applicationId, ...MatchResponse}]}`.

`POST /api/v1/requirements/compile` with `{"title": "Accountant", "experienceLevel": "MID", "description":
"Requirements:\n- 3+ years of GST and Tally experience\n- B.Com or M.Com required\nNice to have:\n- SAP FICO or QuickBooks\n- Young and energetic team player"}`:

```json
{"engine": "rules",
 "requirements": [
  {"type": "EXPERIENCE", "description": "GST and Tally", "mandatory": true, "knockout": false, "minimumYears": 3, "alternatives": [],
   "proficiency": null, "rationale": "'3+ years of GST and Tally experience' - listed under requirements; asks for 3+ years."},
  {"type": "EDUCATION", "description": "B.Com or M.Com", "mandatory": true, "knockout": true, "...": "..."},
  {"type": "SKILL", "description": "SAP FICO", "mandatory": false, "knockout": false, "alternatives": ["QuickBooks"], "...": "..."},
  {"type": "OTHER", "description": "Young and energetic team player", "mandatory": false, "...": "..."}],
 "warnings": [
  {"phrase": "Young", "kind": "AGE_CODED", "suggestion": "Describe the work and tools instead of traits associated with age."},
  {"phrase": "energetic", "kind": "AGE_CODED", "suggestion": "Describe the pace of the work (e.g. 'handles 20+ customer calls a day') rather than a personal trait."},
  {"phrase": "team player", "kind": "VAGUE", "suggestion": "Replace the trait with the task it stands for, so it can be assessed."},
  {"phrase": "B.Com or M.Com", "kind": "DEGREE_INFLATION", "suggestion": "Consider adding 'or equivalent practical experience': ..."}]}
```

`POST /api/v1/work-samples/generate` with `{"jobTitle": "Accountant", "requirements": [<req 11>, <req 12>]}` returns
`{"title": "Accountant work sample", "instructions": "You have 15 minutes in total. ... Task 1 (about 15 minutes): From the summarised figures provided ...",
"timeLimitMinutes": 15, "rubric": [{"requirementId": 11, "criterion": "Use of Tally", "anchors": {"1": "Does not apply Tally, ...",
"2": "...", "3": "...", "4": "..."}}, {"requirementId": 12, ...}, {"requirementId": null, "criterion": "Clarity of reasoning and communication", ...}], "engine": "rules"}`.

`POST /api/v1/interview-kits/generate` with `focusRequirementIds: [12]` returns
`{"questions": [{"id": "q1", "requirementId": 12, "question": "Tell me about a recent piece of work where you used GST compliance over the last 3+ years. ...",
"followUps": ["What exactly was your part, as opposed to the team's?", "..."], "anchors": {"1": "...", "2": "...", "3": "...", "4": "..."}},
{"id": "q2", "requirementId": 12, ...}, {"id": "q3", "requirementId": 11, ...}, {"id": "q4", "requirementId": 11, ...}], "engine": "rules"}`.

`GET /health` -> `{"status": "ok", "version": "2.0.0", "llmProvider": "none", "ocrAvailable": false}`.

## Environment variables

`AI_SERVICE_TOKEN` (required), `LLM_PROVIDER` (`none`|`anthropic`|`openai_compatible`), `LLM_MODEL` (required when a
provider is enabled - startup fails otherwise), `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`,
`OPENAI_API_KEY`, `LLM_TIMEOUT_SECONDS` (20), `PARSE_WORKERS` (2), `PARSE_TIMEOUT_SECONDS` (60),
`MAX_UPLOAD_BYTES` (10 MiB), `OCR_ENABLED` (false), `SOFFICE_PATH` (auto-detected; empty disables),
`TAXONOMY_EXTRA_PATH`. See `ai-service/.env.example`.

Pinned and verified: fastapi 0.121.3, starlette 0.50.0, python-multipart 0.0.22, uvicorn[standard] 0.38.0,
pydantic 2.12.5, httpx 0.28.1, pdfplumber 0.11.9, pdfminer.six 20251230, python-docx 1.2.0, olefile 0.47;
dev: pytest 8.4.2; OCR extra: rapidocr-onnxruntime 1.4.4. `pydantic-settings` and `python-dotenv` were dropped (unused).

## Evaluation results (seed 20261108, 208 base resumes + 208 twins, 24 jobs)

| Metric | Value | Threshold |
|---|---|---|
| Skill precision / recall / F1 | 0.998 / 0.997 / **0.998** | F1 >= 0.85 |
| Date accuracy (role start and end) | **0.992** | >= 0.90 (added) |
| Flag accuracy (hidden text, injection) | 1.000 | reported |
| Requirement kappa (6 864 pairs) | **0.993** (raw agreement 0.996) | >= 0.70 |
| Ranking NDCG@10 | **0.990** | >= 0.80 |
| Name/gender flip rate (624 checks) | **0.000** | = 0 |
| Adverse-impact ratio (F 0.351 vs M 0.351 selected at >= 60 % verified coverage) | **1.000** | >= 0.80 |

These numbers are for **synthetic** documents generated from templates written alongside the parser and are
expected to be far higher than on real resumes. They are regression guards, not accuracy claims (README states
this). Building the harness exposed and fixed three real parser bugs - none fixed by changing the generator's
difficulty: (1) two-column detection picked only the widest low-coverage band, so a longer email address could
change the reading order and hence the outcome for the "same" candidate (a genuine name-dependent flip);
(2) full-width header rows were split across columns; (3) date ranges wrapped onto two lines were lost. The
generator itself had one bug (twins consumed shared randomness and differed in content), which was fixed so twins
differ only in identity.

## Deviations from the plan

- **Taxonomy size and schema:** 847 skills (plan "~600"). `ambiguous` applies to the skill's *name*; aliases are
  always unambiguous (ambiguous short forms such as ML/DL/CV/Node/REST are simply not listed). One optional field
  was added: `caseSensitive` (default true; only Triage is false, because "triage" is normally lower case).
  One-letter names (C, R) need an explicit context word; another nearby skill is not enough.
- **Tier semantics:** the reported tier is the highest tier at which the requirement is still MATCHED using only
  facts at or above that tier (weakest link of a conjunction), which is what `verifiedCoverage` needs. UNCLEAR
  results report the tier of the best cited fact; NOT_OBSERVED always has `tier: null` but may cite evidence (e.g.
  "states a shorter duration") with its provenance.
- **Linked evidence:** `ws:N:rM` / `iv:N:rM` facts satisfy only requirement M (they never feed text matching).
- **Contract details:** `MatchFact.evidence`, `sourceSection`, `attributes` are optional; fact `type` also accepts
  `OTHER` (claims); `MatchRequirement.knockout`/`alternatives` default to false/[]; an unknown requirement type is a
  422. Up to 500 facts per candidate.
- **Experience relevance:** besides skill mentions, a role counts as relevant when it contains every word of a
  multi-word skill ("B2B SaaS sales" for B2B Sales) or its title names the practitioner of a one-word skill
  ("Recruiter" for Recruitment). Year-only dates are mid-year ("2019 - 2021" = 24 months).
- **Extraction choices:** hobbies/interests and degree lines never yield skills; unknown items in a skills list
  become SKILL facts without `skillId`; one SKILL fact per skill (its best mention, skills section first).
- **DOC:** LibreOffice conversion is tried first when available (keeps hidden-text detection), then the olefile
  piece-table reader.
- **Middleware** additionally enforces the upload size (413) for authenticated requests.
- **Process pool** uses the `spawn` start method; a time-out or crash recycles the pool, terminating workers via
  `ProcessPoolExecutor._processes` (best effort; there is no public API).
- **LLM extraction** only adds SKILL facts (with quotes); experience/education stay rule-based.
- **Eval:** adds date accuracy (gated at 0.90) and flag accuracy; AIR is computed over counterfactual pairs, so it
  isolates identity effects rather than sampling noise.

## Notes for WP-BE-B (backend integration)

- `AiMatchRequest.Fact`: `id` becomes a **string** (`fact:<id>`, `claim:<id>`, `ws:<assignment>:r<req>`,
  `iv:<interview>:r<req>`), `reviewStatus` is replaced by `tier`, plus optional `attributes` (send the stored
  `attributes_json`). Send the stored `timeline_json` as `timeline`. Never fold a candidate edit into the resume
  fact: send the original fact and the claim separately.
- `AiMatchRequest.Requirement` gains `knockout`, `alternatives`, `proficiency`.
- `AiMatchResponse` gains `verifiedCoverage`, `claimedCoverage`, `knockoutsVerified`, `unverifiedKnockouts`;
  each requirement gains `tier`, `factIds`, `verificationHint`; provenance values are now
  `RESUME | CANDIDATE_CLAIM | CORROBORATED | WORK_SAMPLE | INTERVIEW`. `algorithmVersion` is `evidence-ladder-3`.
- `AiResumeParseResponse` gains `flags`, `timeline`, and per fact `spanStart`, `spanEnd`, `attributes`; fact type
  `LINK` is new and must be treated like EMAIL/PHONE (identity data, hidden until reveal).
- Parse failures remain HTTP 200 with `status`/`errorCode`; new codes are listed above.

## Known limitations

- OCR is optional and only tested with a fake engine here; real OCR quality depends on scans.
- The olefile `.doc` reader cannot see hidden formatting (LibreOffice conversion can).
- Hidden text behind images or via PDF render modes is not detected.
- Role segmentation and section detection are heuristic; unusual layouts can merge or split roles.
- Matching is lexical/taxonomy based; skills outside the taxonomy fall back to own-type lexical matching.
- De-identification relies on the header name and patterns; names elsewhere that are not in the header (e.g. a
  referee) are not removed, and gendered job titles (e.g. "salesman") are not neutralised.
- The synthetic evaluation does not measure real-world accuracy; a recruiter-labelled set is still required (plan
  Section 10).
