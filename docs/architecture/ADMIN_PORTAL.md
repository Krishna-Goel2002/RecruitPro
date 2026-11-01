# RecruitPro — Admin Portal Architecture & Specification

## 1. Executive Summary

The **Admin Portal** is a production-oriented management and governance subsystem within RecruitPro. It equips authorized platform administrators with global oversight of user identities, recruitment personnel, job opportunities, applications, and system operations while preserving the Spring Boot backend as the single system of record and maintaining strict data isolation.

---

## 2. System Architecture & Component Interaction

```mermaid
flowchart TD
    subgraph Client_Layer["Frontend & UI Layer (React + Vite)"]
        AP["Admin Portal Console (hr-frontend)"]
        HP["HR Workspace (hr-frontend)"]
        CP["Candidate Portal (frontend)"]
    end

    subgraph Security_Layer["Security & Gateway Filter Chain"]
        JWT["JwtAuthenticationFilter"]
        SEC["Spring Security Authorization Filter (hasRole('ADMIN'))"]
    end

    subgraph Service_Layer["Admin Business Services"]
        AC["AdminController (/api/v1/admin/*)"]
        ADS["AdminDashboardService"]
        AUS["AdminUserService"]
        AHS["AdminHrManagementService"]
        AJS["AdminJobService"]
        AAS["AdminAuditService (Ring-Buffer + SLF4J)"]
        APS["AdminProvisioningService"]
    end

    subgraph Data_Layer["Database & Infrastructure (MySQL 8 / H2 in Tests)"]
        UR["UserRepository"]
        HRR["HRProfileRepository"]
        CPR["CandidateProfileRepository"]
        JR["JobRepository"]
        AR["ApplicationRepository"]
        ACT["Spring Boot Actuator Health"]
    end

    AP -->|Bearer JWT| JWT
    HP -->|Bearer JWT| JWT
    CP -->|Bearer JWT| JWT
    JWT --> SEC
    SEC --> AC

    AC --> ADS
    AC --> AUS
    AC --> AHS
    AC --> AJS
    AC --> AAS

    ADS --> ACT
    ADS --> UR
    ADS --> JR
    ADS --> AR

    AUS --> UR
    AUS --> CPR
    AUS --> HRR
    AUS --> AAS

    AHS --> UR
    AHS --> HRR
    AHS --> JR

    AJS --> JR
    AJS --> AR
    AJS --> AAS

    APS --> UR
```

---

## 3. Entity-Relationship Diagram (ERD)

