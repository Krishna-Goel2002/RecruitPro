"""HTTP layer: the RecruitPro envelope, bearer sessions per portal, pacing and retries.

* Every response is ``{success, message, data, errors, timestamp}``; :meth:`ApiSession.request` returns ``data``.
* Auth: ``POST /auth/login`` and ``POST /auth/register`` return ``data.accessToken`` (15 minutes). The refresh token
  lives in an httpOnly cookie that is ``Secure`` by default (``AUTH_COOKIE_SECURE=true``), which a plain-http client
  will not send back, so on a 401 the session simply signs in again with the stored credentials.
* Portal header ``X-RecruitPro-Portal: candidate|staff``: candidates on the candidate portal, HR on the staff portal.
* Rate limits (``RateLimitFilter``, per client IP): login 10/min, register 5/min, refresh 30/min, other
  ``/auth/*`` 30/min, ``DELETE /candidates/me`` 5/15 min. :class:`RateGate` paces those routes client-side so a run
  stays under them; a 429 is still honoured via ``Retry-After``. For big local runs start the backend with
  ``RATE_LIMIT_ENABLED=false`` and pass ``--pacing off``.
"""
from __future__ import annotations

import base64
import email.utils
import hashlib
import hmac
import logging
import random
import struct
import threading
from collections import deque
from dataclasses import dataclass, field
from typing import Any

import httpx

from .clock import Clock

log = logging.getLogger("simulate.api")

PORTAL_HEADER = "X-RecruitPro-Portal"
CANDIDATE = "candidate"
STAFF = "staff"

# (limit, window seconds) per route key, mirroring backend RateLimitFilter.RULES.
BACKEND_LIMITS: dict[str, tuple[int, float]] = {
    "auth/login": (10, 60.0),
    "auth/register": (5, 60.0),
    "auth/refresh": (30, 60.0),
    "auth/mfa/verify": (10, 60.0),
    "auth/*": (30, 60.0),
    "DELETE candidates/me": (5, 900.0),
}

RETRY_STATUSES = {429, 502, 503, 504}


class ApiError(Exception):
    def __init__(self, status: int | None, message: str, *, method: str = "", path: str = "",
                 payload: Any = None) -> None:
        super().__init__(f"{method} {path} -> {status}: {message}")
        self.status = status
        self.message = message
        self.method = method
        self.path = path
        self.payload = payload


@dataclass
class ApiStats:
    requests: int = 0
    retries_429: int = 0
    retries_5xx: int = 0
    retries_transport: int = 0
    reauth: int = 0
    paced_seconds: float = 0.0
    retry_after_seconds: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def add(self, name: str, amount: float = 1) -> None:
        with self._lock:
            setattr(self, name, getattr(self, name) + amount)

    def as_dict(self) -> dict[str, Any]:
        return {k: (round(v, 2) if isinstance(v, float) else v) for k, v in self.__dict__.items()
                if not k.startswith("_")}


@dataclass
class RetryPolicy:
    max_attempts: int = 6
    base_delay: float = 1.0
    max_delay: float = 60.0
    max_retry_after: float = 900.0

    def backoff(self, attempt: int) -> float:
        delay = min(self.max_delay, self.base_delay * (2 ** (attempt - 1)))
        return delay * (0.75 + random.random() * 0.5)


class RateGate:
    """Client-side sliding-window pacing per route key, shared by every session/thread of a run."""

    def __init__(self, clock: Clock, limits: dict[str, tuple[int, float]] | None = None, enabled: bool = True,
                 stats: ApiStats | None = None, margin: float = 1.0) -> None:
        self.clock = clock
        self.limits = dict(BACKEND_LIMITS if limits is None else limits)
        self.enabled = enabled
        self.stats = stats
        self.margin = margin
        self._events: dict[str, deque[float]] = {}
        self._blocked_until: dict[str, float] = {}
        self._lock = threading.Lock()

    @staticmethod
    def key_for(method: str, path: str) -> str | None:
        p = path.strip("/").split("?")[0]
        if method.upper() == "DELETE" and p == "candidates/me":
            return "DELETE candidates/me"
        if p.startswith("auth/"):
            return p if p in BACKEND_LIMITS else "auth/*"
        return None

    def acquire(self, key: str | None) -> None:
        if key is None:
            return
        while True:
            with self._lock:
                now = self.clock.now()
                wait = self._blocked_until.get(key, 0.0) - now
                if wait <= 0 and self.enabled and key in self.limits:
                    limit, window = self.limits[key]
                    q = self._events.setdefault(key, deque())
                    while q and q[0] <= now - window - self.margin:
                        q.popleft()
                    if len(q) >= limit:
                        wait = q[0] + window + self.margin - now
                if wait <= 0:
                    if self.enabled and key in self.limits:
                        self._events.setdefault(key, deque()).append(now)
                    return
            if self.stats:
                self.stats.add("paced_seconds", wait)
            self.clock.sleep(wait)

    def block(self, key: str | None, seconds: float) -> None:
        """A 429 told us to wait: every thread using this route waits too."""
        if key is None:
            return
        with self._lock:
            until = self.clock.now() + seconds
            self._blocked_until[key] = max(self._blocked_until.get(key, 0.0), until)


def parse_retry_after(value: str | None, clock: Clock) -> float | None:
    if not value:
        return None
    value = value.strip()
    try:
        return max(0.0, float(value))
    except ValueError:
        pass
    try:
        when = email.utils.parsedate_to_datetime(value)
        return max(0.0, when.timestamp() - clock.wall())
    except (TypeError, ValueError, IndexError):
        return None


