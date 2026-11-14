# WP-BE-B notes: backend screening core

Status: implemented, not yet compiled. This environment has no JDK and no Maven Central access. The first `./mvnw verify` runs on a developer machine or in CI.

Scope (plan §9): V15–V17, requirements v2, JobPublic, deadline closer, application lifecycle, decisions, reveal, applicant review API, evidence ladder, claims, async matching (MATCH_APPLICATION), notifications and candidate ApplicationDetail. Findings closed: C1, C2, C3 (API), H2 (data), H6, H8, M3, M8 (grouping), M9, M13, M14 (data), L1, L2, L10.

| Gate | Result |
|---|---|
| G-SQL `python3 -B tools/check_migrations_pg.py` | V1–V17 apply to PostgreSQL 16 |
| Backfills | Checked separately: V1–V14, then legacy rows, then V15–V17. Results:<br>• `knockout = is_mandatory`<br>• shortlisted applications are revealed<br>• legacy run is current with `verified_coverage` copied<br>• a second run per application is allowed<br>• the new CHECK constraints reject bad values<br>• LINK facts are accepted<br>• open applications are queued for re-matching |
| G-JAVA-STATIC `python3 -B tools/java_static_check.py backend/src` | 0 errors in 291 files |
| Entity/schema agreement `python3 -B tools/check_entity_schema.py` (**new tool**) | 243 mapped columns of 20 entities exist with compatible types. Mutation-tested: a renamed column, a wrong Java type and a missing column name are each reported. |
| G-JAVA-REVIEW | My own line-by-line pass, then one independent reviewer agent.<br>• Compile errors found: none.<br>• Fixed: one failing test assertion (the notification outbox writes EMAIL + IN_APP rows) and six runtime issues (see §8). |

All JSON below uses the existing envelope `{success, message?, data?, errors?, timestamp}`. **Null fields are omitted** (`default-property-inclusion: non_null`), so a field typed `x | null` in TypeScript arrives as *absent*. Empty lists are always sent as `[]`. Timestamps are UTC ISO strings ending in `Z`.

---

## 1. Endpoints as implemented

### 1.1 Jobs and requirements (`JobController`)

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| POST | `/api/v1/jobs` | ADMIN, HR_MANAGER | `{title, description, department, location, jobType, experienceLevel, minimumSalary?, maximumSalary?, vacancies, deadline?}` → `JobStaff` (DRAFT) |
| GET | `/api/v1/jobs?page&size&status&department&location&jobType&search&sort&direction` | staff | Staff → `Page<JobStaff>` for their organisation.<br>CANDIDATE → `Page<JobPublic>`: PUBLISHED jobs with an open deadline, all organisations. `status` is ignored for candidates. |
| GET | `/api/v1/jobs/{id}` | staff, CANDIDATE | Staff → `JobStaff`; a job of another organisation is 404.<br>Candidate → `JobPublic`; 404 unless the job is open. |
| PUT | `/api/v1/jobs/{id}` | ADMIN, HR_MANAGER | Same body as POST (no `@Future`) → `JobStaff`.<br>Allowed in DRAFT, PUBLISHED and ON_HOLD.<br>A **changed** deadline must be in the future (compared at millisecond precision); an unchanged past deadline is accepted. |
| PATCH | `/api/v1/jobs/{id}/status` | ADMIN, HR_MANAGER | `{status}` → `JobStaff`. Closing sets `closedReason: "CLOSED_BY_HR"`. |
| DELETE | `/api/v1/jobs/{id}` | ADMIN, HR_MANAGER | DRAFT only, else 400 |
| POST | `/api/v1/jobs/{id}/requirements` | ADMIN, HR_MANAGER | `RequirementInput` → `Requirement` (max 100 per job) |
| PUT | `/api/v1/jobs/{id}/requirements/{rid}` | ADMIN, HR_MANAGER | `RequirementInput` → `Requirement` |
| DELETE | `/api/v1/jobs/{id}/requirements/{rid}` | ADMIN, HR_MANAGER | → `null` |
| PUT | `/api/v1/jobs/{id}/requirements` | ADMIN, HR_MANAGER | `{requirements: RequirementInput[]}` (max 100) → `JobStaff`. Replace-all; see `id` below. |
| POST | `/api/v1/jobs/compile-requirements` | ADMIN, HR_MANAGER | `{title (≤200), description (≤20000), experienceLevel?}` → `CompileResult`. Never persists.<br>**503** when the AI service is unreachable or not configured; **400** when it rejects the input. |

Requirement changes are allowed in DRAFT, PUBLISHED and ON_HOLD; CLOSED and CANCELLED give 400. On a PUBLISHED or ON_HOLD job every requirement change does three things in the same transaction:
1. increments `requirementsVersion`;
2. writes a `JOB_REQUIREMENTS_CHANGED` audit event;
3. queues MATCH_APPLICATION for every application in SUBMITTED, SHORTLISTED, INTERVIEWING or REJECTED.

