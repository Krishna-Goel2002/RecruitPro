"""HTTP endpoints (all POST, all behind the internal token middleware).

Error mapping: invalid input -> 422 (FastAPI); a source that cannot be read at all -> 422 with
``{"detail": {"code", "message"}}`` (codes: URL_BLOCKED, ROBOTS_DISALLOWED, SOURCE_NOT_FOUND, UNSUPPORTED_CONTENT,
UNSUPPORTED_DOCUMENT, DOCUMENT_UNREADABLE, OFF_SITE_REDIRECT, HTTP_ERROR, DNS_FAILURE, BAD_CREDENTIAL, BAD_TARGET
for a PROFILE_BIO_CODE target that is not ``github:<login>`` / ``gitlab:<login>``); upstream
trouble -> 502 (TIMEOUT, NETWORK_ERROR, UPSTREAM_ERROR, RATE_LIMITED, TOKEN_INVALID, TOO_MANY_REDIRECTS, BUDGET);
document too large -> 413.
"""
from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Callable

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from pydantic import ValidationError

from app.api.deps import NetworkFactory, get_clock, get_network_factory, get_settings
from app.core.budget import BudgetExceeded
from app.core.config import Settings
from app.extract import ExtractionError
from app.fetch.ssrf import FetchError
from app.llm.summary import summary_writer
from app.models import (LOGIN_PATTERN, BadgeVerifyRequest, CodeVerifyRequest, DocumentVerifyRequest,
                        OwnershipCheckRequest, OwnershipCheckResponse, ReferenceVerifyRequest, VerifyResponse,
                        WebVerifyRequest)
from app.sources.common import SourceError
from app.verdicts import VerifierOutput, finalize
from app.verifiers.badge import BadgeInputError, verify_badge
from app.verifiers.code import verify_code
from app.verifiers.document import verify_document
from app.verifiers.ownership import check_ownership
from app.verifiers.reference import verify_reference
from app.verifiers.web import verify_web

router = APIRouter(prefix="/api/v1")
UPSTREAM_CODES = {"TIMEOUT", "NETWORK_ERROR", "UPSTREAM_ERROR", "RATE_LIMITED", "TOKEN_INVALID", "TOO_MANY_REDIRECTS",
                  "BUDGET"}
Clock = Callable[[], datetime]


def _fail(code: str, message: str) -> HTTPException:
    return HTTPException(status_code=502 if code in UPSTREAM_CODES else 422,
                         detail={"code": code, "message": message})


def _bio_login(target: str) -> str:
    """The login of a PROFILE_BIO_CODE target ``github:<login>`` / ``gitlab:<login>``; 422 BAD_TARGET otherwise."""
    provider, separator, login = target.partition(":")
    known = provider.strip().lower() in {"github", "gitlab"}
    if not separator or not known or not re.match(LOGIN_PATTERN, login.strip()):
        raise _fail("BAD_TARGET", "the target must be github:<login> or gitlab:<login>")
    return login.strip()


def _respond(output: VerifierOutput, settings: Settings) -> VerifyResponse:
    return finalize(output, summary_writer=summary_writer(settings))


@router.post("/ownership/check", response_model=OwnershipCheckResponse, response_model_by_alias=True)
def ownership_check(request: OwnershipCheckRequest, settings: Settings = Depends(get_settings),
                    networks: NetworkFactory = Depends(get_network_factory),
                    clock: Clock = Depends(get_clock)) -> OwnershipCheckResponse:
    target = _bio_login(request.target) if request.method == "PROFILE_BIO_CODE" else request.target
    return check_ownership(request, settings=settings, network=networks(settings, target, request.code), now=clock())


@router.post("/verify/code", response_model=VerifyResponse, response_model_by_alias=True)
def code(request: CodeVerifyRequest, settings: Settings = Depends(get_settings),
         networks: NetworkFactory = Depends(get_network_factory), clock: Clock = Depends(get_clock)) -> VerifyResponse:
    try:
        output = verify_code(request, settings=settings, network=networks(settings, request.login, None), now=clock())
    except SourceError as exc:
        raise _fail(exc.code, str(exc)) from exc
    except BudgetExceeded as exc:
        raise _fail("BUDGET", "the request budget was exhausted before the profile was read") from exc
    return _respond(output, settings)


@router.post("/verify/web", response_model=VerifyResponse, response_model_by_alias=True)
def web(request: WebVerifyRequest, settings: Settings = Depends(get_settings),
        networks: NetworkFactory = Depends(get_network_factory), clock: Clock = Depends(get_clock)) -> VerifyResponse:
    try:
        output = verify_web(request, settings=settings, network=networks(settings, request.url, None), now=clock())
    except FetchError as exc:
        raise _fail(exc.code, str(exc)) from exc
    return _respond(output, settings)


@router.post("/verify/document", response_model=VerifyResponse, response_model_by_alias=True)
async def document(file: UploadFile = File(...), request: str = Form(...), settings: Settings = Depends(get_settings),
                   clock: Clock = Depends(get_clock)) -> VerifyResponse:
    try:
        parsed = DocumentVerifyRequest.model_validate(json.loads(request))
    except (ValueError, ValidationError) as exc:
        raise HTTPException(status_code=422, detail={"code": "BAD_REQUEST", "message": "request must be valid JSON"}) \
            from exc
    content = await file.read(settings.max_document_bytes + 1)
    if len(content) > settings.max_document_bytes:
        raise HTTPException(status_code=413, detail={"code": "DOCUMENT_TOO_LARGE", "message": "documents are limited "
                                                     "to 15 MB"})
    try:
        output = verify_document(content, file.filename or "", parsed, now=clock())
    except ExtractionError as exc:
        raise _fail(exc.code, str(exc)) from exc
    return _respond(output, settings)


@router.post("/verify/badge", response_model=VerifyResponse, response_model_by_alias=True)
def badge(request: BadgeVerifyRequest, settings: Settings = Depends(get_settings),
          networks: NetworkFactory = Depends(get_network_factory), clock: Clock = Depends(get_clock)) -> VerifyResponse:
    identifier = request.url or ""
    try:
        output = verify_badge(request, settings=settings, network=networks(settings, identifier, None), now=clock())
    except BadgeInputError as exc:
        raise _fail("BAD_CREDENTIAL", str(exc)) from exc
    except FetchError as exc:
        raise _fail(exc.code, str(exc)) from exc
    return _respond(output, settings)


@router.post("/verify/reference", response_model=VerifyResponse, response_model_by_alias=True)
def reference(request: ReferenceVerifyRequest, settings: Settings = Depends(get_settings),
              clock: Clock = Depends(get_clock)) -> VerifyResponse:
    return _respond(verify_reference(request, now=clock()), settings)
