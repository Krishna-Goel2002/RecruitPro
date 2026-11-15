"""GitLab REST v4 (gitlab.com or ``GITLAB_API_URL``).

Requests per account: user lookup, owned public projects, then per project: latest commits (100), languages
(GitLab reports percentages), root tree, README and up to 4 manifests (raw files). GitLab commits are not linked to
user accounts by the API, so the user's commits are those whose author name equals the username or the profile
name; this weaker attribution is reported as warning ``GITLAB_NAME_ATTRIBUTION``.
"""
from __future__ import annotations

from urllib.parse import quote

import httpx

from app.core.budget import Budget, BudgetExceeded
from app.core.clock import parse_instant
from app.core.config import Settings
from app.manifests import is_manifest
from app.sources.common import (MAX_FILE_BYTES, MAX_MANIFESTS_PER_REPO, AccountFacts, ApiClient, ProfileFacts,
                                RepoFacts, SourceError)
from app.sources.github import _manifest_order


class GitLabSource:
    def __init__(self, settings: Settings, budget: Budget, token: str | None,
                 transport: httpx.BaseTransport | None = None) -> None:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self.api = ApiClient(settings, budget, settings.gitlab_api_url, headers, transport)
        self.user_id: int | None = None

    def profile(self, login: str) -> ProfileFacts:
        status, users = self.api.get("/users", {"username": login})
        user = next((item for item in users or [] if isinstance(item, dict)
                     and str(item.get("username", "")).lower() == login.lower()), None) if isinstance(users, list) else None
        if user is None:
            raise SourceError("SOURCE_NOT_FOUND", "the account does not exist")
        self.user_id = int(user["id"])
        status, detail = self.api.get(f"/users/{self.user_id}")
        detail = detail if isinstance(detail, dict) else user
        return ProfileFacts(login=str(user["username"]), name=detail.get("name") or None, bio=detail.get("bio") or None,
                            url=detail.get("web_url"), created_at=parse_instant(detail.get("created_at")),
                            account_type="User")

    def collect(self, login: str, max_repos: int) -> AccountFacts:
        profile = self.profile(login)
        facts = AccountFacts(profile=profile, repos=[], warnings=["GITLAB_NAME_ATTRIBUTION"])
        try:
            status, projects = self.api.get(f"/users/{self.user_id}/projects",
                                            {"owned": "true", "visibility": "public", "order_by": "last_activity_at",
                                             "per_page": min(100, max_repos)})
            for item in (projects if isinstance(projects, list) else [])[:max_repos]:
                if not isinstance(item, dict) or not item.get("id"):
                    continue
                repo = RepoFacts(full_name=str(item.get("path_with_namespace") or item.get("name")),
                                 url=item.get("web_url"), is_fork=bool(item.get("forked_from_project")),
                                 created_at=parse_instant(item.get("created_at")),
                                 pushed_at=parse_instant(item.get("last_activity_at")),
                                 topics=[str(topic) for topic in item.get("topics") or item.get("tag_list") or []][:20])
                try:
                    self._fill(profile, repo, int(item["id"]), str(item.get("default_branch") or "main"))
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

    def _fill(self, profile: ProfileFacts, repo: RepoFacts, project_id: int, branch: str) -> None:
        base = f"/projects/{project_id}"
        self._commits(profile, repo, base)
        if repo.is_fork and repo.user_commits == 0:
            return
        status, languages = self.api.get(f"{base}/languages")
        if isinstance(languages, dict):
            repo.languages = {str(name): float(share) / 100.0 for name, share in languages.items()
                              if isinstance(share, (int, float)) and share > 0}
        status, tree = self.api.get(f"{base}/repository/tree", {"per_page": 100, "ref": branch})
        names = [str(entry.get("name")) for entry in tree or [] if isinstance(entry, dict)
                 and entry.get("type") == "blob"] if isinstance(tree, list) else []
        readme = next((name for name in names if name.lower().startswith("readme")), None)
        if readme:
            repo.readme = self._raw(base, readme, branch)
            repo.readme_path = readme
        for name in sorted((name for name in names if is_manifest(name)), key=_manifest_order)[:MAX_MANIFESTS_PER_REPO]:
            text = self._raw(base, name, branch)
            if text is not None:
                repo.manifests[name] = text

    def _raw(self, base: str, name: str, branch: str) -> str | None:
        self.api.budget.charge()
        try:
            response = self.api._client.get(f"{base}/repository/files/{quote(name, safe='')}/raw", params={"ref": branch})
        except httpx.HTTPError:
            return None
        if response.status_code != 200:
            return None
        return response.content[:MAX_FILE_BYTES].decode("utf-8", errors="replace")

    def _commits(self, profile: ProfileFacts, repo: RepoFacts, base: str) -> None:
        status, commits = self.api.get(f"{base}/repository/commits", {"per_page": 100})
        if not isinstance(commits, list):
            return
        identities = {profile.login.lower()} | ({profile.name.lower()} if profile.name else set())
        considered = 0
        for item in commits:
            if not isinstance(item, dict):
                continue
            authored = parse_instant(item.get("authored_date"))
            committed = parse_instant(item.get("committed_date")) or authored
            if repo.is_fork and repo.created_at and (committed is None or committed <= repo.created_at):
                continue
            considered += 1
            if str(item.get("author_name", "")).strip().lower() in identities:
                repo.user_commits += 1
                if authored:
                    repo.user_commit_dates.append(authored)
                if committed:
                    repo.user_committer_dates.append(committed)
        repo.commits_sampled = considered
        if considered:
            repo.authored_share = repo.user_commits / considered
