# Development Conventions & Standards

## AI-Powered Recruitment & Hiring Management Platform

**Version:** 1.0  
**Phase:** 0 — Architecture & Foundation

---

## 1. Code Organization

### 1.1 Backend (Java / Spring Boot)

**Base Package:** `com.recruitpro`

Each module follows a consistent internal structure:

```
com.recruitpro.{module}/
├── controller/      # REST controllers (@RestController)
├── service/         # Business logic interfaces and implementations
├── repository/      # Spring Data JPA repositories
├── entity/          # JPA entity classes
├── dto/             # Data Transfer Objects (request/response)
├── mapper/          # Entity ↔ DTO mappers
└── exception/       # Module-specific exceptions (if any)
```

**Shared / Cross-cutting:**
```
com.recruitpro.common/
├── config/          # Spring configuration classes
├── security/        # Security filters, JWT utilities
├── dto/             # Shared DTOs (ApiResponse, PageResponse)
├── exception/       # Global exception handler, custom exceptions
└── util/            # Utility classes
```

### 1.2 Frontend (React / TypeScript)

```
src/
├── components/      # Reusable UI components
│   ├── common/      # Buttons, inputs, modals, etc.
│   └── layout/      # Header, sidebar, footer
├── pages/           # Page-level components (one per route)
├── services/        # API call functions (Axios)
├── hooks/           # Custom React hooks
├── context/         # React Context providers
├── types/           # TypeScript type definitions
├── utils/           # Helper functions
├── assets/          # Images, icons, fonts
└── styles/          # Global and module CSS
```

### 1.3 AI Service (Python / FastAPI)

```
app/
├── api/             # Route definitions
│   └── v1/          # Versioned endpoints
├── core/            # Configuration, security
├── models/          # Pydantic models (request/response schemas)
├── services/        # Business logic
├── ml/              # ML/NLP model code (future)
└── utils/           # Helper functions
```

---

## 2. Naming Conventions

### 2.1 Java

| Element | Convention | Example |
|---------|-----------|---------|
| Package | lowercase, dot-separated | `com.recruitpro.auth.controller` |
| Class | PascalCase | `JobController`, `CandidateService` |
| Interface | PascalCase | `CandidateService` |
| Implementation | PascalCase + Impl | `CandidateServiceImpl` |
| Method | camelCase | `findByEmail()`, `createJob()` |
| Variable | camelCase | `candidateId`, `jobTitle` |
| Constant | UPPER_SNAKE_CASE | `MAX_FILE_SIZE`, `JWT_EXPIRY` |
| Entity | Singular PascalCase | `Job`, `Candidate`, `Application` |
| Table | snake_case, plural | `jobs`, `candidates`, `applications` |
| Column | snake_case | `first_name`, `created_at` |
| DTO | PascalCase + Request/Response | `JobCreateRequest`, `JobResponse` |
| Repository | PascalCase + Repository | `JobRepository` |
| Controller | PascalCase + Controller | `JobController` |

### 2.2 TypeScript / React

| Element | Convention | Example |
|---------|-----------|---------|
| Component | PascalCase | `JobCard`, `CandidateList` |
| File (component) | PascalCase.tsx | `JobCard.tsx` |
| File (utility) | camelCase.ts | `apiClient.ts` |
| Function | camelCase | `fetchJobs()`, `handleSubmit()` |
| Variable | camelCase | `jobList`, `isLoading` |
| Constant | UPPER_SNAKE_CASE | `API_BASE_URL` |
| Type / Interface | PascalCase | `Job`, `CandidateProfile` |
| CSS Module | camelCase.module.css | `jobCard.module.css` |

### 2.3 Python

