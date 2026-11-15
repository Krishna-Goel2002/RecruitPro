# WP-VBE notes: Claim Ledger and verification (backend)

Date: 2026-11-16 · Contract: [IMPLEMENTATION_PLAN_V2.md](IMPLEMENTATION_PLAN_V2.md) sections 1, 3.1, 3.2, 3.4, 3.5, 5.
Status: implemented, **not compiled** (no JDK/Maven in the sandbox). First `./mvnw verify` happens on a JDK 21 machine.

| Gate | Result |
|---|---|
| `python3 tools/java_static_check.py backend/src` | 575 files, **0 errors** |
| `python3 tools/check_entity_schema.py` | 474 columns of 41 entities vs V1–V24, **0 errors, 0 warnings** |
| `python3 tools/check_migrations_pg.py` | V1–V24 apply on PostgreSQL 16 |
| `cd ai-service && python3 -m pytest -q` | 178 passed (one new test, see §6) |
| Independent compile review (agent, by reading) | 2 test bugs found and fixed (Mockito re-stubbing a throwing mock; `@Modifying` query called outside a transaction) |

## 1. What was built

- **Package `com.recruitpro.verification`**: `entity` (9 entities + enums + `ClaimSubjectRef`), `repository` (11), `client`
  (`AgentServiceClient`, `AgentModels`, `AgentServiceException`, `GitHubOAuthClient`), `service`, `controller`, `dto`.
- **Migrations:** `V21__verification_ledger.sql` (sources, runs, evidence_items, claim_verdicts, reviews, labels,
  organization_verification_settings, verification_oauth_states) and `V22__verification_references.sql`
  (verification_reference_requests, the FK from claim_verdicts.reference_id, `evidence_items.reference_id`).
- **Jobs:** `VerifySourceJobHandler` (VERIFY_SOURCE, entity = run id) and `VerifyReferenceJobHandler` (VERIFY_REFERENCE,
  entity = reference id).
- **Contributor:** `VerificationEvidenceContributor` (`@Order(30)`), see §4.
- **Shared files touched (minimal, additive):** `NotificationTemplate` (+3 templates), `SystemHealthService` and
  `SystemHealthResponse` (+`agentService`), `CandidateDataExport` / `CandidateDataExportService` (+`verification`),
  `CandidateAccountDeletionService` (calls `VerificationPrivacyService`), `SecurityConfig` (2 public paths),
  `application.yml` (`app.agent-service.*`, `app.verification.*`), `application-test.yml` (storage dir, sweep off),
  `TestDatabase` (my tables first), `docker-compose.yml` (backend env + `depends_on: agent-service`), `.env.example`,
  `backend/.env.example`, `ai-service/tests/test_matching.py` (one test).
- **Not touched:** `DemoDataSeeder` (my seed is a separate runner), `RateLimitFilter` (see §7), `AuditActions` (my
  constants are in `VerificationAuditActions`), frontends, agent-service.

## 2. Endpoints as implemented

All responses use the `ApiResponse` envelope `{success, message?, data?, errors?, timestamp}`. Every new record is
`@JsonInclude(ALWAYS)`: `x | null` fields are sent as explicit `null`. Times are UTC ISO strings ending in `Z`.

### 2.1 Candidate, base `/api/v1/candidates/me/verification` (role CANDIDATE)

