# WP-PLAT notes: platform hardening

Plan v2 sections 3.3 (V23, V24), 6 (items 1 to 9) and 9. Status: implemented, **not compiled**. The sandbox has no JDK 21 and no Maven Central access. G-JAVA-REAL (`./mvnw verify`) has to run on a JDK 21 machine.

| Gate | Result |
|---|---|
| G-JAVA-STATIC `python3 -B tools/java_static_check.py backend/src` | 575 files, 0 errors |
| Entity/schema `python3 -B tools/check_entity_schema.py` | 474 mapped columns of 41 entities, 24 migrations: 0 errors, 0 warnings |
| G-SQL `python3 -B tools/check_migrations_pg.py` | V1 to V24 apply to PostgreSQL 16 (includes WP-VBE's V21/V22) |
| Independent review | A reviewer agent read every file listed in Section 9 and checked it against the jjwt 0.12.5 and Spring 6.2 APIs. No compile errors, startup failures or failing assertions found. Four minor runtime issues were found and fixed: request id on error dispatch; dedupe now catches only `DuplicateKeyException`; reschedule invites are sent without an interviewer; scorecard edits that change nothing are no-ops. |

Envelope: as before, `{success, message?, data?, errors?, timestamp}`. Error codes are the start of `message`, for example `"MFA_NOT_CONFIGURED: ..."` (the envelope has no code field).

---

## 1. Endpoints as implemented

### 1.1 Staff MFA (TOTP)

The TOTP parameters follow RFC 6238:

| Parameter | Value |
|---|---|
| Algorithm | HMAC-SHA1 |
| Digits | 6 |
| Time step | 30 s |
| Accepted drift | ±1 step |
| Secret | 20 random bytes, Base32 without padding |
| Replay guard | a step must be later than `user_mfa.last_used_step` |

The secret is stored with `SecretCipher` (context `mfa:<userId>`). Without `APP_ENCRYPTION_KEY`, every enrol or confirm call returns **503** with `message` starting `MFA_NOT_CONFIGURED`. A sign-in of a user who already has MFA enabled also returns 503 in that case. Candidates get **403** on `/me/mfa*`, and their sign-in never involves MFA.

| Method | Path | Auth | Body → `data` |
|---|---|---|---|
| POST | `/api/v1/auth/login` | public | Unchanged for candidates and for staff without MFA. Staff with MFA, or whose organisation requires it, get `MfaChallenge` instead of `AuthResponse`: no access token and no cookie. |
| POST | `/api/v1/auth/mfa/verify` | public (10/min per IP) | `{mfaToken, code?, recoveryCode?}`, exactly one of `code` and `recoveryCode`, otherwise **400**. → `AuthResponse` plus the portal's refresh cookie (`rp_refresh_staff` with `X-RecruitPro-Portal: staff`). See failure rules below. |
| GET | `/api/v1/me/mfa` | bearer (staff) | → `{enabled, enabledAt|null, pendingEnrollment, recoveryCodesRemaining, requiredByOrganization, available}`. `available=false` means there is no encryption key. |
| POST | `/api/v1/me/mfa/enroll` | bearer **or** `{mfaToken}` body | Body optional: `{mfaToken?}`. → `{secret, otpauthUri}`. Replaces an unconfirmed enrolment. **409** if MFA is already enabled. |
| POST | `/api/v1/me/mfa/confirm` | bearer **or** `mfaToken` | `{code, mfaToken?}` → `{recoveryCodes: string[10], session?: AuthResponse}`. See confirmation rules below. |
| POST | `/api/v1/me/mfa/disable` | bearer | `{password, code}`, where `code` is a 6-digit TOTP code or a recovery code. → `null`. **400** for a wrong password or code. **400** while the organisation requires MFA. |
| POST | `/api/v1/me/mfa/recovery-codes` | bearer | No body. → `{recoveryCodes: string[10]}`. The earlier codes stop working. **400** if MFA is not enabled. |

**`/auth/mfa/verify` failures:**
- A wrong code is **401** `"The verification code is not valid"`. It counts as a failed sign-in: five failures lock the account for 15 minutes, as for passwords.
- An expired, used or forged token is **401** `"This sign-in step has expired. Please sign in again."`.
- A locked account is **401** `"Too many failed attempts..."`.

**`/me/mfa/confirm`:**
- A wrong code is **400**. With an `mfaToken`, it also counts towards the lockout.
- With the enrolment `mfaToken`, the token is consumed and `session` holds the new sign-in, and the portal's refresh cookie is set. That is how a first enrolment that the organisation requires completes the login.
- With a bearer token, `session` is absent.

```ts
type MfaChallenge = { mfaRequired: true; mfaEnrollmentRequired: boolean; mfaToken: string; expiresInSeconds: 300 }
// mfaEnrollmentRequired=true → call /me/mfa/enroll and /me/mfa/confirm with {mfaToken}; otherwise /auth/mfa/verify.
type MfaStatus = { enabled: boolean; enabledAt: string|null; pendingEnrollment: boolean; recoveryCodesRemaining: number;
                   requiredByOrganization: boolean; available: boolean }
type MfaEnroll = { secret: string /* Base32, 32 chars */; otpauthUri: string }
// otpauthUri = otpauth://totp/RecruitPro:<url-encoded email>?secret=<secret>&issuer=RecruitPro&algorithm=SHA1&digits=6&period=30
type MfaConfirm = { recoveryCodes: string[] /* "abcde-fgh23", shown once */; session?: AuthResponse }
```

**How to tell the login answers apart:** `data.mfaRequired === true` is a challenge; otherwise `data.accessToken` is present.

**Recovery codes:**
- Format: 10 characters from `a-z` (no i, l, o) and `2-9`, shown as `xxxxx-xxxxx`.
- Matching ignores case, spaces and hyphens.
- Each is stored as a bcrypt hash (the existing `PasswordEncoder`) and is single use.

**`mfaToken`:**
- An HS256 JWT with issuer `recruitpro-mfa`, signed with a key derived from `app.jwt.secret` with HMAC and a purpose label, so it can never be accepted as an access token.
- Its `jti` is a one-time token stored hashed in `user_tokens`, with purpose `MFA_LOGIN` or `MFA_ENROLL`, valid for 5 minutes.
- Issuing a new token invalidates earlier ones of the same purpose.
- A wrong code does not consume the token; the lockout counter limits guessing. Success consumes it.
- The password step no longer resets the failure counter for MFA users; only a successful second step does. Otherwise re-entering the password would reset the MFA guesses.

**Organisation setting:**
- `PATCH /api/v1/admin/organization` (ADMIN) accepts `requireStaffMfa: boolean` and `ssoAllowedDomains: string[]`.
- `GET` and `PATCH` responses now return `{id, name, slug, blindReview, shareVerificationStatus, requireStaffMfa, ssoAllowedDomains: string[]}`.
- `requireStaffMfa=true` without the encryption key is **503** `MFA_NOT_CONFIGURED`.
- Domains are lower-cased, a leading `@` is removed, exact domains only (no wildcards), at most 20 and at most 500 characters stored; an invalid domain is **400**. `[]` turns SSO off.
- The requirement applies to admins too. Existing sessions are not ended; the requirement applies at the next sign-in.

**Audit:** MFA_ENABLED, MFA_DISABLED, MFA_RECOVERY_CODES_REGENERATED, MFA_RECOVERY_CODE_USED, MFA_VERIFICATION_FAILED, plus LOGIN_LOCKED from the existing lockout.

### 1.2 Staff SSO (OIDC)

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/auth/sso/providers` | → `[{id, name}]`, only providers with client id and secret configured. `[]` when `APP_ENCRYPTION_KEY` is missing. |
| GET | `/api/v1/auth/sso/{provider}/start` | Browser navigation: **302** to the provider. With `Accept: application/json`: `{authorizeUrl}`. Unknown or unconfigured provider: 302 to `<hr-url>/login?ssoError=not_configured`, or JSON **503** `SSO_NOT_CONFIGURED: ...`. |
| GET | `/api/v1/auth/sso/{provider}/callback?code&state[&error]` | **302** to `<hr-url>/sso/complete` with the `rp_refresh_staff` cookie set, or to `<hr-url>/login?ssoError=<code>`. |

**After `/sso/complete`:** the HR portal calls `POST /api/v1/auth/refresh` with `X-RecruitPro-Portal: staff` to get the access token. The cookie is `SameSite=Strict`, `Path=/api/v1/auth`. It is set on the top-level navigation response and sent on the portal's own later fetch.

**`ssoError` codes:**

| Code | Meaning |
|---|---|
| `invalid_state` | Unknown, used, expired or wrong-provider state |
| `cancelled` | The provider returned `error=access_denied` |
| `provider_error` | Any other provider error or a missing code |
| `not_configured` | Unknown or unconfigured provider |
| `token_exchange_failed` | The authorisation code could not be exchanged |
| `invalid_token` | The id_token failed validation (signature, kid, iss, aud/azp, exp, iat, nonce, subject) |
| `email_not_verified` | No e-mail, or `email_verified` is not true (Microsoft's `xms_edov=true` is also accepted) |
| `domain_not_allowed` | The e-mail domain is not in the organisation's `sso_allowed_domains` |
| `no_account` | No enabled staff account matches; candidates are never matched |
| `account_disabled` | The account is disabled |
| `account_locked` | The account is locked |

**Flow details:**
- **Authorisation request:** code flow with PKCE S256 (the 86-character verifier is encrypted in `sso_login_states.code_verifier_enc`). `state` and `nonce` are stored as SHA-256 hashes, valid 10 minutes, single use. The state is marked used, and that is committed, *before* the token exchange. `prompt=select_account` is sent.
- **Token exchange:** the token endpoint gets `client_secret_post` with `code_verifier`.
- **id_token validation:**
  - Only RS256 and ES256 are accepted.
  - The key is chosen by `kid` from the provider JWKS, parsed per key with jjwt `Jwks.parser()`; `use=enc` and unparseable keys are skipped.
  - The JWKS is cached for 6 h and re-fetched on an unknown `kid`, at most once per 30 s.
  - Claims checked: `iss`, where Microsoft's `{tenantid}` template is filled from `tid`. `aud` must contain the client id, and `azp` must match when there are several audiences. `exp` is required; `iat` must not be in the future (60 s skew) or older than 600 s. The SHA-256 of `nonce` must match the stored hash. `sub` is required, and a verified e-mail.
- **Linking:**
  - A `user_identities` row (provider, sub) wins. Otherwise the user is matched by the exact verified e-mail.
  - In both cases the account must be an enabled, non-deleted staff account, and its organisation's `sso_allowed_domains` must contain the e-mail domain of both the IdP address and the account address.
  - The identity is then linked and audited as `SSO_IDENTITY_LINKED`. **No auto-provisioning.**
- **After a successful link:**
  - `email_verified_at` is set when it was null and the lockout counter is reset.
  - The sign-in is audited as `SSO_LOGIN`; refusals are audited as `SSO_LOGIN_REFUSED`, with the e-mail domain only.
  - MFA counts as satisfied by the IdP.
- **Discovery:** set `issuer` only, and `authorization_endpoint`, `token_endpoint`, `jwks_uri` and the canonical issuer are fetched lazily from `<issuer>/.well-known/openid-configuration` and cached. Explicit `authorization-uri`, `token-uri` and `jwks-uri` skip discovery.

### 1.3 Interviews

- **ICS invitations:** `INTERVIEW_SCHEDULED` (candidate) and `INTERVIEW_ASSIGNED` (interviewer) e-mails carry `invite.ics` (`text/calendar; charset=UTF-8; method=REQUEST`). `INTERVIEW_CANCELLED` carries `cancel.ics` (`method=CANCEL`).
  - **Event fields:**
    - `UID:interview-<id>@<domain of app.mail.from>`.
    - `SEQUENCE` is `interviews.ics_sequence`: 0 when scheduled, +1 on every reschedule with a changed time or place, +1 on cancel.
    - `DTSTART`/`DTEND` in UTC (`...Z`), `ORGANIZER` = `app.mail.from`.
  - **Attendees:** the candidate's copy lists the candidate and the interviewer. The interviewer's copy lists the interviewer, plus the candidate only once the identity is revealed (blind review). The summary uses the blind label until then.
  - **Format:** RFC 5545 escaping, CRLF line ends, folding at 75 octets.
  - **Delivery:** in-app copies have no attachment. A reschedule now re-sends the candidate's invitation even when the interviewer's account is gone.
- **Scorecard edit** (author only, until the application is SELECTED, REJECTED or WITHDRAWN):

| Method | Path | Body → `data` |
|---|---|---|
| PUT | `/api/v1/interviews/{id}/scorecard` | Same body as POST: `{ratings:[{questionId, requirementId?, rating 1..4, notes?}], recommendation, summary?}` → `Interview` |
| PUT | `/api/v1/hr/interviews/{id}/scorecards/{scorecardId}` | Alias with the same rules; a `scorecardId` that is not this interview's scorecard is **404** |
| GET | `/api/v1/interviews/{id}/scorecard/revisions` | → `[{id, revisedByUserId, revisedByName, revisedAt, previous: Scorecard}]`, newest first. The author, ADMIN or HR_MANAGER; anyone else **403**. |

  - **Refusals:**
    - Someone other than the author (managers included) gets **403**.
    - No scorecard yet: **404**.
    - Decided application or cancelled interview: **400**.
    - Invalid ratings: **400**, same validation as submit. Requirement links come from the kit.
  - **Effects:** the replaced version goes to `interview_scorecard_revisions`. The edit is audited as `SCORECARD_UPDATED` (before and after summary) and queues re-matching like a submission. An edit that changes nothing is a no-op.
  - **`Scorecard` now has extra fields:** `{id, ratings, recommendation, summary, submittedAt, updatedAt, revisionCount}`. Existing fields are unchanged.

### 1.4 Work samples

| Method | Path | Roles | Body → `data` |
|---|---|---|---|
| POST | `/api/v1/candidates/me/work-samples/{assignmentId}/submit` | CANDIDATE | **multipart/form-data**; see below. → `CandidateWorkSample`. The JSON variant is unchanged. |
| GET | `/api/v1/candidates/me/work-samples/{assignmentId}/files/{fileId}` | CANDIDATE | Own file as an attachment; another candidate's file is **404** |
| GET | `/api/v1/hr/work-sample-assignments/{assignmentId}/files/{fileId}` | ADMIN, HR_MANAGER, RECRUITER | File as an attachment; audited `WORK_SAMPLE_FILE_DOWNLOADED`; another organisation is **404** |
| POST | `/api/v1/hr/work-sample-assignments/{assignmentId}/cancel` | ADMIN, HR_MANAGER | `{reason}` (1..1000 characters) → `WorkSampleAssignment` (status CANCELLED) |

- **Multipart submit, fields:**
  - `responseText`: optional when at least one file is attached; ≤ 50000 characters.
  - `responseUrl`: optional, http(s), ≤ 500 characters.
  - `files`: repeated part, 0..3.
- **Allowed files:** pdf, zip, docx, pptx, png, jpg/jpeg, at most 20 MB each.
  - The type comes from the extension and must match the magic bytes. docx and pptx must be a ZIP whose central directory lists `[Content_Types].xml` and `word/document.xml` or `ppt/presentation.xml`.
  - A ZIP is never extracted.
  - `contentType` is set by the server; names are stripped of any path and control characters.
  - Every check runs before anything is stored. On a rollback the stored files are deleted.
- **Downloads:** `Content-Type` is the stored type, `Content-Disposition: attachment; filename*=UTF-8''...`, with `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- **Cancellation:**
  - Allowed from ASSIGNED, STARTED, SUBMITTED or SCORED. CANCELLED or EXPIRED is **400**. RECRUITER is **403**.
  - The candidate gets `WORK_SAMPLE_CANCELLED` (new template; e-mail plus inbox) with the reason.
  - Audited as `WORK_SAMPLE_CANCELLED`. A cancelled SCORED assignment stops counting as DEMONSTRATED evidence and re-matching is queued.
  - The candidate can no longer start or submit it.
- **New fields** (both are `@JsonInclude(ALWAYS)`, so absent values are explicit nulls or `[]`):
  - `WorkSampleAssignment` (staff): `files: WorkSampleFile[]`, `cancelledAt|null`, `cancelReason|null`, `cancelledByName|null`.
  - `CandidateWorkSample`: `files: WorkSampleFile[]`, `cancelledAt|null`, `cancelReason|null`.
  - `WorkSampleFile = {id, originalName, contentType, sizeBytes, sha256 (hex), uploadedAt}`.
- **Account deletion** removes the candidate's work-sample files: the rows at once, the stored files after commit.

### 1.5 Fairness

`GET /api/v1/hr/jobs/{jobId}/fairness?from=YYYY-MM-DD&to=YYYY-MM-DD` and `GET /api/v1/admin/fairness?from&to`:
- Both parameters are optional ISO dates and inclusive (UTC days), filtering by `applications.created_at`.
- `from > to` or a malformed date is **400**.
- The response shape is unchanged.

### 1.6 Observability and headers

- **`X-Request-Id`:**
  - `RequestIdFilter` is the first servlet filter.
  - An incoming value matching `[A-Za-z0-9._-]{8,64}` is reused; otherwise a UUID is generated.
  - The id is put in the MDC as `requestId`, echoed in the response header, and kept across error dispatches.
  - It is sent to the ai-service by `AiServiceClient` (`RequestIdClientInterceptor`). WP-VBE's `AgentServiceClient` reads the same MDC key.
  - Each background job run gets `job-<jobId>-<8 hex>`.
  - It is not sent to external identity providers.
- **Metrics:**
  - `micrometer-registry-prometheus` exposes `/actuator/prometheus`, ADMIN only unless `app.metrics.public=true`.
  - Timers: `recruitpro.ai.call` (tags `endpoint`, `outcome` = SUCCESS, CLIENT_ERROR, SERVER_ERROR or IO_ERROR) and `recruitpro.job.handle` (tags `type`, `outcome` = success, retry, dead or failed).
- **Logging:** the prod profile uses `logging.structured.format.console: ecs`. The dev console pattern shows `[requestId]`.
- **Security headers** on every backend response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. `/api/v1/**` and `/actuator/**` also get `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`. Swagger UI (dev only) gets no CSP. Headers are set before Spring Security, so 401, 403 and 429 answers carry them too.
- **nginx (both portals):** `nginx.conf` is now an envsubst template (copied to `/etc/nginx/templates/default.conf.template`).
  - CSP: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' ${API_ORIGIN}; frame-ancestors 'none'; base-uri 'self'; form-action 'self' ${API_ORIGIN}; object-src 'none'`, plus nosniff, DENY, `Referrer-Policy: strict-origin-when-cross-origin` and `Permissions-Policy`. The headers are repeated in every location.
  - `API_ORIGIN` is the origin of `VITE_API_BASE_URL`. It is a build arg and env var in both Dockerfiles, defaulting to `http://localhost:8080`; override it at run time with `-e API_ORIGIN=https://api.example.com`.

### 1.7 Multi-instance safety

- **Background jobs:**
  - A new PENDING job gets the unique `dedupe_key` `<TYPE>:<entityId>`. Claiming the job clears it, so a follow-up can be queued while it runs.
  - The insert runs in a JDBC savepoint on the shared JPA connection. Losing the race on the unique key (`DuplicateKeyException`) rolls back to the savepoint, so the caller's transaction stays usable on PostgreSQL, and returns the winner's job.
  - The new `enqueueJob(type, id, duplicateStatuses)` returns `EnqueueResult(job, created)`. `enqueue` and `enqueueFollowUp` keep their boolean API.
  - V24 backfills the key on the oldest PENDING job per (type, entity).
- **Notification sender:**
  - It lists only unclaimed PENDING rows. It claims each row with `UPDATE ... SET claimed_by, claimed_until = now + 2 min WHERE id = ? AND status = PENDING AND (claimed_until IS NULL OR claimed_until < now)` in its own transaction, then delivers it. Delivery clears the claim.
  - A crashed instance's claim expires.
  - Rows with an attachment are sent as MIME multipart.
- **Rate limiter:**
  - `app.rate-limit.store=memory` (the default) is the existing sliding window.
  - `database` keeps fixed-window counters in `rate_limit_buckets`. Each request runs a short `REQUIRES_NEW` transaction: `SELECT ... FOR UPDATE`, then insert, reset or increment.
  - A concurrent first insert hits the primary key and is retried in a new transaction (no `ON CONFLICT`).
  - Keys over 200 characters are hashed. Rows older than 2 h are purged every 500 calls.
  - A store failure lets the request through (fail-open, logged).

### 1.8 Demo seed

`DemoAssessmentSeeder` is called at the end of `DemoDataSeeder.seed`, through setter injection so WP-VBE's constructor is untouched. Every piece is created only when missing. For "Backend Engineer (Java)" of Acme Labs it creates:
- an interview kit (4 questions linked to Java, Spring Boot, PostgreSQL and Backend development);
- an ACTIVE work sample with a 2-criterion rubric;
- an application from `candidate@demo.test` in INTERVIEWING, identity revealed, with SHORTLIST and MOVE_TO_INTERVIEW history. If the candidate has no resume, a generated one-page PDF resume is created and queued for parsing;
- a SCORED assignment (4 and 3, mean 3.50);
- a COMPLETED interview two days ago with interviewer@acme.test's submitted scorecard (YES);
- a SCHEDULED interview in three days;
- a MATCH_APPLICATION job.

If WP-VBE's seed already created that application in another status, the work sample and interviews are skipped, with a log line.

## 2. Configuration keys (new)

| Key (env) | Default | Purpose |
|---|---|---|
| `app.encryption.key` (`APP_ENCRYPTION_KEY`) | empty | Shared with WP-VBE. MFA and SSO need it. |
| `app.mfa.issuer` (`MFA_ISSUER`) | RecruitPro | otpauth issuer |
| `app.sso.redirect-base-url` (`SSO_REDIRECT_BASE_URL`) | http://localhost:8080 | Callback `<base>/api/v1/auth/sso/<id>/callback` |
| `app.sso.state-ttl-minutes`, `clock-skew-seconds`, `max-token-age-seconds`, `http-timeout-ms` | 10, 60, 600, 10000 | |
| `app.sso.providers.<id>.{name, client-id, client-secret, issuer, authorization-uri, token-uri, jwks-uri, scopes}` | `google` (issuer accounts.google.com), `microsoft` (issuer `https://login.microsoftonline.com/${SSO_MICROSOFT_TENANT_ID:organizations}/v2.0`), `oidc` (generic) | Env: `SSO_GOOGLE_CLIENT_ID/SECRET`, `SSO_MICROSOFT_CLIENT_ID/SECRET/TENANT_ID`, `SSO_OIDC_NAME/CLIENT_ID/CLIENT_SECRET/ISSUER/SCOPES` |
| `app.rate-limit.store` (`RATE_LIMIT_STORE`) | memory | `memory` or `database` |
| `app.metrics.public` (`METRICS_PUBLIC`) | false | `/actuator/prometheus` without authentication |
| `app.instance-id` (`APP_INSTANCE_ID`) | host name + random suffix | Notification claims |
| `app.work-samples.files.max-size-mb` | 20 | |
| `app.file-storage.work-sample-dir` (`WORK_SAMPLE_STORAGE_PATH`) | `${app.file-storage.upload-dir}/work-samples` | |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 20MB / 62MB (were 10MB / 11MB) | Resumes keep their own 10 MB check |
| `management.endpoints.web.exposure.include` | adds `prometheus` | |
| `logging.structured.format.console` (prod) | ecs | |
| `API_ORIGIN` (portal images) | http://localhost:8080 | nginx CSP `connect-src` |

`backend/.env.example` documents all of them.

## 3. Data model (V23, V24)

- **V23:**
  - `user_mfa` (user_id PK, secret_enc, enabled_at, last_used_step, created_at, updated_at).
  - `user_recovery_codes` (id, user_id, code_hash, used_at, timestamps).
  - `organizations.require_staff_mfa BOOLEAN DEFAULT FALSE NOT NULL` and `organizations.sso_allowed_domains VARCHAR(500)`.
  - `user_identities` (id, user_id, provider, subject, email, last_login_at, timestamps, unique(provider, subject)).
  - `sso_login_states` (id, state_hash unique, **nonce_hash**, code_verifier_enc, provider, portal, expires_at, used_at, timestamps).
  - `user_tokens` CHECK extended with `MFA_LOGIN` and `MFA_ENROLL`.
- **V24:**
  - `background_jobs.dedupe_key VARCHAR(120) UNIQUE` (backfilled).
  - `notifications.claimed_by`, `claimed_until`, **`attachment_name`, `attachment_content_type`, `attachment_content TEXT`**.
  - `rate_limit_buckets` (bucket_key PK, window_start, **hit_count**).
  - `work_sample_assignments.cancelled_at`, `cancelled_by_user_id` (FK, `ON DELETE SET NULL`), `cancel_reason`. CANCELLED was already in the CHECK.
  - `work_sample_submission_files` (as planned, plus `updated_at`).
  - `interview_scorecard_revisions` (as planned, plus `updated_at`).
  - **`interviews.ics_sequence INTEGER DEFAULT 0 NOT NULL`**.

All of it stays within the H2 subset: no partial indexes, no `ON CONFLICT`, one `ALTER` per column.

## 4. Deviations from the plan and brief

1. **Login response.** The plan shows `{mfaRequired:true, mfaToken}` and `{mfaEnrollmentRequired:true, mfaToken}`. Implemented: one shape, `{mfaRequired:true, mfaEnrollmentRequired:boolean, mfaToken, expiresInSeconds:300}`, for both cases.
2. **Enrolment with a token.** `/me/mfa/enroll` and `/me/mfa/confirm` take the token in the JSON body (`mfaToken`), not in a header. Confirming with it returns `session` (an `AuthResponse`) and sets the refresh cookie.
3. **`POST /me/mfa/recovery-codes`** needs no body and no extra code; the access token is enough.
4. **`/me/mfa/disable` `code`** also accepts a recovery code.
5. **`SSO /start`** returns JSON `{authorizeUrl}` when `Accept: application/json` is sent; the default is a 302.
6. **The scorecard edit** keeps the existing path shape, `PUT /api/v1/interviews/{id}/scorecard`. The brief's `PUT /api/v1/hr/interviews/{id}/scorecards/{scorecardId}` exists as an alias. There is also a new `GET .../scorecard/revisions`.
7. **Column names:**
   - `sso_login_states.nonce_hash` instead of `nonce`, because it stores a hash.
   - `rate_limit_buckets.hit_count` instead of `count`, to avoid a reserved-word risk.
   - Extra columns: `notifications.attachment_*`, `interviews.ics_sequence`, `user_identities.last_login_at`, and `updated_at` on the new tables.
8. **The dedupe key** is set only on freshly enqueued PENDING jobs. A job that returns to PENDING for a retry has no key; enqueue still finds it by status. Setting the key again on retry could collide with a follow-up job queued meanwhile.
9. **Blind review in ICS.** The interviewer's invitation lists the candidate as an attendee only after reveal.
10. **New template** `WORK_SAMPLE_CANCELLED` (e-mail plus inbox).
11. **Extra response fields** (Sections 1.3 and 1.4) are additive. `OrganizationResponse` gained `requireStaffMfa` and `ssoAllowedDomains`.
12. **Recovery codes** are hashed with bcrypt, the existing `PasswordEncoder`. With 10 codes, a recovery-code check costs at most 10 bcrypt comparisons.

## 5. Tests (JUnit 5, H2)

| Class | Covers |
|---|---|
| `auth/mfa/TotpTest` | RFC 6238 SHA-1 vectors (59 s to 20000000000 s, 8 and 6 digits); ±1 step and not ±2; replay guard; malformed codes; RFC 4648 Base32 vectors; recovery-code normalisation |
| `auth/mfa/MfaIntegrationTest` | Direct sign-in without MFA; candidates unaffected (403 on `/me/mfa`). Enrol, wrong code 400, confirm, 10 codes, status. Login challenge without cookie; replay of the confirmation code refused; verify sets `rp_refresh_staff`; failure counter reset; token single use, forged token refused, missing code 400. Recovery codes single use and regenerated codes. Five wrong codes lock the account (audit rows). Organisation requirement: enrolment token not usable as a login token; enrol and confirm with `mfaToken` create a session and cookie; disable refused while required. Disable needs password and code. Organisation SSO-domain validation. |
| `auth/mfa/MfaNotConfiguredIntegrationTest` | 503 `MFA_NOT_CONFIGURED`; `requireStaffMfa` refused; no SSO providers |
| `auth/sso/SsoIntegrationTest` | JDK `HttpServer` stub IdP (JWKS plus token endpoint) and a generated RSA key. Provider list; unconfigured provider. Success: PKCE S256 verified at the token endpoint, cookie, identity linked, audits; state replay and unknown state; second sign-in via the identity. Refused: bad nonce, wrong aud, wrong iss, expired, iat in the future, `email_verified=false`, no e-mail, wrong key, unknown kid. Domain not allowed (two cases), unknown user, candidate, disabled user: no identity created, no account created, refusals audited. Provider `access_denied` gives `cancelled`; a failed exchange gives `token_exchange_failed`. |
| `jobs/BackgroundJobDedupeIntegrationTest` | Key on PENDING jobs; a duplicate returns the existing job; claiming releases the key and allows a follow-up; retried jobs still count as duplicates; the lost insert race returns null and the surrounding transaction stays usable; the database rejects a second key while NULL keys never collide |
| `notification/NotificationClaimIntegrationTest` | Only one instance holds a claim until it expires; the sender skips foreign claims and clears its own; the attachment is stored on the EMAIL row |
| `security/ratelimit/DatabaseRateLimiterStoreIntegrationTest` | Store selection, per-key limits, window reset, hashed long keys, 20 concurrent requests across 4 threads never exceed the limit, the filter answers 429 with `Retry-After` from the database store |
| `observability/RequestIdFilterTest` | Safe ids reused; unsafe, short, long or CRLF ids replaced by a UUID; MDC set and cleared; client interceptor sends the header (`MockRestServiceServer`) |
| `security/headers/SecurityHeadersIntegrationTest` | Headers and CSP on 200, 401 and 403; the request id is echoed; Prometheus gives 401 or 403 without ADMIN |
| `interview/service/IcsCalendarTest` | Structure, UTC times, CRLF, METHOD REQUEST/CANCEL, STATUS, SEQUENCE, ORGANIZER/ATTENDEE with quoted CN, escaping, 75-octet folding with multi-byte characters, unfolding round trip, mail-from parsing |
| `interview/InterviewPlatformIntegrationTest` | ICS on schedule, reschedule and cancel (same UID, SEQUENCE 0/1/2, METHOD; no attachment on inbox rows). Scorecard edit by the author; other interviewer and manager 403; invalid rating 400; revision row, audit, re-match; revisions endpoint and its permissions; alias 404 and 200; a no-op edit keeps the history; decided application 400; no scorecard 404. |
| `assessment/service/WorkSampleFileServiceTest` | All six types accepted with matching content; mismatches refused (pdf/png/jpg/zip/docx/pptx); exe, missing extension, empty and oversize files refused; at most 3 files, empty parts ignored; name sanitising; ZIP central directory read without extraction |
| `assessment/WorkSampleFilesIntegrationTest` | Multipart submit with 2 files; staff list shows them; audited staff download (headers, bytes); other organisation 404, interviewer 403, unknown file 404; candidate's own download and another candidate's 404. Four files, disguised content, exe and empty submission are 400 and nothing is stored; files-only submission; JSON submission still works. Cancellation: roles, tenancy, blank reason, success fields, second cancel 400, audit, notification, re-match, no longer SCORED, candidate view, submit after cancel 400. |
| `fairness/FairnessDateFilterIntegrationTest` | Inclusive ranges, single day, open-ended ranges, empty range, `from > to` 400, malformed date 400, organisation report |

`support/TestDatabase` cleans the new tables.

## 6. What could not be verified without compiling and running

- **Compilation itself.** Nothing was compiled; the static checker and one independent review are the only checks. Spots to watch at the first `./mvnw verify`:
  - jjwt 0.12.5 `Jwks.parser()` on keys carrying `x5c` (Microsoft's JWKS);
  - `Locator<Key>` given as a lambda;
  - `MockRestServiceServer.bindTo(RestClient.Builder)`;
  - records with an extra constructor (`AuthSession`, `OrganizationResponse`, `OrganizationUpdateRequest`);
  - `ApiResponse<Object>` returned from `AuthController.login`.
- **Savepoint in `BackgroundJobService.insertPending`.** It uses `DataSourceUtils.getConnection` inside the JPA transaction, which relies on `JpaTransactionManager` exposing Hibernate's connection. On PostgreSQL this is what keeps the caller's transaction usable after a unique violation. The H2 test cannot prove that, because H2 does not abort the transaction on errors. A true two-instance race (two threads, uncommitted rows) was not tested because H2's lock behaviour makes it flaky.
- **H2 `SELECT ... FOR UPDATE` under contention** in the concurrent rate-limit test (expected to work; PostgreSQL certainly does).
- **Real identity providers** were not exercised: Google, and Entra with `xms_edov` or `{tenantid}`. Nor were real SMTP with ICS attachments in Gmail and Outlook, nginx with envsubst, or a Prometheus scrape (tests disable metrics export, so `/actuator/prometheus` is only tested for 401 and 403).
- **The browser accepting the `SameSite=Strict` refresh cookie** set on the SSO callback, a cross-site top-level navigation. Browsers accept Set-Cookie on top-level navigations; the portal's own later `POST /auth/refresh` sends it.

## 7. Risks and open items

- **Prometheus.** A scraper cannot easily hold a 15-minute ADMIN token, so deployments should set `METRICS_PUBLIC=true` and keep `/actuator` off the public network (it is not proxied by the portals).
- **The fixed-window database limiter** can let up to twice the limit through around a window boundary. Every rate-limited request costs one short transaction.
- **SSO depends on the encryption key** (for the PKCE verifier). Without it, providers are hidden.
- **SSO on a stolen IdP account** bypasses MFA by design (documented in the plan). Organisations should enforce MFA at the IdP.
- **Turning `requireStaffMfa` on** also forces admins to enrol at their next sign-in. Sessions that already exist stay valid until refresh-token expiry; the requirement is checked at sign-in only.
- **QR codes in the HR portal** must be rendered locally (`data:`, `blob:` or canvas). The nginx CSP blocks external QR-image services.
- **Work-sample file names** may contain identity. Work samples exist only after shortlisting, which reveals the identity, so blind review is not weakened.
- **Shared files with WP-VBE** were edited minimally and re-read first: `SecurityConfig`, `application.yml`/`-prod.yml`, `DemoDataSeeder` (setter only), `CandidateAccountDeletionService` (setter plus 3 lines), `NotificationTemplate`, `AuditActions`, `TestDatabase`, `pom.xml` and `backend/.env.example`. `docker-compose.yml` is unchanged; the portals' `API_ORIGIN` default matches the compose URLs.

## 8. What the frontends must know

**HR portal:**
- **Login:** if `data.mfaRequired`, show the code step. When `mfaEnrollmentRequired` is also true, enrol first: `POST /me/mfa/enroll {mfaToken}`, show a QR of `otpauthUri` rendered locally, then `POST /me/mfa/confirm {code, mfaToken}`. That returns `recoveryCodes` and `session`; adopt `session` like a login response. Otherwise `POST /auth/mfa/verify {mfaToken, code}` or `{mfaToken, recoveryCode}`.
- **SSO:** list `GET /auth/sso/providers` and navigate the browser to `${API}/auth/sso/{id}/start`. Add a `/sso/complete` route that calls `POST /auth/refresh` (portal header `staff`), and show `?ssoError=<code>` on `/login`.
- **Account security:** `GET /me/mfa`, enrol and confirm, `POST /me/mfa/disable {password, code}`, `POST /me/mfa/recovery-codes`. Admins set `requireStaffMfa` and `ssoAllowedDomains` with the existing `PATCH /admin/organization`.
- **Work samples and scorecards:** cancel with `POST /hr/work-sample-assignments/{id}/cancel {reason}`; download files from `GET /hr/work-sample-assignments/{id}/files/{fileId}`, which is a binary attachment, not the JSON envelope. Edit scorecards with `PUT /interviews/{id}/scorecard` and read the history from `GET .../scorecard/revisions`.
- **Fairness:** use the `from`/`to` parameters.

**Candidate portal:**
- Submit work samples as multipart: `responseText`, `responseUrl`, and `files` up to 3 times. Do not set the `Content-Type` header by hand; let the browser add the boundary.
- Show `files` with downloads from `GET /candidates/me/work-samples/{assignmentId}/files/{fileId}`.
- Show `status: CANCELLED` together with `cancelReason`.
