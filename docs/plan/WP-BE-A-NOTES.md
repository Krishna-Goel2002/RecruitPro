# WP-BE-A notes — backend platform

Status: implemented, not yet compiled. This environment has no JDK 17/21 and no Maven Central, so the first `./mvnw verify` runs on a developer machine or in CI (`.github/workflows/ci.yml`). Gates run here:

| Gate | Result |
|---|---|
| G-SQL `python3 -B tools/check_migrations_pg.py` | V1–V14 apply to PostgreSQL 16. The V11/V12/V14 backfills were also run against pre-existing rows. |
| G-JAVA-STATIC `python3 -B tools/java_static_check.py backend/src` | 0 errors in 236 files |
| Entity/schema cross-check (ad hoc script) | Every `@Column`/`@JoinColumn` of all 18 entities exists in the migrated PostgreSQL schema, which is what `ddl-auto: validate` needs in dev/prod. |
| G-JAVA-REVIEW | Two passes: my own line-by-line review, then an independent reviewer agent. Neither found a compile error. The four runtime issues the reviewer raised are fixed (see Deviations). |

## 1. Inventory

### Build and configuration
- `backend/pom.xml`: Spring Boot 3.5.6, Java 21, springdoc 2.8.9, `org.postgresql:postgresql` (runtime), `flyway-core` + `flyway-database-postgresql`. H2 is test-scoped. Removed `mysql-connector-j` and `flyway-mysql`. Lombok is an explicit annotation-processor path. Surefire runs with `-Duser.timezone=UTC`.
- `application.yml`, `application-dev.yml` (PostgreSQL `jdbc:postgresql://localhost:5432/recruitpro`, `recruitpro`/`recruitpro`, validate), `application-prod.yml`, `application-test.yml` (H2 `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`, `ddl-auto: none`; background jobs, notification sender and rate limiting off; refresh token exposed in the body; cookie not Secure).
- Migrations V1–V10 rewritten in PostgreSQL syntax (same names and meaning). New: `V11__organizations_and_tenancy`, `V12__auth_hardening`, `V13__audit_events`, `V14__background_jobs_and_notifications`, as in plan §3.2.

### New packages and classes
| Package | Classes |
|---|---|
| `common.time` | `UtcTime` (now/format/parse), `UtcTimeModule` (Jackson `Module` bean), `UtcLocalDateTimeSerializer`, `UtcLocalDateTimeDeserializer` |
| `common.util` / `common.web` | `LikePatterns` (L4), `ClientIp` |
| `common.exception` | `TooManyRequestsException`; `GlobalExceptionHandler` extended (M2) |
| `security` | `CurrentUser` (record: userId, email, role, organizationId), `SecurityUtils` (bean), `filter.RateLimitFilter`, `ratelimit.SlidingWindowRateLimiter` |
| `organization` | `entity.Organization`, `entity.Invitation`, `repository.OrganizationRepository`, `repository.InvitationRepository`, `service.OrganizationService`, `service.InvitationService`, `controller.OrganizationSignupController`, `controller.InvitationAuthController`, `controller.AdminOrganizationController`, `bootstrap.BootstrapRunner`, `bootstrap.DevSeedRunner`, `bootstrap.DemoDataSeeder`, DTOs |
| `audit` | `entity.AuditEvent`, `repository.AuditEventRepository`, `service.AuditService`, `service.AuditActions`, `dto.AuditEventResponse` |
| `jobs` | `entity.BackgroundJob`, `entity.BackgroundJobType` {PARSE_RESUME, MATCH_APPLICATION}, `entity.BackgroundJobStatus`, `repository.BackgroundJobRepository`, `service.BackgroundJobService`, `service.BackgroundJobHandler`, `service.BackgroundJobWorker`, `service.NonRetryableJobException` |
| `notification` | `entity.Notification`, `entity.NotificationTemplate` (enum with plain-text templates), `entity.NotificationChannel`, `entity.NotificationStatus`, `repository.NotificationRepository`, `service.NotificationService`, `service.NotificationSender`, `service.NotificationRecipient`, `controller.NotificationController`, DTOs |
| `auth` | `entity.UserToken` + `UserTokenPurpose`, `repository.UserTokenRepository`, `service.RefreshTokenService`, `service.UserTokenService`, `service.TokenHashing`, `service.AuthSession`, `validation.PasswordPolicy`, `web.RefreshTokenCookies`, `controller.MeController`, DTOs `VerifyEmailRequest`, `ForgotPasswordRequest`, `ResetPasswordRequest`, `ChangePasswordRequest` |
| `user.service` | `UserAccountService` (account creation, lockout counters, password change) |
| `hr.service` | `HrProfileService` (auto HR profile for ADMIN, HR_MANAGER, RECRUITER) |
| `admin` | `service.SystemHealthService`, `dto.SystemHealthResponse` |
| `ai.service` | `ResumeParseJobHandler` (PARSE_RESUME) |
| `config` | `SchedulingConfig` (`@EnableScheduling`; Boot scheduler with 4 threads) |

