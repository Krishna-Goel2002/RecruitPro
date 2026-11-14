# RecruitPro

Evidence-based hiring for small and mid-sized teams. Organisations publish jobs with structured requirements; candidates apply with their resume; an offline AI service turns resumes into cited evidence; reviewers rank applicants on what is **verified** (resume, recruiter-corroborated claims, scored work samples, structured interviews) rather than on what is merely claimed. Review is blind until shortlisting. AI never makes a hiring decision: every status change is a human action and is audited.

- Plan, decisions and API contracts: [docs/plan/IMPLEMENTATION_PLAN.md](docs/plan/IMPLEMENTATION_PLAN.md)
- What was fixed, what is new, what remains, and the verification evidence: [docs/plan/STATUS.md](docs/plan/STATUS.md)

> **Important: the Java backend has not been compiled yet.** The code in this repository was written in an environment without a JDK or Maven Central. It passed a static check (syntax, imports, types), an entity/schema check and a line-by-line review, but **no `javac` run and no backend test run has happened.** Before merging or deploying, run on a machine with JDK 21 (or let CI do it):
>
> ```bash
> cd backend && ./mvnw verify        # Windows: mvnw.cmd verify
> ```
>
> Fix whatever it reports before relying on the backend. Everything else (AI service, migrations, both portals) was built and tested here.

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
| AI service | http://localhost:8000/health |
| Mailpit (every outgoing email) | http://localhost:8025 (SMTP on 1025) |
| PostgreSQL 16 (pgvector image) | localhost:5432, database / user / password `recruitpro` |

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

Seeding is idempotent and never runs in the `prod` profile. The seed does not create work samples, interview kits or interviews; generate them from a job's tabs in the HR portal.

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
| Blind review | — | "Candidate #A-…" until shortlisting (auto-reveal, audited) or a manager reveals; resume and cover letter after reveal |
| Work samples | start a timed task, submit a response and link; expiry handled | generate (AI) or edit, activate, assign (start-by time), score with the anchored rubric |
| Interviews | see scheduled interviews | interview kits (AI or manual), schedule, reschedule, cancel, structured scorecards |
| Decisions | application timeline, requirement verification status, withdraw | shortlist, interview, select, reject, undo and reopen with comments; decision history with the match run used |
| Fairness and privacy | voluntary equal-opportunity survey, data export, account deletion | adverse-impact report per job and per organisation (groups under 5 suppressed), job audit CSV |
| Account | notifications inbox with unread badge, change password, email verification, password reset | notifications inbox with unread badge, change password; admin: staff, invitations, organisation settings, job oversight, audit log with CSV export, live health and queue |

## Configuration (environment variables)

Every variable has a development default; the full lists with comments are in [`.env.example`](.env.example) (Docker Compose), [`backend/.env.example`](backend/.env.example) and [`ai-service/.env.example`](ai-service/.env.example).

| Variable | Used by | Default (dev) | Purpose |
|---|---|---|---|
| `JWT_SECRET` | backend | none outside compose (**required**, ≥ 32 bytes) | access-token signing key |
| `AI_SERVICE_TOKEN` | backend + AI service | compose dev value (**required**) | shared secret, header `X-Internal-Service-Token` |
| `AI_SERVICE_URL` | backend | `http://localhost:8000` | AI service base URL |
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

The refresh cookies are `SameSite=Strict`, so each portal and the API must be on the same site (`localhost:5173` → `localhost:8080` works; in production use e.g. `jobs.example.com`, `hr.example.com` and `api.example.com`).

## Manual setup (without Docker)

Requirements: JDK 21, Node.js 22 with pnpm 10, Python 3.10 or 3.11, PostgreSQL 16 (Mailpit optional).

1. **Database**
   ```bash
   createuser --pwprompt recruitpro      # password: recruitpro
   createdb --owner recruitpro recruitpro
   ```
   Flyway creates the schema (V1–V20) on first start.
2. **AI service**
   ```bash
   cd ai-service
   python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
   pip install -r requirements.txt
   export AI_SERVICE_TOKEN=<same-secret-as-backend>
   uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```
3. **Backend** (see `backend/.env.example` for every variable)
   ```bash
   cd backend
   export JWT_SECRET=<at-least-32-random-bytes> AI_SERVICE_TOKEN=<same-secret> APP_SEED_DEMO=true
   ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
   ```
   With `MAIL_ENABLED=false` (default outside Docker) emails are not sent; verification, reset and invitation links are written to the backend log.
4. **Portals**
   ```bash
   cd frontend && pnpm install && pnpm dev       # http://localhost:5173
   cd hr-frontend && pnpm install && pnpm dev    # http://localhost:5174
   ```

## Verification gates

Run all of these before merging; CI (`.github/workflows/ci.yml`) runs the same set plus a smoke test that starts the backend jar on PostgreSQL 16 with the demo seed and signs in.

| Gate | Command | Status in the build environment |
|---|---|---|
| **G-JAVA-REAL** backend compile + unit and integration tests (H2 in PostgreSQL mode) | `cd backend && ./mvnw verify` | **not run: no JDK available. Must be run.** |
| G-PY AI service tests | `cd ai-service && python -m pytest -q` | passed |
| G-PY evaluation thresholds (synthetic data) | `cd ai-service && python -m eval.run --check` | passed |
| G-SQL migrations on a real PostgreSQL 16 | `pip install pgserver psycopg2-binary && python tools/check_migrations_pg.py` | passed |
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
        +------------------+-------------------+        +-----------------------------+
                           | JPA / Flyway
                           v
                  PostgreSQL 16 (5432)        Mailpit (SMTP 1025, UI 8025) in development
```

- **Organisations (multi-tenant).** Staff users (ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER) belong to one organisation and only see its data; another organisation's ids behave as 404. Candidates are global and can apply to any organisation's published jobs.
- **Authentication.** 15-minute JWT access tokens kept in memory; refresh tokens rotate on every use inside a token family (reuse detection, 30-second grace for concurrent tabs) in a per-portal httpOnly `SameSite=Strict` cookie. Lockout after five failed sign-ins, per-IP rate limits, email verification, password reset and change.
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
frontend/       Candidate portal (Vite + React)
hr-frontend/    HR, interviewer and administration portal (Vite + React)
docs/           Architecture, database, guides, the implementation plan and STATUS.md
tools/          Migration, entity/schema and Java static checks used locally and in CI
audit/          Probe script for the matching defects found in the audit
```

## License

Developed as an academic major project.
