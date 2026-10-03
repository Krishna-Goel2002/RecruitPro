"""
AI-Powered Recruitment Platform — AI Service

FastAPI application entry point.
This service provides AI/NLP capabilities for the recruitment platform:
- Resume parsing and information extraction
- Job requirement analysis
- Candidate-job matching and scoring
- Candidate ranking
- Human-readable explanations for AI recommendations

This service is called by the Spring Boot backend via REST APIs.
It does NOT independently make hiring decisions.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="Recruitment AI Service",
    description="AI/NLP service for resume analysis, candidate-job matching, and ranking",
    version="0.1.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# CORS — allow the Spring Boot backend to call this service
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", tags=["Health"])
async def health_check():
    """Health check endpoint."""
    return {"status": "healthy", "service": "recruitment-ai-service", "version": "0.1.0"}


# ---- Future route registrations (Phase 5+) ----
# from app.api.v1 import resume, matching, ranking
# app.include_router(resume.router, prefix="/api/v1/resume", tags=["Resume"])
# app.include_router(matching.router, prefix="/api/v1/match", tags=["Matching"])
# app.include_router(ranking.router, prefix="/api/v1/rank", tags=["Ranking"])