**Deleted:** `user/provisioning/*` and its three tests, `META-INF/spring.factories`, `admin/audit/AdminAuditService`, `admin/dto/AdminAuditRecord`, `ai/service/ResumeAnalysisWorker`, `config/ResumeIntelligenceAsyncConfig`, and the empty `hiring`, `onboarding` and `analytics` packages (D12).

### Endpoints added or changed
| Method | Path | Notes |
|---|---|---|
| POST | /api/v1/auth/register | 201, `AuthResponse` + `rp_refresh` cookie, sends VERIFY_EMAIL |
| POST | /api/v1/auth/login | lockout after 5 failures (same 401 when locked) |
| POST | /api/v1/auth/refresh | cookie (or body when exposed); no `Set-Cookie` inside the 30 s grace window; 401 clears the cookie |
| POST | /api/v1/auth/logout | revokes the family, clears the cookie |
| POST | /api/v1/auth/verify-email, /resend-verification (authenticated), /forgot-password (always 200), /reset-password | |
| GET | /api/v1/auth/invitations/{token} | `{organizationName,email,role,expiresAt}`; 404 when unknown/used/revoked/expired |
| POST | /api/v1/auth/accept-invite | `AuthResponse` + cookie; email counts as verified |
| POST | /api/v1/organizations/signup | 201, ADMIN of a new organisation; `app.organizations.self-signup-enabled` |
| GET | /api/v1/me | `AuthUser` |
| POST | /api/v1/me/password | returns the new session's `AuthResponse` + cookie (see Deviations) |
| GET/PATCH/POST | /api/v1/me/notifications, /unread-count, /{id}/read, /read-all | in-app inbox |
| GET/PATCH | /api/v1/admin/organization | `{id,name,slug,blindReview,shareVerificationStatus}` |
| GET/POST/DELETE | /api/v1/admin/invitations[/{id}] | response adds `status` (PENDING/ACCEPTED/REVOKED/EXPIRED); `inviteUrl` only on create when `app.invitations.expose-link=true` |
| GET | /api/v1/admin/audit-logs?page&size&action | persistent, organisation-scoped, newest first |
| GET | /api/v1/admin/audit-export?from&to | `text/csv`; ISO date or date-time; default last 90 days; audited (AUDIT_EXPORTED) |
| GET | /api/v1/admin/dashboard | organisation-scoped; flat health fields kept, plus `health {backend,database,aiService,queue{pending,failed,dead}}` |
| PATCH | /api/v1/admin/users/{id}/role | staff roles only (CANDIDATE → 400) |
| PATCH | /api/v1/admin/jobs/{id}/status | transition table (H7) |