| Element | Convention | Example |
|---------|-----------|---------|
| Module | snake_case | `resume_parser.py` |
| Class | PascalCase | `MatchingEngine` |
| Function | snake_case | `parse_resume()`, `compute_score()` |
| Variable | snake_case | `match_score`, `candidate_data` |
| Constant | UPPER_SNAKE_CASE | `MODEL_PATH`, `MAX_RETRIES` |
| Pydantic Model | PascalCase | `ResumeParseRequest` |

---

## 3. API Conventions

### 3.1 URL Pattern

```
/api/v1/{module}/{resource}
```

### 3.2 HTTP Methods

| Method | Purpose | Example |
|--------|---------|---------|
| GET | Retrieve resource(s) | `GET /api/v1/jobs` |
| POST | Create resource | `POST /api/v1/jobs` |
| PUT | Full update | `PUT /api/v1/jobs/{id}` |
| PATCH | Partial update | `PATCH /api/v1/jobs/{id}/status` |
| DELETE | Remove resource | `DELETE /api/v1/jobs/{id}` |

### 3.3 Naming Rules

- Use **nouns**, not verbs: `/api/v1/jobs` not `/api/v1/getJobs`
- Use **plural** for collections: `/api/v1/candidates` not `/api/v1/candidate`
- Use **path params** for identification: `/api/v1/jobs/{id}`
- Use **query params** for filtering: `/api/v1/jobs?status=PUBLISHED&page=0&size=20`
- Use **nested paths** for relationships: `/api/v1/jobs/{id}/applications`

---

## 4. Git Conventions

### 4.1 Branch Naming

```
main                          # Production-ready code
develop                       # Integration branch
feature/{module}-{description}  # New features
bugfix/{description}          # Bug fixes
hotfix/{description}          # Urgent production fixes
```

**Examples:**
```
feature/auth-jwt-login
feature/hr-job-creation
feature/ai-resume-parser
bugfix/application-status-update
```

### 4.2 Commit Messages

Follow Conventional Commits:

```
{type}({scope}): {description}

Types:
  feat     — New feature
  fix      — Bug fix
  docs     — Documentation only
  style    — Formatting, no code change
  refactor — Code restructuring
  test     — Adding/updating tests
  chore    — Build, config, tooling

Examples:
  feat(auth): add JWT token generation
  fix(application): correct status transition validation
  docs(architecture): update ER diagram
  chore(backend): add Spring Security dependency
```

### 4.3 Commit Rules

- Each commit should represent a single logical change
- Never commit broken code to `main` or `develop`
- Write meaningful commit messages

---

## 5. Error Handling

### 5.1 Backend

- Use a **global exception handler** (`@ControllerAdvice`)
- Define custom exceptions: `ResourceNotFoundException`, `DuplicateResourceException`, `UnauthorizedException`
- Always return structured error responses (never raw stack traces)
- Log errors with context (user, endpoint, parameters)

### 5.2 Frontend

- Use Axios interceptors for global error handling
- Display user-friendly error messages
- Handle 401 errors with automatic redirect to login
- Handle network errors gracefully

### 5.3 AI Service

- Use FastAPI exception handlers
- Return structured error responses matching backend format
- Log errors with request context

---

## 6. Testing Conventions

| Layer | Framework | Naming |
|-------|-----------|--------|
| Backend Unit | JUnit 5 + Mockito | `{Class}Test.java` |
| Backend Integration | Spring Boot Test | `{Class}IntegrationTest.java` |
| Frontend Unit | Jest + React Testing Library | `{Component}.test.tsx` |
| AI Service | pytest | `test_{module}.py` |

---

## 7. Documentation Conventions

- All public API endpoints must have Swagger/OpenAPI annotations
- All entity classes must have Javadoc comments
- All service interfaces must document method contracts
- Complex business logic must have inline comments
- Architecture decisions must be documented in `docs/architecture/`

## 8. Authentication & Authorization (Phase 2)

