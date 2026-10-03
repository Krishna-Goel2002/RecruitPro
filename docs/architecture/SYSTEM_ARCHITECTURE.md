# System Architecture Document

## AI-Powered End-to-End Recruitment & Hiring Management Platform

**Version:** 1.0  
**Phase:** 0 — Architecture & Foundation  
**Date:** October 2026

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
│  │          MySQL 8.x              │  │
│  │   recruitment_platform_db       │  │
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

**Purpose:** Provides the user interface for all actors (HR, Recruiters, Candidates, Admins).

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
- Render role-based dashboards (HR, Candidate, Admin)
- Handle form inputs and validation
- Display AI recommendations with explanations
- Manage file uploads (resumes)
- Show analytics dashboards

### 2.2 Backend — Spring Boot Modular Monolith

**Purpose:** The primary business logic engine. Handles authentication, authorization, CRUD operations, workflow orchestration, and serves as the gateway to the AI service.

| Aspect | Detail |
|--------|--------|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Security | Spring Security + JWT |
| ORM | Spring Data JPA + Hibernate |
| Build | Maven |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Database | MySQL 8.x |
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

### 2.4 Database — MySQL

**Purpose:** Single relational database serving the Spring Boot backend.

| Aspect | Detail |
|--------|--------|
| Engine | MySQL 8.x |
| Schema | `recruitment_platform_db` |
| Managed By | Spring Data JPA + Hibernate (DDL auto-generation in dev) |
| Migrations | Flyway or Liquibase (to be added in Phase 1) |

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
         
Communication is synchronous.
Backend sends candidate/job data → AI returns analysis.
Backend stores the result in MySQL.
```

### 3.3 Backend ↔ Database

```
Backend  ──[JDBC / JPA]──►  MySQL
         ◄──[JDBC / JPA]──
```

---

## 4. Security Architecture

### 4.1 Authentication Flow

```
1. User submits credentials (email + password)
2. Backend validates credentials against database
3. Backend generates JWT (access token + refresh token)
4. Frontend stores tokens securely
5. All subsequent requests include: Authorization: Bearer <access_token>
6. Backend validates JWT on every request via Spring Security filter chain
```

### 4.2 Authorization Model

| Role | Permissions |
|------|------------|
| **ADMIN** | Full system access, user management, system configuration |
| **HR_MANAGER** | Create jobs, review candidates, manage hiring pipeline, make decisions |
| **RECRUITER** | View jobs, screen candidates, schedule interviews |
| **INTERVIEWER** | View assigned interviews, submit feedback |
| **CANDIDATE** | Register, apply for jobs, upload resume, view status |

### 4.3 Security Principles

- Passwords hashed with BCrypt
- JWT with configurable expiry
- Role-based access control (RBAC) on all endpoints
- CORS configured for frontend origin only
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
  GET    /api/v1/jobs
  POST   /api/v1/jobs
  GET    /api/v1/jobs/{id}
  PUT    /api/v1/jobs/{id}
  DELETE /api/v1/jobs/{id}
  GET    /api/v1/jobs/{id}/applications
  POST   /api/v1/candidates/{id}/resume
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
│                │   MySQL   │                │
│                │   :3306   │                │
│                └───────────┘                │
└─────────────────────────────────────────────┘
```

---

## 8. Cross-Cutting Concerns

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
