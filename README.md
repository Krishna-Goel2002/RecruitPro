# AI-Powered End-to-End Recruitment & Hiring Management Platform

A comprehensive, AI-assisted recruitment lifecycle management system built as a college major project. The platform combines human decision-making with AI-powered candidate screening to streamline the entire hiring process — from job creation through onboarding.

## Architecture Overview

```
┌──────────────┐
│   Frontend   │  React + TypeScript
│ Candidate 5173 / HR 5174 (React + TypeScript SPAs)
└──────┬───────┘
       │ REST API
       ▼
┌──────────────┐         REST API        ┌──────────────────┐
│  Spring Boot │ ◄──────────────────────► │  Python FastAPI   │
│   Backend    │                          │   AI Service      │
│  (Monolith)  │                          │                   │
│  Port 8080   │                          │  Port 8000        │
└──────┬───────┘                          └──────────────────┘
       │ JPA/Hibernate
       ▼
┌──────────────┐
│    MySQL     │
│   Database   │
│  Port 3307   │
└──────────────┘
```

### Core Principle: Human-in-the-Loop AI

AI provides analysis, matching scores, ranking, and explanations.  
**AI does NOT make final hiring decisions.** All decisions remain with authorized HR/recruiters.

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Frontends | Candidate and HR portals, React, TypeScript, Vite |
| Backend | Java 17, Spring Boot 3.x, Spring Security, Spring Data JPA |
| Database | MySQL 8.x |
| AI Service | Python 3.11+, FastAPI |
| Authentication | JWT |
| Build (Backend) | Maven |
| Build (Frontend) | npm / Vite |
| API Documentation | OpenAPI / Swagger |
| Version Control | Git |

## Project Structure

```
Major Project/
├── backend/                  # Spring Boot modular monolith
│   ├── src/main/java/        # Java source code (modular packages)
│   ├── src/main/resources/   # Configuration, SQL, templates
│   ├── src/test/             # Unit and integration tests
│   └── pom.xml               # Maven build configuration
│
├── frontend/                 # React TypeScript SPA
│   ├── src/                  # React components, pages, services
│   ├── public/               # Static assets
│   └── package.json          # npm configuration
├── hr-frontend/              # HR job and applicant review SPA
│   ├── src/                  # HR sign-in and recruitment workspace
│   └── package.json
│
├── ai-service/               # Python FastAPI AI microservice
│   ├── app/                  # FastAPI application code
│   ├── models/               # ML/NLP models (future)
│   ├── tests/                # Python tests
│   └── requirements.txt      # Python dependencies
│
├── docs/                     # Project documentation
│   ├── architecture/         # Architecture decisions & diagrams
│   ├── api/                  # API contracts
│   ├── database/             # ER diagrams & schema docs
│   └── guides/               # Development guides
│
└── README.md                 # This file
```

## System Modules

| # | Module | Owner Service | Current scope |
|---|--------|--------------|---------------|
| 1 | Authentication & Users | Backend | Implemented: candidate registration, JWT login/refresh/logout, trusted HR provisioning |
| 2 | HR & Recruitment | Backend | Implemented: job lifecycle, structured requirements, applicant review and decision history |
| 3 | Candidate Management | Backend | Implemented: profile and private resume history |
| 4 | Applications | Backend | Implemented: apply, history, detail, withdrawal, shortlist and rejection |
| 5 | Resume Intelligence | AI Service + Backend | Implemented: local PDF/DOCX extraction; no OCR, legacy DOC extraction, or external model |
| 6 | Matching | AI Service + Backend | Implemented: deterministic requirement evidence coverage; HR retains decision authority |
| 7 | Assessment | Backend | Not implemented |
| 8 | Interview | Backend | Not implemented |
| 9 | Hiring (Selection/Offer) | Backend | Not implemented |
| 10 | Onboarding | Backend | Not implemented |
| 11 | Analytics | Backend | Not implemented |

## Development Phases

