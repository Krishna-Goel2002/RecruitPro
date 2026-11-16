# Bug hunt R1: backend platform

Scope: `backend/src/main/java/com/recruitpro/{auth,security,config,user,organization,admin,notification,jobs,audit,observability,retention,common}/**`,
their tests, `support/` test helpers, `application*.yml` and `pom.xml`. Branch `Kshitij-redesign`, nothing committed.
Every file in scope was read line by line against the review checklist:
- correctness;
- transactions with `open-in-view=false`;
- races;
- authentication and authorisation on every endpoint;
- tenancy;
- token handling (refresh rotation, MFA, TOTP, SSO);
- rate limits;
- input validation;
- entity/schema agreement;
- schedulers;
- retention;
- templates;
- audit;
- configuration;
- jjwt 0.12 and Spring Boot 3.5 API use.

The existing tests in scope were also checked for runtime mistakes.

Context read: handbook chapter 1 (sections 7 and 9), `docs/plan/STATUS.md`, WP-BE-A, WP-PLAT and WP-OPS notes.

**Affects Kshitij?** `git diff Kshitij HEAD` shows no change for any Java package in scope. The only difference in
`application.yml` is the retention block added on this branch. So every finding below also exists on the demo branch
`Kshitij`, except the ones in `retention/` (that package is not on `Kshitij`; there were none).

**Not compiled or run.** Java cannot be compiled or run here (JDK 11, no Maven Central). Fixes and tests were checked
only with the repository's static tools (results at the end). None of the new or changed tests have been executed.

Paths are relative to `backend/src/main/java/com/recruitpro/` (main code) and `backend/src/test/java/com/recruitpro/`
(tests). Line numbers are those after the fix.

## Findings and fixes

