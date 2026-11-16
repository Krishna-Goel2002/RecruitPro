# Bug hunt R4 - AI service (resume parsing, evidence-ladder-4 matching, requirement compiler, generators)

Branch `Kshitij-redesign`, 16 Nov 2026, not committed. Scope: `ai-service/**`, plus the taxonomy re-sync into
`agent-service/app/taxonomy` through `tools/sync_taxonomy.py`.

Method: every module read line by line; adversarial resumes built for non-technical roles (staff nurse, PGT/TGT
teacher, B2B sales, product designer, Indian accountant with an "OCR-style" layout, DOCX tables, HTML/RTF saved as
`.doc`, renamed files, vector-outline PDFs); a mutation fuzzer over PDF/DOCX/DOC inputs and the Word 97 piece
table; HTTP probes of the middleware; side-by-side runs of the old and new requirement compiler. Every finding
has a regression test in `ai-service/tests/test_bug_hunt_r4.py` (test names carry the id).

## Gate results

| Gate | Result |
|---|---|
| `cd ai-service && python3 -B -m pytest -q` | **259 passed** (186 before; 73 new in `tests/test_bug_hunt_r4.py`) |
| `cd ai-service && python3 -B -m eval.run --check` | **CHECK PASSED** - skill F1 0.998 (precision 0.9979 -> 0.9987), date accuracy 0.992, kappa 0.993, NDCG@10 0.990, flip rate 0, AIR 1.0, proficiency cases 18/18, invariant violations 0 |
| `python3 tools/sync_taxonomy.py --check` | in sync (skills.json and taxonomy.py re-vendored) |
| `cd agent-service && python3 -m pytest -q` | **273 passed** |
| `python3 -B audit/probe_matching.py` | runs; all probes behave as before |
| `pip install --dry-run -r requirements.txt` | resolves (with the new security floors) |
| Docker image build | **not run** (no Docker in this environment) |

