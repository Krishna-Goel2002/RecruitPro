import asyncio
from typing import Annotated

from fastapi import APIRouter, File, UploadFile

from app.core.config import PARSER_VERSION, load_settings
from app.core.workers import WorkerCrashed, parser_pool
from app.models.resume import ParseResponse
from app.pipeline.extract import parse_resume

router = APIRouter()


@router.post("/resume/parse", response_model=ParseResponse)
async def parse_resume_endpoint(file: Annotated[UploadFile, File(...)]) -> ParseResponse:
    settings = load_settings()
    content = await file.read(settings.max_upload_bytes + 1)
    await file.close()
    if len(content) > settings.max_upload_bytes:
        return ParseResponse(status="FAILED", errorCode="FILE_TOO_LARGE", parserVersion=PARSER_VERSION)
    filename = (file.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
    try:
        return await parser_pool.run(parse_resume, filename, content, settings=settings,
                                     timeout=settings.parse_timeout_seconds, workers=settings.parse_workers)
    except asyncio.TimeoutError:
        return ParseResponse(status="FAILED", errorCode="PARSE_TIMEOUT", parserVersion=PARSER_VERSION)
    except WorkerCrashed:
        return ParseResponse(status="FAILED", errorCode="PARSER_CRASHED", parserVersion=PARSER_VERSION)
