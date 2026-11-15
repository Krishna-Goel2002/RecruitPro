# RecruitPro implementation plan v2: traceable claims, verification agents, platform hardening

Date: 2026-11-15 · Owner: Kshitij (branch `Kshitij`) · Builds on [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) and [STATUS.md](STATUS.md).
Design source: the doc "RecruitPro — Evidence Verification Agents & Enhanced Scope".

Phase 1 (this plan): functional features and robust architecture. Phase 2 (later): rebrand, UI/UX review and redesign of
both portals. Phase 1 frontends use the current component style; Phase 2 restyles everything.

Out of scope by decision: DigiLocker / API Setu (skipped 15 Nov 2026), PostgreSQL row-level security, pgvector,
Spring Boot 4, WhatsApp, HRMS/job-board connectors.

---

## 1. Principles (apply to every work package)

1. **Every claim is traceable.** A verdict always names the evidence items behind it; every evidence item has a
   source URL (or file), retrieval time, SHA-256 content hash and a stored snapshot.
2. **Ownership before credit.** A source whose ownership is not proven can produce at most `PARTLY_VERIFIED`.
3. **Absence is neutral.** `NOT_FOUND` never lowers a score or rank; it is shown as "not found in connected sources".
4. **No popularity signals.** Stars, followers, likes, commit counts compared across people: never used.
5. **Agents read and draft; the backend decides.** The agent service is stateless and never writes the record.
   The backend stores evidence, verdicts and reviews; only a recruiter's confirmation changes a tier (to `CORROBORATED`,
   for that recruiter's organisation only).
6. **Candidate control.** Per-source consent; candidate previews every verdict first and chooses Share / Re-check / Hide;
   revoking a source deletes its token, snapshots and evidence and withdraws its verdicts.
7. **Blind-first stays intact.** Before reveal, staff see verdicts and de-identified reasons only; evidence items
   (URLs, usernames, excerpts) only after reveal.
8. **Fetched content is data, never instructions.** No tool is callable from fetched text; LLM output is accepted only
   when every sentence cites an evidence key and its quote is found verbatim in that evidence.
9. **Shadow first.** Each organisation starts in `SHADOW` mode: verdicts are produced and can be labelled by admins,
   but recruiters do not see them and matching ignores them. `VISIBLE` is an explicit admin switch.
10. **Offline by default.** Everything works with `LLM_PROVIDER=none`; tests never touch the network (recorded fixtures,
    `httpx.MockTransport`, Spring `MockRestServiceServer`/mocked clients).

## 2. Architecture

```
candidate-portal / hr-portal ──► backend (Spring, system of record)
                                   │  Claim Ledger, consent, background_jobs, audit, snapshots
                                   ├──► ai-service (no egress): parsing, matching, kits
                                   └──► agent-service (egress, sandboxed fetch): code, web, document, badge, reference verifiers
```

New service `agent-service/` (FastAPI, Python 3.12), port 8100, internal token `AGENT_SERVICE_TOKEN`
(header `X-Internal-Service-Token`, checked before the body like ai-service). Stateless. It vendors the skills taxonomy
from ai-service (`tools/sync_taxonomy.py` copies `ai-service/app/taxonomy/{skills.json,taxonomy.py}`; a test fails when
the copies differ).

## 3. Data model (backend migrations)

Migration numbers are reserved: **V21, V22 = WP-VBE; V23, V24 = WP-PLAT.** H2 (MODE=PostgreSQL) runs every migration in
tests: no partial indexes, no `ON CONFLICT`, no PostgreSQL-only types (use `TEXT` for JSON).

### 3.1 V21__verification_ledger.sql (WP-VBE)

- `verification_sources`: id, candidate_id → candidate_profiles ON DELETE CASCADE, kind (`GITHUB`, `GITLAB`, `WEBSITE`,
  `DOCUMENT`, `OPEN_BADGE`), identifier VARCHAR(500) (login, URL, file name, badge id), display_label, ownership_status
  (`UNPROVEN`, `PENDING`, `PROVEN`, `FAILED`), ownership_method NULL (`OAUTH`, `DNS_TXT`, `WELL_KNOWN_FILE`, `PAGE_CODE`,
  `PROFILE_BIO_CODE`, `ISSUER_SIGNATURE`), ownership_checked_at, challenge_code VARCHAR(64) (random, shown to the
  candidate), access_token_enc TEXT NULL (SecretCipher, context `vsrc:<id>`), token_scopes, file_path NULL (DOCUMENT),
  consent_text_version, consented_at NOT NULL, revoked_at NULL, created_at, updated_at.
  Unique (candidate_id, kind, identifier) among non-revoked rows is enforced in the service.