| ID | Severity | File:line | Problem | Fix | Test | Affects Kshitij? |
|---|---|---|---|---|---|---|
| R1-H1 | High | `user/service/UserAccountService.java:92`, `user/repository/UserRepository.java:25` | **Lockout counter could lose increments.** `recordFailedLogin` read `failed_login_count`, added one in Java and wrote it back without a lock. Parallel wrong passwords or MFA codes for one account read the same value and overwrote each other. An attacker sending N guesses at once got about N guesses per "failure", so the 5-attempt lockout (the only per-account brute-force limit; rate limits are per IP) did not hold. | Added `UserRepository.lockById` (`PESSIMISTIC_WRITE`); the counter is updated under that row lock. | `user/UserAccountLockoutIntegrationTest.parallelFailuresAreAllCounted`, `.theFifthFailureLocksTheAccount` | yes |
| R1-H2 | High | `auth/service/AuthService.java:218-225`, `auth/mfa/MfaService.java:288-293`, `security/filter/RateLimitFilter.java:37-60`, `user/service/UserAccountService.java:87` | **Password oracle with a stolen access token.** `POST /me/password` and `POST /me/mfa/disable` checked the current password with no lockout and no rate limit. Anyone holding a stolen 15-minute access token could guess the password without limit (one bcrypt per guess). Once found, a password change makes the takeover permanent and signs the victim out. In addition, `/me/mfa/enroll` and `/me/mfa/confirm` are public with an `mfaToken`, yet had no per-IP limit. | A wrong current password now counts as a failed sign-in. While the account is locked, both endpoints answer 429. `recordFailedLogin` now runs `REQUIRES_NEW`, so the caller's 400 rollback cannot undo the count. New POST rate limits: `/me/password` 10 per 15 min, `/me/mfa/disable` 10 per 15 min, `/me/mfa/enroll` and `/me/mfa/confirm` 10 per min. | `auth/AccountFlowsIntegrationTest.wrongCurrentPasswordsCountTowardsTheLockout`, `auth/mfa/MfaIntegrationTest.disablingNeedsThePasswordAndACode` (counter assertion added), `auth/RateLimitIntegrationTest.passwordChangeAndMfaStepsAreLimited`, `user/UserAccountLockoutIntegrationTest.aFailureSurvivesTheRollbackOfTheCallersTransaction` | yes |
| R1-M1 | Medium | `auth/sso/SsoController.java:68-115`, `auth/sso/SsoService.java:108,151` | **SSO login CSRF.** The OIDC `state` was not bound to the browser that started the sign-in. An attacker could start SSO, sign in at the identity provider with their own account, and send the victim the callback URL (`code` + `state`). The victim's browser then received a staff session for the attacker's account, so whatever the victim did or typed went into the attacker's account. | `/start` sets the httpOnly cookie `rp_sso_state`: `SameSite=Lax`, because the provider's redirect back is a cross-site top-level GET; path `/api/v1/auth/sso`; lifetime = state TTL. The callback is accepted only when the cookie equals the `state` parameter (constant-time compare). A mismatch gives `ssoError=invalid_state` and does not use up the state. The callback clears the cookie. The HR portal already starts SSO by top-level navigation (`<a href>`), so nothing changes in the frontend. `Accept: application/json` callers must send credentials. | `auth/sso/SsoIntegrationTest.aCallbackFromAnotherBrowserIsRefusedAsLoginCsrf`, `.startBindsTheStateToTheBrowserWithAnHttpOnlyLaxCookie`; helpers updated to carry the cookie | yes |
| R1-M2 | Medium | `security/RequestPaths.java` (new), `security/filter/RateLimitFilter.java:127`, `security/headers/ApiSecurityHeadersFilter.java:38` | **Rate limits bypassed with percent-encoded paths.** The rate limiter compared the raw `getRequestURI()`, but Spring MVC decodes the path before routing. So `DELETE /api/v1/candidates/m%65` reached account deletion with **no** limit on its password guesses, `/api/v1/organizations/s%69gnup` skipped the 3-per-hour sign-up limit, and `/api/v1/auth/l%6Fgin` fell into the 30/min default bucket instead of 10/min. Encoded paths could also get an API response without the API CSP header. | `RequestPaths.of`: strip the context path, percent-decode and trim trailing slashes, like MVC does. Both filters use it. (Spring's firewall already rejects encoded `/`, `.`, `%` and `;`.) | `auth/RateLimitIntegrationTest.percentEncodedPathsCountAgainstTheSameLimit`, `security/headers/SecurityHeadersIntegrationTest.percentEncodedApiPathsGetTheCspToo` | yes |
| R1-M3 | Medium | `admin/service/AdminJobService.java:117` | The admin status override read the job without a lock. Hibernate writes the whole row, so an override from a stale copy could overwrite a CLOSED status set at the same moment by the deadline closer or HR, and reopen a closed job. | Re-read the row with `PESSIMISTIC_WRITE` (`entityManager.refresh`) before checking the transition. | `admin/AdminConcurrentChangeIntegrationTest.overrideRereadsTheJobAndNeverReopensAJobClosedMeanwhile` | yes |
| R1-M4 | Medium | `organization/service/InvitationService.java:188`, `organization/controller/InvitationAuthController.java:50` | **Invitation acceptance skipped required MFA.** `accept-invite` always returned a full session (access token plus a 14-day refresh cookie), even when the organisation sets `requireStaffMfa`. | Acceptance calls `MfaService.challengeFor`. When MFA is required it returns the same `{mfaRequired, mfaEnrollmentRequired, mfaToken}` challenge as `/auth/login`, with no cookie. It fails closed (503) when the encryption key is missing. | `organization/InvitationMfaIntegrationTest.acceptingAnInvitationIntoAnMfaOrganisationReturnsTheEnrolmentChallenge` | yes |
| R1-M5 | Medium | `audit/service/AuditService.java:114` | Audit log and CSV export: a plain-date `to=2026-11-08` was read as the first instant of that day, so every event of the `to` day was left out. | A plain `to` date covers the whole UTC day (until the end of the day, minus 1 µs). | `admin/AdminPortalIntegrationTest.auditLogsArePersistentFilterableAndExportable` (from = to = day), `audit/service/AuditServiceTest.onlyADateWithoutTimeIsAPlainDate` | yes |
| R1-M6 | Medium | `notification/service/NotificationSender.java:205-227` | **E-mail retries were used up in about 25 seconds.** A failed send stayed PENDING and was retried on the next 5-second poll, so all 5 attempts went within roughly 25 s. A short SMTP outage therefore permanently failed every queued mail (password reset, invitation, referee request). | After a failure, the row stays claimed until its retry time: 1, 2, 4, 8 min, at most 1 h. It becomes FAILED only after the last attempt. | `notification/NotificationClaimIntegrationTest.failedSendsBackOffInsteadOfUsingUpEveryAttemptWithinSeconds` | yes |
| R1-M7 | Medium | `notification/service/NotificationSender.java:165,220` | Only `MailException` and `MessagingException` were caught. Any other runtime error stopped the rest of the batch. The bad row was then retried every 2 minutes forever, without its attempt count increasing. | Every row catches `MessagingException` and `RuntimeException` and counts the failure as an attempt. | `NotificationClaimIntegrationTest.anUnexpectedErrorCountsAsAnAttemptAndDoesNotStopTheBatch` | yes |
| R1-M8 | Medium | `notification/service/NotificationSender.java:250` | **Referee one-time token left readable.** Redaction removed only `token=` query parameters. The referee token in `/reference/<token>` stayed in plain text in `notifications.body` (kept 180 days) and was written to the INFO log even with `log-links=false`. | The `/reference/<token>` path segment is redacted as well. | `NotificationClaimIntegrationTest.refereeOneTimeLinksAreRedactedAfterDelivery` | yes |
| R1-M9 | Medium | `jobs/service/BackgroundJobWorker.java:84` | **Background jobs could run twice or sit stuck.** The worker claimed the whole batch up front, all with the batch-start lock time. In a slow batch, the later jobs could be released as stale and run again by another instance. A crash or shutdown left every claimed job stuck in RUNNING for 10 minutes. | `runOnce` claims one job immediately before running it, up to the batch size. | `jobs/BackgroundJobWorkerIntegrationTest.jobsAreClaimedOneAtATimeRightBeforeTheyRun` | yes |
| R1-L1 | Low | `auth/service/AuthService.java:112` | Spring checks "locked" and "disabled" before it compares the password. A locked or disabled account therefore answered measurably faster (no bcrypt), which reveals that the account exists. | On `LockedException`/`DisabledException` the bcrypt comparison is run anyway (result ignored). The response is the same 401. | none: a timing property. The behaviour is unchanged and stays covered by `auth/LoginLockoutIntegrationTest` and `AuthIntegrationTest.loginHandlesValidInvalidUnknownAndDisabledUsers` | yes |
| R1-L2 | Low | `auth/mfa/MfaService.java:223-242`, `auth/mfa/MfaController.java:65` | `/me/mfa/confirm` with an enrolment token checked the portal only after the transaction had committed. On the wrong portal it answered 403, but MFA was already enabled and the token consumed, so the user was stuck. | The portal is checked inside the transaction, before anything changes (new `confirm(code, mfaToken, portal)` overload). | `auth/mfa/MfaIntegrationTest.confirmingAnEnrolmentFromTheWrongPortalChangesNothing` | yes |
| R1-L3 | Low | `auth/service/AuthService.java:202,232`, `auth/service/UserTokenService.java:42` | A password reset link mailed before a password change kept working for its 60 minutes. Pending `mfaToken`s from sign-ins with the old password also survived a change or reset. | Change and reset invalidate outstanding `RESET_PASSWORD`, `MFA_LOGIN` and `MFA_ENROLL` tokens. | `auth/AccountFlowsIntegrationTest.changingThePasswordInvalidatesAnOutstandingResetLink` | yes |
| R1-L4 | Low | `security/ratelimit/SlidingWindowRateLimiter.java:41-47` | Once more than 10,000 keys were tracked (easy with many IPv6 addresses), the full map scan ran on **every** request. That is a CPU denial-of-service on the public auth routes. | The scan runs at most once per 30 s (CAS on the last scan time). | `security/ratelimit/SlidingWindowRateLimiterTest.housekeepingOfManyKeysRunsAtMostOncePerInterval` | yes |
| R1-L5 | Low | `auth/sso/JwksKeyCache.java:51-70` | A failed JWKS refresh (provider hiccup at the 6-hour refresh, or an unknown-kid refresh) replaced the cached keys with an empty set. Every SSO sign-in then failed until the next successful refresh. | A failed fetch keeps the known keys. The time of the last attempt still throttles refreshes. A `Clock` is injectable for tests. | `auth/sso/JwksKeyCacheTest.aFailedRefreshKeepsTheKnownKeysAndIsThrottled` | yes |
| R1-L6 | Low | `auth/sso/OidcTokenValidator.java:70-75` | `azp` was checked only when there were several audiences. OIDC Core 3.1.3.7: when `azp` is present it must equal this client. | Any `azp` other than our client id is refused; `azp` is still required when there are several audiences. | `auth/sso/SsoIntegrationTest.invalidTokensAreRefused` (azp case) | yes |
| R1-L7 | Low | `config/SecurityConfig.java:95` | A container error dispatch (an exception escaping a filter, for example the database being down while the JWT filter loads the user) went through authorisation as anonymous. It answered 401, so the portals signed the user out instead of showing a server error. | `dispatcherTypeMatchers(ERROR).permitAll()`. Direct requests to `/error` stay protected. | none: MockMvc does not perform container error dispatches | yes |
| R1-L8 | Low | `admin/service/AdminDashboardService.java:25` | A class-level read-only transaction held a database connection across two outbound health calls (up to 2 s each). A failed queue-count query marked the transaction rollback-only, so the dashboard answered 500 instead of showing -1. | Removed the class-level `@Transactional`; each repository call runs on its own. | `admin/AdminConcurrentChangeIntegrationTest.dashboardHoldsNoTransactionAcrossOutboundHealthChecks` | yes |
| R1-L9 | Low | `admin/service/AdminUserService.java:99` | Setting a user's status to the value it already had still wrote the row and added a misleading `USER_STATUS_CHANGED` audit entry. | No-op when nothing changes. | `AdminConcurrentChangeIntegrationTest.settingTheCurrentStatusAgainIsANoOp` | yes |
| R1-L10 | Low | `admin/service/AdminUserService.java:180` | Status and role changes worked on an unlocked copy of the user. The whole-row update could overwrite a concurrent role or status change. | New `lockTarget`: for an admin target it locks all admins in id order, then re-reads the target under a lock. The last-admin rules are unchanged. | `AdminConcurrentChangeIntegrationTest.statusChangeDoesNotOverwriteARoleChangedMeanwhile` (existing last-admin tests still apply) | yes |
| R1-L11 | Low | `notification/service/NotificationSender.java:165,205` | The SMTP send ran inside a database transaction, so a pooled connection was held for the whole network call. | Three steps: read in a short transaction, send with no transaction, then write the outcome in a second transaction (only if our claim still holds). | `NotificationClaimIntegrationTest.aSuccessfulSendIsRecordedAndReleasesTheClaim`, plus the existing claim tests | yes |
| R1-L12 | Low | `jobs/service/BackgroundJobService.java:187,221,236`, `jobs/repository/BackgroundJobRepository.java` | `markDone` and `markFailed` overwrote a job that had already been released as stale and queued again. `releaseStaleJobs` used an unconditional update, so two instances could both act on the same job and run the dead-letter handler twice. | Both methods now require status RUNNING. Stale release is a conditional update (`releaseStale`). Javadoc corrected: the first retry waits 30 s. | `BackgroundJobWorkerIntegrationTest.aJobReleasedAsStaleIsNotMarkedDoneByTheSlowWorker` | yes |
| R1-L13 | Low | `common/exception/GlobalExceptionHandler.java:320` | These requests fell through to 500 with an ERROR log and stack trace: a malformed multipart body, a missing required header or cookie, an unacceptable `Accept`, and a client disconnecting mid-response. | 400 / 400 / 406 without a body; a client disconnect is logged at debug level only. | `common/exception/GlobalExceptionHandlerTest.handleMalformedMultipart`, `.handleMissingHeader`, `.handleNotAcceptable` | yes |
| R1-L14 | Low | `common/exception/GlobalExceptionHandler.java:309` | 405 responses had no `Allow` header (RFC 9110 requires one). | `Allow` is set from the supported methods. | `GlobalExceptionHandlerTest.handleMethodNotSupportedSetsAllow` | yes |

**Totals: 0 critical, 2 high, 9 medium, 14 low (25 findings), all fixed.**

### Existing tests reviewed

All tests in scope were read against the code and helpers they exercise:
- `auth/*` (AuthIntegration, AccountFlows, LoginLockout, RateLimit, RefreshTokenRotation, PortalSession, PortalTest, SecurityTestController);
- `auth/mfa/*` (Mfa, MfaNotConfigured, Totp);
- `auth/sso/SsoIntegrationTest`;
- `security/*` (SlidingWindowRateLimiter, DatabaseRateLimiterStore, SecurityHeaders);
- `common/*`, `observability/RequestIdFilterTest`;
- `jobs/*`, `notification/*`, `retention/*`;
- `admin/*`, `organization/*`;
- `support/*`.

No wrong expectations were found. The few edits to existing tests are needed only because of the fixes:
- `SsoIntegrationTest` now carries the binding cookie through its helpers;
- `MfaIntegrationTest` gained a counter assertion;
- `AdminPortalIntegrationTest` gained a same-day audit-filter assertion.

One fragility, not changed: H2's default lock timeout is 1 s. The two thread-contention tests
(`DatabaseRateLimiterStoreIntegrationTest.concurrentRequestsNeverExceedTheLimit` and the new
`UserAccountLockoutIntegrationTest.parallelFailuresAreAllCounted`) could fail on a heavily loaded CI machine. If that
happens, add `;LOCK_TIMEOUT=10000` to the H2 URL in `application-test.yml`.

## Not fixed (and why)

| ID | Severity | Item | Reason |
|---|---|---|---|
| U1 | Low | `POST /me/mfa/recovery-codes` needs only an access token (no password or code step-up). A stolen 15-minute token plus a known password yields lasting recovery codes. | It is a documented API decision (WP-PLAT deviation 3); requiring a step-up needs an HR-portal change. Combined with R1-H2 (password guessing is now limited), the remaining risk is low. |
| U2 | Low | Refresh-token families slide forever: each rotation gives another 14 days, with no absolute session lifetime. | A product decision (common practice). An absolute cap would need a `family_started_at` column. |
| U3 | Low | Password change rejects older access tokens only to one-second precision (JWT `iat` is in seconds). A token issued in the same second just before the change stays valid for up to 15 minutes. | Fixing it needs a millisecond claim or a token version column. The exposure is under 1 s. |
| U4 | Low (ops) | Behind a reverse proxy every client has the proxy's IP, so the per-IP limits become global (one attacker can 429 everybody's login). | A deployment setting, not code. Set `SERVER_FORWARD_HEADERS_STRATEGY=native` and `server.tomcat.remoteip.internal-proxies` to the proxy addresses. It is not switched on by default: compose publishes the backend directly, and Docker's userland proxy presents clients from a private address that Tomcat would trust, which would let anyone spoof `X-Forwarded-For`. |
| U5 | Low | Inviting an e-mail that already has an account (even a candidate account) returns 409, which tells any org admin that the address is registered. | Needs a product decision on how invitations should handle existing accounts. |
| U6 | Low | Two organisation sign-ups with the same name at the same moment can pick the same slug; the loser gets a generic 409. | Rare; a retry works. |
| U7 | Low | On a multi-instance first start `BootstrapRunner` can race, and the second instance fails to start. | Run the bootstrap on one instance (operations note). |
| U8 | Low | The audit CSV stops at 50,000 rows without saying the file was cut off. | Needs a response-header or UI contract. |