The job row is locked while this happens.

```ts
type RequirementInput = { id?: number;          // only used by the bulk PUT
  type: 'SKILL'|'EDUCATION'|'CERTIFICATION'|'EXPERIENCE'|'OTHER'; description: string /* 1..500, trimmed */;
  mandatory?: boolean /* default true */; knockout?: boolean /* default = mandatory */;
  minimumYears?: number /* 0..50 */; alternatives?: string[] /* ≤10, each ≤200, trimmed, de-duplicated, blanks and the description itself dropped */;
  proficiency?: 'BASIC'|'INTERMEDIATE'|'ADVANCED'|'EXPERT' }
// Bulk PUT: an item whose id belongs to this job updates that requirement in place (keeps links from work samples
// and interview kits); an item without id is created; an id of another job → 400; requirements not listed are deleted.
// knockout=true with mandatory=false → 400.
type Requirement = { id; type; description; mandatory: boolean; knockout: boolean; minimumYears?; alternatives: string[]; proficiency? }
type JobStaff = { id; organizationId; title; description; department; location; jobType; experienceLevel;
  minimumSalary?; maximumSalary?; vacancies; status; publishedAt?; deadline?; closedReason? /* DEADLINE_PASSED|CLOSED_BY_HR|ADMIN_OVERRIDE */;
  requirementsVersion: number; createdByEmail; createdByName; createdAt; updatedAt; requirements: Requirement[]; applicationCount: number }
type JobPublic = { id; organizationName; title; description; department; location; jobType; experienceLevel;
  minimumSalary?; maximumSalary?; vacancies; deadline?; publishedAt?; requirements: Requirement[] }   // no status, no creator
type CompileResult = { engine: 'rules'|'llm'; requirements: (Omit<Requirement,'id'> & { rationale: string })[];
  warnings: { phrase; kind: 'AGE_CODED'|'GENDER_CODED'|'DEGREE_INFLATION'|'EXCLUSIONARY'|'VAGUE'|'EXCESSIVE_EXPERIENCE'; suggestion }[] }
```

`JobResponse` changed in two ways:
- `JobStaff` no longer has `createdByProfileId`.
- `Requirement` no longer has `jobId`, `createdAt` or `updatedAt`.

### 1.2 HR applicant review

Two path prefixes are used below:
- the job prefix is `/api/v1/hr/jobs/{jobId}/applicants`;
- `…` stands for `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}`.

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| GET | `/api/v1/hr/jobs/{jobId}/applicants?page&size&status&sort=rank\|appliedAt` | ADMIN, HR_MANAGER, RECRUITER | → `Page<ApplicantSummary>`. `size` 1..100. `sort` defaults to `rank`; `appliedAt` = newest first. Any other `sort` value → 400. |
| GET | `/api/v1/hr/jobs/{jobId}/applicants/{applicationId}` | ADMIN, HR_MANAGER, RECRUITER | → `ApplicantDetail` |
| POST | `…/decisions` | ADMIN, HR_MANAGER | `{action, comment?}` → `DecisionEvent` |
| POST | `…/shortlist`, `…/reject` | ADMIN, HR_MANAGER | `{comment?}` or no body → `DecisionEvent` (aliases) |
| PUT | `…/review-comment` | ADMIN, HR_MANAGER, **RECRUITER** | `{comment}` (≤2000; blank clears) → `DecisionEvent` (`COMMENT_UPDATED`). Allowed in any status. |
| GET | `…/history` | ADMIN, HR_MANAGER, RECRUITER | → `DecisionEvent[]` (oldest first) |
| POST | `…/reveal` | ADMIN, HR_MANAGER | → `ApplicantDetail`. 400 when SUBMITTED under blind review; idempotent once revealed. |
| GET | `…/resume` | ADMIN, HR_MANAGER, RECRUITER | Returns the file: `Content-Disposition: attachment; filename="application-{id}-resume.{ext}"`, `nosniff`, `no-store`.<br>403 until the identity is revealed; 404 when the file is missing.<br>Audited as `RESUME_DOWNLOADED_BY_STAFF`. |
| PATCH | `…/analysis/retry` | ADMIN, HR_MANAGER | → `null` (FAILED analyses only; WITHDRAWN applications give 400) |
| POST | `…/claims/{claimId}/verification` | ADMIN, HR_MANAGER, RECRUITER | `{verified: boolean, note? ≤500}` → `CandidateClaim` (staff view). Audited as `CLAIM_VERIFIED`. |
| POST | `/api/v1/hr/jobs/{jobId}/rematch` | ADMIN, HR_MANAGER | → `{enqueued: number}`: matchable applications now waiting for a match (0 when the job has no requirements) |
| GET | `/api/v1/hr/interviewers` | ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER | → `{userId, name, email, role}[]`: enabled, not-deleted staff of the caller's organisation, sorted by name |