| Method + path | Body → `data` |
|---|---|
| `GET /sources` | `SourceResponse[]`, non-revoked, newest first |
| `POST /sources` | `{kind: GITHUB\|GITLAB\|WEBSITE\|OPEN_BADGE, identifier, credential?: string\|object, consent: true}` → `SourceResponse`. 400 without consent / bad identifier / kind DOCUMENT; **409** duplicate among non-revoked sources (case-insensitive) |
| `POST /sources/documents` | multipart `file` + form field `consent=true` → `SourceResponse`. pdf/docx/pptx, ≤ 15 MB, magic bytes (`%PDF-`; ZIP with `word/` or `ppt/` entries); 409 same file name |
| `POST /sources/{id}/ownership-check` | `{method}` → `SourceResponse`. GITHUB/GITLAB: `PROFILE_BIO_CODE` (target `github:<login>`); WEBSITE: `DNS_TXT`/`WELL_KNOWN_FILE` (target host) or `PAGE_CODE` (target URL); DOCUMENT/OPEN_BADGE → 400. 503 when the agent is unreachable/unconfigured; 429 after 20 checks/hour/candidate |
| `DELETE /sources/{id}` | revoke → `data` absent, message "Source revoked" |
| `POST /sources/{id}/runs` | → `RunResponse` (PENDING). **409** run PENDING/RUNNING, **429** ≥ 10 runs of the candidate in 24 h |
| `GET /runs/{id}` | → `RunResponse` (extra, for polling) |
| `GET /oauth/github/start` | → `{authorizeUrl}`; **503** with message `OAUTH_NOT_CONFIGURED: ...` |
| `GET /ledger` | `LedgerClaim[]` |
| `GET /verdicts/{id}` | `VerdictDetail` (any status of the candidate's own verdicts) |
| `POST /verdicts/{id}/response` | `{action: SHARE\|HIDE\|RECHECK, note?: ≤1000}` → `VerdictDetail`. Current verdicts only (else 400). RECHECK starts a run of the verdict's source (409/429 as above; 400 for reference verdicts) |
| `GET /references` | `ReferenceResponse[]`, newest first |
| `POST /references` | `{refereeName ≤200, refereeEmail, relationship, employerName?, employerDomain?, claims:[{subjectType, subjectId}] (1..20, current subjects), consent: true}` → `ReferenceResponse`. **429** at 5 open (SENT, unexpired); 400 own e-mail |
| `POST /references/{id}/cancel` | → `ReferenceResponse` (CANCELLED); 400 unless SENT |

```ts
type SourceResponse = { id; kind; identifier; displayLabel; ownershipStatus: 'UNPROVEN'|'PENDING'|'PROVEN'|'FAILED';
  ownershipMethod: 'OAUTH'|'DNS_TXT'|'WELL_KNOWN_FILE'|'PAGE_CODE'|'PROFILE_BIO_CODE'|'ISSUER_SIGNATURE'|null;
  challengeCode: string /* "rp-" + 24 base64url chars */; ownershipInstructions: string[] /* [] once PROVEN */;
  consentedAt; lastRun: {id, status, finishedAt|null, errorCode|null}|null;
  ownershipCheckedAt|null; ownershipDetail: string|null /* agent's outcome text */; createdAt }   // last 3 are extra
type RunResponse = { id; sourceId; status: 'PENDING'|'RUNNING'|'COMPLETED'|'FAILED'; requestedBy: 'CANDIDATE'|'SYSTEM';
  verifier|null; createdAt; startedAt|null; finishedAt|null; errorCode|null }
type LedgerClaim = { subjectType: 'RESUME_FACT'|'CANDIDATE_CLAIM'; subjectId; claimType; value;
  tier /* the subject's own tier: RESUME_STATED for facts, the claim's tier for claims */;
  verdicts: {id, verifier, verdict, candidateStatus, sourceLabel|null, createdAt}[] /* current only, newest first */ }
type Reason = { text; evidenceKeys: string[]; quote: string|null }
type IntegrityNote = { code; text; evidenceKeys: string[] }
type EvidenceItem = { id; key; kind; url|null; title|null; retrievedAt; contentHash /* sha256:<hex> */; excerpt|null; attributes: object|null }
type VerdictDetail = { id; subjectType; subjectId; claimType; value; verifier; verifierVersion|null; verdict; ownershipProven;
  candidateStatus; candidateNote|null; candidateRespondedAt|null; current: boolean; sourceId|null;
  sourceKind: 'GITHUB'|...|'REFERENCE'|null; sourceLabel|null; referenceId|null; runId|null;
  summary: Reason[] /* run narrative */; reasons: Reason[]; integrityNotes: IntegrityNote[]; probeQuestion|null;
  evidence: EvidenceItem[] /* cited items, in citation order */; createdAt }
type ReferenceResponse = { id; refereeName; refereeEmail; relationship; employerName|null; employerDomain|null;
  claims: {subjectType, subjectId, claimType, value}[]; status: 'SENT'|'ANSWERED'|'DECLINED'|'EXPIRED'|'CANCELLED';
  expiresAt; answeredAt|null; createdAt; verdictCount: number; errorCode|null }
```

Ownership instructions are plain sentences (GitHub: "Connect with GitHub" first when OAuth is configured, then the
bio code; website: three options DNS TXT `recruitpro-verification=<code>`, `https://<host>/.well-known/recruitpro-verification.txt`,
page code / `<meta name="recruitpro-verification">`; document: "cannot be proven"; badge: proven during the run).

### 2.2 Public (no auth; rate-limited per IP; `SecurityConfig.PUBLIC_ENDPOINTS`)

| Method + path | Result |
|---|---|
| `GET /api/v1/verification/oauth/github/callback?code&state[&error]` | **302** to `${candidate-url}/verification?connected=github` or `?error=<CODE>`: `OAUTH_NOT_CONFIGURED`, `STATE_INVALID`, `STATE_EXPIRED`, `STATE_USED`, `ACCESS_DENIED`, `CODE_MISSING`, `CODE_REJECTED`, `GITHUB_UNAVAILABLE`, `CONNECT_FAILED`, `RATE_LIMITED` |
| `GET /api/v1/public/references/{token}` | `{candidateFirstName, relationship, employerName\|null, claims:[{id: "fact:12"\|"claim:7", claimType, value}], expiresAt}`; 404 unknown token; **410** expired / answered / declined / cancelled. 30/min/IP |
| `POST /api/v1/public/references/{token}` | `{answers:[{claimId, answer: CONFIRM\|PARTLY\|CANNOT_CONFIRM\|DISAGREE, comment?≤2000}], refereeComment?≤2000, confirmIdentity: true}` or `{decline: true}` → message only. 400 without `confirmIdentity`, unknown/duplicate claim ids; 410 as above. 10 per 15 min/IP |

### 2.3 Staff

| Method + path | Who | Result |
|---|---|---|
| `GET /api/v1/hr/applications/{applicationId}/verification` | ADMIN, HR_MANAGER, RECRUITER of the job's org (other org: 404; INTERVIEWER: 403) | `{mode: 'SHADOW'\|'VISIBLE', revealed, claims: StaffClaim[]}`; SHADOW → `claims: []` |
| `POST /api/v1/hr/verdicts/{id}/review` | ADMIN, HR_MANAGER, RECRUITER with an application of the candidate in their org (else 404) | `{decision: CONFIRM\|OVERRULE, note?}` → `StaffVerdict`. Shared+current verdicts only (404), VISIBLE only (400), OVERRULE needs a note (400). Audit `VERDICT_REVIEWED`; re-matches the candidate's applications in that org |
| `GET /api/v1/admin/verification/settings` | ADMIN | `{mode, connectors:{GITHUB, GITLAB, WEBSITE, DOCUMENT, OPEN_BADGE, REFERENCE: boolean}, updatedAt\|null, updatedByName\|null}` |
| `PUT /api/v1/admin/verification/settings` | ADMIN | `{mode, connectors?}` (missing connectors keep their value; unknown key 400) → same; audit `VERIFICATION_SETTINGS_CHANGED` |
| `GET /api/v1/admin/verification/shadow-queue?page=0&size=20` | ADMIN, HR_MANAGER | `PageResponse<ShadowQueueItem>` |
| `POST /api/v1/admin/verification/verdicts/{id}/label` | ADMIN, HR_MANAGER | `{labelVerdict, note?}` → `{verdictId, labelVerdict, note, at}` (a second label by the same user replaces it) |
| `GET /api/v1/admin/verification/metrics` | ADMIN, HR_MANAGER | `VerificationMetrics` |

```ts
type StaffClaim = { subjectType; subjectId; claimType; value; tier /* for the caller's org, CORROBORATED when lifted */;
  verdicts: StaffVerdict[] }
type StaffVerdict = { id; verifier; verdict; ownershipProven; reasons: Reason[]; integrityNotes: IntegrityNote[];
  probeQuestion|null; candidateNote: string|null /* null before reveal */;
  myOrgReview: {decision, note|null, reviewerName|null, at}|null;
  evidence: EvidenceItem[]|null /* null before reveal */; evidenceCount: number;
  sourceKind: 'GITHUB'|...|'REFERENCE'; createdAt }                     // sourceKind, createdAt extra
type ShadowQueueItem = { verdictId; applicationId /* latest application of the candidate in the org */; revealed;
  subjectType; subjectId; claimType; value; sourceKind; verifier; verdict; ownershipProven; reasons; integrityNotes;
  probeQuestion|null; evidence: EvidenceItem[]|null; evidenceCount; createdAt;
  myLabel: {verdictId, labelVerdict, note, at}|null; labelCount }
type VerificationMetrics = { labelled; agreement: number|null; falseCorroborationRate: number|null; kappa: number|null;
  byVerifier: {verifier, labelled, agreement, falseCorroborationRate, kappa}[];
  byVerdict: {verdict, predicted, gold, agreed, precision|null, recall|null}[] /* always the 5 classes */;
  readyForVisible: boolean /* labelled>=150 && fcr<=0.02 && kappa>=0.70 */ }
```

System health: `SystemHealthResponse` gained `agentService` (`UP`/`DOWN`/`UNCONFIGURED`, `GET <agent>/health`, 2 s
timeout, cached 15 s) — in the admin dashboard it is `health.agentService`.

## 3. Decisions

- **Subjects:** facts SKILL/CERTIFICATION/EDUCATION/EXPERIENCE of the analysis of each resume (COMPLETED **and
  PARTIAL**, like matching) plus claims not rejected by a recruiter; ids `fact:<id>` / `claim:<id>`. Code hosts get
  SKILL subjects only (everything else would be NOT_CHECKABLE noise); web/document/badge get all (≤ 200).
- **Hash check:** every item's `contentHash` must equal `sha256:` + hex of the UTF-8 `snapshotText` (case-insensitive).
  **One mismatch fails the whole run** (`EVIDENCE_HASH_MISMATCH`), as do unknown kinds / missing or duplicate keys
  (`INVALID_EVIDENCE`); nothing is stored. Snapshots are written to `app.verification.storage-dir` (UUID keys,
  resolved inside the root) and removed again on rollback.
- **Ownership cap in the backend:** GITHUB/GITLAB/WEBSITE `ownershipProven = agent && source PROVEN`; DOCUMENT always
  false; OPEN_BADGE = agent (recipient matched; the source then becomes PROVEN/ISSUER_SIGNATURE); references = agent.
  VERIFIED without ownership is stored as PARTLY_VERIFIED.
- **Append-only:** the current verdict of the same subject + source (or reference) becomes SUPERSEDED/not current;
  the new one is AWAITING_CANDIDATE. Verdicts for subjects not sent (or deleted meanwhile) are dropped; claim type and
  value come from the backend's subject. `evidence_item_ids_json` = items cited by reasons or notes.
- **Errors (WP-AG note 6):** non-2xx with `detail.code` → run FAILED with that code, no retry. No code + 5xx/408/429 or
  transport error → retried; dead letter → `AGENT_SERVICE_UNAVAILABLE`. Other non-2xx → `AGENT_SERVICE_REJECTED`; no
  token → `AGENT_SERVICE_NOT_CONFIGURED`; document gone → `FILE_UNAVAILABLE`; revoked meanwhile → `SOURCE_REVOKED`.
- **Revoke:** source row kept with `revoked_at`; token, scopes, file, pasted credential cleared; every evidence item of
  the source (ownership proof included) deleted and its snapshots removed after commit; verdicts WITHDRAWN + not
  current (`evidence_item_ids_json = []`); PENDING/RUNNING runs FAILED. Re-matches the candidate if a withdrawn verdict
  was shared/reviewed. The same identifier can be connected again.
- **Tier rule (3.5)** in one place (`VerificationStaffSupport.lifts`): VERIFIED ∧ ownershipProven ∧ current ∧ SHARED ∧
  org VISIBLE ∧ connector enabled ∧ org's latest review CONFIRM. Used by the staff view (`tier`) and the contributor.
- **Connectors** are per organisation: a disabled connector's verdicts are hidden from that org's staff view and
  review, and never lift its matching. They do not restrict what candidates may connect.
- **Blind-first:** before reveal (or with blind review on and not revealed) `evidence` and `candidateNote` are null.
- **Re-matching:** review (org only), mode/connector change (candidates reviewed by the org), HIDE/revoke/supersede
  of a shared verdict (candidate-wide; cheap and safe).
- **GitHub OAuth:** state 32 bytes Base64url, SHA-256 hex stored, 10-min expiry, consumed with a conditional update
  (also on `error=`); token encrypted with context `vsrc:<id>` after the row exists; the callback creates or updates the
  GITHUB source (lower-cased login). Consent: the click on "Connect with GitHub" is the consent (recorded with the
  current consent version) since `start` has no body.
- **References:** token 32 bytes Base64url, SHA-256 stored, 14 days; claims snapshotted in `claim_refs_json`; e-mail
  REFERENCE_REQUEST has no inbox copy (one-time link). REFERENCE_ANSWERED goes to the candidate after the verdicts are
  stored, or immediately on decline. `emailDomain` = referee e-mail domain; `holderName` = candidate full name.
- **Demo seed** (`VerificationDemoSeedRunner`, order 30, after `DevSeedRunner`; not prod; failures only logged): Acme
  Labs → VISIBLE; candidate@demo.test gets SKILL claims React/TypeScript/Python, GitHub source `demo-asha` (PROVEN by
  PROFILE_BIO_CODE), one COMPLETED run with 4 SYNTHETIC evidence items (hashes computed, snapshots stored) and SHARED
  verdicts React VERIFIED, TypeScript VERIFIED, Python PARTLY_VERIFIED (probe question). No agent call. Idempotent.

## 4. Deviations (all backwards compatible)

1. **Extra columns:** `verification_sources.ownership_detail`, `credential_text` (pasted badge; identifier becomes
   `credential:<32 hex of sha256>`); `verification_runs.summary_json`, `warnings_json`; `evidence_items.reference_id`
   (V22; `run_id`/`source_id` nullable); `verification_reference_requests.verified_at`, `error_code`.
2. **Extra response fields** listed above (`ownershipCheckedAt`, `ownershipDetail`, `createdAt`, `sourceKind`,
   `verdictCount`, `errorCode`, …) and the extra `GET /runs/{id}`.
3. **PARTIAL analyses** count as current (matching uses them too).
4. **POST /verdicts/{id}/response returns `VerdictDetail`** (contract did not define it).
5. **`candidateNote` hidden before reveal** in the staff view (blind-first).
6. **503 OAUTH_NOT_CONFIGURED** is the HTTP status plus `message` starting with `OAUTH_NOT_CONFIGURED`.
7. **Rate limits** use the same `SlidingWindowRateLimiter` and `app.rate-limit.enabled` as `RateLimitFilter`, but in
   `PublicEndpointRateLimiter` (memory only), not inside the filter, to avoid editing WP-PLAT's file.
8. **Audit action names** live in `VerificationAuditActions`: `VERIFICATION_SOURCE_CONNECTED`,
   `VERIFICATION_SOURCE_REVOKED`, `VERIFICATION_OWNERSHIP_PROVEN`, `VERDICT_SHARED`, `VERDICT_REVIEWED`,
   `VERIFICATION_SETTINGS_CHANGED`, `REFERENCE_REQUESTED`. OAuth-callback audits use `recordFor` with the candidate as
   actor (no session).
9. **docker-compose:** `GITHUB_OAUTH_REDIRECT_URI` defaults to `http://localhost:8080/api/v1/verification/oauth/github/callback`
   (empty client id/secret still disable OAuth).

## 5. Configuration

`app.agent-service.{base-url (AGENT_SERVICE_URL, http://localhost:8100), service-token (AGENT_SERVICE_TOKEN),
timeout-ms 60000, connect-timeout-ms 5000, health-timeout-ms 2000}`;
`app.verification.{consent-version, storage-dir (VERIFICATION_STORAGE_PATH, default $FILE_STORAGE_PATH/verification),
max-runs-per-day 10, github.{client-id, client-secret, redirect-uri} (GITHUB_*), reference-expiry.{enabled, interval-ms
900000, initial-delay-ms}}`. Docker: `APP_ENCRYPTION_KEY` dev default `ZGV2LW9ubHktcmVjcnVpdHByby1lbmNyeXB0LWtleSE=`
(Base64 of a fixed 32-byte string, DEV ONLY), `AGENT_SERVICE_TOKEN` default equal to the agent-service entry.

## 6. Tests (JUnit 5; H2; `AgentServiceClient` and `GitHubOAuthClient` are `@MockitoBean`s)

All verification integration tests share one context (`VerificationIntegrationTestBase`: test encryption key, GitHub
client configured, agent token).

| Class | Covers |
|---|---|
| `VerificationSchemaIntegrationTest` | V21/V22 tables and `evidence_items.reference_id` on H2; round-trip of every entity; label uniqueness; OAuth state `markUsed` single use; CHECK constraint |
| `VerificationSourceIntegrationTest` | consent required, 409 duplicate (case-insensitive), URL/kind validation, badge credential, documents (consent, magic bytes, ZIP folder check, duplicate name), ownership check (target strings, FAILED → PROVEN, proof stored, audit, PROVEN kept, wrong method 400, other candidate 404), revoke cleanup (token, scopes, evidence, snapshot file, verdicts WITHDRAWN, runs FAILED, audit, reconnect) |
| `GitHubOAuthIntegrationTest` | authorize URL (client id, scope, 32-byte state), hashed state with 10-min expiry, public callback 302, source PROVEN/OAUTH, **no plaintext token in DB** and decrypts with `vsrc:<id>`, state single use, expired/unknown/missing/denied states, GitHub outage |
| `GitHubOAuthNotConfiguredIntegrationTest` | 503 `OAUTH_NOT_CONFIGURED`, callback redirect with the code |
| `VerifySourceJobHandlerIntegrationTest` | claims sent (SKILL only for code hosts, no contact facts), evidence + snapshot stored, verdicts AWAITING, unknown subjects dropped, notification, **supersede**, **hash mismatch rejects all**, **backend ownership cap**, coded failure, retry + dead letter, rejected, **409 active run, 429 after 10/24 h**, revoked source |
| `CandidateLedgerIntegrationTest` | ledger (facts of finished analyses + claims, contact facts excluded), detail with notes and evidence, SHARE (audit) / HIDE / invalid / RECHECK (job queued, 409 while running), superseded verdict 400, other candidate 404 |
| `ReferenceIntegrationTest` | create (consent, own e-mail, 5 open → 429), e-mail link token, token stored hashed, public GET shape, unknown 404, answer validation, answered once → 410, VERIFY_REFERENCE (referee domains, holderName, verdicts with reference_id, evidence, idempotent), decline, expired 410 + sweep, cancel |
| `StaffVerificationIntegrationTest` | SHADOW → `claims: []` and review 400 and no `vf:`; VISIBLE shared-only, blind-first (evidence/note null until reveal), other org 404, INTERVIEWER 403; review: INTERVIEWER 403, unrelated org 404, unshared 404, OVERRULE needs note, RECRUITER CONFIRM, audit, org-only re-match, tier CORROBORATED for that org only; **`MatchingService.prepare` contains `vf:<id>` (CORROBORATED, ≤40 chars) only for the confirming org in VISIBLE mode**, OVERRULE / SHADOW remove it |
| `AdminVerificationIntegrationTest` | settings default, ADMIN-only PUT, validation, connectors merge, audit; shadow queue (applicants only, shared only, unlabelled first, blind), labels, metrics numbers |
| `VerificationPrivacyIntegrationTest` | export contains sources/runs/evidence/verdicts/references but no token, token hash or storage path; account deletion removes rows (reviews too) and files |
| `VerificationMetricsTest` (unit) | hand-computed kappa (0.697), FCR, per-class precision/recall, kappa edge cases, readiness thresholds, per-verifier split |
| `VerificationRulesTest` (unit) | subject refs, ownership cap, every tier-rule condition, evidence hashing/validation, agent error translation (`detail.code`, transient vs not) |
| ai-service `test_matching.py::test_recruiter_confirmed_verification_facts_are_corroborated_general_facts` | `vf:` facts are origin `other`, provenance CORROBORATED, not linked to a requirement — **no engine change was needed** |

## 7. Could not be verified without compiling / open risks

- **Nothing was compiled or run.** Static checks cover imports/types and entity↔schema only; method resolution,
  generics, Lombok and Spring wiring were checked by reading (mine + one independent agent pass). Expect possible
  small fixes on the first `mvnw verify`.
- `@MockitoBean` on fields of the abstract base class relies on Spring 6.2 bean-override discovery in superclasses.
- **Rate limits are in-memory** (`PublicEndpointRateLimiter`): with WP-PLAT's `app.rate-limit.store=database`, these
  limits stay per instance. WP-PLAT may move the three rules into `RateLimitFilter`.
- **REFERENCE_REQUEST e-mails survive account deletion** (recipient is the referee, so `deleteAllForRecipient` does not
  match them); they contain the candidate's name. Needs a notification-side delete by template + recipient.
- Snapshot/file deletion is after-commit and best-effort (logged on failure); no orphan sweep.
- The shadow queue is paged in memory (fine for hundreds of verdicts, not for very large orgs).
- Documents are stored unencrypted on disk (same as resumes).
- The agent's de-identification is lexical; `candidateNote` is free text and only shown after reveal.

## 8. For WP-PLAT and the frontends

- **WP-PLAT:** I added `APP_ENCRYPTION_KEY` (dev default) and `GITHUB_*` to the backend service in `docker-compose.yml`
  — please do not add a second `APP_ENCRYPTION_KEY`. `TestDatabase` lists my 9 tables first. Public paths added to
  `PUBLIC_ENDPOINTS` under a `// WP-VBE` comment. `X-Request-Id` is forwarded from MDC key `requestId` (your filter
  should put it there). Multipart limit (your 20 MB) already covers 15 MB documents.
- **Candidate portal:** `/verification` handles `?connected=github` and `?error=<CODE>` (list in §2.2); public page
  `/reference/:token` maps 404 → "link not valid", 410 → "expired or already answered". Upload with form field
  `consent=true`. Poll `GET /sources` (or `GET /runs/{id}`) while `lastRun.status` is PENDING/RUNNING. Errors: 409 run
  in progress, 429 daily limit / open references.
- **HR portal:** tab data from `GET /api/v1/hr/applications/{id}/verification`; show `evidenceCount` when `evidence`
  is null. Admin dashboard health: `health.agentService`.
