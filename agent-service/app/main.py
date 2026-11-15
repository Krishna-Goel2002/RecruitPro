"""RecruitPro agent service: evidence verification agents (code, web, document, badge, reference).

Called only by the Spring Boot backend over the private network. Every ``/api/*`` route requires
``X-Internal-Service-Token`` (checked before any body is read); ``GET /health`` is public. The service is stateless:
it reads sources and drafts verdicts; the backend stores evidence and decides.
"""
from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.core.auth import InternalTokenMiddleware
from app.core.config import VERSION, load_settings, validate_settings
from app.llm.provider import get_provider

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    settings = load_settings()
    validate_settings(settings)  # refuse to start without a token (unless AGENT_ALLOW_NO_TOKEN=true) or a bad LLM setup
    if settings.demo_fixtures:
        logger.warning("AGENT_DEMO_FIXTURES=true: demo- logins and hosts are answered from synthetic fixtures")
    yield


def create_app() -> FastAPI:
    application = FastAPI(title="RecruitPro agent service", version=VERSION, docs_url=None, redoc_url=None,
                          openapi_url=None, lifespan=lifespan)
    application.include_router(router)
    application.add_middleware(InternalTokenMiddleware)

    @application.get("/health")
    def health() -> dict[str, object]:
        settings = load_settings()
        return {
            "status": "ok",
            "version": VERSION,
            "llmProvider": settings.llm_provider if get_provider(settings) is not None else "none",
            "demoFixtures": settings.demo_fixtures,
        }

    return application


app = create_app()