Status codes:
- Another organisation's job behaves as missing (404) on every endpoint above.
- Decisions:
  - A transition not in the plan §3.4 table → 400.
  - UNDO_SHORTLIST or REOPEN without a comment → 400.
  - COMMENT_UPDATED, WITHDRAW or IDENTITY_REVEALED as `action` → 400.
  - RECRUITER → 403.

```ts
type ApplicantSummary = { applicationId; displayName /* "Candidate #A-<id>" until revealed */; identityRevealed: boolean;
  applicationStatus; appliedAt; statusChangedAt?; analysisStatus? /* PENDING|PROCESSING|COMPLETED|PARTIAL|FAILED|NEEDS_OCR|UNSUPPORTED_FORMAT */;
  matchStatus: 'READY'|'PENDING'|'NO_REQUIREMENTS'|'ANALYSIS_UNAVAILABLE'|'FAILED';
  verifiedCoverage?; claimedCoverage?; knockoutsVerified?; unverifiedKnockouts: number[];
  rankGroup: 'RANKED'|'MUST_HAVES_UNVERIFIED'|'UNSCORED'; rank? /* RANKED only, 1-based over the whole filtered list */;
  flags: string[] /* parser flag codes, e.g. HIDDEN_TEXT, PROMPT_INJECTION_SUSPECTED */; workSampleStatus?; interviewCount: number }
type RequirementEvidence = { requirementId; type; description; mandatory; knockout; minimumYears?; alternatives: string[];
  status: 'MATCHED'|'UNCLEAR'|'NOT_OBSERVED'; tier?: EvidenceTier; evidence?; sourceSection?; explanation: string;
  verificationHint?; provenance? /* RESUME|CANDIDATE_CLAIM|CORROBORATED|WORK_SAMPLE|INTERVIEW — extra field */ }
type CandidateClaim = { id; claimType: 'SKILL'|'CERTIFICATION'|'EDUCATION'|'EXPERIENCE'|'OTHER'; value; evidenceUrl?;
  hasEvidenceUrl: boolean; sourceFactId?; tier: EvidenceTier; verificationStatus: 'UNVERIFIED'|'VERIFIED'|'REJECTED';
  verifiedAt?; verificationNote?; createdAt; updatedAt }
type DecisionEvent = { id; applicationId; previousStatus; newStatus; actionType; actorUserId?; actorName; timestamp; comment?; matchRunId? }
type ApplicantDetail = ApplicantSummary & { requirements: RequirementEvidence[]; matchRunId?; algorithmVersion?; calculatedAt?;
  claims: CandidateClaim[]; candidate?: { fullName; email; city?; country?; linkedinUrl?; portfolioUrl?; yearsOfExperience?;
  currentCompany?; currentDesignation? }; coverLetter?; resumeAvailable: boolean; resumeFilename?;
  workSamples: any[] /* [] until WP-BE-C */; interviews: any[] /* [] until WP-BE-C */; history: DecisionEvent[];
  allowedActions: ('SHORTLIST'|'MOVE_TO_INTERVIEW'|'SELECT'|'REJECT'|'UNDO_SHORTLIST'|'REOPEN')[] /* [] for RECRUITER */;
  revealAllowed: boolean /* extra: manager, blind review on, not revealed, status ≠ SUBMITTED */ }
```

**Ranking** (`ApplicantRanking`, plan §5.4, D6):

| Group | Who is in it | Order within the group |
|---|---|---|
| RANKED | current run, `knockoutsVerified === true`, `verifiedCoverage` set | verified desc, claimed desc, appliedAt asc |
| MUST_HAVES_UNVERIFIED | current run with `knockoutsVerified` false or null | verified desc, claimed desc, appliedAt asc |
| UNSCORED | no current run, or a run without a verified coverage | appliedAt asc |

How the list is computed:
- The server loads two lightweight projections for the job: `ApplicantRow` for every application and `RunSummary` for every current run.
- It sorts everything, then slices the page in memory.
- Only the page is enriched: names, analysis, flags, run JSON, latest MATCH job status and extension extras.
- When the job currently has no requirements, stored runs are ignored: everyone is UNSCORED with `NO_REQUIREMENTS`.

**matchStatus**, in priority order:
1. `NO_REQUIREMENTS`
2. `PENDING`: the latest MATCH job is PENDING or RUNNING, or the resume analysis is PENDING or PROCESSING. Coverage from the previous run is still shown.
3. `READY`: a current run exists.
4. `ANALYSIS_UNAVAILABLE`: the resume could not be analysed and there is no other evidence.
5. `FAILED`: there is no run and nothing is queued (the AI rejected the request, or legacy data). Use rematch.

