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

---

*This document defines the development standards for all contributors. Follow these conventions consistently across all phases.*
