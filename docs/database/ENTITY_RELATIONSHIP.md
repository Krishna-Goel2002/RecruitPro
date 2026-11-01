# Entity-Relationship Documentation

## AI-Powered Recruitment & Hiring Management Platform

**Version:** 1.0  
**Phase:** 0 — Architecture & Foundation

---

## 1. Entity Overview

| # | Entity | Module | Purpose |
|---|--------|--------|---------|
| 1 | User | Auth | Base user account for all actors |
| 2 | Role | Auth | Role definition (ADMIN, HR_MANAGER, RECRUITER, INTERVIEWER, CANDIDATE) |
| 3 | Candidate | Candidate | Extended profile for job seekers |
| 4 | HRProfile | HR | Extended profile for HR personnel |
| 5 | Job | HR | Job posting with details and requirements |
| 6 | JobRequirement | HR | Specific skill/qualification requirements for a job |
| 7 | Resume | Candidate | Uploaded resume file and extracted metadata |
| 8 | Application | Application | A candidate's application to a specific job |
| 9 | AIAnalysis | AI | AI processing result for a resume |
| 10 | MatchResult | AI | AI-computed match between a candidate and a job |
| 11 | Assessment | Assessment | Assessment/test linked to a job or application |
| 12 | Question | Assessment | Individual question within an assessment |
| 13 | AssessmentAttempt | Assessment | A candidate's attempt at an assessment |
| 14 | Interview | Interview | Scheduled interview session |
| 15 | InterviewFeedback | Interview | Interviewer's evaluation of a candidate |
| 16 | Selection | Hiring | Selection decision for an application |
| 17 | Offer | Hiring | Formal job offer issued to a selected candidate |
| 18 | Onboarding | Onboarding | Onboarding process for an accepted candidate |
| 19 | OnboardingTask | Onboarding | Individual task/checklist item in onboarding |
| 20 | RefreshToken | Auth | Hashed, expiring and revocable refresh-token record |

---

## 2. Entity Definitions & Relationships

### 2.1 User

**Module:** Auth  
**Purpose:** Base authentication entity for every person who uses the system.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| email | VARCHAR(255) | UNIQUE, NOT NULL |
| password_hash | VARCHAR(255) | NOT NULL |
| first_name | VARCHAR(100) | NOT NULL |
| last_name | VARCHAR(100) | NOT NULL |
| role | VARCHAR(50) | NOT NULL; one of the predefined roles |
| enabled | BOOLEAN | DEFAULT TRUE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- User → One Role (stored as an enum string; Phase 2 implementation)
- User → Many RefreshToken records (hashed tokens, deleted when the user is deleted)
- User → Zero or One Candidate (if the user is a candidate)
- User → Zero or One HRProfile (for HR_MANAGER or RECRUITER users)

---

### 2.2 Role

**Module:** Auth  
**Purpose:** Defines access levels within the system.

Role is a Java enum persisted in `users.role` as a string. Phase 2 uses one role per user; separate `roles` and `user_roles` tables are not part of the current schema.

**Predefined Values:**
- ADMIN
- HR_MANAGER
- RECRUITER
- INTERVIEWER
- CANDIDATE

**Relationships:**
- User → one Role

### 2.2a RefreshToken (Phase 2)

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| user_id | BIGINT | FK to users.id, ON DELETE CASCADE |
| token_hash | VARCHAR(64) | UNIQUE, NOT NULL |
| expires_at | TIMESTAMP | NOT NULL |
| revoked_at | TIMESTAMP | NULLABLE |
| created_at / updated_at | TIMESTAMP | NOT NULL |

The raw opaque token is returned once to the client. Only its SHA-256 hash is persisted. Successful refresh revokes the current record and creates a replacement.

---

### 2.3 CandidateProfile (Phase 4)

**Module:** Candidate  
**Purpose:** Extended profile for users who are job seekers. Table: `candidate_profiles`.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| user_id | BIGINT | FK → User, UNIQUE, NOT NULL |
| date_of_birth | DATE | NULLABLE |
| gender | VARCHAR(20) | NULLABLE |
| address | TEXT | NULLABLE |
| city | VARCHAR(100) | NULLABLE |
| state | VARCHAR(100) | NULLABLE |
| country | VARCHAR(100) | NULLABLE |
| zip_code | VARCHAR(20) | NULLABLE |
| linkedin_url | VARCHAR(500) | NULLABLE |
| portfolio_url | VARCHAR(500) | NULLABLE |
| years_of_experience | INTEGER | NULLABLE |
| current_company | VARCHAR(255) | NULLABLE |
| current_designation | VARCHAR(255) | NULLABLE |
| expected_salary | DECIMAL(12,2) | NULLABLE |
| notice_period_days | INTEGER | NULLABLE |
| profile_summary | TEXT | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Candidate → One User (FK: user_id)
- CandidateProfile → Many Resume records (resume history)
- Candidate → Many Applications