- `verification_runs`: id, source_id, candidate_id, status (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`), verifier,
  verifier_version, requested_by (`CANDIDATE`, `SYSTEM`), started_at, finished_at, error_code, usage_json, created_at,
  updated_at.
- `evidence_items`: id, run_id, source_id, candidate_id, item_key VARCHAR(40) (agent key), kind (`PROFILE`, `REPOSITORY`,
  `MANIFEST`, `COMMIT_SUMMARY`, `README`, `PAGE`, `DOCUMENT`, `BADGE`, `OWNERSHIP_PROOF`, `REFERENCE_RESPONSE`), url
  VARCHAR(1000), title VARCHAR(300), retrieved_at, content_hash VARCHAR(80) (`sha256:<hex>`), snapshot_path VARCHAR(500),
  excerpt VARCHAR(1000), attributes_json TEXT, created_at.
- `claim_verdicts` (append-only; one current row per subject + source): id, candidate_id, subject_type
  (`RESUME_FACT`, `CANDIDATE_CLAIM`), subject_id, claim_type, claim_value VARCHAR(1000), source_id NULL, reference_id NULL,
  run_id NULL, verifier, verifier_version, verdict (`VERIFIED`, `PARTLY_VERIFIED`, `NOT_FOUND`, `CONTRADICTED`,
  `NOT_CHECKABLE`), ownership_proven BOOLEAN, reasons_json TEXT (list of `{text, evidenceKeys[], quote}`; texts are
  de-identified), integrity_notes_json TEXT, evidence_item_ids_json TEXT, probe_question VARCHAR(500),
  candidate_status (`AWAITING_CANDIDATE`, `SHARED`, `HIDDEN`, `WITHDRAWN`, `SUPERSEDED`), candidate_note VARCHAR(1000),
  candidate_responded_at, is_current BOOLEAN, created_at, updated_at.
- `claim_verdict_reviews` (per organisation, append-only, latest wins): id, verdict_id, organization_id, decision
  (`CONFIRM`, `OVERRULE`), note VARCHAR(1000) (required for OVERRULE), reviewer_user_id, created_at.
- `claim_verdict_labels` (shadow evaluation): id, verdict_id, organization_id, labeller_user_id, label_verdict (same enum
  as verdict), note, created_at. Unique (verdict_id, labeller_user_id).
- `organization_verification_settings`: organization_id PK, mode (`SHADOW`, `VISIBLE`) default SHADOW, enabled kinds
  (`connectors_json`), updated_by, updated_at.

### 3.2 V22__verification_references.sql (WP-VBE)

`verification_reference_requests`: id, candidate_id, referee_name, referee_email, relationship (`MANAGER`, `PEER`,
`REPORT`, `CLIENT`, `TEACHER`, `OTHER`), employer_name NULL, employer_domain NULL, claim_refs_json (subject refs),
token_hash VARCHAR(64) UNIQUE, status (`SENT`, `ANSWERED`, `DECLINED`, `EXPIRED`, `CANCELLED`), answers_json,
referee_comment, expires_at (14 days), answered_at, consented_at, created_at, updated_at.

### 3.3 V23 / V24 (WP-PLAT)

- V23__staff_mfa_sso.sql: `user_mfa` (user_id PK, secret_enc, enabled_at, last_used_step BIGINT), `user_recovery_codes`
  (id, user_id, code_hash, used_at), `organizations.require_staff_mfa BOOLEAN DEFAULT FALSE`,
  `organizations.sso_allowed_domains VARCHAR(500)`, `user_identities` (id, user_id, provider, subject, email,
  created_at, unique(provider, subject)), `sso_login_states` (id, state_hash unique, nonce, code_verifier_enc, provider,
  portal, expires_at, used_at).
- V24__platform_hardening.sql: `background_jobs.dedupe_key VARCHAR(120) NULL UNIQUE` (set to `TYPE:entityId` while
  PENDING, cleared when the job leaves PENDING); `notifications.claimed_by VARCHAR(80)`, `notifications.claimed_until`;
  `rate_limit_buckets` (bucket_key PK, window_start, count); work samples: `work_sample_assignments.cancelled_at`,
  `cancelled_by_user_id`, `cancel_reason`, status `CANCELLED` added to its CHECK; `work_sample_submission_files` (id,
  assignment_id, original_name, content_type, size_bytes, storage_path, sha256, created_at); `interview_scorecards`
  edit history (`interview_scorecard_revisions`: id, scorecard_id, revised_by, snapshot_json, created_at).

### 3.4 Shared pieces created before dispatch

- `common/crypto/SecretCipher` (AES-256-GCM, key `app.encryption.key` / env `APP_ENCRYPTION_KEY`, 32 bytes Base64).
- `BackgroundJobType.VERIFY_SOURCE` (entity = verification_runs.id), `VERIFY_REFERENCE`
  (entity = verification_reference_requests.id).

### 3.5 Tier rule

A verdict lifts a claim to `CORROBORATED` for organisation O only when **all** hold: verdict `VERIFIED`,
`ownership_proven = true` (references: independent referee, see 4.5), candidate_status `SHARED`, O's mode `VISIBLE`, and
O's latest review is `CONFIRM`. `VerificationEvidenceContributor` (an `EvidenceContributor`) then adds fact
`vf:<verdictId>` (type = claim type, value = claim value, tier CORROBORATED, sourceSection "verification", evidence
"Verified by <verifier>; confirmed by a recruiter"). The ai-service treats `vf:` as a general fact (origin `other`),
provenance CORROBORATED. A CONFIRM/OVERRULE triggers re-matching of the candidate's applications in O.

## 4. Agent service contract (WP-AG)

All JSON camelCase. Instants ISO-8601 UTC with `Z`.

```
EvidenceItem { key, kind, url|null, title, retrievedAt, contentHash "sha256:<hex>", excerpt (<=1000),
               snapshotText (<=262144 chars, the normalised text that was hashed), attributes {} }
Reason       { text (de-identified: no login, URL, person or org name of the candidate), evidenceKeys [], quote|null }
IntegrityNote{ code, text, evidenceKeys [] }
Verdict      { claimId, claimType, claimValue, verdict, ownershipProven, reasons [Reason], integrityNotes [IntegrityNote],
               probeQuestion|null }
ClaimIn      { id ("fact:12" | "claim:7"), type (SKILL|CERTIFICATION|EDUCATION|EXPERIENCE|OTHER), value }
VerifyResponse { verifier, verifierVersion, evidence [EvidenceItem], verdicts [Verdict] (one per input claim),
                 summary [Reason] (cited narrative, may be empty), skills [{skillId, name, evidenceKeys, repoCount}],
                 usage {requests, llmCalls, truncated}, warnings [] }
```

Endpoints (all `POST`, all require the token):

| Path | Body | Notes |
|---|---|---|
| `/api/v1/ownership/check` | `{method, target, code}` method ∈ DNS_TXT, WELL_KNOWN_FILE, PAGE_CODE, PROFILE_BIO_CODE (target = GitHub/GitLab `provider:login`) | → `{proven, method, detail, evidence: EvidenceItem|null}`. DNS TXT `recruitpro-verification=<code>`; file `https://<host>/.well-known/recruitpro-verification.txt` containing the code; page text containing the code; profile bio containing the code |
| `/api/v1/verify/code` | `{provider: github|gitlab, login, accessToken|null, ownershipProven, claims, limits{maxRepos=30,maxRequests=150}}` | GitHub REST/GraphQL; GitLab REST |
| `/api/v1/verify/web` | `{url, ownershipProven, claims, limits{maxPages=8,maxBytes=2000000}}` | same-site crawl from `url` |
| `/api/v1/verify/document` | multipart: `file` + `request` JSON `{claims, holderName|null}` | PDF, DOCX, PPTX ≤ 15 MB; ownership never proven |
| `/api/v1/verify/badge` | `{credential|null, url|null, claims, holderName|null, holderEmail|null}` | Open Badges 2.0 hosted assertions; OB 3.0 VC-JWT (EdDSA/ES256/RS256, issuer key via `did:web`/`did:key`/JWKS URL). Data Integrity proofs → NOT_CHECKABLE "proof type not supported yet" |
| `/api/v1/verify/reference` | `{claims, referee{relationship, emailDomain, employerDomain|null}, answers[{claimId, answer: CONFIRM|PARTLY|CANNOT_CONFIRM|DISAGREE, comment}]}` | deterministic |

Rules (deterministic layer; the optional LLM only writes `summary`):

- **Code.** Only repositories the login owns, non-fork, or forks with the user's own commits. Per repo: languages
  (bytes), manifest dependencies (package.json, requirements*.txt, pyproject.toml, pom.xml, build.gradle(.kts), go.mod,
  Cargo.toml, Gemfile, composer.json, *.csproj), topics, README excerpt, the user's commit count and authored share
  (user commits / all commits, from contributors stats; 1.0 when the user is the only contributor), first/last commit
  dates. Map languages, dependencies and topics to taxonomy skills.
  SKILL claim: `VERIFIED` if ownershipProven and ≥ 2 repos show the skill with authored share ≥ 0.5 and the user's
  commits in them span ≥ 30 days; `PARTLY_VERIFIED` if any repo shows it (or ownership unproven); else `NOT_FOUND`.
  Other claim types → `NOT_CHECKABLE`. Code never yields `CONTRADICTED`. Integrity notes (never verdicts): all commits in
  one day on a repo created < 14 days ago (`BULK_HISTORY`); fork without own commits ignored (`FORK_ONLY`); commits
  whose author email is not linked to the account (`UNLINKED_AUTHOR`); README identical to a known template
  (`TEMPLATE_README`). Stars, followers, watchers: never read.
- **Web.** Fetch with SSRF guard (http/https only, ports 80/443, resolve and block private, loopback, link-local,
  multicast and metadata addresses before every request and redirect, ≤ 5 redirects, 10 s timeout, size cap, HTML/text/
  PDF only, robots.txt respected, user agent `RecruitProVerifier/1.0 (+contact)`), no cookies, no JavaScript. Extract
  text, title, JSON-LD (`Person`, `CreativeWork`, `author`), meta author, bylines, dates. A claim is `VERIFIED` only when
  ownershipProven and the claim value is stated on a page with an authorship marker that matches; `PARTLY_VERIFIED`
  when stated without ownership; `NOT_FOUND` otherwise; `CONTRADICTED` only when a page explicitly states a conflicting
  value for the same claim (e.g. different degree year), with both quotes.
- **Document.** Metadata (author, created, modified, producer, last modified by), text. Never `VERIFIED`
  (ownership unprovable); `PARTLY_VERIFIED` when the claim is stated; integrity notes for metadata after the claimed
  date or author mismatch with `holderName`.
- **Badge.** `VERIFIED` when the signature (OB3) or hosted assertion (OB2) checks out, it is not revoked/expired, the
  recipient matches (`holderEmail` hashed identity or name), and the achievement matches the claim (taxonomy or token
  overlap). Wrong recipient → `CONTRADICTED`. Unsupported proof → `NOT_CHECKABLE`. ownershipProven = recipient matched.
- **Reference.** CONFIRM from a MANAGER/PEER/CLIENT whose `emailDomain` equals `employerDomain` → `VERIFIED`
  (ownershipProven = true: independent anchor); CONFIRM otherwise → `PARTLY_VERIFIED`; PARTLY → `PARTLY_VERIFIED`;
  CANNOT_CONFIRM → `NOT_FOUND`; DISAGREE → `CONTRADICTED` (reason quotes the comment). Free-email domains (gmail etc.)
  never count as employer domains.
- **Ownership cap**: ownershipProven false ⇒ verdict ≤ PARTLY_VERIFIED, enforced in one place for all verifiers.
- **Probe questions**: for PARTLY_VERIFIED, NOT_CHECKABLE and CONTRADICTED verdicts, a neutral structured-interview
  question about the claim (template-based, no identity).
- **Citation validator**: drops any summary sentence without evidence keys, with unknown keys, or whose quote is not
  found verbatim (whitespace-insensitive) in the cited items' snapshot text. Prompt-injection markers in fetched text
  are flagged as integrity notes (`INSTRUCTION_TEXT`) and the text is still treated as data.
- **De-identification of reasons**: login, display name, URLs, emails and repo names are replaced by "the account",
  "repository A/B/…", "the site", before returning.
- **Demo fixtures**: `AGENT_DEMO_FIXTURES=true` serves recorded responses for logins/hosts starting with `demo-` so
  the demo works offline.
- **Eval harness** `agent-service/eval/`: labelled gold cases (recorded GitHub profiles, pages, documents, badges,
  references; synthetic, clearly marked) → metrics: false-corroboration rate (VERIFIED when gold ≠ VERIFIED), Cohen's
  kappa, per-verdict precision/recall. `python -m eval.run --check` fails if false corroboration > 0.02 or kappa < 0.70.

## 5. Backend contract (WP-VBE)

Base for candidates: `/api/v1/candidates/me/verification` (role CANDIDATE). Errors use the existing
`ApiResponse`/`GlobalExceptionHandler` shapes.

| Method + path | Body / result |
|---|---|
| `GET /sources` | `[SourceResponse{id, kind, identifier, displayLabel, ownershipStatus, ownershipMethod, challengeCode, ownershipInstructions[], consentedAt, lastRun{id,status,finishedAt,errorCode}|null}]` |
| `POST /sources` | `{kind (GITHUB|GITLAB|WEBSITE|OPEN_BADGE), identifier, consent:true}` → SourceResponse (409 duplicate). OPEN_BADGE identifier = assertion URL; or `credential` field for a pasted JWT/JSON |
| `POST /sources/documents` | multipart `file` (pdf, docx, pptx ≤ 15 MB) + `consent=true` → SourceResponse |
| `POST /sources/{id}/ownership-check` | `{method}` → SourceResponse (calls agent `/ownership/check`; audit) |
| `GET /oauth/github/start` | → `{authorizeUrl}`; 503 `OAUTH_NOT_CONFIGURED` without `GITHUB_CLIENT_ID/SECRET` or encryption key |
| `GET /api/v1/verification/oauth/github/callback?code&state` (public) | state hashed in `sso_login_states`-like table of its own (`verification_oauth_states`, in V21), exchanges code, reads `/user`, creates/updates the GITHUB source, ownership PROVEN/OAUTH, stores encrypted token (scope `read:user`), 302 to `${candidate-url}/verification?connected=github` or `?error=<code>` |
| `POST /sources/{id}/runs` | → RunResponse (enqueue VERIFY_SOURCE). 409 if a run is PENDING/RUNNING; 429 after 10 runs per candidate per 24 h |
| `DELETE /sources/{id}` | revoke: token, file, snapshots and evidence deleted; verdicts → WITHDRAWN; audit |
| `GET /ledger` | `[LedgerClaim{subjectType, subjectId, claimType, value, tier, verdicts:[VerdictSummary{id, verifier, verdict, candidateStatus, sourceLabel, createdAt}]}]` built from the candidate's current resume facts (SKILL, CERTIFICATION, EDUCATION, EXPERIENCE of the latest COMPLETED analysis per resume) and claims |
| `GET /verdicts/{id}` | VerdictDetail: summary + reasons + integrityNotes + probeQuestion + evidence items (full, incl. URLs) |
| `POST /verdicts/{id}/response` | `{action: SHARE|HIDE|RECHECK, note|null}`; RECHECK enqueues a new run of its source |
| `GET /references`, `POST /references` | `{refereeName, refereeEmail, relationship, employerName|null, employerDomain|null, claims:[{subjectType, subjectId}], consent:true}`; max 5 open; sends REFERENCE_REQUEST email with link `${candidate-url}/reference/<token>` |
| `POST /references/{id}/cancel` | |

Public (no auth, rate-limited): `GET /api/v1/public/references/{token}` → `{candidateFirstName, relationship,
employerName, claims:[{id, claimType, value}], expiresAt}`; `POST` same path `{answers:[{claimId, answer, comment}],
refereeComment, confirmIdentity:true}` or `{decline:true}`. 410 when expired/answered. Answer enqueues VERIFY_REFERENCE.

Staff:

| Method + path | Who | Result |
|---|---|---|
| `GET /api/v1/hr/applications/{applicationId}/verification` | org staff with access to the job | `{mode, revealed, claims:[{subjectType, subjectId, claimType, value, tier, verdicts:[StaffVerdict{id, verifier, verdict, ownershipProven, reasons, integrityNotes, probeQuestion, candidateNote, myOrgReview{decision,note,reviewerName,at}|null, evidence:[items]|null (null before reveal), evidenceCount}]}]}`. In SHADOW mode: `claims: []` and `mode: "SHADOW"`. Only SHARED, current verdicts |
| `POST /api/v1/hr/verdicts/{id}/review` | ADMIN, HR_MANAGER, RECRUITER with access to an application of that candidate in their org | `{decision, note}`; VISIBLE mode only; audit `VERDICT_REVIEWED`; re-match |
| `GET/PUT /api/v1/admin/verification/settings` | ADMIN | `{mode, connectors{GITHUB,GITLAB,WEBSITE,DOCUMENT,OPEN_BADGE,REFERENCE}}`; audit |
| `GET /api/v1/admin/verification/shadow-queue?page` | ADMIN, HR_MANAGER | shared current verdicts of candidates who applied to the org, unlabelled by the caller first; evidence only for revealed applications |
| `POST /api/v1/admin/verification/verdicts/{id}/label` | ADMIN, HR_MANAGER | `{labelVerdict, note}` |
| `GET /api/v1/admin/verification/metrics` | ADMIN, HR_MANAGER | `{labelled, agreement, falseCorroborationRate, kappa, byVerifier:[…], byVerdict:[…], readyForVisible: labelled>=150 && fcr<=0.02 && kappa>=0.70}` |

Also: `VerificationEvidenceContributor` (3.5), `AgentServiceClient` (RestClient, token, timeouts 60 s, request id header),
`SystemHealthService` reports agent-service health, candidate data export includes sources/runs/evidence metadata/
verdicts/references, account deletion removes snapshots and files, notifications `VERIFICATION_READY`,
`REFERENCE_REQUEST`, `REFERENCE_ANSWERED`, demo seed (candidate with a `demo-` GitHub source and shared verdicts,
org in VISIBLE mode for the demo org only), configuration keys under `app.verification.*` and `app.agent-service.*`.

## 6. Platform hardening (WP-PLAT)

1. **Staff MFA (TOTP, RFC 6238, SHA-1, 6 digits, 30 s, ±1 step, replay guard via last_used_step)**: enrol
   (`POST /api/v1/me/mfa/enroll` → `{secret, otpauthUri}`), confirm (`POST /me/mfa/confirm {code}` → 10 recovery codes
   shown once), disable (`POST /me/mfa/disable {password, code}`), regenerate codes. Login: when enabled (or required
   by org and not enrolled → `mfaEnrollmentRequired`), `POST /auth/login` returns `{mfaRequired:true, mfaToken}`
   (signed, 5 min, single use) instead of tokens; `POST /auth/mfa/verify {mfaToken, code|recoveryCode}` issues the
   session. Admin toggle `requireStaffMfa`. Candidates unaffected. Lockout counters apply to MFA failures.
2. **OIDC SSO for staff** (Google, Microsoft Entra; generic OIDC config `app.sso.providers.<id>.{client-id,
   client-secret,issuer,authorization-uri,token-uri,jwks-uri,scopes}`), authorization code + PKCE + state + nonce,
   id_token RS256 validated against JWKS (jjwt 0.12 JWK support), `iss`/`aud`/`exp`/`nonce`/`email_verified`. Links to an
   existing enabled staff user by verified email whose org allows the email domain (`sso_allowed_domains`); no
   auto-provisioning. `GET /api/v1/auth/sso/providers` (enabled list), `GET /auth/sso/{provider}/start`, callback →
   sets staff refresh cookie → 302 `${hr-url}/sso/complete`. MFA is satisfied by the IdP (documented).
3. **Multi-instance safety**: background job dedupe key; notification sender claims rows (`UPDATE … SET claimed_by,
   claimed_until WHERE id=? AND (claimed_until IS NULL OR claimed_until < now)`); rate limiter store
   `app.rate-limit.store=memory|database`.
4. **Observability**: `X-Request-Id` filter (accept a safe incoming value or generate), MDC, propagated to ai-service and
   agent-service clients and echoed in responses; Spring Boot structured logging (`logging.structured.format.console:
   ecs`) in the prod profile; `micrometer-registry-prometheus`, `/actuator/prometheus` ADMIN-only unless
   `app.metrics.public=true`; timers around AI and agent calls and job handling.
5. **Interviews**: ICS (`text/calendar; method=REQUEST`) attached to interview scheduled/rescheduled emails and
   `METHOD:CANCEL` on cancel; scorecard editing by its author until the application is decided, with revisions and audit.
6. **Work samples**: candidate file upload with a submission (pdf, zip, docx, pptx, png, jpg ≤ 20 MB, max 3 files),
   staff download (audited), HR cancellation of an assignment with reason (candidate notified).
7. **Fairness**: `from`/`to` date filters on the adverse-impact report.
8. **Demo seed**: work samples, an interview kit, a scheduled interview and a submitted scorecard for the demo jobs.
9. **Security headers** on API responses (`X-Content-Type-Options`, `Referrer-Policy`, `Content-Security-Policy:
   default-src 'none'` for JSON) and a CSP in both portals' nginx configs.

## 7. Frontends (Phase 1, functional; WP-FE-CAND2 and WP-FE-HR2)

Candidate portal: "Strengthen your application" (`/verification`): connect GitHub (OAuth button when available, else
username + bio-code), GitLab, website (DNS TXT / well-known file / page code instructions with copy buttons and
"Check now"), document upload, Open Badge (URL or paste), references (form + status list); consent text per source;
run status with polling while PENDING/RUNNING; ledger table of claims with verdict chips; verdict detail drawer
(reasons with evidence links, retrieved time, hash, integrity notes, probe question) and Share / Hide / Re-check with
note; revoke with confirmation. Public referee page `/reference/:token` (no sign-in, accessible form, decline option).
Work-sample file attachments on submit.

HR portal: applicant view "Verification" tab (mode banner, per-claim verdicts, reasons, evidence after reveal,
integrity notes, probe questions, Confirm / Overrule with note); admin "Verification" page (mode switch with metrics
and readiness, connector toggles, shadow labelling queue); account security (MFA enrol with QR from the otpauth URI,
recovery codes, disable; org "require MFA" toggle for admins); login MFA step and SSO buttons; `/sso/complete`;
work-sample attachment downloads and assignment cancellation; scorecard edit; fairness date range.

## 8. Gates (all must pass before integration is called done)

| Gate | Command |
|---|---|
| G-PY ai-service | `cd ai-service && python -m pytest -q` and `python -m eval.run --check` |
| G-AG agent-service | `cd agent-service && python -m pytest -q` and `python -m eval.run --check` |
| G-SQL | `python tools/check_migrations_pg.py` (V1–V24 on PostgreSQL 16) |
| Entity/schema | `python tools/check_entity_schema.py` |
| G-JAVA-STATIC | `python tools/java_static_check.py backend/src` |
| G-FE | both portals: `pnpm install --frozen-lockfile && pnpm build` (+ `pnpm test` where present) |
| G-JAVA-REAL | `cd backend && ./mvnw verify` (JDK 21) — run by Kshitij; cannot run in the build sandbox |

## 9. Work packages and file ownership

| WP | Owns | May touch (minimal, re-read before editing) |
|---|---|---|
| WP-AG | `agent-service/**`, `tools/sync_taxonomy.py` | `docker-compose.yml` (add service), `.github/workflows/ci.yml` (add job), `.env.example` |
| WP-VBE | `backend/.../verification/**` (new package), V21, V22, backend tests for it | `EvidenceContributor` users, `SystemHealthService`, `CandidateDataExportService`, `CandidateAccountDeletionService`, `DemoDataSeeder`, `SecurityConfig` (public paths), `application.yml` (`app.verification`, `app.agent-service`), notification templates, `docker-compose.yml` backend env |
| WP-PLAT | `backend/.../auth/mfa/**`, `auth/sso/**`, `observability/**`, V23, V24, related tests | `AuthController`/`AuthService` login path, `BackgroundJobService`, notification sender, `RateLimitFilter`, interview/work-sample/fairness services, `DemoDataSeeder`, `SecurityConfig`, `application*.yml`, `pom.xml` (prometheus only), portals' `nginx.conf` |
| WP-FE-CAND2 | `frontend/src/**` | — |
| WP-FE-HR2 | `hr-frontend/src/**` | — |

Each WP writes `docs/plan/WP-<name>-NOTES.md` (what was built, decisions, deviations, tests, open risks).
