"""The guarded HTTP fetcher used for every URL a candidate supplies (sites, badges, DID documents, JWKS, ...).

Per request: SSRF check and address pinning (``app.fetch.ssrf``), budget charge, no cookies, no proxies from the
environment, no JavaScript, ``RecruitProVerifier/1.0 (+contact)`` user agent, 10 s timeout, a byte cap on the decoded
body (streamed; compressed bodies are capped after decoding), at most 5 redirects (each hop re-checked), an
allow-list of content types, and robots.txt for crawled pages.
"""
from __future__ import annotations

import ipaddress
import re
import time
from dataclasses import dataclass, field
from typing import Callable, Protocol
from urllib.parse import urljoin, urlsplit

import httpx

from app.core.budget import Budget, BudgetExceeded
from app.core.config import Settings
from app.fetch.robots import RobotsPolicy
from app.fetch.ssrf import BlockedUrlError, CheckedTarget, FetchError, Resolver, check_url, system_resolver

MAX_REDIRECTS = 5
REDIRECT_STATUSES = {301, 302, 303, 307, 308}
PAGE_TYPES = frozenset({"text/html", "application/xhtml+xml", "text/plain", "application/pdf"})
DATA_TYPES = frozenset({"application/json", "application/ld+json", "text/plain", "application/jwt",
                        "application/vc+jwt", "application/vc+ld+json", "application/did+json",
                        "application/did+ld+json", "application/jwk-set+json", "application/octet-stream",
                        "text/html"})
ROBOTS_MAX_BYTES = 256 * 1024
TransportFactory = Callable[[], httpx.BaseTransport]
_META_CHARSET = re.compile(rb"""<meta[^>]{0,200}?charset\s*=\s*["']?\s*([A-Za-z0-9._:-]{1,40})""", re.I)


def _now() -> float:
    """Monotonic clock of the fetcher (a seam for tests)."""
    return time.monotonic()


def sniff_charset(body: bytes) -> str | None:
    """Charset of an HTML body without a ``charset`` header parameter: a byte-order mark, else ``<meta charset>`` /
    ``<meta http-equiv=content-type content="...; charset=...">`` in the first 2 KB."""
    if body.startswith(b"\xef\xbb\xbf"):
        return "utf-8"
    if body.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "utf-16"
    match = _META_CHARSET.search(body[:2048])
    return match.group(1).decode("ascii").lower() if match else None


@dataclass
class FetchResult:
    url: str  # final URL after redirects
    status: int
    content_type: str
    body: bytes
    truncated: bool = False
    redirects: list[str] = field(default_factory=list)
    charset: str | None = None
    location: str | None = None  # Location header of a redirect response

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def text(self) -> str:
        charset = self.charset
        if charset is None and self.content_type in {"text/html", "application/xhtml+xml"}:
            charset = sniff_charset(self.body)
        try:
            return self.body.decode(charset or "utf-8", errors="replace")
        except LookupError:
            return self.body.decode("utf-8", errors="replace")


class Fetcher(Protocol):
    budget: Budget

    def fetch(self, url: str, *, accept: frozenset[str] = PAGE_TYPES, max_bytes: int = 2_000_000,
              respect_robots: bool = True) -> FetchResult: ...


def _media_type(header: str) -> tuple[str, str | None]:
    parts = [part.strip() for part in header.split(";")]
    media = parts[0].lower() if parts and parts[0] else "application/octet-stream"
    charset = None
    for part in parts[1:]:
        if part.lower().startswith("charset="):
            charset = part.split("=", 1)[1].strip("\"' ") or None
    return media, charset


def _accepted(media: str, accept: frozenset[str]) -> bool:
    if media in accept:
        return True
    return media.endswith("+json") and "application/json" in accept


def _is_ipv6_literal(host: str) -> bool:
    try:
        return ipaddress.ip_address(host).version == 6
    except ValueError:
        return False


def origin(url: str) -> str:
    parts = urlsplit(url)
    return f"{parts.scheme.lower()}://{(parts.hostname or '').lower()}"


