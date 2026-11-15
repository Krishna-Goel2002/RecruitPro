# RecruitPro

Evidence-based hiring for small and mid-sized teams. Organisations publish jobs with structured requirements; candidates apply with their resume; an offline AI service turns resumes into cited evidence; reviewers rank applicants on what is **verified** (resume, recruiter-corroborated claims, scored work samples, structured interviews, and claims checked by verification agents against GitHub/GitLab, websites, documents, Open Badges and references) rather than on what is merely claimed. Review is blind until shortlisting. AI never makes a hiring decision: every status change is a human action and is audited.

- **Run and test everything, step by step:** [docs/guides/RUN_AND_TEST_GUIDE.md](docs/guides/RUN_AND_TEST_GUIDE.md)
- **Handbook** (architecture, hiring core and AI, claim verification, portals and flows): [docs/handbook/](docs/handbook/)
- Status, verification evidence and known limitations: [docs/plan/STATUS.md](docs/plan/STATUS.md)
- Plans and API contracts: [IMPLEMENTATION_PLAN.md](docs/plan/IMPLEMENTATION_PLAN.md) (core), [IMPLEMENTATION_PLAN_V2.md](docs/plan/IMPLEMENTATION_PLAN_V2.md) (verification agents, MFA, SSO, platform hardening)

> **Verification status (16 Nov 2026).** The core platform (commit `9ad341a`) passed `./mvnw verify` on JDK 21 (239 tests, 0 failures, 6 skipped) and started under Docker Compose on 15 Nov. The plan v2 work (commit `2fedafb`: Claim Ledger, agent-service, MFA, SSO, platform hardening) and the fixes made since **have not been compiled yet**: the Java static check, entity/schema check, migrations V1–V25 on PostgreSQL 16, the agent-service and AI-service tests and evaluations, and both portal builds pass, but `./mvnw verify` must be run on a JDK 21 machine first (guide section 4.1). Details: [STATUS.md](docs/plan/STATUS.md).

## Quick start (Docker Compose)

Requirements: Docker with Compose v2.

```bash
docker compose up --build
```

| Service | URL / port |
|---|---|
| Candidate portal | http://localhost:5173 |
| HR, interviewer and admin portal | http://localhost:5174 |
| Backend API | http://localhost:8080/api/v1 (Swagger UI: http://localhost:8080/swagger-ui.html, not in `prod`) |
| AI service | not published (internal network, no egress): `docker compose exec ai-service python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/health').read().decode())"` |
| Agent service (verification agents) | http://localhost:8100/health (127.0.0.1 only) |
| Mailpit (every outgoing email) | http://localhost:8025 (SMTP on 1025; 127.0.0.1 only) |
| PostgreSQL 16 (pgvector image) | not published: `docker compose exec postgres psql -U recruitpro -d recruitpro` |

Networks: PostgreSQL, Mailpit, the AI service and the backend share an `internal` network without internet access; the agent service reaches the backend over its own internal network and the internet over a separate one; only the backend has general egress (SMTP, GitHub OAuth, OIDC). An LLM provider for the AI service needs `docker compose -f docker-compose.yml -f docker-compose.ai-egress.yml up -d`. To reach PostgreSQL from the host (e.g. a backend started with `mvnw`), add a `docker-compose.override.yml` that gives `postgres` `ports: ["127.0.0.1:5432:5432"]` and `networks: [internal, egress]`. Details: [WP-OPS-NOTES.md](docs/plan/WP-OPS-NOTES.md).

The compose file uses development secrets. To change them, copy `.env.example` to `.env` next to `docker-compose.yml` and edit it. Data lives in the `postgres-data` and `resume-uploads` volumes; `docker compose down -v` removes them.

### Demo accounts

With `APP_SEED_DEMO=true` (the compose default) the backend creates the organisation **Acme Labs**, two published jobs with requirements, and these accounts. The password for all of them is `ChangeMe-123!` (`APP_SEED_PASSWORD`).

