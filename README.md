# AI-Powered End-to-End Recruitment & Hiring Management Platform

A comprehensive, AI-assisted recruitment lifecycle management system built as a college major project. The platform combines human decision-making with AI-powered candidate screening to streamline the entire hiring process — from job creation through onboarding.

## Architecture Overview

```
┌──────────────┐
│   Frontend   │  React + TypeScript
│   (SPA)      │  Port 3000
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
│  Port 3306   │
└──────────────┘
```

### Core Principle: Human-in-the-Loop AI

AI provides analysis, matching scores, ranking, and explanations.  
**AI does NOT make final hiring decisions.** All decisions remain with authorized HR/recruiters.

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React, TypeScript, Vite |
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

| # | Module | Owner Service |
|---|--------|--------------|
| 1 | Authentication & Users | Backend |
| 2 | HR & Recruitment | Backend |
| 3 | Candidate Management | Backend |
| 4 | Applications | Backend |
| 5 | AI Intelligence | AI Service |
| 6 | Assessment | Backend |
| 7 | Interview | Backend |
| 8 | Hiring (Selection/Offer) | Backend |
| 9 | Onboarding | Backend |
| 10 | Analytics | Backend |

## Development Phases

| Phase | Description | Status |
|-------|------------|--------|
| 0 | Architecture & Foundation | 🔄 In Progress |
| 1 | Backend Foundation | ⬜ Planned |
| 2 | Authentication & Authorization | ⬜ Planned |
| 3 | HR & Job Management | ⬜ Planned |
| 4 | Candidate Portal | ⬜ Planned |
| 5 | Resume Intelligence | ⬜ Planned |
| 6 | AI Matching & Ranking | ⬜ Planned |
| 7 | Assessment | ⬜ Planned |
| 8 | Interview Management | ⬜ Planned |
| 9 | Selection, Offer & Onboarding | ⬜ Planned |
| 10 | Analytics | ⬜ Planned |
| 11 | Complete Integration | ⬜ Planned |
| 12 | Testing, Security & Deployment | ⬜ Planned |

## Prerequisites

Before running the project, ensure the following are installed:

- **Java 17** (JDK) — [Download](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- **Maven 3.9+** — [Download](https://maven.apache.org/download.cgi)
- **Node.js 18+ & npm** — [Download](https://nodejs.org/)
- **Python 3.11+** — [Download](https://www.python.org/downloads/)
- **MySQL 8.x** — [Download](https://dev.mysql.com/downloads/)
- **Git** — [Download](https://git-scm.com/)

## Getting Started

> 🚧 Detailed setup instructions will be added as each component is implemented in subsequent phases.

### Quick Start (after all prerequisites are installed)

```bash
# Clone the repository
git clone <repository-url>
cd "Major Project"

# Backend (Phase 1+)
cd backend
mvn spring-boot:run

# Frontend (Phase 4+)
cd frontend
npm install && npm run dev

# AI Service (Phase 5+)
cd ai-service
pip install -r requirements.txt
uvicorn app.main:app --reload
```

## License

This project is developed as an academic major project.
