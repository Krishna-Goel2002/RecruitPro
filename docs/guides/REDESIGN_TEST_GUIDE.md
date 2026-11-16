# RecruitPro `Kshitij-redesign`: run and test guide

**Branch:** `Kshitij-redesign`. **Commit:** `2f6e6fe` ("Docs: bug hunt summary commit reference"). **Written:** 16 Nov 2026.
**For:** Windows 11, PowerShell, Docker Desktop, JDK 21, Node, Python and Git. bash alternatives are given where they differ.

This guide is the reference for this branch. It covers building, starting the stack, every flow in both redesigned
portals, every verification path and every automated check. Button, tab and field names are quoted exactly from the
code (`frontend/src`, `hr-frontend/src`), and the demo accounts and fixtures come from the seeders and
`agent-service/app/demo`.

The older [RUN_AND_TEST_GUIDE.md](RUN_AND_TEST_GUIDE.md) covers both branches and uses some pre-redesign labels.
Where the two disagree, this guide is right for this branch.

**How to use it:**
- Every step has an ID, such as **7.12-4** (section 7.12, step 4), and an **Expected** result.
- When something differs, note the ID and continue if you can.
- Section 11 lists what to send back.

---

## Contents

0. [What this branch contains, and honest status](#0-what-this-branch-contains-and-honest-status)
1. [Prerequisites](#1-prerequisites)
2. [Get the code, push it, read CI](#2-get-the-code-push-it-read-ci)
3. [The `.env` file](#3-the-env-file)
4. [Build and automated checks before starting the stack](#4-build-and-automated-checks-before-starting-the-stack)
5. [Start the stack, check health, reset](#5-start-the-stack-check-health-reset)
6. [Accounts, seeded data, demo fixtures, test files](#6-accounts-seeded-data-demo-fixtures-test-files)
7. [Test scripts, in run order (both portals)](#7-test-scripts-in-run-order-both-portals)
8. [Negative and security tests by hand](#8-negative-and-security-tests-by-hand)
9. [Optional real integrations](#9-optional-real-integrations)
10. [Troubleshooting](#10-troubleshooting)
11. [What to send back](#11-what-to-send-back)
12. [Known blockers and traps found while writing this guide](#12-known-blockers-and-traps-found-while-writing-this-guide)

---

## 0. What this branch contains, and honest status

### 0.1 `Kshitij-redesign` compared with `Kshitij`

`git log --oneline Kshitij..Kshitij-redesign` lists 8 commits:

| Commit | Content |
|---|---|
| `57bfff9` | Verification trust fixes (employer-domain and badge-issuer trust, migration **V26**), data-retention purge, AI-service image with headless LibreOffice and RapidOCR, compose **network isolation** (AI service and PostgreSQL have no host ports), requirement **proficiency** in matching, naming and UX review docs |
| `8f7ab8f`, `c130637` | Two portal fixes (blank Resume/Strengthen pages; evidence table layout) |
| `87bc31d` | Design system in both portals: tokens, light/dark, brand presets, 43 components, AppShell, the `/__design` style guide |
| `5219f3f` | **Redesign of both portals**. Candidate: Overview checklist, apply dialog, "Add a source" drawer, verdict drawer. HR: job wizard, keyboard ranking with Undo, decision bar, MFA QR code, admin verification tabs |
| `2fb0555` | Name-options preview page (`docs/design/name-options.html`, a static file, not an app route) |
| `2f705ac` | **Full bug hunt**: about 260 fixes across backend, ai-service, agent-service, both portals (new Vitest suites), infra/CI and docs. Also migrations **V27** and **V28** |
| `2f6e6fe` | Docs only |

**Behaviour changes from the bug hunt that you will see** (details in
[BUG_HUNT_SUMMARY.md](../audit/BUG_HUNT_SUMMARY.md)):

- **Claim verification is per organisation.** One organisation's "Verify claim" or "Not verified" no longer changes
  what another organisation sees. The candidate sees a summary: any corroboration wins over a rejection.
- **An OTHER requirement never acts as a gate (knockout).** In the HR wizard the **Gate** box is disabled for type
  *Other*.
- **Limits answer differently:**
  - Business caps answer **409** with a code: `REFERENCE_LIMIT` (5 open reference requests) and `SOURCE_LIMIT`
    (30 sources).
  - Rate limits answer **429** with a `Retry-After` header:
    - sign-in: 10 per minute per IP
    - verification runs: 10 per 24 h per candidate
    - reference requests: 10 per 24 h
    - ownership checks: 20 per hour
    - GitHub OAuth starts: 20 per hour
    - MFA lockout
- **MFA lockout** answers 429 `MFA_LOCKED` with `Retry-After`. The HR login shows "Too many wrong codes…".
- **New recovery codes need a step-up.** The HR Security page asks for an authenticator code.
- **Sessions have an absolute lifetime of 30 days** (`JWT_REFRESH_ABSOLUTE_LIFETIME_DAYS`). After that you sign in
  again, however active you were.
- **Requirement order is stored.** Reordering alone does not re-match anyone.
- **Candidates can delete a resume.** A resume that an application uses is kept: the server answers 409 and the
  portal explains why.
- **HR accept-invite handles MFA** (enrolment or a code) when the organisation requires it.
- **Every 429 carries `Retry-After`.** The audit CSV warns when it was cut off.

### 0.2 Honest status (what has really been run)

| Item | Status |
|---|---|
| **Backend Java** (everything after `9ad341a`, incl. the bug hunt) | **Never compiled.** The authoring sandbox has no JDK 21 and no Maven Central. A Python static check found 0 errors in 611 files and two "act as javac" read-throughs found nothing, but **your `.\mvnw.cmd verify` (4.1) or CI is the first real compile**. Expect possibly a few small fixes. |
| Docker images | **Never built by the author** (no Docker in the sandbox). CI's `docker-stack` job builds them; your 5.1 is the first local build. |
| Python services | Run here for this guide: ai-service **269 passed** + eval **CHECK PASSED**; agent-service **273 passed** + eval exit 0 (99 cases, 43 adversarial, false-corroboration 0.0, kappa 1.0). |
| Portals | Run here on clean copies: candidate **113 tests / 14 files** pass + build OK; HR **132 tests / 20 files** pass + build OK. |
| Repository tools | Run here: migrations V1–V28 apply on PostgreSQL 16; entity/schema 0 errors; Java static 0 errors; design sync in sync; taxonomy in sync. |
| Evaluations | **Synthetic data**, with labels written by the same author as the rules. Kappa 1.0 says nothing about real-world accuracy. Shadow-mode labelling (7.35) exists to measure that. |
| Flows in section 7 | **Not clicked through against a live stack yet.** They were traced through the code. The demo-fixture verdicts in 6.3 were produced by running the agent service in-process with the exact inputs below. |
| Open items | Product and ops decisions in BUG_HUNT_SUMMARY §4. Notification links are matched by title (two applications with the same job title open the first one). Some HR "new since last visit" and Undo state is per browser. |

---

## 1. Prerequisites

| Tool | Version | Check (new PowerShell window) | Expected |
|---|---|---|---|
| Git | any recent | `git --version` | `git version 2.4x…` |
| Docker Desktop | recent, Compose v2, WSL 2 backend | `docker version` and `docker compose version` | Client **and** Server sections; `Docker Compose version v2.x` |
| JDK | **21** (Temurin recommended) | `java -version` | `openjdk version "21.…"` |
| Node.js | **22 LTS** (the Docker build uses `node:22-alpine`; CI uses 22) | `node -v` | `v22.x.x` |
| pnpm | **10** | `pnpm -v` | `10.x.x` |
| Python | **3.12** (3.10–3.12 work; CI uses 3.11 for ai-service and 3.12 for agent-service) | `python --version` (or `py -3.12 --version`) | `Python 3.12.x` |
| curl | the one that ships with Windows 11 | `curl.exe --version` | `curl 8.x` |
| A browser | Chrome or Edge | — | — |

**Install pnpm 10.** `corepack enable` needs an admin shell on Windows, so use either of these:

```powershell
npm install -g pnpm@10      # then: pnpm -v
# or, without installing: prefix every pnpm command with  npx pnpm@10
```

**Docker Desktop resources.**
- The AI-service image now contains LibreOffice Writer, RapidOCR, onnxruntime and opencv, so it is the largest image.
- The first build also compiles Java and two Node apps.
- Give Docker **at least 8 GB of memory (10–12 GB is comfortable)** and **about 20 GB of free disk** for images and
  build cache.
- With the WSL 2 backend, memory is set in `%UserProfile%\.wslconfig`. Docker Desktop's Resources page says so when
  WSL 2 is active.

```powershell
notepad "$env:USERPROFILE\.wslconfig"
```

```ini
[wsl2]
memory=10GB
```

Then run `wsl --shutdown` and restart Docker Desktop.

**Ports the stack publishes:**
- 5173, 5174 and 8080 on all interfaces;
- 8100, 8025 and 1025 on 127.0.0.1 only;
- the AI service (8000) and PostgreSQL (5432) are not published.

Check that none of the published ports is in use:

```powershell
netstat -ano | findstr ":5173 :5174 :8080 :8100 :8025 :1025"
```

**Expected:** no output.

---

## 2. Get the code, push it, read CI

### 2.1 Check out the branch

```powershell
cd C:\Users\KIIT0001\Desktop\RecruitPro
git fetch origin
git checkout Kshitij-redesign
git status                 # "On branch Kshitij-redesign" ... "up to date with 'origin/Kshitij-redesign'"
git log --oneline -1       # 2f6e6fe Docs: bug hunt summary commit reference
```

**Fresh machine** (use a path without spaces):

```powershell
git clone https://github.com/Krishna-Goel2002/RecruitPro.git C:\dev\RecruitPro
cd C:\dev\RecruitPro
git checkout Kshitij-redesign
```

**Line endings: do this once.** `.gitattributes` asks for CRLF in `backend/mvnw.cmd` and LF in `backend/mvnw`, but a
checkout made before those rules existed keeps the old endings. In this working tree **`mvnw.cmd` currently has LF
endings** (see 12, item 2).

```powershell
git ls-files --eol backend/mvnw backend/mvnw.cmd
```

**Expected:**
- `w/lf` for `mvnw`;
- `w/crlf` for `mvnw.cmd`.

If `mvnw.cmd` shows `w/lf`, recreate it from Git. This changes nothing in Git, only the file on disk:

```powershell
Remove-Item backend\mvnw.cmd
git checkout -- backend/mvnw.cmd
git ls-files --eol backend/mvnw.cmd   # now w/crlf
```

### 2.2 Push so that CI runs

`.github/workflows/ci.yml` runs on every push to `Kshitij-redesign` (and `Kshitij`, `master`, `main`), on pull
requests, and by hand.

```powershell
git push origin Kshitij-redesign
```

`2f6e6fe` is already on `origin`, so a run for it may already exist. To run CI again without a new commit:
1. GitHub → **Actions** → **CI**.
2. **Run workflow**.
3. Choose branch `Kshitij-redesign` → **Run workflow**.

**The 10 jobs and what success means:**

| Job name on GitHub | What it does | Green means |
|---|---|---|
| Backend (mvn verify, JDK 21) | `./mvnw -B verify` | Java compiles; all tests pass (H2, no Docker) |
| Backend smoke test on PostgreSQL 16 (dev profile) | packages the jar, starts it on PostgreSQL 16 with the demo seed, signs in as `admin@acme.test`, calls `/admin/dashboard` and `/jobs` | Flyway V1–V28, Hibernate validate and seeding work on real PostgreSQL |
| AI service (pytest + evaluation thresholds) | pytest + `eval.run --check` on Python 3.11 | — |
| Agent service (pytest + evaluation thresholds + taxonomy sync) | taxonomy check + pytest + eval on Python 3.12 | — |
| Flyway migrations and entity mappings on PostgreSQL 16 | `check_migrations_pg.py`, `check_entity_schema.py` | — |
| Java static check | `java_static_check.py backend/src` | — |
| Design sync, taxonomy sync, compose files | `check_design_sync.py`, `sync_taxonomy.py --check`, `docker compose config --quiet` for both compose files | — |
| Portal (frontend) install, test, build | `pnpm install --frozen-lockfile`, `pnpm run --if-present test`, `pnpm build` | — |
| Portal (hr-frontend) install, test, build | same | — |
| Docker images build and the compose stack starts healthy | `docker compose up --build --detach --wait`, health of 8080/8100, the AI service's `/health` via `exec`, **AI service has no internet**, `soffice` present, the portals' CSP, backend can write to the uploads volume | the whole stack starts |

**Reading a run:**
1. Open the run; the left column lists the jobs.
2. Click a red job.
3. Expand the red step.
4. Use the gear icon → **View raw logs**.

When the backend job fails, the run page has an artifact **surefire-reports** at the bottom; download it. The
**Docker** job prints `docker compose logs` on failure.

---

## 3. The `.env` file

**For local testing you do not need a `.env` at all.** Every variable has a development default in
`docker-compose.yml`. Those defaults include:
- the JWT secret and both service tokens;
- a **dev-only** `APP_ENCRYPTION_KEY`, so MFA, SSO and GitHub OAuth work;
- Mailpit, `APP_SEED_DEMO=true`, `LLM_PROVIDER=none` and `AGENT_DEMO_FIXTURES=true`.

> **Note:** copying `.env.example` to `.env` unchanged is safe: its secrets are empty, so the dev-only defaults in
> `docker-compose.yml` apply (fixed after this guide was first written; see 12, item 1). Set real values for anything
> other than local development.

Create one only to override something:

```powershell
Copy-Item .env.example .env          # bash: cp .env.example .env
notepad .env                         # then fix JWT_SECRET, AI_SERVICE_TOKEN, AGENT_SERVICE_TOKEN (or delete those lines)
```

| Variable | Required? | Default from `docker-compose.yml` | Notes |
|---|---|---|---|
| `JWT_SECRET` | no | `dev-only-jwt-secret-…` | ≥ 32 bytes |
| `AI_SERVICE_TOKEN` | no | `dev-only-ai-service-token-…` | compose passes the same value to the backend and the AI service |
| `AGENT_SERVICE_TOKEN` | no | `dev-only-agent-service-token-…` | ≥ 16 characters, not starting with `replace-with` or `changeme` |
| `APP_ENCRYPTION_KEY` | no | dev-only Base64 key | empty = dev default. Otherwise Base64 of **exactly 32 bytes**, or the backend stops. Changing it later makes existing MFA enrolments unreadable (then `docker compose down -v`) |
| `APP_SEED_DEMO` / `APP_SEED_PASSWORD` | no | `true` / `ChangeMe-123!` | demo organisation "Acme Labs" and accounts |
| `AGENT_DEMO_FIXTURES` | no | `true` | answers `demo-…` identifiers offline. **Never `true` outside local testing** |
| `LLM_PROVIDER`, `AGENT_LLM_PROVIDER`, `LLM_MODEL`, keys | no | `none` | see 9.3 |
| `OCR_ENABLED` | no | `false` | OCR for scanned resumes (installed in the image) |
| `GITHUB_CLIENT_ID/SECRET`, `GITHUB_OAUTH_REDIRECT_URI` | no | empty, empty, `http://localhost:8080/api/v1/verification/oauth/github/callback` | see 9.1 |
| `SSO_*` | no | empty | see 9.2 |
| `BOOTSTRAP_ORG_NAME/ADMIN_EMAIL/ADMIN_PASSWORD` | no | empty | first organisation without the demo seed |
| `AGENT_GITHUB_TOKEN` | no | empty | GitHub API rate limits for real (non-demo) accounts |

**Generate secrets in PowerShell** (works in 5.1 and 7):

```powershell
# APP_ENCRYPTION_KEY: 32 random bytes, Base64
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
# JWT_SECRET, AI_SERVICE_TOKEN, AGENT_SERVICE_TOKEN: 48 random bytes
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

bash: `openssl rand -base64 32` and `openssl rand -base64 48`.

After changing `.env`, apply it with `docker compose up -d`, which recreates the changed containers. `docker compose
restart` does **not** re-read `.env`.

---

## 4. Build and automated checks before starting the stack

Run these from the repository root unless a step says otherwise. Python and Node leave nothing tracked in Git:
`.venv`, `node_modules`, `.pnpm-store`, `dist`, `__pycache__` and `.pytest_cache` are git-ignored.

### 4.1 Backend: `mvnw verify` (most important; the first real compile)

```powershell
cd backend
.\mvnw.cmd -B verify *> ..\backend-build.log
cd ..
Select-String -Path backend-build.log -Pattern "Tests run:.*Fail.*Skipped: \d+$","BUILD SUCCESS","BUILD FAILURE" | Select-Object -Last 4
```

```bash
cd backend && ./mvnw -B verify > ../backend-build.log 2>&1; cd ..
grep -E "Tests run:.*Skipped: [0-9]+$|BUILD SUCCESS|BUILD FAILURE" backend-build.log | tail -4
```

- The first run downloads Maven 3.9.16 and its dependencies (5–15 minutes). The tests use H2 in PostgreSQL mode, so
  Docker is not needed.
- `backend/mvnw` is stored as executable (`100755`), so `chmod` is no longer needed.

**Expected:**
- `Tests run: 441, Failures: 0, Errors: 0, Skipped: 6` (there are 441 `@Test` methods; the 6 skipped are the opt-in
  `AiLifecycleIntegrationTest`, which runs only with `RECRUITPRO_AI_IT=true`);
- then `BUILD SUCCESS`.

A count slightly different from 441 is fine as long as Failures and Errors are 0.

**If it fails:**

```powershell
Select-String -Path backend-build.log -Pattern "\[ERROR\]" | Select-Object -First 30
```

1. Send those lines and the whole `backend-build.log` (section 11).
2. For test failures also send `backend\target\surefire-reports\*.txt` of the failing classes:
   `Get-ChildItem backend\target\surefire-reports\*.txt | Select-String "FAILURE" -List`.
3. `docker compose up --build` compiles the same code, without tests, inside Docker. A **compile** error therefore
   also stops 5.1, but **test** failures do not.

**Fallback runner:** if Maven reports temp-folder or path problems, run `.\Run-Maven.ps1 verify` in `backend/`. It
sets `java.io.tmpdir` to a short path.

### 4.2 ai-service tests and evaluation

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider
.\.venv\Scripts\python.exe -B -m eval.run --check
cd ..
```

bash: `python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -B -m pytest -q && .venv/bin/python -B -m eval.run --check`

Calling `.venv\Scripts\python.exe` directly avoids `Activate.ps1` and execution-policy prompts.

**Expected pytest result:**

| Machine | Result |
|---|---|
| Linux with LibreOffice (the author's run) | `269 passed` |
| Without LibreOffice | `267 passed, 2 skipped` |
| Windows | `266 passed, 3 skipped` (one more test is POSIX-only) |

**Expected eval result** (about 30 s) — a table, then `CHECK PASSED`:

```
skill_f1                     0.9979   threshold >= 0.85
date_accuracy                0.9923   threshold >= 0.9
requirement_kappa            0.993   threshold >= 0.7
ndcg_at_10                   0.9897   threshold >= 0.8
name_swap_flip_rate          0.0   threshold <= 0.0
adverse_impact_ratio         1.0   threshold >= 0.8
proficiency_case_accuracy    1.0   threshold >= 1.0
CHECK PASSED
```

Optional: run the matching probes, `.\ai-service\.venv\Scripts\python.exe -B audit\probe_matching.py`.

### 4.3 agent-service tests and evaluation

```powershell
cd agent-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -B ..\tools\sync_taxonomy.py --check; "exit=$LASTEXITCODE"
.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider
.\.venv\Scripts\python.exe -B -m eval.run --check; "exit=$LASTEXITCODE"
cd ..
```

**Expected:**
- `sync_taxonomy.py --check` prints nothing, then `exit=0`.
- pytest: `273 passed`. In a copy without `ai-service` next to it, one taxonomy test is skipped (`272 passed, 1 skipped`).
- eval: a JSON report starting `"cases": 99, "adversarial_cases": 43, "claims": 135, "false_corroboration_rate": 0.0,
  … "kappa": 1.0`, every verifier with `"agreement": 1.0`, then `mismatches: 0, errors: 0` and `exit=0`.

### 4.4 Repository tools

Use the agent-service venv; it has everything except the extra packages installed below:

```powershell
$py = ".\agent-service\.venv\Scripts\python.exe"
& $py -m pip install pgserver psycopg2-binary tree-sitter tree-sitter-java
& $py -B tools\check_migrations_pg.py
& $py -B tools\check_entity_schema.py
& $py -B tools\java_static_check.py backend\src
& $py -B tools\check_design_sync.py
& $py -B tools\sync_taxonomy.py --check; "exit=$LASTEXITCODE"
& $py -B tools\totp.py JBSWY3DPEHPK3PXP
```

**Expected, line by line:**
- `OK   V1__…` through `OK   V28__integration_fixes.sql`, then `Applied 28 migrations to PostgreSQL successfully.`
  `pgserver` brings its own PostgreSQL, with a Windows wheel for Python 3.12, so Docker is not needed.
- `Checked 490 mapped columns of 42 entities against 28 migrations: 0 error(s), 0 warning(s).` A `RuntimeDirWarning`
  line before it is harmless.
- `Checked 611 Java files: 0 error(s).`
- `design folders in sync`
- `exit=0`
- a 6-digit code and `(valid for about N more seconds)`.

### 4.5 Portals: unit tests and production builds

Run in each portal folder. On Windows the installs inside the repository are fine: `node_modules` is git-ignored.

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
cd ..\hr-frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
cd ..
```

(With `npx`: replace `pnpm` by `npx pnpm@10`.) bash is identical.

**Expected:**

| Portal | `pnpm test` | `pnpm build` |
|---|---|---|
| Candidate (`frontend`) | `Test Files 14 passed (14)`, `Tests 113 passed (113)` | ends with `✓ built in …`, no TypeScript errors |
| HR (`hr-frontend`) | `Test Files 20 passed (20)`, `Tests 132 passed (132)` | ends with `✓ built in …`; prints a harmless **"Some chunks are larger than 500 kB"** warning (main chunk ≈ 544 kB, 165 kB gzip) |

Optional cleanup afterwards: `Remove-Item -Recurse -Force frontend\node_modules, frontend\dist, hr-frontend\node_modules, hr-frontend\dist`.

### 4.6 Compose files (needs Docker running)

```powershell
docker compose -f docker-compose.yml config --quiet; "exit=$LASTEXITCODE"
docker compose -f docker-compose.yml -f docker-compose.ai-egress.yml config --quiet; "exit=$LASTEXITCODE"
```

**Expected:** no output and `exit=0` twice.

### 4.7 Optional: screenshot harness (both portals, light and dark, axe)

It needs the builds from 4.5. On Windows, point it at an installed Chrome or Edge with **`CHROME_PATH`**. Without it,
the harness tries `@sparticuz/chromium`, which only runs on Linux.

```powershell
cd tools\ui-snapshots
npm install --no-package-lock --omit=optional
npm install --no-save --no-package-lock axe-core
$env:CHROME_PATH = "C:\Program Files\Google\Chrome\Application\chrome.exe"   # or "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
node snap.mjs --portal candidate --dist ..\..\frontend\dist --theme both --axe --only design-system
node snap.mjs --portal hr --dist ..\..\hr-frontend\dist --theme both --axe --only design-system
cd ..\..
```

**Expected** for each command:
- 4 file names (`design-system--light--1440.png`, …);
- then `4 screenshots → …\out\<portal>  (report.json: 0 screen(s) with errors, blank render or horizontal overflow)`;
- exit code 0.

The full sets (omit `--only`) take several minutes per portal. The harness exits 1 on any crash, overflow or
serious/critical axe violation. Output goes to `tools\ui-snapshots\out\` (git-ignored).

---

## 5. Start the stack, check health, reset

### 5.1 Start

From the repository root:

```powershell
docker compose up --build
```

**First build:** 15–30 minutes, depending on the network. Five images are built:
- `backend`: Maven downloads and compile;
- `ai-service`: apt LibreOffice, pip with RapidOCR, onnxruntime and opencv — the slowest;
- `agent-service`;
- `candidate-portal` and `hr-portal`: pnpm install and Vite build.

Later starts without `--build` take under a minute. The backend starts only after PostgreSQL, the AI service and the
agent service report **healthy**.

To keep your terminal free, start detached and watch the logs instead:

```powershell
docker compose up --build -d
docker compose logs -f backend          # Ctrl+C stops following, not the stack
```

**Backend log lines that mean "ready"** (filter: `docker compose logs backend | Select-String "Demo|Verification demo|Started RecruitPro"`):

```
Demo data ready: organisation 'Acme Labs' with admin@acme.test, hr@acme.test, recruiter@acme.test, interviewer@acme.test and candidate candidate@demo.test (password from app.seed.password)
Demo assessments ready for 'Backend Engineer (Java)': kit, scored work sample, completed and scheduled interview
Verification demo data ready: Acme Labs in VISIBLE mode, GitHub source demo-asha with 3 shared verdicts (SYNTHETIC)   <- first start only
Started RecruitProApplication in NN.NNN seconds
```

The agent service logs a **warning on purpose**: `AGENT_DEMO_FIXTURES=true: demo- logins and hosts are answered from
synthetic fixtures`.

### 5.2 Health of every service

```powershell
docker compose ps
```

**Expected:** 7 services, `postgres`, `mailpit`, `ai-service`, `agent-service`, `backend`, `candidate-portal` and
`hr-portal`.
- Each shows `Up … (healthy)`. All have health checks: Mailpit's is built into its image; the portals use `wget /`.
- PORTS:
  - `0.0.0.0:8080->8080` (backend)
  - `0.0.0.0:5173->80` and `0.0.0.0:5174->80` (portals)
  - `127.0.0.1:8100->8100` (agent service)
  - `127.0.0.1:8025->8025` and `127.0.0.1:1025->1025` (Mailpit)
  - none for `postgres` and `ai-service`

| Service | Command or URL | Expected |
|---|---|---|
| Backend | `Invoke-RestMethod http://localhost:8080/api/v1/health \| ConvertTo-Json -Compress` | `{"success":true,"message":"Health check passed","data":{"status":"UP"},"timestamp":"…"}` |
| Backend API docs | http://localhost:8080/swagger-ui.html | Swagger UI (dev profile) |
| Agent service | `Invoke-RestMethod http://localhost:8100/health \| ConvertTo-Json -Compress` | `{"status":"ok","version":"1.0.0","llmProvider":"none","demoFixtures":true}` |
| AI service (internal only) | `docker compose exec ai-service python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/health').read().decode())"` | `{"status":"ok","version":"2.0.0","llmProvider":"none","ocrAvailable":false}` |
| AI service has no internet | `docker compose exec ai-service python -c "import urllib.request as u; u.urlopen('https://example.com', timeout=5)"` | a Python **error** (URLError / timeout). That is correct. |
| LibreOffice in the AI image | `docker compose exec ai-service sh -c "command -v soffice"` | `/usr/bin/soffice` |
| PostgreSQL (internal only) | `docker compose exec postgres psql -U recruitpro -d recruitpro -c "select email, role from users order by id;"` | 5 rows: admin, hr, recruiter, interviewer `@acme.test` and `candidate@demo.test` |
| Mailpit | http://localhost:8025 | Mailpit inbox (empty at first) |
| Candidate portal | http://localhost:5173 | redirects to `/login`, heading **Sign in** |
| HR portal | http://localhost:5174 | heading **Sign in**, intro "Use your RecruitPro work account." |
| Portal security headers | `curl.exe -s -D - -o NUL http://localhost:5173/jobs/1 \| findstr /i "content-security-policy"` | contains `connect-src 'self' http://localhost:8080` |

The same `docker compose exec` lines work in bash. The bash equivalents of `Invoke-RestMethod` are
`curl -s localhost:8080/api/v1/health` and `curl -s localhost:8100/health`.

### 5.3 Two-minute smoke test

1. **5.3-1** Open http://localhost:5174 and sign in as `admin@acme.test` / `ChangeMe-123!`.
   **Expected:**
   - you land on **Overview** (`/admin`);
   - **Service status** shows "Application server", "Database", "AI service" and "Verification agents" as **Up**, and
     "Background queue" with "N pending";
   - "Everything is running."
2. **5.3-2** In an InPrivate/Incognito window open http://localhost:5173 and sign in as `candidate@demo.test` /
   `ChangeMe-123!`.
   **Expected:**
   - **Welcome back, Casey**;
   - sidebar group **My evidence** → **Strengthen** shows the GitHub source "GitHub: demo-asha (demo)" with
     "Ownership proven · Code in your profile bio".

### 5.4 Stop and reset

| Goal | Command |
|---|---|
| Stop, keep data | `docker compose down` |
| Start again (no rebuild) | `docker compose up -d` |
| Rebuild after code changes | `docker compose up --build -d` |
| Rebuild one service | `docker compose up --build -d backend` |
| **Wipe all data and reseed** | `docker compose down -v` then `docker compose up -d` |
| Follow logs | `docker compose logs -f backend` (or `agent-service`, `ai-service`, `mailpit`) |
| Switch to `Kshitij` | `docker compose down -v` first: this branch has migrations V26–V28 that `Kshitij` does not know |

---

## 6. Accounts, seeded data, demo fixtures, test files

### 6.1 Demo accounts (password for all: `ChangeMe-123!`)

| Email | Name | Role (display name) | Portal | Lands on after sign-in | Sidebar |
|---|---|---|---|---|---|
| `admin@acme.test` | Ada Admin | Administrator | HR :5174 | `/admin` (Overview) | Workspace: Jobs, My interviews, Get started (while set-up steps are open), Inbox. Administration: Overview, Staff accounts, Invitations, Organisation, Job oversight, Audit log, Fairness, Verification |
| `hr@acme.test` | Harper Reyes | HR manager | HR :5174 | `/jobs` | Jobs, My interviews, Inbox; Administration → Verification |
| `recruiter@acme.test` | Riley Chen | Recruiter | HR :5174 | `/jobs` | Jobs, My interviews, Inbox |
| `interviewer@acme.test` | Ivan Petrov | Interviewer | HR :5174 | `/interviews` | My interviews, Inbox |
| `candidate@demo.test` | Casey Candidate | Candidate (email already confirmed) | Candidate :5173 | `/home` (Overview) | Overview, Find jobs, Applications; My evidence: Resume, Claims, Strengthen; Tasks: Work samples, Interviews; Inbox |

### 6.2 What the seed creates (organisation **Acme Labs**, slug `acme-labs`)

**Published jobs** (60-day deadline, Full time; owner Harper Reyes):

| Job | Department | Location | Requirements |
|---|---|---|---|
| **Backend Engineer (Java)** | Engineering | Bengaluru (hybrid) | Skill **Java**: must-have + gate. Skill **Spring Boot**: must-have + gate. Skill **PostgreSQL**: nice. Experience **Backend development, 3+ years**: must-have + gate. Education **Bachelor's degree in computer science or a related field**: nice |
| **Data Analyst** | Analytics | Remote (India) | Skill **SQL**: must + gate. Skill **Python**: must + gate. Skill **Tableau or Power BI**: nice. Experience **Data analysis, 1+ year**: must + gate |

**For Backend Engineer (Java):**
- an interview kit with 4 questions;
- an ACTIVE work sample, **"Design a REST endpoint with Spring Boot"** (60 minutes; 2 criteria: "REST design with
  Spring Boot", "Data model and queries").

**Casey's application to Backend Engineer (Java):**
- **INTERVIEWING**, identity revealed;
- resume `casey-candidate-resume.pdf` (generated);
- a **Scored** work sample (3.50);
- a **completed** interview two days ago with Ivan Petrov's scorecard (recommendation Yes);
- a **scheduled** interview in 3 days at 09:30 UTC (15:00 IST), "Bengaluru office, room 3B".

**Verification:**
- Acme Labs is in **Visible** mode with all connectors on.
- Casey has the skill claims **React**, **TypeScript** and **Python**.
- She has a GitHub source **`demo-asha`** with ownership proven (profile bio code) and one completed run with
  synthetic evidence.
- Her verdicts are **shared**: React **Verified**, TypeScript **Verified**, Python **Partly verified** (with an
  interview question).

Everything is created only when missing. `docker compose down -v` wipes it and reseeds on the next start.

### 6.3 Offline demo fixtures (agent service, `AGENT_DEMO_FIXTURES=true`)

Only identifiers starting with `demo-` are answered offline; everything else goes to the real internet. For the demo
the candidate must be **Asha Synthetica** with email **`asha.demo@example.com`**:
- website verdicts are **Verified** only when the page author ("Asha Synthetica") matches the candidate's name;
- badges are **Verified** only when the badge recipient (hash of `asha.demo@example.com`) matches the candidate's
  email.

The results below were produced by running the agent service in-process with the facts the AI service extracts from
`resume-asha.pdf` (6.4):

| What you enter | Ownership check | Run result for Asha (React, TypeScript, Python, Java, Kubernetes, CKAD, "Frontend Engineer, Demo Analytics", B.Tech) |
|---|---|---|
| GitHub username **`demo-asha`** | **Code in your profile bio**: proven (the fixture echoes your code) | React **Verified**, TypeScript **Verified**, Python **Partly verified**, Java and Kubernetes **Not found in connected sources**. Integrity note `FORK_ONLY` (one forked repo). Only skills are sent to code hosts |
| `demo-asha` **without** proving ownership (menu → "Run check without proof") | — | React/TypeScript/Python capped at **Partly verified** |
| GitLab username **`demo-asha`** | bio code: proven | same as GitHub |
| GitHub username **`demo-ravi`** | bio code: proven | Java **Partly verified** with notes `BULK_HISTORY` + `FORK_ONLY`; everything else Not found |
| GitHub username **`demo-nobody`** | **not proven** ("check failed: SOURCE_NOT_FOUND") | the run fails (`SOURCE_NOT_FOUND`) |
| Website **`https://demo-portfolio.example`** | **DNS record**: proven. **Text file**: proven. **Code on page**: **not proven** (the home page carries no code) | as Asha: React, TypeScript, Kubernetes, CKAD, the Demo Analytics experience and the B.Tech all **Verified**; Python and Java **Not found**. As any other name: those six are **Partly verified** |
| Website **`https://demo-portfolio.example/verify`** | **Code on page**: **proven** (this page shows the code) | everything **Not found** (the page has no content). Use it only to test the page-code method |
| Document (upload `resume-asha.pdf` or `.docx`) | none (documents cannot be proven) | every listed claim **Partly verified**. With a DOCX whose author is not your name: note `AUTHOR_MISMATCH` |
| Open Badge link **`https://demo-badges.example/assertions/react-1`** (OB 2.0 hosted, "React Fundamentals") | issuer signature during the run | as Asha: React **Verified**. As anyone else (e.g. Casey): React **Contradicted** (issued to someone else) |
| Open Badge link **`https://demo-badges.example/credentials/python.jwt`** (OB 3.0 VC-JWT, "Python Programming") | same | as Asha: Python **Verified**. As anyone else: Python **Contradicted** |
| Open Badge **"The credential itself"** (pasting the same JWT) | — | Python **Not checkable by agents** (`CREDENTIAL_UNCHECKABLE`): offline, only links are routed to the fixtures (see 12, item 4) |
| Reference: referee `@demo-analytics.example`, employer domain `demo-analytics.example`, employer "Demo Analytics", relationship **Manager** or **Colleague** (or Client), answer **Confirm** | — | **Verified** (trusted demo employer) |
| Same, relationship **Someone who reported to you**, Teacher or Other | — | **Partly verified** (not an anchoring relationship) |
| Referee `@demo-newco.example`, domain `demo-newco.example`, employer "Demo Newco", Manager, Confirm | — | **Partly verified** with `EMPLOYER_DOMAIN_UNPROVEN` + `EMPLOYER_DOMAIN_NEW` (domain is 40 days old) |
| Referee `@gmail.com`, Manager, Confirm | — | **Partly verified** (free mail never anchors) |
| Answer **Partly** / **Can't confirm** / **Disagree** | — | **Partly verified** / **Not found in connected sources** / **Contradicted** (the reason quotes the comment) |

Check that fixtures are on with http://localhost:8100/health: `"demoFixtures":true`.

### 6.4 Test files (2 minutes; fake people only)

Make a folder **outside** the repository and generate the files with a script. It uses `python-docx` from the venv
of 4.2/4.3 and writes PDFs with the standard library.

```powershell
New-Item -ItemType Directory -Force C:\RecruitProTest | Out-Null
cd C:\RecruitProTest
@'
from docx import Document

RESUMES = {
    "resume-a": ["Arjun Test", "arjun.test@example.com | +91 90000 00001", "SKILLS",
                 "Java, Spring Boot, PostgreSQL, REST APIs, Docker, Git", "EXPERIENCE",
                 "Software Engineer, Alpha Systems    Jan 2020 - Present",
                 "Built REST APIs in Java and Spring Boot backed by PostgreSQL.",
                 "Junior Developer, Beta Labs    Jun 2018 - Dec 2019",
                 "Maintained Java services and wrote SQL reports.", "EDUCATION",
                 "B.Tech in Computer Science and Engineering, Example Institute of Technology, 2018"],
    "resume-b": ["Meera Sample", "meera.sample@example.com", "SKILLS", "Python, Excel, Tableau", "EXPERIENCE",
                 "Data Intern, Gamma Analytics    Jun 2024 - Dec 2024",
                 "Cleaned data in Python and built Tableau dashboards.", "EDUCATION",
                 "B.Sc. in Statistics, Coastal University, 2024"],
    "resume-asha": ["Asha Synthetica", "asha.demo@example.com", "SKILLS", "React, TypeScript, Python, Java",
                    "CERTIFICATIONS", "Certified Kubernetes Application Developer (CKAD)", "EXPERIENCE",
                    "Frontend Engineer, Demo Analytics    Jan 2021 - Present",
                    "Built React and TypeScript dashboards.", "EDUCATION",
                    "B.Tech in Computer Science, Demo Institute of Technology, 2019"],
}


def pdf(lines):
    esc = lambda s: s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = "BT /F1 11 Tf 72 740 Td 16 TL\n" + "".join(f"({esc(l)}) Tj T*\n" for l in lines) + "ET\n"
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            f"<< /Length {len(stream.encode('ascii'))} >>\nstream\n{stream}endstream"]
    out, offsets = "%PDF-1.4\n", []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out.encode("ascii")))
        out += f"{i} 0 obj\n{body}\nendobj\n"
    xref = len(out.encode("ascii"))
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return out.encode("ascii")


for name, lines in RESUMES.items():
    open(f"{name}.pdf", "wb").write(pdf(lines))
    document = Document()
    for line in lines:
        document.add_paragraph(line)
    document.core_properties.author = lines[0]
    document.save(f"{name}.docx")
open("work-sample-answer.pdf", "wb").write(pdf(["Work sample answer (test)",
     "POST /orders validates input with Bean Validation.",
     "Orders table: id, customer_id (FK), external_ref (unique), created_at."]))
open("not-a-resume.txt", "w").write("This file type is refused by the resume upload.\n")
print("Created: resume-a/-b/-asha (.pdf and .docx), work-sample-answer.pdf, not-a-resume.txt")
'@ | Set-Content -Encoding ascii make_test_files.py
& "C:\Users\KIIT0001\Desktop\RecruitPro\agent-service\.venv\Scripts\python.exe" make_test_files.py
Get-ChildItem
```

**Expected:**
- the script prints `Created: …`;
- the folder holds `resume-a.pdf/.docx`, `resume-b.pdf/.docx`, `resume-asha.pdf/.docx`, `work-sample-answer.pdf`
  and `not-a-resume.txt`.

What the AI service extracts from them (checked in-process):
- `resume-a`: experience "Software Engineer, Alpha Systems" and "Junior Developer, Beta Labs"; the B.Tech; skills
  Java, Spring Boot, PostgreSQL, REST API, Docker, Git, SQL.
- `resume-b`: "Data Intern, Gamma Analytics"; the B.Sc.; skills Python, Excel, Tableau.
- `resume-asha`: "Frontend Engineer, Demo Analytics"; the B.Tech; certification "Certified Kubernetes Application
  Developer (CKAD)"; skills React, TypeScript, Python, Java, Kubernetes.

Also have a **file over 10 MB** (any large PDF or video) for the size check, and optionally a PNG screenshot for the
work-sample attachment.

### 6.5 Test people you will create

| Person | Email | Password | Used for |
|---|---|---|---|
| Arjun Test | `arjun.test@example.com` | `TestPass123` | strong Backend applicant; work sample, interview, select |
| Meera Sample | `meera.sample@example.com` | `TestPass123` | weak applicant; reject/reopen; **account deletion at the end** |
| Asha Synthetica | `asha.demo@example.com` | `TestPass123` | every verification path; Frontend applicant |
| Priya Manager (referee) | `priya@demo-analytics.example` | — | reference page via Mailpit |
| Test Org 2 admin | `owner@org2.example` | `TestPass123` | new organisation, first-run checklist, tenant isolation |
| Invited HR | `newhr@acme.test` | `TestPass123` | invitation + accept-invite with MFA |

All mail lands in Mailpit, whatever the domain.

---

## 7. Test scripts, in run order (both portals)

**Browser set-up:**
- **Window H** (normal): HR portal http://localhost:5174.
- **Window C** (InPrivate/Incognito): candidate portal http://localhost:5173.
- A third tab: Mailpit http://localhost:8025.
- Both portals may be signed in at once in one browser (separate cookies `rp_refresh_staff` and
  `rp_refresh_candidate`). Separate windows just keep things clear.

**Timing:**
- Resume analysis, matching, verification runs and reference checks go through a job queue polled every 2 s.
- Emails go out every 5 s.
- Pages poll by themselves while something is pending. Most results appear within seconds.

**Labels:** quoted labels are exact. "→" means "then click".

### Part I: seeded data and roles

#### 7.1 [HR] Admin overview and health

1. **7.1-1** Window H → http://localhost:5174.
   **Expected:**
   - heading **Sign in**, fields **Work email** and **Password**, link "Forgot your password?", button **Sign in**,
     footer "New organisation? Create a workspace";
   - **no** "or" divider and no "Continue with …" buttons (no SSO configured).
2. **7.1-2** Type `admin@acme.test` / `ChangeMe-123!` → **Sign in**.
   **Expected:** **Overview** with:
   - "Refreshes every minute";
   - **Service status**: Application server, Database, AI service and Verification agents **Up**; Background queue;
   - **In your organisation**: Staff accounts, Jobs (2), Applications, …;
   - charts **Applications by status** and **Jobs by status**.
3. **7.1-3** Account menu (avatar, top right).
   **Expected:**
   - name, email and role;
   - links **Change password** and **Two-step verification**;
   - a **Theme** switch **System / Light / Dark**;
   - **Sign out**.
4. **7.1-4** Press **Sign out**. **Expected:** back on **Sign in**.

#### 7.2 [HR] Roles and wrong-portal sign-in

1. **7.2-1** Sign in as `hr@acme.test`.
   **Expected:**
   - lands on **Jobs** (`/jobs`);
   - tabs **All**, **Published**, **Drafts**, **On hold**, **Closed**;
   - both Acme jobs listed with columns Job, Department, Location, Status, Applicants, Closes, Created;
   - button **New job**.
2. **7.2-2** Sign out; sign in as `recruiter@acme.test`.
   **Expected:**
   - Jobs, My interviews and Inbox only;
   - no **New job**;
   - typing http://localhost:5174/admin shows "Not available for your role" / "Your role does not include this area".
3. **7.2-3** Sign out; sign in as `interviewer@acme.test`.
   **Expected:**
   - lands on **My interviews**: "Completed and cancelled" lists the seeded completed interview (Casey) with
     "Scorecard in";
   - "Upcoming" lists the one in 3 days.
4. **7.2-4** Sign out; sign in as `candidate@demo.test`.
   **Expected:** red notice "This is the HR portal for organisation staff. Candidate accounts sign in to the candidate
   portal." No session is created.
5. **7.2-5** Window C → http://localhost:5173 → sign in as `hr@acme.test`.
   **Expected:** form error "This is the candidate portal. Staff accounts sign in to the RecruitPro HR portal."
6. **7.2-6** Window H: sign in as `hr@acme.test` again (stay signed in for Part II).

#### 7.3 [Candidate] Casey's seeded state

1. **7.3-1** Window C → sign in as `candidate@demo.test` / `ChangeMe-123!`.
   **Expected:**
   - **Overview** (`/home`), heading "Welcome back, Casey", "1 application in progress";
   - **Needs your attention** shows "Interview: Backend Engineer (Java)" (action **Details**);
   - **Your applications** lists Backend Engineer (Java), "Interviewing".
2. **7.3-2** **Applications** → the row.
   **Expected:**
   - **Next step** "Next step · interview in …" with **Add to calendar**;
   - **Progress** timeline with Submitted, Shortlisted, Interviewing;
   - **What you sent**: Resume `casey-candidate-resume.pdf`;
   - **Work samples** and **Interviews** listed.
3. **7.3-3** **Work samples**. **Expected:** under **Finished**, "Design a REST endpoint with Spring Boot", status
   **Reviewed**.
4. **7.3-4** **Interviews**.
   **Expected:**
   - "Times are shown in your time zone, IST (UTC+5:30)." (or your zone);
   - **Upcoming**: Backend Engineer (Java), 15:00–16:00 IST, "60 minutes", "Bengaluru office, room 3B", button
     **Add to calendar** (no **Join**: the location is not a link);
   - **Past and cancelled**: the completed one with badge **Ended**.
5. **7.3-5** **Strengthen** (sidebar, under My evidence).
   **Expected:**
   - heading **Strengthen your application**; buttons **Ask for a reference** and **Add a source**;
   - stats "Claims with outside evidence", "Waiting for your review" (0), "Shared with employers" (3), "Sources" (1);
   - source "GitHub: demo-asha (demo)": "Ownership proven · Code in your profile bio", "Last checked …";
   - **All your claims**: React and TypeScript **Verified**, Python **Partly verified**.
6. **7.3-6** Leave Casey signed in for now.

### Part II: the job used by later steps

#### 7.4 [HR] Job wizard: Frontend Engineer (React)

As `hr@acme.test` in Window H.

1. **7.4-1** **Jobs** → **New job**.
   **Expected:**
   - page **New job**;
   - stepper **Basics / Requirements / Review** (`?step=basics`).
2. **7.4-2** Section **The role**:
   - **Job title** `Frontend Engineer (React)`;
   - **Department** `Engineering`;
   - **Location** `Remote (India)`;
   - **Employment type** Full time;
   - **Experience level** Mid.

   Section **Pay, openings and deadline**:
   - **Minimum salary** `1200000`;
   - **Maximum salary** `2000000`;
   - **Openings** `1`;
   - leave **Application deadline** empty.

   **Job description** (paste):

   > We are looking for a young, energetic rockstar developer. Must have 3+ years of React and TypeScript. Python is a plus. Experience with AWS or Azure is required. A degree in computer science is mandatory. Good communication skills.

3. **7.4-3** Reload the page (F5) before continuing.
   **Expected:** banner "You have an unfinished job on this device" with **Resume** / **Start over** → **Resume**.
4. **7.4-4** **Continue to requirements**.
   **Expected:**
   - toast "Draft created" / "Saved automatically from now on.";
   - the suggestions run by themselves ("Reading your description…");
   - panel **Suggested from your description**, subline "Drafted by the rules engine. Each suggestion quotes the
     sentence it came from." (`LLM_PROVIDER=none`).

   The rules engine suggests exactly these 4 (checked in-process):
   - Experience "have React and TypeScript", 3 years minimum, must-have + gate;
   - Skill "Python", nice to have;
   - Skill "AWS", must-have + gate, **Also accepted: Azure**;
   - Education "degree in computer science", must-have + gate.

   Box **Wording to reconsider (5)**:

   | Phrase | Badge |
   |---|---|
   | "young" | Age-coded |
   | "energetic" | Age-coded |
   | "rockstar" | Gender-coded |
   | "Good communication skills" | Vague |
   | "degree in computer science" | Degree inflation |

5. **7.4-5** Leave **All selected** → **Use 4 suggestions**. **Expected:** toast "4 requirements added".
6. **7.4-6** Edit the list so it reads exactly as below:
   - **Row 1:** type **Skill**, text `React`, **Must-have** ✓, **Gate** ✓. Open **More** and clear "Minimum years"
     (placeholder "Any").
   - **Row 2:** **Add requirement** → Skill `TypeScript`, **Must-have** ✓, **Gate** unticked. This tests a must-have
     that is not a gate.
   - **Row 3:** Python stays a nice-to-have. Open **More** → **Proficiency** "Expert". This is the proficiency test in
     7.21.
   - **Row 4:** AWS: untick **Must-have** (Gate becomes disabled). In **More**, "Also accepted" already holds `Azure`:
     type `GCP` + Enter to add a second alternative.
   - **Row 5:** the degree row → **Remove "degree in computer science"**.
     **Expected:** toast "Removed "…"" with **Undo**. Click **Undo** (it returns), then remove it again.
   - **Add requirement** → type **Experience**, text `Frontend development`, **More** → **Minimum years** `2`, not
     must-have.
   - **Add requirement** → type **Other**, text `Portfolio walkthrough`, tick **Must-have**.
     **Expected:** **Gate** stays disabled; tooltip on Gate: "Holds an applicant in "Must-haves not yet verified" until
     this is verified. Never rejects anyone." (OTHER never gates.) Then remove this row again.

   **Expected:** summary line "6 requirements · 2 must-haves · 1 gates" (exact counts follow your rows).
7. **7.4-7** Reorder: drag row 3 (Python) above row 2, or focus its handle and press **Alt+↑**. Then use **More** →
   **Move Python down** to restore.
   **Expected:** rows move; nothing is lost.
8. **7.4-8** **Review** step.
   **Expected:**
   - sections **How applicants will be ranked**, **Checks** (failing checks have a **Fix** link) and **What candidates
     see**;
   - buttons **Keep as draft** and **Publish**.
9. **7.4-9** **Publish** → dialog "Publish "Frontend Engineer (React)"?" → **Publish**.
   **Expected:**
   - the job page with tabs **Applicants**, **Work samples**, **Interview kit**, **Fairness**, **Details**;
   - status Published.
10. **7.4-10** Window C (Casey) → **Find jobs**. **Expected:** "Frontend Engineer (React)" is listed.

#### 7.5 [HR] Wizard: AI offline behaviour, hold/resume, live edit

1. **7.5-1** In PowerShell: `docker compose stop ai-service`.
2. **7.5-2** Window H → **Jobs** → **New job** → title `AI offline test`, description `Must have Kotlin and Android
   experience for this mobile role.` → **Continue to requirements**.
   **Expected:**
   - "Suggestions are unavailable right now. Add requirements by hand; you can try again later." with **Try again**;
   - you can still **Add requirement** by hand.
3. **7.5-3** `docker compose start ai-service`. Wait until `docker compose ps` shows it `(healthy)` (about 20 s), then
   **Try again**. **Expected:** suggestions appear (rules engine).
4. **7.5-4** Back to **Jobs** → tab **Drafts** → row ⋯ of "AI offline test" → **Delete draft** → confirm **Delete
   draft**. **Expected:** gone.
5. **7.5-5** Open **Frontend Engineer (React)** → header **Put on hold**.
   **Expected:**
   - toast ""Frontend Engineer (React)" is on hold" / "Candidates can no longer see or apply to it." with **Undo**
     (6 s);
   - banner "On hold: candidates cannot see or apply to this job…".
   - Window C → **Find jobs**: the job is gone.
6. **7.5-6** Window H → **Resume publishing**. **Expected:** toast "…is published again"; the job is back for
   candidates.
7. **7.5-7** **Edit** → change **Openings** to `2` → **Save changes**. **Expected:** saved; no re-match prompt.
8. **7.5-8** **Edit** → **Requirements** → add a nice-to-have Skill `Jest` → **Save changes**.
   **Expected:** dialog "Change the requirements of a live job?" → **Save and re-match**.
9. **7.5-9** Edit again and only move a row up and back down, then save.
   **Expected:** the order is saved. **No** "Change the requirements" dialog: a pure reorder does not re-match.

**Close** is tested in 7.34 on a throw-away job, because a closed job cannot be reopened.

### Part III: candidates

#### 7.6 [Candidate] Sign-up, email confirmation, sign-in

1. **7.6-1** Window C: account menu → **Sign out** ("You have signed out.") → **New here? Create an account**.
   **Expected:**
   - heading "Create your account";
   - fields **First name**, **Last name**, **Email**, **Password** (live checklist "10 to 72 characters", "At least one
     letter", "At least one digit") and **Confirm password**;
   - no consent boxes.
2. **7.6-2** Validation:
   - empty submit → "Enter your first name." etc.;
   - password `abc` → "Use 10 to 72 characters with at least one letter and one digit.";
   - mismatched confirm → "The passwords do not match."
3. **7.6-3** Register **Arjun** / **Test** / `arjun.test@example.com` / `TestPass123` (twice) → **Create account**.
   **Expected:**
   - toast "Your account is ready" / "We sent a link to confirm your email address.";
   - **Overview**, "Welcome, Arjun";
   - banner "Confirm your email address." with **Resend link**;
   - checklist **Get set up**: "0 of 5 done…" with step 1 **Confirm your email** open.
4. **7.6-4** **Resend link**. **Expected:** toast "Verification link sent" / "Check arjun.test@example.com…".
5. **7.6-5** Mailpit.
   **Expected:** two mails "Confirm your email address for RecruitPro". Open the newest → click the link
   (`http://localhost:5173/verify-email?token=…`).
   **Expected:**
   - "Email address confirmed" / "Thank you. arjun.test@example.com is confirmed…" → **Continue**;
   - the banner is gone; checklist "1 of 5 done".
6. **7.6-6** Click the **same** link again. **Expected:** "We could not confirm this link" … "Links expire after 24
   hours and work once."
7. **7.6-7** Reload with F5. **Expected:** still signed in.
   - DevTools → Application → Local Storage holds only UI keys (e.g. `theme-preference`); no token.
   - Cookies (for localhost:8080) hold `rp_refresh_candidate`, HttpOnly.
8. **7.6-8** Sign out → **Forgot your password?** → `arjun.test@example.com` → **Send reset link**.
   **Expected:**
   - "Check your email";
   - Mailpit "Reset your RecruitPro password" → link `…/reset-password?token=…`;
   - set `TestPass1234` twice → **Set new password**;
   - "Password changed" / "Every session was signed out…" → **Go to sign in**;
   - sign in with `TestPass1234`.

   (From here Arjun's password is `TestPass1234`.)

#### 7.7 [Candidate] Overview checklist and resume

As Arjun.

1. **7.7-1** Checklist step 2 **Add your resume**: drop `C:\RecruitProTest\resume-a.pdf` on "Drop a PDF, DOC or DOCX
   here, or choose a file".
   **Expected:**
   - toast "resume-a.pdf uploaded" / "We are reading it now…";
   - step 3 **Review what we read** shows "We are reading your resume…", then its button **Review what we read**.
2. **7.7-2** **Review what we read** (goes to `/resumes`).
   **Expected:**
   - heading **Resume**;
   - file row "… · newest, uploaded …" with badge **Analysis ready**;
   - **What we read**: groups **Skills** (Java, Spring Boot, PostgreSQL, REST API, Docker, Git, SQL), **Experience**
     (Software Engineer, Alpha Systems; Junior Developer, Beta Labs), **Education** (the B.Tech) and **Contact details
     and links** (read-only).
   - Back on **Overview** the checklist shows step 3 done.
3. **7.7-3** On "Docker" → **Suggest a correction** → **Your correction** `Docker, Kubernetes` → **Save as my claim**.
   **Expected:**
   - toast "Saved as your claim";
   - the extracted value stays.
   - Validation: an empty value → "Enter the corrected value."; the same value → "This is the same as the current value."
4. **7.7-4** Upload `resume-a.docx`.
   **Expected:**
   - it becomes **newest**;
   - status **Queued for analysis** → **Analysing** → **Analysis ready**.
5. **7.7-5** Upload `not-a-resume.txt`. **Expected:** "not-a-resume.txt is not an accepted file type."
6. **7.7-6** Upload a file over 10 MB. **Expected:** "… is larger than 10 MB."
7. **7.7-7** On the older `resume-a.pdf` → **Delete resume-a.pdf** → dialog "Delete this resume?" → **Delete resume**.
   **Expected:**
   - toast "resume-a.pdf deleted" / "Its analysis and the corrections you made to it were deleted too.";
   - the docx remains. (The in-use case is 7.9-6.)
8. **7.7-8** Upload `resume-a.pdf` again so Arjun applies with a PDF.

#### 7.8 [Candidate] Jobs search and job detail

1. **7.8-1** **Find jobs**.
   **Expected:**
   - "Open roles you can apply to now…";
   - filters **Job title** ("Search job titles"), **Department** ("Any department"), **Location** ("Any location") and
     **Job type** ("All job types");
   - "3 open roles".
2. **7.8-2** Type `engineer` slowly.
   **Expected:**
   - the cursor stays in the box;
   - the list updates after you pause and shows "2 open roles";
   - the URL holds `?q=engineer`.
3. **7.8-3** **Department** "Analytics" → only Data Analyst. **Clear filters** → all again.
4. **7.8-4** Type `zzz`. **Expected:** "No open roles match "zzz"". **Clear filters**.
5. **7.8-5** Open **Backend Engineer (Java)**.
   **Expected:**
   - breadcrumb "Find jobs › Backend Engineer (Java)";
   - **About the role**;
   - **What they are looking for** with **Must-haves** (Java, Spring Boot, Backend development "3+ years", each
     "Checked first") and **Nice to have** (PostgreSQL, the degree);
   - facts **Salary**, **Openings**, **Apply by**, **Published**;
   - panel **How your evidence matches**: "N of 5 requirements found in your resume and claims";
   - button **Apply for this role**;
   - no recruiter email anywhere.

#### 7.9 [Candidate] Apply dialog and applications

1. **7.9-1** **Apply for this role**.
   **Expected:**
   - dialog "Apply: Backend Engineer (Java)" with steps **Resume / Review / Sent**;
   - **Resume to send** has the newest file preselected ("Newest · Uploaded …");
   - **Cover letter (optional)** placeholder "Why this role, and what you would bring to it."
2. **7.9-2** Type a two-line cover letter.
   **Expected:**
   - hint "Draft saved.";
   - close the dialog with **Cancel**, reopen it: the text is still there.
3. **7.9-3** **Review application**.
   **Expected:**
   - rows **Role**, **Resume**, **Cover letter** ("Included (N characters)") and **Contact email**;
   - **Evidence that goes with it**;
   - note "Your name and cover letter stay hidden until the employer shortlists you…";
   - buttons **Back** / **Submit application**.
4. **7.9-4** **Submit application**.
   **Expected:**
   - title "Application sent";
   - "What happens next": **Blind review**, **Shortlist**, **Work sample or interview**;
   - buttons **Close** / **View application**;
   - Mailpit "We received your application for Backend Engineer (Java)".
5. **7.9-5** **View application**.
   **Expected:**
   - status **Submitted**; next step "In blind review with the employer";
   - **Progress** Submitted, then upcoming Shortlist, Interview, Decision;
   - **Requirements and your evidence**: Docker/Kubernetes is not a requirement, but each requirement shows a badge
     ("Not yet verified" or "Your claim, unverified");
   - menu **More actions** → **Withdraw application…** exists (do not use it yet).
6. **7.9-6** **Resume** → delete `resume-a.pdf` (the one you applied with).
   **Expected:** "An application uses this file, so it is kept: employers review the resume you applied with. You can
   still upload a newer one." The server answered 409.
7. **7.9-7** Open the job again. **Expected:** "You applied …" and **View your application** instead of Apply.
8. **7.9-8** **Claims** → **Add a claim**:
   - Type **Certification**, "What you are claiming" `Oracle Certified Professional Java SE 17`;
   - **Evidence link** `javascript:alert(1)` → **Add claim**. **Expected:** "Enter a full link starting with https://
     or http://."
   - Change the link to `https://example.com/cert` → **Add claim**. **Expected:** toast "Claim added"; the row shows
     "Not checked yet".
9. **7.9-9** **Privacy** → **Equal-opportunity survey**:
   - pick any **Gender** and **Age group**; leave the others "Not answered";
   - **Save answers** without ticking consent → "Tick the consent box to save your answers. Saving is optional.";
   - tick the consent → **Save answers**. **Expected:** saved; the button becomes **Update answers**.

   This feeds the fairness report in 7.29.

#### 7.10 [Candidate] Meera (weak applicant)

1. **7.10-1** Sign out → register **Meera** / **Sample** / `meera.sample@example.com` / `TestPass123`. No need to
   confirm the email (dev allows applying unconfirmed).
2. **7.10-2** Checklist step 2: upload `resume-b.pdf`; wait for **Analysis ready**.
3. **7.10-3** **Find jobs** → **Backend Engineer (Java)** → **Apply for this role** → **Review application** →
   **Submit application**.
4. **7.10-4** Also apply to **Data Analyst** the same way.
5. **7.10-5** **Privacy** → survey: choose a different **Gender** from Arjun → consent → **Save answers**.

#### 7.11 [Candidate] Asha (verification persona)

1. **7.11-1** Sign out → register **Asha** / **Synthetica** / `asha.demo@example.com` / `TestPass123`.
   Optional: confirm the email via Mailpit.
2. **7.11-2** Upload `resume-asha.pdf` → **Analysis ready** → open **Resume** once (completes "Review what we read").
   **Expected skills:** React, TypeScript, Python, Java, Kubernetes; certification CKAD.
3. **7.11-3** **Find jobs** → **Frontend Engineer (React)** → the match panel.
   **Expected:** must-haves React and TypeScript found in your resume.
   **Apply for this role** → **Review application** → **Submit application**.
4. **7.11-4** Overview checklist.
   **Expected:**
   - "4 of 5 done" (no source yet), or 3 if you skipped the email;
   - step **Strengthen with a source** open with **Add a source**.

#### 7.12 [Candidate] Strengthen: GitHub by username and bio code

As Asha.

> **Run budget.** A candidate may start **10 checks per 24 h**. Every run and every **Re-check** counts; ownership
> checks and references do not. 7.12–7.18 use exactly 10 runs for Asha, in this order:
> 1. GitHub
> 2. GitLab
> 3. demo-ravi
> 4. demo-nobody
> 5. website
> 6. document
> 7. React badge
> 8. Python badge
> 9. pasted badge
> 10. one Re-check
>
> The 11th run is the 429 test in 7.18-3. Do not start extra runs, or use Casey for experiments.

1. **7.12-1** Checklist **Add a source** (or sidebar **Strengthen** → **Add your first source**).
   **Expected:**
   - drawer with steps **Choose / Connect / Prove / Check**; URL holds `?add=source&step=choose`;
   - "Where can your work be seen?" with GitHub, GitLab, Website, Document and Open Badge.
2. **7.12-2** **GitHub** → **Continue**.
   **Expected:**
   - a consent checkbox ("I agree that RecruitPro may read my public GitHub profile and repositories…");
   - text "You will sign in on GitHub and come straight back here…";
   - button **Continue with GitHub**.
3. **7.12-3** Without ticking consent → **Continue with GitHub**. **Expected:** "Please tick the consent box first.
   Nothing is connected without your consent."
4. **7.12-4** Tick consent → **Continue with GitHub**.
   **Expected (no OAuth app configured):**
   - the panel switches to the username route;
   - message "Signing in with GitHub is not available on this server. Connect your username instead and prove it is
     yours with a code in your profile bio."

   With an OAuth app configured, see 9.1.
5. **7.12-5** **GitHub username** `demo-asha` → **Connect**.
   **Expected:**
   - toast "GitHub: demo-asha connected";
   - step **Prove**: "Add a code to your GitHub profile bio" with steps 1–2;
   - a copy box **Code** showing `rp-` plus 24 characters; **Copy Code** shows "Copied";
   - buttons **Skip for now** / **Check now**.
6. **7.12-6** **Check now**. You edit no real bio: the fixture echoes the code.
   **Expected:**
   - "Ownership proven"; toast "Ownership proven" / "Results from this source can now be Verified.";
   - button **Continue to the first check**.
7. **7.12-7** **Continue to the first check** → **Run the first check**.
   **Expected:**
   - "Reading GitHub: demo-asha" / "This usually takes under a minute…";
   - then "N results are ready" / "Only you can see them until you choose to share.";
   - buttons **Later** / **Review results**;
   - Mailpit: "Your verification results are ready".
8. **7.12-8** **Review results**.
   **Expected:** the verdict drawer:
   - result chip, "Awaiting your review", "1 of N waiting";
   - **Why** — reasons speak of "the account" and "repository A";
   - **Evidence** — items with title, link, retrieved time and **Fingerprint** (sha256), labelled synthetic;
   - "You proved this source is yours.";
   - **What employers see if you share**;
   - footer **Re-check**, **Hide** and **Share with employers**.
9. **7.12-9** Check the results against 6.3: React **Verified**, TypeScript **Verified**, Python **Partly verified**
   (section **An interviewer may ask** with a question), Java and Kubernetes **Not found in connected sources**.
   Press **j** / **k** to move to the next / previous result.
10. **7.12-10** On React → **Add a note for employers** → `Built the dashboard at work` → **Share with employers**.
    **Expected:**
    - toast "Shared with employers"; the drawer moves to the next result;
    - **Share** TypeScript and Python too.
11. **7.12-11** **Strengthen** page.
    **Expected:**
    - source "GitHub: demo-asha", "Ownership proven · Code in your profile bio", "Last checked …";
    - **All your claims**: columns **Claim**, **Evidence**, **Outside checks**.

#### 7.13 [Candidate] Weaker and failing code sources

1. **7.13-1** **Add a source** → **GitLab** → consent → **GitLab username** `demo-asha` → **Connect** → **Check now**
   → **Run the first check**. **Expected:** the same verdicts as GitHub.
2. **7.13-2** **Add a source** → GitHub → consent → **Use my username instead** → `demo-ravi` → **Connect** →
   **Check now** → run.
   **Expected:** Java **Partly verified**, notes `BULK_HISTORY` and `FORK_ONLY` in **Notes for context**; nothing
   Verified.
3. **7.13-3** **Add a source** → GitHub → `demo-nobody` → **Connect** → **Check now**.
   **Expected:** "We could not find the code yet…", i.e. not proven. The source shows "Ownership check did not succeed
   yet".
4. **7.13-4** On the `demo-nobody` source menu → **Run check without proof**.
   **Expected:** the run ends without results (failed, code `SOURCE_NOT_FOUND`).
5. **7.13-5** Add `demo-asha` on **GitHub** again. **Expected:** "This source is already connected. Revoke the existing
   one first if you want to connect it again." (409)
6. **7.13-6** Revoke the ravi and nobody sources: source menu → **Revoke source…** → dialog "Revoke GitHub:
   demo-ravi?" → **Revoke source**.
   **Expected:**
   - toast "Source revoked";
   - their results disappear from the ledger.

#### 7.14 [Candidate] Website: DNS record, text file, code on page

1. **7.14-1** **Add a source** → **Website** → consent → **Website address** `https://demo-portfolio.example` →
   **Connect**.
   **Expected:** **Prove** with a three-way switch **How to prove it**: "DNS record" / "Text file" / "Code on page".
2. **7.14-2** **DNS record**.
   **Expected:**
   - "Add a DNS TXT record";
   - copy boxes **Host** (`demo-portfolio.example`) and **TXT value** (`recruitpro-verification=rp-…`).

   **Text file:**
   - **File address** `https://demo-portfolio.example/.well-known/recruitpro-verification.txt`;
   - **File content** `rp-…`.

   **Code on page:**
   - **Code**;
   - **Meta tag** `<meta name="recruitpro-verification" content="rp-…">`.
3. **7.14-3** Select **Code on page** → **Check now**.
   **Expected:** not proven ("We could not find the code yet…"). The demo home page carries no code (6.3).
4. **7.14-4** Select **DNS record** → **Check now**. **Expected:** "Ownership proven".
5. **7.14-5** **Continue to the first check** → **Run the first check** → **Review results**.
   **Expected:** React, TypeScript, Kubernetes, CKAD, "Frontend Engineer, Demo Analytics" and the B.Tech all
   **Verified**; Python and Java **Not found**. **Share** them.
6. **7.14-6** Test the other two methods on extra sources. They only prove ownership; do **not** run them (run
   budget).
   - **Add a source** → Website → `https://demo-portfolio.example/verify` → **Connect** → **Code on page** →
     **Check now**. **Expected:** "Ownership proven". → **Not now**.
   - **Add a source** → Website → `https://demo-portfolio.example/about` → **Connect** → **Text file** → **Check
     now**. **Expected:** "Ownership proven" (the file lives on the host). → **Not now**.
   - Revoke both extra sources (source menu → **Revoke source…** → **Revoke source**).

#### 7.15 [Candidate] Document

1. **7.15-1** **Add a source** → **Document** → consent → drop `C:\RecruitProTest\resume-asha.docx` on "Drop a PDF,
   DOCX or PPTX here, or choose a file" (hint "Up to 15 MB. Ownership of a document cannot be proven, so its results
   are at most Partly verified.") → **Upload and connect**.
   **Expected:** the drawer skips **Prove** and goes to **Check**.
2. **7.15-2** **Run the first check** → **Review results**.
   **Expected:**
   - every listed claim **Partly verified**;
   - "Ownership of this source is not proven, so results are at most "Partly verified"."
3. **7.15-3** Try uploading `not-a-resume.txt`. **Expected:** "Only PDF, DOCX and PPTX files can be checked."

#### 7.16 [Candidate] Open Badges

1. **7.16-1** **Add a source** → **Open Badge** → consent → **How do you have the badge?** **A link to the badge** →
   **Badge address** `https://demo-badges.example/assertions/react-1` → **Connect**.
   **Expected:** no Prove step.
   **Run the first check** → React **Verified** (issuer-hosted assertion, recipient matches your email). **Share**.
2. **7.16-2** Same with `https://demo-badges.example/credentials/python.jwt`.
   **Expected:** Python **Verified** (OB 3.0 signed credential, issuer `did:web:demo-badges.example`). **Share**.
3. **7.16-3** **Add a source** → Open Badge → **The credential itself** → **Badge credential**: paste the JWT text.
   Get it with:
   ```powershell
   & .\agent-service\.venv\Scripts\python.exe -c "import sys; sys.path.insert(0,'agent-service'); from app.demo.fixtures import demo_vc_jwt; print(demo_vc_jwt())"
   ```
   (run from the repository root) → **Connect** → run.
   **Expected:** Python **Not checkable by agents**. Offline, a pasted credential's issuer cannot be resolved; this
   proves the "uncheckable" path (12, item 4). Revoke it.
4. **7.16-4** Contradiction check:
   - Window C: sign out; sign in as `candidate@demo.test`;
   - add the badge link `https://demo-badges.example/assertions/react-1` → run.
   **Expected:**
   - React **Contradicted** (badge issued to someone else);
   - **Hide** it ("Hidden from employers");
   - then revoke the badge source.

   Sign back in as Asha.

#### 7.17 [Candidate + referee] References

As Asha.

1. **7.17-1** **Strengthen** → **Ask for a reference**.
   **Expected:**
   - drawer "Ask for a reference" — "They get a private link by email, valid for 14 days, and answer without signing
     in.";
   - steps **Who** → **What to confirm**.
2. **7.17-2** **Who**:
   - **Their name** `Priya Manager`;
   - **Their email** `priya@demo-analytics.example`;
   - **How do they know your work?** **Manager**;
   - **Employer or organisation (optional)** `Demo Analytics`;
   - **Employer website domain (optional)** `demo-analytics.example`;
   - → **Choose claims**.
3. **7.17-3** **What to confirm**: tick **React**, **Python** and **Frontend Engineer, Demo Analytics** ("3 of N
   chosen") → tick the consent → **Send request**.
   **Expected:**
   - toast "Request sent to Priya Manager" / "The link is valid for 14 days.";
   - **References** "1 of 5 open", status **Waiting for an answer**.
4. **7.17-4** Mailpit.
   **Expected:**
   - "Asha asked you for a reference" (first name only);
   - the link is `http://localhost:5173/reference/<token>`; open it in **any** browser, signed out.
5. **7.17-5** Referee page.
   **Expected:**
   - header "RecruitPro · Reference request" and a theme icon button;
   - heading "Can you confirm Asha's work?"; "This link works until …";
   - one row per claim with **Confirm** / **Partly** / **Can't confirm** / **Disagree** and **Add a comment**.
6. **7.17-6** **Send answers** with nothing chosen.
   **Expected:** "Choose an answer." and, without the tick, "Please confirm who you are."
7. **7.17-7** Choose **Confirm** for all three → tick "I confirm that I am the person this request was sent to…" →
   **Send answers**.
   **Expected:** **Thank you**.
8. **7.17-8** Reload the same link.
   **Expected:** "This request is closed" / "The link has expired, was cancelled, or the request was already
   answered…". This is HTTP **410**: DevTools → Network shows `GET …/public/references/<token>` → 410.
9. **7.17-9** Within seconds:
   - Mailpit: "Priya Manager responded to your reference request";
   - Asha's **Strengthen**: **Waiting for your review** → React, Python and the experience **Verified** (source
     Reference);
   - **Share** them.
10. **7.17-10** Variants. Send one new request per variant, answer it from Mailpit, and compare with 6.3:

    | Variant | Expected |
    |---|---|
    | Referee `ravi@demo-newco.example`, Manager, employer "Demo Newco", domain `demo-newco.example`, Confirm | **Partly verified** with notes `EMPLOYER_DOMAIN_UNPROVEN` and `EMPLOYER_DOMAIN_NEW` (lifted for Acme in 7.35-7) |
    | Referee `friend@gmail.com`, Manager, domain `demo-analytics.example`, Confirm | **Partly verified** |
    | Referee `peer@demo-analytics.example`, **Someone who reported to you**, Confirm | **Partly verified** |
    | Referee `boss@demo-analytics.example`, Manager, answer **Disagree** with comment "Not on my team" | **Contradicted**; the reason quotes the comment |
    | A request where the referee clicks **I would rather not answer** → **Decline** | "Request declined"; status **Declined** |

11. **7.17-11** **Cancel and 410:** send one more request → its menu **Cancel request** → "Cancel the request to …?" →
    **Cancel request**.
    **Expected:**
    - toast "Request cancelled" / "The link no longer works.";
    - the Mailpit link shows "This request is closed" (410).
12. **7.17-12** **Limits.** Asha has now sent 7 requests, and the cap is 10 per 24 h. Test both limits with
    **Casey** instead:
    1. Sign in as `candidate@demo.test`.
    2. Send 5 requests to `x1@demo-analytics.example` … `x5@…` and leave them unanswered.
    3. **Expected:** **References** "5 of 5 open". **Ask for a reference** → "You already have 5 open requests". The
       server answers 409 `REFERENCE_LIMIT`.
    4. Cancel requests and send new ones until the 11th in 24 h. **Expected:** HTTP 429 with `Retry-After` (DevTools →
       Network).

#### 7.18 [Candidate] Verdict review: share, hide, re-check

1. **7.18-1** Open a shared result → **Hide**.
   **Expected:**
   - toast "Hidden from employers";
   - status **Hidden**.

   Open it again → **Share with employers** (toast "Shared with employers").
2. **7.18-2** On the website TypeScript result → **Re-check**.
   **Expected:**
   - toast "Re-check started"; the chip shows "Checking…";
   - the old result becomes **Replaced by a newer check**;
   - the new one is **Awaiting your review**, because a re-check un-shares (a documented design gap).

   **Share** it again.
3. **7.18-3** **Run limit:** that re-check was Asha's 10th run in 24 h. Re-check any other result.
   **Expected:**
   - "You have reached the limit of 10 verifications in 24 hours…";
   - DevTools → Network: **429** with a `Retry-After` header (seconds until the oldest run leaves the window).

### Part IV: HR review

#### 7.19 [HR] Ranking: groups, keyboard triage, quick look, compare, Undo, bulk, URL views

As `hr@acme.test`, Window H → **Jobs** → **Backend Engineer (Java)** (tab **Applicants**).

1. **7.19-1** **Expected:**
   - toolbar views **All** / **To review** / **Shortlisted** / **Interviewing** / **More**;
   - order **Evidence ranking** / **Newest**;
   - **Copy link to this view**; **Re-run matching**;
   - columns **#**, **Applicant**, **Status**, **Evidence by requirement**, **Verified**, **With claims**.

   **Groups:**
   - **Ranked** ("Every gate requirement is verified…"): Casey Candidate (revealed) and **Candidate #A-<id>** (Arjun).
   - **Must-haves not yet verified** ("At least one gate is not verified yet. Not rejected…"): Meera's **Candidate
     #A-<id>** (no Java, no Spring Boot).
   - **Unscored**: anyone whose analysis is still running.
2. **7.19-2** Press **?**.
   **Expected:** sheet **Keyboard shortcuts** ("Shortcuts work when no text field has focus.") with groups **Move**,
   **Act** and **Anywhere**. Close it with **Esc**.
3. **7.19-3** Press **j**, **j**, **k**. **Expected:** the focused row moves down, down, up.
4. **7.19-4** Press **Space**.
   **Expected:**
   - drawer quick look "i of n" (URL `?peek=<id>`);
   - sections **Evidence by requirement** and **Also on file** (Claims, Work samples, Interviews, Resume notes);
   - footer **Shortlist**, **Not progressed**, **Open full review**.

   Press **j** inside it (next applicant), then **Space** to close.
5. **7.19-5** On Arjun press **x**, move to Meera and press **x**.
   **Expected:**
   - bulk bar "2 selected" with **Compare**, **Shortlist 2**, **Not progressed 2** and **Clear**;
   - press **c**: dialog "Compare 2 applicants" (URL `?compare=a,b`) with rows Requirement/Coverage and **Open** per
     column;
   - close it, then **Clear**.
6. **7.19-6** Undo window. On Meera press **s**.
   **Expected:**
   - dialog "Shortlist Candidate #A-<id>?" with confirm **Shortlist and reveal**; confirm;
   - toast "Shortlisting Candidate #A-<id>" / "Sending in 5 seconds." with **Undo**;
   - click **Undo** within 5 s: nothing changes (the status stays Submitted).
   - DevTools → Network shows **no** decision request.
7. **7.19-7** Press **r** on Meera.
   **Expected:**
   - dialog "Mark Candidate #A-<id> as not progressed?" with optional **Comment** and quick reasons;
   - **Not progressed** → toast "Marking … as not progressed" → let the 5 s pass → "… marked as not progressed";
   - status **Not progressed**; the row moves to the **More** → **Not progressed** view.
8. **7.19-8** Click **Copy link to this view** on view **Shortlisted** / order **Newest**.
   **Expected:**
   - the URL holds `?status=SHORTLISTED&sort=appliedAt`;
   - pasting it into a new tab opens the same view.
9. **7.19-9** **Re-run matching**. **Expected:** toast "Re-matching N applications"; the evidence strip refreshes.
10. **7.19-10** As `recruiter@acme.test` (sign in in a second profile, or sign out and in):
    - **Expected:** no **Re-run matching**;
    - **s** and **r** do nothing (managers only).

    Sign back in as `hr@acme.test`.

#### 7.20 [HR] Applicant page: evidence, claims, reveal, decisions, notes

1. **7.20-1** Open Arjun (**Enter** on his row).
   **Expected:**
   - tabs **Evidence**, **Claims**, **Work samples**, **Interviews**, **Verification**;
   - **Evidence at a glance**: "N of 5 requirements evidenced", "Ranked #… · every ranking gate is verified";
   - each requirement row shows **Why**, **Evidence** (a quote) and the tier **Resume**;
   - **Blind review** card; no name, email or **Download resume**.
2. **7.20-2** **Claims** tab.
   **Expected:**
   - "Docker, Kubernetes" (from the correction) and the Oracle certification;
   - "Link hidden until identity is revealed".
3. **7.20-3** On the Oracle claim → **Verify claim** → **Note (optional)** `Checked certificate number` → **Verify
   claim**.
   **Expected:**
   - toast "Claim corroborated";
   - the row says "Verified by a reviewer" with a **Change** disclosure.

   On "Docker, Kubernetes" → **Not verified** → **Mark not verified** (toast "Marked not verified").
4. **7.20-4** **Reveal identity**.
   **Expected:**
   - dialog "Reveal this candidate's identity?" listing what becomes visible and "Recorded in the audit log with your
     name and the time";
   - **Reveal identity** → toast "Identity revealed";
   - name "Arjun Test", email, **Download resume** and the cover letter are visible.
5. **7.20-5** **Download resume**. **Expected:** `resume-a.pdf` downloads.
6. **7.20-6** Rail **Review note**: type `Strong Spring Boot evidence; probe PostgreSQL` → **Ctrl+Enter**.
   **Expected:** toast "Review note saved"; **History** lists it.
7. **7.20-7** Decision bar: primary **Shortlist** → dialog "Shortlist this applicant?" (already revealed) →
   **Shortlist**.
   **Expected:**
   - toast "… shortlisted" with **Undo** (8 s); click **Undo**: toast "Undone: …"; status back to **Submitted**;
   - **Shortlist** again and let it stand.
   - Mailpit: "Update on your application for Backend Engineer (Java)" to Arjun.
8. **7.20-8** **More** → **Undo shortlist** → dialog "Undo the shortlist?" → empty comment → **Undo shortlist**.
   **Expected:** "Add a short reason. It is kept in the decision history."

   Cancel; leave Arjun **Shortlisted**.
9. **7.20-9** Open Meera (Not progressed) → primary **Reopen** → "Reopen this application?" → comment required →
   `Second look` → **Reopen application**.
   **Expected:** **Submitted**.

   Then **Reject** → dialog "Reject this application?":
   - field **Reason** with hint "…The candidate only sees "Not progressed"";
   - quick reasons;
   - → **Reject** → toast with **Undo** (8 s; let it pass);
   - Window C as Meera: status **Not progressed**.
10. **7.20-10** Press **]** and **[**. **Expected:** next / previous applicant.

    Press **2**. **Expected:** the second tab (Claims).
11. **7.20-11** As `recruiter@acme.test` open Arjun.
    **Expected:**
    - "HR managers make decisions. Your review note and verifications inform them.";
    - **Verify claim** works; no **Reveal identity**.

#### 7.21 [HR] Verification tab: blind vs revealed, confirm/overrule, tier lift

As `hr@acme.test` → **Frontend Engineer (React)** → Applicants → Asha (**Candidate #A-<id>**).

1. **7.21-1** **Evidence** tab.
   **Expected:**
   - React and TypeScript **Matched** with tier **Resume**;
   - **Python**: **Unclear**, with a note on missing depth (proficiency **Expert** needs ≥ 5 years, tier Demonstrated,
     or the word "expert");
   - **AWS** (also accepts Azure, GCP): **Not observed** — "Nothing found in the resume or claims. This does not mean
     the candidate lacks it.";
   - **Frontend development (2+ years)**: Matched.
2. **7.21-2** **Verification** tab (blind).
   **Expected:**
   - "Sources stay hidden until the identity is revealed.";
   - each result shows the chip (**Verified** / **Partly verified** / …) and "N evidence items · sources appear after
     identity is revealed";
   - reasons are de-identified;
   - filter **All** / **Needs review · N**;
   - results Asha **hid** do not appear.
3. **7.21-3** Header **Reveal identity** → confirm.
   **Expected:**
   - "Sources are shown because the identity is revealed.";
   - evidence items with URLs, titles and fingerprints, the employer domain chip, and Asha's note "Built the dashboard
     at work".
4. **7.21-4** Expand **React** (GitHub, Verified, "Ownership proven") → **Your organisation's review**.
   **Expected:** switcher **Confirm** / **Overrule**; hint "Confirming lifts this claim to Corroborated for your
   organisation only."

   **Confirm** → **Note** (optional) → **Save review**.
   **Expected:**
   - toast "Result confirmed" / "The claim rises to Corroborated for your organisation once matching finishes.";
   - row state **Confirmed**.
5. **7.21-5** Wait 5–10 s → **Evidence** tab.
   **Expected:** React's tier changes from **Resume** to **Corroborated**: the tier lift.
6. **7.21-6** On a **Partly verified** row → **Overrule** → **Save review** with an empty note.
   **Expected:** "Explain why you are overruling this result."

   Add a note → **Save review** → toast "Result overruled"; the tier does not rise.
7. **7.21-7** **Change review** on React → **Cancel**. **Expected:** nothing changes.
8. **7.21-8** Seeded alternative: **Backend Engineer (Java)** → Casey → **Verification**.
   **Expected:**
   - already revealed, so evidence is shown;
   - confirming React works, but Backend has no React requirement, so the Evidence tab does not change.
9. **7.21-9** As `interviewer@acme.test`, open an applicant URL directly
   (`http://localhost:5174/jobs/<id>/applicants/<id>`).
   **Expected:** "Not available for your role".

#### 7.22 [HR] Work samples on the job tab

As `hr@acme.test` → **Backend Engineer (Java)** → **Work samples**.

1. **7.22-1** **Expected:**
   - the seeded "Design a REST endpoint with Spring Boot" with status **Active**;
   - the assignments table with views **To score** / **In progress** / **Not started** / **Scored** / **All**;
   - Casey's row is **Scored**.
2. **7.22-2** **Generate from requirements**.
   **Expected:** "Generating…", then a new **Draft** sample.
3. **7.22-3** Edit it:
   - **Title** `Quick timer test`;
   - **Time limit** `5` (hint "Minutes, 5–480. Submissions are accepted for 5 minutes after the limit.");
   - → **Save work sample** → **Activate**.

   **Expected:** status **Active**.

#### 7.23 [HR + Candidate] Assign, take with timer and files, score, download, cancel

1. **7.23-1** Window H → Arjun (Shortlisted) → **Work samples** → **Assign work sample**.
   **Expected:**
   - dialog "Assign a work sample";
   - radio list with "60 minutes · 2 criteria" and "5 minutes · …";
   - **Start by** with quick picks **In 2 days**, **In 3 days**, **In 5 days**, **In 7 days** and **No deadline**.

   Choose the seeded 60-minute sample, **In 2 days** → **Assign**.
   **Expected:** toast "Work sample assigned"; Mailpit "A work sample is waiting for you: Design a REST endpoint with
   Spring Boot".
2. **7.23-2** Assign **Quick timer test** to Arjun as well.
3. **7.23-3** Window C as Arjun (`TestPass1234`) → **Work samples**.
   **Expected:**
   - **To do** lists both as **Not started**;
   - "Instructions appear when you press Start. The timer cannot be paused."
4. **7.23-4** Open **Quick timer test**.
   **Expected:** **Before you start** rules:
   - "…You then have 5 minutes…";
   - "You can attach up to 3 files (PDF, ZIP, DOCX, PPTX, PNG or JPG, 20 MB each)…";
   - "Responses sent more than 5 minutes after the time limit are not accepted.";
   - **Start, 5-minute timer begins**.
5. **7.23-5** Press it → dialog "Start the timer now?" → **Start 5-minute timer**.
   **Expected:**
   - **Instructions** appear;
   - the sticky timer counts down `4:59 left` with "Ends …", turns amber at 5:00 and pulses in the last minute.
6. **7.23-6** Leave it to run out (5 min).
   **Expected:**
   - label "Time is up, submit now";
   - alert "Time is up. Submit now: responses are accepted for up to 5 minutes after the time limit."
7. **7.23-7** Wait 5 more minutes. **Expected:** "Window closed" and "The submission window has closed…".

   Type text and press **Submit response** → **Submit response**.
   **Expected:**
   - "Your response was not accepted" / "The submission window closed and this work sample has expired…";
   - status **Expired**;
   - **Your unsent response** keeps the text.
8. **7.23-8** Open the 60-minute sample → **Start, 60-minute timer begins** → confirm.
   - **Response**: type two lines. **Expected:** hint "Draft saved in this browser at …".
   - **Link to your work (optional)**: `ftp://x` → **Submit response**. **Expected:** "Enter a full link starting with
     https:// or http://."
   - Change it to `https://github.com/example/orders`.
   - **Attach files**: drop `work-sample-answer.pdf`, `resume-a.docx` and a PNG. A 4th file gives "You can attach at
     most 3 files, so 1 file(s) were not added."; `not-a-resume.txt` gives "…only PDF, ZIP, DOCX, PPTX, PNG and JPG
     files can be attached."
9. **7.23-9** **Submit response** → "Submit your response?" / "You cannot edit it afterwards. 3 files will be
   attached." → **Submit response**.
   **Expected:**
   - toast "Response submitted";
   - "Submitted …";
   - **Attached files** with download buttons.
10. **7.23-10** Window H → Arjun → **Work samples** → **Submission**.
    **Expected:** text, link and 3 files with **Download**.

    **Download** one. **Expected:** toast "Downloaded <file>" / "Recorded in the audit log."
11. **7.23-11** Job tab **Work samples** → view **To score** → Arjun's row menu → **Download <name> (<size>)**.
    **Expected:** the same file downloads (the second place).
12. **7.23-12** Arjun → **Score with rubric**.
    **Expected:** drawer "Score: Design a REST endpoint with Spring Boot".

    Rate "REST design with Spring Boot" **Strong** (4) and "Data model and queries" **Solid** (3) → **Save scores**.
    **Expected:**
    - toast "Scores saved"; status **Scored**;
    - after a few seconds the **Evidence** tab shows Spring Boot and PostgreSQL with tier **Demonstrated**.
13. **7.23-13** **Cancel**:
    - assign the **Quick timer test** to Arjun again (a second assignment);
    - on the job tab row menu **Cancel assignment…** (or on Arjun's tab) → dialog "Cancel this assignment?";
    - empty **Reason** → "A reason is required to cancel."; type `Assigned by mistake` → **Cancel assignment**.

    **Expected:**
    - toast "Assignment cancelled";
    - Mailpit "Work sample cancelled: Quick timer test";
    - Window C: "The employer cancelled this work sample", "Reason given: Assigned by mistake".

#### 7.24 [HR] Interview kit, schedule (ICS), reschedule, cancel

1. **7.24-1** **Backend Engineer (Java)** → **Interview kit**.
   **Expected:**
   - 4 numbered questions with "Probes" requirement markers, follow-ups and a 1–4 anchor grid;
   - **Edit questions** opens the editor (**Add question**, **Add follow-up**, **Save kit**). Cancel.
2. **7.24-2** **Frontend Engineer (React)** → **Interview kit** → **Generate kit**.
   **Expected:** questions generated from its requirements.

   **Regenerate** asks "Regenerate the interview kit?".
3. **7.24-3** Arjun → **Interviews** (or the decision bar primary **Schedule interview**).
   **Expected:**
   - dialog "Schedule an interview" / "Times are in India Standard Time (UTC+5:30). The interviewer and the candidate
     are notified.";
   - fields **Interviewer** = Ivan Petrov, **Date and time** = tomorrow 10:00, **Duration** = 45 min, **Location or
     video link** = `https://meet.example.com/abc`;
   - → **Schedule**.

   **Expected:**
   - toast "Interview scheduled";
   - Arjun's status becomes **Interviewing**.
4. **7.24-4** Mailpit.
   **Expected:**
   - "Interview scheduled for Backend Engineer (Java)" (to Arjun);
   - "You are interviewing Arjun Test for Backend Engineer (Java)" (to Ivan; an unrevealed applicant would be named
     "Candidate #A-…");
   - each with an **`invite.ics`** attachment. Open it in Mailpit: `METHOD:REQUEST`, times in UTC.
5. **7.24-5** Row menu → **Reschedule** → +1 hour → **Save changes**.
   **Expected:** new mails whose ICS has the same `UID` and `SEQUENCE:1`.
6. **7.24-6** Schedule a second interview, then → **Cancel interview** → "Cancel this interview?" → confirm.
   **Expected:**
   - "Interview cancelled: Backend Engineer (Java)";
   - `cancel.ics` with `METHOD:CANCEL`.
7. **7.24-7** Window C as Arjun → **Interviews**.
   **Expected:**
   - the upcoming interview with **Add to calendar** (downloads `interview-<id>.ics`, made in the browser) and
     **Join** (opens the link; it becomes the primary button 15 minutes before the start);
   - the cancelled one under **Past and cancelled**, struck through.

#### 7.25 [HR interviewer] Scorecard submit and edit

1. **7.25-1** Window H: sign in as `interviewer@acme.test` → **My interviews**.
   **Expected:** groups **Waiting for your scorecard**, **Upcoming** (Arjun's) and **Completed and cancelled**.
2. **7.25-2** Open Arjun's interview.
   **Expected:**
   - header with **Join call**;
   - focus requirements;
   - each question card with follow-ups and the 1–4 rating ("Weak", "Partial", "Solid", "Strong");
   - keys **1**–**4** rate the focused question.

   Rate every question, **Overall recommendation** **Yes**, **Summary** `Clear answers` → **Submit scorecard** →
   "Submit your scorecard?" → confirm.
   **Expected:** toast "Scorecard submitted".

   (A future interview can be scored now; the server allows it.)
3. **7.25-3** Open the seeded **completed** interview with Casey → **Edit scorecard** → change one rating and the
   summary → **Save changes** → "Save your changes?" → confirm.
   **Expected:**
   - toast "Scorecard updated";
   - the read-only view says "· edited 1 time".
4. **7.25-4** Sign in as `hr@acme.test` → Casey's application → **Interviews**. **Expected:** the new version is shown.

#### 7.26 [HR] Select and the final state

1. **7.26-1** Arjun → decision bar primary **Select** → "Select this candidate?" with a "Last check" panel → **Select
   candidate**.
   **Expected:**
   - "Selected — handed off to your offer process.";
   - Mailpit update to Arjun;
   - Window C: status **Selected**, "The employer will contact you about next steps".
2. **7.26-2** As `interviewer@acme.test`, open Arjun's submitted scorecard.
   **Expected:** **Scorecard closed** (editing is refused after Selected, Rejected or Withdrawn).

#### 7.27 [Candidate] Withdraw

1. **7.27-1** Window C as Meera → **Applications** → Data Analyst → **More actions** → **Withdraw application…** →
   "Withdraw your application for Data Analyst?" → **Withdraw application**.
   **Expected:**
   - toast "Application withdrawn";
   - status **Withdrawn**, "You withdrew this application".

#### 7.28 [Both] Inbox

1. **7.28-1** Window C (any candidate) → bell / **Inbox**.
   **Expected:**
   - "N unread", groups **Today** / **Earlier**;
   - each unread row has a **Mark as read: …** button;
   - **Mark all as read** gives toast "All caught up" and "You are up to date".
2. **7.28-2** Window H as `hr@acme.test` → **Inbox**.
   **Expected:**
   - filter **All** / **Unread · N** (`?filter=unread`);
   - categories such as "New application" and "Work sample submitted";
   - **Mark all as read**.
3. **7.28-3** As `interviewer@acme.test` → **Inbox**. **Expected:** "Interview scheduled" items for Arjun's interview.

#### 7.29 [HR] Fairness report with a date range

1. **7.29-1** `hr@acme.test` → **Backend Engineer (Java)** → **Fairness**.
   **Expected:**
   - **Report period** **Last 30 days** / **Last 90 days** / **This year** / **All time** / **Custom**;
   - tiles **Scope**, **Survey response rate**, **Smallest group shown**, **Stages flagged**;
   - metrics **Selection rate** and **Impact ratio** (flag below 0.80);
   - with only 2 answers, groups are suppressed (fewer than 5): that is the privacy rule.
2. **7.29-2** **Custom** → **From** today, **To** today → **Apply**. **Expected:** the URL holds `?from=…&to=…`; the
   report recalculates.

   A To date before From is refused. **All time** resets.
3. **7.29-3** `admin@acme.test` → **Fairness** (Administration). **Expected:** the organisation-wide report with the
   same controls.

#### 7.30 [HR] Audit log and exports

1. **7.30-1** `admin@acme.test` → **Audit log**.
   **Expected:**
   - columns **When**, **Action**, **Actor**, **Target**, **Details**, **IP**;
   - entries for the reveal, decisions, claim verification, resume download, `VERDICT_REVIEWED`,
     work-sample file download and `SCORECARD_UPDATED`;
   - filter **Action**.
2. **7.30-2** Leave **From**/**To** at the last 30 days → **Export CSV**.
   **Expected:** `audit-YYYY-MM-DD.csv` downloads; toast "Exported audit-….csv".
3. **7.30-3** `hr@acme.test` → **Jobs** → Backend row ⋯ → **Export audit CSV**.
   **Expected:** `job-<id>-audit-YYYY-MM-DD.csv` with one row per decision.

### Part V: security, MFA and administration

#### 7.31 [HR] Staff MFA: enrol (QR or `totp.py`), sign in with a code, recovery code, regenerate, lockout, turn off

Use `recruiter@acme.test`, so a mistake cannot lock out the admin.

1. **7.31-1** Recruiter → account menu → **Two-step verification**.
   **Expected:**
   - "Two-step verification is off" / "You need an authenticator app…";
   - **Set up two-step verification**.
2. **7.31-2** Press it.
   **Expected:**
   - stepper **Scan / Confirm / Save codes**;
   - "Scan this code with your authenticator app" with a **QR code** ("It is drawn in your browser; nothing is sent
     anywhere.");
   - "Can't scan? Enter this setup key instead (time-based, 6 digits):" with **Setup key** in groups of four and
     **Copy Setup key**.
3. **7.31-3** Get a code.
   - **Either** scan the QR with Google/Microsoft Authenticator or 1Password,
   - **or**, without a phone, copy the setup key and run:
     ```powershell
     python tools\totp.py "PASTE SETUP KEY"      # spaces are ignored
     ```
     **Expected:** `123456  (valid for about NN more seconds)`.
4. **7.31-4** **Enter the 6-digit code the app shows**.
   - A wrong code + **Turn on** → "That code did not match. Check the time on your phone and try the newest code."
   - The right code → **Turn on**.

   **Expected:**
   - toast "Two-step verification is on";
   - "Two-step verification is on. Save your recovery codes." with **10 codes**, **Download .txt** (saves
     `recruitpro-recovery-codes.txt`) and **Copy all**;
   - **Done** is disabled until you tick "I have saved these codes somewhere safe".

   Save the codes, tick → **Done**.
5. **7.31-5** **Expected:**
   - "Two-step verification is on", "Turned on …";
   - meter **Recovery codes left** "10 of 10";
   - **Create new recovery codes**.
6. **7.31-6** Sign out → sign in as recruiter with the password.
   **Expected:**
   - **Enter your code**, field **6-digit code** (six boxes);
   - "Expires in m:ss";
   - links **Use a recovery code instead** and **Back to sign in**.
7. **7.31-7** Paste a fresh code (`totp.py` again, or wait for the next 30 s window if you just used one).
   **Expected:** it **submits by itself** after the 6th digit and you are signed in. "123 456" with a space also works.
8. **7.31-8** Sign out → sign in → **Use a recovery code instead** → **Recovery code** (hint "Spaces and dashes are
   ignored.") → paste one → **Verify and sign in**.
   **Expected:**
   - signed in; **Recovery codes left** "9 of 10";
   - the same code a second time fails: "That recovery code did not work. Check it, or use another one."
9. **7.31-9** **Create new recovery codes**.
   **Expected:**
   - dialog "Create new recovery codes?" / "…Confirm with a code from your authenticator app.";
   - field **6-digit code from your app**;
   - a wrong code → "That code did not match…"; the right code → **Create new codes** → "Your new recovery codes"
     (10). The old ones no longer work.
10. **7.31-10** **Lockout:** sign out → sign in with the password → enter **5 wrong codes** (wait for each error).
    **Expected:**
    - after the 5th: back on the password step with "Too many wrong codes. For your security sign-in is paused for
      about 15 minutes; then enter your password again.";
    - DevTools → Network: `POST /api/v1/auth/mfa/verify` → **429** with `Retry-After: ~900`, body `MFA_LOCKED: …`;
    - for 15 minutes even the right password answers "That email and password do not match an account, or the account
      is locked…".
11. **7.31-11** Unlock now instead of waiting:
    ```powershell
    docker compose exec postgres psql -U recruitpro -d recruitpro -c "update users set locked_until = null, failed_login_count = 0 where email = 'recruiter@acme.test';"
    ```
    **Expected:** `UPDATE 1`. Sign in with password + code works again.
12. **7.31-12** Turn off: **Turn off two-step verification** panel → **Password** + **Code from your app, or a
    recovery code** → **Turn off**.
    - Either field empty → "Enter your password and a current code (or a recovery code)."
    - **Expected:** toast "Two-step verification is off".

#### 7.32 [HR admin] Organisation settings: require MFA, SSO domains, blind review

1. **7.32-1** `admin@acme.test` → **Organisation**.
   **Expected:** sections:
   - **Name** (hint "Shown to candidates. Identifier: acme-labs");
   - **Review**: switches **Blind review** and **Share verification status with candidates**;
   - **Security**: **Require two-step verification for all staff**;
   - **Single sign-on**: **Allowed email domains**, hint "Exact domains, no wildcards. At most 20.".
2. **7.32-2** **Allowed email domains**:
   - type `https://www.Acme.test/path` + Enter. **Expected:** the chip shows `acme.test` (normalised);
   - `*.acme.test` → "…is not a domain name such as example.com.";
   - `co.uk` → "…is a public suffix; enter the organisation's own domain.".

   **Expected:**
   - the save bar "1 unsaved change · changes are audited" with **Discard** / **Save changes**;
   - **Save changes** → toast "Organisation settings saved" / "Every change is recorded in the audit log.";
   - the login page still shows **no** SSO buttons (no provider configured; see 9.2).
3. **7.32-3** Switch **Blind review** off → **Save changes**.
   **Expected:**
   - confirm "Turn off blind review?" → **Turn off blind review**;
   - a new application now shows the name at once.

   Turn it **back on** and save.
4. **7.32-4** Switch **Require two-step verification for all staff** on → **Save changes**. Keep this admin window
   signed in: open sessions are not ended.
5. **7.32-5** In a second browser profile (or Window C, which is a different cookie jar), sign in to the HR portal as
   `interviewer@acme.test`.
   **Expected:**
   - forced enrolment starts by itself: overline "Required by your organisation", "Set up two-step verification",
     stepper **Scan / Confirm / Save codes**;
   - a QR code (or "Can't scan? Enter a setup key");
   - **Enter the 6-digit code the app shows** (`tools\totp.py`) → **Confirm and continue**;
   - "Save your recovery codes" → tick → **Continue to workspace**.
6. **7.32-6** Leave **Require two-step verification** **on** for 7.33.

#### 7.33 [HR admin] Staff accounts, invitations, accept-invite (with MFA required)

1. **7.33-1** **Staff accounts**.
   **Expected:**
   - "4 people · An organisation always keeps at least one enabled administrator";
   - search "Search name or email", filters **All roles** and **Any status**;
   - columns Name (your row " (you)"), Role, Status, Joined.
2. **7.33-2** On Riley Chen change **Role** to **HR manager**.
   **Expected:**
   - "Make Riley Chen HR manager?" → **Change role** → toast "Riley Chen is now HR manager" / "Recorded in the audit
     log.";
   - change it back to **Recruiter**.

   Your own row cannot be changed (tooltip "You cannot change your own role.").
3. **7.33-3** **Disable** on Riley → "Disable Riley Chen?" → **Disable**.
   **Expected:** a recruiter sign-in now fails (401 message).

   **Enable** again.
4. **7.33-4** **Invite staff**.
   **Expected:**
   - drawer "Invite staff" / "The person gets a link valid for 7 days. It works once.";
   - **Work email** `newhr@acme.test`, **Role** card **HR manager** → **Send invitation**.

   **Expected:**
   - "Invitation sent to newhr@acme.test";
   - copyable **Invitation link (development setups only)**;
   - Mailpit "You are invited to join Acme Labs on RecruitPro".

   **Done**.
5. **7.33-5** **Invitations**.
   **Expected:**
   - "1 pending invitation · Links are valid for 7 days and work once";
   - **Pending · 1** / **All · N**;
   - row status **Pending** with **Revoke**. There is **no Resend**: inviting the same email again replaces the pending
     invitation.
6. **7.33-6** Open the Mailpit link (`http://localhost:5174/accept-invite?token=…`) in a signed-out browser.
   **Expected:**
   - "Join Acme Labs" with facts Organisation, Your role, Email and Expires;
   - fields **First name** `New`, **Last name** `Manager`, **Password** `TestPass123` (checklist "At least 10
     characters", "A letter", "A digit") → **Join workspace**.
7. **7.33-7** Because MFA is required: the enrolment step ("Required by your organisation", QR) → code → **Confirm and
   continue** → recovery codes → **Continue to workspace**.
   **Expected:** signed in as HR manager on **Jobs**.
8. **7.33-8** Open the same invitation link again.
   **Expected:** "This invitation cannot be used" / "It may have expired (links last 7 days), already been used, or
   been revoked…".
9. **7.33-9** Invite `revoke.me@acme.test` → **Invitations** → **Revoke** → "Revoke the invitation for
   revoke.me@acme.test?" → **Revoke invitation**.
   **Expected:**
   - toast "Invitation revoked";
   - its Mailpit link shows "This invitation cannot be used".
10. **7.33-10** Clean up:
    - **Organisation** → switch **Require two-step verification** off → **Save changes**;
    - the interviewer and newhr may turn MFA off under **Two-step verification**. They can now: while the organisation
      required it, the page said "Your organisation requires two-step verification, so it cannot be turned off here."

#### 7.34 [HR admin] Job oversight and closing a job

1. **7.34-1** As `hr@acme.test` create and publish a throw-away job `Close me` (Basics, one requirement, **Publish**).
2. **7.34-2** `admin@acme.test` → **Job oversight**.
   **Expected:**
   - "N jobs · Overrides need a reason and are audited";
   - columns Job, Owner, Status, Applications, Created;
   - row action **Override status**.
3. **7.34-3** On "Close me" → **Override status**.
   **Expected:**
   - "Override the status of "Close me"?";
   - **New status**: On hold or Closed;
   - **Reason** required ("A reason is required for an override.").

   **Closed** + `Test close` → **Override status**.
   **Expected:**
   - toast ""Close me" is now Closed";
   - the row action becomes **Final**.
4. **7.34-4** As `hr@acme.test`, a Published job's ⋯ menu offers **Close job** → "Close "…"?" / "It stops taking
   applications and cannot be reopened…". You do not need to confirm it.

#### 7.35 [HR admin] Verification administration

1. **7.35-1** `admin@acme.test` → **Verification**.
   **Expected:**
   - "Agents check candidates' claims…";
   - mode block **Visible mode** — "Reviewers see results candidates shared. A verified result raises the tier only
     after a reviewer confirms it." (plus "Changed … by …" once someone has switched the mode);
   - pill "{n} of 3 targets met";
   - **Switch to shadow**;
   - tabs **Readiness**, **Labelling queue** (with a count), **Connectors** and **Trusted domains**.
2. **7.35-2** **Readiness**.
   **Expected:**
   - meters "Labelled results" ("N / 150", target "at least 150"), "False corroboration" (target "at most 2% · lower is
     better") and "Agreement beyond chance (κ)" (target "at least 0.70"), each with "Met" or "Not yet";
   - tables **By verifier** and **By verdict**, or "No labels yet".
3. **7.35-3** **Labelling queue**.
   **Expected:**
   - "N shared results · unlabelled first…" (Casey's and Asha's shared results);
   - the detail shows "Agent said" with the chip;
   - **Your verdict** is **Verified** / **Partly** / **Not found** / **Contradicted** / **Not checkable** (keys **1**–**5**).

   Press **1** → **Note (optional)** → **Save and next** (or **Enter**). **Expected:** the item shows "You: Verified".

   **J** / **K** move. Saving without a choice gives "Choose what you think the right verdict is."
4. **7.35-4** **Switch to shadow** → "Switch back to shadow mode?" → **Switch to shadow**.
   **Expected:** toast "Back in shadow mode".

   `hr@acme.test` → Asha → **Verification** shows the alert **Shadow mode**, no results, and after re-matching React's
   tier drops back to **Resume**.
5. **7.35-5** **Switch to visible**. **Expected:** "Switch to visible before the targets are met?" (danger) → **Switch
   to visible anyway** → toast "Verification is visible".
6. **7.35-6** **Connectors**: the switches GitHub, GitLab, Website, Document, Open Badge, Reference.
   - Turn **Website** off → **Save changes** → toast "Verification settings saved".
   - **Expected:** Asha's website results disappear from HR's Verification tab and the queue.
   - Turn it back on and save.
7. **7.35-7** **Trusted domains** → **Trusted employer domains** → **Employer domains** `demo-newco.example` → **Add**
   → **Save changes**.
   **Expected:**
   - in HR → Asha → **Verification**, the Demo Newco reference result counts as **Verified** for Acme only;
   - the domain chip says trusted by your organisation.

   **Trusted badge issuers**: add `badges.university.edu` and save. Invalid input `not a domain` → "…is not a domain
   name such as example.com".
8. **7.35-8** As `hr@acme.test` → **Verification** (Administration).
   **Expected:**
   - "Mode is managed by an administrator";
   - only **Readiness** and **Labelling queue** (no Connectors or Trusted domains).

#### 7.36 [HR admin] Dashboard health when services stop

1. **7.36-1** `docker compose stop agent-service`. Wait 20 s (health is cached for 15 s) → **Overview** (or wait for
   the 1-minute refresh).
   **Expected:**
   - **Verification agents** **Down** — "Verification runs wait until it is back; candidates see "Checking".";
   - "1 thing needs attention."
2. **7.36-2** `docker compose stop ai-service`.
   **Expected:** **AI service** **Down** — "Resume analysis and matching wait in the queue; nothing is lost."
3. **7.36-3** While the AI service is down, upload a resume as any candidate. **Expected:** it stays **Queued for
   analysis**.
4. **7.36-4** `docker compose start ai-service agent-service`.
   **Expected:**
   - both **Up** within a minute;
   - the queued resume finishes by itself (retries with back-off, up to a few minutes).

#### 7.37 [HR] Change password

1. **7.37-1** `hr@acme.test` → account menu → **Change password**.
   **Expected:** fields **Current password**, **New password** (checklist) and **Confirm new password**.
   - A wrong current password → "That is not your current password."
   - The same as current → "Choose a password that differs from the current one."
2. **7.37-2** Change it to `ChangeMe-1234!`.
   **Expected:** "Password changed" / "Other sessions were signed out. This one stays signed in."

   Change it back to `ChangeMe-123!` the same way.

#### 7.38 [HR] New organisation via sign-up, first-run checklist, isolation

1. **7.38-1** Sign out → **Create a workspace** (`/signup`).
   **Expected:**
   - "Create your workspace";
   - fields **Organisation name** (hint "Candidates see this name on your jobs."), **First name**, **Last name**,
     **Work email** and **Password**.

   Enter `Test Org 2`, `Olive`, `Owner`, `owner@org2.example`, `TestPass123` → **Create workspace**.
   **Expected:** you land on `/welcome`.
2. **7.38-2** **Welcome to RecruitPro**.
   **Expected:**
   - meter "Set-up progress", "1 of 7 done";
   - steps:
     1. **Create your workspace** — done
     2. **Confirm your email** — **Send the link again**
     3. **Turn on two-step verification** — **Set up**
     4. **Check how reviews work** — **Looks right** / **Change**
     5. **Invite your team** — **Invite people**
     6. **Create your first job** — **New job**
     7. **Publish it** — **Open drafts**
   - the next open step is tagged **Next**;
   - sidebar **Get started** with "N steps left".
3. **7.38-3** Mailpit → "Confirm your email address for RecruitPro" for owner@org2.example → link
   `http://localhost:5174/verify-email?token=…`.
   **Expected:**
   - the HR portal confirms it;
   - **Welcome** shows step 2 done.
4. **7.38-3b** **Check how reviews work** → **Looks right**. **Expected:** done (stored per browser).
5. **7.38-4** **Hide this checklist**.
   **Expected:**
   - toast "Checklist hidden" / "You can find it again at /welcome.";
   - **Get started** disappears from the sidebar;
   - `/welcome` → **Show it in the sidebar again**.
6. **7.38-5** **Jobs**. **Expected:** an empty list ("Create your first job"), no Acme data.
7. **7.38-6** Tenant isolation: see 8.4.
8. **7.38-7** Sign-up from the same IP is limited to **3 per hour**. A 4th gives a 429.

### Part VI: candidate account and UI

#### 7.39 [Candidate] Profile and password

1. **7.39-1** As Asha → account menu → **Profile**.
   **Expected:**
   - "Recruiters see these details only after they shortlist you. We never ask for your date of birth or gender.";
   - badge **Email confirmed** (or **not**);
   - sections **Work**, **Expectations**, **Location**, **Links**, **About you**, **Password**.
2. **7.39-2** **Years of experience** `5`, **City** `Pune`, **LinkedIn** `javascript:x`.
   **Expected:** a link error; fix it to `https://linkedin.com/in/asha-demo`.

   Save bar "You have unsaved changes", "Ctrl + S saves" → **Ctrl+S**. **Expected:** toast "Profile saved".
3. **7.39-3** **Years of experience** `200`. **Expected:** "Enter a number from 0 to 100." → **Discard**.
4. **7.39-4** **Password**: current `TestPass123`, new `TestPass1234` twice → **Change password**.
   **Expected:** toast "Password changed" / "You were signed out on your other devices."

#### 7.40 [Candidate] Privacy: export and delete (use Meera)

1. **7.40-1** As Meera → **Privacy and data** → **Download your data** → **Download my data**.
   **Expected:**
   - `recruitpro-my-data-YYYY-MM-DD.json` downloads; toast "Your data export was downloaded";
   - the file holds profile, resumes, applications, claims, survey and verification sections, with no tokens and no
     storage paths.
2. **7.40-2** **Delete my answers** (survey) → "Delete your survey answers?" → **Delete answers**.
3. **7.40-3** **Delete your account** → **Your password** `wrong-pass-123` → **Delete my account…** → confirm.
   **Expected:** refused (an error shown; the account stays).
4. **7.40-4** The right password `TestPass123` → **Delete my account…** → "Delete your account permanently?" →
   **Delete my account**.
   **Expected:**
   - `/login` with "Your account has been deleted. Thank you for using RecruitPro.";
   - signing in again fails.
5. **7.40-5** `hr@acme.test` → Backend Engineer (Java) → Meera's application.
   **Expected:** still listed, anonymised.

#### 7.41 [Both] Theme, mobile view, style guide

1. **7.41-1** Signed in on either portal: account menu → **Theme** → **Dark**.
   **Expected:**
   - the whole UI switches at once and survives F5 (`localStorage['theme-preference']`);
   - **Light** switches back;
   - **System** follows Windows (Settings → Personalisation → Colours) and removes the key.
2. **7.41-2** On a sign-in page: the single icon button "Switch to dark theme" / "Switch to light theme". The referee
   page has the same button.
3. **7.41-3** Mobile, candidate: DevTools → **Toggle device toolbar** (Ctrl+Shift+M) → iPhone 12 Pro (390 px).
   **Expected:**
   - no horizontal scrolling;
   - a **Menu** button opens the slide-out **Navigation** with **Close menu**;
   - **Find jobs** shows only the title search plus a **Filters** button (panel with **Reset** / **Show N roles**);
   - a job page has a sticky bottom bar with salary and **Apply**.
4. **7.41-4** Mobile, HR at 390 px.
   **Expected:**
   - **Menu** button;
   - an applicant page shows the tab **Identity & notes** below 1200 px.
5. **7.41-5** Style guide: http://localhost:5173/__design and http://localhost:5174/__design. No sign-in is needed;
   the route is public and present in production builds.
   **Expected:**
   - page **Design system** with sections Foundations, Actions, Forms, Navigation, Evidence, Data, Feedback, Overlays
     and Brand;
   - **Brand presets** shows neutral / plumbline / assay / gradus side by side;
   - works in light and dark.
6. **7.41-6** Name options: open `docs\design\name-options.html` directly in the browser (a static file, not an app
   route).
7. **7.41-7** Not found: http://localhost:5173/nope (signed in).
   **Expected:**
   - "Page not found";
   - "There is nothing at this address";
   - **Go to your overview**.

   HR http://localhost:5174/nope → "Page not found".

---

## 8. Negative and security tests by hand

These use `curl.exe`, which ships with Windows. JSON bodies go into a file, because PowerShell 5.1 mangles inner
quotes passed to native programs.

### 8.1 Wrong-portal sign-in (API level)

```powershell
'{"email":"hr@acme.test","password":"ChangeMe-123!"}' | Set-Content -Encoding ascii $env:TEMP\hr.json
curl.exe -s -w "`nHTTP %{http_code}`n" -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -H "X-RecruitPro-Portal: candidate" --data-binary "@$env:TEMP\hr.json"
```

**Expected:** `HTTP 403`, with a message containing "This is the candidate portal. Staff accounts sign in to the
RecruitPro HR portal." No cookie is set. With `X-RecruitPro-Portal: staff` you get HTTP 200 and an `accessToken`.

### 8.2 Rate limit: 429 with `Retry-After`

Use an email that does not exist, so no real account gets locked. Sign-in is limited to 10 per minute per IP.

```powershell
'{"email":"nobody@example.com","password":"wrong-password-1"}' | Set-Content -Encoding ascii $env:TEMP\bad.json
1..12 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code} retry-after=%header{retry-after}`n" -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -H "X-RecruitPro-Portal: candidate" --data-binary "@$env:TEMP\bad.json" }
```

**Expected:**
- about ten lines `401 retry-after=`;
- then `429 retry-after=NN` (seconds, ≤ 60).

For about a minute every sign-in from this machine gets "Too many attempts. Please wait a minute and try again." plus
"You can try again in N seconds." in the candidate portal. If your curl is older than 7.84 and prints the literal
`%header{…}`, add `-i` to one call and read the `Retry-After` header.

**Account lockout** (separate from the IP limit):
- Five wrong passwords on one real account lock it for 15 minutes.
- Locked sign-ins answer **401** like a wrong password, by design: a locked account is indistinguishable.
- Unlock it with the `psql` line in 7.31-11 (change the email).

### 8.3 Blind-first leakage checks

1. **8.3-1** As `hr@acme.test`, open a not-yet-revealed applicant (e.g. a new application to the Frontend job). In
   DevTools → **Network**, select the request `…/hr/jobs/<jobId>/applicants/<applicationId>` → **Response**.
   **Expected:**
   - no first name, last name, email, phone, resume file name or cover letter;
   - `displayName` is `Candidate #A-<id>`.
2. **8.3-2** **Verification** tab before reveal: the responses hold no evidence URLs, titles or employer domains; the
   UI says "sources appear after identity is revealed".
3. **8.3-3** **Claims** tab before reveal: "Link hidden until identity is revealed".
4. **8.3-4** Interview mail for an unrevealed applicant names "Candidate #A-…" (subject "You are interviewing
   Candidate #A-… for …" and inside the ICS).
5. **8.3-5** Asha's **hidden** results never appear in HR, before or after reveal.

### 8.4 Another organisation cannot see Acme's data

1. **8.4-1** As `hr@acme.test`, open **Backend Engineer (Java)** and copy the URL (e.g.
   `http://localhost:5174/jobs/1`). Also copy an applicant URL.
2. **8.4-2** Sign in as `owner@org2.example` (7.38) and paste both URLs.
   **Expected:**
   - "Job not found" / "This job does not exist, or it belongs to another organisation";
   - the applicant page shows an error;
   - DevTools: **404**, never 403 or data.
3. **8.4-3** Org 2 → **Audit log**, **Staff accounts**, **Fairness**: only Org 2's own rows.
4. **8.4-4** Org 2 → **Verification** → **Labelling queue**: "Nothing to label" (no candidate applied to Org 2).

### 8.5 `javascript:` and other unsafe links

1. **8.5-1** Candidate **Claims** evidence link and work-sample link: `javascript:` is refused in the UI (7.9-8,
   7.23-8).
2. **8.5-2** Bypass the UI and send the claim straight to the API. The server refuses it too:
   1. Window C: DevTools → Network → any API request → copy the `Authorization: Bearer …` value.
   2. Run:
      ```powershell
      $t = "PASTE_THE_TOKEN_AFTER_Bearer"
      '{"claimType":"SKILL","value":"XSS test","evidenceUrl":"javascript:alert(1)"}' | Set-Content -Encoding ascii $env:TEMP\claim.json
      curl.exe -s -w "`nHTTP %{http_code}`n" -X POST http://localhost:8080/api/v1/candidates/me/claims -H "Authorization: Bearer $t" -H "Content-Type: application/json" --data-binary "@$env:TEMP\claim.json"
      ```

   **Expected:** `HTTP 400`, mentioning "must be an http or https URL". The access token lives 15 minutes.
3. **8.5-3** In HR, evidence and provenance links render as links only for `http:`/`https:` (`ProvenanceLine`).

### 8.6 Expired, cancelled or answered referee link (410)

Done in 7.17-8 and 7.17-11. **Expected** both times: "This request is closed", with HTTP 410 in the Network tab. A
made-up token (`http://localhost:5173/reference/abc`) gives "This link is not valid" (404).

### 8.7 OAuth not configured

- Done in 7.12-4: the candidate sees "Signing in with GitHub is not available on this server…".
- API: the start call `GET /api/v1/candidates/me/verification/oauth/github/start` answers **503** `OAUTH_NOT_CONFIGURED`.
- HR: no SSO buttons (7.1-1). `Invoke-RestMethod http://localhost:8080/api/v1/auth/sso/providers | ConvertTo-Json`
  returns an empty list in `data`.

### 8.8 Business caps answer 409, not 429

- 6th open reference request → 409 `REFERENCE_LIMIT`.
- 31st active source → 409 `SOURCE_LIMIT`.
- A duplicate source → 409 "already connected".
- Rate caps (runs, references per day, ownership checks, OAuth starts) → 429 with `Retry-After`.

### 8.9 Session checks

- Signing in on the candidate portal does not sign you out of the HR portal: separate cookies per portal.
- **Change password** signs out other sessions. Check with a second browser: its next action returns you to sign-in
  with "Your session has ended. Please sign in again."
- The absolute 30-day lifetime cannot be tested by hand quickly. Its unit tests run in 4.1.

---

## 9. Optional real integrations

None is needed for the tests above, and **none has been tested against a real provider yet**. After editing `.env`,
run `docker compose up -d` (it recreates the affected containers).

### 9.1 GitHub OAuth App ("Continue with GitHub" for candidates)

1. GitHub → **Settings** → **Developer settings** → **OAuth Apps** → **New OAuth App**:
   - Application name `RecruitPro local`;
   - Homepage URL `http://localhost:5173`;
   - **Authorization callback URL `http://localhost:8080/api/v1/verification/oauth/github/callback`**.
2. **Register application** → copy the **Client ID** → **Generate a new client secret**.
3. `.env`:
   ```
   GITHUB_CLIENT_ID=<client id>
   GITHUB_CLIENT_SECRET=<client secret>
   GITHUB_OAUTH_REDIRECT_URI=http://localhost:8080/api/v1/verification/oauth/github/callback
   AGENT_GITHUB_TOKEN=<optional personal access token, avoids the 60 requests/hour limit>
   ```
   `APP_ENCRYPTION_KEY` must be valid; the dev default is.
4. `docker compose up -d backend agent-service`
5. **Verify:**
   - Candidate → **Add a source** → GitHub → consent → **Continue with GitHub** → authorise on GitHub (scope
     `read:user`).
   - **Expected:** back on `http://localhost:5173/verification?connected=github` with "GitHub connected" / "GitHub is
     connected and ownership is proven…". The source shows "Ownership proven · GitHub sign-in".
   - A real login is not `demo-`, so the run reads the real GitHub API.
   - Finish in the **same browser**; another browser ends with `?error=BROWSER_MISMATCH`.

### 9.2 Staff SSO (Google, Microsoft Entra, generic OIDC)

1. Register a **Web** application with the provider. Redirect URI:
   `http://localhost:8080/api/v1/auth/sso/<google|microsoft|oidc>/callback`.
2. `.env`:
   - `SSO_GOOGLE_CLIENT_ID` and `SSO_GOOGLE_CLIENT_SECRET`;
   - or `SSO_MICROSOFT_CLIENT_ID`, `SSO_MICROSOFT_CLIENT_SECRET` and `SSO_MICROSOFT_TENANT_ID`;
   - or `SSO_OIDC_NAME`, `SSO_OIDC_CLIENT_ID`, `SSO_OIDC_CLIENT_SECRET` and `SSO_OIDC_ISSUER`;
   - keep `SSO_REDIRECT_BASE_URL=http://localhost:8080`.
3. `docker compose up -d backend`
4. Admin → **Organisation** → **Allowed email domains** → your company domain → **Save changes**. The staff account
   must already exist with that exact email (invite it): there is no auto-provisioning.
5. **Verify:**
   - the HR login shows an "or" divider and **Continue with Google** (or the provider's name);
   - after the provider you land on `/sso/complete` and then the workspace;
   - errors come back as `/login?ssoError=<code>` under "Single sign-on did not complete" (for example a domain not
     allowed or no matching account).

### 9.3 LLM providers (optional summaries and suggestions; verdicts and matching never depend on them)

`.env`:

```
LLM_PROVIDER=anthropic            # or openai_compatible (then OPENAI_BASE_URL and OPENAI_API_KEY)
AGENT_LLM_PROVIDER=anthropic      # optional, independent
LLM_MODEL=<model id>              # required when a provider is set, or the service refuses to start
ANTHROPIC_API_KEY=<key>
COMPOSE_FILE=docker-compose.yml;docker-compose.ai-egress.yml   # Windows separator ";" (Linux/macOS ":")
```

`COMPOSE_FILE` makes every later `docker compose` command include the egress override. Without it, a plain `up`
recreates the AI service with no internet.

```powershell
docker compose up -d ai-service agent-service backend
```

**Verify:**
- the AI service `/health` (`docker compose exec` line in 5.2) and http://localhost:8100/health show
  `"llmProvider":"anthropic"`;
- in the job wizard the suggestion subline reads "Drafted by the assistant and checked against your text".

---

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `mvnw verify` fails | Send the first `[ERROR]` lines and `backend-build.log` (4.1, 11). |
| `.\mvnw.cmd` behaves oddly, or "The system cannot find the batch label" | `mvnw.cmd` has LF line endings in this working tree. Recreate it (2.1). |
| `./mvnw: /bin/sh^M: bad interpreter` (Git Bash/WSL) | `mvnw` has CRLF. `Remove-Item backend\mvnw; git checkout -- backend/mvnw` |
| `dependency failed to start: container recruitpro-agent-service-1 is unhealthy` / agent logs `AGENT_SERVICE_TOKEN must be a random secret… (not the .env.example placeholder)` | You copied `.env.example` unchanged. Fix or delete the `replace-with-…` lines (3), then `docker compose up -d`. |
| `…ai-service-1 is unhealthy` | `docker compose logs ai-service`. A provider set without `LLM_MODEL` stops it on purpose (9.3). |
| `port is already allocated` | `netstat -ano \| findstr :8080` (or the port), then stop that PID: `Stop-Process -Id <PID>` |
| Build killed, `exit code 137`, or extremely slow | Docker memory: `.wslconfig` `memory=10GB` (1), `wsl --shutdown`, restart Docker Desktop. |
| First build takes > 30 min | Normal on slow networks (LibreOffice and onnxruntime downloads). Later builds use the cache. |
| `no space left on device` | `docker system df`; free space with `docker builder prune` (keeps images) or `docker system prune` (careful). |
| Docker says WSL is missing | `wsl --install` in an admin PowerShell, reboot, enable WSL 2 in Docker Desktop. |
| Backend exits: `app.encryption.key must be Base64 encoded` / `must decode to exactly 32 bytes` | Bad `APP_ENCRYPTION_KEY`. Empty it or generate one (3). |
| Backend exits: Flyway "validate failed" / "checksum mismatch" / "not resolved locally" | The database came from another branch or version. `docker compose down -v`, then `up`. |
| Signed out right after a restart, "Your session has ended", or both portals loop to sign-in | Stale refresh cookie (after `down -v`). Sign in again. If it persists: DevTools → Application → **Clear site data** for `localhost`. |
| Portal loads but every action fails, or CORS errors in the console | Backend not ready (wait for `Started RecruitProApplication`), or you used `127.0.0.1`. Use exactly `http://localhost:5173` and `:5174`. |
| Runs end failed with `AGENT_SERVICE_REJECTED` while the dashboard says agents Up | **Token mismatch**: the backend's `AGENT_SERVICE_TOKEN` differs from the agent's. Set it once in `.env` (compose passes it to both), then `docker compose up -d backend agent-service`. The same applies to `AI_SERVICE_TOKEN` (resumes stuck or failing). |
| AI service or PostgreSQL "not reachable" from the host | By design: they have no host port. Use the `docker compose exec` lines (5.2). For host access to PostgreSQL, add a `docker-compose.override.yml` (README "Quick start"). |
| `demo-asha` gives "Not found" or `SOURCE_NOT_FOUND` | `http://localhost:8100/health` must show `"demoFixtures":true`. Only `demo-asha`, `demo-ravi` and the hosts in 6.3 exist. |
| Website results only Partly verified / badge Contradicted | The candidate is not Asha Synthetica / `asha.demo@example.com`. That is correct behaviour (6.3). |
| "Code on page" never proves on `https://demo-portfolio.example` | Expected: use DNS or Text file, or the `/verify` page (6.3, 7.14). |
| 429 on **Run the first check** / **Check now** / references | Limits: 10 runs per 24 h per candidate, 20 ownership checks per hour, 10 reference requests per 24 h. Use another candidate, or `docker compose down -v`. |
| HR Verification tab says **Shadow mode** | Admin → Verification → **Switch to visible**. |
| Verification tab shows no evidence | The applicant is not revealed yet (7.21-3). |
| No **Corroborated** tier after Confirm | The job has no such requirement, the verdict was not shared or not Verified, ownership not proven, the review was Overrule, or matching has not finished (wait 10 s). |
| MFA code rejected | The Windows clock is off (Settings → Time → **Sync now**), or a code was reused (wait 30 s). |
| Locked out after MFA tests | 7.31-11 `psql` unlock. |
| No email | Mailpit http://localhost:8025. The sender polls every 5 s. `docker compose logs backend \| Select-String Notification` |
| Resume stays "Queued for analysis" | `docker compose ps ai-service`; `docker compose logs ai-service`. It retries by itself. |
| `pnpm` not found | `npm install -g pnpm@10`, or use `npx pnpm@10 …` (1). |
| `pnpm install` "ERR_PNPM_OUTDATED_LOCKFILE" | Use pnpm **10** and `--frozen-lockfile`. Do not update the lockfile. |
| pnpm "Ignored build scripts" warning | Harmless for these portals; the build works (checked). |
| Python `Activate.ps1` blocked | Not needed: call `.venv\Scripts\python.exe` directly (4.2). |
| ai-service pytest on Windows shows skips | Expected: 3 skipped (LibreOffice and POSIX-only tests). Anything **failed** → send it (11). |
| Screenshot harness: "Could not find Chrome" / sparticuz error | Set `$env:CHROME_PATH` (4.7). |

---

## 11. What to send back

Send what applies, in this order:

1. **The build result** (4.1):
   ```powershell
   Select-String -Path backend-build.log -Pattern "Tests run:.*Skipped: \d+$","BUILD SUCCESS","BUILD FAILURE" | Select-Object -Last 4
   Select-String -Path backend-build.log -Pattern "\[ERROR\]" | Select-Object -First 40
   ```
   On failure also attach `backend-build.log` and the failing `backend\target\surefire-reports\*.txt`.
2. **The last lines of each automated check** (4.2–4.7): pytest summaries, the eval `CHECK PASSED` or JSON tail, tool
   outputs, `Test Files … Tests …` and `built in` for both portals.
3. **CI:** the run URL and which jobs are red. For a red job, its raw log (gear → **View raw logs**) or the
   **surefire-reports** artifact.
4. **Stack state** after 5.2:
   ```powershell
   docker compose ps                          > services.txt
   docker compose logs --no-color backend     > backend.log
   docker compose logs --no-color agent-service > agent-service.log
   docker compose logs --no-color ai-service  > ai-service.log
   ```
   `services.txt` and `*.log` are git-ignored.
5. **For each step that did not match:**
   - the **step ID** (e.g. "7.21-5");
   - what you did, expected and saw;
   - a screenshot;
   - from DevTools → **Network**, the failing request: URL, status and response body (right-click → Copy → Copy
     response).
6. **The checklist:** a list of step IDs passed, failed or skipped. A short form like "7.1–7.20 pass, 7.21-5 fail" is
   fine.

---

## 12. Known blockers and traps found while writing this guide

No application bug was found that blocks a step in this guide. These are the traps and limitations found while
checking the code. Nothing was changed.

| # | Kind | Where | What happens | Workaround in this guide |
|---|---|---|---|---|
| 1 | **Configuration trap (FIXED: secrets in `.env.example` are now empty)** | `.env.example:9`, `:12`, `:31` (`replace-with-…` values) + `agent-service/app/core/config.py:116-118` | Copying `.env.example` to `.env` unchanged passes the placeholder `AGENT_SERVICE_TOKEN`. The agent service refuses to start, and the backend waits for it forever. The file's own header says every value has a development default, which invites the copy. Suggested fix: comment the three token lines out in `.env.example`. | 3 (do not copy unchanged), 10 |
| 2 | Working-tree state (FIXED in this folder: `mvnw.cmd` converted to CRLF; other clones: 2.1) | `backend/mvnw.cmd` (`.gitattributes:15` wants CRLF) | `git ls-files --eol` shows `w/lf` for `mvnw.cmd` in this checkout. `cmd.exe` can mis-parse labels in LF batch files. | 2.1 (recreate the file) |
| 3 | Demo fixture limitation | `agent-service/app/demo/fixtures.py:140-141` | Only `/.well-known/…` and `/verify` echo the challenge code. The home page `https://demo-portfolio.example` never proves with **Code on page**. | 6.3, 7.14 |
| 4 | Demo routing limitation | `agent-service/app/api/routes.py:114` (`identifier = request.url or ""`) | A badge given as **The credential itself** has no URL, so it is not routed to the offline fixtures. The demo VC-JWT becomes **Not checkable by agents**, not Verified. | 7.16-3 |
| 5 | Stale UI hint (minor) | `hr-frontend/src/pages/jobs/RequirementsEditor.tsx:254` | The **Proficiency** hint says "Shown to reviewers; not used in matching yet." Matching does use it since `evidence-ladder-4` (`MatchRequestBuilder.java:83` sends it; `ai-service/app/matching/engine.py` applies ADVANCED/EXPERT depth). | 7.21-1 tests the real behaviour |
| 6 | Rules-engine output (not a bug) | `ai-service/app/compile/requirements.py` | "Must have 3+ years of React and TypeScript" becomes one Experience row "have React and TypeScript" (3 years), not two skill rows. | 7.4-6 edits the rows |
| 7 | Known product limits (BUG_HUNT_SUMMARY §4) | — | Notification links are matched by title. A re-check un-shares a verdict. HR Undo windows are lost on tab close. "New since last visit" and "Looks right" are stored per browser. | expectations stated in 7.18-2, 7.19-6 |