| Email | Role | Sign in at |
|---|---|---|
| admin@acme.test | ADMIN (organisation administrator) | HR portal, :5174 |
| hr@acme.test | HR_MANAGER | HR portal, :5174 |
| recruiter@acme.test | RECRUITER | HR portal, :5174 |
| interviewer@acme.test | INTERVIEWER | HR portal, :5174 |
| candidate@demo.test | CANDIDATE | Candidate portal, :5173 |

Seeding is idempotent and never runs in the `prod` profile. It also creates, for "Backend Engineer (Java)", an interview kit, a work sample, Casey's application in INTERVIEWING with a scored work sample, a completed interview with a scorecard and a scheduled interview; and for verification, Acme Labs in VISIBLE mode with Casey's GitHub source `demo-asha` and shared verdicts (synthetic data; with `AGENT_DEMO_FIXTURES=true` the agent service answers `demo-*` identifiers offline). See guide section 5.

**Separate sessions per portal.** Both portals can be signed in at the same time in one browser. Each sends `X-RecruitPro-Portal: candidate|staff` and gets its own refresh cookie (`rp_refresh_candidate` / `rp_refresh_staff`). A staff account cannot sign in on the candidate portal and a candidate cannot sign in on the HR portal (403 with an explanation).

### First account on a fresh installation

Pick one:

1. **Self-service sign-up**: the HR portal's sign-up page (`POST /api/v1/organizations/signup`) creates an organisation and its first administrator. Controlled by `ORG_SELF_SIGNUP_ENABLED` (default `true`).
2. **Bootstrap**: set `BOOTSTRAP_ORG_NAME`, `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`. When no organisation exists yet, the backend creates it at start-up (any profile).
3. **Invitations**: an administrator invites staff from the HR portal; the emailed link (valid 7 days) opens `/accept-invite` on the HR portal.

Candidates register themselves in the candidate portal; public registration always creates a candidate.

## What the product does

| Area | Candidate portal | HR portal |
|---|---|---|
| Jobs | search and filter open jobs, apply with a resume and cover letter | create, edit (also after publishing), publish, hold, close; draft requirements from the description with wording warnings |
| Evidence | resume analysis with facts and parser flags; corrections become *unverified* claims; add claims with evidence links | ranked applicants (verified vs claimed coverage, must-haves gate), evidence table with tiers and verification hints, verify or reject claims |
| Verification (Claim Ledger) | "Strengthen your application": connect GitHub (OAuth or username + bio code), GitLab, a website (DNS TXT, well-known file, page code), documents, Open Badges; ask referees (public referee page); preview every verdict and Share / Hide / Re-check; revoke a source | applicant Verification tab (blind-first: evidence only after reveal), Confirm / Overrule per organisation (a confirmed VERIFIED verdict lifts the claim to *corroborated* in matching); admin: SHADOW/VISIBLE mode, connectors, shadow labelling queue and agreement metrics |
| Blind review | — | "Candidate #A-…" until shortlisting (auto-reveal, audited) or a manager reveals; resume and cover letter after reveal |
| Work samples | start a timed task, submit a response, a link and up to 3 files; expiry and cancellation handled | generate (AI) or edit, activate, assign (start-by time), download submitted files (audited), cancel with a reason, score with the anchored rubric |
| Interviews | see scheduled interviews; calendar invitations (ICS) by email | interview kits (AI or manual), schedule, reschedule, cancel (ICS update/cancel), structured scorecards, scorecard editing by its author with revisions |
| Decisions | application timeline, requirement verification status, withdraw | shortlist, interview, select, reject, undo and reopen with comments; decision history with the match run used |
| Fairness and privacy | voluntary equal-opportunity survey, data export (incl. verification data), account deletion | adverse-impact report per job and per organisation with a date range (groups under 5 suppressed), job audit CSV |
| Account | notifications inbox with unread badge, change password, email verification, password reset | notifications inbox with unread badge, change password, email verification, **two-step verification (TOTP + recovery codes)**, **single sign-on** (Google, Microsoft Entra, generic OIDC); admin: staff, invitations, organisation settings (require MFA for all staff, SSO allowed domains), job oversight, audit log with CSV export, live health (AI and agent service) and queue |

## Configuration (environment variables)