**Blind review (D4).** "Revealed" means `identity_revealed_at` is set or the organisation has blind review off. Until then the following are null/absent or false:
- `candidate`, `coverLetter` and `resumeFilename`;
- `resumeAvailable` (false);
- claim `evidenceUrl` (`hasEvidenceUrl` tells the UI one exists);
- in history, the `actorName` of candidate events (WITHDRAW) shows the blind name and `actorUserId` is absent.

SHORTLIST reveals the identity automatically:
- **Blind review on:** writes an `IDENTITY_REVEALED` history row (previous = new = SHORTLISTED, comment "Identity revealed automatically on shortlist") and an `IDENTITY_REVEALED` audit event.
- **Blind review off:** only sets `identity_revealed_at`, so the identity stays visible if blind review is turned on later.

UNDO_SHORTLIST keeps the identity revealed.

**Claims, staff view (D3).** A claim belongs to the candidate, but a recruiter's verification only counts for the recruiter's organisation. For every other organisation the claim is shown, and matched, as `SELF_CLAIMED` / `UNVERIFIED`, with no `verifiedAt` and no note. The other organisation cannot see that the claim was checked, so it cannot infer that the candidate applied elsewhere.

### 1.3 Candidate

| Method | Path | Body → `data` |
|---|---|---|
| GET | `/api/v1/candidates/me/claims` | → `CandidateClaim[]`, newest first. Candidate view: the recruiter's `verificationNote` is never sent. |
| POST | `/api/v1/candidates/me/claims` | `{claimType, value (1..1000), evidenceUrl? (http/https, ≤500)}` → `CandidateClaim` (SELF_CLAIMED; max 100 claims) |
| DELETE | `/api/v1/candidates/me/claims/{id}` | → `null`. Deleting a fact-correction claim also resets that fact to UNREVIEWED. |
| GET | `/api/v1/candidates/me/resumes/{id}/analysis` | `{resumeId, status, parserVersion?, errorCode?, attemptCount, analyzedAt?, facts: Fact[], flags: {code, detail}[], timeline?}` |
| PATCH | `/api/v1/candidates/me/resumes/{id}/analysis/facts/{factId}` | `{value}` → `Fact`; behaviour below |
| POST | `/api/v1/jobs/{jobId}/applications` | `{resumeId, coverLetter? ≤10000}` → `Application`.<br>• Queues MATCH_APPLICATION and sends APPLICATION_RECEIVED.<br>• 403 when `app.auth.require-verified-email-to-apply` is on and the email is unverified.<br>• 409 when the candidate already applied.<br>• 400 when the job is not open. |
| GET | `/api/v1/candidates/me/applications?page&size` | → `Page<Application>` |
| GET | `/api/v1/candidates/me/applications/{id}` | → `ApplicationDetail` |
| PATCH | `/api/v1/candidates/me/applications/{id}/withdraw` | → `Application`. Allowed from SUBMITTED, SHORTLISTED and INTERVIEWING, else 400 with the message "Only submitted, shortlisted or interviewing applications can be withdrawn".<br>Writes a WITHDRAW history row (actor: the candidate) and sends APPLICATION_STATUS_CHANGED. |

**Fact review (PATCH):**
- A changed value is stored as `reviewedValue`, and a SELF_CLAIMED claim linked by `source_fact_id` is created, or updated with its verification reset.
- Confirming the extracted value removes that claim.
- `extracted_value` is never changed.
- EMAIL, PHONE and LINK facts never become claims.

```ts
type Fact = { id; type /* SKILL|EDUCATION|EXPERIENCE|CERTIFICATION|EMAIL|PHONE|LINK */; value /* displayed: claimed ?? extracted */;
  extractedValue; evidence; sourceSection; sourceReference?; reviewStatus: 'UNREVIEWED'|'CONFIRMED'|'CORRECTED'; reviewedAt?;
  tier: 'RESUME_STATED'; claimedValue?; claimId?; spanStart?; spanEnd?; attributes?: object }
type Application = { id; jobId; jobTitle; organizationName; resumeId; resumeFilename; status; coverLetter?; appliedAt; updatedAt; statusChangedAt? }
type ApplicationDetail = Application & { timeline: { status; at }[] /* appliedAt + every status-changing history event */;
  requirements?: { requirementId; type; description; mandatory; verification: 'VERIFIED'|'CLAIMED'|'NOT_VERIFIED' }[];
  workSamples: any[]; interviews: any[] }
```

`requirements` is present only when both of these hold:
- the organisation has `share_verification_status = true`;
- the application status is SUBMITTED, SHORTLISTED or INTERVIEWING.

Each entry is derived from the current match run:
- `VERIFIED`: MATCHED at tier ≥ RESUME_STATED;
- `CLAIMED`: MATCHED only at SELF_CLAIMED;
- `NOT_VERIFIED`: anything else, including when there is no run yet.

