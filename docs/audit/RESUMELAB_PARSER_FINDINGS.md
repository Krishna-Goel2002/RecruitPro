# ResumeLab: parser and matcher findings

This is input for a later fix pass. No AI-service app code was changed while producing it.

- **Corpora:**
  - `tools/resumelab/sample`: 60 resumes, 4 jobs, committed.
  - A regenerable 300-resume corpus with 8 jobs and 21 professions:
    `python -m resumelab generate --out /tmp/rl300 --count 300 --seed 99 --adversarial-rate 0.15 --as-of 2026-11`.
- **Harness:** `cd ai-service && python -m eval.run --corpus <dir> --json out.json`. It runs the real
  `parse_resume` (bytes in) and `match_requirements` for every resume against every job.
- **Settings:** `LLM_PROVIDER=none`, OCR off (the default), today = 2026-11-15.
- **Gold:** the visible document, read back by the generator. The rules are in
  `tools/resumelab/README.md`. Skill metrics only count gold skills that have a taxonomy id.

## Headline metrics

| Metric | Sample (60) | 300-resume corpus |
|---|---|---|
| Parse status | 55 COMPLETED, 3 NEEDS_OCR (scans), 2 FAILED (txt) | 248 / 33 / 19 |
| Skill precision / recall / F1 (all files) | 0.995 / 0.868 / 0.927 | 0.994 / 0.787 / 0.878 |
| Skill recall, PDF + DOCX only | 0.935 | 0.949 |
| Role date accuracy (start and end at shown precision) | 0.75 | 0.66 |
| Role count exact | 69% (13 under, 4 over) | 67% (56 under, 26 over) |
| totalMonths error: MAE / median / within ±3 months | 6.9 / 0 / 79% | 12.9 / 0 / 71% |
| Per-skill months within ±3 | 77% | 73% |
| Highest degree level detected | 90% | 96% |
| Prompt injection flagged | 3/3 | 4/4 |
| Keyword stuffing flagged | 3/3 | 8/8 |
| Hidden text detected | 3/3 | 8/8 |
| False injection flags on clean resumes | 0/49 | 0/237 |
| False hidden-text flags on clean resumes | 0/49 | 2/237 |
| Requirement agreement with gold, MATCHED vs met: SKILL | P 0.97 / R 0.87 | P 0.98 / R 0.79 |
| Same: EXPERIENCE | P 1.00 / R 0.37 | P 0.74 / R 0.31 |
| Same: EDUCATION | P 0.88 / R 0.83 | P 0.86 / R 0.56 |
| Same: CERTIFICATION | P 1.00 / R 0.75 | P 1.00 / R 0.71 |
| Ranking-group agreement with gold | 0.86 | 0.81 |
| NDCG@10 per job | nurse 1.00, data analyst 0.99, backend 0.90, sales 0.73 | 0.77–0.93 (mean 0.85) |

### What already works well

- Skill precision is about 0.99.
- Every injection and stuffing case is caught, and no clean resume gets an injection flag.
- Two-column detection worked on 100% of sidebar PDFs.
- DOCX text boxes are read; `docx_textbox` skill recall is 0.98–1.0.
- Indian date styles parse well: `June'19`, `2019 – till date`, `Since Mar 2021`, `Sept. 2019 — Date`,
  `04/2024`, `2024-02` and year-only.
- When a role is segmented, its dates are almost always right.
- The same LLM-free pipeline gave synthetic-eval F1 0.85 or higher. Recall is lower here because
  layout and wording vary much more.

## Weaknesses, most impactful first

### W1. A common Indian heading drops the whole experience section (high impact)

`Professional Journey` is not in the section lexicon, and no keyword fallback catches it. Every role
under it disappears: no timeline, `totalMonths` = 0, every EXPERIENCE requirement is NOT_OBSERVED,
and the knockouts fail. In the 300-resume corpus, 27 of 31 resumes using this heading lost all
their roles. That is the largest single cause of role misses: 98 of 137 missed roles.

- **Sample examples:** r0006, r0011, r0017, r0027, r0045, r0060.
- **Other headings ResumeLab uses that `lexicon_kind` and `_keyword_kind` both miss:** `Career Goal`,
  `Key Strengths`, `Personal Strengths`, `Conference Presentations`. These are mostly harmless, but
  their content inherits the previous section.
