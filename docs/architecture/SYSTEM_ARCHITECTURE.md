# System Architecture Document

## AI-Powered End-to-End Recruitment & Hiring Management Platform

**Version:** 1.1  
**Phase:** 0 — Architecture & Foundation (updated November 2026 for PostgreSQL, organisations and the background queue)  
**Date:** October 2026

> **Update (WP-BE-A, November 2026).** The database is PostgreSQL 16 (port 5432), the backend runs on Java 21 / Spring Boot 3.5, staff users belong to organisations, access tokens last 15 minutes with refresh tokens in an httpOnly cookie, AI work runs through a database-backed background queue, and the audit trail is persisted in `audit_events`. The current plan is [docs/plan/IMPLEMENTATION_PLAN.md](../plan/IMPLEMENTATION_PLAN.md); backend platform details are in [docs/plan/WP-BE-A-NOTES.md](../plan/WP-BE-A-NOTES.md). Sections below were corrected where they had become false.

---

## 1. Architectural Vision

This system implements a **modular monolith** architecture with a **separate AI microservice**. The design balances simplicity (appropriate for a college major project) with real-world architectural principles that enable independent evolution of components.

### 1.1 Key Architectural Principles

| Principle | Description |
|-----------|------------|
| **Human-in-the-Loop AI** | AI provides analysis and recommendations; humans make final decisions |
| **Modular Monolith** | Logically separated modules within a single deployable Spring Boot application |
| **Separation of Concerns** | AI/ML workloads isolated in a Python service; business logic in Java |
| **API-First** | All inter-service and client-server communication via well-defined REST APIs |
| **Domain-Driven Boundaries** | Each module owns its entities and business logic |
| **Progressive Enhancement** | System designed to grow incrementally across development phases |

### 1.2 High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                              │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              React + TypeScript SPA                       │    │
│  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐          │    │
│  │  │Auth  │ │HR    │ │Cand. │ │Assess│ │Analy.│          │    │
│  │  │Pages │ │Pages │ │Pages │ │Pages │ │Pages │          │    │
│  │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘          │    │
│  └────────────────────────┬────────────────────────────────┘    │
└───────────────────────────┼─────────────────────────────────────┘
                            │ REST API (JSON over HTTPS)
                            │ JWT Bearer Tokens
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                              │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │           Spring Boot Modular Monolith                    │    │
│  │                                                           │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │    │
│  │  │  Auth   │ │   HR    │ │Candidate│ │  App    │       │    │
│  │  │ Module  │ │ Module  │ │ Module  │ │ Module  │       │    │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘       │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │    │
│  │  │  AI     │ │ Assess  │ │Interview│ │ Hiring  │       │    │
│  │  │ Client  │ │ Module  │ │ Module  │ │ Module  │       │    │
│  │  └────┬────┘ └─────────┘ └─────────┘ └─────────┘       │    │
│  │       │      ┌─────────┐ ┌─────────┐                    │    │
│  │       │      │Onboard  │ │Analytics│                    │    │
│  │       │      │ Module  │ │ Module  │                    │    │
│  │       │      └─────────┘ └─────────┘                    │    │
│  │       │                                                   │    │
│  │  ┌────┴──────────────────────────────────────────────┐   │    │
│  │  │          Common / Shared Layer                      │   │    │
│  │  │  (Security, DTOs, Exceptions, Utils, Config)       │   │    │
│  │  └───────────────────────────────────────────────────┘   │    │
│  └────────────────────────┬────────────────────────────────┘    │
└───────────────────────────┼─────────────────────────────────────┘
                            │ JPA / Hibernate
                            ▼
┌───────────────────────────────────────┐
│          DATA LAYER                    │
│  ┌─────────────────────────────────┐  │
│  │        PostgreSQL 16            │  │
│  │          recruitpro             │  │
│  └─────────────────────────────────┘  │
└───────────────────────────────────────┘

          REST API (Internal)