**Notifications.** Every status change notifies the candidate with APPLICATION_STATUS_CHANGED: decisions, undo, reopen and withdrawal. The `status` placeholder uses candidate-facing labels ("Under review", "Shortlisted", "Interviewing", "Selected", "Not selected", "Withdrawn"). As documented by WP-BE-A, each call writes an EMAIL outbox row plus an IN_APP inbox row.

---

## 2. Contracts for WP-BE-C

### 2.1 `com.recruitpro.evidence.service.EvidenceContributor` (tiers 3–4)

```java
public interface EvidenceContributor {
    List<AiMatchRequest.Fact> contribute(Application application, List<JobRequirement> requirements);
    static AiMatchRequest.Fact fact(String id, String type, String value, String evidence, String sourceSection, EvidenceTier tier);
}
```

How the MATCH job uses contributors:
- Every bean implementing the interface is called, in `@Order`, by the MATCH_APPLICATION job (`MatchingService.prepare`). The call runs inside a **read-only** transaction with no HTTP user.
- A contributor must not write and must not call the AI service. It returns `[]`, never `null`. Throwing fails the attempt, and the job retries.

How to build facts:
- Ids must be origin-tagged and at most 40 characters: `ws:<assignmentId>:r<requirementId>` (tier `DEMONSTRATED`) or `iv:<interviewId>:r<requirementId>` (tier `INTERVIEW_VERIFIED`). The AI service lets such a fact satisfy only requirement `<requirementId>`.
- Use `EvidenceContributor.fact(...)`. It trims values to the AI limits.
- `type` is the requirement type.
- `evidence` is a short citation shown to reviewers before reveal, so it must contain no identity.

When evidence changes (a rubric is scored, a scorecard is submitted), call `MatchTriggerService.requestMatch(applicationId)` in the same transaction.

The builder sends facts in this order and caps them at 500: resume facts, then contributed facts, then claims.

### 2.2 `com.recruitpro.application.service.ApplicationDetailExtension` (work samples and interviews in the views)

```java
default Map<Long, ApplicantExtras> applicantExtras(Collection<Long> applicationIds) // HR list page: workSampleStatus, interviewCount
default List<?> staffWorkSamples(Application a)      // ApplicantDetail.workSamples  (WorkSampleAssignment[])
default List<?> staffInterviews(Application a)       // ApplicantDetail.interviews   (Interview[])
default List<?> candidateWorkSamples(Application a)  // ApplicationDetail.workSamples (CandidateWorkSample[])
default List<?> candidateInterviews(Application a)   // ApplicationDetail.interviews ({id, scheduledAt, durationMinutes, location, status}[])
record ApplicantExtras(String workSampleStatus, int interviewCount)
```

- All beans are combined by `ApplicationExtensions`: lists are concatenated, and extras are merged (first non-null status, interview counts added).
- The methods run inside read-only GET transactions. They must not write.
- Objects are serialised with Jackson as returned.
- `applicantExtras` gets the ids of one page (≤ 100), so query in batch.

### 2.3 Other hooks

- **Scheduling the first interview** must move SHORTLISTED to INTERVIEWING. In the HR request, call `ApplicationDecisionService.decide(jobId, applicationId, new DecisionRequest(ApplicationDecisionAction.MOVE_TO_INTERVIEW, null))`. This keeps the state machine, history (with `match_run_id`), audit and notification in one place.
- **Candidate account deletion** must delete `candidate_claims` explicitly if the profile row is kept; FK `ON DELETE CASCADE` only fires when the profile is deleted. Facts keep `reviewed_value`.
- **Job audit export** can read `application_decision_history.match_run_id`, `job_match_runs` (append-only) and these audit actions: `APPLICATION_DECISION`, `IDENTITY_REVEALED`, `RESUME_DOWNLOADED_BY_STAFF`, `CLAIM_VERIFIED`, `JOB_REQUIREMENTS_CHANGED`, `JOB_AUTO_CLOSED`.
- `TestDatabase` order now deletes `application_decision_history` before `job_match_runs`, and `candidate_claims` before `candidate_profiles`. Insert V18–V20 tables children-first.

---

## 3. Matching pipeline (D7, D8)

**Handler.** `MatchApplicationJobHandler` (MATCH_APPLICATION, entity = `applications.id`) works in three steps:

1. **Prepare** (`MatchingService.prepare`, read-only). It returns null, and nothing is written, when:
   - the application is missing, WITHDRAWN or SELECTED;
   - the job has no requirements;
   - the analysis is PENDING or PROCESSING (its completion queues a new match);
   - the analysis is unusable (FAILED, NEEDS_OCR or UNSUPPORTED_FORMAT) and there are no claims and no contributed facts.

   Otherwise it builds the v3 request:
   - requirements v2 (≤ 100, alternatives ≤ 10, years ≤ 50);
   - resume facts with their *extracted* value at `evidence_tier` (RESUME_STATED), with parser `attributes`, and never EMAIL, PHONE or LINK;
   - contributed facts;
   - claims at their effective tier for the job's organisation (`ClaimEvidence`); rejected claims are not sent;
   - the stored `timeline` (only when it has `totalMonths ≥ 0`).