`AuthResponse = {accessToken, tokenType:"Bearer", expiresInSeconds:900, user, refreshToken?}` and `AuthUser = {id,email,firstName,lastName,role,organizationId?,organizationName?,emailVerified}`. Null fields are omitted (`non_null`), so `organizationId` is absent for candidates. `JobResponse` gained `organizationId`.

## 2. Configuration keys (defaults)
| Key (env) | Default | Purpose |
|---|---|---|
| `app.jwt.secret` (`JWT_SECRET`) | required, at least 32 bytes | access-token key; checked at start-up |
| `app.jwt.access-token-expiry-ms` / `refresh-token-expiry-ms` | 900000 / 1209600000 | 15 min / 14 days |
| `app.auth.cookie-secure` (`AUTH_COOKIE_SECURE`) | true (dev and test false) | refresh cookie Secure flag |
| `app.auth.expose-refresh-token-in-body` | false (test true) | API clients and tests |
| `app.auth.refresh-grace-seconds` | 30 | concurrent-tab grace window |
| `app.auth.require-verified-email-to-apply` | false (prod true) | enforced in `ApplicationService.apply` |
| `app.auth.email-verification-ttl-hours` / `password-reset-ttl-minutes` | 24 / 60 | |
| `app.auth.lockout.max-attempts` / `duration-minutes` | 5 / 15 | |
| `app.rate-limit.enabled` (`RATE_LIMIT_ENABLED`) | true (test false) | |
| `app.organizations.self-signup-enabled` (`ORG_SELF_SIGNUP_ENABLED`) | true | |
| `app.invitations.ttl-days` / `expose-link` | 7 / false (dev and test true) | |
| `app.frontend.candidate-url` / `hr-url` | http://localhost:5173 / :5174 | links in emails |
| `app.bootstrap.*` (`BOOTSTRAP_ORG_NAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `..._FIRST_NAME`, `..._LAST_NAME`) | empty | first organisation |
| `app.seed.demo` (`APP_SEED_DEMO`) / `app.seed.password` | false / `ChangeMe-123!` | ignored in prod |
| `app.background-jobs.enabled` / `poll-interval-ms` / `batch-size` / `max-attempts` / `stale-after-minutes` | true (test false) / 2000 / 5 / 5 / 10 | |
| `app.mail.enabled` (`MAIL_ENABLED`) / `app.mail.from` | false (prod true) | SMTP through `spring.mail.*` |
| `app.notifications.sender-enabled` / `poll-interval-ms` / `max-attempts` / `log-links` | true (test false) / 5000 / 5 / false (dev true) | |
| `app.ai-service.health-timeout-ms` | 2000 | dashboard AI health |
| `spring.task.scheduling.pool.size` | 4 | scheduler threads |

Removed: `app.dev-provisioning.*`, `app.ai-service.queue-enabled`, `app.ai-service.poll-interval-ms`.

## 3. Tenancy: how scoping works and what later packages must call
- `User.organizationId` and `Job.organizationId` are plain `Long` columns, not JPA associations, so code never touches lazy proxies. Candidates have `organizationId = null`. `Invitation`, `AuditEvent` and `Notification` also carry `organizationId`.
- **Current caller**: inject `SecurityUtils` (a bean, not static).
  - `currentUser()` returns a `CurrentUser` (401 when missing).
  - `findCurrentUser()` returns an `Optional`.
  - `requireOrganizationId()` returns the caller's organisation id (403 when the caller has none).
  - `requireOrganizationMember()` returns the `CurrentUser` and guarantees an organisation.

  With the JWT filter the principal is `CustomUserDetails`, loaded per request; any other principal (MockMvc `user(...)` in tests) is resolved by email from the database.
- **Jobs**: use `HrJobAccessService`.
  - `requireManageAccess(jobId)` (ADMIN, HR_MANAGER) and `requireApplicantReadAccess(jobId)` / `requireJobReadAccess(jobId)` (ADMIN, HR_MANAGER, RECRUITER) return the `Job` of the caller's organisation, or 404. A wrong role gets 403, checked before the lookup.
  - `requireJobManager()` / `requireJobReader()` return the `CurrentUser` (with its organisation) for list or create queries.
  - INTERVIEWER has no job access.
- **Repository rule**: every staff query takes the organisation id, e.g. `JobRepository.findByIdAndOrganizationId`, `UserRepository.findByIdAndOrganizationId`, `findAllByOrganizationIdAndRoleIn`, `ApplicationRepository.countByJob_OrganizationId...`, `InvitationRepository.findByIdAndOrganizationId`. Applications are reached through an access-checked job (`findByIdAndJobId` after `requireManageAccess`).
- Candidate job browsing (`JobService.list/get` with `candidateOnly`) is unscoped: PUBLISHED with a null or future deadline, across all organisations. `JobPublic` is left to WP-BE-B.
- HR profiles are created by `HrProfileService.ensureProfileFor(User)` for ADMIN, HR_MANAGER and RECRUITER: at sign-up, invitation acceptance, bootstrap, seed, role change, and as a fallback at first job creation.

## 4. Background jobs
- **Enqueue**: `backgroundJobService.enqueue(BackgroundJobType.MATCH_APPLICATION, applicationId)` returns `true` when a new job was created. It joins the caller's transaction, so the job exists only if the triggering change commits. It is a no-op while a PENDING or RUNNING job exists for the same type and entity.
- **Handler**: write a `@Component` implementing `BackgroundJobHandler` with `type()`, `handle(long entityId) throws Exception`, and optionally `onDeadLetter(long, String)`. There must be exactly one handler per type; a duplicate fails start-up.
  - Handlers run outside any worker transaction.
  - Throwing schedules a retry; `NonRetryableJobException` marks the job FAILED; returning normally marks it DONE.
- **Retry semantics**:
  - `attempts` is incremented when a job is claimed.
  - On failure the job returns to PENDING with `run_after = now + min(15 s × 2^attempts, 30 min)` (30 s, 60 s, 120 s, 240 s), or becomes DEAD at `max_attempts` (5).
  - RUNNING jobs locked for more than 10 minutes return to PENDING, or become DEAD if their attempts are exhausted.
  - `BackgroundJobWorker.runOnce()` runs one batch synchronously; tests use it directly.
- **PARSE_RESUME** (`ResumeParseJobHandler`, entity = `resume_analyses.id`):
  - Queued by `ResumeIntelligenceService.createFor` (upload) and on candidate or HR retry. V14 queues existing PENDING and PROCESSING analyses.
  - Transient AI errors (connection failures, 5xx, 429, 408, empty response) set the analysis back to PENDING (`errorCode=AI_SERVICE_UNAVAILABLE`) and throw.
  - Permanent outcomes complete or fail the analysis: parser results, other 4xx (`AI_SERVICE_REJECTED`), a missing file, or a missing token.
  - Dead letter sets the analysis to FAILED, which users can retry.
  - `AiResumeParseResponse` ignores unknown v2 fields (`flags`, `timeline`, `spanStart`, `spanEnd`, `attributes`). Unknown fact types (e.g. LINK) are skipped and long values truncated to column sizes; nothing v2-specific is stored yet.

## 5. Notifications
- **Send**: call `notificationService.notify(NotificationRecipient.of(user), NotificationTemplate.APPLICATION_STATUS_CHANGED, Map.of("firstName", ..., "jobTitle", ..., "organizationName", ..., "status", ..., "message", ...))`.
  - It writes an EMAIL outbox row in the caller's transaction, plus an IN_APP row for registered users when the template is inbox-worthy.
  - Use `NotificationRecipient.email(address, orgId)` for people without an account.
  - Placeholders are `{{name}}`; missing values render empty; `Map.of` rejects null values, so use a `HashMap` when a value may be null.
- **Templates**: VERIFY_EMAIL, PASSWORD_RESET and INVITATION have no inbox copy because they carry one-time links. APPLICATION_RECEIVED, APPLICATION_STATUS_CHANGED, WORK_SAMPLE_ASSIGNED, INTERVIEW_SCHEDULED and INTERVIEW_ASSIGNED do.
- **Delivery**: `NotificationSender` runs every 5 s.
  - With `app.mail.enabled=true` it sends through `JavaMailSender`; a message that keeps failing becomes FAILED after 5 attempts.
  - Otherwise it marks the row SKIPPED and logs subject and links at INFO. Tokens are redacted from that log line unless `app.notifications.log-links=true` (dev).
  - After delivery, one-time tokens are removed from the stored body.
- **Audit**: `auditService.record(action, targetType, targetId, details)` records the current actor, organisation and IP in the caller's transaction. Use `recordFor(orgId, actorId, actorEmail, ...)` when there is no authenticated caller. Action names are in `AuditActions`; add new ones there. Details are masked for `password=`, `token=` and `secret=` patterns and capped at 4000 characters.

## 6. Test changes
- **New `support` helpers**: `TestDatabase.clean(JdbcTemplate)` deletes all rows in foreign-key order; `TestDataFactory` (`@Component`) creates organisations, staff, HR profiles, candidates and jobs; `AuthTestSupport` reads cookies and envelopes. Every integration test now cleans before and after. **Add new tables to `TestDatabase`.**
- **Updated**:
  - All integration tests: staff get an organisation and jobs get `organizationId`.
  - "other HR manager gets 403" became "other organisation gets 404" plus "same-organisation colleague may manage".
  - Admin tests use staff instead of candidates for status and role changes, check persistent audit, CSV export and the H7 table.
  - `AuthIntegrationTest`: password policy, cookie attributes, 15-minute lifetime and claims, grace-window refresh semantics, and no 500 for a deleted or disabled user's token.
  - `JobManagementIntegrationTest`: automatic admin HR profile, an unknown principal gets 401, LIKE escaping.
  - `HealthControllerTest`: actuator needs ADMIN.
  - Unit tests now use the new constructors (`HrJobAccessService(JobRepository, SecurityUtils)`, `ResumeIntelligenceService` with 4 arguments).
  - `AiLifecycleIntegrationTest` uses the worker and runs only when `RECRUITPRO_AI_IT=true`, because it needs a live AI service.
- **Deleted**: the three provisioning tests.
- **New**:
  - `auth/RefreshTokenRotationIntegrationTest`: rotation, grace window, reuse detection, logout.
  - `auth/LoginLockoutIntegrationTest`.
  - `auth/RateLimitIntegrationTest`, with `app.rate-limit.enabled=true`.
  - `auth/AccountFlowsIntegrationTest`: verify, forgot/reset, change password, `/me`, inbox, sender.
  - `organization/OrganizationTenancyIntegrationTest`: sign-up, invitations, cross-organisation 404s with real JWTs, candidate cross-organisation browsing.
  - `admin/AdminJobOverrideTransitionTest`.
  - `jobs/BackgroundJobWorkerIntegrationTest`: de-duplication, backoff, dead letter, stale recovery, non-retryable failure, duplicate handlers.
  - `ai/ResumeParseJobHandlerTest`.
  - `common/time/UtcJsonSerializationTest`.
  - `common/exception/ErrorMappingIntegrationTest`, plus M2 cases added to `GlobalExceptionHandlerTest`.
  - `security/ratelimit/SlidingWindowRateLimiterTest`.

## 7. Deviations from the plan
1. **`POST /api/v1/me/password`** returns the new session's `AuthResponse` (and cookie) instead of `null`. Access tokens issued before the change are rejected (iat earlier than `password_changed_at`), so the caller needs the new token. A client that ignores `data` still works through refresh.
2. **Refresh body.** A token in the request body is honoured only when `expose-refresh-token-in-body=true`.
3. **Queue de-duplication** follows the plan (PENDING or RUNNING). A trigger that arrives while a MATCH job is RUNNING is dropped. WP-BE-B should enqueue again after the run if the inputs changed (e.g. compare fingerprints).
4. **V14 also queues analyses left in PROCESSING**, not only PENDING; the handler claims both.
5. **`invitations.invited_by_user_id`** is `ON DELETE SET NULL`, and invitations have a role CHECK constraint.
6. **Extra audit actions**: ORGANIZATION_CREATED, PASSWORD_CHANGED, PASSWORD_RESET and REFRESH_TOKEN_REUSE_DETECTED (only when an active family was revoked).
7. **Inbox copies.** VERIFY_EMAIL, PASSWORD_RESET and INVITATION have no IN_APP copy.
8. **Rate limits.** Auth routes not listed in the plan (verify-email, reset-password, invitation preview, resend) share a default limit of 30 per minute per IP.
9. **Dashboard semantics.** `totalUsers` counts the organisation's staff; `totalCandidates` counts distinct applicants to the organisation's jobs.
10. **Scheduler.** Boot's auto-configured scheduler (`spring.task.scheduling.pool.size: 4`) replaces a custom scheduler bean, so the default `applicationTaskExecutor` still exists.
11. **Prod mail.** `MAIL_ENABLED` defaults to true in prod, so tokens never go only to logs.

## 8. Risks and open items
- **Not compiled.** The first real compile and test run happens in CI or on a JDK 21 machine. Most likely friction: Spring Boot 3.5 deprecations (`@SpyBean` in `ApplicationReviewAuthorizationIntegrationTest`, `DaoAuthenticationProvider()` without arguments). These are warnings, not errors.
- **H2 vs PostgreSQL.** Migrations were executed on PostgreSQL only. H2 compatibility rests on the plan §3.1 subset (DEFAULT before NOT NULL, no partial indexes, CAST for concatenation). **For WP-BE-B**: plan V17 names a column `candidate_claims.value`, and `VALUE` (like `KEY`) is a reserved word in H2, so that migration would fail in tests. Name it `claim_value`.
- **FOR UPDATE with outer joins.** The existing `ApplicationRepository.findForReview` uses `@Lock(PESSIMISTIC_WRITE)` with an entity graph (outer joins). PostgreSQL rejects `FOR UPDATE` on the nullable side of an outer join, unless Hibernate restricts it to `FOR UPDATE OF` the root. The CI smoke test does not exercise it. WP-BE-B should verify, or lock the application row separately.
- **Synchronous matching.** `ApplicantIntelligenceService.list` still calls the AI service synchronously and writes match runs (H6/H8 are WP-BE-B). It is organisation-scoped now.
- **Single instance assumed.** The rate limiter and notification sender assume one backend instance: limiter state is in memory, and the sender has no row claim. The job worker is multi-instance safe (conditional claim).
- **Flyway checksums.** V1–V10 checksums changed (D1). Existing MySQL databases are not supported; PostgreSQL databases must be new.
- **Time zone.** UTC correctness relies on the JVM default zone being UTC (`main()`, surefire `argLine`, Docker `JAVA_TOOL_OPTIONS`) combined with `hibernate.jdbc.time_zone=UTC`. Tests run from an IDE without `-Duser.timezone=UTC` fail `UtcJsonSerializationTest` on purpose.
- **Docker images.** `ai-service/Dockerfile` copies the whole folder; WP-PY should add an `ai-service/.dockerignore` (`.venv`, caches). Mailpit uses the `latest` tag. Frontend images install `pnpm@10` with npm. I added `.dockerignore` files for `backend/`, `frontend/` and `hr-frontend/`.
- **Not done (outside this package or deferred):**
  - `JobPublic` DTO and auto-close (WP-BE-B).
  - MATCH_APPLICATION handler (WP-BE-B).
  - WhatsApp channel (D13, deferred).
  - Distributed rate limiting.
  - Row-level security (deferred in plan §10).