Every variable has a development default; the full lists with comments are in [`.env.example`](.env.example) (Docker Compose), [`backend/.env.example`](backend/.env.example) and [`ai-service/.env.example`](ai-service/.env.example).

| Variable | Used by | Default (dev) | Purpose |
|---|---|---|---|
| `JWT_SECRET` | backend | none outside compose (**required**, ≥ 32 bytes) | access-token signing key |
| `AI_SERVICE_TOKEN` | backend + AI service | compose dev value (**required**) | shared secret, header `X-Internal-Service-Token` |
| `AI_SERVICE_URL` | backend | `http://localhost:8000` | AI service base URL |
| `AGENT_SERVICE_TOKEN`, `AGENT_SERVICE_URL` | backend + agent service | compose dev value, `http://localhost:8100` | verification agent service (header `X-Internal-Service-Token`) |
| `AGENT_DEMO_FIXTURES`, `AGENT_LLM_PROVIDER`, `AGENT_GITHUB_TOKEN` | agent service | `true` (local only), `none`, empty | offline `demo-*` fixtures (**must be `false` outside demos**); optional summary LLM; GitHub API token for real runs |
| `APP_ENCRYPTION_KEY` | backend | compose dev key; empty elsewhere | 32 bytes Base64 (AES-256-GCM). Needed for MFA, SSO and GitHub OAuth; without it they are unavailable |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`, `GITHUB_OAUTH_REDIRECT_URI` | backend | empty, empty, `http://localhost:8080/api/v1/verification/oauth/github/callback` | "Connect with GitHub" (OAuth + PKCE, browser-bound state); empty = username + bio code only |
| `SSO_REDIRECT_BASE_URL`, `SSO_GOOGLE_*`, `SSO_MICROSOFT_*`, `SSO_OIDC_*` | backend | `http://localhost:8080`, empty | staff single sign-on providers (shown only when configured) |
| `RATE_LIMIT_STORE`, `METRICS_PUBLIC` | backend | `memory`, `false` | `database` for several backend instances; `/actuator/prometheus` without a token |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | backend | `jdbc:postgresql://localhost:5432/recruitpro`, `recruitpro`, `recruitpro` | PostgreSQL 16 (required in `prod`) |
| `SPRING_PROFILES_ACTIVE` | backend | `dev` | `dev`, `prod` or `test` |
| `CORS_ALLOWED_ORIGINS` | backend | `http://localhost:5173,http://localhost:5174` | portal origins (credentials allowed; no `*`) |
| `CANDIDATE_PORTAL_URL`, `HR_PORTAL_URL` | backend | `http://localhost:5173`, `:5174` | links in emails |
| `AUTH_COOKIE_SECURE` | backend | `false` in dev, `true` otherwise | `Secure` flag of the refresh cookies |
| `AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY` | backend | `false` (prod `true`) | candidates verify their email before applying |
| `RATE_LIMIT_ENABLED`, `ORG_SELF_SIGNUP_ENABLED` | backend | `true`, `true` | per-IP limits on auth routes; organisation sign-up |
| `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, … | backend | `false` (compose `true` with Mailpit) | SMTP delivery; when off, links are logged |
| `APP_SEED_DEMO`, `APP_SEED_PASSWORD` | backend | compose `true`, `ChangeMe-123!` | demo organisation and accounts |
| `BOOTSTRAP_ORG_NAME`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | backend | empty | first organisation and admin |
| `BACKGROUND_JOBS_ENABLED`, `JOB_DEADLINE_CLOSER_ENABLED`, `WORK_SAMPLE_EXPIRY_SWEEP_ENABLED`, `NOTIFICATIONS_SENDER_ENABLED` | backend | `true` | queue worker and scheduled tasks |
| `FILE_STORAGE_PATH` | backend | `./uploads` (compose `/data/uploads`) | resume files |
| `LLM_PROVIDER`, `LLM_MODEL`, `ANTHROPIC_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_API_KEY` | AI service | `none` | optional LLM enhancement (only de-identified text is sent) |
| `PARSE_WORKERS`, `PARSE_TIMEOUT_SECONDS`, `MAX_UPLOAD_BYTES`, `OCR_ENABLED` | AI service | `2`, `60`, 10 MiB, `false` | parsing limits; OCR needs `requirements-ocr.txt` |
| `VITE_API_BASE_URL` | both portals (build time) | `http://localhost:8080/api/v1` | API base URL compiled into the bundle |

