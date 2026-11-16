# Handbook chapter 1: Architecture and platform

Scope: services, backend structure, data model, identity and security, asynchronous processing, observability and
operations, testing, and likely demo questions. Written on 16 Nov 2026 against commit `2fedafb` (branch `Kshitij`)
plus the working tree at that time; sections 1.1 to 1.3 (services, networks, compose wiring) were updated for branch
`Kshitij-redesign` (R7 infrastructure audit, `docs/audit/BUG_HUNT_R7_INFRA.md`). Every statement names the file it comes from. Paths are relative to the
repository root; `…/recruitpro/` is short for `backend/src/main/java/com/recruitpro/`.

Ground rules used in this chapter:

- "Implemented" means the code exists in the repository. It does not mean it was executed. Section 7 says what was
  actually run and when.
- Numbers (limits, timeouts, counts) are copied from code or configuration, not estimated.
- Where the code and an older document disagree, the code wins and the disagreement is listed in Section 9.

---

## 1. System overview

### 1.1 Services

RecruitPro is a modular monolith (one Spring Boot backend) with two Python helper services and two single-page
portals. The backend is the system of record; the Python services hold no data
(`ai-service/app/main.py` docstring, `agent-service/app/main.py` docstring).

| Service (compose name) | Purpose | Technology | Host port → container | Source |
|---|---|---|---|---|
| `postgres` | The only database | `pgvector/pgvector:0.8.7-pg16` image (PostgreSQL 16; `pg16` on branch `Kshitij`). The pgvector extension is **not used**: no migration creates it (`backend/src/main/resources/db/migration/`), and pgvector is out of scope (`docs/plan/IMPLEMENTATION_PLAN_V2.md` line 9) | not published on `Kshitij-redesign` (`docker compose exec postgres psql …`); 5432 → 5432 on `Kshitij` | `docker-compose.yml` |
| `mailpit` | Development SMTP sink with a web UI for reading sent mail | `axllent/mailpit:v1.33.1` (`latest` on `Kshitij`) | 127.0.0.1:8025 (UI), 127.0.0.1:1025 (SMTP) | `docker-compose.yml` |
| `ai-service` | Resume parsing, requirement matching, requirement compiler, work-sample and interview-kit generation | Python 3.11 (`python:3.11-slim-bookworm`), FastAPI 0.121.3, Starlette 0.50.0, uvicorn, pdfplumber, python-docx, olefile; headless LibreOffice Writer and the RapidOCR extra in the default image; optional LLM over httpx | not published on `Kshitij-redesign` (internal network, `docker compose exec ai-service …`); 8000 → 8000 on `Kshitij` | `ai-service/Dockerfile`, `ai-service/requirements.txt`, `ai-service/app/api/v1/*.py` |
| `agent-service` | Evidence verification agents (code, web, document, badge, reference) and source-ownership checks | Python 3.12 (`python:3.12-slim-bookworm`), FastAPI, httpx, dnspython, pypdf, PyJWT, cryptography | 127.0.0.1:8100 → 8100 (all interfaces on `Kshitij`) | `agent-service/Dockerfile`, `agent-service/requirements.txt`, `agent-service/app/api/routes.py` |
| `backend` | REST API, business rules, security, background queue, schedulers, mail outbox, audit | Java 21, Spring Boot 3.5.6, Spring Security, Spring Data JPA/Hibernate, Flyway, jjwt 0.12.5, springdoc 2.8.9, Micrometer Prometheus | 8080 → 8080 | `backend/pom.xml`, `backend/Dockerfile` |
| `candidate-portal` | Candidate UI | React 18, TypeScript 5.6, Vite 5, TanStack Query 5, React Router 6; served by `nginx:1.27-alpine` | 5173 → 80 | `frontend/package.json`, `frontend/Dockerfile` |
| `hr-portal` | Staff UI (ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER) | Same stack as the candidate portal | 5174 → 80 | `hr-frontend/package.json`, `hr-frontend/Dockerfile` |

Container hardening that exists: the backend, ai-service and agent-service images run as a non-root system user and
have a `HEALTHCHECK` (`backend/Dockerfile`: `curl /api/v1/health`; the Python images: `urllib` on `/health`; the
portal images, on `Kshitij-redesign`: busybox `wget` on `/`). The
backend JVM is forced to UTC (`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`) and `-XX:MaxRAMPercentage=75`
(`backend/Dockerfile`).

### 1.2 How the services talk

```
 browser ──HTTPS?──► candidate-portal (nginx, static files)   browser ──► hr-portal (nginx, static files)
    │                                                             │
    └──── fetch, Bearer access token + httpOnly refresh cookie ───┴──► backend :8080 /api/v1/**
                                                                          │
           ┌──────────────────────────────┬────────────────────────────────┼───────────────────────┐
           ▼                              ▼                                ▼                       ▼
   postgres :5432 (JDBC)      ai-service :8000 /api/v1/*        agent-service :8100 /api/v1/*   SMTP (mailpit :1025)
                              X-Internal-Service-Token          X-Internal-Service-Token
                              (no internet needed)              (internet egress: GitHub, GitLab,
                                                                 websites, badge issuers)
   backend also calls, when configured: github.com OAuth + api.github.com (GitHub connector),
   OIDC identity providers (staff SSO).
```

- **Browser → backend.** The portals are static bundles; the browser calls the backend directly at
  `VITE_API_BASE_URL` (compiled in at build time, `frontend/Dockerfile`). There is no reverse proxy in front of the
  backend in `docker-compose.yml`; nginx in the portal images serves only static files (`frontend/nginx.conf`).
  TLS is not terminated anywhere in the compose stack; HTTPS needs an external proxy or load balancer.
- **Backend → ai-service.** Synchronous REST with a shared secret in `X-Internal-Service-Token`
  (`…/recruitpro/ai/service/AiServiceClient.java`). Endpoints called: `POST /api/v1/resume/parse`, `/match`,
  `/requirements/compile`, `/work-samples/generate`, `/interview-kits/generate`. `POST /api/v1/match/batch` exists in
  the ai-service (`ai-service/app/api/v1/matching.py`) but the backend does not call it. Connect and read timeout
  30 s (`app.ai-service.timeout-ms`). Parsing and matching are only called from background jobs; the three authoring
  calls are made synchronously from HR requests (`…/ai/service/AiAuthoringCalls.java`).
- **Backend → agent-service.** Same token pattern (`…/verification/client/AgentServiceClient.java`). Endpoints:
  `/api/v1/ownership/check`, `/verify/code`, `/verify/web`, `/verify/document` (multipart), `/verify/badge`,
  `/verify/reference`. Read timeout 60 s, connect 5 s (`application.yml` `app.agent-service.*`); the agent's own
  deadline is 50 s (`agent-service/app/core/config.py`).
- **Token check in the Python services** is pure ASGI middleware that runs before any request body is read, compares
  with `hmac.compare_digest`, answers 503 when no token is configured and 401 on mismatch, and enforces a body-size
  cap with 413 (`ai-service/app/core/auth.py`, `agent-service/app/core/auth.py`). `GET /health` is public on both.
  The agent-service refuses to start without `AGENT_SERVICE_TOKEN` unless `AGENT_ALLOW_NO_TOKEN=true`
  (`agent-service/app/core/config.py` `validate_settings`). OpenAPI docs are disabled on both
  (`docs_url=None, openapi_url=None` in each `main.py`).
- **Request id propagation.** The backend sends `X-Request-Id` to the ai-service through
  `…/observability/RequestIdClientInterceptor.java`; `AgentServiceClient` reads the same MDC key
  (`docs/plan/WP-PLAT-NOTES.md` §1.6). It is not sent to identity providers.

**Who has internet egress (intended design vs what compose enforces).** Plan v2 draws the ai-service with "no
egress" and the agent-service with "egress, sandboxed fetch" (`docs/plan/IMPLEMENTATION_PLAN_V2.md` §2). In code:

| Service | Needs outbound internet for | Notes |
|---|---|---|
| agent-service | GitHub/GitLab APIs, candidate websites, badge issuers, DNS TXT lookups | Every candidate-supplied URL goes through the SSRF guard (`agent-service/app/fetch/ssrf.py`): http/https only, ports 80/443, no user-info, every resolved address must be public (private, loopback, link-local, CGNAT, metadata ranges blocked), the connection is pinned to the checked IP (DNS-rebinding defence), and the check repeats on every redirect hop. Per-verification request budget and 50 s wall clock (`agent-service/app/core/budget.py`). `AGENT_DEMO_FIXTURES=true` answers `demo-` logins/hosts from synthetic fixtures (`agent-service/app/core/config.py`). |
| backend | SMTP server; GitHub OAuth (`https://github.com/login/oauth/access_token`, `https://api.github.com/user`, `…/verification/client/GitHubOAuthClient.java`); OIDC providers (discovery, token, JWKS, `…/auth/sso/OidcHttpClient.java`) | All optional; only used when configured. |
| ai-service | Nothing by default. Only when `LLM_PROVIDER` is `anthropic` or `openai_compatible` | Only de-identified text is sent to an LLM (`ai-service/app/llm/provider.py` docstring). |
| postgres, mailpit, portals | Nothing | |

On branch `Kshitij-redesign` the compose file enforces this with five networks (`docker-compose.yml` header,
`docs/plan/WP-OPS-NOTES.md` §3): `internal` (internal: true; postgres, mailpit, ai-service, backend), `agent`
(internal: true; backend, agent-service), `egress` (backend), `agent-egress` (agent-service) and `edge` (the portals
and mailpit, for published ports only). The ai-service has no egress; `docker-compose.ai-egress.yml` adds an
`ai-egress` network for an LLM provider. The agent-service cannot reach postgres or the ai-service. On branch
`Kshitij` the compose file defines no networks, so every container sits on the default bridge network with outbound
internet and the "no egress" property is only a design intent.

### 1.3 docker-compose wiring

From `docker-compose.yml`:

- `backend` `depends_on`: `postgres` (`condition: service_healthy`, using `pg_isready`), `mailpit`
  (`service_started`), `ai-service` and `agent-service` (`service_healthy` on `Kshitij-redesign`, using the images'
  `HEALTHCHECK`; `service_started` on `Kshitij`). The portals depend on `backend` (`service_started`).