---

### 2.4 HRProfile

**Module:** HR  
**Purpose:** Extended profile for HR personnel.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| user_id | BIGINT | FK → User, UNIQUE, NOT NULL |
| employee_id | VARCHAR(50) | UNIQUE, NULLABLE |
| department | VARCHAR(100) | NULLABLE |
| designation | VARCHAR(100) | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- HRProfile → One User (FK: user_id)
- HRProfile → Many Jobs (as creator)

---

### 2.5 Job

**Module:** HR  
**Purpose:** A job posting created by HR.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| created_by | BIGINT | FK → HRProfile, NOT NULL |
| title | VARCHAR(255) | NOT NULL |
| description | TEXT | NOT NULL |
| department | VARCHAR(100) | NOT NULL |
| location | VARCHAR(255) | NOT NULL |
| job_type | VARCHAR(30) | NOT NULL; FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP |
| experience_level | VARCHAR(30) | NOT NULL; ENTRY, MID, SENIOR, LEAD, EXECUTIVE |
| minimum_salary | DECIMAL(12,2) | NULLABLE, NON-NEGATIVE |
| maximum_salary | DECIMAL(12,2) | NULLABLE, NON-NEGATIVE, >= minimum_salary |
| vacancies | INTEGER | NOT NULL, DEFAULT 1, POSITIVE |
| status | VARCHAR(30) | NOT NULL; DRAFT, PUBLISHED, ON_HOLD, CLOSED, CANCELLED |
| published_at | TIMESTAMP | NULLABLE |
| deadline | TIMESTAMP | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

The Phase 3 migration is `V4__create_hr_profiles_jobs_and_job_requirements.sql`. Jobs are editable and physically deletable only while DRAFT. Job requirements can be created, updated, and deleted only while their job is DRAFT. Enum names are persisted as strings.

**Relationships:**
- Job → One HRProfile (FK: created_by)
- Job → Many JobRequirements
- Job → Many Applications
- Job → Many Assessments

---

### 2.6 JobRequirement

**Module:** HR  
**Purpose:** Specific skill, qualification, or requirement for a job.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| job_id | BIGINT | FK → Job, NOT NULL |
| requirement_type | ENUM | SKILL, EDUCATION, CERTIFICATION, EXPERIENCE, OTHER |
| description | VARCHAR(500) | NOT NULL |
| is_mandatory | BOOLEAN | DEFAULT TRUE |
| min_years | INTEGER | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- JobRequirement → One Job (FK: job_id)

---

### 2.7 Resume (Phase 4 metadata; parsing is future scope)

**Module:** Candidate  
**Purpose:** Uploaded resume file and AI-extracted metadata.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| candidate_id | BIGINT | FK → CandidateProfile, NOT NULL |
| storage_key | VARCHAR(36) | UNIQUE opaque identifier, NOT NULL |
| original_filename | VARCHAR(255) | Display metadata, NOT NULL |
| file_extension | VARCHAR(10) | NOT NULL |
| content_type | VARCHAR(100) | Detected type, NOT NULL |
| file_size | BIGINT | NOT NULL |
| uploaded_at | TIMESTAMP | NOT NULL |
| created_at / updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Resume → One CandidateProfile (FK: candidate_id)
- Resume → Many Applications (the submitted resume is retained per application)

The file itself is stored in a private filesystem directory. API DTOs never expose the storage key or an absolute path. Resume records are retained when new resumes are uploaded.

---

### 2.8 Application

**Module:** Application  
**Purpose:** Represents a candidate's application to a specific job.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| candidate_id | BIGINT | FK → Candidate, NOT NULL |
| job_id | BIGINT | FK → Job, NOT NULL |
| resume_id | BIGINT | FK → Resume, NOT NULL |
| status | VARCHAR(30) | SUBMITTED or WITHDRAWN in Phase 4 |
| cover_letter | TEXT | NULLABLE |
| applied_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Constraints:**
- UNIQUE(candidate_id, job_id) — a candidate can apply to a job only once.
- Phase 4 creates applications as SUBMITTED. Candidates may withdraw only while SUBMITTED; HR transitions are future scope.

