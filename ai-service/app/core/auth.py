"""Internal service authentication as pure ASGI middleware (finding S3).

The token is checked from the request headers *before* the application runs, so an unauthenticated
caller can never make the service read or parse a request body (multipart included). Authenticated
requests are also held to a body-size limit: a declared ``Content-Length`` over the limit is
refused up front, and a chunked body that grows past it is cut off and answered with 413.
"""
from __future__ import annotations

import hmac
import json

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.config import load_settings

TOKEN_HEADER = b"x-internal-service-token"
# Multipart framing and form fields on top of the largest accepted file.
BODY_OVERHEAD_BYTES = 64 * 1024


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
        if not settings.ai_service_token:
            await _reject(send, 503, "Internal service authentication is not configured")
            return
        token = _header(scope, TOKEN_HEADER)
        if token is None or not hmac.compare_digest(token, settings.ai_service_token.encode()):
            await _reject(send, 401, "Internal service authentication failed")
            return
        limit = settings.max_upload_bytes + BODY_OVERHEAD_BYTES
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