2. **Call** `POST /api/v1/match` outside any transaction.
   - Retried (throw): HTTP 5xx, 429, 408, connection errors, time-outs and an empty body.
   - Permanent failure (`NonRetryableJobException`, job FAILED, `matchStatus` FAILED): other 4xx responses and a missing token.
3. **Persist** (`MatchingService.persist`, application row locked with a plain `SELECT … FOR UPDATE`). It appends a run and flips `is_current`:
   - `verified_coverage` (also copied into the legacy `observed_evidence_coverage`), `claimed_coverage`, `knockouts_verified`, `requirements_version`, `trigger_reason`;
   - `requirement_results_json = {"requirements": RequirementResult[], "unverifiedKnockouts": number[]}`;
   - `calculated_at` = the time the inputs were read.

**Stale-run guard.** A run is kept but **not made current** in either of these cases:
- its requirements version is older than the current run's;
- it has the same version, but its inputs were read before the current run's (overlapping jobs).

A late result for an application that was withdrawn meanwhile is dropped.

**trigger_reason** is derived by comparing with the previous current run:
- `INITIAL`: there is no previous run.
- `REQUIREMENTS_CHANGED`: the requirements version differs.
- `RESUME_ANALYSIS_UPDATED`: another analysis was used, or the analysis completed after the previous run.
- `EVIDENCE_CHANGED`: the input fingerprint differs (claims or contributed evidence).
- `RERUN`: nothing changed.

**Triggers** go through `MatchTriggerService` and run in the caller's transaction. "Matchable" means SUBMITTED, SHORTLISTED, INTERVIEWING or REJECTED.

| Event | Applications queued |
|---|---|
| Application submitted | that application |
| Resume analysis completed or failed (`complete`, `fail`, dead letter) | every matchable application using the resume |
| Candidate adds, changes or deletes a claim, or corrects or confirms a fact | every matchable application of the candidate |
| A recruiter verifies or rejects a claim | the candidate's matchable applications in **the recruiter's organisation** |
| Requirements change on a PUBLISHED or ON_HOLD job | every matchable application of the job |
| `POST …/rematch` | every matchable application of the job |
| WP-BE-C evidence changes | the affected application |
| V17 migration | every open application, once (legacy runs carry no tier or knockout data) |

**De-duplication.** Triggers use the new `BackgroundJobService.enqueueFollowUp`: only a **PENDING** job counts as a duplicate. A change made while a match is RUNNING therefore queues exactly one follow-up run.

**Old path removed.** The old synchronous matching on GET (`ApplicantIntelligenceService`, batch DTOs, `AiServiceClient.matchBatch`) is gone. GET endpoints never call the AI service and never write. The one exception is the staff resume download, which writes its required audit row.

---

## 4. Data model (V15–V17)

As in plan §3.2, with these details:

**V15**
- `chk_job_requirements_proficiency` and `chk_jobs_requirements_version`.
- Index `jobs(status, deadline)` for the deadline closer.

**V16**
- `fk_applications_revealed_by` and `fk_app_decision_history_match_run`.
- Backfill: SHORTLISTED applications get `identity_revealed_at = updated_at`; every application that is not SUBMITTED gets `status_changed_at = updated_at`.
- Indexes `applications(job_id, status)` and `applications(resume_id)`.

**V17**
- CHECK constraints on fact tier, claim type and tier, and the two coverages.
- `candidate_claims.claim_value`, not `value` (H2 reserved word).
- Queues MATCH_APPLICATION jobs for open applications.

**New code**
- Entities: `CandidateClaim` (evidence.entity) and the `EvidenceTier`, `ClaimType` and `Proficiency` enums.
- `ResumeFactType.LINK`.
- `ApplicationStatus` + INTERVIEWING, SELECTED.
- `ApplicationDecisionAction` + UNDO_SHORTLIST, REOPEN, MOVE_TO_INTERVIEW, SELECT, WITHDRAW, IDENTITY_REVEALED.
- `JobRequirement.alternatives` is `List<String>` through `common.persistence.StringListJsonConverter`, stored in TEXT. An empty list is stored as NULL, and null elements are dropped.
- `JobMatchRun.application` is now `@ManyToOne`; its `currentRun` field maps to `is_current`.

## 5. Configuration keys (new)