| Phase | Description | Status |
|-------|------------|--------|
| 0 | Architecture & Foundation | ✅ Complete |
| 1 | Backend Foundation | ✅ Complete |
| 2 | Authentication & Authorization | ✅ Complete |
| 3 | HR & Job Management | ✅ Complete |
| 4 | Candidate Portal | 🚧 Implemented; verification pending |
| 5 | Resume Intelligence | ✅ Implemented; runtime verification pending |
| 6 | AI Matching & Ranking | ✅ Implemented; runtime verification pending |
| 7 | Assessment | ⬜ Planned |
| 8 | Interview Management | ⬜ Planned |
| 9 | Selection, Offer & Onboarding | ⬜ Planned |
| 10 | Analytics | ⬜ Planned |
| 11 | Complete Integration | 🚧 Candidate and HR paths implemented; live verification pending |
| 12 | Testing, Security & Deployment | 🚧 Automated checks available; release checks pending |

Phase 3 adds authenticated HR job management and structured job requirements. Job lifecycle, access rules, filters, and publishing validation are documented in [the architecture guide](docs/architecture/SYSTEM_ARCHITECTURE.md) and [development conventions](docs/guides/DEVELOPMENT_CONVENTIONS.md).

## Prerequisites

Before running the project, ensure the following are installed:

- **Java 17** (JDK) — [Download](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- **Maven 3.9+** — [Download](https://maven.apache.org/download.cgi)
- **Node.js 18+ & pnpm** — [Download](https://nodejs.org/)
- **Python 3.10+** for the AI service — [Download](https://www.python.org/downloads/)
- **MySQL 8.x** — [Download](https://dev.mysql.com/downloads/)
- **Git** — [Download](https://git-scm.com/)

## Getting Started

For a safe local demonstration, use the isolated rebuild-test launcher and the steps in [the release-readiness and demo guide](docs/guides/RELEASE_READINESS.md). The ordinary `dev` profile defaults to `recruitment_platform_db`; set an explicitly isolated `DB_URL` before starting it. Do not use the protected database names listed in the release guide.

### Backend Authentication (Phase 2)

Run `mvnw.cmd test` from `backend/` (use `./mvnw test` on Unix-like shells). Local development uses the `dev` profile and also requires `JWT_SECRET`; production requires `JWT_SECRET` and `CORS_ALLOWED_ORIGINS`. Provide a strong signing secret of at least 256 bits and the exact trusted frontend origins.

Public auth routes: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, and `POST /api/v1/auth/logout`. Refresh and logout authenticate using the refresh-token body. Registration always assigns `CANDIDATE`; privileged roles must be provisioned through a trusted channel. Access tokens expire after 24 hours by default; refresh tokens expire after 7 days and rotate on use.

### Candidate Portal (Phase 4)

The backend and candidate portal provide profile self-service, published-job browsing, private PDF/DOC/DOCX resume history, application submission, application detail and withdrawal. Candidate resume files are stored outside the frontend under `FILE_STORAGE_PATH` (default `./uploads`); configure a private writable path for the backend process. Uploads are limited to 10 MB and checked against supported document signatures. In `frontend/`, install dependencies with `pnpm install`, then run `pnpm build`.

### HR Workspace

The HR portal supports private sign-in, draft job and requirement management, publishing, hold/resume/close transitions, applicant evidence review, shortlist/reject decisions, comments and decision history. Privileged accounts must be created through trusted provisioning; public registration always creates a candidate. The local opt-in HR manager provisioner is restricted to the `dev` profile and a loopback MySQL URL.

### Local demo processes

```bash
# Start MySQL 8 on port 3307 and confirm the isolated rebuild-test database is present.
# In an interactive PowerShell terminal, start the backend with its safety checks:
.\test-data\scripts\Start-RecruitProRebuildTest.ps1

# In separate terminals, run the candidate and HR portals:
cd frontend; pnpm dev
cd hr-frontend; pnpm dev
```

The isolated launcher requires a hidden interactive MySQL password prompt, targets only `recruitment_platform_rebuild_test_20261027`, disables Flyway, sets Hibernate to `validate`, uses dedicated resume storage, and keeps `AI_RESUME_QUEUE_ENABLED=false`. Resumes therefore remain pending in this demo unless a separately reviewed local AI configuration is used. The AI service requires the same locally generated `AI_SERVICE_TOKEN` in both processes; it must remain private and is never sent to an external model.

## License

This project is developed as an academic major project.
