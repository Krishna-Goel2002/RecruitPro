import hmac
import os
from typing import Annotated

from fastapi import APIRouter, File, Header, HTTPException, UploadFile, status

from app.models.intelligence import BatchMatchRequest, BatchMatchResponse, CandidateMatch, MatchRequest, MatchResponse, ResumeParseResponse
from app.services.matching_engine import match_requirements
from app.services.resume_parser import DocumentParseError, MAX_FILE_BYTES, parse_resume


router = APIRouter()


def _authorize(token: str | None) -> None:
    expected = os.environ.get("AI_SERVICE_TOKEN", "")
    if not expected:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Internal service authentication is not configured")
    if not token or not hmac.compare_digest(token, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Internal service authentication failed")


@router.post("/resume/parse", response_model=ResumeParseResponse)
async def parse_resume_endpoint(
    file: Annotated[UploadFile, File(...)],
    x_internal_service_token: Annotated[str | None, Header()] = None,
) -> ResumeParseResponse:
    _authorize(x_internal_service_token)
    content = await file.read(MAX_FILE_BYTES + 1)
    await file.close()
    if len(content) > MAX_FILE_BYTES:
        return ResumeParseResponse(status="FAILED", errorCode="FILE_TOO_LARGE", parserVersion="rule-parser-1")
    filename = (file.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
    try:
        return parse_resume(filename, content)
    except DocumentParseError as exc:
        return ResumeParseResponse(status=exc.status, errorCode=exc.code, parserVersion="rule-parser-1")


@router.post("/match", response_model=MatchResponse)
def match_endpoint(
    request: MatchRequest,
    x_internal_service_token: Annotated[str | None, Header()] = None,
) -> MatchResponse:
    _authorize(x_internal_service_token)
    return match_requirements(request)


@router.post("/match/batch", response_model=BatchMatchResponse)
def batch_match_endpoint(
    request: BatchMatchRequest,
    x_internal_service_token: Annotated[str | None, Header()] = None,
) -> BatchMatchResponse:
    _authorize(x_internal_service_token)
    candidates = []
    for candidate in request.candidates:
        result = match_requirements(MatchRequest(requirements=request.requirements, facts=candidate.facts))
        candidates.append(CandidateMatch(applicationId=candidate.applicationId, **result.model_dump()))
    return BatchMatchResponse(candidates=candidates)