The refresh cookies are `SameSite=Strict` (the GitHub OAuth binding cookie is `SameSite=Lax`), so each portal and the API must be on the same site (`localhost:5173` → `localhost:8080` works; in production use e.g. `jobs.example.com`, `hr.example.com` and `api.example.com`).

## Manual setup (without Docker)

Requirements: JDK 21, Node.js 22 with pnpm 10, Python 3.10–3.12, PostgreSQL 16 (Mailpit optional).

1. **Database**
   ```bash
   createuser --pwprompt recruitpro      # password: recruitpro
   createdb --owner recruitpro recruitpro
   ```
   Flyway creates the schema (V1–V25) on first start.
2. **AI service**
   ```bash
   cd ai-service
   python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
   pip install -r requirements.txt
   export AI_SERVICE_TOKEN=<same-secret-as-backend>
   uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```
3. **Agent service** (verification agents)
   ```bash
   cd agent-service
   python -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   export AGENT_SERVICE_TOKEN=<same-secret-as-backend> AGENT_DEMO_FIXTURES=true
   uvicorn app.main:app --host 127.0.0.1 --port 8100
   ```
4. **Backend** (see `backend/.env.example` for every variable)
   ```bash
   cd backend
   export JWT_SECRET=<at-least-32-random-bytes> AI_SERVICE_TOKEN=<same-secret> AGENT_SERVICE_TOKEN=<same-secret> APP_ENCRYPTION_KEY=<32-bytes-base64> APP_SEED_DEMO=true
   ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
   ```
   With `MAIL_ENABLED=false` (default outside Docker) emails are not sent; verification, reset and invitation links are written to the backend log.
5. **Portals**
   ```bash
   cd frontend && pnpm install && pnpm dev       # http://localhost:5173
   cd hr-frontend && pnpm install && pnpm dev    # http://localhost:5174
   ```

## Verification gates

Run all of these before merging; CI (`.github/workflows/ci.yml`) runs the same set plus a smoke test that starts the backend jar on PostgreSQL 16 with the demo seed and signs in.

| Gate | Command | Status in the build environment |
|---|---|---|
| **G-JAVA-REAL** backend compile + unit and integration tests (H2 in PostgreSQL mode) | `cd backend && ./mvnw verify` | passed for `9ad341a` on 15 Nov 2026 (239 tests, 6 skipped); **not yet run for `2fedafb` and later** |
| G-PY AI service tests | `cd ai-service && python -m pytest -q` | passed (178; 186 with WP-OPS on `redesign`) |
| G-PY evaluation thresholds (synthetic data) | `cd ai-service && python -m eval.run --check` | passed |
| G-AG agent service tests | `cd agent-service && python -m pytest -q` | passed (176) |
| G-AG evaluation (false corroboration ≤ 0.02, kappa ≥ 0.70; synthetic) | `cd agent-service && python -m eval.run --check` | passed (83 cases, FCR 0.0, kappa 1.0) |
| G-SQL migrations on a real PostgreSQL 16 | `pip install pgserver psycopg2-binary && python tools/check_migrations_pg.py` | passed (V1–V25) |
| Entity ↔ schema agreement | `pip install pgserver psycopg2-binary tree-sitter tree-sitter-java && python tools/check_entity_schema.py` | passed |
| G-JAVA-STATIC syntax, imports, types | `pip install tree-sitter tree-sitter-java && python tools/java_static_check.py backend/src` | passed (0 errors) |
| G-FE candidate portal | `cd frontend && pnpm install --frozen-lockfile && pnpm build` | passed |
| G-FE HR portal | `cd hr-frontend && pnpm install --frozen-lockfile && pnpm build` | passed |

`AiLifecycleIntegrationTest` needs a running AI service and only runs with `RECRUITPRO_AI_IT=true`. The portals need pnpm 10 (`pnpm-workspace.yaml` uses pnpm 10 settings; `npx pnpm@10 …` works without a global install). Use `python -B` when running the Python tools inside the repository to avoid leaving `__pycache__` folders.