- **Misclassified:** `Declaration` maps to PERSONAL, and `Strengths` maps to SKILLS (soft-skill
  lists).
- **Suggested fix:** add `professional journey`, `career journey`, `career goal`, `key strengths`
  and similar entries. Consider treating any heading-like line that is followed by date-ranged role
  headers as EXPERIENCE.

### W2. Experience relevance is lexical, so EXPERIENCE requirements are under-matched (high impact on ranking)

EXPERIENCE recall is 0.31–0.37. The two most common explanations, "states a shorter duration" and
"no comparable experience", trace back to W1, W3 and W4: roles are missing or shortened. The rest
are relevance misses: the roles exist, but `_eval_experience` cannot tie them to the subject.

- **Examples of relevance misses:**
  - "3+ years: professional backend development experience" against `Software Engineer` / `SDE-2`
    roles with Java and Spring bullets.
  - "B2B sales experience" against `Account Executive` / `Key Account Manager`.
  - "product or UX design experience" against `Interaction Designer`.
  - "experience in a manufacturing plant" against `Production Engineer`.
- **Effect:** these are knockouts, so strong candidates fall from RANKED to MUST_HAVES_UNVERIFIED.
  That happened 13 times in the sample and 37 times in the 300-resume corpus. The top failing
  knockouts are B2B sales (15), product management (10), manufacturing plant (9) and backend
  development (6 in the sample). This explains the sales job's NDCG@10 of 0.73.
- **Suggested fix:** map role titles to practice skills (title-head ontology, e.g. SDE or Software
  Engineer to Backend Development when backend skills appear in the block, Account Executive to B2B
  Sales). Let EXPERIENCE subjects match the profession implied by the title plus block skills.

### W3. Two-column and table layouts break role segmentation and leak identity (high)

- **Sidebar PDFs (`modern_sidebar`):** 18 roles were missed in the 300-resume corpus even where an
  experience section was found, and the sample lost all roles in r0019. The candidate's name,
  printed at the top of the main column, is read as an unknown SKILL fact inside the sidebar's skills
  section. Every name leak (6 in the 300-resume corpus) came from `modern_sidebar` (sample r0025). It is an identity leak into professional evidence, and blind
  review depends on facts not carrying the name.
- **Education in the sidebar** is sometimes not extracted at all (r0008: all three EDUCATION
  requirements NOT_OBSERVED).
- **Table layouts (`table_grid` DOCX and PDF):** the section label sits in the left cell, so a whole
  row reads as one line, for example "COURSES & CERTIFICATIONS | Registered Nurse ...". The section is
  then classified as `other`, so certifications are not CERTIFICATION facts (r0005, r0009, r0032,
  r0037) and education can be lost (r0005). 9 roles were missed in `table_grid` files.
- **Indian education tables (`fresher_indian`, `indian_traditional`):** degree cells that wrap onto
  two lines lose the degree row; only Class X and XII survive (r0031).
- **Suggested fix:**
  - Read DOCX table rows cell by cell, and treat a short first cell that matches the lexicon as a
    heading.
  - Order PDF columns by reading the sidebar after the main column.
  - Drop the header-name line from skill extraction.
  - Merge wrapped table cells before degree detection.

### W4. Separate internship sections lose internships (medium)

Roles under `Internships`, `Internship & Training` or `Industrial Training` headings, or listed
date-first inside fresher templates, are often not segmented. Examples: r0031, r0039, r0049, r0050,
r0056. Freshers then show `totalMonths` = 0, which hurts entry-level jobs that need "1+ years"
including internships.

### W5. Languages are mostly lost (medium)

- Languages listed under Personal Details ("Languages Known: English, Hindi") are never extracted:
  recall 0% in both corpora, because the PERSONAL section is excluded on purpose.
- In a real Languages section, recall is only about 0.72. Most misses are rating-dot lists
  ("English ●●●●○" in `creative_color`) and table cells (`table_grid`), where ambiguous language
  names lack list context.
- "Fluent in English and Hindi" jobs (OTHER today) and customer-support language requirements cannot
  be evidenced.
