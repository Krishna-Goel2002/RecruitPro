"""GitHub REST v3 (+ GraphQL v4 for the repository inventory when a token is available).

Requests per account: profile, repository list (REST, or one GraphQL query that also returns languages, topics,
README.md and the root file list), then per repository in pushed-at order until the budget runs out: latest commits
(100), languages + root listing + README (REST only), up to 4 manifests, contributor statistics (non-forks).
Only repositories the login owns are listed (``type=owner`` / ``ownerAffiliations: OWNER``); forks count only with
the user's own commits after the fork was created.
"""
from __future__ import annotations

from urllib.parse import quote

import httpx

from app.core.budget import Budget, BudgetExceeded
from app.core.clock import parse_instant
from app.core.config import Settings
from app.manifests import is_manifest
from app.sources.common import (MAX_FILE_BYTES, MAX_MANIFESTS_PER_REPO, AccountFacts, ApiClient, ProfileFacts,
                                RepoFacts, SourceError, decode_content)

_GRAPHQL_QUERY = """
query($login: String!, $first: Int!) {
  user(login: $login) {
    repositories(first: $first, ownerAffiliations: OWNER, privacy: PUBLIC,
                 orderBy: {field: PUSHED_AT, direction: DESC}) {
      nodes {
        nameWithOwner url isFork createdAt pushedAt
        languages(first: 20, orderBy: {field: SIZE, direction: DESC}) { edges { size node { name } } }
        repositoryTopics(first: 20) { nodes { topic { name } } }
        readme: object(expression: "HEAD:README.md") { ... on Blob { text } }
        root: object(expression: "HEAD:") { ... on Tree { entries { name type } } }
      }
    }
  }
}
"""
_MANIFEST_PRIORITY = ("package.json", "pyproject.toml", "requirements.txt", "pom.xml", "build.gradle.kts",
                      "build.gradle", "go.mod", "cargo.toml", "composer.json", "gemfile")


def _manifest_order(name: str) -> int:
    lower = name.lower()
    return _MANIFEST_PRIORITY.index(lower) if lower in _MANIFEST_PRIORITY else len(_MANIFEST_PRIORITY)


def _shares(sizes: dict[str, int]) -> dict[str, float]:
    total = sum(size for size in sizes.values() if size > 0)
    return {name: size / total for name, size in sizes.items() if total and size > 0}