## Architecture

```
 Candidate portal (5173)          HR / interviewer / admin portal (5174)      React 18 + TypeScript, nginx
   X-RecruitPro-Portal: candidate     X-RecruitPro-Portal: staff
            \                              /
             \  REST + JSON; access token in memory; refresh token in a per-portal httpOnly cookie
              v                            v
        +--------------------------------------+        +-----------------------------+
        | Spring Boot 3.5 backend (8080)       |  REST  | FastAPI AI service (8000)   |
        | modular monolith, Java 21            | -----> | parsing, matching v3,       |
        | background job queue, mail outbox    |        | requirement compiler,       |
        | evidence ladder, blind review, audit |        | work-sample/kit generators  |
        | Claim Ledger, MFA, SSO               |        +-----------------------------+
        |                                      |  REST  +-----------------------------+
        |                                      | -----> | FastAPI agent service (8100)|
        +------------------+-------------------+        | code, web, document, badge, |
                           |                            | reference verifiers (egress,|
                           |                            | SSRF-guarded, stateless)    |
                           |                            +-----------------------------+
                           | JPA / Flyway
                           v
                  PostgreSQL 16 (5432)        Mailpit (SMTP 1025, UI 8025) in development
```

- **Organisations (multi-tenant).** Staff users (ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER) belong to one organisation and only see its data; another organisation's ids behave as 404. Candidates are global and can apply to any organisation's published jobs.
- **Authentication.** 15-minute JWT access tokens kept in memory; refresh tokens rotate on every use inside a token family (reuse detection, 30-second grace for concurrent tabs) in a per-portal httpOnly `SameSite=Strict` cookie. Lockout after five failed sign-ins, per-IP rate limits, email verification, password reset and change. Staff can enrol TOTP two-step verification (an organisation can require it) and sign in with OIDC single sign-on (PKCE, nonce, JWKS-validated id_token; linked only to existing staff accounts whose domain the organisation allows).
- **Claim verification.** The agent service reads public sources the candidate connected and proposes verdicts with cited, hashed evidence; the backend stores them append-only. Ownership must be proven before a verdict can be VERIFIED; the candidate previews and shares each verdict; only a recruiter's confirmation lifts a claim to *corroborated*, for that organisation only. Organisations start in SHADOW mode (verdicts are labelled by admins, not shown to recruiters) until they switch to VISIBLE.
- **Evidence ladder.** Self-claimed < resume-stated < corroborated < demonstrated (work sample) < interview-verified. Ranking uses verified coverage; unverified must-haves put an applicant in a separate group, never an automatic rejection.
- **Asynchronous AI.** Resume parsing and matching run through a database-backed queue with retries, exponential backoff and a dead-letter state; GET requests never call the AI service. Every match run is kept, and every decision records the run it was made on.
- **Notifications.** An outbox delivers email through SMTP and keeps an in-app inbox for both portals.
- **Audit.** Security, administrative and hiring events are stored in `audit_events`; organisation and per-job CSV exports.
- **Time.** Everything is stored and returned in UTC (`2026-11-08T10:15:30Z`); the portals show local time.

More detail: [docs/architecture/SYSTEM_ARCHITECTURE.md](docs/architecture/SYSTEM_ARCHITECTURE.md), work-package notes in `docs/plan/WP-*-NOTES.md`.

## Repository layout

```
backend/        Spring Boot API (Flyway migrations in src/main/resources/db/migration)
ai-service/     FastAPI resume intelligence service and evaluation harness (eval/)
agent-service/  FastAPI verification agents (code, web, document, badge, reference) and evaluation harness (eval/)
frontend/       Candidate portal (Vite + React)
hr-frontend/    HR, interviewer and administration portal (Vite + React)
docs/           Handbook, guides (run and test), architecture, database, the implementation plans and STATUS.md
tools/          Migration, entity/schema and Java static checks used locally and in CI
audit/          Probe script for the matching defects found in the audit
```

## License

Developed as an academic major project.