| Key (env) | Default | Purpose |
|---|---|---|
| `app.jobs.deadline-closer.enabled` (`JOB_DEADLINE_CLOSER_ENABLED`) | true (test: false) | `JobDeadlineCloser` closes PUBLISHED jobs whose deadline passed (`closed_reason = DEADLINE_PASSED`, audit `JOB_AUTO_CLOSED` by SYSTEM). ON_HOLD jobs are not closed, so their deadline can still be extended. |
| `app.jobs.deadline-closer.interval-ms` / `initial-delay-ms` | 60000 / 30000 | schedule |

Existing keys used:
- `app.auth.require-verified-email-to-apply`;
- `app.ai-service.base-url`, `service-token` and `timeout-ms`, which now also apply to `/api/v1/match` and `/api/v1/requirements/compile`.

## 6. Test changes

**New**

Unit tests:
- `hr/service/ApplicantRankingTest`: groups, order, rank, ties; claimed coverage never outranks verified.
- `application/service/ApplicationTransitionsTest`: full §3.4 table, undo and reopen comments, terminal states, withdraw rules.
- `evidence/MatchRequestBuilderTest`: **C1**. A correction is a SELF_CLAIMED fact and the extracted fact stays RESUME_STATED. Rejected claims are dropped; another organisation's verification neither raises nor removes a claim; contact facts are never sent; requirement and timeline limits hold.
- `hr/service/RequirementCompilerServiceTest`: mapping and defaults; 503 and 400 cases.
- `hr/service/HrJobAccessServiceTest`: moved from the deleted `ApplicationReviewServiceTest`.

Integration tests:
- `evidence/MatchApplicationJobHandlerIntegrationTest` (stub `AiServiceClient`, real DB):
  - append-only runs with one current run;
  - every trigger reason;
  - the request content;
  - the stale-version and stale-snapshot guards;
  - a withdrawn-meanwhile result is dropped;
  - retry and permanent-failure classification;
  - nothing to match;
  - an unusable analysis is matched only with other evidence.
- `evidence/CandidateClaimsIntegrationTest`: claims CRUD and validation, fact correction to linked claim (update, verification reset, confirm, delete resets the fact), contact facts, triggers, isolation.
- `evidence/ResumeAnalysisCompletionIntegrationTest`: spans, attributes, LINK facts, flags and timeline are stored; completion queues matching for open applications only.
- `hr/ApplicantReviewIntegrationTest`:
  - ranking across three pages, sorting and filtering;
  - blind names;
  - auto-reveal with audit, history and notification;
  - reveal and resume rules with their audit;
  - blind review off;
  - undo, reopen, interview and select;
  - recruiter comments;
  - claim verification, including cross-organisation non-disclosure;
  - rematch and interviewers;
  - 404 for another organisation on every endpoint;
  - **GETs never write** (row counts of runs, jobs and audit are unchanged).
- `hr/JobRequirementsV2IntegrationTest`:
  - defaults and validation;
  - bulk replace with in-place ids;
  - a published edit bumps the version, writes an audit event and queues matching (withdrawn applications excluded, no duplicates);
  - ON_HOLD deadline edit; CLOSED is not editable;
  - JobPublic (no creator email, status or count; expired jobs hidden);
  - JobStaff fields;
  - deadline closer;
  - compile proxy (403, 400, 503 against an unreachable AI URL).
- `application/CandidateApplicationDetailIntegrationTest`: apply queues matching and sends APPLICATION_RECEIVED; timeline; VERIFIED, CLAIMED and NOT_VERIFIED; sharing off; withdraw from SHORTLISTED; history actor; email count.
- `application/ApplyRequiresVerifiedEmailIntegrationTest`: property-driven context.

**Support**
- `support/ScreeningFixtures`: resumes, analyses, facts, applications, requirements and runs.
- `TestDatabase`: new table order.

**Updated**
- `ApplicationReviewAuthorizationIntegrationTest`:
  - recruiters may comment and read history but not decide or reveal;
  - shortlist history now includes IDENTITY_REVEALED;
  - SHORTLISTED → REJECTED is allowed;
  - comments are allowed before a decision;
  - REOPEN needs a comment;
  - size limit is 100;
  - list fields are `matchStatus` and `rankGroup`;
  - the rollback test uses `ApplicationDecisionService`;
  - the concurrency test now expects both orders to end WITHDRAWN with consistent history.
- `CandidateApplicationLifecycleIntegrationTest`: withdraw rules and message; SHORTLISTED withdrawal is recorded.
- `CandidatePortalIntegrationTest`: JobPublic fields.
- `ResumeIntelligenceSecurityIntegrationTest`: the summary shape; a correction creates a claim and keeps the extracted value.
- `ApplicationReviewControllerSecurityTest`: per-endpoint roles.
- `ResumeParseJobHandlerTest`: constructors.
- `AiLifecycleIntegrationTest`: opt-in; matching through the worker.

**Deleted:** `ApplicantIntelligenceServiceTest`, which tested the removed synchronous path.

## 7. Deviations from the plan