class GitHubSource:
    def __init__(self, settings: Settings, budget: Budget, token: str | None,
                 transport: httpx.BaseTransport | None = None) -> None:
        self.token = token or settings.github_token
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        self.api = ApiClient(settings, budget, settings.github_api_url, headers, transport)
        self.graphql_url = settings.github_graphql_url

    # ------------------------------------------------------------------------------------------ account
    def profile(self, login: str) -> ProfileFacts:
        status, data = self.api.get(f"/users/{quote(login)}")
        if status == 404 or not isinstance(data, dict):
            raise SourceError("SOURCE_NOT_FOUND", "the account does not exist")
        if str(data.get("login", "")).lower() != login.lower():
            raise SourceError("SOURCE_NOT_FOUND", "the account name does not match")
        return ProfileFacts(login=str(data["login"]), name=data.get("name") or None, bio=data.get("bio") or None,
                            url=data.get("html_url"), created_at=parse_instant(data.get("created_at")),
                            account_type=data.get("type"))

    def collect(self, login: str, max_repos: int) -> AccountFacts:
        profile = self.profile(login)
        facts = AccountFacts(profile=profile, repos=[])
        try:
            listed = self._inventory(login, max_repos, facts)
            for repo, root in listed:
                try:
                    self._fill(profile, repo, root, facts)
                except BudgetExceeded:
                    repo.partial = True
                    facts.truncated = True
                    if repo.user_commits or not repo.is_fork:
                        facts.repos.append(repo)
                    break
                if repo.is_fork and repo.user_commits == 0:
                    facts.skipped_forks.append(repo.full_name)
                else:
                    facts.repos.append(repo)
        except BudgetExceeded:
            facts.truncated = True
        finally:
            self.api.close()
        return facts

    # ---------------------------------------------------------------------------------------- inventory
    def _inventory(self, login: str, max_repos: int, facts: AccountFacts) -> list[tuple[RepoFacts, list[str] | None]]:
        if self.token:
            try:
                listed = self._graphql_inventory(login, max_repos)
                if listed is not None:
                    return listed
            except SourceError as exc:
                if exc.code == "RATE_LIMITED":
                    raise
            facts.warnings.append("GRAPHQL_UNAVAILABLE")
        status, data = self.api.get(f"/users/{quote(login)}/repos",
                                    {"type": "owner", "sort": "pushed", "per_page": min(100, max_repos)})
        if not isinstance(data, list):
            return []
        if len(data) >= 100 and max_repos > 100:
            facts.truncated = True
        result: list[tuple[RepoFacts, list[str] | None]] = []
        for item in data[:max_repos]:
            if not isinstance(item, dict) or not item.get("full_name"):
                continue
            owner = str((item.get("owner") or {}).get("login", "")).lower()
            if owner and owner != login.lower():
                continue
            result.append((RepoFacts(full_name=str(item["full_name"]), url=item.get("html_url"),
                                     is_fork=bool(item.get("fork")), created_at=parse_instant(item.get("created_at")),
                                     pushed_at=parse_instant(item.get("pushed_at")),
                                     topics=[str(topic) for topic in item.get("topics") or []][:20]), None))
        result.sort(key=lambda pair: pair[0].pushed_at.timestamp() if pair[0].pushed_at else 0.0, reverse=True)
        return result

    def _graphql_inventory(self, login: str, max_repos: int) -> list[tuple[RepoFacts, list[str] | None]] | None:
        status, data = self.api.request("POST", self.graphql_url,
                                        json_body={"query": _GRAPHQL_QUERY,
                                                   "variables": {"login": login, "first": min(100, max_repos)}})
        user = ((data or {}).get("data") or {}).get("user") if isinstance(data, dict) else None
        if not isinstance(user, dict):
            return None
        result: list[tuple[RepoFacts, list[str] | None]] = []
        for node in ((user.get("repositories") or {}).get("nodes") or [])[:max_repos]:
            if not isinstance(node, dict) or not node.get("nameWithOwner"):
                continue
            sizes = {edge["node"]["name"]: int(edge.get("size") or 0)
                     for edge in (node.get("languages") or {}).get("edges") or []
                     if isinstance(edge, dict) and isinstance(edge.get("node"), dict)}
            repo = RepoFacts(full_name=str(node["nameWithOwner"]), url=node.get("url"), is_fork=bool(node.get("isFork")),
                             created_at=parse_instant(node.get("createdAt")),
                             pushed_at=parse_instant(node.get("pushedAt")), languages=_shares(sizes),
                             language_bytes=sizes,
                             topics=[str(((topic or {}).get("topic") or {}).get("name", ""))
                                     for topic in (node.get("repositoryTopics") or {}).get("nodes") or []][:20])
            readme = node.get("readme")
            if isinstance(readme, dict) and isinstance(readme.get("text"), str):
                repo.readme = readme["text"][:MAX_FILE_BYTES]
                repo.readme_path = "README.md"
            root = node.get("root")
            entries = [str(entry.get("name")) for entry in (root or {}).get("entries") or []
                       if isinstance(entry, dict) and entry.get("type") == "blob"] if isinstance(root, dict) else []
            result.append((repo, entries))
        return result

    # ------------------------------------------------------------------------------------------ details
    def _fill(self, profile: ProfileFacts, repo: RepoFacts, root: list[str] | None, facts: AccountFacts) -> None:
        path = f"/repos/{quote(repo.full_name, safe='/')}"
        self._commits(profile, repo, path)
        if repo.is_fork and repo.user_commits == 0:
            return
        if root is None:
            status, languages = self.api.get(f"{path}/languages")
            if isinstance(languages, dict):
                sizes = {str(name): int(size) for name, size in languages.items() if isinstance(size, int)}
                repo.language_bytes = sizes
                repo.languages = _shares(sizes)
            status, listing = self.api.get(f"{path}/contents")
            entries = [entry for entry in listing or [] if isinstance(entry, dict) and entry.get("type") == "file"] \
                if isinstance(listing, list) else []
            root = [str(entry.get("name")) for entry in entries
                    if int(entry.get("size") or 0) <= MAX_FILE_BYTES]
            readme_name = next((name for name in root if name.lower().startswith("readme")), None)
            if readme_name:
                status, payload = self.api.get(f"{path}/contents/{quote(readme_name)}")
                repo.readme = decode_content(payload)
                repo.readme_path = readme_name
        manifests = sorted((name for name in root if is_manifest(name)), key=_manifest_order)
        for name in manifests[:MAX_MANIFESTS_PER_REPO]:
            status, payload = self.api.get(f"{path}/contents/{quote(name)}")
            text = decode_content(payload)
            if text is not None:
                repo.manifests[name] = text
        if not repo.is_fork:
            self._stats(profile, repo, path)

    def _commits(self, profile: ProfileFacts, repo: RepoFacts, path: str) -> None:
        status, commits = self.api.get(f"{path}/commits", {"per_page": 100})
        if not isinstance(commits, list):
            return
        login = profile.login.lower()
        identities = {login} | ({profile.name.lower()} if profile.name else set())
        considered = 0
        for item in commits:
            if not isinstance(item, dict):
                continue
            commit = item.get("commit") or {}
            authored = parse_instant((commit.get("author") or {}).get("date"))
            committed = parse_instant((commit.get("committer") or {}).get("date")) or authored
            if repo.is_fork and repo.created_at and (committed is None or committed <= repo.created_at):
                continue  # upstream history copied by the fork
            considered += 1
            author = item.get("author")
            if isinstance(author, dict) and str(author.get("login", "")).lower() == login:
                repo.user_commits += 1
                if authored:
                    repo.user_commit_dates.append(authored)
                if committed:
                    repo.user_committer_dates.append(committed)
            elif author is None and str((commit.get("author") or {}).get("name", "")).strip().lower() in identities:
                repo.unlinked_commits += 1
        repo.commits_sampled = considered
        if considered:
            repo.authored_share = repo.user_commits / considered

    def _stats(self, profile: ProfileFacts, repo: RepoFacts, path: str) -> None:
        status, stats = self.api.get(f"{path}/stats/contributors")
        if status != 200 or not isinstance(stats, list) or not stats:
            return
        totals: dict[str, int] = {}
        for entry in stats:
            if isinstance(entry, dict):
                name = str((entry.get("author") or {}).get("login", "")).lower()
                totals[name] = totals.get(name, 0) + int(entry.get("total") or 0)
        overall = sum(totals.values())
        if overall:
            repo.authored_share = totals.get(profile.login.lower(), 0) / overall
            repo.share_source = "stats"
