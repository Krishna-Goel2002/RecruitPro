# WP-BE-C notes: verification modules, fairness and privacy

Status: implemented, not yet compiled. This environment has no JDK and no Maven Central access. The first `./mvnw verify` runs on a developer machine or in CI.

Scope (plan §9): V18–V20; work samples (HR and candidate); interview kits, interviews, scorecards and interviewer endpoints; evidence contributors for tiers 3–4; the voluntary survey and fairness reports; job audit export; candidate data export and account deletion; removal of date of birth and gender from the profile. Findings closed: M15, D14.

| Gate | Result |
|---|---|
| G-SQL `python3 -B tools/check_migrations_pg.py` | V1–V20 apply to PostgreSQL 16 |
| `python3 -B tools/check_entity_schema.py` | 300 mapped columns of 26 entities, 0 errors, 0 warnings |
| G-JAVA-STATIC `python3 -B tools/java_static_check.py backend/src` | 0 errors in 384 files |
| G-JAVA-REVIEW | My own line-by-line pass, then one independent reviewer agent: no compile errors; one runtime bug and three hardening points, all fixed (§9) |

Conventions as in WP-BE-B: the envelope is `{success, message?, data?, errors?, timestamp}` and timestamps are UTC ISO strings ending in `Z`. **Difference:** every new response record is annotated `@JsonInclude(ALWAYS)`, so its `x | null` fields arrive as explicit `null` (plan §5 shapes), not absent. Nested WP-BE-B types (for example `RequirementEvidence` inside `InterviewDetail`) still omit nulls.

---

## 1. Endpoints as implemented