- **Suggested fix:** whitelist a "Languages Known" key inside PERSONAL. Treat rating glyphs (●○★☆)
  as list delimiters.

### W6. Requirement parsing treats "preferred" clauses as required (medium)

"Bachelor's degree; MBA preferred" turns the MBA field into a requirement. Any non-business degree
then becomes UNCLEAR ("bachelor's degree in law, which is a different field"). On the
product-manager job in the 300-resume corpus, 177 of the 232 resumes whose degree meets the
requirement came back UNCLEAR. That alone explains why EDUCATION recall is 0.56 there; most other jobs
are at about 0.9 or above. "Graduate in any discipline; MBA in Marketing preferred" was
handled correctly.

- **Suggested fix:** drop clauses after `preferred`, `a plus` or `nice to have` before
  `parse_education`.

### W7. Professional qualifications are not recognised as degrees (medium for finance and legal)

`detect_degree` misses CA (Chartered Accountant), CS (Company Secretary), CMA, BHM and M.Stat. A
qualified CA therefore shows no qualification fact. A job asking for "CA or M.Com" would only match
on the M.Com.

### W8. Alias and phrase coverage gaps cost recall (medium)

These phrasings are natural for a human reader but missed by the parser:

- verb forms: "mentored" for Mentoring (20 misses in the 300-resume corpus);
- "journey maps" for Customer Journey Mapping (10);
- "online classes" for Online Teaching (6);
- the singular "A/B test" for A/B Testing;
- "influencer campaigns" for Influencer Marketing;
- "GTM" for Go-to-Market Strategy;
- ellipsis, as in "field and inside sales" for Field Sales;
- "Excel" in prose ("reports in Excel"; 8 misses), because ambiguous-name rules reject it without
  list context even when the context words are present.

Real-world skills absent from the taxonomy (taxonomyId null, invisible to matching): STAAD.Pro,
ETABS, Primavera P6, CATIA, ANSYS, Creo, GD&T, PPAP, FMEA, CNC Programming, Zendesk, Freshdesk,
Miro, Framer, LinkedIn Recruiter and Naukri RMS. Civil and mechanical engineering are the most
affected.

### W9. Systematic false positives (low volume, but systematic)

The parser reads skills from text that is not a skill claim:

- **Employer names:** "Gyanpath Edtech" becomes Educational Technology (9 times in the
  300-resume corpus), "Northstar Logistics" becomes Logistics (5), "Little Acorns Montessori"
  becomes Montessori.
