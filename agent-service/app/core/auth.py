"""Internal service authentication as pure ASGI middleware (copied pattern from ai-service).

The token is checked from the request headers *before* the application runs, so an unauthenticated caller can never
make the service read or parse a request body (multipart included). Authenticated requests are also held to a body
size limit: a declared ``Content-Length`` over the limit is refused up front, and a chunked body that grows past it is
cut off and answered with 413. The document endpoint allows the document cap; every JSON endpoint allows 1 MB.

With an empty ``AGENT_SERVICE_TOKEN`` every ``/api/*`` request is refused with 503, unless
``AGENT_ALLOW_NO_TOKEN=true`` (tests only), in which case the token check is skipped.
"""
from __future__ import annotations

import hmac
import json

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.config import load_settings

TOKEN_HEADER = b"x-internal-service-token"
BODY_OVERHEAD_BYTES = 64 * 1024
JSON_BODY_LIMIT = 1024 * 1024
DOCUMENT_PATH = "/api/v1/verify/document"


async def _reject(send: Send, status: int, detail: str) -> None:
    body = json.dumps({"detail": detail}).encode()
    await send({"type": "http.response.start", "status": status, "headers": [
        (b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()), (b"connection", b"close"),
    ]})
    await send({"type": "http.response.body", "body": body})


def _header(scope: Scope, name: bytes) -> bytes | None:
    for key, value in scope.get("headers", []):
        if key.lower() == name:
            return value
    return None


class InternalTokenMiddleware:
    def __init__(self, app: ASGIApp, protected_prefix: str = "/api/") -> None:
        self.app = app
        self.prefix = protected_prefix

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope.get("path", "").startswith(self.prefix):
            await self.app(scope, receive, send)
            return
        settings = load_settings()
        if settings.agent_service_token:
            token = _header(scope, TOKEN_HEADER)
            if token is None or not hmac.compare_digest(token, settings.agent_service_token.encode()):
                await _reject(send, 401, "Internal service authentication failed")
                return
        elif not settings.allow_no_token:
            await _reject(send, 503, "Internal service authentication is not configured")
            return
        limit = (settings.max_document_bytes + BODY_OVERHEAD_BYTES if scope.get("path") == DOCUMENT_PATH
                 else JSON_BODY_LIMIT)
        declared = _header(scope, b"content-length")
        if declared is not None and (not declared.isdigit() or int(declared) > limit):
            await _reject(send, 413, "Request body too large")
            return

        received = 0
        overflow = False
        response_started = False

        async def limited_receive() -> Message:
            nonlocal received, overflow
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    overflow = True
                    return {"type": "http.disconnect"}
            return message

        async def guarded_send(message: Message) -> None:
            nonlocal response_started
            if overflow:
                if message["type"] == "http.response.start" and not response_started:
                    response_started = True
                    await _reject(send, 413, "Request body too large")
                return
            response_started = response_started or message["type"] == "http.response.start"
            await send(message)

        await self.app(scope, limited_receive, guarded_send)