┌─────────────────────────────────────────┐
│         AI SERVICE LAYER                 │
│  ┌─────────────────────────────────────┐│
│  │       Python FastAPI Service        ││
│  │  ┌──────────┐ ┌──────────────────┐  ││
│  │  │ Resume   │ │ Job Analysis     │  ││
│  │  │ Parser   │ │ Engine           │  ││
│  │  └──────────┘ └──────────────────┘  ││
│  │  ┌──────────┐ ┌──────────────────┐  ││
│  │  │ Matching │ │ Ranking &        │  ││
│  │  │ Engine   │ │ Explanation      │  ││
│  │  └──────────┘ └──────────────────┘  ││
│  └─────────────────────────────────────┘│
└─────────────────────────────────────────┘
```

---

## 2. Component Details

### 2.1 Frontend — React TypeScript SPA

**Purpose:** Provides the user interface for all actors (HR managers, recruiters, candidates, interviewers, and admins).

| Aspect | Detail |
|--------|--------|
| Framework | React 18+ with TypeScript |
| Build Tool | Vite |
| Routing | React Router v6 |
| State Management | React Context + hooks (upgrade to Redux if needed) |
| HTTP Client | Axios |
| UI Library | To be decided (Material UI, Ant Design, or custom) |
| Auth | JWT stored in httpOnly cookies or secure localStorage |

**Key Responsibilities:**
- Render role-based dashboards (HR_MANAGER, RECRUITER, CANDIDATE, INTERVIEWER, ADMIN)
- Handle form inputs and validation
- Display AI recommendations with explanations
- Manage file uploads (resumes)
- Show analytics dashboards

### 2.2 Backend — Spring Boot Modular Monolith

**Purpose:** The primary business logic engine. Handles authentication, authorization, CRUD operations, workflow orchestration, and serves as the gateway to the AI service.

| Aspect | Detail |
|--------|--------|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Security | Spring Security + JWT |
| ORM | Spring Data JPA + Hibernate |
| Build | Maven |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Database | PostgreSQL 16 |
| Port | 8080 (default) |

**Module Packaging Convention:**
```
com.recruitpro
├── common/              # Shared utilities, exceptions, config
│   ├── config/
│   ├── dto/
│   ├── exception/
│   ├── security/
│   └── util/
├── auth/                # Module 1 — Authentication & Users
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   └── dto/
├── hr/                  # Module 2 — HR & Recruitment
├── candidate/           # Module 3 — Candidate Management
├── application/         # Module 4 — Applications
├── ai/                  # Module 5 — AI Client (calls Python service)
├── assessment/          # Module 6 — Assessment
├── interview/           # Module 7 — Interview
├── hiring/              # Module 8 — Selection & Offer
├── onboarding/          # Module 9 — Onboarding
└── analytics/           # Module 10 — Analytics
```

### 2.3 AI Service — Python FastAPI

**Purpose:** Isolated AI/NLP/ML service responsible for all intelligent analysis. Does NOT control recruitment workflow — only provides analysis results when called by the backend.

| Aspect | Detail |
|--------|--------|
| Language | Python 3.11+ |
| Framework | FastAPI |
| Server | Uvicorn |
| Port | 8000 (default) |
| Future NLP | spaCy, transformers, scikit-learn (Phase 5+) |

**Key Responsibilities:**
- Parse and extract structured data from resumes (PDF, DOCX)
- Analyze job requirements and extract key skills/qualifications
- Compute candidate-job matching scores
- Rank candidates for a given job
- Generate human-readable explanations for matches

**Key Constraint:** The AI service is called by the Spring Boot backend. It returns analysis results but NEVER modifies recruitment state (e.g., it cannot accept/reject candidates).

### 2.4 Database — PostgreSQL

**Purpose:** Single relational database serving the Spring Boot backend.

| Aspect | Detail |
|--------|--------|
| Engine | PostgreSQL 16 (H2 in PostgreSQL mode for automated tests) |
| Database | `recruitpro` (port 5432) |
| Managed By | Spring Data JPA + Hibernate (`ddl-auto: validate`) |
| Migrations | Flyway (`backend/src/main/resources/db/migration`) |

---

## 3. Communication Patterns

### 3.1 Frontend ↔ Backend

```
Frontend  ──[HTTP REST + JSON]──►  Backend
          ◄──[HTTP REST + JSON]──
          
Headers:
  Authorization: Bearer <JWT>
  Content-Type: application/json