**Relationships:**
- Application → One Candidate (FK: candidate_id)
- Application → One Job (FK: job_id)
- Application → One Resume (FK: resume_id; exact uploaded version used)
- Application → Zero or One MatchResult
- Application → Zero or Many AssessmentAttempts
- Application → Zero or Many Interviews
- Application → Zero or One Selection

---

### 2.9 AIAnalysis

**Module:** AI  
**Purpose:** Result of AI processing on a resume.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| resume_id | BIGINT | FK → Resume, NOT NULL |
| analysis_type | ENUM | RESUME_PARSE, SKILL_EXTRACTION, EXPERIENCE_ANALYSIS |
| result | JSON | NOT NULL |
| confidence_score | DECIMAL(5,4) | NULLABLE |
| processed_at | TIMESTAMP | NOT NULL |

**Relationships:**
- AIAnalysis → One Resume (FK: resume_id)

---

### 2.10 MatchResult

**Module:** AI  
**Purpose:** AI-computed match between a candidate application and a job.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| application_id | BIGINT | FK → Application, UNIQUE, NOT NULL |
| overall_score | DECIMAL(5,2) | NOT NULL (0.00 - 100.00) |
| skill_score | DECIMAL(5,2) | NULLABLE |
| experience_score | DECIMAL(5,2) | NULLABLE |
| education_score | DECIMAL(5,2) | NULLABLE |
| explanation | TEXT | NOT NULL |
| recommendation | ENUM | STRONG_MATCH, GOOD_MATCH, MODERATE_MATCH, WEAK_MATCH, NOT_RECOMMENDED |
| matched_skills | JSON | NULLABLE |
| missing_skills | JSON | NULLABLE |
| computed_at | TIMESTAMP | NOT NULL |

**Relationships:**
- MatchResult → One Application (FK: application_id)

---

### 2.11 Assessment

**Module:** Assessment  
**Purpose:** A test or assessment created for a job.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| job_id | BIGINT | FK → Job, NOT NULL |
| title | VARCHAR(255) | NOT NULL |
| description | TEXT | NULLABLE |
| duration_minutes | INTEGER | NOT NULL |
| total_marks | INTEGER | NOT NULL |
| passing_marks | INTEGER | NOT NULL |
| is_active | BOOLEAN | DEFAULT TRUE |
| created_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Assessment → One Job (FK: job_id)
- Assessment → Many Questions
- Assessment → Many AssessmentAttempts

---

### 2.12 Question

**Module:** Assessment  
**Purpose:** Individual question in an assessment.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| assessment_id | BIGINT | FK → Assessment, NOT NULL |
| question_text | TEXT | NOT NULL |
| question_type | ENUM | MCQ, SHORT_ANSWER, CODING, ESSAY |
| options | JSON | NULLABLE (for MCQ) |
| correct_answer | TEXT | NULLABLE |
| marks | INTEGER | NOT NULL |
| order_index | INTEGER | NOT NULL |

**Relationships:**
- Question → One Assessment (FK: assessment_id)

---

### 2.13 AssessmentAttempt

**Module:** Assessment  
**Purpose:** A candidate's attempt at an assessment.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| assessment_id | BIGINT | FK → Assessment, NOT NULL |
| application_id | BIGINT | FK → Application, NOT NULL |
| answers | JSON | NULLABLE |
| score | INTEGER | NULLABLE |
| status | ENUM | NOT_STARTED, IN_PROGRESS, SUBMITTED, EVALUATED |
| started_at | TIMESTAMP | NULLABLE |
| submitted_at | TIMESTAMP | NULLABLE |
| evaluated_at | TIMESTAMP | NULLABLE |

**Relationships:**
- AssessmentAttempt → One Assessment (FK: assessment_id)
- AssessmentAttempt → One Application (FK: application_id)

---

### 2.14 Interview

**Module:** Interview  
**Purpose:** A scheduled interview session.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| application_id | BIGINT | FK → Application, NOT NULL |
| interviewer_id | BIGINT | FK → User, NOT NULL |
| round_number | INTEGER | NOT NULL |
| round_name | VARCHAR(100) | NOT NULL |
| scheduled_at | TIMESTAMP | NOT NULL |
| duration_minutes | INTEGER | DEFAULT 60 |
| location | VARCHAR(255) | NULLABLE |
| meeting_link | VARCHAR(500) | NULLABLE |
| status | ENUM | SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED, RESCHEDULED |
| notes | TEXT | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Interview → One Application (FK: application_id)
- Interview → One User as interviewer (FK: interviewer_id)
- Interview → Zero or One InterviewFeedback