class SafeFetcher:
    """SSRF-guarded fetcher. ``resolver`` and ``transport_factory`` are injectable for tests."""

    def __init__(self, settings: Settings, budget: Budget, *, resolver: Resolver = system_resolver,
                 transport_factory: TransportFactory | None = None) -> None:
        self.settings = settings
        self.budget = budget
        self.resolver = resolver
        self.transport_factory = transport_factory or (lambda: httpx.HTTPTransport(retries=0))
        self._robots: dict[str, RobotsPolicy] = {}

    # ------------------------------------------------------------------------------------------ public
    def fetch(self, url: str, *, accept: frozenset[str] = PAGE_TYPES, max_bytes: int = 2_000_000,
              respect_robots: bool = True) -> FetchResult:
        current = url.strip()
        redirects: list[str] = []
        for _ in range(MAX_REDIRECTS + 1):
            target = check_url(current, self.resolver)
            if respect_robots and not self._robots_allow(target.url):
                raise FetchError("ROBOTS_DISALLOWED", "robots.txt disallows this URL")
            result = self._send(target, accept, max_bytes)
            if result.status in REDIRECT_STATUSES:
                location = result.location
                if not location:
                    raise FetchError("BAD_REDIRECT", "redirect without a location")
                following = urljoin(current, location)
                if urlsplit(current).scheme.lower() == "https" and urlsplit(following).scheme.lower() == "http":
                    raise BlockedUrlError("redirects from https to http are not allowed")
                current = following
                redirects.append(current)
                continue
            result.redirects = redirects
            if result.ok and not _accepted(result.content_type, accept):
                raise FetchError("UNSUPPORTED_CONTENT", f"content type {result.content_type} is not accepted")
            return result
        raise FetchError("TOO_MANY_REDIRECTS", "more than 5 redirects")

    # ----------------------------------------------------------------------------------------- internal
    def _robots_allow(self, url: str) -> bool:
        key = origin(url)
        policy = self._robots.get(key)
        if policy is None:
            try:
                result = self.fetch(key + "/robots.txt", accept=frozenset({"text/plain"}) | PAGE_TYPES,
                                    max_bytes=ROBOTS_MAX_BYTES, respect_robots=False)
                policy = RobotsPolicy.from_response(result.status, result.text())
            except FetchError as exc:
                if exc.code == "UNSUPPORTED_CONTENT":
                    policy = RobotsPolicy(allow_all=True)
                elif exc.code == "BUDGET":
                    raise
                else:
                    policy = RobotsPolicy(disallow_all=True)
            self._robots[key] = policy
        return policy.allowed(url)

    def _send(self, target: CheckedTarget, accept: frozenset[str], max_bytes: int) -> FetchResult:
        try:
            self.budget.charge()
        except BudgetExceeded as exc:
            raise FetchError("BUDGET", "request budget exhausted") from exc
        scheme = target.parts.scheme.lower()
        default_port = 443 if scheme == "https" else 80
        ip = ipaddress.ip_address(target.address)
        ip_host = f"[{ip}]" if ip.version == 6 else str(ip)
        port_part = "" if target.port == default_port else f":{target.port}"
        path = target.parts.path or "/"
        pinned_url = f"{scheme}://{ip_host}{port_part}{path}" + (f"?{target.parts.query}" if target.parts.query else "")
        # An IPv6 literal host needs its brackets in the Host header (RFC 7230 5.4).
        host_header = (f"[{target.host}]" if _is_ipv6_literal(target.host) else target.host) + port_part
        headers = {"Host": host_header, "User-Agent": self.settings.user_agent,
                   "Accept": ", ".join(sorted(accept)) + ", */*;q=0.1", "Accept-Encoding": "gzip, deflate"}
        extensions = {"sni_hostname": target.host} if scheme == "https" else {}
        timeout = httpx.Timeout(self.settings.fetch_timeout_seconds)
        # httpx timeouts apply per network operation, so a server trickling bytes could hold a request open far
        # longer; the whole request (headers and body) must finish within the timeout and the verification deadline.
        budget_left = self.budget.deadline_seconds - (time.monotonic() - self.budget.started)
        deadline = _now() + max(0.0, min(self.settings.fetch_timeout_seconds, budget_left))
        try:
            with httpx.Client(transport=self.transport_factory(), timeout=timeout, follow_redirects=False,
                              trust_env=False) as client:
                request = client.build_request("GET", pinned_url, headers=headers, extensions=extensions)
                response = client.send(request, stream=True)
                try:
                    if response.status_code in REDIRECT_STATUSES:
                        return FetchResult(url=target.url, status=response.status_code, content_type="",
                                           body=b"", location=response.headers.get("location"))
                    media, charset = _media_type(response.headers.get("content-type", ""))
                    if 200 <= response.status_code < 300 and not _accepted(media, accept):
                        return FetchResult(url=target.url, status=response.status_code, content_type=media, body=b"")
                    body = bytearray()
                    truncated = False
                    for chunk in response.iter_bytes():
                        body.extend(chunk)
                        if len(body) > max_bytes:
                            truncated = True
                            del body[max_bytes:]
                            break
                        if _now() > deadline:
                            raise FetchError("TIMEOUT", "the response took too long to arrive")
                    return FetchResult(url=target.url, status=response.status_code, content_type=media,
                                       body=bytes(body), truncated=truncated, charset=charset)
                finally:
                    response.close()
        except httpx.TimeoutException as exc:
            raise FetchError("TIMEOUT", "the request timed out") from exc
        except httpx.InvalidURL as exc:  # e.g. characters httpx refuses; not an HTTPError subclass
            raise BlockedUrlError("malformed URL") from exc
        except httpx.HTTPError as exc:
            raise FetchError("NETWORK_ERROR", type(exc).__name__) from exc