```

### 3.2 Backend ↔ AI Service

```
Backend  ──[HTTP REST + JSON]──►  AI Service
         ◄──[HTTP REST + JSON]──
         
Resume parsing runs asynchronously: the upload queues a PARSE_RESUME background
job, a worker calls the AI service and stores the result in PostgreSQL. Failed calls
are retried with exponential backoff and end in a dead-letter state.
(Applicant matching still calls the AI service from the applicant list until WP-BE-B.)
```

### 3.3 Backend ↔ Database

```
Backend  ──[JDBC / JPA]──►  PostgreSQL
         ◄──[JDBC / JPA]──
```

---

## 4. Security Architecture

### 4.1 Authentication Flow

```
1. Candidate registration validates the request, hashes the password with BCrypt, and always assigns CANDIDATE.
2. Login checks the email/password pair and rejects disabled accounts.
3. Backend returns a 15-minute signed JWT access token (claims uid, role, org) in the body and a random refresh token in the portal's cookie (HttpOnly, SameSite=Strict, Path=/api/v1/auth): `rp_refresh_candidate` or `rp_refresh_staff`, selected by the `X-RecruitPro-Portal: candidate|staff` header that both portals send (plain `rp_refresh` when the header is absent). The candidate portal accepts only CANDIDATE accounts and the staff portal every other role; the wrong portal gets 403 before a session is created.
4. The refresh token is stored only as a SHA-256 hash, expires after 14 days, and is single-use within a token family.
5. Refresh rotates the token; a rotated token presented again after a 30-second grace window revokes the whole family. Logout revokes the family. Authenticated API calls use Authorization: Bearer <access_token>.
6. Spring Security verifies issuer, signature and expiration, then loads the current user, role and organisation from the database; unknown or disabled users stay unauthenticated.
7. Five failed sign-ins lock an account for 15 minutes; auth routes are rate limited per IP.
```

### 4.2 Authorization Model

| Role | Permissions |
|------|------------|
| **ADMIN** | Full system access, user management, system configuration |
| **HR_MANAGER** | Create jobs, review candidates, manage hiring pipeline, make decisions |
| **RECRUITER** | View jobs, screen candidates, schedule interviews |
| **INTERVIEWER** | View assigned interviews, submit feedback |
| **CANDIDATE** | Register, apply for jobs, upload resume, view status |

The implementation assigns one role per user (`users.role`). Public registration always creates `CANDIDATE`; staff accounts belong to one organisation (`users.organization_id`) and are created by organisation sign-up, the bootstrap runner or invitations. HR_MANAGER and RECRUITER are separate roles. Every staff query is scoped to the caller's organisation; another organisation's records behave as missing (404).

### 4.3 Security Principles

- Passwords hashed with BCrypt
- JWT with configurable expiry
- Access tokens last 15 minutes; refresh tokens 14 days. Set `JWT_SECRET` (at least 32 bytes) in each environment; there is no fallback signing key.
- Refresh tokens are opaque, hashed at rest, kept in an httpOnly cookie, rotated on use with reuse detection, and revoked by logout or a password change.
- Role-based access control (RBAC) on all endpoints
- CORS origins are configurable and credentials are allowed only for those origins; production requires `CORS_ALLOWED_ORIGINS`.
- Actuator endpoints other than `/actuator/health` require ADMIN; Swagger is disabled in the `prod` profile.
- Administrative and security events are persisted in `audit_events` (organisation, actor, client IP).
- Input validation on all API endpoints
- SQL injection prevention via parameterized queries (JPA)
- XSS prevention via proper output encoding

---

## 5. Data Flow — Complete Recruitment Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    RECRUITMENT LIFECYCLE                          │
│                                                                   │
│  HR Creates Job ──► Job Published ──► Candidates Apply           │
│                                            │                      │
│                                            ▼                      │
│                                     Resume Uploaded               │
│                                            │                      │
│                                            ▼                      │
│                              ┌─────────────────────────┐         │
│                              │   AI Service Analyzes    │         │
│                              │   • Parse resume         │         │
│                              │   • Match to job         │         │
│                              │   • Generate score       │         │
│                              │   • Provide explanation  │         │
│                              └────────────┬────────────┘         │
│                                           │                       │
│                                           ▼                       │
│                              HR Reviews AI Recommendation         │
│                                           │                       │
│                              ┌────────────┴────────────┐         │
│                              ▼                          ▼         │
│                         Shortlisted              Not Shortlisted  │
│                              │                                    │
│                              ▼                                    │
│                         Assessment                                │
│                              │                                    │
│                              ▼                                    │
│                         Interview(s)                              │
│                              │                                    │
│                              ▼                                    │
│                      Final Selection                              │
│                              │                                    │
│                     ┌────────┴────────┐                           │
│                     ▼                  ▼                           │
│                  Selected          Rejected                       │
│                     │                                             │
│                     ▼                                             │
│                Offer Generated                                    │
│                     │                                             │
│              ┌──────┴──────┐                                     │
│              ▼              ▼                                     │
│          Accepted       Declined                                  │
│              │                                                    │
│              ▼                                                    │
│          Onboarding                                               │
│              │                                                    │
│              ▼                                                    │
│          Hired ✓                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. API Design Conventions

### 6.1 URL Structure

```
/api/v1/{module}/{resource}