---

### 2.15 InterviewFeedback

**Module:** Interview  
**Purpose:** Interviewer's evaluation after an interview.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| interview_id | BIGINT | FK → Interview, UNIQUE, NOT NULL |
| technical_score | INTEGER | NULLABLE (1-10) |
| communication_score | INTEGER | NULLABLE (1-10) |
| cultural_fit_score | INTEGER | NULLABLE (1-10) |
| overall_score | INTEGER | NULLABLE (1-10) |
| strengths | TEXT | NULLABLE |
| weaknesses | TEXT | NULLABLE |
| comments | TEXT | NULLABLE |
| recommendation | ENUM | STRONG_HIRE, HIRE, NO_HIRE, STRONG_NO_HIRE |
| submitted_at | TIMESTAMP | NOT NULL |

**Relationships:**
- InterviewFeedback → One Interview (FK: interview_id)

---

### 2.16 Selection

**Module:** Hiring  
**Purpose:** Final selection decision for an application.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| application_id | BIGINT | FK → Application, UNIQUE, NOT NULL |
| decided_by | BIGINT | FK → User, NOT NULL |
| decision | ENUM | SELECTED, REJECTED, ON_HOLD |
| reason | TEXT | NULLABLE |
| decided_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Selection → One Application (FK: application_id)
- Selection → One User (FK: decided_by)
- Selection (if SELECTED) → Zero or One Offer

---

### 2.17 Offer

**Module:** Hiring  
**Purpose:** Formal offer issued to a selected candidate.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| selection_id | BIGINT | FK → Selection, UNIQUE, NOT NULL |
| offered_salary | DECIMAL(12,2) | NOT NULL |
| offered_designation | VARCHAR(255) | NOT NULL |
| offered_department | VARCHAR(100) | NULLABLE |
| joining_date | DATE | NOT NULL |
| offer_letter_path | VARCHAR(500) | NULLABLE |
| status | ENUM | DRAFT, SENT, ACCEPTED, DECLINED, EXPIRED, REVOKED |
| sent_at | TIMESTAMP | NULLABLE |
| responded_at | TIMESTAMP | NULLABLE |
| expiry_date | DATE | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Offer → One Selection (FK: selection_id)
- Offer (if ACCEPTED) → Zero or One Onboarding

---

### 2.18 Onboarding

**Module:** Onboarding  
**Purpose:** Onboarding process for an accepted candidate.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| offer_id | BIGINT | FK → Offer, UNIQUE, NOT NULL |
| status | ENUM | NOT_STARTED, IN_PROGRESS, COMPLETED |
| started_at | TIMESTAMP | NULLABLE |
| completed_at | TIMESTAMP | NULLABLE |
| created_at | TIMESTAMP | NOT NULL |
| updated_at | TIMESTAMP | NOT NULL |

**Relationships:**
- Onboarding → One Offer (FK: offer_id)
- Onboarding → Many OnboardingTasks

---

### 2.19 OnboardingTask

**Module:** Onboarding  
**Purpose:** Individual checklist item in the onboarding process.

| Field | Type | Constraints |
|-------|------|------------|
| id | BIGINT | PK, AUTO_INCREMENT |
| onboarding_id | BIGINT | FK → Onboarding, NOT NULL |
| task_name | VARCHAR(255) | NOT NULL |
| description | TEXT | NULLABLE |
| is_completed | BOOLEAN | DEFAULT FALSE |
| due_date | DATE | NULLABLE |
| completed_at | TIMESTAMP | NULLABLE |
| order_index | INTEGER | NOT NULL |

**Relationships:**
- OnboardingTask → One Onboarding (FK: onboarding_id)

---

## 3. ER Diagram (Mermaid)

