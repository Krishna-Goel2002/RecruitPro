# RecruitPro: run and test guide

Branch **`Kshitij`**, commit `2fedafb` plus the demo patches listed in [Section 0.2](#02-demo-patches-on-top-of-2fedafb). Written 16 Nov 2026.

This guide takes you from a Windows machine with Docker Desktop to a running RecruitPro, then through every flow that exists today, with the exact input to type and the result to expect. Commands are **Windows PowerShell** first; bash equivalents follow where they differ.

If a step does not match what you see, stop, note the section number, and use [Section 10](#10-if-something-goes-wrong) and [Section 12](#12-reporting-a-problem).

---

## Contents

0. [What has and has not been run](#0-what-has-and-has-not-been-run)
1. [Prerequisites](#1-prerequisites)
2. [Get the code](#2-get-the-code)
3. [The `.env` file](#3-the-env-file)
4. [START TONIGHT checklist](#4-start-tonight-checklist)
5. [Demo data, accounts and test files](#5-demo-data-accounts-and-test-files)
6. [Flows, one by one](#6-flows-one-by-one) (6.1 to 6.22)
7. [Verification demo script (5 minutes)](#7-verification-demo-script-5-minutes)
8. [Automated checks](#8-automated-checks)
9. [Optional real integrations](#9-optional-real-integrations)
10. [If something goes wrong](#10-if-something-goes-wrong)
11. [Stop, reset, switch versions](#11-stop-reset-switch-versions)
12. [Reporting a problem](#12-reporting-a-problem)

---

## 0. What has and has not been run

### 0.1 Status

| Item | Status |
|---|---|
| Commit `9ad341a` (core platform) | `mvnw verify` passed on a Windows machine with JDK 21 on 15 Nov 2026 (239 tests, 0 failures, 6 skipped), and `docker compose up --build` started all services. **This is the known-good fallback.** |
| Commit `2fedafb` (verification platform, agent-service, MFA, SSO, ICS, work-sample files, scorecard edit, fairness date range) | **Never compiled.** The sandbox it was written in has no JDK 21 or Maven Central. Static checks passed: `java_static_check.py` (575 files, 0 errors) and `check_entity_schema.py` (474 columns, 0 errors). The first real `mvnw verify` is step 4.1 below. |
| ai-service | `pytest`: 178 passed. `eval.run --check`: passed. |
| agent-service | `pytest`: 161 passed. `eval.run --check`: exit 0, false-corroboration rate 0.0, kappa 1.0 (synthetic data, see 8.3). |
| Both portals | `pnpm build` (`tsc --noEmit` + `vite build`) passes. |
| Docker images of agent-service and of the new backend | Not built yet. |
| **Every flow in Section 6** | **Not yet clicked through against a live stack.** They were traced through the code (frontend → controller → service → agent fixture), and the agent fixtures were run in-process against the exact inputs below. Expect small surprises. Report them using Section 12. |

### 0.2 Demo patches on top of 2fedafb

These changes are in the working tree and not committed yet. Commit and push them before the demo.

| File | Why |
|---|---|
| `backend/.../organization/bootstrap/DevSeedRunner.java`, `DemoDataSeeder.java` | The new demo assessment seed (kit, work sample, interviews) ran inside the main seed transaction. Any error in it would have rolled back all demo accounts **and stopped the backend from starting**. It now runs after the accounts and jobs are committed, in its own transaction. A failure is logged as `Demo assessments ... could not be seeded` and is never fatal. |
| `hr-frontend/src/pages/interviews/InterviewDetailPage.tsx` | The API supported editing a submitted scorecard, but the UI had no way to do it. There is now an **Edit scorecard** button for the author. |
| `hr-frontend/src/pages/admin/AdminOrganizationPage.tsx` | The API supported **Require two-step verification for all staff** and **SSO allowed domains**, but the page had no controls for them. Both are now on the page. |
| `docker-compose.yml` | The SSO variables, the LLM model and key variables, and `AGENT_GITHUB_TOKEN` were not passed into the containers, so the optional integrations in Section 9 could not be switched on. Empty defaults change nothing. |
| `.env.example` | `APP_ENCRYPTION_KEY=replace-with-...` is not valid Base64. Copying the file unchanged **stopped the backend from starting**. It is now empty, which means "use the dev default". The SSO and LLM variables were added. |
| `tools/totp.py` (new) | Prints the current 6-digit code for an MFA secret, for testing MFA without a phone. Standard library only. |

---

## 1. Prerequisites

| Tool | Version | Needed for | Install |
|---|---|---|---|
| Git | any recent | getting the code | https://git-scm.com/downloads |
| Docker Desktop | recent, Compose v2, **WSL 2 backend** | running the stack | https://www.docker.com/products/docker-desktop/ |
| JDK | **21** (Temurin) | `mvnw verify` | https://adoptium.net/temurin/releases/?version=21 (tick "Set JAVA_HOME") |
| Node.js | **22 LTS** | portal builds (outside Docker) | https://nodejs.org/ |
| pnpm | **10** | portal builds | `corepack enable` then `corepack prepare pnpm@10 --activate`, or prefix with `npx pnpm@10` |
| Python | **3.12** (3.10 and 3.11 also work) | ai-service and agent-service tests, `tools/` | https://www.python.org/downloads/ (tick "Add python.exe to PATH") |

**Docker Desktop → Settings → Resources:** give it at least **8 GB of memory**. The stack has 7 containers, and the first build compiles Java and two Node apps.

Check in a **new** PowerShell window:

```powershell
git --version; docker --version; docker compose version
java -version      # must say 21
node --version     # v22.x
pnpm --version     # 10.x
python --version   # 3.12.x (3.10/3.11 ok)
```

**Ports the stack uses:** 5173, 5174, 8080, 8000, 8100, 8025, 1025, 5432. Check that none are in use:

```powershell
netstat -ano | findstr ":5173 :5174 :8080 :8000 :8100 :8025 :1025 :5432"
```

Nothing should be listed. A local PostgreSQL on 5432 is the usual culprit; stop it with `Stop-Service postgresql*` in an admin PowerShell.

---

## 2. Get the code

```powershell
cd C:\Users\KIIT0001\Desktop\RecruitPro
git status                 # "On branch Kshitij"
git pull
git log --oneline -3       # 2fedafb (or newer) at the top
```

On a fresh machine:

```powershell
git clone https://github.com/Krishna-Goel2002/RecruitPro.git C:\dev\RecruitPro
cd C:\dev\RecruitPro
git checkout Kshitij
```

Use a path **without spaces**.

---

## 3. The `.env` file

**For a local demo you do not need a `.env` at all.** Every variable has a development default in `docker-compose.yml`, including the database, the JWT secret, both service tokens, a **dev-only** `APP_ENCRYPTION_KEY`, Mailpit, demo seeding, `LLM_PROVIDER=none` and `AGENT_DEMO_FIXTURES=true`.

Create one only to override something. It lives next to `docker-compose.yml`:

```powershell
Copy-Item .env.example .env      # bash: cp .env.example .env
notepad .env
```

| Variable | Required? | Dev default (docker-compose) | Notes |
|---|---|---|---|
| `JWT_SECRET` | no | `dev-only-jwt-secret-...` | At least 32 bytes. |
| `AI_SERVICE_TOKEN` | no | `dev-only-ai-service-token-...` | Shared by backend and ai-service automatically. |
| `AGENT_SERVICE_TOKEN` | no | `dev-only-agent-service-token-...` | Shared by backend and agent-service automatically. |
| `APP_ENCRYPTION_KEY` | no | dev-only fixed key | Must be Base64 of **exactly 32 bytes**, or empty. Anything else stops the backend. Needed for MFA, SSO and GitHub OAuth. |
| `APP_SEED_DEMO` / `APP_SEED_PASSWORD` | no | `true` / `ChangeMe-123!` | Demo organisation and accounts. |
| `LLM_PROVIDER`, `AGENT_LLM_PROVIDER` | no | `none` | See 9.3. |
| `AGENT_DEMO_FIXTURES` | no | `true` | Serves synthetic data for `demo-` logins and hosts. **Never `true` outside a local demo.** |
| `GITHUB_CLIENT_ID/SECRET` | no | empty | See 9.1. Empty means "Sign in with GitHub" is unavailable. That is expected. |
| `SSO_*` | no | empty | See 9.2. |

**Generate secrets** (only if you want your own):

```powershell
# APP_ENCRYPTION_KEY: 32 random bytes, Base64 (works in Windows PowerShell 5.1 and PowerShell 7)
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
# JWT_SECRET / service tokens: 48 random bytes
$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

```bash
openssl rand -base64 32   # APP_ENCRYPTION_KEY
openssl rand -base64 48   # JWT_SECRET, AI_SERVICE_TOKEN, AGENT_SERVICE_TOKEN
```

Changing `APP_ENCRYPTION_KEY` later makes existing MFA enrolments and GitHub OAuth tokens unreadable. Run `docker compose down -v` afterwards (11.2).

---

## 4. START TONIGHT checklist

Do 4.1 to 4.5 tonight, in order. Allow about an hour. Most of it is first-time downloads.

### 4.1 Backend compile and tests (the most important step)

```powershell
cd backend
.\mvnw.cmd verify *> ..\backend-build.log
cd ..
Select-String -Path backend-build.log -Pattern "BUILD SUCCESS","BUILD FAILURE","Tests run:.*Fail" | Select-Object -Last 5
```

```bash
cd backend && ./mvnw verify > ../backend-build.log 2>&1; cd ..
grep -E "BUILD SUCCESS|BUILD FAILURE|Tests run:.*Fail" backend-build.log | tail -5
```

The first run downloads Maven and its dependencies (5 to 15 minutes).

**Success looks like this:**
- `BUILD SUCCESS` near the end of the log.
- A summary like `Tests run: 3xx, Failures: 0, Errors: 0, Skipped: 6`. There are about 335 `@Test` methods now, against 239 at `9ad341a`.
- The 6 skipped tests are the opt-in AI end-to-end tests (`RECRUITPRO_AI_IT=true`).

**If it fails:**
1. Get the **first** error, not the last:
   ```powershell
   Select-String -Path backend-build.log -Pattern "\[ERROR\]" | Select-Object -First 25
   ```
   bash: `grep -n "\[ERROR\]" backend-build.log | head -25`
2. Send those lines, or the whole `backend-build.log`. A compile error is usually a one-line fix.
3. **If there is no time to fix it before the demo**, use the known-good core platform. It has no verification, MFA, SSO, ICS, file uploads or scorecard editing:
   ```powershell
   docker compose down -v          # the newer database schema (V21-V24) does not work with the older code
   git stash                       # only if `git status` shows local changes
   git checkout 9ad341a
   docker compose up --build
   ```
   Then demo 6.1 to 6.6, 6.10, 6.11, 6.21 and 6.22, plus 6.7 to 6.9 without file attachments, ICS, scorecard editing or the fairness date range. The older guide for that version is `git show 9ad341a:docs/guides/RUN_AND_TEST_GUIDE.md`. To go back: `docker compose down -v; git checkout Kshitij; git stash pop`.

`docker compose up --build` compiles the backend again (without tests) inside Docker. A compile error therefore also breaks 4.2. The fix and the fallback are the same.

### 4.2 Start the stack

From the repository root:

```powershell
docker compose up --build
```

The first build takes 10 to 20 minutes and builds 5 images. Leave this window open: it shows the live logs. Use a second PowerShell window for everything else.

**Backend log lines that mean "ready"** (`docker compose logs backend | Select-String "Demo|Started"`):

```
Demo data ready: organisation 'Acme Labs' with admin@acme.test, hr@acme.test, recruiter@acme.test, interviewer@acme.test and candidate candidate@demo.test ...
Demo assessments ready for 'Backend Engineer (Java)': kit, scored work sample, completed and scheduled interview
Verification demo data ready: Acme Labs in VISIBLE mode, GitHub source demo-asha with 3 shared verdicts (SYNTHETIC)   <- first start only
Started RecruitProApplication in ... seconds
```

agent-service logs a **warning on purpose**: `AGENT_DEMO_FIXTURES=true: demo- logins and hosts are answered from synthetic fixtures`.

### 4.3 Check every service

```powershell
docker compose ps
```

All 7 services (`postgres`, `mailpit`, `ai-service`, `agent-service`, `backend`, `candidate-portal`, `hr-portal`) should be `running`. `postgres`, `backend`, `agent-service` show `(healthy)`.

| Port | URL | Expected |
|---|---|---|
| 8080 | http://localhost:8080/api/v1/health | `{"success":true,"message":"Health check passed","data":{"status":"UP"},...}` |
| 8080 | http://localhost:8080/swagger-ui.html | API documentation (dev profile only) |
| 8000 | http://localhost:8000/health | `{"status":"ok",...,"llmProvider":"none",...}` |
| 8100 | http://localhost:8100/health | `{"status":"ok","version":...,"llmProvider":"none","demoFixtures":true}` |
| 8025 | http://localhost:8025 | Mailpit inbox. Every email the app sends lands here; nothing goes to real inboxes. |
| 5173 | http://localhost:5173 | Candidate portal sign-in |
| 5174 | http://localhost:5174 | HR portal sign-in |
| 5432 | `docker compose exec postgres psql -U recruitpro -d recruitpro -c "select email, role from users;"` | 5 demo users |

Quick check from PowerShell:

```powershell
foreach ($u in "http://localhost:8080/api/v1/health","http://localhost:8000/health","http://localhost:8100/health") { Invoke-RestMethod $u | ConvertTo-Json -Compress }
```

bash: `for u in localhost:8080/api/v1/health localhost:8000/health localhost:8100/health; do curl -s $u; echo; done`

### 4.4 Smoke test (2 minutes)

1. http://localhost:5174, sign in as `admin@acme.test` / `ChangeMe-123!`. The **Overview** dashboard shows health cards: backend, database, **AI service UP** and **Verification agents UP**.
2. http://localhost:5173 in a **private window**, sign in as `candidate@demo.test` / `ChangeMe-123!`. Open **Strengthen your application**: a GitHub source `demo-asha (demo)` is **Proven**, and the ledger shows React and TypeScript **Verified** and Python **Partly verified**, all **Shared**.

If both work, the stack is demo-ready. Do the [Section 7 preparation](#71-prepare-the-night-before-15-minutes) next.

### 4.5 Run the other automated checks (optional tonight)

See Section 8. They take 5 to 10 minutes and are independent of Docker.

---

## 5. Demo data, accounts and test files

### 5.1 Accounts (password for all: `ChangeMe-123!`)

| Email | Role | Portal | What they can do |
|---|---|---|---|
| `admin@acme.test` | Admin | HR :5174 | Everything, including the Administration menu (Overview, Staff accounts, Invitations, Organisation, Job oversight, Audit log, Fairness, Verification) |
| `hr@acme.test` | HR manager | HR :5174 | Jobs, decisions, reveal, work samples, interviews; Verification shadow queue and metrics (not settings) |
| `recruiter@acme.test` | Recruiter | HR :5174 | Reads jobs and applicants, verifies claims, scores work samples, Confirm/Overrule verdicts; no decisions |
| `interviewer@acme.test` | Interviewer | HR :5174 | **My interviews** only |
| `candidate@demo.test` (Casey Candidate) | Candidate | Candidate :5173 | |

Staff cannot sign in to the candidate portal, and candidates cannot sign in to the HR portal. Each refuses with a message naming the right portal.

### 5.2 What the seed creates (organisation "Acme Labs")

- **Jobs (published):**
  - **Backend Engineer (Java)**: Java, Spring Boot, Backend development 3+ yrs as must-haves; PostgreSQL and Bachelor's in CS as nice-to-haves.
  - **Data Analyst**: SQL, Python, Data analysis as must-haves; "Tableau or Power BI" as a nice-to-have.
- **Backend Engineer (Java):**
  - An interview kit with 4 questions.
  - An ACTIVE work sample, "Design a REST endpoint with Spring Boot", with a 2-criterion rubric.
- **Casey's application to Backend Engineer:**
  - Status **INTERVIEWING**, identity revealed.
  - A generated resume, `casey-candidate-resume.pdf`.
  - A **SCORED** work sample (3.50).
  - A **COMPLETED** interview two days ago, with `interviewer@acme.test`'s scorecard (YES).
  - A **SCHEDULED** interview in three days.
- **Verification:**
  - Acme Labs is in **VISIBLE** mode, with all connectors on.
  - Casey has the skill claims React, TypeScript and Python.
  - She has a GitHub source `demo-asha`: ownership PROVEN, one completed run, 4 synthetic evidence items.
  - **SHARED** verdicts: React VERIFIED, TypeScript VERIFIED, Python PARTLY_VERIFIED (with a probe question).
  - No staff review yet.

All of it is created only when missing, so restarts do not duplicate it. `docker compose down -v` (11.2) wipes everything and reseeds it.

### 5.3 Offline demo fixtures (agent-service, `AGENT_DEMO_FIXTURES=true`)

Only identifiers starting with **`demo-`** are served from fixtures. Everything else goes to the real internet.

| Kind | What to type | What the fixture has |
|---|---|---|
| GitHub or GitLab username | **`demo-asha`** | 3 own repos: react-dashboard (TypeScript/React, 8 months), ui-kit (TypeScript/React), data-pipeline (Python, 2 months), plus one fork. → React **Verified**, TypeScript **Verified**, Python **Partly verified**, other skills Not found. Note `FORK_ONLY`. |
| GitHub username | **`demo-ravi`** | One Java repo with 30 commits on a single day, plus a fork of tensorflow → Java **Partly verified** with note `FORK_ONLY`; nothing Verified |
| Website | **`https://demo-portfolio.example`** | Home, about and blog pages by "Asha Synthetica": React, TypeScript, "Certified Kubernetes Application Developer (CKAD)", "B.Tech in Computer Science, Demo Institute of Technology, 2019", "Frontend Engineer at Demo Analytics since 2021" |
| Open Badge (link) | **`https://demo-badges.example/assertions/react-1`** | OB 2.0 hosted badge "React Fundamentals" for **`asha.demo@example.com`** |
| Open Badge (link) | **`https://demo-badges.example/credentials/python.jwt`** | OB 3.0 signed credential "Python Programming" (issuer `did:web:demo-badges.example`) for **`asha.demo@example.com`** |
| Ownership | any method on a `demo-` account or host | The fixture **echoes back whatever challenge code you were given**, so the check passes without editing a real bio or DNS. Exception: **Code on a web page** on `https://demo-portfolio.example` fails, because the home page carries no code. Use **DNS TXT record** or **File on your website**. |

**Why "Asha Synthetica / asha.demo@example.com" matters:**
- Website verdicts are **Verified** only when the page's author name matches the candidate's name. For any other name the result is **Partly verified**.
- Badges are **Verified** only when the badge recipient matches the candidate's email. For any other email the result is **Contradicted** ("issued to someone else"). That is a nice demo of a borrowed badge being caught.

The verification flows below therefore use a new candidate account, **Asha Synthetica, `asha.demo@example.com`**.

### 5.4 Test files (10 minutes, fake data only)

Create these in Word or Google Docs and save each as **PDF** (also `.docx` for Resume A).

**Resume A** (`resume-a.pdf`, `resume-a.docx`): a strong match for Backend Engineer (Java)

```
Arjun Test
arjun.test@example.com | +91 90000 00001
SKILLS
Java, Spring Boot, PostgreSQL, REST APIs, Docker, Git
EXPERIENCE
Software Engineer, Alpha Systems                Jan 2020 – Present
Built REST APIs in Java and Spring Boot backed by PostgreSQL.
Junior Developer, Beta Labs                     Jun 2018 – Dec 2019
Maintained Java services and wrote SQL reports.
EDUCATION
B.Tech in Computer Science and Engineering, Example Institute of Technology, 2018
```

**Resume B** (`resume-b.pdf`): a weak match

```
Meera Sample
meera.sample@example.com
SKILLS
Python, Excel, Tableau
EXPERIENCE
Data Intern, Gamma Analytics                    Jun 2024 – Dec 2024
Cleaned data in Python and built Tableau dashboards.
EDUCATION
B.Sc. in Statistics, Sample University, 2024
```

**Resume C** (`resume-asha.pdf`): for the verification flows

```
Asha Synthetica
asha.demo@example.com
SKILLS
React, TypeScript, Python, Java
CERTIFICATIONS
Certified Kubernetes Application Developer (CKAD)
EXPERIENCE
Frontend Engineer, Demo Analytics               Jan 2021 – Present
Built React and TypeScript dashboards.
EDUCATION
B.Tech in Computer Science, Demo Institute of Technology, 2019
```

**Work-sample attachment**: any small PDF or PNG, for example `resume-b.pdf`.

---

## 6. Flows, one by one

Use a normal browser window for the HR portal (http://localhost:5174) and a **private window** for the candidate portal (http://localhost:5173). Keep Mailpit (http://localhost:8025) open in a third tab.

Things run in the background:
- Resume analysis, matching, verification runs and reference checks run in a job queue that polls every 2 s.
- Emails go out every 5 s.

So most results appear **within a few seconds**, and pages refresh themselves while something is pending.

### 6.1 Candidate sign-up, email verification, sign-in

1. Candidate portal → **Create account**:
   - First name `Arjun`, Last name `Test`
   - Email `arjun.test@example.com`
   - Password `TestPass123` (10 to 72 characters, at least one letter and one digit)
   - Confirm the password and submit.
   - **Expect:** you are signed in, and a banner asks you to verify your email.
2. Mailpit → **"Confirm your email address for RecruitPro"** → click the link (`http://localhost:5173/verify-email?token=...`).
   - **Expect:** the portal confirms the email, and the banner is gone.
3. Sign out, then sign in again with the same credentials. **Expect:** the Jobs list.
4. Negative checks:
   - Register with password `abc`. **Expect:** a validation message.
   - Sign in to the candidate portal as `hr@acme.test`. **Expect:** refused, with a message pointing to the HR portal.
5. Reload with F5. **Expect:** you stay signed in.
   - DevTools → Application → Local/Session Storage contains no token. Sessions use httpOnly cookies `rp_refresh_candidate` / `rp_refresh_staff`.

### 6.2 Resume upload → parsing → facts

1. As Arjun → **Resumes** → upload `resume-a.pdf`.
   - **Expect:** *Queued/Analysing*, then *Analysis ready* within a few seconds.
2. Open the analysis.
   - **Expect:** skills Java, Spring Boot, PostgreSQL, Docker, Git, REST APIs.
   - Two roles with dates.
   - The B.Tech under education.
   - Words like "CV" or "Present" do not become skills.
3. Upload `resume-a.docx` too. **Expect:** it also completes.
4. On one skill, **Suggest a correction** (for example "Docker, Kubernetes").
   - **Expect:** the page says it is a *claim* that recruiters see as unverified.
   - The extracted value stays.

### 6.3 Browse and apply

1. **Find jobs** → type slowly in the search box. **Expect:** the cursor stays in the box while results update.
2. Open **Backend Engineer (Java)**.
   - **Expect:** organisation Acme Labs, requirements with must-have badges, no recruiter email.
3. **Apply** → choose `resume-a.pdf` → write a short cover letter → **Submit application**.
   - **Expect:** a success message, and **Applications** lists it as *Submitted*.
   - Mailpit has an "application received" email.
4. Create a second candidate:
   - Sign up as `Meera Sample` / `meera.sample@example.com`.
   - Upload `resume-b.pdf` and apply to the same job.

### 6.4 HR sign-in (demo accounts)

1. HR portal → `hr@acme.test` / `ChangeMe-123!`. **Expect:** the **Jobs** list with the two Acme jobs.
2. Sign out, then sign in as `interviewer@acme.test`. **Expect:** only **My interviews** in the navigation.
3. Sign in as `candidate@demo.test` on the HR portal. **Expect:** refused, with a message pointing to the candidate portal.

### 6.5 Job create → requirements → publish

1. As `hr@acme.test` → **New job**. Title `Frontend Engineer (React)`, any department, location and salary. Paste this description:

   > We are looking for a young, energetic rockstar developer. Must have 3+ years of React and TypeScript. Python is a plus. Experience with AWS or Azure is required. A degree in computer science is mandatory. Good communication skills.

2. **Draft requirements from description**.
   - **Expect:** React and TypeScript (3 years) as suggestions, "AWS **or** Azure" as alternatives, and Python as nice-to-have.
   - **Expect** wording warnings for "young" (age-coded), "rockstar" (gender-coded), the mandatory degree, and "good communication" (vague).
3. Accept the suggestions you want. Make sure these exist, adding them with **Add requirement** if needed:
   - SKILL **React**, must-have
   - SKILL **TypeScript**, must-have
   - SKILL **Python**, nice-to-have

   Then **Save draft**, then **Publish** and confirm.
4. Candidate portal → **Find jobs**. **Expect:** Frontend Engineer (React) is listed.
5. Edit the published job (for example its title) and save. **Expect:** allowed; matching re-runs.

This job is reused by the verification flows (6.18 to 6.20) and the demo script.

### 6.6 Applicant ranking, blind review, reveal, decisions

1. `hr@acme.test` → **Backend Engineer (Java)** → **Applicants**.
   - **Expect:** Casey (seeded, revealed, *Interviewing*), Arjun and Meera. New applicants show as **Candidate #A-…** (blind).
   - Arjun ranks above Meera on **verified coverage**. Meera may appear under *Must-haves not yet verified*.
2. Open Arjun → **Evidence and assessments** tab.
   - **Expect:** every requirement with a status, a tier badge (*Resume*), a quote, an explanation, and verification hints.
   - No name, email, resume download or cover letter yet.
   - Arjun's "Kubernetes" correction shows as a claim and does not raise verified coverage.
3. **Shortlist** (comment optional) → confirm.
   - **Expect:** status *Shortlisted*.
   - The identity is revealed: name, email, cover letter, **Download resume**.
   - Mailpit: a status email to Arjun.
4. **Undo shortlist**. **Expect:** a comment is required. Undo it with a comment, then shortlist again.
5. Open Meera → **Reject** → then **Reopen** with a comment.
   - **Expect:** *Rejected*, then *Submitted*. The history lists every step with actor and time.
6. As `recruiter@acme.test`, open Arjun. **Expect:** can read and verify claims; no Shortlist/Reject buttons.

### 6.7 Work sample: assign → candidate submits with files → score

1. `hr@acme.test` → Backend Engineer (Java) → **Work samples** tab.
   - **Expect:** the seeded ACTIVE "Design a REST endpoint with Spring Boot".
   - Optional: **Generate work sample** creates a new draft with a rubric; **Activate** it.
2. Open Arjun (shortlisted) → **Work samples** section → **Assign** → choose the active sample → set a **Start by** time later today.
   - **Expect:** status *Assigned*.
   - Mailpit: "A work sample is waiting for you: …" to Arjun.
3. Candidate portal as Arjun → **Work samples** → open it.
   - **Expect:** instructions hidden until **Start**.
   - Press **Start**. **Expect:** instructions and a countdown.
   - Type an answer, attach **1 to 3 files** (pdf, zip, docx, pptx, png, jpg; at most 20 MB each), and submit.
   - **Expect:** *Submitted*, with your files listed and downloadable under **Attached files**.
4. HR → Arjun → Work samples → **View submission**.
   - **Expect:** the text and the files. **Download** saves a file; this is audited as `WORK_SAMPLE_FILE_DOWNLOADED`.
5. **Score with rubric**: give "REST design with Spring Boot" a **4** and "Data model and queries" a **3** → **Save scores**.
   - **Expect:** *Scored*. After a few seconds, Spring Boot and PostgreSQL show the tier **Demonstrated** in the evidence table.
6. Optional: **Cancel assignment** with a reason (HR manager or admin only). The candidate gets "Work sample cancelled", and the Demonstrated tier disappears after re-matching.

### 6.8 Interview kit, schedule (ICS in Mailpit), scorecard and edit

1. Backend Engineer (Java) → **Interview kit** tab. **Expect:** the seeded 4 questions; **Edit questions** works.
   - On the new Frontend job, **Generate kit** creates questions from its requirements.
2. Open Arjun → **Interviews** → **Schedule interview**:
   - Interviewer `Ivan Petrov` (`interviewer@acme.test`)
   - Tomorrow 10:00, 45 minutes
   - Location `https://meet.example.com/abc`
   - **Schedule**.
   - **Expect:** status *Interviewing*.
3. Mailpit has two emails:
   - "Interview scheduled for Backend Engineer (Java)" (to Arjun)
   - "You are interviewing … for Backend Engineer (Java)" (to the interviewer; an unrevealed applicant is named *Candidate #A-…* here and in the ICS)

   **Each carries `invite.ics`** (Mailpit shows it under attachments; opening it adds an event in UTC).
4. **Reschedule** to another time. **Expect:** new emails whose ICS has the same UID and `SEQUENCE:1`.
   - **Cancel interview** sends `cancel.ics` (`METHOD:CANCEL`).
5. Sign in as `interviewer@acme.test` → **My interviews** → open the interview with Arjun.
   - **Expect:** the kit and the "Focus on these requirements" list.
   - Rate every question 1 to 4 (give a requirement-linked question a 3 or 4), choose a recommendation, write a summary, **Submit scorecard**, and confirm.
6. **Edit** (new): open the seeded **completed** interview with **Casey Candidate** (two days ago) → **Edit scorecard** → change a rating and the summary → **Save changes** → confirm.
   - **Expect:** "Scorecard updated" and "Edited 1 time".
   - `hr@acme.test` sees the new version on Casey's application.
   - Audit has `SCORECARD_UPDATED`.
   - Editing is refused once the application is Selected, Rejected or Withdrawn, and for anyone but the author.
7. `hr@acme.test` → Arjun → **Select**. **Expect:** *Selected*, and Arjun is emailed.

### 6.9 Fairness report with a date range

1. Any candidate → **Privacy** → fill in the voluntary equal-opportunity survey.
   - It explains why it is asked, requires consent, and can be deleted later.
2. HR → Backend Engineer (Java) → **Fairness** tab.
   - **Expect:** a report by dimension and stage. With few applicants, groups are **suppressed** (fewer than 5): that is the privacy rule working.
3. Set **From** and **To** dates (for example today to today) and apply.
   - **Expect:** the report recalculates for applications created in that range. The range stays in the URL (`?from=...&to=...`).
   - **All time** resets it. A *To* date before the *From* date is refused.
4. `admin@acme.test` → **Administration → Fairness**: the organisation-wide report with the same date filter.

### 6.10 Audit export

1. `admin@acme.test` → **Audit log**.
   - **Expect:** entries for decisions, reveals, resume downloads, claim verification, `VERDICT_REVIEWED`, `SCORECARD_UPDATED`, MFA events and more.
   - Filter by action; the CSV export downloads a file.
2. Job page → **Export audit CSV**. **Expect:** one row per decision, with actor, time and the match evidence used.

### 6.11 Candidate data export and account deletion (use Meera, not Asha)

1. Meera → **Privacy** → **↓ Download my data**.
   - **Expect:** a JSON file with profile, resumes, applications, claims, survey and a `verification` section.
   - It contains no tokens or storage paths.
2. **Delete my account** with a **wrong** password. **Expect:** refused.
3. With the right password. **Expect:** signed out; signing in again fails.
4. HR → Meera's application. **Expect:** still there, but anonymised.

### 6.12 Staff MFA: enrol, sign in with a code, recovery code

Use `recruiter@acme.test`, so a mistake cannot lock out the admin.

1. HR portal as recruiter → **Two-step verification** → **Turn on**.
   - **Expect:** a secret (32 Base32 characters) and an `otpauth://` link as text. There is no QR code.
2. Get a 6-digit code:
   - Either add the secret to an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password),
   - or, without a phone:
     ```powershell
     python tools\totp.py PASTE_THE_SECRET_HERE
     ```
     It prints `123456  (valid for about NN more seconds)`.

   Enter the code. **Expect:** **Save your recovery codes**: 10 codes like `abcde-fgh23`, shown once. Copy them, then **I have saved them**.
3. Sign out and sign in as recruiter with the password.
   - **Expect:** a code step.
   - Enter a fresh code (wait for the next 30 s window if you just used one; codes are single use) → **Verify and sign in**.
4. Sign out and sign in again → **Use a recovery code** → paste one. **Expect:** signed in.
   - The same recovery code does not work twice. Audit has `MFA_RECOVERY_CODE_USED`.
5. **Two-step verification** shows "Recovery codes left: 9". **Create new recovery codes** invalidates the old ones.
6. Five wrong codes lock the account for 15 minutes, as passwords do.
7. Clean up: **Turn off** (password plus a code).

### 6.13 Admin organisation settings (require MFA, SSO domains)

1. `admin@acme.test` → **Organisation**.
   - **Expect:** Blind review, Share verification status, **Require two-step verification for all staff**, and **Single sign-on: allowed e-mail domains**.
2. Add domain `acme.test` (type it, press Enter) → **Save settings**.
   - **Expect:** "Organisation settings saved".
   - `PATCH /api/v1/admin/organization` returns `ssoAllowedDomains: ["acme.test"]`.
   - With no SSO provider configured, nothing changes on the login page. That is expected (see 9.2).
3. Turn on **Require two-step verification for all staff** → Save.
   - **Keep this admin window signed in.** Existing sessions are not ended.
4. In another browser, sign in as `interviewer@acme.test`.
   - **Expect:** a forced set-up step: **Start set-up** → secret → enter a code (`tools\totp.py`) → recovery codes → **Confirm and sign in**.
5. Back in the admin window: turn the requirement **off** and save. Then the interviewer turns MFA off under **Two-step verification**.
6. Blind review toggle: off → new applicants show names immediately. Turn it back on.

### 6.14 Admin dashboard health (AI service and agent service)

1. `admin@acme.test` → **Overview**.
   - **Expect:** health cards for backend, database, **AI service: UP**, **Verification agents: UP**, plus queue counts.
2. `docker compose stop agent-service` → refresh after about 15 s (the health result is cached for 15 s).
   - **Expect:** **Verification agents: DOWN**.
   - Same with `ai-service`.
3. Restart with `docker compose start agent-service ai-service`.

### 6.15 Verification: what Casey already has (seeded)

1. Candidate portal as `candidate@demo.test` → **Strengthen your application**.
   - **Expect:** the GitHub source `demo-asha (demo)` with Ownership **Proven** (code in your profile bio).
   - The ledger: React **Verified**, TypeScript **Verified**, Python **Partly verified**, each **Shared**.
   - Her resume facts (Java, Spring Boot, …) show **Not checked yet**.
2. Click **React → Verified**.
   - **Expect:** the reasons ("Repository A and repository B depend on React…").
   - The evidence: title, URL, retrieved time, short `sha256` hash.
   - **Share with employers / Hide / Re-check**.
3. Python → **An interviewer may ask**: the probe question.
4. Connecting `demo-asha` again on Casey gives **409 "This source is already connected"**. That is correct; use the Asha account below.

### 6.16 Verification: connect GitHub by username, prove ownership, run, ledger, share

1. Candidate portal → **Create account**:
   - First name **`Asha`**, Last name **`Synthetica`**
   - Email **`asha.demo@example.com`**, password `TestPass123`
   - Verify the email via Mailpit (optional).
2. **Resumes** → upload `resume-asha.pdf` → wait for *Analysis ready*.
   - Optional: **Evidence** → **Add a claim** → type *Certification*, "Certified Kubernetes Application Developer (CKAD)", if the parser did not list it.
3. **Find jobs** → **Frontend Engineer (React)** (from 6.5) → **Apply** with `resume-asha.pdf`.
4. **Strengthen your application** → **Connect a source** → **GitHub**.
   - Click **Sign in with GitHub**. **Expect:** the info box *"Signing in with GitHub is not available on this server"* and a username field. This is expected without a GitHub OAuth app (9.1). Alternatively, click **Connect with my username instead**.
   - Username **`demo-asha`** → tick consent → **Connect source**.
   - **Expect:** "GitHub: demo-asha connected", and the **Prove ownership** dialog opens with your code (`rp-` plus 24 characters) and the step "Add a code to your GitHub profile bio".
5. **Check now** (no need to edit any bio; the demo fixture echoes the code).
   - **Expect:** Ownership **Proven**, method *Code in your profile bio*.
   - In real use the code must be in the GitHub bio.
6. **Run verification**.
   - **Expect:** *Verification running…*, then within seconds the ledger shows:
     - React and TypeScript: **Verified**
     - Python: **Partly verified**, with a probe question
     - Java: **Not found**
     - Certification, education and experience facts are not sent to code hosts, so they stay *Not checked yet*
   - Each result is *Awaiting your review*.
   - Mailpit: "Your verification results are ready".
7. Open React → evidence items (profile, repositories, manifests; labelled synthetic, with hashes) → **Share with employers**. Share TypeScript too.
8. Negative variants:
   - Connect `demo-ravi`. **Expect:** Java **Partly verified** with the integrity note *FORK_ONLY*, and nothing Verified.
   - Connect `demo-nobody`, then **Check now**. **Expect:** ownership not proven (`SOURCE_NOT_FOUND`).
   - Running it fails with an error code.
9. **Revoke** a source. **Expect:** after confirmation its evidence is deleted, and its verdicts are withdrawn from the ledger.

Limits: 10 runs per candidate per 24 h (429), and 20 ownership checks per hour. **Re-check** starts a new run, and the old verdict becomes superseded.

### 6.17 Verification: website, document, Open Badge (as Asha)

1. **Website:** Connect a source → **Website** → `https://demo-portfolio.example` → consent → **Connect source**.
   - In the dialog choose **DNS TXT record** or **File on your website** → **Check now**. **Expect:** **Proven**. (*Code on a web page* fails on this demo site; see 5.3.)
   - **Run verification**. **Expect:** React, TypeScript and the CKAD certification **Verified**, because the pages carry the author marker "Asha Synthetica". Python and Java **Not found**.
   - With any other candidate name these would be **Partly verified**.
2. **Document:** Connect a source → **Document** → upload `resume-asha.pdf` (any PDF, DOCX or PPTX ≤ 15 MB) → consent → **Upload document** → **Run verification**.
   - **Expect:** claims written in the document are **Partly verified**, never Verified. There is no ownership check ("Ownership of an uploaded document cannot be proven").
   - A note *AUTHOR_MISMATCH* may appear when the file's author metadata is not your name.
3. **Open Badge (is there a demo badge? yes):** Connect a source → **Open Badge** → **A link to the badge** → `https://demo-badges.example/assertions/react-1` → consent → **Connect source** → **Run verification**.
   - **Expect:** React **Verified**: issuer-hosted assertion, recipient hash matches `asha.demo@example.com`.
   - Add `https://demo-badges.example/credentials/python.jwt` the same way. **Expect:** Python **Verified** (signed OB 3.0 credential, `did:web` issuer).
   - Do the same as Casey. **Expect:** **Contradicted**, because the badge was issued to someone else.
   - Badges need no ownership step; the issuer's signature is checked during the run.
4. Share the results you want employers to see.

### 6.18 Verification: reference request → referee answers → verdict

1. As Asha → **Strengthen your application** → **References** → **Ask for a reference**:
   - Their name `Priya Manager`, email **`priya@demo-analytics.example`**
   - Relationship **Manager**
   - Employer `Demo Analytics`, employer website domain **`demo-analytics.example`**
   - Tick claims (for example React and the "Frontend Engineer, Demo Analytics" experience) → consent → **Send request**.
   - **Expect:** status *Sent*, with an expiry date 14 days ahead.
2. Mailpit → **"Asha Synthetica asked you for a reference"** → open the link. It is **`http://localhost:5173/reference/<token>`** and works in any browser without signing in.
   - **Expect:** a page naming "Asha", the relationship, and one question per claim.
3. Choose **Confirm** for each claim (add a comment if you like), tick the identity confirmation → **Send answers**.
   - **Expect:** a thank-you state.
   - Opening the link again shows "expired or already answered" (410).
4. Within seconds:
   - Mailpit: **"Priya Manager responded to your reference request"** to Asha.
   - Asha's ledger shows those claims **Verified** with source *Reference* → **Share with employers**.
5. Variants:
   - A `@gmail.com` referee gives **Partly verified** (free-mail domains never anchor).
   - **Disagree** gives **Contradicted**, quoting the comment.
   - **I would rather not answer** declines.
   - Up to 5 open requests at a time; a request can be cancelled while *Sent*.

### 6.19 HR applicant Verification tab: blind before reveal, evidence after, confirm/overrule, re-match

1. `hr@acme.test` → **Frontend Engineer (React)** → **Applicants** → Asha (shows as *Candidate #A-…*) → **Verification** tab.
   - **Expect:** "Results from sources the candidate connected and chose to share" (React Verified via GitHub, website, badge and reference), with de-identified reasons ("the account", "repository A"), integrity notes and probe questions.
   - **Sources stay hidden until the candidate is revealed**: each card says "Reveal the candidate to see sources" and shows only an evidence count.
   - Results Asha did **not** share do not appear.
2. **Evidence and assessments** tab → **Shortlist**. Back on **Verification**: evidence items (URLs, titles, hashes) and Asha's notes are now visible.
3. On React (GitHub) → **Confirm** → "Confirm: the result matches what I see" → optional note → **Confirm result**.
   - **Expect:** the claim's tier becomes **Corroborated** for Acme Labs.
   - After a few seconds, on **Evidence and assessments** the **React** requirement shows tier **Corroborated evidence**.
   - **Overrule** requires a note and does not lift the tier.
   - Audit: `VERDICT_REVIEWED`.
4. Seeded alternative: Backend Engineer (Java) → Casey (already revealed) → **Verification**. Her React/TypeScript/Python verdicts appear with evidence.
   - Confirming React lifts it to Corroborated in the tab.
   - Backend Engineer has no React requirement, so the evidence table does not change. Her application to Frontend Engineer would show it.
5. `interviewer@acme.test` has no Verification tab (403 by design).

### 6.20 Admin verification settings, shadow queue labelling, metrics

1. `admin@acme.test` → **Administration → Verification**.
   - **Mode and connectors:** Mode **Visible** (seeded), with GitHub, GitLab, Website, Document, Open Badge and Reference all on.
2. **Shadow queue:**
   - Shared verdicts of candidates who applied to Acme jobs. Unlabelled ones come first; blind candidates show no evidence.
   - Pick a verdict in the 5-option picker (what *you* think the right verdict is), add an optional note → **Save label**.
   - **Expect:** the item shows your label, and the button becomes **Update label**.
3. **Metrics:**
   - Labelled count, agreement, false-corroboration rate, Cohen's kappa, by-verifier and by-verdict tables.
   - The readiness checklist needs 150 labels, FCR ≤ 2% and kappa ≥ 0.70 before a real organisation should go Visible.
4. Switch Mode to **Shadow** → **Save changes**.
   - HR's Verification tab now shows the *Shadow mode* banner and no results, and corroborated tiers drop after re-matching.
   - **Switch back to Visible.** The confirmation warns that readiness is not met; accept it for the demo.
5. Turning a connector off hides that connector's results from Acme's staff and stops them lifting matching.
6. `hr@acme.test` sees the queue and metrics but not the settings (ADMIN only).

### 6.21 Notifications and inboxes

Both portals → **Notifications**.
- **Expect:** status changes, work samples, interviews and verification results, with an unread badge.
- *Mark as read* and *mark all read* work.

### 6.22 Other core checks (unchanged since 9ad341a)

- **Invitations:** Admin → **Invitations** → invite `newhr@example.com` as HR manager. Mailpit receives the invitation; the link opens **Accept invite**.
- **Staff accounts:** disable or enable a user.
- **Job oversight:** a Published job offers only On hold and Closed; reasons are required.
- **Second organisation:** sign out, then http://localhost:5174/signup → "Test Org 2". **Expect:** an empty workspace with none of Acme's data.
- **Forgot password:** a reset link arrives in Mailpit.
- **Lockout:** 5 wrong passwords lock the account for 15 minutes.
- **Resilience:** `docker compose stop ai-service`, upload a resume (it stays queued), `docker compose start ai-service`. **Expect:** it completes on its own within a few minutes.
- **Change password** (HR) and **Withdraw** (candidate) work.

---

## 7. Verification demo script (5 minutes)

### 7.1 Prepare the night before (15 minutes)

1. Run `docker compose down -v`, then `docker compose up --build`, so the database is clean and freshly seeded. Wait for `Started RecruitProApplication`.
2. `hr@acme.test`: create and publish **Frontend Engineer (React)** with React (must), TypeScript (must) and Python (nice) (6.5).
3. Candidate portal:
   - Register **Asha Synthetica / `asha.demo@example.com`**.
   - Upload `resume-asha.pdf` and wait for *Analysis ready*.
   - Apply to Frontend Engineer (React).
4. Optional, to save live time:
   - Connect `https://demo-portfolio.example` and the badge `https://demo-badges.example/assertions/react-1`, run both and share them.
   - Send the reference request (6.18 step 1) **but do not answer it yet**.
5. Open the tabs you need:
   - Window 1 (normal): HR portal, signed in as `hr@acme.test`.
   - Window 2 (private): candidate portal, signed in as Asha.
   - Mailpit.
   - A spare tab signed in as `admin@acme.test` (Overview).

### 7.2 Live (5 minutes)

| Time | Where | Do | Say / show |
|---|---|---|---|
| 0:00 | Candidate (Asha) → **Strengthen your application** | Connect GitHub → **Connect with my username instead** → `demo-asha` → consent → **Connect source** | "The candidate connects her own work. Nothing is read without consent." |
| 0:40 | Prove ownership dialog | **Check now** | "She proves the account is hers with a one-time code in her bio. The demo fixture plays GitHub here; in production it reads the real bio." **Proven** |
| 1:00 | Sources | **Run verification** | Results arrive in seconds: React and TypeScript **Verified**, Python **Partly verified** with an interview question. |
| 1:30 | Ledger → React | Open it | "Every result names its evidence: repositories, retrieved time, content hash. No stars or follower counts are used." → **Share with employers** (and TypeScript). |
| 2:00 | Mailpit | Open the reference email → link | Referee page → **Confirm** → identity tick → **Send answers**. "A manager at the employer's own domain confirms it, so the result is Verified." |
| 2:40 | HR → Frontend Engineer (React) → Applicants | Open *Candidate #A-…* → **Verification** | "HR sees shared results with de-identified reasons. Sources are hidden while the review is blind." |
| 3:20 | **Evidence and assessments** | **Shortlist** → back to **Verification** | Evidence is now visible. **Confirm** React → tier **Corroborated**. |
| 4:00 | **Evidence and assessments** | Wait a few seconds | The React requirement now shows **Corroborated evidence**. "A human confirmed it; the AI never decides." |
| 4:30 | Admin → **Verification** | Show the shadow queue and metrics | "Before an organisation turns this on, its reviewers label results in shadow mode. We measure false corroboration and agreement." |
| 4:50 | Admin → **Overview** | Show health | AI service and Verification agents **UP**. |

**Fallback if Asha's run fails live:** switch to the seeded Casey. Candidate `candidate@demo.test` shows the Verified ledger. In HR, open Backend Engineer (Java) → Casey → Verification (revealed, with evidence) → Confirm.

---

## 8. Automated checks

Run them from the repository root. On PowerShell, `.venv\Scripts\Activate.ps1` may need `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once.

### 8.1 Backend

```powershell
cd backend; .\mvnw.cmd verify; cd ..
```

**Expect:** `BUILD SUCCESS`, 0 failures and 0 errors (Section 4.1). Optional end-to-end with a running ai-service: set `RECRUITPRO_AI_IT=true` (see `AiLifecycleIntegrationTest`).

### 8.2 ai-service

```powershell
cd ai-service
python -m venv .venv; .\.venv\Scripts\Activate.ps1      # bash: python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q                 # 178 passed (one LibreOffice test may be skipped)
python -m eval.run --check          # ends with CHECK PASSED
python ..\audit\probe_matching.py   # matching probes P1-P8
deactivate; cd ..
```

### 8.3 agent-service

```powershell
cd agent-service
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
python -m pytest -q                       # 161 passed, no network used
python -m eval.run --check; $LASTEXITCODE # prints a JSON report; exit code 0; FCR 0.0, kappa 1.0
python ..\tools\sync_taxonomy.py --check  # taxonomy in sync with ai-service
deactivate; cd ..
```

The eval set is synthetic, and its labels were written by the same author as the rules. Kappa 1.0 says nothing about real-world accuracy. That is what the shadow-mode labelling in 6.20 is for.

### 8.4 Repository tools

```powershell
python -m pip install pgserver psycopg2-binary tree-sitter tree-sitter-java
python -B tools\check_migrations_pg.py            # V1-V24 apply to PostgreSQL 16
python -B tools\check_entity_schema.py            # 474 mapped columns of 41 entities: 0 errors, 0 warnings
python -B tools\java_static_check.py backend\src  # 575 files: 0 errors
```

### 8.5 Portals

```powershell
cd frontend;    pnpm install --frozen-lockfile; pnpm build; cd ..
cd hr-frontend; pnpm install --frozen-lockfile; pnpm build; cd ..
```

**Expect:** each ends with Vite's `built in …` and no TypeScript errors.

---

## 9. Optional real integrations

None of these are needed for the demo, and **none has been tested against the real provider yet**. After editing `.env`, recreate the affected containers: `docker compose up -d backend` (or `ai-service agent-service`). `restart` does not re-read `.env`.

### 9.1 GitHub OAuth App ("Sign in with GitHub" for candidates)

1. GitHub → **Settings → Developer settings → OAuth Apps → New OAuth App**:
   - Application name: `RecruitPro local`
   - Homepage URL: `http://localhost:5173`
   - **Authorization callback URL: `http://localhost:8080/api/v1/verification/oauth/github/callback`**
2. Copy the Client ID, generate a client secret, and put both in `.env`:
   ```
   GITHUB_CLIENT_ID=Iv1....
   GITHUB_CLIENT_SECRET=....
   GITHUB_OAUTH_REDIRECT_URI=http://localhost:8080/api/v1/verification/oauth/github/callback
   ```
   `APP_ENCRYPTION_KEY` must be valid; the compose default is.
3. `docker compose up -d backend`.
4. Candidate → GitHub → **Sign in with GitHub** → authorise → back on `/verification?connected=github`.
   - **Expect:** the source is **Proven** via OAuth (scope `read:user`).
   - A real login is not `demo-`, so the agent reads the real GitHub API. That needs internet; set `AGENT_GITHUB_TOKEN` to avoid the 60 requests per hour unauthenticated limit.

### 9.2 Staff SSO (Google, Microsoft, generic OIDC)

1. Register a web application with the identity provider. Its redirect URI is `http://localhost:8080/api/v1/auth/sso/<google|microsoft|oidc>/callback`:
   - Google Cloud Console: OAuth client ID, type "Web application"
   - Microsoft Entra: App registration, "Web" platform
2. Set the matching variables in `.env`:
   - `SSO_GOOGLE_CLIENT_ID/SECRET`
   - `SSO_MICROSOFT_CLIENT_ID/SECRET/TENANT_ID`
   - `SSO_OIDC_NAME/CLIENT_ID/CLIENT_SECRET/ISSUER`

   Keep `SSO_REDIRECT_BASE_URL=http://localhost:8080`. Then run `docker compose up -d backend`.
3. Admin → **Organisation** → add your company domain under **Single sign-on: allowed e-mail domains**.
4. The staff account must already exist with exactly that email (invite it). There is **no auto-provisioning**, and candidates are never matched.
5. The HR login page shows a button per configured provider. Errors come back as `/login?ssoError=<code>` with a readable message (for example `domain_not_allowed`, `no_account`).

### 9.3 LLM providers

Verdicts and matching never depend on an LLM. The LLM only writes optional summaries and requirement suggestions.

```
LLM_PROVIDER=anthropic            # AI service; or openai_compatible
AGENT_LLM_PROVIDER=anthropic      # agent service summary (optional, independent)
LLM_MODEL=<model id>              # required when a provider is set, or the service refuses to start
ANTHROPIC_API_KEY=...
# openai_compatible instead: OPENAI_BASE_URL=https://.../v1  OPENAI_API_KEY=...
```

Then run `docker compose up -d ai-service agent-service`. **Expect:** `/health` on ports 8000 and 8100 shows `"llmProvider":"anthropic"`.

---

## 10. If something goes wrong

| Symptom | Cause and fix |
|---|---|
| `mvnw verify` fails | Send the first `[ERROR]` lines (4.1). Fall back to `9ad341a` (4.1 step 3). |
| `port is already allocated` | Another program uses the port (often PostgreSQL on 5432). Find it with `netstat -ano \| findstr :5432` and stop it. |
| Build killed / `exit code 137` / very slow | Docker memory. Give Docker Desktop at least 8 GB (Settings → Resources) and retry `docker compose up --build`. |
| Docker errors about WSL | `wsl --install` in an admin PowerShell, reboot, select WSL 2 in Docker Desktop. |
| Backend exits with `app.encryption.key must be Base64 encoded` or `must decode to exactly 32 bytes` | Bad `APP_ENCRYPTION_KEY` in `.env`. Leave it empty or generate one (Section 3). |
| Backend exits with a Flyway error ("migration ... not resolved locally", "checksum mismatch", "validate failed") | The database was created by another version. Run `docker compose down -v`, then `docker compose up`. |
| Signed out right after a restart, or "Your session has expired" | Old refresh cookie (after `down -v` or a version switch). **Sign in again.** If it persists, clear site data for `localhost` (DevTools → Application → Clear storage). |
| Portal loads but every action fails | The backend is not ready yet (wait for `Started RecruitProApplication`), or you used `127.0.0.1`. Use exactly `http://localhost:5173` / `:5174`. |
| "Signing in with GitHub is not available on this server" | **Expected** without a GitHub OAuth app. Use the username route (6.16). |
| 409 "This source is already connected" | Casey already has `demo-asha`. Use the Asha account, or revoke the source first. |
| 429 on Run verification / Check now / Send request | Limits: 10 runs per day per candidate, 20 ownership checks per hour, 5 open references. Use another candidate account, or `docker compose down -v`. |
| Ownership check: "The verification service is unavailable" (503), or runs end *Failed* with `AGENT_SERVICE_UNAVAILABLE` | The agent service is down. Check `docker compose ps` and `docker compose logs agent-service`, then `docker compose start agent-service`. Queued runs retry by themselves. |
| Runs fail with `AGENT_SERVICE_REJECTED`, while the dashboard says Verification agents UP | **Token mismatch.** The backend's `AGENT_SERVICE_TOKEN` differs from the agent's (health is public, so it still shows UP). Set one value in `.env` (compose passes it to both), then `docker compose up -d backend agent-service`. |
| A run fails with `SOURCE_NOT_FOUND` for a `demo-` name | Only `demo-asha` and `demo-ravi` exist (and the hosts in 5.3). Check `http://localhost:8100/health` shows `"demoFixtures":true`. |
| Website results only *Partly verified* | The candidate's name is not "Asha Synthetica" (author marker mismatch). This is correct behaviour. |
| Badge *Contradicted* | The candidate's email is not `asha.demo@example.com` (recipient mismatch). This is correct behaviour. |
| HR Verification tab says *Shadow mode* | Admin → Verification → Mode **Visible** → Save. |
| Verification tab shows no evidence | The applicant is not revealed yet. Shortlist them (6.19). |
| No React "Corroborated evidence" in the evidence table | The job has no React requirement, the verdict was not shared, the source was not proven, or the review was Overrule. Check each condition (6.19). |
| Backend log: `Demo assessments (kit, work sample, interviews) could not be seeded` | Everything else still works. Send the stack trace that follows. Interviews and work samples can be created by hand (6.7, 6.8). |
| MFA code rejected | The Windows clock is off (Settings → Time → **Sync now**), or the same code was used twice (wait for the next 30 s code). Five wrong codes lock the account for 15 minutes. |
| `MFA_NOT_CONFIGURED` (503) | `APP_ENCRYPTION_KEY` is empty. Only possible when running the backend outside Docker. |
| No email | Check Mailpit http://localhost:8025. The sender polls every 5 s; `docker compose logs backend \| Select-String Notification`. |
| Resume stays *Queued* | `docker compose logs ai-service`. It retries with back-off once the service is back. |
| `pnpm` not found | `corepack enable; corepack prepare pnpm@10 --activate`, or use `npx pnpm@10 …`. |

---

## 11. Stop, reset, switch versions

| Goal | Command |
|---|---|
| Stop, keep data | `docker compose down` |
| Start again (no rebuild) | `docker compose up` |
| Rebuild after code changes | `docker compose up --build` |
| Rebuild one service | `docker compose up --build backend` |
| Apply `.env` changes | `docker compose up -d` (recreates changed containers) |
| **Wipe all data and reseed** | `docker compose down -v`, then `docker compose up --build` |
| Follow logs | `docker compose logs -f backend` (or `agent-service`, `ai-service`) |
| Fall back to the known-good core | `docker compose down -v; git checkout 9ad341a; docker compose up --build` |
| Return to the latest | `docker compose down -v; git checkout Kshitij; docker compose up --build` |

---

## 12. Reporting a problem

```powershell
docker compose ps                    > services.txt
docker compose logs backend          > backend.log
docker compose logs agent-service    > agent-service.log
docker compose logs ai-service       > ai-service.log
```

Send:
- the section number (for example "6.16 step 6"),
- what you did, what you expected, and what happened,
- a screenshot, ideally with DevTools → **Network** showing the failing request (URL, status, response body),
- the log files above,
- `backend-build.log` for build problems.

---

## Changes after 16 Nov evening

Review fixes (committed in `50e7b39`), on top of the patches in 0.2. Details and test names: [STATUS.md](../plan/STATUS.md) section B. Still **not compiled**: run 4.1 (`./mvnw verify`) first; new migration **V25** (`verification_oauth_states` columns).

- **GitHub OAuth (only with `GITHUB_CLIENT_ID/SECRET` set):** "Connect with GitHub" now sets a short-lived cookie `rp_oauth_github` and uses PKCE. Finish the GitHub step **in the same browser** that clicked the button; another browser or a blocked cookie ends on `?error=BROWSER_MISMATCH`. `GITHUB_OAUTH_REDIRECT_URI` must point at the same API host the portal calls (default `http://localhost:8080/...`). Register the same callback URL in the GitHub OAuth app.
- **6.18 references:** the e-mail now names the candidate by **first name only** ("Asha asked you for a reference", not "Asha Synthetica ...").
- **HR sign-up e-mail:** the verification link `http://localhost:5174/verify-email?token=...` now opens a page that confirms the address (it used to show "Page not found").
- **Deleting a claim** (or changing a corrected fact's value) withdraws that claim's verdicts; staff no longer see verdicts of subjects that disappeared.
- **Shadow queue (6.20):** verdicts of connectors switched off in the admin settings no longer appear.
- **MFA:** if an organisation requires MFA and `APP_ENCRYPTION_KEY` is missing, staff sign-in is refused with 503 `MFA_NOT_CONFIGURED` (it used to skip MFA). The compose dev key avoids this locally.
- **agent-service:** commit dates before a repository's creation no longer count towards the 30-day span (new note `BACKDATED_HISTORY`); demo verdicts are unchanged. `pytest`: 176 passed; `eval.run --check`: 83 cases, FCR 0.0, kappa 1.0. A PROFILE_BIO_CODE target without `github:`/`gitlab:` now answers 422 `BAD_TARGET` instead of 500.