- Run backend checks from `backend/`: `mvnw.cmd clean test` and `mvnw.cmd clean compile` (or `./mvnw` on Unix-like shells).
- Public authentication routes are `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, and `POST /api/v1/auth/logout`. Refresh and logout authenticate using the refresh-token body. Health and Swagger endpoints are public; application routes and non-health actuator routes require authentication.
- Registration always creates a `CANDIDATE`, regardless of any submitted role. Assign `ADMIN`, `HR_MANAGER`, `RECRUITER`, or `INTERVIEWER` only through trusted provisioning.
- Each user has one role. Spring authorities use the `ROLE_` prefix (for example `ROLE_HR_MANAGER`), while API responses and `users.role` use the enum name without the prefix.
- Access tokens are signed JWTs. Refresh tokens are opaque, persisted only as SHA-256 hashes, single-use, rotated on refresh, and revocable through logout. Default lifetimes are 24 hours and 7 days respectively.
- Supply `JWT_SECRET` in every environment (at least 256 bits); production also requires `CORS_ALLOWED_ORIGINS` set to the deployed frontend origin list. Keep credentials in environment variables, never in committed configuration.
- Auth and API errors use the shared `ApiResponse` shape: `success`, `message`, optional `errors`, and `timestamp`. Authentication failures return 401; authorization failures return 403.

## 9. HR Job Management (Phase 3)

- Jobs are created as DRAFT. Only ADMIN and HR_MANAGER can create, edit, transition, or delete; RECRUITER has read-only list/detail access; INTERVIEWER and CANDIDATE are denied.
- `PUT /api/v1/jobs/{id}` and all requirement mutations are limited to DRAFT jobs. Physical job deletion is limited to DRAFT; other statuses are retained.
- Lifecycle transitions are DRAFT → PUBLISHED/CANCELLED, PUBLISHED → ON_HOLD/CLOSED, and ON_HOLD → PUBLISHED/CLOSED. CLOSED and CANCELLED are terminal. Never change lifecycle through the full update API.
- Publishing requires title, description, department, location, job type, experience level, positive vacancies, valid non-negative salary range, and a future deadline if provided. A structured requirement is not mandatory to publish; deadline itself is optional.
- List parameters: page default 0, size default 20 (1–100), status, department (case-insensitive exact), location (case-insensitive substring), jobType, search (title substring), sort (`createdAt`, `title`, `department`, `location`, `status`, `deadline` only; default `createdAt`), and direction (default `DESC`).
- Provision HR profiles through trusted account provisioning. Job creation does not silently create a profile; it returns a clear error when none exists.
- V4 adds `hr_profiles`, `jobs`, and `job_requirements`; enum values use strings and JPA schema generation remains `validate`.

## 10. Candidate Portal (Phase 4)

- Candidate identity comes from the authenticated principal. Do not accept candidate or user IDs from client input as an ownership boundary.
- Public registration continues to force the CANDIDATE role and provisions the candidate profile transactionally.
- Candidates see only PUBLISHED jobs; unpublished direct lookups return 404. Existing HR and recruiter job permissions stay unchanged.
- Keep resumes in private storage configured with `FILE_STORAGE_PATH`. Use generated opaque names, never a client filename for a storage path, and serve downloads only after resolving the resume through the authenticated candidate.
- Accept only the configured PDF, DOCX, and DOC formats, validate extension and file content, and cap file size at 10 MB. Do not expose storage keys or absolute paths in API responses.
- Keep resume history. Every application references the exact resume row selected at submission; uploading another resume must not change prior applications.
- Applications require an owned resume and an open PUBLISHED job. Enforce duplicate prevention in service logic and with a unique `(candidate_id, job_id)` database constraint. Phase 4 statuses are SUBMITTED and WITHDRAWN; only SUBMITTED applications can be withdrawn by a candidate.
- V5–V7 add candidate profiles, resume metadata, and applications. Never edit V1–V4; Flyway remains the schema authority and Hibernate uses `validate`.

---

*This document defines the development standards for all contributors. Follow these conventions consistently across all phases.*