## Cross-area follow-ups (for other reviewers / the coordinator)

- **HR portal (`AcceptInvitePage.tsx`)** must handle `data.mfaRequired` the way the login page does: enrol with the
  `mfaToken`, then confirm (R1-M4). Until then, in organisations that require MFA, the page shows an error after the
  account is created. The user can still sign in normally and enrol.
- **R2 (`hr/service/JobService.transition`)** has the same stale-read race as R1-M3; R2's report (R2-M04) says it is now
  fixed there.
- **Docs.** WP-PLAT notes §1.2 and handbook §4.8 should mention:
  - the `rp_sso_state` binding cookie;
  - that the JSON mode of `/sso/{id}/start` needs `credentials: 'include'`;
  - the new rate limits on `/me/password` and `/me/mfa/*`.

## Checker results (after all R1 edits)

| Check | Result |
|---|---|
| `python3 tools/java_static_check.py backend/src` | 604 files, 0 errors |
| `python3 tools/check_entity_schema.py` | 488 mapped columns of 42 entities against 27 migrations, 0 errors, 0 warnings (includes R2's V27, originally numbered V28) |
| `python3 tools/check_migrations_pg.py` | not run: R1 added no migration |
| `./mvnw verify` | **not run** (no JDK 21 / Maven Central here); required before merging |