### 1.1 Work samples (`assessment`)

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| POST | `/api/v1/hr/jobs/{jobId}/work-samples/generate` | ADMIN, HR_MANAGER | → `WorkSample` (DRAFT). Calls the AI service outside any transaction. **503** when the service is unreachable, returns 5xx/408/429, or has no token; **400** on other 4xx. Any job except CANCELLED (closed jobs may still have applicants in progress). |
| GET | `/api/v1/hr/jobs/{jobId}/work-samples` | ADMIN, HR_MANAGER, RECRUITER | → `WorkSample[]` by id |
| PUT | `/api/v1/hr/jobs/{jobId}/work-samples/{id}` | ADMIN, HR_MANAGER | `{title ≤255, instructions ≤20000, timeLimitMinutes 1..480, rubric: RubricCriterion[1..30]}` → `WorkSample`. **DRAFT only** (else 400). Criterion 1..500 chars; all four anchors 1..1000 chars; `requirementId` null or a requirement of this job. |
| PATCH | `/api/v1/hr/jobs/{jobId}/work-samples/{id}/status` | ADMIN, HR_MANAGER | `{status}` → `WorkSample`. Allowed: DRAFT→ACTIVE/ARCHIVED, ACTIVE→ARCHIVED, ARCHIVED→ACTIVE. Back to DRAFT only while the sample has no assignments. Same status = no-op. Activation needs instructions and a complete rubric (no "Not described yet" placeholder anchors). |
| POST | `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/work-samples` | ADMIN, HR_MANAGER | `{workSampleId, dueAt?}` → `WorkSampleAssignment` (ASSIGNED). Rules: application SHORTLISTED or INTERVIEWING (row locked like a decision), sample ACTIVE and of this job (another job's sample → 404), `dueAt` in the future, one assignment per sample and application (**409**). Sends WORK_SAMPLE_ASSIGNED to the candidate. |
| GET | `/api/v1/hr/jobs/{jobId}/work-sample-assignments?status` | ADMIN, HR_MANAGER, RECRUITER | → `WorkSampleAssignment[]`, newest first; an unknown `status` → 400 |
| POST | `/api/v1/hr/work-sample-assignments/{id}/score` | ADMIN, HR_MANAGER, RECRUITER | `{scores:[{requirementId, criterion, score 1..4, note? ≤2000}]}` → `WorkSampleAssignment` (SCORED). SUBMITTED only (else 400). Exactly one score per rubric criterion, matched by criterion text (case-insensitive). **The `requirementId` is taken from the stored rubric, never from the request.** Audited as WORK_SAMPLE_SCORED; queues MATCH_APPLICATION. |
| GET | `/api/v1/candidates/me/work-samples` | CANDIDATE | → `CandidateWorkSample[]`, newest first |
| POST | `/api/v1/candidates/me/work-samples/{assignmentId}/start` | CANDIDATE | → `CandidateWorkSample` (STARTED, instructions revealed). ASSIGNED only; idempotent once STARTED. 400 after `dueAt` or when the application is no longer SHORTLISTED or INTERVIEWING. Another candidate's assignment → 404. |
| POST | `/api/v1/candidates/me/work-samples/{assignmentId}/submit` | CANDIDATE | `{responseText 1..50000, responseUrl? http(s) ≤500}` → `CandidateWorkSample` (SUBMITTED). Accepted until `startedAt + timeLimit + 5 min`. A later submission is **400** and the assignment becomes EXPIRED; that change is committed. |

```ts
type WorkSample = { id; jobId; title; instructions; timeLimitMinutes; rubric: RubricCriterion[];
  status: 'DRAFT'|'ACTIVE'|'ARCHIVED'; createdAt; updatedAt }
type RubricCriterion = { requirementId: number|null; criterion: string; anchors: {'1','2','3','4'} }  // keys always "1".."4"
type WorkSampleAssignment = { id; workSampleId; workSampleTitle; applicationId; status; assignedAt; dueAt|null;
  startedAt|null; submittedAt|null; responseText|null; responseUrl|null;
  scores: {requirementId|null, criterion, score, note|null}[] /* [] until scored, rubric order */;
  totalScore: number|null /* mean of the scores, 1.00–4.00 */; scoredAt|null; scoredByName|null }
type CandidateWorkSample = { assignmentId; jobTitle; title; instructions: string|null /* null until started */;
  timeLimitMinutes; status; dueAt|null; startedAt|null; submittedAt|null;
  deadlineAt: string|null /* startedAt + timeLimit, without the grace */ }
```

Generated drafts keep only rubric links to requirements of this job. Missing anchors become "Not described yet", so the sample cannot be activated until they are written.

**Expiry sweep:** `WorkSampleExpirySweeper` runs every 60 s and marks assignments EXPIRED with a conditional update:
- STARTED assignments past `startedAt + limit + 5 min`;
- ASSIGNED assignments past `dueAt`.

`dueAt` is the latest time the candidate may **start**. Once started, the time limit applies.

### 1.2 Interviews (`interview`)

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| GET | `/api/v1/hr/jobs/{jobId}/interview-kit` | ADMIN, HR_MANAGER, RECRUITER | → `InterviewKit`; **404 "This job has no interview kit yet"** when none (as the HR portal assumes) |
| PUT | `/api/v1/hr/jobs/{jobId}/interview-kit` | ADMIN, HR_MANAGER | `{questions: InterviewQuestion[0..60]}` → `InterviewKit` (`generatedBy: "manual"`). Question 1..2000 chars; ids ≤40 and unique (blank ids get `q<n>`); ≤10 follow-ups of ≤1000 chars (blank ones dropped); four anchors; `requirementId` null or of this job. |
| POST | `/api/v1/hr/jobs/{jobId}/interview-kit/generate` | ADMIN, HR_MANAGER | → `InterviewKit` (`generatedBy` = AI engine, `"rules"` or `"llm"`). Replaces the kit. 503/400 as for work samples. |
| POST | `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews` | ADMIN, HR_MANAGER | `{interviewerUserId, scheduledAt (future), durationMinutes 5..480, location? ≤500}` → `Interview` (SCHEDULED). See rules below. |
| GET | `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}/interviews` | ADMIN, HR_MANAGER, RECRUITER | → `Interview[]`, soonest first |
| PATCH | `/api/v1/interviews/{id}` | ADMIN, HR_MANAGER | `{scheduledAt?, durationMinutes?, location?, status?: 'CANCELLED'}` → `Interview`. SCHEDULED only (else 400). Absent = unchanged; `location: ""` clears. With `status: CANCELLED` the other fields are ignored; other statuses → 400. A changed time or place re-sends INTERVIEW_SCHEDULED and INTERVIEW_ASSIGNED; a cancellation sends **INTERVIEW_CANCELLED** (new template) to both. |
| GET | `/api/v1/interviews/mine` | ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER | → `Interview[]` assigned to the caller in their organisation, soonest first |
| GET | `/api/v1/interviews/{id}` | same | → `InterviewDetail`. Allowed for the assigned interviewer or ADMIN/HR_MANAGER; other staff of the organisation → **403**; another organisation → **404**. |
| POST | `/api/v1/interviews/{id}/scorecard` | same | `{ratings:[{questionId ≤40, requirementId?, rating 1..4, notes? ≤2000}] (0..100), recommendation, summary? ≤4000}` → `Interview` (COMPLETED). See rules below. |
| GET | `/api/v1/candidates/me/interviews` | CANDIDATE | → `{id, jobTitle, scheduledAt, durationMinutes, location|null, status}[]` |

Scheduling rules:
- The application must be SHORTLISTED or INTERVIEWING; its row is locked.
- The interviewer must be an enabled, non-deleted staff user of the job's organisation. Otherwise **400** (a user of another organisation gets the same 400).
- If the application is SHORTLISTED, the service first calls `ApplicationDecisionService.decide(MOVE_TO_INTERVIEW)` in the same transaction. That writes the history row with `match_run_id`, the APPLICATION_DECISION audit and APPLICATION_STATUS_CHANGED.
- Then it sends INTERVIEW_SCHEDULED to the candidate and INTERVIEW_ASSIGNED to the interviewer.

Scorecard rules:
- Only the assigned interviewer may submit; anyone else in the organisation → **403**.
- A CANCELLED interview → 400; a second scorecard → **409**; a withdrawn application → 400.
- Each rating's requirement link is **taken from the job's kit** (question id → requirementId). The client's `requirementId` must be null or a requirement of the job (else 400), but it is not trusted.
- Audited as SCORECARD_SUBMITTED; queues MATCH_APPLICATION.

```ts
type InterviewQuestion = { id; requirementId|null; question; followUps: string[]; anchors: {'1','2','3','4'} }
type InterviewKit = { jobId; questions: InterviewQuestion[]; generatedBy: string; updatedAt }
type Scorecard = { ratings: {questionId, requirementId|null, rating, notes|null}[]; recommendation; summary|null; submittedAt }
type Interview = { id; applicationId; jobId; jobTitle; candidateName /* blind label until revealed */; interviewerUserId;
  interviewerName /* "Former staff member" if the user row is gone */; scheduledAt; durationMinutes; location|null;
  status: 'SCHEDULED'|'COMPLETED'|'CANCELLED'; scorecard: Scorecard|null }
type InterviewDetail = Interview & { kit: InterviewQuestion[] /* [] without a kit */; focusRequirements: RequirementEvidence[] }
```

`focusRequirements` lists every current requirement, with evidence from the current match run (the same mapping as ApplicantDetail), in this order:
1. requirements without verified evidence first (status ≠ MATCHED, or tier SELF_CLAIMED or none);
2. within each group: knockouts, then must-haves, then weaker tier first, then by id;
3. verified requirements last.

### 1.3 Applicant views (`ApplicationDetailExtension` beans)

`WorkSampleDetailExtension` (`@Order(10)`) and `InterviewDetailExtension` (`@Order(20)`) feed these fields:

| Field | Content |
|---|---|
| `ApplicantSummary.workSampleStatus` | status of the latest assignment that is not CANCELLED (CANCELLED only if all are) |
| `ApplicantSummary.interviewCount` | interviews that are not CANCELLED |
| `ApplicantDetail.workSamples` | `WorkSampleAssignment[]`, with response, scores and notes |
| `ApplicantDetail.interviews` | `Interview[]` with scorecards; RECRUITER sees them too, see §7 |
| `ApplicationDetail.workSamples` (candidate) | `CandidateWorkSample[]` |
| `ApplicationDetail.interviews` (candidate) | `{id, jobTitle, scheduledAt, durationMinutes, location, status}[]` |

The candidate views never contain scores, the rubric, anchors, notes, recommendation or the interviewer. Tests assert this on the raw JSON.

### 1.4 Fairness (`fairness`)

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| GET | `/api/v1/candidates/me/equal-opportunity` | CANDIDATE | → `{gender, ageBand, disability, socialCategory, consentGivenAt}`; **404** when nothing is stored (the candidate portal maps 404 to null) |
| PUT | same | CANDIDATE | `{gender?, ageBand?, disability?, socialCategory?, consent: true}` → same shape. Without `consent: true` → 400; values outside plan §5.5 → 400 (case-insensitive; stored upper case). Replaces the stored answers, and `consentGivenAt` is refreshed on every save. |
| DELETE | same | CANDIDATE | → `null` (idempotent) |
| GET | `/api/v1/hr/jobs/{jobId}/fairness` | ADMIN, HR_MANAGER | → `FairnessReport` (scope JOB) |
| GET | `/api/v1/admin/fairness` | ADMIN | → `FairnessReport` (scope ORGANIZATION, `jobId: null`) |

```ts
type FairnessReport = { scope; jobId|null; minimumGroupSize: 5; surveyResponseRate /* 0..1 */;
  dimensions: { dimension: 'gender'|'ageBand'|'disability'|'socialCategory';
    stages: { stage: 'APPLIED'|'SHORTLISTED'|'INTERVIEWING'|'SELECTED';
      groups: { group; count: number|null; selectionRate: number|null /* 0..1, 4 decimals */ }[];
      adverseImpactRatio: number|null /* 3 decimals */; flagged: boolean }[] }[]; note }
```

How the report is computed (`FairnessCalculator`, pure and unit-tested):
- **Unit:** each application is one subject. In the organisation report a candidate who applied to two jobs counts twice.
- **Stage reached:** the furthest of the current status and every previous and new status in the decision history. SELECTED implies INTERVIEWING, which implies SHORTLISTED. An undone shortlist still counts as SHORTLISTED.
- **`count`:** applicants of the group who reached the stage (the frontend shows it as "N people" under the rate).
- **Suppression:** when the group has fewer than 5 applicants, `count` and `selectionRate` are both null.
- **Groups:** every allowed value except PREFER_NOT, in plan order, always listed even when empty (suppressed). Showing only non-empty groups would reveal that a group has 1–4 members.
- **Exclusions:** PREFER_NOT answers and non-respondents.
- **Dimensions:** one that nobody answered is omitted. With no respondents, `dimensions` is `[]`, which the HR UI shows as "No survey responses yet".
- **Adverse-impact ratio:** null with fewer than two unsuppressed groups or when the highest rate is 0. `flagged` = ratio < 0.8, computed on the unrounded value, so exactly 0.8 is not flagged.

### 1.5 Exports and privacy

| Method | Path | Roles | Result |
|---|---|---|---|
| GET | `/api/v1/hr/jobs/{jobId}/audit-export` | ADMIN, HR_MANAGER | `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="job-{id}-audit-{date}.csv"`, `no-store`, `nosniff`. Audited as AUDIT_EXPORTED (target JOB). See below. |
| GET | `/api/v1/candidates/me/export` | CANDIDATE | JSON attachment, **not wrapped in the envelope**: `attachment; filename="recruitpro-my-data-{date}.json"`, `no-store`. See below. |
| DELETE | `/api/v1/candidates/me` | CANDIDATE | `{password}` → `null` + cleared `rp_refresh` cookie. A wrong or missing password is **400**, never 401. Rate-limited to 5 per 15 min per IP. See below. |

**Job audit CSV**
- **Header:** `decisionId,timestamp,applicationId,actorUserId,actorRole,actorName,actorEmail,action,previousStatus,newStatus,comment,matchRunId,algorithmVersion,verifiedCoverage,claimedCoverage,knockoutsVerified`.
- **Rows:** one per decision-history event of the job's applications (every action type, including COMMENT_UPDATED and IDENTITY_REVEALED), oldest first, with the run's algorithm and coverages.
- **Candidate actors** (withdrawals) are shown as role CANDIDATE, name "Candidate", with no id or email.
- **Encoding (`common.util.Csv`):** CRLF line ends. Every text cell is quoted with quotes doubled. A value starting with `= + - @`, tab, CR or LF, also after leading spaces, is prefixed with `'`.

**Candidate data export**
- Top-level fields: `{format: "recruitpro-candidate-export-1", exportedAt, account, profile, resumes, facts, claims, applications, workSamples, interviews, equalOpportunity, notifications}`.
  - `resumes`: metadata plus analysis status, flags and timeline.
  - `facts`: every extracted fact, including contact facts, and the candidate's own corrections.
  - `claims`: the candidate view (no recruiter note).
  - `applications`: with cover letter and the status timeline the candidate already sees.
  - `workSamples`: the candidate's own response, no scores.
  - `interviews`: the candidate view.
  - `notifications`: inbox bodies; e-mail rows without body, because they may hold one-time links.
- **Not exported:** review comments, decision comments, scores, rubrics, scorecards, match evidence.

**Account deletion**, in one transaction:
- **Withdrawn:** open applications (SUBMITTED, SHORTLISTED, INTERVIEWING) with a WITHDRAW history row (actor: the candidate, comment "Candidate account deleted").
- **Cancelled:** ASSIGNED or STARTED work samples, and SCHEDULED interviews.
- **Cleared:** cover letters and work-sample responses.
- **Deleted:**
  - claims, the survey and resume facts;
  - parser flags and timeline;
  - notifications to the user id or the old email;
  - one-time tokens.
- **Revoked:** refresh tokens (reason ACCOUNT_DELETED).
- **Anonymised:**
  - resume file names (`deleted-resume.<ext>`);
  - profile fields;
  - the user: `deleted-user-<id>@deleted.invalid`, "Deleted Candidate", random unusable password, disabled, `deleted_at`;
  - the actor email of the user's existing audit rows (the rows themselves stay).
- **Audited:** CANDIDATE_ACCOUNT_DELETED, written with the anonymised email.
- **Files:** resume files are deleted **after commit**.
- **Kept:** applications, decision history, match runs, scores, scorecards and audit rows.

### 1.6 Profile

`CandidateProfile`, `CandidateProfileResponse` and `CandidateProfileUpdateRequest` no longer have `dateOfBirth` or `gender`. V20 drops both columns. If an old client still sends the keys, they are ignored, because unknown properties are not rejected.

---

## 2. Evidence integration (D5)

| Bean | Order | Emits |
|---|---|---|
| `WorkSampleEvidenceContributor` | 10 | For each SCORED assignment: one fact per current requirement linked by a rubric criterion scored ≥ 3 (best criterion). `id = "ws:<assignmentId>:r<requirementId>"`, `type` = requirement type, `value` = requirement description, `evidence = Work sample "<title>": "<criterion>" scored N of 4`, `sourceSection = "work sample"`, tier **DEMONSTRATED** |
| `InterviewEvidenceContributor` | 20 | For each COMPLETED interview with a scorecard: one fact per current requirement linked by a rating ≥ 3 (best rating). `id = "iv:<interviewId>:r<requirementId>"`, `evidence = "Structured interview #<id>: question <qid> rated N of 4"`, `sourceSection = "interview"`, tier **INTERVIEW_VERIFIED** |

Both contributors are read-only, never put candidate text or interviewer notes in the citation, and drop links to requirements the job no longer has. The AI service lets these ids satisfy only requirement `<requirementId>` (WP-PY). OTHER-type requirements stay "needs human review" in the AI engine even when linked.

Scoring and scorecard submission call `MatchTriggerService.requestMatch` in the same transaction. The tests read the request built by `MatchingService.prepare` to check the exact ids and tiers.

---

## 3. Data model (V18–V20)

These tables follow plan §3.2, with these additions:
- `work_samples.job_id` and `interview_kits.job_id` are `ON DELETE CASCADE`, so a DRAFT job can still be deleted.
- `interview_scorecards.interview_id` is `ON DELETE CASCADE`.
- `created_by_user_id` is nullable.
- CHECK constraints: `total_score` 1..4; `duration_minutes > 0`; and every enum value, including the survey values (`chk_eeo_responses_*`).
- Indexes:
  - `work_samples(job_id, id)`;
  - `work_sample_assignments(application_id, assigned_at)` and `(status, started_at)`, the latter for the sweep;
  - `interviews(application_id)` and `(interviewer_user_id, scheduled_at)`.

Entities store user ids, `interview_kits.job_id` and `eeo_responses.candidate_id` as plain `Long`s, like the WP-BE-A organisation ids. `WorkSample.job`, `WorkSampleAssignment.workSample`/`application` and `Interview.application` are LAZY `@ManyToOne`. JSON lives in TEXT columns: `rubric_json`, `scores_json`, `questions_json` and `ratings_json`.

Notification template **INTERVIEW_CANCELLED** was added; `notifications.template` has no CHECK constraint.

## 4. Configuration keys (new)

| Key (env) | Default | Purpose |
|---|---|---|
| `app.work-samples.expiry-sweep.enabled` (`WORK_SAMPLE_EXPIRY_SWEEP_ENABLED`) | true (test: false) | expiry sweep |
| `app.work-samples.expiry-sweep.interval-ms` / `initial-delay-ms` | 60000 / 45000 | schedule |

Existing keys used:
- `app.frontend.candidate-url` (work-sample e-mail link `/work-samples`);
- `app.frontend.hr-url` (interviewer link `/interviews/{id}`);
- `app.ai-service.*` (generation).

## 5. Code touched outside the new packages

| File | Change |
|---|---|
| `MatchRequestBuilder` | `requirement()` is now public; the generators reuse it |
| `ApplicantQueryService` | `requirementEvidence()` is now public; used for focus requirements |
| `AiServiceClient` | `generateWorkSample`, `generateInterviewKit` |
| new `ai.service.AiAuthoringCalls` | AI error mapping to 400/503 |
| `HrJobToolsController` | adds `GET /hr/jobs/{jobId}/audit-export` |
| `RateLimitFilter` | adds a rule for `DELETE /api/v1/candidates/me` |
| `NotificationTemplate` | INTERVIEW_CANCELLED |
| `ApplicationRepository` | `lockAllByCandidateId` |
| `ApplicationDecisionHistoryRepository` | `findAllForJobExport` |
| `NotificationRepository` | `findAllByRecipientUserIdOrderByIdAsc`, `deleteAllForRecipient` |
| `UserTokenRepository` | `deleteAllForUser` |
| `AuditEventRepository` | `findAllByActorUserIdOrderByIdAsc`, `pseudonymiseActor` |
| new `common.util.AnchorLevels`, `common.util.Csv` | shared helpers |
| `application.yml`, `application-test.yml` | the sweep keys above |

SecurityConfig needed no change: INTERVIEWER reaches `/api/v1/interviews/**` and `/api/v1/hr/interviewers` through method security. The service layer then limits interviewers to their own interviews, and all job and applicant endpoints still refuse them.

## 6. Tests

Unit tests:
- `fairness/FairnessCalculatorTest`: rates, suppression (including empty groups), exclusions, the AIR flag at and below 0.8, the "no ratio" cases, and stage from history.
- `common/util/CsvTest`: quoting, the formula prefixes, leading whitespace, numbers and booleans.
- `assessment/service/WorkSampleRulesTest`: score matching (links come from the rubric), the 1–4 range, rubric validation.
- `interview/service/InterviewRulesTest`: focus order, rating validation (links come from the kit), kit validation, lenient AI mapping.

Integration tests (H2, MockMvc):

`assessment/WorkSampleIntegrationTest`:
- editing and the activation lock;
- tenancy 404 on every staff endpoint;
- assignment rules (draft, SUBMITTED application, past `dueAt`, duplicate 409, roles);
- instructions hidden until start; the 34-minute submission accepted and the 36-minute one rejected (EXPIRED persisted) for a 30-minute task;
- start after `dueAt`; the expiry sweep;
- scoring validation, roles and tenancy;
- WORK_SAMPLE_SCORED audit and MATCH job queued;
- a `ws:<id>:r<req>` DEMONSTRATED fact in the prepared match request (unlinked or low-scored criteria give none);
- the candidate list and ApplicationDetail contain no scores, anchors or notes.

`assessment/AuthoringGenerationIntegrationTest` (`@MockitoBean AiServiceClient`):
- mapping of work-sample and kit drafts (foreign links dropped, placeholders, unique ids, kit replaced);
- the request carries requirements v2;
- 503 and 400 mapping; nothing stored on failure.

`interview/InterviewIntegrationTest`:
- kit 404 → PUT → GET, with validation, roles and tenancy;
- scheduling rules (SUBMITTED application, interviewer of another organisation, disabled interviewer, candidate as interviewer, past time, roles, tenancy);
- SHORTLISTED → INTERVIEWING with one MOVE_TO_INTERVIEW history row (match run id set), and the three notifications;
- `interviewCount`;
- `/mine`; detail permissions (assigned 200, manager 200, other interviewer or recruiter 403, other organisation 404) and focus order;
- scorecard permissions (manager not assigned 403, other organisation 404, 409 on the second scorecard), the audit row and MATCH queued;
- an `iv:<id>:r<req>` INTERVIEW_VERIFIED fact;
- the candidate views without the scorecard;
- reschedule and cancel with notifications.

Other integration tests:
- `fairness/FairnessIntegrationTest`: survey consent, values, replace and delete, candidate-only access; the job report from history with suppression and the AIR flag; roles and tenancy; the organisation report is ADMIN only; survey values never appear in the applicant list.
- `hr/JobAuditExportIntegrationTest`: CSV rows with match-run columns, formula neutralisation, the anonymous candidate actor, the AUDIT_EXPORTED audit row, roles and tenancy.
- `candidate/CandidatePrivacyIntegrationTest`: export content and the absence of staff material. Deletion:
  - a wrong, blank or missing password gives 400 and changes nothing;
  - on success: the cookie is cleared, the user and profile are anonymised, the file is deleted after commit, and facts, claims, survey and notifications are gone;
  - the application is kept and WITHDRAWN, both history rows are kept, the scored work sample is kept without its response, and the interview is cancelled;
  - audit rows are kept (count + 1) with the email pseudonymised;
  - the refresh token is dead and login fails;
  - the HR view shows "Deleted Candidate".
- `auth/RateLimitIntegrationTest`: account deletion is limited to 5 attempts.

Updated tests:
- `support/TestDatabase`: the new tables, children first.
- `candidate/controller/CandidateProfileControllerTest`: 19-field record; `dateOfBirth` and `gender` are absent.

## 7. Differences from what the frontend notes assumed

| Frontend assumption | Implemented |
|---|---|
| HR: `GET interview-kit` 404 = no kit | Yes |
| HR: PATCH work-sample status accepts ACTIVE from DRAFT or ARCHIVED | Yes, plus ACTIVE/DRAFT→ARCHIVED, and back to DRAFT while unassigned |
| HR: score sends every rubric criterion with score and optional note | Yes; must be exactly one per criterion (matched by text). Request `requirementId` is ignored. |
| HR: scorecard `notes`/`summary` sent as `null` when empty | Accepted; blank strings are stored as null |
| HR: interview detail rejects non-assigned non-managers | 403 (other organisation: 404) |
| Candidate: survey GET 404 or `data: null` when none | 404 |
| Candidate: `DELETE /candidates/me` wrong password → 400 | Yes (also blank or missing password) |
| Candidate: export is an attachment, fallback name `recruitpro-my-data-<date>.json` | Same name pattern from the server |
| Candidate: `deadlineAt` = end of the time limit; instructions null until started | Yes (grace not included in `deadlineAt`) |
| Candidate: profile PUT without `dateOfBirth`/`gender` | Fields removed server-side |

Not assumed by the frontends, and worth knowing:
1. New responses send explicit `null`s (see the top of this file).
2. `ApplicantDetail.interviews[].scorecard` is visible to RECRUITER as well as ADMIN and HR_MANAGER; recruiters read all applicant evidence.
3. The fairness `count` is "reached the stage", not group size.
4. INTERVIEW_CANCELLED is a new notification template.
5. The candidate interview list items carry `jobTitle`, also inside ApplicationDetail.

## 8. Deviations from the plan

1. **Work sample editing** is DRAFT-only. The plan does not say; this makes every candidate get the same task, and the HR portal already assumes it.
2. **`dueAt` = latest start time**, not latest submission time.
3. **Start and submit** require the application to still be SHORTLISTED or INTERVIEWING.
4. **Rating links come from the kit** and scoring links from the rubric. The plan does not say where they come from; this stops evidence being attached to arbitrary requirements.
5. **Generation is allowed on CLOSED jobs**, where applicants may still be in process. Only CANCELLED jobs are refused.
6. **Account deletion withdraws open applications** (with a history row) and cancels open work samples and interviews. The plan only says "keep anonymised applications and decisions"; this avoids live pipelines for people who no longer exist.
7. **Audit rows of the deleted user** keep their action, target and actor id, but `actor_email` is replaced by the anonymised address. Rows are never deleted.
8. **Match runs** of a deleted candidate are kept, because decisions reference them. Their stored AI evidence strings were de-identified by the AI service, but may still quote resume phrases (see §10).
9. **Extra template** INTERVIEW_CANCELLED; extra rate-limit rule on account deletion.
10. **Fairness "count" semantics** and listing of empty, suppressed groups (§1.4).

## 9. Reviewer findings and resolution

| # | Finding | Resolution |
|---|---|---|
| 1 | **Bug:** the unusable password was a 73-byte input. BCrypt in Spring Security 6.5 rejects inputs over 72 bytes, so every deletion would have failed with 400. | Fixed: 64 hex characters |
| 2 | An interviewer could link any rating to any requirement of the job and so mint INTERVIEW_VERIFIED evidence | Fixed: links come from the kit; unit test added |
| 3 | Interview and work-sample rows were locked before the tenancy or ownership check | Fixed: scalar organisation or owner lookup first, then the lock |
| 4 | The deletion password could be guessed without limit with a stolen access token | Fixed: 5 per 15 min per IP on `DELETE /api/v1/candidates/me`; test added |

## 10. Risks and open items

- **Not compiled.** Spots to watch at the first `mvn verify`:
  - `@MockitoBean` (Spring Framework 6.2) with the `mock-maker-subclass` setting;
  - JPQL constructor expressions (`AssignmentStatusRow`, `InterviewCountRow` with `count()` as `Long`, `ApplicantStageRow`, `StatusEventRow`);
  - `@JsonInclude(ALWAYS)` on records overriding the global `non_null`;
  - `ResponseEntity<CandidateDataExport>` written by the Jackson converter.
- **H2.** V18–V20 were only executed on PostgreSQL. They stay inside the §3.1 subset (named constraints, `DROP COLUMN`, no partial indexes).
- **Match-run evidence after deletion.** `job_match_runs.requirement_results_json` keeps the AI's de-identified evidence quotes for audit. A stricter erasure policy would blank the `evidence` fields of a deleted candidate's runs (decision rows would still reference the runs).
- **Survey in the organisation report.** One survey answer applies to every application of that candidate, including applications made before the answer was given (the survey is not versioned).
- **Single-instance assumptions** as before. The expiry sweep is safe on several instances (conditional updates).
- **No calendar or ICS.** Interview e-mails carry the time in UTC (plan §10).

## 11. Left undone and frontend follow-ups (for WP-INT)

- **HR portal:**
  - Show "Interview cancelled" for INTERVIEW_CANCELLED in any template-based UI (none exists today).
  - Add a reschedule form (the API supports it).
  - The fairness table could label the per-cell count "reached".
  - Work-sample assignments cannot be cancelled by HR; there is no endpoint (plan scope).
- **Candidate portal:**
  - The work-sample page could show `dueAt` as "start by".
  - A 400 on submit after the grace means the assignment is now EXPIRED; refetch the list.
  - After `DELETE /candidates/me` the server has already cleared the cookie; the client should not call refresh.
- **Not built:**
  - Work-sample file uploads (only a URL).
  - Per-question interviewer notes visible to other interviewers.
  - Scorecard editing.
  - Fairness reports by date range.
  - Intersectional groups.
- **DemoDataSeeder** does not seed work samples, kits or interviews.
