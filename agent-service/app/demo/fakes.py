"""Fake upstreams built on ``httpx.MockTransport``: GitHub (REST + GraphQL), GitLab and websites.

They produce *recorded-style* API payloads from a small declarative spec, so tests, the eval harness and demo mode
exercise the real clients, fetcher and verifiers without network access. Payloads deliberately include popularity
fields (stars, followers) so tests can prove they are stripped and never used.
"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass, field
from typing import Any, Callable
from urllib.parse import parse_qs

import httpx


@dataclass
class FakeCommit:
    login: str | None  # linked account login, None for an unlinked author
    name: str
    date: str
    committed: str | None = None
    email: str = "dev@example.invalid"


@dataclass
class FakeRepo:
    name: str
    languages: dict[str, int] = field(default_factory=dict)
    topics: list[str] = field(default_factory=list)
    files: dict[str, str] = field(default_factory=dict)
    commits: list[FakeCommit] = field(default_factory=list)
    created_at: str = "2024-01-10T09:00:00Z"
    pushed_at: str = "2025-06-01T09:00:00Z"
    fork: bool = False
    stats: list[tuple[str, int]] | None = None  # contributor statistics; None -> 202 (still computing)


@dataclass
class FakeAccount:
    login: str
    name: str | None = None
    bio: str | None = None
    created_at: str = "2019-03-01T10:00:00Z"
    repos: list[FakeRepo] = field(default_factory=list)


Handler = Callable[[httpx.Request], httpx.Response]


def _commit_json(commit: FakeCommit, index: int) -> dict[str, Any]:
    return {"sha": f"{index:040x}", "commit": {
        "author": {"name": commit.name, "email": commit.email, "date": commit.date},
        "committer": {"name": commit.name, "email": commit.email, "date": commit.committed or commit.date},
        "message": f"change {index}"}, "author": {"login": commit.login, "id": 1} if commit.login else None}


def _repo_json(account: FakeAccount, repo: FakeRepo) -> dict[str, Any]:
    full = f"{account.login}/{repo.name}"
    return {"name": repo.name, "full_name": full, "html_url": f"https://github.com/{full}", "fork": repo.fork,
            "owner": {"login": account.login}, "created_at": repo.created_at, "pushed_at": repo.pushed_at,
            "topics": repo.topics, "stargazers_count": 999, "watchers_count": 999, "forks_count": 50,
            "default_branch": "main"}


def github_handler(accounts: list[FakeAccount], calls: list[str] | None = None) -> Handler:
    by_login = {account.login.lower(): account for account in accounts}

    def find_repo(owner: str, name: str) -> tuple[FakeAccount, FakeRepo] | None:
        account = by_login.get(owner.lower())
        repo = next((repo for repo in account.repos if repo.name == name), None) if account else None
        return (account, repo) if account and repo else None

    def handle(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if calls is not None:
            calls.append(f"{request.method} {path}")
        if request.method == "POST" and path.endswith("/graphql"):
            body = json.loads(request.content)
            account = by_login.get(str(body["variables"]["login"]).lower())
            if account is None:
                return httpx.Response(200, json={"data": {"user": None}})
            nodes = []
            for repo in account.repos[: int(body["variables"]["first"])]:
                full = f"{account.login}/{repo.name}"
                readme = repo.files.get("README.md")
                nodes.append({"nameWithOwner": full, "url": f"https://github.com/{full}", "isFork": repo.fork,
                              "createdAt": repo.created_at, "pushedAt": repo.pushed_at, "stargazerCount": 999,
                              "languages": {"edges": [{"size": size, "node": {"name": name}}
                                                      for name, size in repo.languages.items()]},
                              "repositoryTopics": {"nodes": [{"topic": {"name": topic}} for topic in repo.topics]},
                              "readme": {"text": readme} if readme is not None else None,
                              "root": {"entries": [{"name": name, "type": "blob"} for name in repo.files]}})
            return httpx.Response(200, json={"data": {"user": {"repositories": {"nodes": nodes}}}})
        parts = [part for part in path.split("/") if part]
        if len(parts) == 2 and parts[0] == "users":
            account = by_login.get(parts[1].lower())
            if account is None:
                return httpx.Response(404, json={"message": "Not Found"})
            return httpx.Response(200, json={"login": account.login, "id": 1, "type": "User", "name": account.name,
                                             "bio": account.bio, "html_url": f"https://github.com/{account.login}",
                                             "created_at": account.created_at, "followers": 12345, "following": 7,
                                             "public_repos": len(account.repos)})
        if len(parts) == 3 and parts[0] == "users" and parts[2] == "repos":
            account = by_login.get(parts[1].lower())
            if account is None:
                return httpx.Response(404, json={"message": "Not Found"})
            return httpx.Response(200, json=[_repo_json(account, repo) for repo in account.repos])
        if len(parts) >= 4 and parts[0] == "repos":
            found = find_repo(parts[1], parts[2])
            if found is None:
                return httpx.Response(404, json={"message": "Not Found"})
            account, repo = found
            rest = parts[3:]
            if rest == ["commits"]:
                return httpx.Response(200, json=[_commit_json(commit, index)
                                                 for index, commit in enumerate(repo.commits)])
            if rest == ["languages"]:
                return httpx.Response(200, json=repo.languages)
            if rest == ["contents"]:
                return httpx.Response(200, json=[{"name": name, "path": name, "type": "file", "size": len(text)}
                                                 for name, text in repo.files.items()])
            if len(rest) == 2 and rest[0] == "contents":
                text = repo.files.get(rest[1])
                if text is None:
                    return httpx.Response(404, json={"message": "Not Found"})
                return httpx.Response(200, json={"name": rest[1], "encoding": "base64",
                                                 "content": base64.b64encode(text.encode()).decode()})
            if rest == ["stats", "contributors"]:
                if repo.stats is None:
                    return httpx.Response(202, json={})
                return httpx.Response(200, json=[{"author": {"login": login}, "total": total, "weeks": []}
                                                 for login, total in repo.stats])
        return httpx.Response(404, json={"message": "Not Found"})

    return handle


def gitlab_handler(accounts: list[FakeAccount], calls: list[str] | None = None) -> Handler:
    users = {index + 1: account for index, account in enumerate(accounts)}
    projects: dict[int, tuple[FakeAccount, FakeRepo]] = {}
    for user_id, account in users.items():
        for number, repo in enumerate(account.repos):
            projects[user_id * 1000 + number] = (account, repo)

    def handle(request: httpx.Request) -> httpx.Response:
        path = request.url.path.replace("/api/v4", "", 1)
        if calls is not None:
            calls.append(f"{request.method} {path}")
        parts = [part for part in path.split("/") if part]
        query = parse_qs(request.url.query.decode() if isinstance(request.url.query, bytes) else request.url.query)
        if parts == ["users"]:
            name = (query.get("username") or [""])[0].lower()
            return httpx.Response(200, json=[{"id": user_id, "username": account.login}
                                             for user_id, account in users.items() if account.login.lower() == name])
        if len(parts) == 2 and parts[0] == "users":
            account = users.get(int(parts[1]))
            if account is None:
                return httpx.Response(404, json={})
            return httpx.Response(200, json={"id": int(parts[1]), "username": account.login, "name": account.name,
                                             "bio": account.bio, "web_url": f"https://gitlab.com/{account.login}",
                                             "created_at": account.created_at, "followers": 99})
        if len(parts) == 3 and parts[0] == "users" and parts[2] == "projects":
            user_id = int(parts[1])
            return httpx.Response(200, json=[{
                "id": project_id, "name": repo.name, "path_with_namespace": f"{account.login}/{repo.name}",
                "web_url": f"https://gitlab.com/{account.login}/{repo.name}", "created_at": repo.created_at,
                "last_activity_at": repo.pushed_at, "topics": repo.topics, "star_count": 500, "forks_count": 9,
                "default_branch": "main", "forked_from_project": {"id": 1} if repo.fork else None}
                for project_id, (account, repo) in projects.items() if project_id // 1000 == user_id])
        if len(parts) >= 3 and parts[0] == "projects":
            found = projects.get(int(parts[1]))
            if found is None:
                return httpx.Response(404, json={})
            account, repo = found
            rest = parts[2:]
            if rest == ["repository", "commits"]:
                return httpx.Response(200, json=[{"id": f"{index:040x}", "author_name": commit.name,
                                                  "author_email": commit.email, "authored_date": commit.date,
                                                  "committed_date": commit.committed or commit.date}
                                                 for index, commit in enumerate(repo.commits)])
            if rest == ["languages"]:
                total = sum(repo.languages.values()) or 1
                return httpx.Response(200, json={name: round(size * 100 / total, 2)
                                                 for name, size in repo.languages.items()})
            if rest == ["repository", "tree"]:
                return httpx.Response(200, json=[{"name": name, "type": "blob", "path": name} for name in repo.files])
            if len(rest) == 4 and rest[:2] == ["repository", "files"] and rest[3] == "raw":
                text = repo.files.get(rest[2])
                return httpx.Response(200, text=text) if text is not None else httpx.Response(404, json={})
        return httpx.Response(404, json={})

    return handle


@dataclass
class FakePage:
    body: str | bytes = ""
    content_type: str = "text/html; charset=utf-8"
    status: int = 200
    headers: dict[str, str] = field(default_factory=dict)


def web_handler(pages: dict[str, FakePage], calls: list[str] | None = None, *,
                code: str | None = None) -> Handler:
    """Serve ``pages`` keyed by ``https://host/path`` using the Host header (the URL holds the pinned IP).

    ``{{code}}`` in a body is replaced with ``code`` (demo ownership challenges only)."""

    def handle(request: httpx.Request) -> httpx.Response:
        host = request.headers.get("host", request.url.host)
        target = f"{request.url.scheme}://{host}{request.url.raw_path.decode()}"
        if calls is not None:
            calls.append(target)
        page = pages.get(target) or pages.get(target.rstrip("/")) or pages.get(target + "/" if "?" not in target else "")
        if page is None:
            return httpx.Response(404, text="not found", headers={"content-type": "text/plain"})
        body = page.body
        if code is not None and isinstance(body, str):
            body = body.replace("{{code}}", code)
        content = body.encode("utf-8") if isinstance(body, str) else body
        return httpx.Response(page.status, content=content, headers={"content-type": page.content_type, **page.headers})

    return handle


def fixed_resolver(mapping: dict[str, list[str]], default: list[str] | None = None) -> Callable[[str], list[str]]:
    from app.fetch.ssrf import FetchError

    def resolve(host: str) -> list[str]:
        if host in mapping:
            return list(mapping[host])
        if default is not None:
            return list(default)
        raise FetchError("DNS_FAILURE", "unknown host")

    return resolve