Examples:
  POST   /api/v1/auth/login
  POST   /api/v1/auth/register
  POST   /api/v1/auth/refresh
  POST   /api/v1/auth/logout   (revokes the refresh-token family from the portal's refresh cookie)
  GET    /api/v1/jobs
  POST   /api/v1/jobs
  GET    /api/v1/jobs/{id}
  PUT    /api/v1/jobs/{id}
  DELETE /api/v1/jobs/{id}
  POST   /api/v1/jobs/{jobId}/applications
  GET    /api/v1/candidates/me
  PUT    /api/v1/candidates/me
  POST   /api/v1/candidates/me/resumes
  GET    /api/v1/candidates/me/resumes
  GET    /api/v1/candidates/me/resumes/{resumeId}/download
  GET    /api/v1/candidates/me/applications
  GET    /api/v1/ai/match/{applicationId}
```

### 6.2 Standard Response Format

**Success:**
```json
{
  "success": true,
  "message": "Operation completed successfully",
  "data": { ... },
  "timestamp": "2026-10-03T20:50:00Z"
}
```

**Error:**
```json
{
  "success": false,
  "message": "Validation failed",
  "errors": [
    { "field": "email", "message": "Email is required" }
  ],
  "timestamp": "2026-10-03T20:50:00Z"
}
```

Authentication and authorization errors use the same shape with `success: false` and a safe `message`; validation details use the `errors` array. No separate `error` field is part of the API contract. Missing, invalid or expired access authentication returns 401; authenticated callers without the required role receive 403; rate-limited calls receive 429. Registration, login, refresh, logout, email verification, password reset, invitation preview/acceptance and organisation sign-up are public routes. Health and API documentation are public; other application and actuator endpoints are protected by default.

**Paginated:**
```json
{
  "success": true,
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  },
  "timestamp": "2026-10-03T20:50:00Z"
}
```

### 6.3 HTTP Status Codes

| Code | Usage |
|------|-------|
| 200 | Successful GET, PUT |
| 201 | Successful POST (resource created) |
| 204 | Successful DELETE |
| 400 | Bad request / validation error |
| 401 | Unauthorized (not authenticated) |
| 403 | Forbidden (not authorized) |
| 404 | Resource not found |
| 409 | Conflict (duplicate resource) |
| 500 | Internal server error |

---

## 7. Deployment Topology (Target)

```
┌─────────────────────────────────────────────┐
│              Production Environment          │
│                                              │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐ │
│  │ Frontend │  │ Backend  │  │ AI Service│ │
│  │ (Nginx)  │  │ (Tomcat) │  │ (Uvicorn) │ │
│  │ :80/443  │  │ :8080    │  │ :8000     │ │
│  └──────────┘  └────┬─────┘  └───────────┘ │
│                      │                       │
│                ┌─────┴─────┐                │
│                │ PostgreSQL│                │
│                │   :5432   │                │
│                └───────────┘                │
└─────────────────────────────────────────────┘
```

---

## 8. Cross-Cutting Concerns

### 8.1 Phase 3 — HR Job Management

The HR module owns `HRProfile`, `Job`, and `JobRequirement`. An HR profile references one existing user; each job references its creator profile; each requirement references one job. Phase 3 adds no candidate, application, assessment, AI, interview, or hiring tables.

`POST /api/v1/jobs` creates a DRAFT. Legal transitions are DRAFT → PUBLISHED or CANCELLED; PUBLISHED → ON_HOLD or CLOSED; ON_HOLD → PUBLISHED or CLOSED. CLOSED and CANCELLED are terminal. The first successful publication records `publishedAt`. Publishing requires a title, description, department, location, job type, experience level, positive vacancies, valid non-negative salary bounds, and a future deadline when supplied. A requirement row is optional. A deadline is optional and, when present on create/update, must be in the future.

ADMIN and HR_MANAGER can manage every job of their own organisation. RECRUITER can read job lists and details of the organisation. INTERVIEWER has no job access yet; CANDIDATE sees published jobs with an open deadline from every organisation. The creator profile is retained as audit ownership. ADMIN, HR_MANAGER and RECRUITER users get an HRProfile automatically (sign-up, invitation, role change, or first job creation).

Jobs support `page` (default 0), `size` (default 20, maximum 100), `status`, `department` (case-insensitive exact), `location` (case-insensitive contains), `jobType`, and `search` (title contains). Sorting is allow-listed to `createdAt`, `title`, `department`, `location`, `status`, and `deadline`; default is `createdAt,DESC`. Only DRAFT jobs and their requirements may be edited or deleted. Published, on-hold, closed, and cancelled jobs remain as historical records.

The Phase 3 API routes are `POST /api/v1/jobs`, `GET /api/v1/jobs`, `GET /api/v1/jobs/{id}`, `PUT /api/v1/jobs/{id}`, `PATCH /api/v1/jobs/{id}/status`, and `DELETE /api/v1/jobs/{id}`. Requirement routes are `POST /api/v1/jobs/{id}/requirements`, `PUT /api/v1/jobs/{id}/requirements/{requirementId}`, and `DELETE /api/v1/jobs/{id}/requirements/{requirementId}`. Requirement IDs are always resolved together with their parent job ID, preventing cross-job mutation.

### 8.2 Phase 4 — Candidate Portal

`CandidateProfile` is provisioned in the same registration transaction and uniquely references one `User`. Candidate profile, resume, and application operations derive ownership from the authenticated principal. Candidates can read only PUBLISHED jobs; unpublished direct lookups return 404. Existing ADMIN, HR_MANAGER, and RECRUITER job permissions remain unchanged.

Resumes are stored in a private local directory configured by `FILE_STORAGE_PATH` (default `./uploads`). Metadata uses generated opaque storage keys; clients see neither the key nor the filesystem path. PDF, DOCX, and legacy DOC uploads are limited to 10 MB and checked against the configured extension allow-list and file signatures/document container. Resume records are append-only history; each application references the exact resume used. Files are served only to their owning candidate.

`POST /api/v1/jobs/{jobId}/applications` requires an owned resume and a currently published job with an open deadline. A database unique constraint on `(candidate_id, job_id)` prevents duplicate applications. New applications begin as SUBMITTED; a candidate may withdraw only a SUBMITTED application, changing it to WITHDRAWN. No HR-controlled status transitions are part of Phase 4. V5 creates candidate profiles, V6 resume metadata, and V7 applications; V1–V4 remain unchanged.

| Concern | Approach |
|---------|----------|
| **Logging** | SLF4J + Logback (backend), Python logging (AI service) |
| **Error Handling** | Global exception handler (@ControllerAdvice) |
| **Validation** | Jakarta Bean Validation (backend), Pydantic (AI service) |
| **CORS** | Configured in Spring Security |
| **API Versioning** | URL path versioning (/api/v1/) |
| **File Storage** | Local filesystem (dev), cloud storage (production - future) |
| **Email** | Spring Mail (future phases) |
| **Caching** | Spring Cache abstraction (future phases) |

---

*This document will be updated as the project evolves through subsequent phases.*
