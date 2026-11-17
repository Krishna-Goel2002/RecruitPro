# simulate: end-to-end simulation runner

`simulate` drives a **running** RecruitPro stack with a generated resume corpus, the same way real people use it:

1. **HR** (`hr@acme.test`) creates each corpus job with its requirements, or reuses an open job with the same
   title, then publishes it.
2. For every resume, a **candidate** account is registered, the profile is filled in, the resume is uploaded, the
   analysis is polled until it finishes, and the candidate applies to the target job(s).
3. The runner waits until matching has finished for every application, then reads the HR ranking
   (`GET /hr/jobs/{id}/applicants?sort=rank`) and scores it against the corpus gold labels.
4. It writes `report.json`, a readable `report.md` and `candidates.csv`, and prints a short summary.

It needs only Python 3.10+ and `httpx`. It talks to the public REST API only: no database access and no backend
changes.

## What the report measures

| Section | What it means |
|---|---|
| NDCG@10, NDCG@all | Ranking quality against the gold fit labels (strong=4, good=3, partial=2, weak=1, unrelated=0, exponential gain). 1.0 = perfect order. |
| P@5, P@10 | Share of strong/good candidates in the top 5/10 (divides by the number shown when fewer). |
| Pairwise accuracy | Of all candidate pairs with different labels, the share in the right order. |
| Ranking-group agreement | Actual `rankGroup` (RANKED / MUST_HAVES_UNVERIFIED / UNSCORED) vs the corpus `expectedRankingGroup`, with a confusion table. |
| Parser outcomes | Analysis status (COMPLETED, PARTIAL, FAILED, NEEDS_OCR, UNSUPPORTED_FORMAT, TIMEOUT, UPLOAD_REJECTED) and analysis time per **format** (`pdf`, `docx`, `scan`, ...), **template** (the 11 ResumeLab layouts), **quality** (polished / average / poor) and **traits** (the resume's adversarial tags, `none` when clean). |
| Adversarial | Manipulated resumes (`hidden_keywords`, `prompt_injection`, `inflated_claims`): did they **rise** above a better-fitting clean candidate, or sit in the top third with a low label? Hidden keywords and prompt injection should also be **flagged** to HR (`HIDDEN_TEXT` / `PROMPT_INJECTION_SUSPECTED`); inflated claims are visible text, so they are not expected to be flagged. Per-kind table. Target: 0% rose, 100% flagged. |
| Counterfactual fairness | Twin personas (same resume, different name/gender): the **flip rate** is the share of twin pairs whose ranking group or coverage differs. Target: 0%. ResumeLab does not write twin groups yet, so this section reads "no twins" for its corpora. |
| Processing time | Upload to analysis finished, apply to match settled (resolution = poll interval), registration to everything finished. |
| Skipped resumes | Files the backend does not accept (`.txt`), left out before any account is created. |
| Failures | Every step that failed, with the stage (register, upload, analysis, apply, match) and the API message. |

Only applications created by **this run** are scored. When a job is reused, its earlier applicants still show in
the HR portal but are left out of the metrics (`report.md` shows the job's total applicant count).

## Corpus contract (ResumeLab output)

The runner reads what `python -m resumelab generate --out DIR` writes (full contract: `tools/resumelab/README.md`):

```
DIR/resumes/r0001.pdf | .docx | .doc | .txt      (a "scan" is an image-only .pdf)
DIR/jobs.json      [{key, title, department, location, jobType, experienceLevel, description, vacancies,
                     requirements: [{type, description, mandatory, knockout, minimumYears, alternatives, proficiency}]}]
DIR/manifest.json  {seed, asOf, generator, resumes: [{id, file, format, template, profession, seniority, quality,
                     adversarial: [tags], persona{firstName, lastName, email, phone, city, gender},
                     gold{skills, roles, totalMonths, education, certifications},
                     fit{<jobKey>: {label, expectedRankingGroup, metRequirements, coverage}}, targetJob, targetLabel}]}
```

How the runner uses it:

| Field | Use |
|---|---|
| job `key` | Job selection (`--jobs`) and report keys. Never sent to the backend. |
| every other job field | `POST /jobs` body, field for field: exactly the `JobCreateRequest` components (`title, description, department, location, jobType, experienceLevel, minimumSalary, maximumSalary, vacancies, deadline`). |
| `requirements[]` | `PUT /jobs/{id}/requirements` as `{"requirements": [RequirementInput...]}` (without `id`). |
| enums | Checked when the corpus loads, against the backend enums: `jobType` FULL_TIME / PART_TIME / CONTRACT / INTERNSHIP, `experienceLevel` ENTRY / MID / SENIOR / LEAD / EXECUTIVE, requirement `type` SKILL / EDUCATION / CERTIFICATION / EXPERIENCE / OTHER, `proficiency` BASIC / INTERMEDIATE / ADVANCED / EXPERT. Size limits (title 255, department 100, description 50000, requirement description 500, alternatives 10, minimumYears 0-50, 100 requirements) are checked too, so a bad corpus stops before the run instead of failing with a 400 halfway. |
| `file` | The upload. Its **extension** decides the type: the backend accepts `pdf, doc, docx` (`app.file-storage.allowed-types` in `backend/src/main/resources/application.yml`, enforced in `ResumeService`). Other files (`.txt`) are skipped and listed in the report; `--include-unsupported` uploads them anyway to test the rejection path (`UPLOAD_REJECTED`). |
| `format`, `template`, `quality` | Parser breakdowns. `format` keeps `scan` apart from `pdf`. |
| `adversarial` | Manipulation tags (`hidden_keywords`, `prompt_injection`, `inflated_claims`) feed the adversarial section; the other tags (`near_empty`, `non_english_section`, `duplicate_sections`, `very_long`, `photo_placeholder`) are robustness traits: the resume counts as clean for the ranking checks and shows up under *Parser outcomes by traits*. |
| `targetJob` | With the default `--apply-to target`, each resume applies to its target job only, like a real candidate. ResumeLab plans each job's applicants to a fixed label mix (strong 20%, good 25%, partial 25%, weak 15%, unrelated 15%). |
| `fit{job}.label`, `expectedRankingGroup` | Gold for the ranking metrics and group agreement. ResumeLab labels every resume for every job, so `--apply-to fit` (or `all`) applies each resume to every selected job: four times the applications, mostly `unrelated`. |
| `persona` | Account name; `city` "Kolkata, West Bengal" fills profile city and state. |
| `gold.totalMonths`, `gold.roles` | Profile `yearsOfExperience`, `currentDesignation`, `currentCompany` (the current role, else the latest). The profile is never used for matching. |
| `profession`, `seniority` | Extra columns in `candidates.csv`. |

Older or hand-made corpora still load: `template` (`layout`), `quality` (`noise`), `adversarial` (a string, or
`tags`), `twinGroup` (`counterfactualGroup`, `twinOf`: a twin that names another resume id joins its group),
`yearsOfExperience`, `currentTitle`, `currentCompany` and `summary` are looked up on the resume entry, then in `gold`,
then in `gold.meta` / `gold.render`. Without `targetJob`, `--apply-to target` behaves like `fit`. Missing attributes
show as `n/a`.

Two corpora come with the repo:

* `tools/resumelab/sample`: the real ResumeLab sample (60 resumes, 4 jobs, all 11 layouts, 3 scans, 2 `.txt`).
  `tests/test_resumelab_sample.py` runs it end to end against the fake backend and checks the report.
* `tests/fixtures/corpus`: a small hand-made corpus in the older shape (10 resumes, 2 jobs, a prompt-injection
  resume, a twin pair, a scanned PDF and a `.txt`). Regenerate it with `python tests\fixtures\make_fixtures.py`.

## Step by step (Windows PowerShell)

Every block starts from the repository root (`RecruitPro`). You need Python 3.10+ (`py --list` shows what is
installed; 3.12 is used below) and Docker Desktop.

### 1. Create the two virtual environments (once)

ResumeLab and the runner have separate dependencies, so each gets its own `.venv` (both are git-ignored).

```powershell
# ResumeLab: reportlab, python-docx, pillow, pypdfium2
py -3.12 -m venv tools\resumelab\.venv
tools\resumelab\.venv\Scripts\python.exe -m pip install -r tools\resumelab\requirements.txt

# simulate: httpx (+ pytest for the offline tests)
py -3.12 -m venv tools\simulate\.venv
tools\simulate\.venv\Scripts\python.exe -m pip install -r tools\simulate\requirements-dev.txt

# offline check: fake backend, no network, includes the ResumeLab sample end to end
cd tools\simulate
.\.venv\Scripts\python.exe -m pytest -q
cd ..\..
```

Calling `.venv\Scripts\python.exe` directly avoids activation. If you prefer `.\.venv\Scripts\Activate.ps1` and
PowerShell blocks it: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

### 2. Generate a corpus (120 resumes, 4 jobs)

ResumeLab runs as a package from the `tools` directory. `--as-of` is the month "Present" means; use the current
month so the gold experience months match what the parser computes today. The same seed and `--as-of` give the same
files.

```powershell
cd tools
$asOf = Get-Date -Format "yyyy-MM"
.\resumelab\.venv\Scripts\python.exe -m resumelab generate --out simulate\corpora\c120 `
    --count 120 --jobs 4 --seed 7 --as-of $asOf `
    --professions backend,data_analyst,nurse,sales --adversarial-rate 0.15
.\resumelab\.venv\Scripts\python.exe -m resumelab validate simulate\corpora\c120
cd ..
Get-Content tools\simulate\corpora\c120\jobs.json -Raw | ConvertFrom-Json | Select-Object key, title, experienceLevel
```

`tools\simulate\corpora` is git-ignored. `python -m resumelab list` shows all 21 professions; leave out
`--professions` to draw the jobs from all of them. With the default format mix about 5% of the resumes are `.txt`;
the runner skips those (or generate with `--formats pdf,docx,scan` to avoid them).

### 3. Start the stack with the simulation override

```powershell
python tools\generate_env.py      # once: writes .env with fresh secrets (any Python works)
docker compose -f docker-compose.yml -f tools\simulate\docker-compose.simulate.yml up --build -d

# wait until the backend answers (the first build takes several minutes)
do {
    Start-Sleep -Seconds 5
    try { $health = Invoke-RestMethod http://localhost:8080/api/v1/health } catch { $health = $null }
} until ($health.data.status -eq "UP")
docker compose -f docker-compose.yml -f tools\simulate\docker-compose.simulate.yml config |
    Select-String "RATE_LIMIT_ENABLED|OCR_ENABLED"        # RATE_LIMIT_ENABLED: "false", OCR_ENABLED: "true"
```

The override (`tools/simulate/docker-compose.simulate.yml`) sets `RATE_LIMIT_ENABLED=false` and
`AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY=false` on the backend and `OCR_ENABLED=true` on the AI service, so scanned
resumes are read. **Never use it on a shared or production stack.** Without it, the backend limits sign-ups to 5 per
minute and sign-ins to 10 per minute per IP; the runner then paces itself (`--pacing auto`, about 12 s per
candidate).

The dev seed creates organisation "Acme Labs" with HR user `hr@acme.test` / `ChangeMe-123!` (`APP_SEED_PASSWORD`;
see `docs/guides/REDESIGN_TEST_GUIDE.md`). The runner signs in as that user.

### 4. Run the simulation

```powershell
cd tools\simulate
$env:SIMULATE_HR_PASSWORD = "ChangeMe-123!"     # or pass --hr-password

# optional smoke run first: the ResumeLab sample, 10 resumes
.\.venv\Scripts\python.exe -m simulate run --corpus ..\resumelab\sample --limit 10 --pacing off `
    --fresh-jobs --report out\smoke

# the generated corpus: limits off, 8 candidates in parallel
.\.venv\Scripts\python.exe -m simulate run --corpus corpora\c120 --base-url http://localhost:8080/api/v1 `
    --hr-email hr@acme.test --pacing off --concurrency 8 --fresh-jobs --report out\c120
```

The first line names the **run id** (for example `run simk3x9q2: 112 resumes x 4 jobs ...`) and, when there are
`.txt` files, how many resumes were skipped. Progress is printed per resume, then `waiting for matching ...`, then a
summary with NDCG, group agreement, adversarial and timing figures. **Write the run id down**: cleanup needs it (it
is also the folder name under `runs\`). Every run, the smoke run included, has its own id and is cleaned up
separately. 120 resumes take about 5-15 minutes with the limits off.

Other useful variants:

```powershell
# only two jobs, first 40 resumes
.\.venv\Scripts\python.exe -m simulate run --corpus corpora\c120 --jobs <key1>,<key2> --limit 40 --pacing off
# every resume applies to every job (ResumeLab labels all of them): 4x the applications, mostly "unrelated"
.\.venv\Scripts\python.exe -m simulate run --corpus corpora\c120 --apply-to fit --pacing off --concurrency 8
# also upload the .txt resumes, to check the backend rejects them (UPLOAD_REJECTED)
.\.venv\Scripts\python.exe -m simulate run --corpus corpora\c120 --include-unsupported --pacing off
```

(Job keys come from `jobs.json`, as listed in step 2.)

Options (all have defaults):

| Option | Default | Notes |
|---|---|---|
| `--jobs k1,k2` | all jobs | job keys from `jobs.json` |
| `--limit N` | all | first N resumes that have a job to apply to (after skipping unsupported files) |
| `--concurrency N` | 4 | candidates processed in parallel |
| `--apply-to target\|fit\|all` | `target` | `target`: the resume's `targetJob`; `fit`: every selected job with a gold label; `all`: every selected job |
| `--allowed-types` | `pdf,doc,docx` | the backend's `app.file-storage.allowed-types`; change it only if the backend's setting changes |
| `--include-unsupported` | off | upload other file types too (they fail as `UPLOAD_REJECTED`) |
| `--publish` / `--no-publish` | publish | `--no-publish` creates jobs as drafts and stops (nothing can apply to a draft) |
| `--report DIR` | `runs\<run-id>\report` | output folder |
| `--fresh-jobs` | off | append `[<run-id>]` to job titles instead of reusing open jobs with the same title |
| `--pacing auto\|off` | `auto` | `off` only together with `RATE_LIMIT_ENABLED=false` |
| `--analysis-timeout S` | 300 | per resume; a resume still processing becomes `TIMEOUT` and still applies |
| `--match-timeout S` | 900 | for all matching; unfinished applications are reported as `MATCH_TIMEOUT` |
| `--poll-interval S` / `--max-poll-interval S` | 2 / 15 | polling backs off x1.5 up to the maximum |
| `--email-domain D` | `sim.recruitpro.test` | candidate emails are `<persona-local>+<run-id>-<n>@D`; `persona` keeps the persona's domain (they would receive mail on a stack with real SMTP) |
| `--mailpit-url URL` | none | e.g. `http://localhost:8025`: when applying answers 403 (verified email required), read the link from Mailpit and confirm it |
| `--run-id ID` | new | resume an interrupted run: signed-up candidates sign in again, finished steps are skipped |
| `--hr-totp-secret` | none | Base32 secret if the HR account uses two-step verification (`SIMULATE_HR_TOTP_SECRET`) |

Exit code: 0 when every resume completed every step, 3 when some failed (see the report), 1 on a fatal API error
(for example HR sign-in), 2 on bad arguments. A corpus that does not fit the backend DTOs stops with a message that
names the job and field.

### 5. Read the report

```powershell
Get-Content out\c120\report.md                 # or open it in VS Code: code out\c120\report.md (Ctrl+Shift+V previews)
Invoke-Item out\c120\report.md                 # opens it with the default app for .md
Import-Csv out\c120\candidates.csv | Out-GridView
```

* `report.md`: tables for every section above, plus the top 10 of each job with gold labels next to the system's
  groups and coverages, and the skipped resumes.
* `candidates.csv`: one row per application (resume, job, format, template, quality, profession, traits, gold label,
  expected vs actual group, position, coverages, match/analysis status, flags, timings): filter it to see *why* a
  resume landed where it did.
* `report.json`: everything, including the corpus seed and `asOf`, for comparing runs with a script.

The same corpus against the same build should give the same numbers; compare two `report.json` files after a
change to the parser or matcher.

### 6. Clean up

```powershell
# still in tools\simulate, same PowerShell window ($env:SIMULATE_HR_PASSWORD set); use the run id from step 4
.\.venv\Scripts\python.exe -m simulate cleanup --run-id simk3x9q2 --pacing off --delete-accounts
cd ..\..

# back to normal rate limits and OCR setting
docker compose up -d backend ai-service
# or stop everything; add -v to drop ALL data (the demo data is seeded again on the next up)
docker compose down
# generated files: corpus, run state (holds throwaway passwords) and reports
Remove-Item -Recurse -Force tools\simulate\corpora\c120, tools\simulate\runs, tools\simulate\out
```

What `cleanup` does:

* Withdraws every application the run created (as its candidate).
* `--delete-accounts` deletes the run's candidate accounts (anonymised; resumes, facts and claims removed). With
  rate limits on, the backend allows 5 deletions per 15 minutes per IP, so this is paced (about 3 minutes per
  account) unless you pass `--pacing off` on a stack started with the override.
* Closes the jobs the run **created** (a draft without applicants is deleted). Reused jobs stay open unless you
  pass `--close-reused`.
* Safe to run twice: finished steps are recorded in the run state and skipped.

**What cannot be deleted through the API** (kept on purpose for audit and fairness history): closed jobs,
applications (they stay as WITHDRAWN), decision history, match runs, audit events, and user rows (deletion only
anonymises them). Without `--delete-accounts`, candidate accounts and their resumes stay. For a clean local
database: `docker compose down -v` (drops all data).

Run state lives in `runs\<run-id>\state.json`. It contains the throwaway candidate passwords (needed for withdraw
and delete), so the `runs` folder is git-ignored; delete it when you no longer need cleanup.

## Expected runtime

Rough figures for a laptop running the docker compose stack; the backend processes background jobs (resume parsing
and matching) in batches of 5 every 2 seconds, which caps throughput at about 2.5 jobs per second.

| Corpus | Rate limits | Approximate time | Bottleneck |
|---|---|---|---|
| Fixture (10 resumes, 2 jobs) | on (`--pacing auto`) | 2-3 min | sign-up pacing (5/min) |
| ResumeLab sample (58 uploadable, `--apply-to target`) | off | 3-6 min | parsing + matching queue |
| 120 resumes | on | 25-30 min | sign-up pacing: ~12 s per candidate |
| 120 resumes | off (`--pacing off`, concurrency 4-8) | 5-15 min | parsing + matching queue |
| 1000 resumes | off, concurrency 8 | 1-2 h | parsing + matching queue (1 parse + 1 match job per application) |

Scanned PDFs (OCR) and `.doc` files converted with LibreOffice are slower; without OCR scans end as `NEEDS_OCR`
and stay UNSCORED. If many resumes end as
`TIMEOUT`, raise `--analysis-timeout`; the analysis keeps running on the server.

## How it handles the API

* **Auth:** bearer access token from the `login` / `register` response; the refresh cookie is not used (it is
  `Secure` and the run is plain http). On a 401 the session signs in again. Headers: `X-RecruitPro-Portal:
  candidate` for candidates, `staff` for HR.
* **Registration:** strong random password per candidate; email `<persona>+<run-id>-<n>@sim.recruitpro.test`.
  A 409 (already registered, for example after a lost response) is answered by signing in with our password, and
  if that fails the next suffix is tried. Email verification is not required to apply in dev
  (`AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY=false`); if it is, pass `--mailpit-url`, otherwise the 403 is reported
  with that hint.
* **Upload:** multipart field `file`, content type from the file extension; the backend accepts pdf, doc and docx
  only and checks the content matches. Other types are skipped before registration (with `--include-unsupported`
  they are reported as `UPLOAD_REJECTED`; 10 MB limit: `UPLOAD_TOO_LARGE`).
* **Apply** (`POST /jobs/{id}/applications {resumeId}`) happens after the analysis reaches a terminal status (or
  times out); a 409 means it already exists and the id is looked up.
* **Matching done** = every application's `matchStatus` is READY, NO_REQUIREMENTS, ANALYSIS_UNAVAILABLE or FAILED
  in the HR applicant list (PENDING means queued or analysing).
* **Retries:** 429 waits for `Retry-After` (and holds every thread on that route); 502/503/504 and connection
  errors or timeouts retry with exponential backoff and jitter (6 attempts).

## Layout

```
simulate/api.py       envelope, sessions, pacing (RateGate), retries, TOTP
simulate/corpus.py    corpus loading, ResumeLab fields, backend DTO/enums check, attribute lookup
simulate/runner.py    the run (jobs, candidates, matching, report assembly)
simulate/metrics.py   NDCG, precision, pairwise, group agreement, parser, adversarial, fairness, timings
simulate/report.py    report.json / report.md / candidates.csv
simulate/cleanup.py   best-effort undo
simulate/state.py     run state
tests/                unit tests, fake backend (httpx.MockTransport) that checks the job/requirement DTOs,
                      fixture corpus; test_resumelab_sample.py runs tools/resumelab/sample end to end
```