The following diagram details the data model and structural relationships across RecruitPro, with special emphasis on the administrative domain and reverse foreign-key constraints.

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "has"
    USERS ||--o| HR_PROFILES : "owns"
    USERS ||--o| CANDIDATE_PROFILES : "owns"

    HR_PROFILES ||--o{ JOBS : "creates"
    JOBS ||--o{ JOB_REQUIREMENTS : "defines"
    JOBS ||--o{ APPLICATIONS : "receives"

    CANDIDATE_PROFILES ||--o{ RESUMES : "uploads"
    CANDIDATE_PROFILES ||--o{ APPLICATIONS : "submits"

    RESUMES ||--o{ APPLICATIONS : "attaches"
    RESUMES ||--o| RESUME_ANALYSES : "analyzed_by"
    RESUME_ANALYSES ||--o{ RESUME_ANALYSIS_FACTS : "contains"

    APPLICATIONS ||--o{ APPLICATION_DECISION_HISTORIES : "records"
    JOBS ||--o{ JOB_MATCH_RUNS : "evaluates"

    ADMIN_AUDIT_LOGS {
        string id PK
        string action
        string actor_email
        bigint actor_user_id
        string target_resource_type
        string target_resource_id
        string details
        timestamp timestamp
    }

    USERS {
        bigint id PK
        string email UK
        string password_hash
        string first_name
        string last_name
        string role "ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER, CANDIDATE"
        boolean enabled
        timestamp created_at
        timestamp updated_at
    }

    HR_PROFILES {
        bigint id PK
        bigint user_id FK,UK
        string employee_id
        string department
        string designation
        timestamp created_at
    }

    CANDIDATE_PROFILES {
        bigint id PK
        bigint user_id FK,UK
        string city
        integer years_of_experience
        text profile_summary
        timestamp created_at
    }

    JOBS {
        bigint id PK
        bigint created_by FK
        string title
        string department
        string location
        string job_type
        string experience_level
        decimal minimum_salary
        decimal maximum_salary
        integer vacancies
        string status "DRAFT, PUBLISHED, ON_HOLD, CLOSED, CANCELLED"
        timestamp deadline
        timestamp published_at
        timestamp created_at
    }

    APPLICATIONS {
        bigint id PK
        bigint job_id FK
        bigint candidate_id FK
        bigint resume_id FK
        string status "SUBMITTED, SHORTLISTED, REJECTED, WITHDRAWN"
        string review_comment
        timestamp applied_at
    }
```

---

## 4. Administrative Override & Audit Logging Sequence

When an administrator exercises an administrative status override on a job (e.g. closing or suspending an active listing), a strict validation and audit sequence is enforced:

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Administrator
    participant UI as Admin Portal (hr-frontend)
    participant Ctrl as AdminController
    participant Svc as AdminJobService
    participant Repo as JobRepository
    participant Audit as AdminAuditService
    participant Logger as SLF4J / Security Log

    Admin->>UI: Select Job & click "Override"
    UI->>Admin: Display Modal (Requires target status & mandatory reason)
    Admin->>UI: Submit targetStatus='CLOSED', reason='Restructuring'
    UI->>Ctrl: PATCH /api/v1/admin/jobs/{id}/status
    Ctrl->>Svc: overrideJobStatus(jobId, status, reason)

    rect rgb(240, 248, 255)
        Note over Svc: Validation Phase
        Svc->>Svc: Validate reason is not blank
        Svc->>Svc: Validate targetStatus is ON_HOLD, CLOSED, or CANCELLED
    end

    Svc->>Repo: findById(jobId)
    Repo-->>Svc: Job Entity
    Svc->>Repo: saveAndFlush(job with new status)
    Repo-->>Svc: Persisted Job

    rect rgb(255, 250, 240)
        Note over Svc,Audit: Audit Recording Phase
        Svc->>Audit: record("ADMIN_JOB_STATUS_OVERRIDE", "JOB", jobId, details)
        Audit->>Audit: Add AdminAuditRecord to Concurrent Ring Buffer
        Audit->>Logger: structured log (actor, target, reason)
    end

    Svc-->>Ctrl: AdminJobOverviewResponse
    Ctrl-->>UI: HTTP 200 (Success)
    UI-->>Admin: Show notification & update table
```

---

## 5. Role Transition & Profile Auto-Provisioning Flow

When an administrator updates a user's role, the system prevents privilege escalation vulnerabilities, enforces last-admin preservation, and automatically provisions or aligns the appropriate profile:

```mermaid
flowchart TD
    Start(["Admin requests Role Update"]) --> CheckSelf{"Target is Current Admin?"}

    CheckSelf -- Yes --> DemoteSelf{"Demoting Self?"}
    DemoteSelf -- Yes --> CountAdmins{"Active Admin Count > 1?"}
    CountAdmins -- No --> RejectDemote["Reject with HTTP 400 (Cannot demote last remaining admin)"]
    CountAdmins -- Yes --> ProceedUpdate["Proceed with Role Update"]
    DemoteSelf -- No --> ProceedUpdate
    CheckSelf -- No --> CheckTargetRole{"Target Current Role is ADMIN?"}

    CheckTargetRole -- Yes --> CheckTotalAdmins{"Active Admin Count > 1?"}
    CheckTotalAdmins -- No --> RejectDemote
    CheckTotalAdmins -- Yes --> ProceedUpdate
    CheckTargetRole -- No --> ProceedUpdate

    ProceedUpdate --> ApplyRole["Update User.role in Database"]
    ApplyRole --> RoleType{"New Role Type"}

    RoleType -- "HR_MANAGER or RECRUITER" --> CheckHRProfile{"HRProfile Exists?"}
    CheckHRProfile -- No --> CreateHR["Auto-provision default HRProfile"]
    CheckHRProfile -- Yes --> Audit
    CreateHR --> Audit

    RoleType -- "CANDIDATE" --> CheckCandProfile{"CandidateProfile Exists?"}
    CheckCandProfile -- No --> CreateCand["Auto-provision default CandidateProfile"]
    CheckCandProfile -- Yes --> Audit
    CreateCand --> Audit

    RoleType -- "ADMIN or INTERVIEWER" --> Audit

    Audit["Record Audit Event in Concurrent Ring-Buffer"] --> Done(["Return Updated AdminUserResponse (HTTP 200)"])
```

---

## 6. Endpoints Specification

All endpoints reside under `/api/v1/admin/**` and are secured with `@PreAuthorize("hasRole('ADMIN')")`.

| Method | Endpoint | Description | Request Body / Query | Success Response | Errors |
|--------|----------|-------------|----------------------|------------------|--------|
| `GET` | `/api/v1/admin/dashboard` | Platform metrics & health | None | `AdminDashboardMetricsResponse` | 401, 403 |
| `GET` | `/api/v1/admin/users` | List, search & filter users | `page`, `size`, `search`, `role`, `enabled` | `PageResponse<AdminUserResponse>` | 400, 401, 403 |
| `PATCH` | `/api/v1/admin/users/{id}/status` | Activate or deactivate account | `{"enabled": boolean}` | `AdminUserResponse` | 400 (self or last admin), 401, 403, 404 |
| `PATCH` | `/api/v1/admin/users/{id}/role` | Update account role | `{"role": Role}` | `AdminUserResponse` | 400 (last admin), 401, 403, 404 |
| `GET` | `/api/v1/admin/hr-team` | Directory of HR & recruiter staff | None | `List<AdminHrMemberResponse>` | 401, 403 |
| `GET` | `/api/v1/admin/jobs` | Global job postings across all HRs | `page`, `size`, `status`, `department`, `search` | `PageResponse<AdminJobOverviewResponse>` | 400, 401, 403 |
| `PATCH` | `/api/v1/admin/jobs/{id}/status` | Administrative status override | `{"status": JobStatus, "reason": String}` | `AdminJobOverviewResponse` | 400 (missing reason or invalid status), 401, 403, 404 |
| `GET` | `/api/v1/admin/audit-logs` | Query administrative audit trail | `page`, `size` | `PageResponse<AdminAuditRecord>` | 400, 401, 403 |

---

## 7. Security Safeguards & Governance Rules

1. **Zero Schema Mutations / Flyway Boundary**:
   - `Start-RecruitProRebuildTest.ps1` mandates that Flyway history match exactly V1–V10.
   - The administrative audit log is intentionally maintained in an in-memory concurrent ring buffer (`AdminAuditService`) backed by structured SLF4J security logger output, satisfying complete audit trail criteria without generating forward Flyway migrations that violate test database preflights.
2. **Last-Active-Administrator Guard**:
   - Deactivating the last active administrator account is unconditionally rejected with HTTP 400.
   - Changing the role of the last active administrator is unconditionally rejected with HTTP 400.
   - Self-deactivation by the caller is rejected to prevent accidental administrative lockout.
3. **Audit Log Privacy**:
   - Password hashes, session tokens, and sensitive candidate PII are never recorded in audit log records or log streams.
4. **Interactive Bootstrap Provisioning**:
   - `LocalAdminProvisioner` runs only in the `dev` Spring profile and throws `IllegalStateException` if `prod` is active.
   - Prompt input passwords are zeroed out (`Arrays.fill(password, '\0')`) in memory immediately after use.
   - If an administrator already exists in the database, provisioning automatically skips.

---

## 8. Maven & Windows Environment Execution Guide

On Windows development environments where path spaces (e.g., repository directory paths containing spaces) or temporary directory file-locks may interfere with Apache Maven surefire plugin execution:

1. **Official JVM Config**:
   - `backend/.mvn/jvm.config` specifies `-Djava.io.tmpdir=target` for native Maven wrapper execution.
2. **Safe PowerShell Launcher**:
   - `backend/Run-Maven.ps1` resolves the Windows 8.3 short path (for example, `C:\PATHWI~1\project\backend\target`) and forwards parameters cleanly.
   - Execution example:
     ```powershell
     powershell -ExecutionPolicy Bypass -File .\backend\Run-Maven.ps1 test -Dtest=AdminPortalIntegrationTest
     ```