No eval data was changed. Two existing assertions were changed on purpose and are justified in the table
(R4-38 "strong" is no longer ADVANCED; R4-37 OTHER requirements are never knockouts, the backend's R2-H01 rule).

## Severity counts

| Severity | Found | Fixed |
|---|---|---|
| High | 14 | 14 |
| Medium | 30 | 30 |
| Low | 14 | 14 |
| **Total** | **58** | **58** |
| Not fixed (documented, see end) | 10 (3 Medium, 7 Low) | - |

"Kshitij?" says whether the demo branch `Kshitij` has the same defect: the parsing, compiler, generator and
platform code is identical on both branches; `Kshitij` has no proficiency rule in matching (evidence-ladder-3),
so the compiler's proficiency only matters on `Kshitij-redesign`.

## Findings

| Id | Sev | File:line (after fix) | Problem | Fix | Test | Kshitij? |
|---|---|---|---|---|---|---|
| R4-01 | Med | `app/pipeline/text.py:16,58` | Soft hyphens, zero-width and bidi characters inside words were kept or turned into spaces: "Py­thon" was not Python (keyword obfuscation, Word/PDF artefacts). | Invisible format characters are removed before NFKC. | `test_r4_01` | Yes |
| R4-02 | Med | `app/pipeline/text.py:20` | PDF/Word bullet glyphs (U+F0B7, U+F0A7 private-use, "o" sub-bullet, "->", bullets without a following space) were not recognised: list items lost list context, so ambiguous skills ("Go") in bullets were missed. | Bullet pattern covers private-use glyphs, arrows, glued bullets and "o " before a capital. | `test_r4_02` | Yes |
| R4-03 | High | `app/pipeline/text.py:111` | Stemmer stripped a plural "s" twice: "nursing" -> "nur" but "nurse" -> "nurs". An EXPERIENCE requirement "Nursing, 3 years" never matched "Staff Nurse" roles (8.5 years) - UNCLEAR for every nurse. Same for housing/house etc. | Plural endings are stripped only in the first pass. | `test_r4_03`, `test_r4_33` | Yes |
| R4-04 | High | `app/pipeline/ingest.py:510-560` | DOCX tables were read cell by cell: "Staff Nurse \| Apollo \| Jan 2018 - Mar 2021" became three lines, so the title was lost and the second row's role merged into the first. | Rows whose cells hold one line each are read as one line joined by " \| " (layout tables still cell by cell); lxml proxies kept alive so row identity is stable. | `test_r4_04` | Yes |
| R4-05 | Med | `app/pipeline/ingest.py:616` | First-page and even-page headers/footers were ignored: contact blocks kept in a "different first page" header produced no EMAIL/PHONE facts. | All displayed header/footer variants are read. | `test_r4_05` | Yes |
| R4-06 | Med | `app/pipeline/ingest.py:597-601` | A header that is defined but never displayed (first-page header without "different first page") is invisible text that could hide keywords. | Its text counts as hidden text (`HIDDEN_TEXT`, stuffing check) unless it repeats a displayed header. | `test_r4_06` | Yes |
| R4-07 | High | `app/pipeline/ingest.py:94,116` | Format came from the extension only: a DOCX renamed `.doc`, a PDF uploaded as `.docx`, or a PDF with a BOM/junk before `%PDF-` all failed as MALFORMED. Very common with real uploads. | Magic-byte sniffing (PDF header anywhere in the first KB, ZIP, OLE2, RTF, HTML). | `test_r4_07` | Yes |
| R4-08 | Med | `app/pipeline/ingest.py:663-745` | HTML saved as `.doc` (old Word/web exports) failed as MALFORMED_DOC. | Read with `html.parser` (not LibreOffice, which would fetch linked resources); `display:none`/`hidden` content is hidden text; table cells joined. | `test_r4_08` | Yes |
| R4-09 | Low | `app/pipeline/ingest.py:747` | RTF saved as `.doc` failed as MALFORMED_DOC. | Converted with LibreOffice when available, otherwise `UNSUPPORTED_FORMAT` (an honest code). Conversion uses an explicit `MS Word 2007 XML` filter. | `test_r4_09`, `test_r4_09b` | Yes |
| R4-10 | Med | `app/pipeline/ingest.py:817` | A truncated Word 97 piece table raised `struct.error` outside the try block: parse_resume raised, the API answered HTTP 500 and the backend retried 5 times. Also an O(n^2) length check over pieces. | Piece-table decoding errors -> `MALFORMED_DOC`; running total. | `test_r4_10` | Yes |
| R4-11 | Med | `app/pipeline/ingest.py:60,329` | PDFs whose text is drawn as vector outlines (Canva/Illustrator exports) were `EMPTY_DOCUMENT` instead of needing OCR. | >= 150 curve paths with < 80 characters of text -> OCR / `NEEDS_OCR`. | `test_r4_11` | Yes |
| R4-12 | Med | `app/pipeline/ingest.py:356` | Any OCR engine exception (odd image, missing model) escaped as MALFORMED_PDF or crashed DOCX parsing. | OCR failures mean `NEEDS_OCR`; an empty OCR result keeps the old behaviour. | `test_r4_12` | Yes |
| R4-13 | Med | `app/pipeline/ingest.py:641,671` | LibreOffice had a 60 s timeout equal to the 60 s parse timeout: the pool killed the worker first and soffice was orphaned (and could run on). | Budget = 0.6 x parse timeout (5-60 s); soffice in its own process group, killed as a group on timeout. | `test_r4_13` | Yes |
| R4-14 | High | `app/pipeline/extract.py:106,115` | `parse_resume` promised "never raises" but only caught `DocumentParseError`; any other reader/analysis error became HTTP 500 and backend retries. | Reader errors -> `FAILED/MALFORMED_<EXT>`; analysis errors -> `FAILED/PARSER_CRASHED` (both already known to the portals). | `test_r4_14` | Yes |
| R4-15 | Med | `app/pipeline/extract.py:366-402` | LLM skill extraction accepted any taxonomy skill whose quote merely existed in the resume ("Kubernetes" quoting "Managed a team of five"), and skills quoted from hobbies/references. | The quote must name the skill (name or alias); hobbies/references/personal sections are skipped, as in the rules. | `test_r4_15` | Yes |
| R4-16 | Med | `app/pipeline/extract.py:124`; `app/compile/requirements.py:445`; `app/generate/*.py` | Wrongly typed but valid JSON from an LLM (`"rubric": 5`, `"followUps": 3`, `"type": ["SKILL"]`) raised TypeError -> HTTP 500 instead of the rules fallback. | Type checks per item plus a broad fallback to the rules result. | `test_r4_16`, `test_r4_45`, `test_r4_47` | Yes |
| R4-17 | Med | `app/pipeline/sections.py:119,215` | A one-per-line skills list containing "Training", "Research" or "Software" started new sections (COURSES, PUBLICATIONS), so later items lost list context. | Heading words that are also list items need heading styling (style/caps/colon) inside a list section and are never headings as bullets. | `test_r4_17` | Yes |
| R4-18 | High | `app/pipeline/sections.py:128,200,222` | "Roles & Responsibilities:" / "Achievements:" inside a work history ended the EXPERIENCE section; every later role (dates, title) was lost from the timeline. Typical in Indian resumes. | A role sub-heading (different styling from the section heading, a dated role still to follow before the next main heading) stays inside the work history; O(n) look-ahead. | `test_r4_18` | Yes |
| R4-19 | Med | `app/pipeline/sections.py:115,236` | "Education: MBA, Symbiosis" inside a work history produced no EDUCATION fact. | Inline EDUCATION/CERTIFICATIONS labels override any section (not "Languages:", which inside a role lists programming languages). | `test_r4_19` | Yes |
| R4-20 | High | `app/pipeline/deidentify.py:37,160` | Names with initials ("K. Lakshmi", "Lakshmi K"), accents ("José Álvarez" - redacted as "[name]é", "Á[name]") or particles ("Rahul de Souza") were not (fully) redacted: names reached blind reviewers and the LLM. | Unicode-aware name words; initials and particles allowed (never redacted alone); the exact name line is redacted too. | `test_r4_20` | Yes |
| R4-21 | Med | `app/pipeline/deidentify.py:108` | ISO/dotted date ranges ("2019-06 - 2023-03", "2019.03 - 2021.06") were detected as phone numbers: a bogus PHONE contact fact and "[phone]" in evidence. | A candidate that parses as a date range is not a phone. | `test_r4_21` | Yes |
| R4-22 | High | `app/pipeline/deidentify.py:255` | Overlapping redactions of different kinds kept only the first: the rest of a longer redaction (e.g. a personal-detail line overlapping the name) stayed visible. | Overlaps merge into the union; identifying kinds win the label. | `test_r4_22` | Yes |
| R4-23 | Low | `app/pipeline/deidentify.py:48,55` | "HIS" (Hospital Information System) and "SHE" (safety, health, environment) became "Their"/"They"; "Dobson", "Borneo" triggered date-of-birth removal. | Pronouns only in lower/title case; word boundary after DOB labels. | `test_r4_23` | Yes |
| R4-24 | Med | `app/pipeline/deidentify.py:49,64,80` | Table-style labels ("Gender \| Female", "Name \| ...") and header address lines without a street word ("Flat 302, Sai Residency, Andheri") leaked. | "\|" accepted as a label separator; header segments with a PIN or a house/flat/plot word are redacted (weak words like "sector" only with a number). | `test_r4_24` | Yes |
| R4-25 | High | `app/pipeline/dates.py:28,94` | DD/MM/YYYY ("01/06/2018 - 31/03/2021"), "15 Jan 2019", "June 15, 2019", "Apr-21" and the minus sign were not parsed: Indian resumes had no timeline at all, so every EXPERIENCE requirement failed. | New date forms (day-first unless impossible), minus sign as separator. | `test_r4_25`, `test_r4_32` | Yes |
| R4-26 | Med | `app/pipeline/dates.py:44,145` | "Summer 2019 - Fall 2020" and "2016 - 18" were not ranges ("(Summer" was left in the job title). | Seasons and abbreviated end years ("2019-11" stays November). | `test_r4_26` | Yes |
| R4-27 | Med | `app/pipeline/dates.py:129` | "Top performer FY 2019-20" would become a new role anchor (fiscal year read as employment). | Fiscal/academic-year labels are not date ranges. | `test_r4_27` | Yes |
| R4-28 | Low | `app/pipeline/dates.py:55` | "six months" and "2 yrs, 6 months" were not durations (30 months read as 24). | Number words for months, comma between parts. | `test_r4_28` | Yes |
| R4-29 | Med | `app/pipeline/education.py:19-58` | "B.E Computer Science", "M.S in Data Science", "B.A English" (no final dot) were not degrees. | Dotted abbreviations without the last dot. | `test_r4_29` | Yes |
| R4-30 | High | `app/pipeline/education.py:38,52` | A bare "Master" was a master's degree: "Certified Scrum Master" became an EDUCATION fact (MATCHED "Master's degree") and the compiler turned "Scrum Master certification" into an EDUCATION requirement. | "Master"/"Bachelor" need degree context ("'s", "of", "in", "degree", "Bachelor/Master"). | `test_r4_30`, `test_r4_43` | Yes |
| R4-31 | Low | `app/pipeline/education.py:61-64` | D.El.Ed / D.Ed (Indian teacher diplomas), D.Pharm and ITI were not detected. | Added with their fields. | `test_r4_31` | Yes |
| R4-32 | Med | `app/pipeline/timeline.py:34,151,168` | Employer "XYZ Traders Pvt Ltd from" (connective left by the date), "L&T" dropped as employer, many non-technical titles (PGT, TGT, midwife, chef, ...) unknown. | Trailing connectives trimmed; two-letter employers kept; title list extended (surnames avoided). | `test_r4_32` | Yes |
| R4-33 | High | `app/matching/engine.py:47,205,371` | Practitioner titles that do not contain the field word ("PGT Mathematics" for Teaching) were irrelevant; "Two years of nursing experience" kept "two" as a topic word. | Title words plus implied field (PGT/TGT/lecturer/professor -> teaching, midwife -> nursing, ...); number words stripped from the subject. | `test_r4_33`, `test_r4_34` | Yes |
| R4-34 | Med | `app/matching/engine.py:152,157` | Fact/timeline attributes were trusted: `months: true` counted as 1 month, `months: -5` gave "a shorter duration (-0.4 years)", "2019-13" shifted the year. | Only positive int months (not bool), months 1-12. | `test_r4_35` | Yes |
| R4-35 | Med | `app/models/matching.py:23,48,86` | Duplicate requirement ids were accepted: weights and work-sample/interview links keyed by id silently mixed two requirements. | 422 on duplicate ids; alternatives limited to 500 characters each. | `test_r4_36` | Yes |
| R4-36 | Low | `app/models/authoring.py:64`, `app/models/resume.py` | `focusRequirementIds` and timeline role skills unbounded. | Length limits. | (validation) | Yes |
| R4-37 | Med | `app/matching/engine.py:758` | An OTHER requirement marked knockout was always "unverified", putting every applicant in MUST_HAVES_UNVERIFIED (the backend already strips this, R2-H01; direct callers did not). | OTHER never counts as a knockout. Existing test updated to the backend rule. | `test_r4_37`, `test_knockouts_are_gates_not_rejections` | Yes |
| R4-38 | High | `app/compile/requirements.py:77-258` | **Proficiency too aggressive.** "strong", "solid"... mapped to ADVANCED, and the word applied to every skill in the sentence. With evidence-ladder-4, ADVANCED needs >= 3 years per skill / CORROBORATED / a level word, so most compiled matches became UNCLEAR. On a 25-line JD sample 16/35 skill requirements (46 %) were ADVANCED/EXPERT; now 9/35 (26 %), all with an explicit depth word. | Calibrated levels (see decision below); the level word must qualify the skill (same clause, only filler or other skills in between, or right behind it "Excel (advanced)"). Existing test updated ("strong PostgreSQL" -> INTERMEDIATE). | `test_r4_38`, `test_r4_39` | Field set on both; matching effect only on Kshitij-redesign |
| R4-39 | High | `app/compile/requirements.py:261,317` | "Age between 25 and 35 years" became an EXPERIENCE requirement with minimumYears 35 and no warning; "Female candidates preferred", "Freshers only", "Unmarried candidates only", "fair complexion" became OTHER requirements without warnings. | Protected-characteristic wording never becomes a requirement (LLM path too) and always gets a warning; new warnings for preferences, religion/caste, appearance, freshers-only, "salesmen"/"office boy". | `test_r4_40`, `test_r4_44` | Yes |
| R4-40 | Med | `app/compile/requirements.py:61,324` | "Less than 10 years of experience" became minimumYears 10 (inverted) and was mis-flagged as an age limit. | A cap becomes OTHER (not a minimum) with a specific age-proxy warning; real age limits are still flagged. | `test_r4_41` | Yes |
| R4-41 | Med | `app/compile/requirements.py:285,333` | "Master's degree with 3+ years of experience in Python" produced only the EDUCATION requirement; the experience ask was dropped. | Split into EDUCATION + EXPERIENCE. | `test_r4_42` | Yes |
| R4-42 | Low | `app/compile/requirements.py:52,220` | "Two years of experience in Tally" became OTHER; "Valid nursing license (RN)" lost its closing bracket. | Number words in years; bracket balancing. | `test_r4_43` | Yes |
| R4-43 | Med | `app/compile/requirements.py:405-427` | LLM requirements: `"mandatory": "false"` was truthy, a real quote unrelated to the description was accepted, OTHER could be a knockout. | Strict booleans, description must share a content word with its quote, OTHER never knockout. | `test_r4_44` | Yes |
| R4-44 | Med | `app/generate/work_samples.py:26-71` | Rules work samples told candidates to use "the summarised figures provided", "the clause provided", "the written patient scenario provided", "the stated class" - material nobody attaches. | Self-contained scenarios with concrete material. | `test_r4_46` | Yes |
| R4-45 | Low | `app/generate/*.py` | LLM rubric/questions accepted `requirementId: true` (bool is int). | Bool and non-int ids rejected. | `test_r4_47` | Yes |
| R4-46 | Low | `app/generate/work_samples.py:124` | `jobDescription` was accepted but never used. | Sent to the LLM (contacts scrubbed, 4000 characters). | `test_r4_48` | Yes |
| R4-47 | Med | `app/core/workers.py:38,52` | A timeout in one request terminated the whole pool: every other in-flight parse failed with PARSER_CRASHED; a later timeout could also tear down a fresh pool. | Discard only the pool the job ran on; a collateral BrokenProcessPool is retried once within the deadline. | `test_r4_49` | Yes |
| R4-48 | Low | `app/core/config.py:43,74` | `PARSE_WORKERS=two` crashed every request with ValueError; zero/negative timeouts and upload limits above the 10 MiB parser cap were accepted. | Clear ConfigError at startup; range checks. | `test_r4_50` | Yes |
| R4-49 | Low | `app/core/auth.py:43,74` | Websocket scopes under `/api/` bypassed the token check (no routes today); after a body overflow the receive wrapper read again. | Websockets need the token (else close 1008); overflow stays cut off. | `test_r4_51` | Yes |
| R4-50 | Low | `eval/metrics.py:38`, `eval/run.py` | A group with no members got selection rate 0, failing AIR spuriously. | Empty groups have no rate and are ignored. | `test_r4_52` | Yes |
| R4-51 | Med | `app/taxonomy/skills.json` (rust, ruby, scala, kafka, dart, elixir, perl, jenkins, airflow, postman) | Ordinary words were tech skills anywhere: "Rust removal", "Ruby anniversary", "Kafka on the Shore", "postman at India Post", "Mr. Jenkins". | Marked ambiguous with context words (still counted in skills lists and in technical context). Re-synced to agent-service. | `test_r4_53`, `test_r4_54` | Yes |
| R4-52 | High | `app/taxonomy/skills.json:140` | Alias "SOLID" (aliases are case-insensitive): "solid understanding of accounts" was the skill SOLID Principles; the compiler created a "SOLID Principles" requirement from "Solid communication skills". | Alias replaced by "SOLID design principles" / "SOLID object-oriented design". | `test_r4_53` | Yes |
| R4-53 | Low | `app/taxonomy/taxonomy.py:38` | ASCII-only tokens: "Excelência" contained the skill Excel. | Unicode word tokens (re-synced). | `test_r4_55` | Yes |
| R4-54 | Med | `app/pipeline/injection.py:24,37` | Visible "designed system prompts" / "tested for prompt injection" flagged AI and security professionals as PROMPT_INJECTION_SUSPECTED. | Visible text: only commands ("reveal your system prompt") and chat-template tags; bare mentions only in hidden text. | `test_r4_56` | Yes |
| R4-55 | High | `app/pipeline/deidentify.py:33,74` | The street-address and phone patterns used `\s`, so "12 Main Road, Cuttack" ran on into the next lines: the following heading and skills line ("Skills" / "Tally, GST") were redacted and those skills silently dropped. | Address and phone patterns stay on one line. | `test_r4_57` | Yes |
| R4-56 | Med | `app/pipeline/deidentify.py:49-61` | The same `\s` let personal-detail/DOB/address labels swallow unrelated next lines ("first-born" + next line); yet a value on the next line ("Date of Birth:" / "12/05/1994") must still go. | Label patterns may continue onto exactly one next line after an explicit label, never further; bare "born" stays on its line. | `test_r4_58` | Yes |
| R4-57 | Low | `requirements.txt` | Security-relevant transitive dependencies (h11, cryptography, Pillow) were unpinned and unbounded. Pinned direct versions have no known CVEs as far as could be checked offline (starlette 0.50.0 > 0.49.1, python-multipart 0.0.22, pdfminer.six 20251230 include the 2025 fixes). | Floors h11 >= 0.16.0 (CVE-2025-43859), cryptography >= 44.0.1 (CVE-2024-12797), Pillow >= 11.3.0. `pip install --dry-run` resolves. | dry run | Yes |
| R4-58 | Low | `Dockerfile:26,45-47` | The non-root user owned `/app` (could rewrite the service code); its home was `/app`. | Code root-owned and read-only; separate writable `/home/app` (LibreOffice/fontconfig caches). Kept another reviewer's concurrent edits (SOFFICE_PATH auto-detect, `--no-server-header`). | not built here | Yes (Kshitij has the old chown) |

Some ids group two closely related defects in the same function (e.g. R4-23, R4-32); each id is counted once.

## Decision: requirement proficiency (the "74 % become UNCLEAR" risk)

* The matching rule (evidence-ladder-4) is unchanged: ADVANCED needs >= 36 months of relevant roles per skill, tier
  CORROBORATED or a level word; EXPERT >= 60 months, DEMONSTRATED or "expert". It is a deliberately conservative
  proxy, and UNCLEAR is the honest answer when depth is not shown.
* The aggressive part was the **compiler**, which decides how often that rule applies. Job ads use "strong",
  "solid", "good", "excellent", "hands-on", "proficient" as filler for "can do the job"; they are now INTERMEDIATE
  (no effect on matching). ADVANCED needs an explicit depth word (advanced, in-depth, deep, extensive, expertise,
  highly proficient); EXPERT needs "expert"/"mastery"/"subject-matter expert". A level word only applies to the
  skills it is written next to.
* Effect on a 25-line sample of typical JD requirement lines: ADVANCED/EXPERT went from 16 of 35 compiled skill
  requirements to 9 of 35. The synthetic `proficiency_downgrade_share` (0.73 ADVANCED, 0.95 EXPERT) is unchanged
  because the harness re-matches *every* skill requirement as ADVANCED/EXPERT; it measures the rule, not the
  compiler, and stays informational.
* HR still sees and can edit every suggested proficiency; README "Where proficiency comes from" documents this.

## Not fixed (and why)

| Id | Sev | Area | Issue | Why not fixed |
|---|---|---|---|---|
| U1 | Med | `app/pipeline/ocr.py` | OCR rows are grouped by y only, so a two-column scan interleaves the columns. | Needs layout analysis on OCR boxes; OCR is optional and off by default. |
| U2 | Med | `app/taxonomy/import_esco.py` | ESCO single-word labels are imported as unambiguous skills (false positives) when `TAXONOMY_EXTRA_PATH` is used. | Not used by default; needs per-label context words from ESCO descriptions. |
| U3 | Low | education | School levels (10th/12th, HSC) have no degree level, so "12th pass" requirements fall back to lexical matching (UNCLEAR). | Adding a level changes the `degreeLevel` contract shared with the backend. |
| U4 | Low | de-identification | Names that are not in the header (referees, managers) are not redacted. | Documented limitation; needs NER. |
| U5 | Med | LibreOffice | `.doc`/`.rtf` conversion may try to fetch resources linked from the document. | No per-file switch; mitigated by running in a container without a network route (compose). HTML is no longer sent to LibreOffice. |
| U6 | Low | PDF | Text hidden by render mode 3 or behind images is not flagged. | Deliberate: OCR'd ("searchable") scans store their text exactly that way. |
| U7 | Low | numeric dates | 01/06/2018 is read day-first (1 June); a US resume with ambiguous dates is read wrongly. | Documented assumption for an India-first product; unambiguous dates are handled both ways. |
| U8 | Low | certifications | "CA Inter cleared" can match a "CA" requirement via acronym expansion. | Needs a list of partial-qualification qualifiers; rare and visible in the cited evidence. |
| U9 | Low | dev dependency | pytest 8.4.2 is not the newest (9.x exists). | Dev-only; no advisory confirmed offline; a major bump is out of scope. |
| U10 | Low | Dockerfile | Image not built in this environment. | No Docker available; changes are minimal (ownership, HOME). |