- **Issuer names:** "The Linux Foundation" becomes Linux.
- **Contact labels:** "Github: [link]" becomes GitHub (3).
- **Near-miss terms:** "Estimation and Costing" becomes Cost Accounting (4).
- **Hobbies:** they merge into a neighbouring section in two-column or table layouts ("Hobbies |
  Photography ..." becomes Photography).

Suggested fix: skip the employer span of role headers, the issuer span of certification lines, and
contact-label lines.

### W10. Running footers and white-on-colour text (low)

- A running footer "Meghana Prasad - Resume  1/2" became a CERTIFICATION fact, carrying the
  candidate's name (sample r0030, a sidebar PDF). Strip repeated header and footer lines per page.
- In a `classic_serif` PDF (sample r0036), the centred "LANGUAGES KNOWN" and "PERSONAL INFORMATION"
  headings were not taken as headings. The personal-details rows were stored as four CERTIFICATION
  facts, "Father's Name", "Marital Status", "Nationality" and "[date of birth removed]". The values
  are redacted, but the facts are still noise in certification matching.
- Two `creative_color` resumes, whose white name sits on a coloured header band, were flagged
  HIDDEN_TEXT (2/237). The hidden-text check should compare text colour with the fill drawn under
  it, not only with white.

### W11. Unreadable inputs (deployment, not parser logic)

- **Scans:** 100% NEEDS_OCR with the default `OCR_ENABLED=false`. Every scanned applicant ends up
  UNSCORED, together with the `.txt` files. That was 20 of 240 job-resume pairs in the sample and
416 of 2,400 in the larger corpus.
  Re-run with `requirements-ocr.txt` and `OCR_ENABLED=true` to measure OCR quality on ResumeLab
  scans (tilt, speckle, blur, JPEG).
- **`.txt`:** FAILED with `UNSUPPORTED_FORMAT`. This matches the backend, which only accepts
  pdf/doc/docx. Decide whether plain text, which candidates often paste, should be supported.

## Not issues: checked and ruled out

- **Year-only dates** ("2021 – 2023") are parsed as mid-year to mid-year. The harness compares them
  at year precision.
- **Personal details:** date of birth, marital status and father's name never appeared in skill or
  experience facts. The only places they leak are noted in W10, as redacted CERTIFICATION facts.
  Identity leaks are limited to W3 (the name read as a skill) and W10 (the name in a footer).
- **Dates themselves:** every date format ResumeLab emits parses correctly in isolation
  (`find_date_ranges`). Date misses come from section and segmentation failures (W1, W3, W4).

## How to reproduce or extend

```bash
cd tools && python -m resumelab generate --out /tmp/c --count 300 --seed 99 --adversarial-rate 0.15 --as-of 2026-11
cd ../ai-service && python -m eval.run --corpus /tmp/c --json /tmp/c.json
```

The JSON contains every metric above, plus breakdowns by format, quality, template and profession,
recall by gold section, the top missed and false skills, a sample of date misses, the
ranking-group confusion matrix, and the rank position of adversarial resumes for each job.

## Fix pass

All changes are in `ai-service/` (plus the vendored taxonomy copy in `agent-service/app/taxonomy/`, re-synced
with `tools/sync_taxonomy.py`). The API contract is unchanged: no new fact types, no new fields on any
response model. Two new keys appear inside the free-form `attributes` object, which the backend stores as
opaque JSON (`JsonNode`): `internship` on EXPERIENCE facts and `inProgress` on EDUCATION facts.
`PARSER_VERSION` stays `rule-parser-2`.

### How it was measured

The same harness (`python -m eval.run --corpus DIR`, `LLM_PROVIDER=none`, OCR off) was run before and after
on three corpora. "Before" is the code at `HEAD` of `Kshitij-assistant`, exported with `git archive`.

| Corpus | How it was made | Role |
|---|---|---|
| Sample (60) | committed `tools/resumelab/sample`, 4 jobs | used while fixing |
| Held-out (80) | `python -m resumelab generate --out /tmp/heldout --count 80 --seed 4242 --as-of 2026-11`, 5 jobs (operations, teacher, backend, nurse, product manager) | generalisation check; generated before any fix |
| 300 | the command in the header of this file (seed 99), 8 jobs | second generalisation check |

The 300-resume corpus was regenerated after the taxonomy gained 16 skills (CATIA, Zendesk, ...), so its gold
gives those skills ids and the "before" code is penalised for not knowing them. The sample and held-out gold
predate the taxonomy change; there, a skill the parser now knows but the gold lists without an id is left out
of the counts (`eval/corpus.py`, `_named_in`) instead of counting as a false positive.

### Before / after

"All files" includes the scans (no OCR) and `.txt` files, which still parse to nothing (see remaining gaps).

| Metric | Sample before | Sample after | Held-out before | Held-out after | 300 before | 300 after |
|---|---|---|---|---|---|---|
| Skill precision / recall, all files | 0.995 / 0.868 | 0.999 / 0.915 | 0.990 / 0.774 | 0.996 / 0.819 | 0.994 / 0.761 | 0.998 / 0.818 |
| Skill precision / recall, PDF + DOCX | 0.995 / 0.935 | 0.999 / 0.986 | 0.990 / 0.930 | 0.996 / 0.984 | 0.994 / 0.917 | 0.998 / 0.986 |
| Role date accuracy, all files | 0.75 | 0.93 | 0.74 | 0.82 | 0.66 | 0.81 |
| Role date accuracy, PDF + DOCX | 0.80 | 0.99 | 0.91 | 1.00 | 0.80 | 0.98 |
| Role count exact | 69% | 98% | 76% | 99% | 67% | 95% |
| totalMonths MAE (months) / within ±3 | 6.9 / 79% | 0.02 / 100% | 7.4 / 82% | 0.0 / 100% | 12.9 / 71% | 1.9 / 96% |
| Per-skill months within ±3 | 77% | 96% | 77% | 92% | 71% | 92% |
| Highest degree level detected | 90% | 100% | 94% | 100% | 96% | 99.6% |
| Languages recall (Languages section / Personal Details) | 0.73 / 0.00 | 0.89 / 0.88 | 0.69 / 0.00 | 0.78 / 0.91 | 0.72 / 0.00 | 0.79 / 0.82 |
| Candidate name or personal detail in a fact | 6 | 0 | 4 | 0 | 6 | 0 |
| False HIDDEN_TEXT on clean resumes | 0/49 | 0/49 | 1/66 | 0/66 | 2/237 | 1/237 |
| Injection / stuffing / hidden text caught | 3/3, 3/3, 3/3 | same | 1/1, 1/1, 1/1 | same | 4/4, 8/8, 8/8 | same |
| Requirement P / R vs gold: SKILL | 0.97 / 0.87 | 0.97 / 0.91 | 1.00 / 0.80 | 1.00 / 0.82 | 0.98 / 0.79 | 0.98 / 0.82 |
| Same: EXPERIENCE | 1.00 / 0.37 | 1.00 / 0.97 | 0.95 / 0.41 | 0.97 / 0.78 | 0.74 / 0.31 | 0.98 / 0.71 |
| Same: EDUCATION | 0.88 / 0.83 | 0.90 / 0.90 | 0.89 / 0.31 | 0.96 / 0.84 | 0.86 / 0.56 | 0.90 / 0.81 |
| Same: CERTIFICATION | 1.00 / 0.75 | 1.00 / 1.00 | 1.00 / 0.67 | 1.00 / 0.74 | 1.00 / 0.71 | 1.00 / 0.79 |
| Same: all types | 0.94 / 0.80 | 0.95 / 0.92 | 0.97 / 0.58 | 0.98 / 0.82 | 0.93 / 0.66 | 0.95 / 0.81 |
| Ranking-group agreement | 0.86 | 0.92 | 0.83 | 0.84 | 0.81 | 0.82 |
| NDCG@10, mean of jobs | 0.90 | 0.94 | 0.90 | 0.92 | 0.85 | 0.92 |
| NDCG@10, worst job | 0.73 (sales) | 0.78 (sales) | 0.83 (nurse) | 0.83 (nurse) | 0.77 (data analyst) | 0.86 (PM) |

Per-job NDCG@10 after: sample nurse 1.00, data analyst 0.96 (0.99 before; ties at 100% coverage now ordered
differently), sales 0.78, backend 1.00. Held-out operations 0.96, teacher 0.94, backend 0.87, nurse 0.83,
PM 0.99. The 300 corpus: UX 0.87, sales 0.96, data analyst 0.87, legal 0.89, teacher 0.96, nurse 0.96,
mechanical 0.96, PM 0.86 (0.87 before).

Ranking-group disagreements on parsed files are now rare: 4 of 340 scored held-out pairs (2 RANKED shown as
MUST_HAVES_UNVERIFIED, 2 the other way), 0 of 220 on the sample and 15 of 1,984 on the 300 corpus. Most of
the remaining ranking loss on both generalisation corpora comes from candidates whose files are scans or
`.txt` and stay UNSCORED (60 of 400 held-out pairs, 416 of 2,400 on the 300 corpus).

Gates: `python -B -m eval.run --check` passes (skill F1 0.998, date accuracy 0.992, kappa 0.971 vs 0.993
before, NDCG 0.977 vs 0.990, flip rate 0, AIR 1.0, proficiency 18/18 and 0 violations); the assistant intent
eval passes (204/204); `pytest` passes in ai-service (474, of which 40 are new in
`tests/test_resumelab_fixes.py`) and agent-service (275). The small kappa drop is deliberate: the synthetic
gold ties EXPERIENCE only to skills written in a role, so it labels "7 years as Key Account Manager" as not
evidencing B2B sales; title inference now says it does. ResumeLab's gold, written from the visible document,
agrees with the new behaviour.

### What changed, per finding

- **W1 headings** (`pipeline/sections.py`). About 50 new lexicon entries ("Professional Journey",
  "Organisational Experience", "Career Goal", "Industrial Training", "Personal Dossier", ...) and a
  head-noun classifier (`classify_heading`) for headings never seen before: the last word names the section,
  generic heads ("Details", "Summary", "Background") defer to their modifier, coordinated headings take the
  strongest kind, and every word must be heading vocabulary, so a bold employer ("Paywise Technologies") or
  a skill ("Sales Presentations") is never a section. Degree, job-title and employer lines cannot be
  headings. Structural fallbacks: a capitals or heading-styled line right above a dated role header opens a
  work history, and an `OTHER` section that holds dated role headers is promoted to EXPERIENCE. Headings
  wrapped over two lines ("CERTIFICATIONS &" / "TRAININGS") are joined. "Strengths" lists are SUMMARY (taxonomy
  skills still count, traits are not unknown skills); "Declaration" is its own section and yields nothing.
- **W2 experience relevance** (`matching/occupations.py`, `matching/engine.py`). An occupation lexicon of
  42 title patterns, matched after seniority and grade words are removed ("Sr. Software Engineer II"
  -> "software engineer", "SDE-2" -> "sde"). Each occupation implies practices, some only with corroboration
  from the role's own block: a Software Engineer or SDE is a backend developer when the block names backend
  technology; Business Development and Inside Sales are B2B sales only with B2B markers (both are B2C in much
  of Indian retail and edtech); an Audit Associate does internal or statutory audit as the block says; a
  Business Analyst or bare Analyst does data analysis only with data tools. Implied practices widen
  EXPERIENCE relevance (and the timeline depth used by ADVANCED/EXPERT), never SKILL evidence. Requirement
  subjects with an alternative the taxonomy cannot see ("supply chain or operations", "product or UX
  design") are evaluated per alternative, and such an alternative must be named by a role's title, not just
  appear in a bullet. Multi-word practices are matched against the role header, not words scattered over
  bullets ("designed the product page" is not product design experience); this removed most false positives
  on the 300 corpus (EXPERIENCE precision 0.74 -> 0.98).
- **W3 layouts** (`pipeline/ingest.py`, `sections.py`, `deidentify.py`). Sidebar PDFs: the main column of
  every page is read first and all sidebar text after the whole main text. A label/value table ("Father's
  Name : ...") or a right-aligned date or rating column is no longer taken for a second column. A main-column
  title merely outdented into the gutter is not a full-width line. Table rows whose first cell is a section
  label ("TECHNICAL SKILLS | ...", "COURSES & CERTIFICATIONS | ...") start that section. Wrapped certificate
  titles become one CERTIFICATION fact. When the header block has no name, a name-like line (or one-word lines)
  that the email or profile handle spells is redacted, so the name never becomes a skill or evidence.
- **Roles** (`pipeline/timeline.py`). Key-value blocks ("Organisation : / Designation : / Duration :"),
  date-first layouts decided once per section (a bare date line no longer borrows the previous role's
  "Tools & Technologies:" line), a title or employer on the line after "Employer  dates" / "Title  dates", an
  employer line above a title-only anchor, "Registered Nurse - X" and "Design Engineer | X" (title words that
  look like verbs), and "VP - Product" / "Head - Sales" kept whole. Career-break lines and project sub-blocks
  ("Project : / Client : / Duration :") are not roles.
- **W4 internships**. Internship headings are recognised (lexicon and classifier) and every role under them,
  or with an intern/trainee/articled title, carries `attributes.internship = true`. Internships count as
  experience in `totalMonths`, as the gold expects.
- **W5 languages** (`pipeline/extract.py`, `taxonomy.py`). Languages remain SKILL facts in the `LANGUAGE`
  domain (the existing contract; there is no separate fact type). Personal details yield only the languages
  line ("Languages Known : English, Hindi"); nothing else in them becomes a fact. Rating glyphs (●○★☆■□)
  end list items, and a rated item ("English | ●●●○○") is read as a list wherever the layout put it.
- **W6 preferences** (`matching/preferences.py`). "Bachelor's degree; MBA preferred", "B.Tech, preferably in
  Computer Science" and "(MBA is a plus)" are split. The matcher evaluates only the required part; the rules
  compiler emits a mandatory requirement plus an optional one for each preference.
- **W7 qualifications** (`pipeline/education.py`). CA, CS, CMA/ICWA and CFA rank with master's degrees
  (intermediate stages such as "CA Inter", "CS Executive" or "CFA Level II" do not; "San Jose, CA" is not a
  CA); ACCA and CPA, BHM/BHMCT, B.Stat/M.Stat, BSW/MSW, BJMC/MJMC, BAMS/BHMS, BVSc, B.Voc, M.Arch and MPH are
  recognised; "B.E.," and "B.A.," followed by a comma now count. School lines include 10+2, PUC, SSLC, AISSCE,
  ISC/ICSE/CBSE rows and "Std XII". A degree still in progress (`inProgress`) is UNCLEAR against a degree
  requirement instead of MATCHED.
- **W8 phrases** (`pipeline/ellipsis.py`, `skills.json`). Coordination ellipsis is restored in resumes and in
  requirement text ("field and inside sales", "B2B or SaaS sales"). New aliases: Online Classes, A/B Test,
  Journey Maps, Influencer Campaigns, GTM Plan. New skills: STAAD.Pro, ETABS, Primavera P6, CATIA,
  ANSYS, Creo, GD&T, PPAP, FMEA, CNC Programming, Zendesk, Freshdesk, Miro and Framer (both ambiguous, with
  context words), LinkedIn Recruiter, Naukri RMS. Labelled lists inside roles ("Areas handled: Oracle, SAP
  MM, Excel") count as lists, so ambiguous names in them are accepted. Bullets "»", "›" and table-cell dashes
  ("| – Go") are stripped.
- **W9 false positives**. A skill word inside a role's employer name, an issuer ("The Linux Foundation") or a
  contact label ("Github: [link]") is not a skill. "Git/GitHub" still yields GitHub, which ResumeLab's gold
  does not list.
- **W10 furniture and colour**. Page numbers, "Name - Resume | 1/2" running lines and lines repeated at the
  top or bottom of several pages are dropped before sectioning. Text that merely runs past the right page edge
  (a long URL) is excluded without a HIDDEN_TEXT flag; white-on-colour bands were already handled.

### Remaining gaps

- **Scans and `.txt` (W11).** Unchanged: scans need `OCR_ENABLED=true` with `requirements-ocr.txt`, and
  `.txt` stays unsupported to match the backend. They cause most of the remaining loss in "all files" recall,
  date accuracy and NDCG (for example the held-out nurse job, 0.83).
- **Gold disagreements kept on purpose.** ResumeLab counts Business Analyst and Product Analyst roles as
  product-management experience (7 of the 18 remaining EXPERIENCE misses on the 300 corpus), and generic
  "Legal Executive"/"Senior Associate" roles as corporate-law experience (4 more); the matcher asks for the title or
  block to show the practice. "Mathematics" counts as related to computer science and "Commerce" to economics
  for "or related field", which ResumeLab's narrower field families do not (most EDUCATION false positives).
- **Ambiguous short forms.** "GTM" (go-to-market or Google Tag Manager), "Lean", "Oracle SCM", "CBSE" as a
  curriculum and bare "inventory" are not read as skills.
- **"Mentored" for Mentoring.** Adding the alias makes `tools/resumelab`'s template lint fail
  (`test_templates_never_mention_undeclared_skills`): the `qa` and `teacher` professions write "Mentored ..."
  without declaring `[[Mentoring|Mentored]]`, while other professions declare it. The alias was left out so the
  ResumeLab suite stays green; it should be added together with that two-line template fix by the ResumeLab
  owner.
- **Layout leftovers.** Two-column regions at the bottom of `creative_color` pages still interleave
  education and languages lines (languages are found, education evidence can be noisy); `table_grid` PDFs
  can still lose a role when the label column wraps (7 of the 13 PDF/DOCX resumes on the 300 corpus whose
  role count differs from gold; 3 are sidebar PDFs); one
  `creative_color` resume on the 300 corpus still raises a false HIDDEN_TEXT flag.
- **Title lexicon coverage.** The occupation lexicon covers the 21 ResumeLab professions and common Indian
  titles; uncommon titles fall back to the earlier skill- and token-based rules (no regression, but no
  title inference either). It is English-only.