- The portal images take `VITE_API_BASE_URL` (compiled into the bundle) and `API_ORIGIN` (its origin, written into
  the nginx CSP `connect-src` by the image's envsubst step at start-up); compose sets both to the `localhost:8080`
  values.
- Volumes: `postgres-data` (database), `resume-uploads` mounted at `/data/uploads` in the backend
  (`FILE_STORAGE_PATH=/data/uploads`). Work-sample files and verification snapshots default to subfolders of it
  (`application.yml` `app.file-storage.work-sample-dir`, `app.verification.storage-dir`).
- Shared secrets have development defaults with `${VAR:-default}` so `docker compose up --build` works without a
  `.env` file. The same default token is written into both ends (ai-service/backend, agent-service/backend).
- Published ports on `Kshitij-redesign`: 5173, 5174, 8080 on all interfaces; 8100, 8025, 1025 on 127.0.0.1 only;
  postgres (5432) and the ai-service (8000) are not published. On `Kshitij` all of 5432, 8000, 8100, 8025, 1025 are
  published on all interfaces.
- `docker-compose.yml` and `.env.example` pass `LLM_MODEL`, `ANTHROPIC_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_API_KEY`
  to both Python services, `GITHUB_TOKEN` (from `AGENT_GITHUB_TOKEN`) to the agent-service, and the `SSO_*` variables
  to the backend (added after `2fedafb`, committed in `50e7b39`).

### 1.4 Environment variables

Backend (`backend/src/main/resources/application.yml` unless another file is named; `backend/.env.example` documents
the same set). "Compose" = value supplied by `docker-compose.yml`.

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (compose: `dev`) | `dev`, `test` or `prod` profile (Section 6.4) |
| `PORT` | 8080 | HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | dev: `jdbc:postgresql://localhost:5432/recruitpro`, `recruitpro`/`recruitpro` (`application-dev.yml`); prod: required, no default (`application-prod.yml`) | JDBC connection |
| `JWT_SECRET` | none in dev/prod (startup fails); compose supplies a dev value | HMAC key for access tokens and the MFA token key derivation; must be ≥ 32 bytes (`…/security/jwt/JwtService.java` `validateConfiguration`) |
| `AUTH_COOKIE_SECURE` | `true`; dev profile `false`; prod forced `true` | `Secure` flag of the refresh cookie |
| `AUTH_EXPOSE_REFRESH_TOKEN_IN_BODY` | `false` (test profile `true`, prod forced `false`) | Also return/accept the refresh token in JSON (API clients, tests) |
| `AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY` | `false`; prod default `true` | Candidates must verify email before applying |
| `RATE_LIMIT_ENABLED` | `true` (test `false`) | Per-IP limits on auth routes, sign-up, account deletion |
| `RATE_LIMIT_STORE` | `memory` | `memory` (single instance) or `database` (`rate_limit_buckets`, multi-instance) |
| `APP_ENCRYPTION_KEY` | empty; compose has a dev-only fixed key | AES-256-GCM key, Base64 of exactly 32 bytes. Without it: MFA, SSO and the GitHub connector are unavailable (503 / hidden). A malformed key stops startup (`…/common/crypto/SecretCipher.java`) |
| `MFA_ISSUER` | `RecruitPro` | Issuer label in authenticator apps |
| `SSO_REDIRECT_BASE_URL` | `http://localhost:8080` | Base of `/api/v1/auth/sso/<id>/callback` |
| `SSO_GOOGLE_CLIENT_ID/SECRET`, `SSO_MICROSOFT_CLIENT_ID/SECRET/TENANT_ID` (tenant default `organizations`), `SSO_OIDC_NAME/CLIENT_ID/CLIENT_SECRET/ISSUER/SCOPES` | empty | A provider is offered only when id and secret are set |
| `METRICS_PUBLIC` | `false` | `/actuator/prometheus` without authentication |
| `APP_INSTANCE_ID` | host name + random suffix | Id written into notification claims |
| `ORG_SELF_SIGNUP_ENABLED` | `true` | Allow `POST /api/v1/organizations/signup` |
| `INVITATIONS_EXPOSE_LINK` | `false`; dev `true` | Return the invitation link in the API response |
| `CANDIDATE_PORTAL_URL`, `HR_PORTAL_URL` | `http://localhost:5173`, `:5174` | Links in e-mails, SSO redirects |
| `BOOTSTRAP_ORG_NAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` (+ `_FIRST_NAME`, `_LAST_NAME`) | empty | Create the first organisation and admin once, when no organisation exists (`…/organization/bootstrap/BootstrapRunner.java`) |
| `APP_SEED_DEMO`, `APP_SEED_PASSWORD` | `false`, `ChangeMe-123!`; compose `true` | Demo organisation "Acme Labs" and demo accounts; ignored in prod (`…/organization/bootstrap/DevSeedRunner.java`) |
| `BACKGROUND_JOBS_ENABLED` | `true` (test `false`) | Queue worker on/off |
| `JOB_DEADLINE_CLOSER_ENABLED`, `WORK_SAMPLE_EXPIRY_SWEEP_ENABLED`, `VERIFICATION_REFERENCE_EXPIRY_ENABLED`, `NOTIFICATIONS_SENDER_ENABLED` | `true` (test: `false`) | Scheduler switches |
| `MAIL_ENABLED` | `false`; prod default `true`; compose `true` | Really send mail (else SKIPPED) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | `localhost`, 1025, empty, empty, `false`, `false`; compose `mailpit`/1025 | SMTP |
| `MAIL_FROM` | `RecruitPro <no-reply@recruitpro.local>` | Sender; its domain is also the ICS UID domain |
| `NOTIFICATIONS_LOG_LINKS` | `false`; dev `true` | Log links with one-time tokens when mail is off (development only) |
| `AI_SERVICE_URL`, `AI_SERVICE_TOKEN` | `http://localhost:8000`, empty; compose `http://ai-service:8000` + dev token | ai-service location and shared secret |
| `AGENT_SERVICE_URL`, `AGENT_SERVICE_TOKEN` | `http://localhost:8100`, empty; compose `http://agent-service:8100` + dev token | agent-service location and secret |
| `VERIFICATION_CONSENT_VERSION` | `2026-11-v1` | Consent text version stored per verification source |
| `VERIFICATION_STORAGE_PATH` | `${FILE_STORAGE_PATH}/verification` | Evidence snapshots and verification documents |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_OAUTH_REDIRECT_URI` | empty | GitHub connector (scope `read:user`) |
| `FILE_STORAGE_PATH` | `./uploads`; compose `/data/uploads` | Resume files |
| `WORK_SAMPLE_STORAGE_PATH` | `${upload-dir}/work-samples` | Work-sample submission files |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:5174`; prod required | Exact origins (credentials allowed, `*` ignored) |

Fixed (not environment) backend settings worth knowing: access token 15 min (`app.jwt.access-token-expiry-ms:
900000`), refresh token 14 days (`1209600000`), refresh grace 30 s, verification email 24 h, password reset 60 min,
lockout 5 attempts / 15 min, invitations 7 days, queue poll 2 s / batch 5 / max attempts 5 / stale after 10 min,
notification poll 5 s / max attempts 5, resume upload `pdf,doc,docx` up to 10 MB, multipart 20 MB per file and 62 MB
per request, verification runs 10 per candidate per day (all in `application.yml`).

ai-service (`ai-service/app/core/config.py`):

| Variable | Default | Meaning |
|---|---|---|
| `AI_SERVICE_TOKEN` | empty (every `/api/*` call then answers 503) | Shared secret |
| `LLM_PROVIDER` | `none` | `none`, `anthropic`, `openai_compatible`; an enabled provider without `LLM_MODEL` stops startup |
| `LLM_MODEL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL` (`https://api.anthropic.com`), `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `LLM_TIMEOUT_SECONDS` (20) | | Optional LLM |
| `OCR_ENABLED` | `false` | OCR only with `requirements-ocr.txt` installed |
| `SOFFICE_PATH` | auto-detect on `PATH` | LibreOffice for `.doc`. On `Kshitij-redesign` the default image installs headless LibreOffice Writer (`WITH_LIBREOFFICE=true`), so `soffice` is found; on `Kshitij` the image has no LibreOffice and `.doc` goes through the olefile reader (`ai-service/Dockerfile`, `ai-service/README.md`) |
| `PARSE_WORKERS` | 2 | Process-pool size for CPU-bound parsing (`ai-service/app/core/workers.py`) |
| `PARSE_TIMEOUT_SECONDS` | 60 | Per-parse timeout |
| `MAX_UPLOAD_BYTES` | 10 MiB | Body cap (plus 64 KiB framing) |

agent-service (`agent-service/app/core/config.py` docstring and `load_settings`): `AGENT_SERVICE_TOKEN` (required),
`AGENT_ALLOW_NO_TOKEN` (tests only), `LLM_PROVIDER` (compose maps it from `AGENT_LLM_PROVIDER`, default `none`;
writes only the optional summary), the same LLM keys as the ai-service, `AGENT_DEMO_FIXTURES` (compose default
`true`), `AGENT_FETCH_TIMEOUT_SECONDS` (10), `AGENT_REQUEST_DEADLINE_SECONDS` (50), `AGENT_USER_AGENT`
(`RecruitProVerifier/1.0 (+contact)`), `GITHUB_API_URL`, `GITHUB_GRAPHQL_URL`, `GITLAB_API_URL` (public defaults),
`GITHUB_TOKEN` (optional), `AGENT_MAX_DOCUMENT_BYTES` (15 MB).

postgres: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` all `recruitpro` (`docker-compose.yml`).

Portals: `VITE_API_BASE_URL` (build arg, default `http://localhost:8080/api/v1`) and `API_ORIGIN` (build arg and
run-time env, default `http://localhost:8080`, substituted into the nginx CSP) (`frontend/Dockerfile`,
`hr-frontend/Dockerfile`).

---

## 2. Backend structure

### 2.1 Packages

All under `backend/src/main/java/com/recruitpro/` (575 Java files under `backend/src`, per
`tools/java_static_check.py`). Most packages follow `controller` → `service` → `repository` → `entity`, with
request/response records in `dto`.

| Package | Responsibility | Key classes |
|---|---|---|
| `RecruitProApplication` | Entry point; sets the JVM default zone to UTC before Spring starts | `RecruitProApplication.java` |
| `config` | Security filter chain, CORS, JPA auditing in UTC, scheduling, OpenAPI | `SecurityConfig`, `WebConfig`, `JpaConfig`, `SchedulingConfig`, `OpenApiConfig` |
| `security` | JWT issuing/parsing, the JWT and rate-limit filters, rate-limit stores, security headers, current-user helpers | `jwt/JwtService`, `filter/JwtAuthenticationFilter`, `filter/RateLimitFilter`, `ratelimit/{SlidingWindowRateLimiter, InMemoryRateLimiterStore, DatabaseRateLimiterStore, RateLimitStoreConfig}`, `headers/{ApiSecurityHeadersFilter, SecurityHeadersConfig}`, `SecurityUtils`, `CurrentUser`, `userdetails/*`, `config/CustomAuthenticationEntryPoint` |
| `auth` | Registration, login, refresh rotation, logout, email verification, password reset/change, per-portal cookies, staff MFA, staff SSO | `service/{AuthService, RefreshTokenService, UserTokenService, TokenHashing, AuthSession}`, `web/{Portal, RefreshTokenCookies}`, `validation/PasswordPolicy`, `mfa/{MfaService, MfaTokenService, Totp, Base32}`, `sso/{SsoService, OidcTokenValidator, JwksKeyCache, SsoProviderRegistry}` |
| `user` | `users` entity, roles, account creation, lockout counters | `entity/{User, Role}`, `service/UserAccountService`, `repository/UserRepository` |
| `organization` | Organisations (tenants), self sign-up, invitations, bootstrap and demo seeding | `service/{OrganizationService, InvitationService}`, `bootstrap/{BootstrapRunner, DevSeedRunner, DemoDataSeeder, DemoAssessmentSeeder}` |
| `admin` | Organisation admin: dashboard (incl. system health), staff users, job oversight, audit log and CSV | `controller/AdminController`, `service/{AdminDashboardService, SystemHealthService, AdminUserService, AdminJobService, AdminHrManagementService}` |
| `hr` | Jobs and requirements, applicant lists and ranking, decisions, reveal, deadline closer, job audit CSV | `service/{JobService, HrJobAccessService, ApplicantQueryService, ApplicantRanking, ApplicationDecisionService, RequirementCompilerService, JobDeadlineCloser, JobAuditExportService}` |
| `candidate` | Candidate profile, resumes and their storage, resume analysis view, privacy (export, account deletion) | `service/{CandidateProfileService, ResumeService, ResumeIntelligenceService}`, `storage/LocalResumeStorage`, `privacy/{CandidateDataExportService, CandidateAccountDeletionService}` |
| `application` | Applications from the candidate side, status machine, notifications on status change | `service/{ApplicationService, ApplicationTransitions, ApplicationNotifier}`, `entity/{Application, ApplicationDecisionHistory}` |
| `evidence` | Evidence ladder: candidate claims, match request building, match triggers and the MATCH_APPLICATION job | `service/{CandidateClaimService, MatchRequestBuilder, MatchTriggerService, MatchingService, MatchApplicationJobHandler, EvidenceContributor}` |
| `ai` | Client of the ai-service; PARSE_RESUME job handler | `service/{AiServiceClient, AiAuthoringCalls, ResumeParseJobHandler}` |
| `assessment` | Work samples, assignments, submission files, expiry sweep | `service/{WorkSampleService, CandidateWorkSampleService, WorkSampleFileService, WorkSampleExpirySweeper}` |
| `interview` | Interview kits, interviews, scorecards and revisions, ICS invitations | `service/{InterviewService, InterviewKitService, IcsCalendar, InterviewCalendarService}` |
| `fairness` | Voluntary equal-opportunity survey and suppressed adverse-impact reports | `service/{EqualOpportunityService, FairnessReportService, FairnessCalculator}` |
| `verification` | Claim Ledger: sources, ownership checks, runs, evidence, verdicts, references, staff review, shadow mode metrics, GitHub OAuth | `service/{VerificationSourceService, VerificationRunService, VerifySourceJobHandler, VerifyReferenceJobHandler, ReferenceService, StaffVerificationService, ShadowEvaluationService, VerificationMaintenance, PublicEndpointRateLimiter}`, `client/{AgentServiceClient, GitHubOAuthClient}` |
| `jobs` | Durable background job queue | `service/{BackgroundJobService, BackgroundJobWorker, BackgroundJobHandler, NonRetryableJobException}` |
| `notification` | Outbox (EMAIL rows) and in-app inbox (IN_APP rows), templates, sender | `service/{NotificationService, NotificationSender}`, `entity/NotificationTemplate` |
| `audit` | Persistent audit trail and CSV export | `service/{AuditService, AuditActions}`, `entity/AuditEvent` |
| `observability` | Request ids (servlet filter and outgoing interceptor), timer names | `RequestIdFilter`, `RequestIdClientInterceptor`, `TimedClientInterceptor`, `ObservabilityConfig`, `RequestIds` |
| `common` | Envelope, exceptions and global handler, UTC time, AES-GCM cipher, CSV, LIKE escaping, client IP, base entity, public health | `dto/{ApiResponse, PageResponse}`, `exception/GlobalExceptionHandler`, `time/{UtcTime, UtcTimeModule}`, `crypto/SecretCipher`, `util/{Csv, LikePatterns, JsonText}`, `web/ClientIp`, `entity/BaseEntity`, `controller/HealthController` |

### 2.2 Request lifecycle

Servlet filter order for a request to `/api/v1/**`:

| # | Filter | Registered in | What it does |
|---|---|---|---|
| 1 | `RequestIdFilter` | `…/observability/ObservabilityConfig.java` (`Ordered.HIGHEST_PRECEDENCE`) | Reuses an incoming `X-Request-Id` matching 8–64 chars of `[A-Za-z0-9._-]`, else a UUID; puts it in the MDC key `requestId`; echoes it in the response; keeps it on error dispatch |
| 2 | `ApiSecurityHeadersFilter` | `…/security/headers/SecurityHeadersConfig.java` (`HIGHEST_PRECEDENCE + 1`) | Sets `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` on every response and the API CSP on `/api/v1/**` and `/actuator/**`; runs before Spring Security, so 401/403/429 carry them |
| 3 | Spring Security chain (`…/config/SecurityConfig.java`) | Spring Boot default order | CORS (`.cors(withDefaults())` using `WebConfig`), then `RateLimitFilter`, then `JwtAuthenticationFilter` (both inserted before `UsernamePasswordAuthenticationFilter`, in that order), then URL authorisation |

`RateLimitFilter` and `JwtAuthenticationFilter` are Spring beans, so Spring Boot would also register them as plain
servlet filters; `SecurityConfig` disables those registrations so each runs once, inside the chain only.

Inside the chain:

- CSRF is disabled and sessions are `STATELESS` (`SecurityConfig`). The API uses bearer tokens; the refresh cookie
  is `SameSite=Strict` and scoped to `/api/v1/auth` (Section 4.3).
- `JwtAuthenticationFilter` parses `Authorization: Bearer` once (signature, issuer, expiry), then loads the user row
  by `uid` on every request and rejects disabled, deleted, or tokens issued before the last password change
  (`…/security/userdetails/CustomUserDetailsService.java` `loadForAccessToken`). An invalid token leaves the request
  unauthenticated rather than failing.
- URL rules: `OPTIONS` open; `/actuator/health` open; `/actuator/**` ADMIN (prometheus open only with
  `METRICS_PUBLIC=true`); the `PUBLIC_ENDPOINTS` list (health, register, login, refresh, logout, verify-email,
  forgot/reset password, invitation preview/accept, organisation sign-up, MFA verify, SSO, MFA enrol/confirm with
  an `mfaToken`, public reference pages, GitHub OAuth callback); Swagger paths; everything else authenticated.
- Method security: `@EnableMethodSecurity` plus `@PreAuthorize` on controllers (24 × ADMIN/HR_MANAGER, 13 ×
  ADMIN/HR_MANAGER/RECRUITER, 9 × CANDIDATE, 5 × ADMIN, 3 × all staff, 2 × staff-readers + CANDIDATE).
- 401 and 403 from the security layer are written by `…/security/config/CustomAuthenticationEntryPoint.java` in the
  same JSON envelope.

Then controller → service → repository:

- Controllers are thin; they read the portal header where relevant and wrap results in `ApiResponse`.
- Services carry `@Transactional` boundaries. `spring.jpa.open-in-view: false`, so lazy loading outside a service
  transaction fails fast instead of issuing hidden queries (`application.yml`).
- Tenancy is enforced in services: staff queries go through organisation-scoped lookups such as
  `JobRepository.findByIdAndOrganizationId`, and a record of another organisation behaves as missing (404)
  (`…/hr/service/HrJobAccessService.java`).
- Concurrency control uses pessimistic row locks (`PESSIMISTIC_WRITE`, 17 uses in the backend) and conditional
  `UPDATE … WHERE status = …` claims. No entity uses `@Version` optimistic locking.
- Outbox pattern: audit rows (`AuditService.record`), notifications (`NotificationService.notify`) and background jobs
  (`BackgroundJobService.enqueue`) are written in the caller's transaction, so they exist exactly when the change
  that triggered them commits (class Javadoc of each).

**Envelope** (`…/common/dto/ApiResponse.java`): `{ success, message?, data?, errors?, timestamp }`, null fields
omitted. `errors` is a list of `{field, message}` for validation failures. There is no separate error-code field;
where a machine code is needed it is the start of `message`, for example `MFA_NOT_CONFIGURED: …`
(`docs/plan/WP-PLAT-NOTES.md`). File downloads (resumes, CSV exports, work-sample files) are raw bodies with
`Content-Disposition: attachment`, not the envelope. `PageResponse` wraps paged lists.

**Exception mapping** (`…/common/exception/GlobalExceptionHandler.java`):

| Exception | Status | Message returned |
|---|---|---|
| `ResourceNotFoundException`, `NoHandlerFoundException`, `NoResourceFoundException` | 404 | Exception message / "The requested endpoint does not exist" |
| `DuplicateResourceException` | 409 | Exception message |
| `DataIntegrityViolationException` | 409 | Generic constraint message |
| `PessimisticLockingFailureException`, `ObjectOptimisticLockingFailureException` | 409 | "modified by another request. Please retry." |
| `MethodArgumentNotValidException`, `ConstraintViolationException` | 400 | "Validation failed" + `errors[]` |
| `HandlerMethodValidationException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `MissingServletRequestPartException`, `HttpMessageNotReadableException`, `BadRequestException`, `IllegalArgumentException` (generic "Invalid request") | 400 | |
| `HttpMediaTypeNotSupportedException` | 415 | |
| `MaxUploadSizeExceededException` | 413 | |
| `TooManyRequestsException` | 429 | |
| `ResponseStatusException` | its status | its reason |
| `UnauthorizedException`, Spring `AuthenticationException` | 401 | |
| `ForbiddenException`, Spring `AccessDeniedException` | 403 | |
| `HttpRequestMethodNotSupportedException` | 405 | |
| anything else | 500 | "An unexpected error occurred. Please try again later." (class name logged, never the message) |

`server.error.include-message: never` and `include-binding-errors: never` keep Spring's own error page from leaking
details (`application.yml`).

### 2.3 UTC time handling

- JVM default zone set to UTC in `main()` (`…/RecruitProApplication.java`) and in the Docker image; tests run with
  `-Duser.timezone=UTC` (`backend/pom.xml` surefire `argLine`).
- Hibernate writes and reads timestamps in UTC (`spring.jpa.properties.hibernate.jdbc.time_zone: UTC`).
- JPA auditing (`created_at`, `updated_at`) uses `LocalDateTime.now(ZoneOffset.UTC)` (`…/config/JpaConfig.java`).
- Entities use `LocalDateTime` values that are UTC by convention; `…/common/time/UtcTime.java` produces, formats and
  parses them.
- JSON: `UtcTimeModule` serialises every `LocalDateTime` as ISO-8601 with a trailing `Z`, truncated to milliseconds,
  and converts incoming values with an offset to UTC; a value without an offset is taken as UTC, a plain date as the
  start of that UTC day (`…/common/time/UtcTimeModule.java`, `UtcLocalDateTimeDeserializer.java`). The portals send
  `toISOString()` (`docs/plan/STATUS.md` M12).
- Database columns are `TIMESTAMP` (without time zone) in every migration.

### 2.4 JSON conventions

From `application.yml` `spring.jackson`: dates as strings, unknown request properties ignored
(`fail-on-unknown-properties: false`), `default-property-inclusion: non_null` (absent fields are omitted).
Exception: the records added by WP-BE-C and some WP-PLAT/WP-VBE responses use `@JsonInclude(ALWAYS)` and send explicit
`null`/`[]` (`docs/plan/STATUS.md` §2.1, `docs/plan/WP-PLAT-NOTES.md` §1.4). JSON stored in the database is `TEXT`
columns ending in `_json` (for example `job_requirements.alternatives_json`, `job_match_runs.requirement_results_json`)
handled by `…/common/persistence/StringListJsonConverter.java` and `…/common/util/JsonText.java`; the plan forbids
PostgreSQL-only types so the same migrations run on H2 (`docs/plan/IMPLEMENTATION_PLAN_V2.md` line 52).

---

## 3. Data model

Source: `backend/src/main/resources/db/migration/V1__…` to `V24__…` (1,036 lines). 42 application tables plus
Flyway's own `flyway_schema_history`. 41 JPA entities map 474 columns, checked against the migrations by
`tools/check_entity_schema.py` (0 errors, 0 warnings, re-run 16 Nov 2026). `rate_limit_buckets` is used through JDBC
only and has no entity (`…/security/ratelimit/DatabaseRateLimiterStore.java`).

Conventions: `BIGINT GENERATED BY DEFAULT AS IDENTITY` primary keys, `created_at`/`updated_at` on almost every
table, status columns as `VARCHAR` with `CHECK` constraints, JSON as `TEXT`. "Candidate" foreign keys point to
`candidate_profiles.id`, not `users.id`.

### 3.1 Tables by domain

**Platform metadata**

| Table (migration) | Key columns | Constraints and relations |
|---|---|---|
| `system_metadata` (V1) | `property_key`, `property_value` | unique key; seeded `schema_version=1.0.0` |

**Identity, tenancy and access**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `organizations` (V11, V23) | `name`, `slug`, `blind_review` (default TRUE), `share_verification_status` (TRUE), `require_staff_mfa` (FALSE), `sso_allowed_domains` (VARCHAR 500) | unique `slug` |
| `users` (V2, V11) | `email`, `password_hash`, names, `role`, `enabled`, `organization_id`, `email_verified_at`, `failed_login_count`, `locked_until`, `password_changed_at`, `deleted_at` | unique `email`; FK `organization_id` → organizations (NULL for candidates) |
| `invitations` (V11) | `organization_id`, `email`, `role`, `token_hash`, `invited_by_user_id`, `expires_at`, `accepted_at`, `revoked_at` | unique `token_hash`; role CHECK ADMIN/HR_MANAGER/RECRUITER/INTERVIEWER; FK org; invited_by `ON DELETE SET NULL` |
| `refresh_tokens` (V3, V12) | `user_id`, `token_hash`, `family_id`, `expires_at`, `revoked_at`, `replaced_at`, `revoked_reason` | unique `token_hash`; FK user `ON DELETE CASCADE`; index on `family_id` |
| `user_tokens` (V12, V23) | `user_id`, `purpose`, `token_hash`, `expires_at`, `used_at` | purpose CHECK VERIFY_EMAIL/RESET_PASSWORD/MFA_LOGIN/MFA_ENROLL; unique hash; FK user CASCADE |
| `user_mfa` (V23) | `user_id` (PK), `secret_enc`, `enabled_at`, `last_used_step` | 1:1 with users, CASCADE |
| `user_recovery_codes` (V23) | `user_id`, `code_hash` (bcrypt), `used_at` | FK user CASCADE |
| `user_identities` (V23) | `user_id`, `provider`, `subject`, `email`, `last_login_at` | unique (`provider`, `subject`); FK user CASCADE |
| `sso_login_states` (V23) | `state_hash`, `nonce_hash`, `code_verifier_enc`, `provider`, `portal`, `expires_at`, `used_at` | unique `state_hash`; no FK |
| `rate_limit_buckets` (V24) | `bucket_key` (PK), `window_start`, `hit_count` | index on `window_start` |

**Staff and jobs**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `hr_profiles` (V4) | `user_id`, `employee_id`, `department`, `designation` | unique `user_id`, unique `employee_id`; FK user `RESTRICT`. Created for ADMIN, HR_MANAGER, RECRUITER (`…/user/service/UserAccountService.java`) |
| `jobs` (V4, V11, V15) | `organization_id` (NOT NULL), `created_by` (→ hr_profiles), `title`, `description`, `department`, `location`, `job_type`, `experience_level`, salary range, `vacancies`, `status` (default DRAFT), `published_at`, `deadline`, `requirements_version`, `closed_reason` | CHECKs: vacancies > 0, salary ≥ 0, min ≤ max, `requirements_version ≥ 1`; indexes (org, status), (status, deadline) |
| `job_requirements` (V4, V15) | `job_id`, `requirement_type`, `description`, `is_mandatory`, `minimum_years`, `alternatives_json`, `knockout`, `proficiency` | FK job CASCADE; proficiency CHECK BASIC/INTERMEDIATE/ADVANCED/EXPERT |

**Candidates, resumes and claims**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `candidate_profiles` (V5, V20) | `user_id`, address fields, URLs, experience, salary, notice period, summary | unique `user_id`; FK user RESTRICT. V20 dropped `date_of_birth` and `gender` |
| `candidate_resumes` (V6) | `candidate_id`, `storage_key` (36 chars, opaque), `original_filename`, `file_extension`, `content_type`, `file_size`, `uploaded_at` | unique `storage_key`; size > 0; FK candidate RESTRICT; append-only history |
| `resume_analyses` (V8, V17) | `resume_id`, `status`, `parser_version`, `source_sha256`, `error_code`, `attempt_count`, `flags_json`, `timeline_json` | unique `resume_id` (1:1); status CHECK PENDING/PROCESSING/COMPLETED/PARTIAL/FAILED/NEEDS_OCR/UNSUPPORTED_FORMAT |
| `resume_analysis_facts` (V8, V17) | `analysis_id`, `fact_type`, `extracted_value`, `normalized_value`, `evidence_text`, `source_section`, `review_status`, `reviewed_value`, `evidence_tier`, `span_start/end`, `attributes_json` | FK analysis CASCADE; fact_type CHECK SKILL/EDUCATION/EXPERIENCE/CERTIFICATION/EMAIL/PHONE/LINK; tier CHECK SELF_CLAIMED/RESUME_STATED/CORROBORATED/DEMONSTRATED/INTERVIEW_VERIFIED |
| `candidate_claims` (V17) | `candidate_id`, `claim_type`, `claim_value`, `evidence_url`, `source_fact_id`, `tier`, `verified_by_user_id`, `verified_at`, `verification_note` | FK candidate CASCADE; type and tier CHECKs |
| `eeo_responses` (V20) | `candidate_id`, `gender`, `age_band`, `disability`, `social_category`, `consent_given_at` | unique `candidate_id`; FK CASCADE; every value has a CHECK list including PREFER_NOT |

**Applications and screening**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `applications` (V7, V10, V16) | `candidate_id`, `job_id`, `resume_id`, `status`, `cover_letter`, `review_comment`, `identity_revealed_at`, `identity_revealed_by`, `status_changed_at`, `applied_at` | unique (`candidate_id`, `job_id`); status CHECK SUBMITTED/SHORTLISTED/INTERVIEWING/SELECTED/REJECTED/WITHDRAWN; FKs RESTRICT |
| `application_decision_history` (V10, V16) | `application_id`, `previous_status`, `new_status`, `action_type`, `actor_user_id`, `comment`, `match_run_id` | action CHECK SHORTLIST/REJECT/COMMENT_UPDATED/UNDO_SHORTLIST/REOPEN/MOVE_TO_INTERVIEW/SELECT/WITHDRAW/IDENTITY_REVEALED; FK to the match run that was on screen |
| `job_match_runs` (V9, V17) | `application_id`, `job_id`, `resume_analysis_id`, `input_fingerprint`, `algorithm_version`, `verified_coverage`, `claimed_coverage`, `knockouts_verified`, `requirement_results_json`, `is_current`, `requirements_version`, `trigger_reason` | append-only: V17 dropped the unique application constraint; coverages 0–100 CHECKs; index (job, is_current, verified_coverage) |

**Assessments**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `work_samples` (V18) | `job_id`, `title`, `instructions`, `time_limit_minutes`, `rubric_json`, `status` | FK job CASCADE; status DRAFT/ACTIVE/ARCHIVED; time limit > 0 |
| `work_sample_assignments` (V18, V24) | `work_sample_id`, `application_id`, `status`, `due_at` (latest start), `started_at`, `submitted_at`, `response_text`, `response_url`, `scores_json`, `total_score`, `cancelled_*` | unique (sample, application); status ASSIGNED/STARTED/SUBMITTED/SCORED/EXPIRED/CANCELLED; total 1–4 |
| `work_sample_submission_files` (V24) | `assignment_id`, `original_name`, `content_type`, `size_bytes`, `storage_path`, `sha256` | FK CASCADE; size > 0 |
| `interview_kits` (V19) | `job_id`, `questions_json`, `generated_by` | unique `job_id` (1:1); CASCADE |
| `interviews` (V19, V24) | `application_id`, `interviewer_user_id`, `scheduled_at`, `duration_minutes`, `location`, `status`, `ics_sequence` | status SCHEDULED/COMPLETED/CANCELLED; duration > 0 |
| `interview_scorecards` (V19) | `interview_id`, `ratings_json`, `recommendation`, `summary`, `submitted_at` | unique `interview_id` (1:1); recommendation STRONG_NO/NO/YES/STRONG_YES |
| `interview_scorecard_revisions` (V24) | `scorecard_id`, `revised_by`, `snapshot_json` | FK CASCADE; revised_by SET NULL |

**Verification (Claim Ledger)**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `verification_sources` (V21) | `candidate_id`, `kind`, `identifier`, `ownership_status`, `ownership_method`, `challenge_code`, `access_token_enc`, `file_path`, `credential_text`, `consent_text_version`, `consented_at`, `revoked_at` | kind GITHUB/GITLAB/WEBSITE/DOCUMENT/OPEN_BADGE; ownership UNPROVEN/PENDING/PROVEN/FAILED; method OAUTH/DNS_TXT/WELL_KNOWN_FILE/PAGE_CODE/PROFILE_BIO_CODE/ISSUER_SIGNATURE |
| `verification_runs` (V21) | `source_id`, `candidate_id`, `status`, `verifier`, `verifier_version`, `requested_by`, `error_code`, `usage_json`, `summary_json`, `warnings_json` | status PENDING/RUNNING/COMPLETED/FAILED; requested_by CANDIDATE/SYSTEM |
| `evidence_items` (V21, V22) | `run_id`, `source_id`, `reference_id`, `candidate_id`, `item_key`, `kind`, `url`, `content_hash`, `snapshot_path`, `excerpt` | kind PROFILE/REPOSITORY/MANIFEST/COMMIT_SUMMARY/README/PAGE/DOCUMENT/BADGE/OWNERSHIP_PROOF/REFERENCE_RESPONSE |
| `claim_verdicts` (V21, V22) | `candidate_id`, `subject_type`, `subject_id`, `claim_type`, `claim_value`, `source_id`, `reference_id`, `run_id`, `verifier`, `verdict`, `ownership_proven`, `reasons_json`, `integrity_notes_json`, `evidence_item_ids_json`, `probe_question`, `candidate_status`, `is_current` | subject RESUME_FACT/CANDIDATE_CLAIM (polymorphic `subject_id`, no FK); verdict VERIFIED/PARTLY_VERIFIED/NOT_FOUND/CONTRADICTED/NOT_CHECKABLE; candidate_status AWAITING_CANDIDATE/SHARED/HIDDEN/WITHDRAWN/SUPERSEDED |
| `claim_verdict_reviews` (V21) | `verdict_id`, `organization_id`, `decision`, `note`, `reviewer_user_id` | decision CONFIRM/OVERRULE; per organisation |
| `claim_verdict_labels` (V21) | `verdict_id`, `organization_id`, `labeller_user_id`, `label_verdict` | unique (verdict, labeller) |
| `organization_verification_settings` (V21) | `organization_id` (PK), `mode` (default SHADOW), `connectors_json` | mode SHADOW/VISIBLE |
| `verification_oauth_states` (V21) | `state_hash`, `candidate_id`, `provider`, `expires_at`, `used_at` | unique hash; provider GITHUB |
| `verification_reference_requests` (V22) | `candidate_id`, referee name/email, `relationship`, employer, `claim_refs_json`, `token_hash`, `status`, `answers_json`, `expires_at`, `answered_at`, `consented_at` | unique token hash; status SENT/ANSWERED/DECLINED/EXPIRED/CANCELLED |

**Platform services**

| Table | Key columns | Constraints and relations |
|---|---|---|
| `audit_events` (V13) | `organization_id`, `actor_user_id`, `actor_email`, `action`, `target_type`, `target_id`, `details`, `ip_address` | no FKs on purpose (rows outlive users); indexes (org, created_at), (action) |
| `background_jobs` (V14, V24) | `job_type`, `entity_id`, `status`, `attempts`, `max_attempts` (5), `run_after`, `locked_at`, `last_error`, `dedupe_key` | status CHECK; unique `dedupe_key`; polymorphic `entity_id` (no FK) |
| `notifications` (V14, V24) | `organization_id`, `recipient_user_id`, `recipient_email`, `channel`, `template`, `subject`, `body`, `status`, `attempts`, `sent_at`, `read_at`, `claimed_by`, `claimed_until`, `attachment_*` | channel EMAIL/IN_APP; status PENDING/SENT/FAILED/SKIPPED; recipient FK SET NULL |

Data migrations inside the schema history worth knowing: V8 created a PENDING analysis for existing pdf/docx resumes;
V11 created a "Default organisation" and moved existing staff and jobs into it; V14 and V17 queued PARSE_RESUME and
MATCH_APPLICATION jobs for existing rows; V24 back-filled `dedupe_key` on the oldest PENDING job per (type, entity).

### 3.2 Text ERD

Notation: `A 1─1 B`, `A 1─* B` (one A has many B), `A *─1 B`; `[CASCADE]` etc. is the delete rule; `(poly)` means a
logical reference without a database foreign key.

```
organizations 1─* users                      (users.organization_id; NULL for CANDIDATE)
organizations 1─* jobs                       (jobs.organization_id NOT NULL)
organizations 1─* invitations
organizations 1─1 organization_verification_settings   [CASCADE]
organizations 1─* claim_verdict_reviews      [CASCADE]
organizations 1─* claim_verdict_labels       [CASCADE]

users 1─0..1 hr_profiles                     [RESTRICT]   (staff except INTERVIEWER)
users 1─0..1 candidate_profiles              [RESTRICT]   (CANDIDATE)
users 1─* refresh_tokens                     [CASCADE]    (grouped by family_id)
users 1─* user_tokens                        [CASCADE]
users 1─0..1 user_mfa                        [CASCADE]
users 1─* user_recovery_codes                [CASCADE]
users 1─* user_identities                    [CASCADE]
users 1─* notifications (recipient)          [SET NULL]
users 1─* interviews (interviewer)
users 1─* application_decision_history (actor)

hr_profiles 1─* jobs (created_by)            [RESTRICT]
jobs 1─* job_requirements                    [CASCADE]
jobs 1─* applications                        [RESTRICT]
jobs 1─* work_samples                        [CASCADE]
jobs 1─0..1 interview_kits                   [CASCADE]
jobs 1─* job_match_runs                      [RESTRICT]

candidate_profiles 1─* candidate_resumes     [RESTRICT]
candidate_resumes 1─1 resume_analyses        [RESTRICT]
resume_analyses 1─* resume_analysis_facts    [CASCADE]
candidate_profiles 1─* candidate_claims      [CASCADE]   (candidate_claims.source_fact_id → resume_analysis_facts, poly)
candidate_profiles 1─0..1 eeo_responses      [CASCADE]
candidate_profiles 1─* applications          [RESTRICT]
candidate_resumes 1─* applications (resume used) [RESTRICT]

applications 1─* application_decision_history [RESTRICT]
applications 1─* job_match_runs              [RESTRICT]   (one is_current=TRUE)
resume_analyses 1─* job_match_runs           [RESTRICT]
job_match_runs 1─* application_decision_history (match_run_id, evidence shown at decision time)
applications 1─* work_sample_assignments
work_samples 1─* work_sample_assignments
work_sample_assignments 1─* work_sample_submission_files [CASCADE]
applications 1─* interviews
interviews 1─0..1 interview_scorecards       [CASCADE]
interview_scorecards 1─* interview_scorecard_revisions [CASCADE]

candidate_profiles 1─* verification_sources  [CASCADE]
verification_sources 1─* verification_runs   [CASCADE]
verification_runs 1─* evidence_items         [CASCADE]
verification_sources 1─* evidence_items      [CASCADE]
candidate_profiles 1─* verification_reference_requests [CASCADE]
verification_reference_requests 1─* evidence_items     [CASCADE]
candidate_profiles 1─* claim_verdicts        [CASCADE]
claim_verdicts *─1 verification_sources | verification_reference_requests [CASCADE]; *─1 verification_runs [SET NULL]
claim_verdicts.subject_id (poly) → resume_analysis_facts.id | candidate_claims.id
claim_verdicts 1─* claim_verdict_reviews     [CASCADE]
claim_verdicts 1─* claim_verdict_labels      [CASCADE]
candidate_profiles 1─* verification_oauth_states [CASCADE]

background_jobs.entity_id (poly) → resume_analyses | applications | verification_runs | verification_reference_requests
audit_events (no FKs) → organization_id, actor_user_id, target_type/target_id as text
sso_login_states, rate_limit_buckets, system_metadata: stand-alone
```

`docs/database/ENTITY_RELATIONSHIP.md` is an older design document (it describes Assessment, Offer and Onboarding
entities that do not exist and calls resume parsing future scope). Use this section for the real schema.

---

## 4. Identity and security

### 4.1 Accounts and sign-in

- **Registration** (`POST /api/v1/auth/register`) always creates a CANDIDATE with a candidate profile, sends a
  24-hour verification link and signs the user in (`…/auth/service/AuthService.java` `register`,
  `…/user/service/UserAccountService.java` `createCandidateUser`). On the staff portal the call is refused with 403
  (`…/auth/controller/AuthController.java`). Emails are trimmed and lower-cased; duplicates give 409.
- **Password policy**: 10–72 characters, at least one letter and one digit, at most 72 UTF-8 bytes (bcrypt limit)
  (`…/auth/validation/PasswordPolicy.java`). Passwords are hashed with `BCryptPasswordEncoder` (default strength)
  (`…/config/SecurityConfig.java`).
- **Email verification**: one-time token, SHA-256 hash stored, single use, 24 h; issuing a new one invalidates
  earlier ones (`…/auth/service/UserTokenService.java`). Unverified users can sign in; applying requires
  verification only when `AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY=true` (default true in prod).
- **Login** (`AuthService.login`): Spring's `DaoAuthenticationProvider` checks lock, enabled and password. A locked
  account gets the same 401 as a wrong password (`LockedException` is turned into `BadCredentialsException`). Then the
  portal role gate (403, before any session, failure counters untouched), then the MFA challenge for staff, else
  failure counters reset and a session is created.
- **Lockout**: 5 consecutive failures lock for 15 minutes, audited as `LOGIN_LOCKED`
  (`UserAccountService.recordFailedLogin`, `CustomUserDetails.isAccountNonLocked`). MFA code failures count towards
  the same counter (`docs/plan/WP-PLAT-NOTES.md` §1.1).
- **Password reset**: `forgot-password` always answers success (no account enumeration) and emails a 60-minute
  link only for an enabled, non-deleted account; `reset-password` checks the policy, consumes the token, marks the
  email verified, revokes every refresh token and audits `PASSWORD_RESET` (`AuthService`).
- **Password change** (`POST /api/v1/me/password`): needs the current password, refuses an unchanged password,
  revokes all refresh tokens and every older access token (via `password_changed_at`), audits `PASSWORD_CHANGED`
  and returns a new session (`AuthService.changePassword`).

### 4.2 Access token

- Lifetime 15 minutes (`app.jwt.access-token-expiry-ms: 900000`); `expiresInSeconds` is returned with it.
- Signed with an HMAC key built from `JWT_SECRET` by `Keys.hmacShaKeyFor` (jjwt chooses HS256/384/512 by key
  length); secrets shorter than 32 bytes stop startup (`…/security/jwt/JwtService.java`).
- Claims: `sub` = email, `uid` = user id, `role`, `org` = organisation id (absent for candidates), `iss` =
  `recruitment-platform`, `jti` (random UUID), `iat`, `exp`.
- Validation: signature, issuer and expiry, then the user row is loaded on every request; disabled or deleted
  accounts, and tokens issued before the last password change, are rejected (`CustomUserDetailsService`). Role
  changes take effect on the next request because authorities come from the database row, not the claim.
- Portals keep the access token in a module-level variable only, never in `localStorage`/`sessionStorage`
  (`frontend/src/api/client.ts` header comment and `let accessToken`; `hr-frontend/src/api/client.ts`). A reload
  recovers the session through the refresh cookie.
- Logout revokes the refresh family but cannot revoke an access token already issued; it stays valid until it
  expires (at most 15 minutes) unless the password changes or the account is disabled.

### 4.3 Refresh tokens, families and per-portal cookies

`…/auth/service/RefreshTokenService.java`:

- A refresh token is 32 random bytes, base64url (43 chars); only its SHA-256 is stored (`TokenHashing`). Lifetime 14
  days from issue.
- A sign-in starts a new **family** (`family_id` = UUID). Each `POST /auth/refresh` revokes the presented token with
  reason `ROTATED` and issues the next token of the same family. The row is read with a write lock
  (`findByTokenHashForUpdate`), so two concurrent refreshes serialise.
- **Grace window**: presenting a token rotated less than 30 s ago, while the family still has an active token, returns
  a new access token without a new refresh token (two tabs refreshing together).
- **Reuse detection**: presenting a rotated token after the grace window revokes every active token of the family
  (`REUSE_DETECTED`), logs a warning and audits `REFRESH_TOKEN_REUSE_DETECTED` when something was actually revoked.
  The revocation is committed even though the call fails (`noRollbackFor = UnauthorizedException`).
- Other revocation reasons: `LOGOUT` (whole family), `PASSWORD_CHANGED`, `ACCOUNT_DISABLED` (all of the user's tokens).
- Each rotation gives the new token a fresh 14-day expiry, so an active session slides; there is no absolute
  maximum session length.
- Expired and revoked rows are never purged (no clean-up job exists for `refresh_tokens`).

Cookies (`…/auth/web/RefreshTokenCookies.java`, `…/auth/web/Portal.java`):

- `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`, `Max-Age` 14 days, `Secure` per `app.auth.cookie-secure`.
- Both portals send `X-RecruitPro-Portal: candidate` or `staff` on every request. The header selects the cookie:
  `rp_refresh_candidate` or `rp_refresh_staff`. Without the header the legacy `rp_refresh` is used (API clients, the
  CI smoke test). An unknown value is 400. Cookies ignore ports, so without this split `localhost:5173` and `:5174`
  would overwrite each other's session (`Portal` Javadoc).
- Role gate: the candidate portal accepts only CANDIDATE; the staff portal accepts every other role. Login, refresh
  (before rotation), invitation acceptance and organisation sign-up from the wrong portal are 403 with a message
  naming the right portal; registration from the staff portal is 403.
- A refresh refused with 401/403 also clears that portal's cookie (`AuthController.refresh`).
- Clients: single-flight refresh with one retry, sign-out when the refresh is refused (`docs/plan/STATUS.md` M6).
- CORS allows the header (`…/config/WebConfig.java`).

### 4.4 Rate limiting

Per client IP, in the security chain (`…/security/filter/RateLimitFilter.java`). Over the limit: 429, JSON envelope,
`Retry-After` seconds. Only these routes are limited:

| Route | Limit |
|---|---|
| `POST /api/v1/auth/login` | 10 / minute |
| `/api/v1/auth/register` | 5 / minute |
| `/api/v1/auth/refresh` | 30 / minute |
| `/api/v1/auth/forgot-password` | 5 / 15 minutes |
| `/api/v1/auth/accept-invite` | 10 / minute |
| `/api/v1/auth/mfa/verify` | 10 / minute |
| any other `/api/v1/auth/**` (verify-email, reset-password, invitation preview, SSO, logout …) | 30 / minute |
| `/api/v1/organizations/signup` | 3 / hour |
| `DELETE /api/v1/candidates/me` (account deletion with password) | 5 / 15 minutes |

Separate in-memory limits for public verification routes (`…/verification/service/PublicEndpointRateLimiter.java`):
referee page GET 30/min, referee submit 10/15 min, GitHub OAuth callback 30/min, ownership checks 20/hour per
candidate. Verification runs: 10 per candidate per 24 h (`app.verification.max-runs-per-day`), returned as 429.

Stores (`…/security/ratelimit/RateLimitStoreConfig.java`):

- `memory` (default): sliding window of timestamps per key in a `ConcurrentHashMap`
  (`SlidingWindowRateLimiter.java`). Correct for one instance only.
- `database`: fixed-window counters in `rate_limit_buckets`, one short `REQUIRES_NEW` transaction per request
  (`SELECT … FOR UPDATE`, then insert/reset/increment), primary-key collision retried up to 3 times, keys over 200
  chars hashed, rows older than 2 h purged every 500 calls (`DatabaseRateLimiterStore.java`). A fixed window can let
  up to twice the limit through around a window boundary (class Javadoc).
- Both fail open: if the store throws, the request is allowed and a warning is logged (`RateLimitFilter`).
- The client IP is `request.getRemoteAddr()` (`…/common/web/ClientIp.java`). `server.forward-headers-strategy` is not
  set in any yml, so behind a reverse proxy every client would share the proxy's IP until that is configured.
- `PublicEndpointRateLimiter` always uses its own in-memory limiter, even with `RATE_LIMIT_STORE=database`.

### 4.5 Roles and permissions

Five roles (`…/user/entity/Role.java`). One role per user. Summary derived from the `@PreAuthorize` annotations on
every controller and from `HrJobAccessService`:

| Capability | CANDIDATE | INTERVIEWER | RECRUITER | HR_MANAGER | ADMIN |
|---|---|---|---|---|---|
| Own profile, resumes, applications, claims, work samples, interviews list, EEO survey, data export, account deletion, verification sources/ledger/references | yes | – | – | – | – |
| Browse published jobs (`GET /api/v1/jobs`, `/{id}`) | yes | – | yes | yes | yes |
| Own inbox, `/me`, password change | yes | yes | yes | yes | yes |
| MFA self-service (`/me/mfa`) | 403 | yes | yes | yes | yes |
| Own interviews, submit/edit own scorecard, interviewer list | – | yes | yes | yes | yes |
| Read jobs, applicants, decision history, staff resume download (audited), review comment | – | – | yes | yes | yes |
| Corroborate/reject candidate claims, score work samples, review verification verdicts | – | – | yes | yes | yes |
| Create/edit/delete jobs and requirements, AI requirement compiler, re-match, job audit CSV | – | – | – | yes | yes |
| Shortlist/reject/select/undo/reopen, reveal identity | – | – | – | yes | yes |
| Work samples: generate, edit, assign, cancel; interview kits: edit, generate; schedule/reschedule interviews | – | – | – | yes | yes |
| Per-job fairness report; verification shadow queue, labels, metrics | – | – | – | yes | yes |
| Admin dashboard and system health, staff users (status, role), job oversight, audit log and CSV, organisation settings (blind review, MFA requirement, SSO domains), invitations, org-wide fairness, verification mode | – | – | – | – | yes |
| Actuator (`/actuator/**` except health) | – | – | – | – | yes |

Controller sources: `…/admin/controller/AdminController.java` (class-level ADMIN), `…/hr/controller/*`,
`…/assessment/controller/WorkSampleController.java`, `…/interview/controller/*`,
`…/verification/controller/*`, `…/fairness/controller/FairnessReportController.java`,
`…/organization/controller/AdminOrganizationController.java`. INTERVIEWER access is further limited to the
interviewer's own interviews in the service layer (`docs/plan/STATUS.md` M1). The last enabled ADMIN of an
organisation cannot be demoted or disabled; the check takes a pessimistic lock on the organisation's admins
(`…/admin/service/AdminUserService.java`, `UserRepository.lockEnabledByOrganizationAndRole`).

### 4.6 Organisations, tenancy and invitations

- An organisation is a tenant. Staff users belong to exactly one (`users.organization_id`); candidates belong to none
  and can apply to jobs of any organisation (`…/user/service/UserAccountService.java`).
- Tenancy is application-level: every staff query filters by the caller's organisation and a foreign record is 404
  (`…/hr/service/HrJobAccessService.java`, `…/admin/service/AdminUserService.java`). PostgreSQL row-level security
  is not implemented (out of scope, `docs/plan/IMPLEMENTATION_PLAN_V2.md` line 9). Cross-tenant tests exist
  (`backend/src/test/java/com/recruitpro/organization/OrganizationTenancyIntegrationTest.java`, plus "other
  organisation 404" cases in the WP-PLAT and WP-VBE tests).
- First accounts come from: organisation self sign-up (`POST /api/v1/organizations/signup`, creates the org and its
  first ADMIN, can be switched off with `ORG_SELF_SIGNUP_ENABLED=false`), the bootstrap runner (env vars, only when no
  organisation exists), invitations, or the demo seed (`…/organization/service/OrganizationService.java`,
  `…/organization/bootstrap/*`).
- Invitations: an ADMIN invites an e-mail with role ADMIN, HR_MANAGER, RECRUITER or INTERVIEWER
  (`InvitationService.INVITABLE_ROLES`); the link `${HR_PORTAL_URL}/accept-invite?token=…` is valid 7 days; the
  token is stored hashed; `GET /api/v1/auth/invitations/{token}` previews, `POST /api/v1/auth/accept-invite` creates
  the staff account and signs it in (`…/organization/service/InvitationService.java`). Created, revoked and accepted
  invitations are audited.
- Organisation settings that affect privacy and security: `blind_review` (default TRUE; candidate identity hidden from
  staff until shortlist or an audited reveal), `share_verification_status`, `require_staff_mfa`, `sso_allowed_domains`
  (V11, V23; `…/organization/dto/OrganizationUpdateRequest.java`). Verification mode per organisation: SHADOW (default;
  verdicts produced and labelled but never shown to recruiters or used in matching) or VISIBLE
  (`…/verification/entity/VerificationMode.java`).

### 4.7 Staff MFA (TOTP)

Code: `…/auth/mfa/*`. Details in `docs/plan/WP-PLAT-NOTES.md` §1.1, checked against the code.

- RFC 6238: HMAC-SHA1, 6 digits, 30 s step, ±1 step accepted, 20-byte secret, Base32 without padding
  (`Totp.java` constants). Replay guard: a code's step must be later than `user_mfa.last_used_step`.
- The secret is stored encrypted with `SecretCipher`, associated data `mfa:<userId>` (`MfaService.context`).
- Candidates never get MFA: `challengeFor` returns nothing for CANDIDATE, and `/me/mfa*` answers 403.
- **Login two-step**: when a staff user has MFA enabled, login returns
  `{mfaRequired: true, mfaEnrollmentRequired: false, mfaToken, expiresInSeconds: 300}` with no access token and no
  cookie (`AuthController.login`, `MfaService.challengeFor`). The portal then calls `POST /api/v1/auth/mfa/verify`
  with `{mfaToken, code}` or `{mfaToken, recoveryCode}` and receives the normal session and cookie.
- **Required enrolment**: when the organisation sets `require_staff_mfa` and the user has no MFA,
  `mfaEnrollmentRequired: true`; the portal calls `/me/mfa/enroll` and `/me/mfa/confirm` with the `mfaToken`; confirm
  returns 10 recovery codes and the session.
- The `mfaToken` is an HS256 JWT with issuer `recruitpro-mfa` signed with a key derived from `JWT_SECRET` for this
  purpose only, so it can never pass as an access token; its `jti` is a one-time `user_tokens` row (`MFA_LOGIN` or
  `MFA_ENROLL`), valid 5 minutes (`MfaTokenService.TTL`). A wrong code does not consume it; success does.
- The password step does not reset the failure counter for MFA users; only the second step does, so re-entering the
  password cannot reset MFA guessing. Five wrong codes lock the account for 15 minutes.
- **Recovery codes**: 10 codes, 10 characters from `a-z` without i/l/o and `2-9`, shown as `xxxxx-xxxxx`, stored as
  bcrypt hashes, single use; regenerating invalidates the old set (`MfaService.RECOVERY_CODE_COUNT`).
- Disable needs password plus a TOTP or recovery code and is refused while the organisation requires MFA.
- Without `APP_ENCRYPTION_KEY`: enrol/confirm and the sign-in of a user with MFA enabled answer 503
  `MFA_NOT_CONFIGURED`, and an admin cannot turn on `requireStaffMfa`. If an organisation already requires MFA and the
  key is later removed, `challengeFor` logs a warning and does **not** enforce enrolment (it lets the password-only
  sign-in through) (`MfaService.challengeFor`).
- Audit actions: `MFA_ENABLED`, `MFA_DISABLED`, `MFA_RECOVERY_CODES_REGENERATED`, `MFA_RECOVERY_CODE_USED`,
  `MFA_VERIFICATION_FAILED` (`…/audit/service/AuditActions.java`).
- Demo helper: `tools/totp.py` (uncommitted at the time of writing) prints the current code for a Base32 secret.

### 4.8 Staff SSO (OIDC)

Code: `…/auth/sso/*`. Endpoints: `GET /api/v1/auth/sso/providers`, `GET /api/v1/auth/sso/{provider}/start`,
`GET /api/v1/auth/sso/{provider}/callback`.

Flow:

1. The HR portal lists providers (only those with client id and secret, and only when the encryption key is set) and
   navigates the browser to `/start`.
2. `/start` creates `sso_login_states`: state and nonce stored as SHA-256 hashes, PKCE verifier stored encrypted, 10
   minutes TTL, single use; redirects (302) to the provider with `code_challenge_method=S256` and
   `prompt=select_account` (`SsoService.java` lines ~110–130). Expired states older than a day are deleted on the way.
3. The provider redirects to `/callback?code&state`. The state is marked used and committed before the token
   exchange. The code is exchanged with `client_secret_post` plus `code_verifier`.
4. The id_token is validated (`OidcTokenValidator.java`): algorithm RS256 or ES256 only; key chosen by `kid` from the
   provider JWKS (cached 6 h, re-fetched on an unknown `kid` at most every 30 s, `JwksKeyCache.java`); `iss`
   (Microsoft `{tenantid}` filled from `tid`); `aud` contains the client id and `azp` matches when there are several
   audiences; `exp` required; `iat` not in the future (60 s skew) and not older than 600 s; SHA-256 of `nonce` equals
   the stored hash; `sub` present; e-mail present and `email_verified=true` (or Microsoft `xms_edov=true`).
5. Linking (`SsoService` ~line 223–285): an existing `user_identities` (provider, sub) row wins, else the user is found
   by exact verified e-mail. The account must be an enabled, non-deleted **staff** account (candidates are never
   matched), and the organisation's `sso_allowed_domains` must contain the e-mail domain of both the IdP address and
   the account address. **No auto-provisioning**: an unknown e-mail is refused (`no_account`).
6. Success: identity linked (audit `SSO_IDENTITY_LINKED`), `email_verified_at` set if null, lockout reset, audit
   `SSO_LOGIN`, `rp_refresh_staff` cookie set, 302 to `${HR_PORTAL_URL}/sso/complete`, where the portal calls
   `POST /auth/refresh` to get its access token. Refusals redirect to `/login?ssoError=<code>` and are audited as
   `SSO_LOGIN_REFUSED` with the e-mail domain only.
7. MFA is considered satisfied by the IdP (documented risk in `docs/plan/WP-PLAT-NOTES.md` §7).

Providers configured in `application.yml`: `google` (issuer `https://accounts.google.com`), `microsoft` (issuer
`https://login.microsoftonline.com/${SSO_MICROSOFT_TENANT_ID:organizations}/v2.0`) and a generic `oidc`. Endpoints
come from `<issuer>/.well-known/openid-configuration` unless all three URIs are set. Real Google/Microsoft sign-ins
have not been tested; the tests use a JDK `HttpServer` stub IdP (Section 7).

### 4.9 SecretCipher (AES-256-GCM)

`…/common/crypto/SecretCipher.java`: key = `APP_ENCRYPTION_KEY`, Base64 of exactly 32 bytes (else startup fails);
`AES/GCM/NoPadding`, 12-byte random IV, 128-bit tag; output `v1:` + Base64(IV ‖ ciphertext ‖ tag); an optional
context string is bound as associated data so a ciphertext copied to another row or purpose fails to decrypt.
Without a key `isConfigured()` is false and features that need it report "not configured" instead of storing
plaintext. Used for exactly three things (all `encrypt(` call sites): TOTP secrets (`MfaService`, context
`mfa:<userId>`), SSO PKCE verifiers (`SsoService`), GitHub access tokens of verification sources
(`GitHubOAuthService`, decrypted only in `VerificationRunService` when a run calls the agent-service). There is no
key rotation mechanism beyond the `v1:` version prefix. Resume files, work-sample files and evidence snapshots on
disk are **not** encrypted by the application; the storage guide leaves file-system protection to the operator
(`docs/guides/RESUME_STORAGE_DEPLOYMENT.md`).

### 4.10 Security headers, CSP and CORS

Backend (`…/security/headers/ApiSecurityHeadersFilter.java`): every response gets `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`; `/api/v1/**` and `/actuator/**` also get
`Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`. Swagger UI (dev only) gets no CSP. No HSTS
header is set by the backend (TLS is expected to be terminated in front of it).

Portals (`frontend/nginx.conf`, identical `hr-frontend/nginx.conf`), repeated in every `location` because nginx
`add_header` in a location replaces the server list:
`Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'
data: blob:; font-src 'self' data:; connect-src 'self' ${API_ORIGIN}; frame-ancestors 'none'; base-uri 'self';
form-action 'self' ${API_ORIGIN}; object-src 'none'`, plus nosniff, `X-Frame-Options: DENY`,
`Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(),
payment=()`. `index.html` is `no-cache`; `/assets/` is cached for a year (fingerprinted). `'unsafe-inline'` for styles
covers React style attributes; scripts have no inline allowance. QR codes must be rendered locally because the CSP
blocks external image services.

CORS (`…/config/WebConfig.java`): explicit origins from `CORS_ALLOWED_ORIGINS` (`*` is ignored with a warning because
credentials are allowed); methods GET, POST, PUT, PATCH, DELETE, OPTIONS; allowed headers `Authorization`,
`Content-Type`, `Accept`, `X-Requested-With`, `X-RecruitPro-Portal`; exposed headers `Content-Disposition`,
`Retry-After`; credentials allowed; preflight cache 1 h. `X-Request-Id` is not in the exposed list, so browser code
cannot read it cross-origin (it is still in the network tab).

### 4.11 Actuator and Prometheus

`management.endpoints.web.exposure.include: health,info,metrics,prometheus`; health `show-details: never`,
`show-components: never`; mail health disabled (`application.yml`). `/actuator/health` is public, everything else
under `/actuator` needs ADMIN, and `/actuator/prometheus` becomes public only with `METRICS_PUBLIC=true`
(`SecurityConfig`). Because a Prometheus scraper cannot hold a 15-minute ADMIN token, the WP-PLAT notes recommend
`METRICS_PUBLIC=true` with `/actuator` kept off the public network (`docs/plan/WP-PLAT-NOTES.md` §7). Swagger and
`/api-docs` are disabled in prod (`application-prod.yml`).

### 4.12 Threat → control table

| Threat (OWASP Top 10 2021 area) | Control in RecruitPro | Where | Known gap |
|---|---|---|---|
| A01 Broken access control: staff of org A reads org B | Organisation-scoped lookups, foreign records 404; `@PreAuthorize` per endpoint; INTERVIEWER limited to own interviews | `HrJobAccessService`, controllers, `OrganizationTenancyIntegrationTest` | No database row-level security |
| A01: candidate session used on staff portal or vice versa | Portal role gate and separate cookies | `auth/web/Portal`, `PortalSessionIntegrationTest` | |
| A02 Cryptographic failures: password theft | bcrypt; reset/verify/invite/refresh tokens stored as SHA-256 only | `SecurityConfig`, `TokenHashing` | |
| A02: secrets at rest | AES-256-GCM with context binding | `SecretCipher` | Resume and evidence files unencrypted; no key rotation |
| A02: transport | `Secure` cookie in prod | `RefreshTokenCookies`, `application-prod.yml` | TLS/HSTS not provided by the stack |
| A03 Injection: SQL | JPA/JPQL parameters; LIKE wildcards escaped | repositories, `common/util/LikePatterns` | |
| A03: CSV/formula injection in exports | Cells quoted, `= + - @` tab CR LF prefixed with `'` | `common/util/Csv`, `AuditService.cell` | |
| A03: e-mail header injection | Template subject flattened to one line, max 255 chars | `notification/entity/NotificationTemplate` | |
| A03: prompt injection via resumes or fetched pages | Injection detection/handling; LLM optional and fed de-identified text; LLM output must quote sources | `ai-service/app/pipeline/injection.py`, `agent-service/app/injection.py`, `agent-service/app/llm/summary.py` | |
| A04 Insecure design: AI deciding hires | AI reports evidence only; knockouts gate ranking but never auto-reject; humans decide | `ai-service/app/main.py` docstring, `docs/plan/STATUS.md` M14 | |
| A05 Misconfiguration | Startup fails on short JWT secret or malformed encryption key; Swagger off in prod; actuator ADMIN; generic 500 messages; `include-message: never` | `JwtService`, `SecretCipher`, `application-prod.yml`, `GlobalExceptionHandler` | Compose ships dev defaults for all secrets |
| A06 Vulnerable components | Spring Boot 3.5.6; pinned FastAPI/Starlette/python-multipart versions chosen for known CVEs | `backend/pom.xml`, `*/requirements.txt`, `docs/plan/STATUS.md` S3/S4 | No automated dependency scanning (no Dependabot or audit step in `.github/`) |
| A07 Authentication failures: brute force | Per-IP rate limits; 5-failure lockout; MFA codes share the counter | `RateLimitFilter`, `UserAccountService` | In-memory limiter is per instance unless `RATE_LIMIT_STORE=database`; proxy IP issue (4.4) |
| A07: stolen refresh token | Rotation, family reuse detection, httpOnly SameSite=Strict path-scoped cookie | `RefreshTokenService` | No absolute session lifetime |
| A07: stolen access token | 15-minute lifetime, memory-only storage, revoked on password change/disable | `JwtService`, `CustomUserDetailsService` | Not revoked by logout |
| A07: account takeover of staff | Optional/required TOTP, recovery codes, SSO with domain allow-list | `auth/mfa`, `auth/sso` | SSO trusts the IdP for MFA |
| A08 Integrity: uploaded files | Size limits, extension must match magic bytes, ZIPs not extracted, `Content-Disposition: attachment`, `nosniff`, server-set content type | `ResumeService`, `WorkSampleFileService` | No malware scanning |
| A08: evidence tampering | Content hashes on evidence items and files; append-only match runs and verdict history | `evidence_items.content_hash`, `work_sample_submission_files.sha256`, V17 | |
| A09 Logging and monitoring failures | Persistent `audit_events` (actions in 4.x/5.4), request ids, structured logs in prod, Prometheus timers | `AuditService`, `observability/*` | No alerting, no log shipping configured |
| A10 SSRF via candidate URLs | Agent-service SSRF guard with IP pinning, port and scheme limits, redirect re-check, budgets; backend only calls configured URLs | `agent-service/app/fetch/ssrf.py` | On `Kshitij` compose does not isolate the agent's network (on `Kshitij-redesign` it cannot reach postgres or the ai-service) |
| CSRF | No cookie-authenticated state-changing API except `/auth/*`, whose cookie is SameSite=Strict and path-scoped; CORS limited to listed origins | `SecurityConfig`, `RefreshTokenCookies`, `WebConfig` | |
| XSS and clickjacking | React escaping; strict portal CSP without inline scripts; API CSP `default-src 'none'`; `frame-ancestors 'none'`, `X-Frame-Options: DENY` | `nginx.conf`, `ApiSecurityHeadersFilter` | |
| Internal service spoofing | Shared token, constant-time compare, checked before reading the body | `*/app/core/auth.py` | Single static token per service, no mTLS |
| Account enumeration | Forgot-password always succeeds; locked = wrong password (same 401) | `AuthService` | Registration returns 409 for a taken e-mail |

---

## 5. Asynchronous processing

### 5.1 Background jobs (`background_jobs`)

Code: `…/jobs/*`. Schema V14 and V24.

| Job type | `entity_id` refers to | Handler | Enqueued by |
|---|---|---|---|
| `PARSE_RESUME` | `resume_analyses.id` | `…/ai/service/ResumeParseJobHandler.java` | `ResumeIntelligenceService` (upload, retry) |
| `MATCH_APPLICATION` | `applications.id` | `…/evidence/service/MatchApplicationJobHandler.java` | `MatchTriggerService` (apply, analysis completed, claims, requirement edits, scores, scorecards, verdict reviews) |
| `VERIFY_SOURCE` | `verification_runs.id` | `…/verification/service/VerifySourceJobHandler.java` | `VerificationRunService` |
| `VERIFY_REFERENCE` | `verification_reference_requests.id` (after the referee answered) | `…/verification/service/VerifyReferenceJobHandler.java` | `ReferenceService` |

States (`BackgroundJobStatus`): `PENDING` (waiting for `run_after`) → `RUNNING` (claimed) → `DONE`; or `FAILED`
(permanent, no retry: `NonRetryableJobException` or no handler) or `DEAD` (attempts exhausted).

Mechanics (`BackgroundJobService.java`, `BackgroundJobWorker.java`, `BackgroundJobRepository.java`):

- **Enqueue** joins the caller's transaction (outbox). Duplicate rule: `enqueue` returns the existing job when a
  PENDING or RUNNING job of the same type exists for the entity; `enqueueFollowUp` (MATCH_APPLICATION) counts only
  PENDING, so a trigger during a run queues exactly one follow-up and changes are not lost.
- **Dedupe key** (V24): a new PENDING job carries the unique `dedupe_key` `<TYPE>:<entityId>`. The insert runs in a
  JDBC savepoint; if another instance won the race (`DuplicateKeyException`), the savepoint is rolled back so the
  caller's PostgreSQL transaction stays usable, and the winner's job is returned. Claiming clears the key. A job sent
  back to PENDING for a retry has no key and is found by status instead.
- **Claim**: the worker selects up to `batch-size` (5) due ids ordered by `run_after, id`, then for each runs
  `UPDATE … SET status=RUNNING, attempts=attempts+1, locked_at=now, dedupe_key=NULL WHERE id=? AND status=PENDING`;
  only the instance whose update returns 1 runs it. Safe with several backend instances.
- **Poll**: `@Scheduled(fixedDelay = 2000 ms, initialDelay = 5000 ms)`; jobs of a batch run one after another on the
  scheduler thread.
- **Retry with backoff**: on an exception the job goes back to PENDING with `run_after = now + min(15 s × 2^attempts,
  30 min)` (`backoffFor`). Because `attempts` is incremented at claim time, the delays after attempts 1–4 are 30 s,
  60 s, 120 s and 240 s; the fifth failure makes the job DEAD (`max_attempts` 5 from `app.background-jobs.max-attempts`
  and the column default). The 30-minute cap is not reached with the default of 5 attempts.
- **Dead letter**: `handler.onDeadLetter(entityId, lastError)` lets the domain record the failure, e.g. a resume
  analysis marked failed or a verification run failed with `AGENT_SERVICE_UNAVAILABLE`
  (`VerifySourceJobHandler` Javadoc).
- **Stale lock recovery**: each poll first moves RUNNING jobs whose `locked_at` is older than 10 minutes back to
  PENDING (run now) or to DEAD when attempts are exhausted (`releaseStaleJobs`). Handler calls are bounded by the
  HTTP timeouts (30 s AI, 60 s agent), so a healthy job never reaches 10 minutes.
- **Error text** stored in `last_error` is the exception class and message, at most 500 chars; for HTTP errors only the
  status code, never the response body (`BackgroundJobWorker.describe`).
- **Retry classification for verification**: an agent answer with a `detail.code` (e.g. `URL_BLOCKED`,
  `RATE_LIMITED`, `TOKEN_INVALID`) fails the run without retry; connection errors, timeouts and 5xx/408/429 without a
  code are retried (`VerifySourceJobHandler` Javadoc). MATCH_APPLICATION retries on 5xx, 408, 429 and connection
  errors, and fails permanently on any other 4xx or a missing ai-service token (`MatchApplicationJobHandler`).
- **Observability**: each job runs with MDC `requestId = job-<id>-<8 hex>`, forwarded to the Python services, and
  records the timer `recruitpro.job.handle{type, outcome=success|retry|dead|failed}`.
- Retention: finished jobs are never deleted (no purge exists).

### 5.2 Schedulers

All use `@Scheduled(fixedDelay…)` on Spring's scheduler with a pool of 4 threads
(`spring.task.scheduling.pool.size: 4`, `…/config/SchedulingConfig.java`). Each has an enable switch that is off in
the test profile.

| Task | Interval / first run | What it does | Source |
|---|---|---|---|
| Background job worker | 2 s / 5 s | Release stale jobs, claim and run up to 5 | `…/jobs/service/BackgroundJobWorker.java` |
| Notification sender | 5 s / 5 s | Deliver PENDING e-mail rows (5.3) | `…/notification/service/NotificationSender.java` |
| Job deadline closer | 60 s / 30 s | PUBLISHED jobs whose deadline passed → CLOSED, `closed_reason=DEADLINE_PASSED`, audited `JOB_AUTO_CLOSED`; each job in its own transaction with a row lock; ON_HOLD jobs untouched | `…/hr/service/JobDeadlineCloser.java` |
| Work-sample expiry sweep | 60 s / 45 s | STARTED assignments past time limit + 5-minute grace, and ASSIGNED ones past `due_at`, → EXPIRED via conditional update (a submission holding the row lock wins) | `…/assessment/service/WorkSampleExpirySweeper.java` |
| Reference expiry | 15 min / 60 s | SENT reference requests past their 14-day expiry → EXPIRED; GitHub OAuth states older than a day deleted | `…/verification/service/VerificationMaintenance.java` |

With several backend instances, the deadline closer and the sweeps run on every instance; they are written as
conditional or locked updates, so a second instance finds nothing left to change.

### 5.3 Notifications and the outbox

`…/notification/service/NotificationService.java`, `NotificationSender.java`, `entity/NotificationTemplate.java`.

- `notify(recipient, template, params[, attachment])` writes, in the caller's transaction, one EMAIL row (status
  PENDING) and, for registered users and templates marked for the inbox, one IN_APP row.
- Templates (13; `true` = also in the in-app inbox): `VERIFY_EMAIL` (false), `PASSWORD_RESET` (false), `INVITATION`
  (false), `APPLICATION_RECEIVED` (true), `APPLICATION_STATUS_CHANGED` (true), `WORK_SAMPLE_ASSIGNED` (true),
  `WORK_SAMPLE_CANCELLED` (true), `INTERVIEW_SCHEDULED` (true), `INTERVIEW_ASSIGNED` (true), `INTERVIEW_CANCELLED`
  (true), `VERIFICATION_READY` (true), `REFERENCE_REQUEST` (false; sent to a referee without an account),
  `REFERENCE_ANSWERED` (true). Messages carrying one-time links are never copied to the inbox.
- **Sender**: every 5 s it lists up to 20 unclaimed PENDING EMAIL rows and claims each one with a conditional
  `UPDATE … SET claimed_by=<instance>, claimed_until=now+2 min WHERE id=? AND status=PENDING AND (claimed_until IS
  NULL OR claimed_until < now)` in its own transaction; only then it sends. A crashed instance's claim expires after
  2 minutes. Delivery clears the claim.
- With `MAIL_ENABLED=false` rows become SKIPPED and the subject (and, in dev, the links) are logged. With mail on:
  SENT with `sent_at`, or on a mail exception the attempt count rises and the row is retried on the next poll; after 5
  attempts it is FAILED. There is no backoff between e-mail attempts.
- After delivery (or skip) one-time tokens are removed from the stored body (`redact`), so the database does not keep
  usable links.
- **ICS attachments**: interview e-mails carry `invite.ics` (`text/calendar; method=REQUEST`) or `cancel.ics`
  (`method=CANCEL`), stored in `notifications.attachment_*` on the EMAIL row only and sent as MIME multipart.
  `UID: interview-<id>@<mail-from domain>`, `SEQUENCE` = `interviews.ics_sequence` (0, +1 per reschedule that changes
  time or place, +1 on cancel), times in UTC, RFC 5545 escaping, CRLF, 75-octet folding. The interviewer's copy lists
  the candidate only after identity reveal (`…/interview/service/IcsCalendar.java`,
  `…/interview/service/InterviewCalendarService.java`, `docs/plan/WP-PLAT-NOTES.md` §1.3).
- **In-app inbox**: `GET /api/v1/me/notifications`, `/unread-count`, `PATCH /{id}/read`, `POST /read-all`
  (`…/notification/controller/NotificationController.java`), shown in both portals with an unread badge.
- In development all mail goes to Mailpit; open `http://localhost:8025` during the demo.
- WhatsApp or SMS: not implemented (no provider; `NotificationChannel` Javadoc).

### 5.4 Audit trail (`audit_events`)

- `AuditService.record` / `recordFor` write the organisation, actor id and e-mail (or `SYSTEM`), action, target,
  details and client IP in the caller's transaction, so the audit row commits or rolls back with the change
  (`…/audit/service/AuditService.java`). Details are passed through `sanitize`, which masks secret-looking values and
  truncates.
- Actions (`…/audit/service/AuditActions.java`, `…/verification/service/VerificationAuditActions.java`):
  user status/role changes; invitation created/revoked/accepted; organisation created/updated; admin job status
  override; job requirements changed; job auto-closed; application decision; identity revealed; resume downloaded by
  staff; claim verified; work sample scored/cancelled; work-sample file downloaded; scorecard submitted/updated; audit
  exported; candidate account deleted; login locked; password changed/reset; refresh-token reuse detected; MFA
  enabled/disabled/recovery codes regenerated/recovery code used/verification failed; SSO login/identity linked/login
  refused; verification source connected/revoked; ownership proven; verdict shared/reviewed; verification settings
  changed; reference requested.
- Read: `GET /api/v1/admin/audit-logs?page&size&action` (ADMIN, own organisation, newest first, size ≤ 100).
- **CSV exports**: `GET /api/v1/admin/audit-export?from&to` (ADMIN; ISO dates or date-times, default last 90 days;
  the export itself is audited as `AUDIT_EXPORTED`) (`…/admin/controller/AdminController.java`); per-job decision
  audit `GET /api/v1/hr/jobs/{jobId}/audit-export` (ADMIN, HR_MANAGER) with the match run's algorithm and coverages
  (`…/hr/service/JobAuditExportService.java`). Both use `common/util/Csv` (RFC 4180, CRLF, formula neutralisation).
- Account deletion keeps the candidate's audit rows but anonymises the actor e-mail
  (`…/candidate/privacy/CandidateAccountDeletionService.java`).
- Retention: audit rows are never deleted by the application.

---

## 6. Observability and operations

### 6.1 Request ids and logging

- `X-Request-Id` on every response; incoming ids are accepted only if they match the safe pattern
  (`…/observability/RequestIds.java`, `RequestIdFilter.java`); background jobs get `job-<id>-<8 hex>`.
- Dev console pattern: `%d{ISO with offset} [%thread] [%X{requestId}] %-5level %logger - %msg`
  (`application.yml` `logging.pattern.console`); `com.recruitpro` at DEBUG in dev (`application-dev.yml`).
- Prod: `logging.structured.format.console: ecs` (one JSON object per line in Elastic Common Schema, MDC included),
  root WARN, `com.recruitpro` INFO (`application-prod.yml`).
- Logging hygiene in code: the global handler logs exception class names for 4xx and the stack only for 500; job
  errors drop HTTP response bodies; one-time links appear in logs only with `NOTIFICATIONS_LOG_LINKS=true` (dev).

### 6.2 Metrics

`micrometer-registry-prometheus` (`backend/pom.xml`). Custom timers (`…/observability/ObservabilityConfig.java`):
`recruitpro.ai.call` (tags `endpoint`, `outcome` = SUCCESS, CLIENT_ERROR, SERVER_ERROR, IO_ERROR; recorded by
`TimedClientInterceptor`) and `recruitpro.job.handle` (tags `type`, `outcome`). Spring Boot's standard metrics (HTTP
server requests, JVM, HikariCP pool) are exposed by the same registry. No dashboards or alert rules are in the
repository.

### 6.3 Health

| Check | Endpoint | What it checks | Source |
|---|---|---|---|
| Public liveness | `GET /api/v1/health` | Always `{status: "UP"}` if the app answers; used by the Docker `HEALTHCHECK` and the CI smoke test | `…/common/controller/HealthController.java` |
| Spring health | `GET /actuator/health` | Aggregate status (database included by Spring Boot), no details | `application.yml` |
| Admin dashboard | `GET /api/v1/admin/dashboard` (ADMIN) → `health` | `backend` (UP if answering), `database` (`SELECT 1`), `aiService` and `agentService` (`GET /health`, 2 s timeout, cached 15 s; `UNCONFIGURED` when no URL), queue counts `pending`, `failed`, `dead` | `…/admin/service/SystemHealthService.java`, `…/admin/dto/SystemHealthResponse.java` |
| ai-service | `GET :8000/health` | `{status, version 2.0.0, llmProvider, ocrAvailable}` | `ai-service/app/main.py` |
| agent-service | `GET :8100/health` | `{status, version 1.0.0, llmProvider, demoFixtures}` | `agent-service/app/main.py` |

### 6.4 Configuration profiles

| Profile | Database | Notable settings | File |
|---|---|---|---|
| `dev` (default) | PostgreSQL, Hikari pool 10, `ddl-auto: validate` | `JWT_SECRET` required; insecure cookie allowed for http://localhost; invitation links returned; links logged; DEBUG logging | `application-dev.yml` |
| `prod` | PostgreSQL from required env, pool 20, `validate` | Secure cookie, no body tokens, verified e-mail required to apply, mail on, demo seed off, CORS origins required, Swagger off, ECS JSON logs, root WARN | `application-prod.yml` |
| `test` | H2 in PostgreSQL mode, same Flyway migrations, `ddl-auto: none` (H2 reports TEXT as CLOB) | Fixed JWT secret, body tokens on, rate limits/schedulers/worker/mail off, uploads under `target/` | `application-test.yml` |

Flyway runs on every start (`spring.flyway.enabled: true`) and Hibernate only validates; schema changes are made
only by migrations.

### 6.5 CI pipeline

`.github/workflows/ci.yml`, triggered on push to `master`/`main` and on pull requests (a push to `Kshitij` alone does
not run it). `permissions: contents: read`. Jobs:

| Job | What it runs |
|---|---|
| `backend` | Temurin 21, `./mvnw -B verify`; uploads surefire reports on failure |
| `backend-smoke` (needs `backend`) | PostgreSQL 16 service; packages the jar; starts it with the dev profile, demo seed on, worker off; waits up to 180 s for `/api/v1/health`; signs in as `admin@acme.test` and calls `/admin/dashboard` and `/jobs` |
| `ai-service` | Python 3.11, `pytest -q`, `python -m eval.run --check` |
| `agent-service` | Python 3.12, `tools/sync_taxonomy.py --check` (vendored taxonomy equals ai-service's), `pytest -q`, `eval.run --check` |
| `migrations` | `tools/check_migrations_pg.py` (all migrations on a real PostgreSQL via `pgserver`), `tools/check_entity_schema.py` |
| `java-static` | `tools/java_static_check.py backend/src` |
| `frontends` (matrix `frontend`, `hr-frontend`) | Node 22, pnpm 10, `pnpm install --frozen-lockfile`, `pnpm build` (`tsc --noEmit && vite build`) |

The repository contains no record of a CI run; whether it is green on GitHub is not visible from the code.

### 6.6 Verification tooling in `tools/`

| Tool | Purpose |
|---|---|
| `tools/java_static_check.py` | tree-sitter based checker of the Java sources (imports, types, member calls) for environments without a JDK |
| `tools/check_entity_schema.py` | Compares every JPA entity's mapped columns with the schema produced by the migrations |
| `tools/check_migrations_pg.py` | Applies V1…V24 to an embedded PostgreSQL |
| `tools/sync_taxonomy.py` | Copies or checks the skills taxonomy vendored from ai-service into agent-service |
| `tools/totp.py` | Demo helper printing a TOTP code (uncommitted at the time of writing) |

Also: `audit/probe_matching.py` (matching probes from the audit), `ai-service/eval/` and `agent-service/eval/`
(synthetic evaluation harnesses), `test-data/scripts/`.

---

## 7. Testing

### 7.1 Backend (JUnit 5, Spring Boot test, H2 in PostgreSQL mode)

87 Java files under `backend/src/test/java/com/recruitpro/`, 335 `@Test`/`@ParameterizedTest` methods. Integration
tests start the Spring context on H2 with the real Flyway migrations (`application-test.yml`), use
`support/TestDatabase` to clean tables, and mock the ai-service and agent-service clients where needed.

| Package | Test classes | Test methods | What they cover |
|---|---|---|---|
| `admin` | `AdminJobOverrideTransitionTest`, `AdminPortalIntegrationTest` | 13 | Admin job transition table, admin endpoints |
| `ai` | `AiLifecycleIntegrationTest` (opt-in, needs a running ai-service and `RECRUITPRO_AI_IT=true`), `ResumeParseJobHandlerTest` | 11 | End-to-end parse with the real service; handler retry/dead-letter |
| `application` | 6 classes (`ApplicationTransitionsTest`, `CandidateApplicationLifecycleIntegrationTest`, `ApplyRequiresVerifiedEmailIntegrationTest`, …) | 22 | Status machine, candidate lifecycle, verified-email rule, review controller security |
| `assessment` | `WorkSampleIntegrationTest`, `WorkSampleRulesTest`, `AuthoringGenerationIntegrationTest`, `WorkSampleFileServiceTest`, `WorkSampleFilesIntegrationTest` | 20 | Work samples, generation, file validation, cancellation |
| `auth` | `AuthIntegrationTest`, `AccountFlowsIntegrationTest`, `LoginLockoutIntegrationTest`, `RateLimitIntegrationTest`, `RefreshTokenRotationIntegrationTest`, `PortalSessionIntegrationTest`, `PortalTest`, `mfa/TotpTest`, `mfa/MfaIntegrationTest`, `mfa/MfaNotConfiguredIntegrationTest`, `sso/SsoIntegrationTest` | 60 | Sign-in, verification, reset, lockout, rate limits, rotation and reuse, per-portal cookies, TOTP RFC vectors, MFA flows, SSO with a stub IdP |
| `candidate` | 6 classes | 19 | Profile, resume storage, resume-intelligence security, privacy export/deletion |
| `common` | 7 classes | 35 | Envelope, exception mapping, UTC JSON, CSV, health |
| `evidence` | 4 classes | 13 | Claims, match request building, match job handler |
| `fairness` | 3 classes | 7 | Calculator, reports, date filter |
| `hr` | 9 classes | 45 | Job lifecycle and management, requirements v2, applicant review and authorisation, ranking, audit export, requirement compiler |
| `interview` | `InterviewIntegrationTest`, `InterviewRulesTest`, `IcsCalendarTest`, `InterviewPlatformIntegrationTest` | 17 | Scheduling, scorecards, ICS format, scorecard edits |
| `jobs` | `BackgroundJobWorkerIntegrationTest`, `BackgroundJobDedupeIntegrationTest` | 14 | Queue, backoff, dead letter, stale locks, dedupe key |
| `notification` | `NotificationClaimIntegrationTest` | 3 | Multi-instance claims, attachments |
| `observability` | `RequestIdFilterTest` | 3 | Id reuse/replacement, MDC, outgoing header |
| `organization` | `OrganizationTenancyIntegrationTest` | 4 | Cross-tenant isolation |
| `security` | `SlidingWindowRateLimiterTest`, `headers/SecurityHeadersIntegrationTest`, `ratelimit/DatabaseRateLimiterStoreIntegrationTest` | 10 | Limiters, headers and CSP on 200/401/403, Prometheus protection |
| `verification` | 12 classes (`VerificationSourceIntegrationTest`, `VerifySourceJobHandlerIntegrationTest`, `ReferenceIntegrationTest`, `StaffVerificationIntegrationTest`, `AdminVerificationIntegrationTest`, `VerificationPrivacyIntegrationTest`, `VerificationMetricsTest`, `VerificationRulesTest`, …) | 38 | Claim Ledger end to end with a mocked agent |

Of the 335 methods, 239 existed at commit `9ad341a` and 96 were added in `2fedafb` (the 14 WP-PLAT test classes and the
12 WP-VBE verification classes listed by `git diff --name-status 9ad341a 2fedafb -- backend/src/test`). The "6
skipped" of the last real run match the 6 tests of the opt-in `AiLifecycleIntegrationTest` (`README.md` line 8).

### 7.2 Python services

| Suite | Location | Count | Covers |
|---|---|---|---|
| ai-service pytest | `ai-service/tests/` (`test_api`, `test_parsing`, `test_pipeline`, `test_matching`, `test_taxonomy`, `test_authoring`, `test_llm`, `test_audit_probes`, `test_eval`) | 121 test functions, 178 collected cases (parametrised) | Token middleware, parsing (sections, dates, timelines, two-column, hidden text, injection), matching tiers and alternatives, audit probes, LLM fallback |
| ai-service evaluation | `ai-service/eval/` (`python -m eval.run --check`) | 416 synthetic resumes (208 base) | Thresholds: skill F1 ≥ 0.85, date accuracy ≥ 0.9, requirement kappa ≥ 0.7, NDCG@10 ≥ 0.8, name-swap flip rate ≤ 0, adverse-impact ratio ≥ 0.8 |
| agent-service pytest | `agent-service/tests/` (`test_auth`, `test_ssrf`, `test_code`, `test_web`, `test_document`, `test_badge`, `test_reference`, `test_ownership`, `test_verdicts`, `test_evidence`, `test_taxonomy`, `test_demo`, `test_eval`) | 98 test functions, 161 collected cases | No network: SSRF guard, each verifier, ownership checks, verdict rules |
| agent-service evaluation | `agent-service/eval/` | 79 cases (26 adversarial), 110 claims | Thresholds: false corroboration rate ≤ 0.02, kappa ≥ 0.70 (`agent-service/eval/run.py` `THRESHOLDS`) |

Both evaluation sets are synthetic. They show the rules behave as designed on generated data; they are not accuracy
figures on real resumes or real candidates (`docs/plan/STATUS.md` §4.2: "Replace the synthetic set before quoting
accuracy").

### 7.3 Frontends

No unit or UI test runner is committed for either portal (no `*.test.*` files; `package.json` has only `dev` and
`build`). The gate is `tsc --noEmit && vite build`. Earlier jsdom smoke harnesses lived outside the repository
(`docs/plan/STATUS.md` §4.3).

### 7.4 Verified vs not yet run

| Item | Status | Evidence |
|---|---|---|
| Backend compile + `./mvnw verify` on a real JDK 21 | **Ran once**, 15 Nov 2026, teammate's Windows machine: 239 tests, 0 failures, 0 errors, 6 skipped. That run predates the legacy `.doc` queueing change, which was then committed in `9ad341a`; STATUS and README ask for one more run | `docs/plan/STATUS.md` (update note and §5), `README.md` line 8 |
| Docker Compose stack up | Started once with all services (same teammate run, before `2fedafb`) | `docs/plan/STATUS.md` update note |
| Backend code added in `2fedafb` (MFA, SSO, multi-instance queue/outbox/rate limiter, observability, headers, verification/Claim Ledger, work-sample files, scorecard edits, ICS) | **Never compiled or executed.** Static checks and one independent review only | `docs/plan/WP-PLAT-NOTES.md` header and §6, `docs/plan/WP-VBE-NOTES.md` line 4 and line 219 |
| The 96 backend tests added in `2fedafb` | **Not run** | same |
| Java static check | 575 files, 0 errors (re-run 16 Nov 2026 while writing this chapter) | `tools/java_static_check.py` |
| Entity ↔ schema | 474 columns of 41 entities vs 24 migrations, 0 errors (re-run 16 Nov 2026) | `tools/check_entity_schema.py` |
| Migrations V1–V24 on PostgreSQL | Applied successfully (re-run 16 Nov 2026 with `pgserver`) | `tools/check_migrations_pg.py` |
| ai-service pytest | 178 passed (re-run 16 Nov 2026, Python 3.10 in the sandbox; CI and Docker use 3.11) | |
| ai-service evaluation | CHECK PASSED (re-run 16 Nov 2026): skill F1 0.9977, date accuracy 0.9923, kappa 0.993, NDCG@10 0.9897, flip rate 0.0, AIR 1.0 | |
| agent-service pytest | 161 passed (re-run 16 Nov 2026, Python 3.10; Docker uses 3.12) | `docs/plan/WP-AG-NOTES.md` also reports 161 |
| agent-service evaluation | exit 0, false corroboration 0.0, kappa 1.0 on the synthetic set (re-run 16 Nov 2026) | |
| agent-service Docker build | Not run | `docs/plan/WP-AG-NOTES.md` (line 286) |
| Portal builds | Green on 16 Nov 2026 (candidate 370.6 kB JS, HR 449.5 kB JS); not run against a live backend or in a browser | `docs/plan/WP-FE-FINISH-NOTES.md` |
| Real identity providers (Google, Entra), real SMTP rendering of ICS in Gmail/Outlook, nginx envsubst at container start, a Prometheus scrape, browser acceptance of the SSO cookie, the savepoint behaviour of the dedupe insert on PostgreSQL under a true two-instance race | Not tested | `docs/plan/WP-PLAT-NOTES.md` §6 |
| CI on GitHub | No evidence in the repository | — |

Practical consequence for the demo: features from `2fedafb` should be shown only after one successful
`./mvnw verify` and a `docker compose up --build` on a JDK 21 machine.

---

## 8. Likely demo questions

**Architecture**

1. *Why a monolith plus two Python services?* The business rules, security and data live in one Spring Boot
   application (simple to deploy, one transaction boundary). Parsing/matching and web verification are isolated in
   Python because of the libraries they need and, for the agent-service, because it fetches untrusted URLs and should
   run with different network rights (`RecruitProApplication.java` Javadoc, `docs/plan/IMPLEMENTATION_PLAN_V2.md` §2).
2. *How do the services authenticate each other?* A shared secret in `X-Internal-Service-Token`, checked with a
   constant-time compare before the body is read; a missing token gives 503, a wrong one 401
   (`ai-service/app/core/auth.py`, `agent-service/app/core/auth.py`). There is no mTLS.
3. *Do the Python services store data?* No. The backend stores every result; the services are stateless.
4. *What happens if the ai-service is down?* Parsing and matching are background jobs, so uploads and applications
   still succeed; jobs retry after 30, 60, 120, 240 s and then go DEAD; the admin dashboard shows `aiService: DOWN` and
   queue counts; a resume analysis can be retried (`BackgroundJobService`, `SystemHealthService`,
   `PATCH /candidates/me/resumes/{id}/analysis/retry`). Synchronous authoring calls (requirement compiler, kit and
   work-sample generation) fail for that request.
5. *Why a database queue instead of Kafka/RabbitMQ?* One less moving part; jobs are enqueued in the same transaction
   as the change (outbox), so no job is lost or created for a rolled-back change. The trade-off is polling (every 2 s)
   and modest throughput.
6. *How is time handled across time zones?* Everything is UTC: JVM, Hibernate, auditing, JSON with a trailing `Z`;
   incoming offsets are converted (`common/time/*`).

**Scalability**

7. *Can you run two backend instances?* The job queue (conditional claim + dedupe key), the e-mail outbox (row
   claims with expiry) and the rate limiter (`RATE_LIMIT_STORE=database`) are written for it. Schedulers run on every
   instance but use conditional/locked updates. Not tested with two real instances (Section 7.4). Files are on a
   local volume, so instances would need shared storage.
8. *What limits throughput?* Each instance runs at most one batch of 5 jobs at a time, sequentially, on a 4-thread
   scheduler pool; AI calls take up to 30 s and agent calls up to 60 s. Add instances to scale the worker.
9. *Database growth?* No retention jobs exist for `refresh_tokens`, `user_tokens`, `background_jobs`,
   `notifications` or `audit_events`; this would be needed for long-running deployments.
10. *Why is pgvector in the image?* It is the PostgreSQL 16 image used; semantic search with pgvector was deferred and
    no table uses it.

**Security**

11. *Where are tokens stored in the browser?* The access token in memory only; the refresh token in an httpOnly,
    SameSite=Strict cookie limited to `/api/v1/auth`, separate per portal (`client.ts`, `RefreshTokenCookies`).
12. *What if a refresh token is stolen?* The first time either party uses it after rotation, the other copy becomes
    "reused"; after the 30 s grace window that revokes the whole family and is audited (`RefreshTokenService`).
13. *Does logout invalidate the access token?* No, it revokes the refresh family; the access token lives at most 15
    minutes. Password change and account disable do invalidate access tokens immediately.
14. *How do you stop password guessing?* 10 logins per minute per IP, lockout after 5 failures for 15 minutes, MFA
    codes share the counter, bcrypt hashing.
15. *How does MFA work?* Staff only, TOTP (RFC 6238, SHA-1, 6 digits, 30 s, ±1 step, replay guard), secret encrypted
    with AES-256-GCM, 10 single-use bcrypt-hashed recovery codes, and an organisation can require it at next sign-in.
16. *Can a candidate log into the HR portal?* No: 403 at login and refresh, before a session is created (`Portal`).
17. *How is SSO secured?* Authorisation code + PKCE S256, single-use hashed state and nonce, strict id_token checks,
    domain allow-list on both e-mails, no auto-provisioning, candidates never matched (`auth/sso/*`).
18. *How do you prevent SSRF when verifying a candidate's website?* Agent-service SSRF guard: only http/https on
    80/443, every DNS answer must be public, connection pinned to the checked IP, re-checked on redirects, request
    budget and 50 s deadline (`agent-service/app/fetch/ssrf.py`, `core/budget.py`).
19. *What does the CSP allow?* Portals: scripts only from their own origin, API calls only to the backend origin, no
    framing; API responses: `default-src 'none'` (`nginx.conf`, `ApiSecurityHeadersFilter`).
20. *Are uploads safe?* Size limits, extension must match the file's magic bytes, ZIPs never extracted, downloads as
    attachments with `nosniff`; no antivirus scanning.

**Data privacy**

21. *Does the AI see the candidate's identity?* Contact facts (e-mail, phone, links) are never sent to matching
    (`MatchRequestBuilder` Javadoc); any LLM receives only de-identified text (`ai-service/app/llm/provider.py`). The
    raw resume file is sent to the internal ai-service for parsing.
22. *Do recruiters see names?* Not by default: `blind_review` is on, identity is revealed on shortlist or by an
    audited manager reveal; the interviewer's calendar invitation lists the candidate only after reveal
    (`…/interview/service/InterviewCalendarService.java`).
23. *Can a candidate delete their data?* Yes, with password confirmation: files, facts, claims, survey, notifications,
    tokens and all verification data are deleted; the account and texts are anonymised; applications, decisions,
    match runs, scores and audit rows are kept for audit (`CandidateAccountDeletionService` Javadoc). A JSON export is
    available (`GET /candidates/me/export`).
24. *What about sensitive attributes?* Date of birth and gender were removed from the profile (V20). An optional,
    consented equal-opportunity survey is stored separately and reported only in suppressed aggregates.
25. *Is data encrypted at rest?* Small secrets (TOTP, PKCE, GitHub tokens) yes, with AES-256-GCM. Passwords and
    one-time tokens are hashed. Database and files rely on disk/volume encryption, which the stack does not set up.

**Tenancy**

26. *How are organisations isolated?* Every staff query is scoped to `users.organization_id`; another organisation's
    jobs, applicants and users answer 404. There is no row-level security in PostgreSQL; isolation depends on the
    service layer and is covered by tenancy tests.
27. *Can one candidate apply to two companies?* Yes. Candidates have no organisation. Verification reviews and
    corroborations are stored per organisation, so one company's CONFIRM does not raise the candidate's evidence tier
    for another company (`claim_verdict_reviews.organization_id`, `docs/plan/WP-VBE-NOTES.md` tests).
28. *How does a new company get its first admin?* Self sign-up (can be disabled), bootstrap environment variables, or
    invitation by an existing admin.

**Failure handling**

29. *What if an e-mail cannot be sent?* The row stays PENDING and is retried every 5 s up to 5 attempts, then FAILED;
    the in-app copy is unaffected; nothing is lost on restart because the outbox is in the database.
30. *What if the backend crashes mid-job?* The job stays RUNNING; after 10 minutes the next poll returns it to
    PENDING (or DEAD if attempts are used up). Handlers must therefore tolerate a repeated run.
31. *What if two HR users change the same application at once?* Row locks serialise them; a lock failure becomes
    409 "modified by another request" (`GlobalExceptionHandler`).
32. *What does a client see on an unexpected error?* A 500 with a generic message and the `X-Request-Id`; the server
    log line carries the same id for lookup.
33. *How do you know it works?* Section 7.4: older backend code passed 239 tests on JDK 21; the newest backend code has
    passed static, schema and migration checks only; Python suites and evaluations pass today.

---

## 9. Inconsistencies found between documents and code

| Document | Statement | Code / reality |
|---|---|---|
| `docs/plan/STATUS.md` §4.2 | SSO and MFA "Deferred" | Implemented in `2fedafb` (`auth/mfa`, `auth/sso`, V23); STATUS was not updated for `2fedafb` |
| `docs/plan/STATUS.md` §4.3 | "Single-instance assumptions: in-memory rate limiter, notification sender without row claims"; work-sample uploads, HR cancellation, scorecard editing, fairness by date range "not built" | All added in `2fedafb` (V24, `DatabaseRateLimiterStore`, `NotificationSender` claims, `WorkSampleFileService`, scorecard revisions, fairness `from`/`to`) |
| `README.md`, `docs/guides/RUN_AND_TEST_GUIDE.md`, `docs/architecture/SYSTEM_ARCHITECTURE.md` | No mention of the agent-service, MFA or SSO (0 occurrences of "agent") | `agent-service/`, compose service on 8100, `auth/mfa`, `auth/sso` exist |
| `docs/architecture/SYSTEM_ARCHITECTURE.md` line 451 | Resume files "are served only to their owning candidate" | Staff (ADMIN/HR_MANAGER/RECRUITER) can download the resume of an applicant, audited as `RESUME_DOWNLOADED_BY_STAFF` (`ApplicationReviewController` `GET …/resume`) |
| `docs/database/ENTITY_RELATIONSHIP.md` | Assessment, Question, Offer, Onboarding entities; resume parsing "future scope" | None of those tables exist; parsing is implemented. Use Section 3 |
| `docs/guides/RELEASE_READINESS.md` | MySQL database, partial test runs ("125 main Java sources") | The project runs on PostgreSQL 16 (`application-dev.yml`, `docker-compose.yml`); this file predates that move and should not be quoted |
| `docs/plan/IMPLEMENTATION_PLAN_V2.md` §2 | ai-service has "no egress" | Enforced on `Kshitij-redesign` (internal network); not on `Kshitij` (compose defines no networks) |
| `docker-compose.yml` | `pgvector/pgvector:pg16` image | pgvector is out of scope and unused |
| `backend/.env.example` | "Per-IP rate limits on /api/v1/auth/** and organisation sign-up" | Also on `DELETE /api/v1/candidates/me` (`RateLimitFilter`) |
| `BackgroundJobService` Javadoc | Backoff `min(15 s × 2^attempts, 30 min)` | Correct formula, but the first retry is 30 s, not 15 s, because attempts are counted at claim; the 30-minute cap is unreachable with 5 attempts |
| `MfaService.challengeFor` | Organisation "requires" MFA | Not enforced (warning only) if `APP_ENCRYPTION_KEY` is missing at sign-in time |
