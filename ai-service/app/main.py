"""RecruitPro AI service: resume evidence extraction, requirement matching, hiring-kit generation and the HR
assistant planner.

Called only by the Spring Boot backend over the private network. Every ``/api/*`` route requires
``X-Internal-Service-Token`` (checked before any body is read); ``GET /health`` is public. The
service never makes hiring decisions; it reports evidence and its tier.
"""
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.v1.assistant import router as assistant_router
from app.api.v1.authoring import router as authoring_router
from app.api.v1.matching import router as matching_router
from app.api.v1.resume import router as resume_router
from app.core.auth import InternalTokenMiddleware
from app.core.config import VERSION, load_settings, validate_settings
from app.core.workers import parser_pool
from app.llm.provider import get_assistant_provider, get_provider
from app.pipeline.ocr import ocr_installed


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    validate_settings(load_settings())  # fail fast, e.g. an LLM provider without LLM_MODEL
    yield
    parser_pool.shutdown()


def create_app() -> FastAPI:
    application = FastAPI(title="RecruitPro AI service", version=VERSION, docs_url=None, redoc_url=None,
                          openapi_url=None, lifespan=lifespan)
    for router in (resume_router, matching_router, authoring_router, assistant_router):
        application.include_router(router, prefix="/api/v1")
    application.add_middleware(InternalTokenMiddleware)

    @application.get("/health")
    def health() -> dict[str, object]:
        settings = load_settings()
        return {
            "status": "ok",
            "version": VERSION,
            "llmProvider": settings.llm_provider if get_provider(settings) is not None else "none",
            "assistantPlanner": settings.assistant_llm_provider if get_assistant_provider(settings) is not None else "offline",
            "ocrAvailable": settings.ocr_enabled and ocr_installed(),
        }

    return application


app = create_app()
