# AI Service — Python FastAPI

## AI-Powered Recruitment & Hiring Management Platform

This is the AI/NLP microservice responsible for intelligent analysis of resumes and job matching.

## Tech Stack

- **Python 3.11+**
- **FastAPI** — high-performance async web framework
- **Uvicorn** — ASGI server
- **Pydantic** — data validation and serialization
- **spaCy** / **transformers** / **scikit-learn** — NLP and ML (future phases)

## Project Structure

```
ai-service/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI application entry point
│   ├── api/
│   │   ├── __init__.py
│   │   └── v1/
│   │       ├── __init__.py
│   │       ├── resume.py    # Resume parsing endpoints
│   │       ├── matching.py  # Candidate-job matching endpoints
│   │       └── ranking.py   # Candidate ranking endpoints
│   ├── core/
│   │   ├── __init__.py
│   │   └── config.py        # Application settings
│   ├── models/
│   │   ├── __init__.py
│   │   ├── requests.py      # Pydantic request models
│   │   └── responses.py     # Pydantic response models
│   ├── services/
│   │   ├── __init__.py
│   │   ├── resume_parser.py
│   │   ├── matching_engine.py
│   │   └── ranking_engine.py
│   ├── ml/                  # ML model code (Phase 5+)
│   │   └── __init__.py
│   └── utils/
│       ├── __init__.py
│       └── text_processing.py
├── tests/
│   ├── __init__.py
│   └── test_main.py
├── models/                  # Trained ML model files (gitignored)
├── requirements.txt
├── requirements-dev.txt
└── README.md                # This file
```

## API Endpoints (Planned)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Health check |
| POST | `/api/v1/resume/parse` | Parse and extract resume data |
| POST | `/api/v1/match` | Match candidate to job |
| POST | `/api/v1/rank` | Rank candidates for a job |

## Setup (Phase 5+)

```bash
cd ai-service
python -m venv venv
venv\Scripts\activate        # Windows
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

The API will be available at `http://localhost:8000`.  
Swagger docs at `http://localhost:8000/docs`.

## Key Constraint

This service provides **analysis and recommendations only**. It does NOT make hiring decisions or modify recruitment state. The Spring Boot backend is the sole authority for business state.

---

> 🚧 The AI service will be implemented starting in Phase 5 (Resume Intelligence).
> This README serves as the architectural placeholder.
