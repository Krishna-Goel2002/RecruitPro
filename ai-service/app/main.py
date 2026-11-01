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

from app.api.v1.intelligence import router as intelligence_router

app = FastAPI(
    title="Recruitment AI Service",
    description="AI/NLP service for resume analysis, candidate-job matching, and ranking",
    version="0.1.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

app.include_router(intelligence_router, prefix="/api/v1", tags=["Resume intelligence"])


@app.get("/health", tags=["Health"])
async def health_check():
    """Health check endpoint."""
    return {"status": "healthy", "service": "recruitment-ai-service", "version": "0.1.0"}