def totp(secret: str, at: float, step: int = 30, digits: int = 6) -> str:
    """RFC 6238 (same as tools/totp.py) for staff accounts with two-step verification."""
    secret = secret.replace(" ", "").upper()
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(at // step)), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(value).zfill(digits)


def _message(response: httpx.Response) -> tuple[str, Any]:
    try:
        body = response.json()
    except ValueError:
        return (response.text[:300] or response.reason_phrase), None
    if isinstance(body, dict):
        msg = body.get("message") or response.reason_phrase
        errors = body.get("errors")
        if errors:
            msg += " " + "; ".join(f"{e.get('field')}: {e.get('message')}" for e in errors if isinstance(e, dict))
        return msg, body
    return response.reason_phrase, body


class ApiSession:
    """One signed-in identity (HR or a candidate) on one portal."""

    def __init__(self, client: httpx.Client, *, portal: str, gate: RateGate, clock: Clock,
                 stats: ApiStats, retry: RetryPolicy | None = None, email: str | None = None,
                 password: str | None = None, totp_secret: str | None = None) -> None:
        self.client = client
        self.portal = portal
        self.gate = gate
        self.clock = clock
        self.stats = stats
        self.retry = retry or RetryPolicy()
        self.email = email
        self.password = password
        self.totp_secret = totp_secret
        self.token: str | None = None
        self.user: dict[str, Any] | None = None
        self._auth_lock = threading.Lock()

    # ---- auth ----
    def adopt(self, auth_data: dict[str, Any]) -> None:
        token = (auth_data or {}).get("accessToken")
        if not token:
            raise ApiError(None, "response carried no accessToken", payload=auth_data)
        self.token = token
        self.user = auth_data.get("user")

    def login(self) -> dict[str, Any]:
        if not self.email or not self.password:
            raise ApiError(401, "no credentials to sign in with")
        data = self.request("POST", "auth/login", json={"email": self.email, "password": self.password},
                            auth=False, reauth=False)
        if isinstance(data, dict) and data.get("mfaRequired"):
            if data.get("mfaEnrollmentRequired"):
                raise ApiError(403, "this staff account must enrol in two-step verification first; "
                                    "enrol in the HR portal, then pass --hr-totp-secret")
            if not self.totp_secret:
                raise ApiError(403, "this staff account uses two-step verification; pass --hr-totp-secret "
                                    "(or use an account without MFA)")
            data = self.request("POST", "auth/mfa/verify",
                                json={"mfaToken": data.get("mfaToken"),
                                      "code": totp(self.totp_secret, self.clock.wall())},
                                auth=False, reauth=False)
        self.adopt(data)
        return data

    def _reauth(self, stale_token: str | None) -> None:
        with self._auth_lock:
            if self.token != stale_token:  # another thread already signed in again
                return
            self.stats.add("reauth")
            self.login()

    # ---- requests ----
    def request(self, method: str, path: str, *, json: Any = None, params: dict | None = None,
                files: dict | None = None, auth: bool = True, reauth: bool = True,
                timeout: float | None = None) -> Any:
        path = path.lstrip("/")
        key = RateGate.key_for(method, path)
        attempt = 0
        reauthed = False
        while True:
            attempt += 1
            self.gate.acquire(key)
            headers = {PORTAL_HEADER: self.portal, "Accept": "application/json"}
            token = self.token
            if auth and token:
                headers["Authorization"] = f"Bearer {token}"
            self.stats.add("requests")
            try:
                kwargs: dict[str, Any] = {"headers": headers, "params": params}
                if json is not None:
                    kwargs["json"] = json
                if files is not None:
                    kwargs["files"] = files
                if timeout is not None:
                    kwargs["timeout"] = timeout
                response = self.client.request(method, path, **kwargs)
            except httpx.TransportError as exc:
                if attempt >= self.retry.max_attempts:
                    raise ApiError(None, f"{type(exc).__name__}: {exc}", method=method, path=path) from exc
                self.stats.add("retries_transport")
                delay = self.retry.backoff(attempt)
                log.warning("%s %s: %s, retry %d in %.1fs", method, path, type(exc).__name__, attempt, delay)
                self.clock.sleep(delay)
                continue

            status = response.status_code
            if status in RETRY_STATUSES and attempt < self.retry.max_attempts:
                if status == 429:
                    wait = parse_retry_after(response.headers.get("Retry-After"), self.clock)
                    wait = self.retry.backoff(attempt) if wait is None else min(wait, self.retry.max_retry_after)
                    self.gate.block(key, wait)
                    self.stats.add("retries_429")
                    self.stats.add("retry_after_seconds", wait)
                    log.warning("%s %s: 429, waiting %.1fs (Retry-After)", method, path, wait)
                    # the gate holds every thread on this route; also sleep here for unpaced routes
                    if key is None:
                        self.clock.sleep(wait)
                else:
                    self.stats.add("retries_5xx")
                    delay = self.retry.backoff(attempt)
                    log.warning("%s %s: %d, retry %d in %.1fs", method, path, status, attempt, delay)
                    self.clock.sleep(delay)
                continue
            if status == 401 and auth and reauth and not reauthed and self.email and self.password:
                reauthed = True
                self._reauth(token)
                continue
            if status >= 400:
                msg, body = _message(response)
                raise ApiError(status, msg, method=method, path=path, payload=body)
            if status == 204 or not response.content:
                return None
            try:
                body = response.json()
            except ValueError:
                return response.content
            if isinstance(body, dict) and ("data" in body or "success" in body):
                return body.get("data")
            return body