```mermaid
erDiagram
    USER ||--o{ REFRESH_TOKEN : owns
    USER ||--o| CANDIDATE : "may be"
    USER ||--o| HR_PROFILE : "may be"

    HR_PROFILE ||--o{ JOB : creates
    JOB ||--o{ JOB_REQUIREMENT : has
    JOB ||--o{ APPLICATION : receives
    JOB ||--o{ ASSESSMENT : has

    CANDIDATE ||--o| RESUME : uploads
    CANDIDATE ||--o{ APPLICATION : submits

    APPLICATION ||--o| MATCH_RESULT : "AI analyzes"
    APPLICATION ||--o{ ASSESSMENT_ATTEMPT : takes
    APPLICATION ||--o{ INTERVIEW : scheduled_for
    APPLICATION ||--o| SELECTION : results_in

    RESUME ||--o{ AI_ANALYSIS : "processed by"

    ASSESSMENT ||--o{ QUESTION : contains
    ASSESSMENT ||--o{ ASSESSMENT_ATTEMPT : attempted_in

    INTERVIEW ||--o| INTERVIEW_FEEDBACK : receives
    INTERVIEW }o--|| USER : "conducted by"

    SELECTION ||--o| OFFER : generates
    SELECTION }o--|| USER : "decided by"

    OFFER ||--o| ONBOARDING : initiates
    ONBOARDING ||--o{ ONBOARDING_TASK : contains

    USER {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar first_name
        varchar last_name
        varchar role
        boolean enabled
        timestamp created_at
    }

    REFRESH_TOKEN {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        timestamp expires_at
        timestamp revoked_at
    }

    CANDIDATE {
        bigint id PK
        bigint user_id FK
        integer years_of_experience
        text profile_summary
    }

    HR_PROFILE {
        bigint id PK
        bigint user_id FK
        varchar department
        varchar designation
    }

    JOB {
        bigint id PK
        bigint created_by FK
        varchar title
        enum status
        timestamp deadline
    }

    JOB_REQUIREMENT {
        bigint id PK
        bigint job_id FK
        enum requirement_type
        boolean is_mandatory
    }

    RESUME {
        bigint id PK
        bigint candidate_id FK
        varchar storage_key UK
        varchar original_filename
        varchar content_type
        bigint file_size
    }

    APPLICATION {
        bigint id PK
        bigint candidate_id FK
        bigint job_id FK
        bigint resume_id FK
        varchar status
        timestamp applied_at
    }

    AI_ANALYSIS {
        bigint id PK
        bigint resume_id FK
        enum analysis_type
        json result
    }

    MATCH_RESULT {
        bigint id PK
        bigint application_id FK
        decimal overall_score
        text explanation
        enum recommendation
    }

    ASSESSMENT {
        bigint id PK
        bigint job_id FK
        varchar title
        integer duration_minutes
    }

    QUESTION {
        bigint id PK
        bigint assessment_id FK
        text question_text
        enum question_type
    }

    ASSESSMENT_ATTEMPT {
        bigint id PK
        bigint assessment_id FK
        bigint application_id FK
        integer score
        enum status
    }

    INTERVIEW {
        bigint id PK
        bigint application_id FK
        bigint interviewer_id FK
        integer round_number
        enum status
    }

    INTERVIEW_FEEDBACK {
        bigint id PK
        bigint interview_id FK
        integer overall_score
        enum recommendation
    }

    SELECTION {
        bigint id PK
        bigint application_id FK
        bigint decided_by FK
        enum decision
    }

    OFFER {
        bigint id PK
        bigint selection_id FK
        decimal offered_salary
        enum status
    }

    ONBOARDING {
        bigint id PK
        bigint offer_id FK
        enum status
    }

    ONBOARDING_TASK {
        bigint id PK
        bigint onboarding_id FK
        varchar task_name
        boolean is_completed
    }
```

---

## 4. Module-Entity Ownership

```
Module 1 (Auth)        → User (enum role), RefreshToken
Module 2 (HR)          → HRProfile, Job, JobRequirement
Module 3 (Candidate)   → Candidate, Resume
Module 4 (Application) → Application
Module 5 (AI)          → AIAnalysis, MatchResult
Module 6 (Assessment)  → Assessment, Question, AssessmentAttempt
Module 7 (Interview)   → Interview, InterviewFeedback
Module 8 (Hiring)      → Selection, Offer
Module 9 (Onboarding)  → Onboarding, OnboardingTask
Module 10 (Analytics)  → No owned entities (reads from all modules)
```

---

## 5. Cross-Module Entity Dependencies

| Entity | Owned By | Referenced By |
|--------|----------|--------------|
| User | Auth | Candidate, HRProfile, Interview, Selection |
| Candidate | Candidate | Application |
| Job | HR | Application, Assessment |
| Application | Application | MatchResult, AssessmentAttempt, Interview, Selection |
| Resume | Candidate | AIAnalysis |
| Selection | Hiring | Offer |
| Offer | Hiring | Onboarding |

---

*This ER documentation defines the architectural contract. Actual JPA entity classes will be implemented starting in Phase 1.*
