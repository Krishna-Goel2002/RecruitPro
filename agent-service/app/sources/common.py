"""Provider-neutral facts about a code-host account and its repositories, plus shared HTTP plumbing.

Popularity signals (stars, watchers, forks counts, followers, following) are removed from every API payload the
moment it is parsed (``strip_popularity``), so no later code can read them.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import httpx

from app.core.budget import Budget
from app.core.config import Settings

POPULARITY_KEYS = frozenset({
    "stargazers_count", "stargazers", "stargazerCount", "watchers", "watchers_count", "watchersCount", "forks",
    "forks_count", "forkCount", "followers", "following", "followers_url", "following_url", "subscribers_count",
    "network_count", "star_count", "starrers", "stargazers_url", "subscribers_url", "public_gists",
})
MAX_MANIFESTS_PER_REPO = 4
MAX_FILE_BYTES = 200_000


class SourceError(Exception):
    """The source could not be read. Codes: SOURCE_NOT_FOUND, RATE_LIMITED, TOKEN_INVALID, UPSTREAM_ERROR."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


def strip_popularity(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: strip_popularity(item) for key, item in value.items() if key not in POPULARITY_KEYS}
    if isinstance(value, list):
        return [strip_popularity(item) for item in value]
    return value


@dataclass
class ProfileFacts:
    login: str
    name: str | None
    bio: str | None
    url: str | None
    created_at: datetime | None
    account_type: str | None = None


@dataclass
class RepoFacts:
    full_name: str
    url: str | None
    is_fork: bool
    created_at: datetime | None
    pushed_at: datetime | None
    languages: dict[str, float] = field(default_factory=dict)  # share of the repository, 0..1
    language_bytes: dict[str, int] | None = None
    topics: list[str] = field(default_factory=list)
    manifests: dict[str, str] = field(default_factory=dict)  # path -> text
    readme: str | None = None
    readme_path: str | None = None
    commits_sampled: int = 0
    user_commits: int = 0
    user_commit_dates: list[datetime] = field(default_factory=list)
    user_committer_dates: list[datetime] = field(default_factory=list)
    unlinked_commits: int = 0
    authored_share: float | None = None
    share_source: str = "sample"  # "stats" (contributors statistics) or "sample" (latest commits)
    partial: bool = False


@dataclass
class AccountFacts:
    profile: ProfileFacts
    repos: list[RepoFacts]
    skipped_forks: list[str] = field(default_factory=list)  # forks without the user's own commits
    warnings: list[str] = field(default_factory=list)
    truncated: bool = False


class ApiClient:
    """Small JSON GET helper with budget accounting and error mapping."""

    def __init__(self, settings: Settings, budget: Budget, base_url: str, headers: dict[str, str],
                 transport: httpx.BaseTransport | None) -> None:
        self.budget = budget
        self._client = httpx.Client(base_url=base_url, headers={"User-Agent": settings.user_agent, **headers},
                                    timeout=httpx.Timeout(settings.fetch_timeout_seconds), transport=transport,
                                    follow_redirects=False, trust_env=False)

    def close(self) -> None:
        self._client.close()

    def request(self, method: str, path: str, *, params: dict[str, Any] | None = None,
                json_body: Any = None) -> tuple[int, Any]:
        """(status, stripped JSON or None). 404/409/204 -> (status, None); 202 -> (202, None)."""
        self.budget.charge()
        try:
            response = self._client.request(method, path, params=params, json=json_body)
        except httpx.TimeoutException as exc:
            raise SourceError("UPSTREAM_ERROR", "timeout") from exc
        except httpx.HTTPError as exc:
            raise SourceError("UPSTREAM_ERROR", type(exc).__name__) from exc
        status = response.status_code
        if status == 401:
            raise SourceError("TOKEN_INVALID", "the access token was rejected")
        if status == 429 or (status == 403 and response.headers.get("x-ratelimit-remaining") == "0"):
            raise SourceError("RATE_LIMITED", "the code host rate limit was reached")
        if status >= 500:
            raise SourceError("UPSTREAM_ERROR", f"code host answered {status}")
        if status in (202, 204, 403, 404, 409, 451) or status >= 300:
            return status, None
        try:
            return status, strip_popularity(response.json())
        except ValueError:
            return status, None

    def get(self, path: str, params: dict[str, Any] | None = None) -> tuple[int, Any]:
        return self.request("GET", path, params=params)


def decode_content(payload: Any) -> str | None:
    """Text of a GitHub contents API file object (base64), capped at MAX_FILE_BYTES."""
    if not isinstance(payload, dict) or payload.get("encoding") != "base64":
        return None
    try:
        raw = base64.b64decode(str(payload.get("content", "")), validate=False)
    except ValueError:
        return None
    return raw[:MAX_FILE_BYTES].decode("utf-8", errors="replace")