1. **Follow-up de-duplication.** `BackgroundJobService.enqueueFollowUp` was added, and MATCH triggers de-duplicate on PENDING only (see WP-BE-A deviation 3).
2. **Derived run fields.** `trigger_reason` is derived (§3), because `background_jobs` has no payload. `calculated_at` is the time the inputs were read.
3. **Bulk replace ids.** The bulk requirement PUT accepts optional `id` items, so links survive. `knockout` requires `mandatory`.
4. **Claim verification is per organisation (D3).** A single verification slot is shared, so the latest verifier wins: if organisation A rejects a claim and organisation B later corroborates it, A then sees it as unverified. Candidates never see recruiter notes. Staff see claim URLs only after reveal.
5. **Review comments.** Allowed in every status, and a blank comment clears. History is readable by RECRUITER; INTERVIEWER has no job access, so interviewers read it through WP-BE-C's interview detail.
6. **Withdrawal history.** A candidate withdrawal writes a `WITHDRAW` history row and an APPLICATION_STATUS_CHANGED notification.
7. **Blind review off.** No IDENTITY_REVEALED event is written; only the reveal time is recorded. `revealAllowed` is false.
8. **matchStatus.** `FAILED` also covers "usable analysis, no run, nothing queued".
9. **Unusable analysis.** An unparseable resume is still matched when the candidate has claims or contributed evidence; resume facts are not sent.
10. **Extra response fields.**
    - `ApplicantDetail.revealAllowed`
    - `RequirementEvidence.provenance`
    - `CandidateClaim`: `hasEvidenceUrl`, `sourceFactId`, `verificationStatus`, `updatedAt`
    - `Fact`: `claimId`, `spanStart`, `spanEnd`, `attributes`
    - analysis: `flags`, `timeline`
11. **Applicant list size.** The maximum is 100 (was 50), per the plan.
12. **Not read-only.** These candidate read paths are no longer read-only transactions, because `getOrProvisionMine()` may insert a profile, which PostgreSQL rejects in a read-only transaction: `listMine`, `getMine`, the claims list and the analysis.
13. **Removed batch matching.** `AiBatchMatchRequest/Response` and `AiServiceClient.matchBatch` were removed as dead code.

## 8. Reviewer findings and resolution

| # | Finding | Resolution |
|---|---|---|
| 1 | The detail test counted EMAIL and IN_APP notification rows | The test now counts EMAIL rows only |
| 2 | An older same-version run finishing late could become current; a withdrawn-meanwhile result was stored | Fixed with the snapshot-time guard and a status check in `persist`; both are tested |
| 3 | Stale runs were still ranked after every requirement was deleted | Fixed: runs are ignored when the job has no requirements |
| 4 | Claim verification leaked across organisations and changed their tiers | Fixed with `ClaimEvidence` per-organisation semantics, staff-view masking and organisation-scoped re-queue; tested |
| 5 | An unchanged past deadline counted as changed | Fixed: millisecond-precision comparison |
| 6 | IDENTITY_REVEALED was written with blind review off | Fixed |
| 7 | Concurrent triggers can create duplicate PENDING jobs (check-then-insert) | **Not fixed.** A partial unique index is outside the H2 subset (§3.1). Duplicates only cost an extra AI call. |
| 8 | Read-only transactions could provision a profile | Fixed |
| 9 | Legacy runs had no tier or knockout data | Fixed: V17 queues re-matching |

## 9. Risks and open items

- **Not compiled.** Spots to watch at the first `mvn verify`:
  - `@EntityGraph` on the overridden `JobRepository.findAll(Specification, Pageable)`;
  - `@Convert` on a `List<String>` attribute;
  - JPQL constructor expressions into records (`ApplicantRow`, `RunSummary`, `AnalysisSummary`, `LatestJobStatus`).
- **H2.** V17 drops `uk_job_match_runs_application`. H2 must then hand that index's ownership to the FK on `application_id`. The reviewer agrees it does, but it was only run on PostgreSQL here.
- **Scale.** Ranking loads one small row per application and one per current run on every list request: O(n) memory. That is fine for thousands of applicants; add a cached or materialised rank beyond that.
- **Multi-instance.** The stale-run guard keeps the newest snapshot current. Duplicate PENDING jobs are possible under concurrent triggers (see §8 #7).
- **Claims and AI evidence text.** Claim evidence URLs are never sent to the AI. The AI's own evidence strings are de-identified by the AI service (WP-PY). The backend shows them before reveal.
- **AI token.** In production `AI_SERVICE_TOKEN` must be set. Without it every MATCH job fails permanently: `matchStatus` FAILED, then a manual rematch.
- **Left to WP-BE-C:**
  - evidence contributors and detail extensions;
  - automatic MOVE_TO_INTERVIEW on the first interview;
  - fairness;
  - job audit CSV;
  - candidate export and delete, including claims.
