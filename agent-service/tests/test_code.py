"""Code verifier rules (GitHub REST/GraphQL and GitLab through MockTransport)."""
from __future__ import annotations

import hashlib
import json
from datetime import timedelta

import pytest

from app.core.budget import Budget
from app.core.clock import parse_instant
from app.core.config import load_settings
from app.demo.fakes import FakeAccount
from app.sources.common import POPULARITY_KEYS, RepoFacts, strip_popularity
from app.sources.github import GitHubSource
from app.sources.gitlab import GitLabSource
from app.verifiers.code import backdated_commits, counted_commit_dates
from eval.builders import NOW, days_ago, monthly, package_json, repo
from tests.conftest import HEADERS

REACT = {"package.json": package_json({"react": "^18", "react-dom": "^18"})}
TS = {"TypeScript": 50000}


def _post(client, fake_net, repos, claims, **extra):
    fake_net.accounts.append(FakeAccount(login="dev", name="Dev Person", bio="synthetic", repos=repos))
    body = {"provider": "github", "login": "dev", "ownershipProven": True,
            "claims": [{"id": f"fact:{index}", "type": kind, "value": value}
                       for index, (kind, value) in enumerate(claims, start=1)], **extra}
    response = client.post("/api/v1/verify/code", headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


def _verdicts(data):
    return [verdict["verdict"] for verdict in data["verdicts"]]


def test_verified_needs_two_strong_repos_spanning_thirty_days(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS, files=REACT), repo("b", languages=TS, files=REACT,
                                                                                user_dates=monthly(200, 3))],
                 [("SKILL", "React"), ("SKILL", "TypeScript"), ("SKILL", "Go"), ("EXPERIENCE", "Engineer")])
    assert _verdicts(data) == ["VERIFIED", "VERIFIED", "NOT_FOUND", "NOT_CHECKABLE"]
    assert data["verifier"] == "code" and data["verifierVersion"] == "code-1.0"
    assert data["verdicts"][0]["probeQuestion"] is None
    assert data["verdicts"][3]["probeQuestion"]
    react = next(skill for skill in data["skills"] if skill["skillId"] == "react")
    assert react["repoCount"] == 2 and "manifest-1-1" in react["evidenceKeys"]


def test_ownership_unproven_caps_at_partly(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS), repo("b", languages=TS, user_dates=monthly(200, 3))],
                 [("SKILL", "TypeScript")], ownershipProven=False)
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED" and verdict["ownershipProven"] is False
    assert any("capped" in reason["text"] for reason in verdict["reasons"])


def test_short_span_single_repo_and_minority_author_are_partly(client, fake_net):
    data = _post(client, fake_net, [
        repo("a", languages={"Python": 10}, user_dates=[days_ago(40), days_ago(35)]),
        repo("b", languages={"Python": 10}, user_dates=[days_ago(30), days_ago(25)]),
        repo("c", languages={"Go": 10}, others=40), repo("d", languages={"Go": 10}, others=40),
        repo("e", languages={"Rust": 10})], [("SKILL", "Python"), ("SKILL", "Go"), ("SKILL", "Rust")])
    assert _verdicts(data) == ["PARTLY_VERIFIED"] * 3


def test_forks_without_own_commits_are_ignored(client, fake_net):
    data = _post(client, fake_net, [repo("tensorflow", languages={"Python": 10}, fork=True, user_dates=[], others=3,
                                         created_days_ago=50)], [("SKILL", "Python")])
    assert _verdicts(data) == ["NOT_FOUND"]
    assert [note["code"] for note in data["verdicts"][0]["integrityNotes"]] == ["FORK_ONLY"]
    assert not any(item["kind"] == "REPOSITORY" for item in data["evidence"])


def test_fork_counts_only_commits_after_the_fork(client, fake_net):
    fork = repo("lib", languages={"Python": 10}, fork=True, created_days_ago=100,
                user_dates=[days_ago(500), days_ago(400)])  # all before the fork: upstream history
    data = _post(client, fake_net, [fork], [("SKILL", "Python")])
    assert _verdicts(data) == ["NOT_FOUND"]


def test_bulk_history_is_noted_and_does_not_count(client, fake_net):
    data = _post(client, fake_net, [
        repo("a", languages=TS, created_days_ago=3, user_dates=[days_ago(300 - i * 30) for i in range(8)],),
        repo("b", languages=TS, created_days_ago=2, user_dates=[days_ago(2)] * 10)], [("SKILL", "TypeScript")])
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert {note["code"] for note in verdict["integrityNotes"]} == {"BULK_HISTORY", "BACKDATED_HISTORY"}


BACKDATED = [days_ago(400 - index * 30) for index in range(10)]  # a year of history ...


def test_backdated_history_in_fresh_repos_is_not_verified(client, fake_net):
    # ... in repositories created three days ago: commit dates are set by the committer
    data = _post(client, fake_net, [repo("shop", languages=TS, created_days_ago=3, user_dates=BACKDATED),
                                    repo("shop2", languages=TS, created_days_ago=3, user_dates=BACKDATED)],
                 [("SKILL", "TypeScript")])
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert any("span 0 days" in reason["text"] for reason in verdict["reasons"])
    notes = [note for note in verdict["integrityNotes"] if note["code"] == "BACKDATED_HISTORY"]
    assert [note["evidenceKeys"] for note in notes] == [["repo-1", "commits-1"], ["repo-2", "commits-2"]]
    assert "10 commit(s)" in notes[0]["text"] and "repository A" in notes[0]["text"]
    assert "shop" not in json.dumps(notes) and "dev" not in json.dumps(notes)
    commits = next(item for item in data["evidence"] if item["key"] == "commits-1")
    assert f"Dates counted (committer dates, not before the repository was created): {days_ago(3)[:10]} to " \
           f"{days_ago(3)[:10]}" in commits["snapshotText"]


def test_fresh_repo_counts_only_its_real_age(client, fake_net):
    # one genuine repository with 20 days of history plus a backdated fresh one: the fresh one may not stretch the span
    data = _post(client, fake_net, [repo("old", languages=TS, created_days_ago=400,
                                         user_dates=[days_ago(60), days_ago(40)]),
                                    repo("fresh", languages=TS, created_days_ago=45, user_dates=BACKDATED)],
                 [("SKILL", "TypeScript")])
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert any("span 20 days" in reason["text"] for reason in verdict["reasons"])
    assert [note["code"] for note in verdict["integrityNotes"]] == ["BACKDATED_HISTORY"]


def test_genuine_old_history_is_still_verified(client, fake_net):
    first_push = days_ago(300)  # first commit made a couple of hours before the repository was created: normal
    data = _post(client, fake_net, [repo("a", languages=TS, created_days_ago=400, user_dates=monthly(300, 6)),
                                    repo("b", languages=TS, created_days_ago=300, user_dates=[days_ago(300)]
                                         + monthly(270, 4)),
                                    repo("c", languages=TS, created_days_ago=299.9, user_dates=[first_push])],
                 [("SKILL", "TypeScript")])
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "VERIFIED"
    assert verdict["integrityNotes"] == []


def test_committer_date_is_preferred_over_author_date(client, fake_net):
    # author dates claim ten months of work; the commits were actually committed within two days
    data = _post(client, fake_net, [repo("a", languages=TS, user_dates=BACKDATED, committed=days_ago(3)),
                                    repo("b", languages=TS, user_dates=BACKDATED, committed=days_ago(2))],
                 [("SKILL", "TypeScript")])
    verdict = data["verdicts"][0]
    assert verdict["verdict"] == "PARTLY_VERIFIED"
    assert any("span 1 days" in reason["text"] for reason in verdict["reasons"])


@pytest.mark.parametrize("gitlab", [False, True])
def test_parsers_keep_committer_dates(fake_net, gitlab):
    fake_net.gitlab = gitlab
    fake_net.accounts.append(FakeAccount(login="dev", name="Dev Person", repos=[
        repo("a", languages=TS, created_days_ago=400, user_dates=[days_ago(300), days_ago(200)],
             committed=days_ago(5))]))
    settings = load_settings({"AGENT_SERVICE_TOKEN": "x"})
    source_cls = GitLabSource if gitlab else GitHubSource
    facts = source_cls(settings, Budget(50, 30), None, transport=fake_net.network().api_transport).collect("dev", 10)
    parsed = facts.repos[0]
    assert [moment.isoformat() for moment in parsed.user_commit_dates] == \
        [parse_instant(days_ago(300)).isoformat(), parse_instant(days_ago(200)).isoformat()]
    assert counted_commit_dates(parsed, NOW) == [parse_instant(days_ago(5))] * 2


def test_counted_dates_are_clamped_to_creation_and_now():
    facts = RepoFacts(full_name="x/y", url=None, is_fork=False, created_at=NOW - timedelta(days=3), pushed_at=None,
                      user_commit_dates=[NOW - timedelta(days=300), NOW + timedelta(days=90)])
    assert counted_commit_dates(facts, NOW) == [NOW - timedelta(days=3), NOW]
    assert backdated_commits(facts) == 1
    assert backdated_commits(RepoFacts(full_name="x/y", url=None, is_fork=False, created_at=None, pushed_at=None,
                                       user_commit_dates=[NOW - timedelta(days=300)])) == 0


def test_unlinked_author_and_template_readme_notes(client, fake_net):
    readme = "# Getting Started\nThis project was bootstrapped with [Create React App](https://example.org)."
    data = _post(client, fake_net, [repo("a", languages=TS, unlinked=True, files={"README.md": readme})],
                 [("SKILL", "TypeScript")])
    codes = {note["code"] for note in data["verdicts"][0]["integrityNotes"]}
    assert codes == {"UNLINKED_AUTHOR", "TEMPLATE_README"}
    commits = next(item for item in data["evidence"] if item["key"] == "commits-1")
    assert commits["attributes"]["userCommits"] == 0


def test_dependencies_topics_and_hierarchy_map_to_skills(client, fake_net):
    pom = ("<project><dependencies><dependency><groupId>org.springframework.boot</groupId>"
           "<artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>")
    data = _post(client, fake_net, [
        repo("a", languages={"Java": 10}, files={"pom.xml": pom, "requirements.txt": "psycopg2==2.9\n"},
             topics=["machine-learning"]),
        repo("b", languages={"Java": 10}, files={"pom.xml": pom, "requirements.txt": "psycopg2==2.9\n"},
             topics=["machine-learning"], user_dates=monthly(100, 3))],
        [("SKILL", "Spring Boot"), ("SKILL", "Spring"), ("SKILL", "SQL"), ("SKILL", "Machine Learning")])
    assert _verdicts(data) == ["VERIFIED"] * 4
    dependencies = [dep for item in data["evidence"] if item["kind"] == "MANIFEST"
                    for dep in item["attributes"]["dependencies"]]
    assert "org.springframework.boot" in dependencies and "psycopg2" in dependencies


def test_popularity_signals_are_never_read(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS)], [("SKILL", "TypeScript")])
    text = json.dumps(data)
    for word in ("999", "12345", "stargazers", "followers", "watchers"):
        assert word not in text
    assert strip_popularity({"a": {"stargazers_count": 1, "x": [{"followers": 2, "y": 3}]}}) == {"a": {"x": [{"y": 3}]}}
    assert {"stargazers_count", "followers", "watchers_count", "star_count"} <= POPULARITY_KEYS


def test_evidence_items_follow_the_contract(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS, files={"README.md": "x" * 5000})], [("SKILL", "TypeScript")])
    for item in data["evidence"]:
        assert item["contentHash"] == "sha256:" + hashlib.sha256(item["snapshotText"].encode()).hexdigest()
        assert item["retrievedAt"].endswith("Z") and len(item["excerpt"]) <= 1000 and len(item["key"]) <= 40
    assert {"PROFILE", "REPOSITORY", "COMMIT_SUMMARY", "README"} <= {item["kind"] for item in data["evidence"]}


def test_reasons_are_de_identified(client, fake_net):
    data = _post(client, fake_net, [repo("secret-project-dev", languages=TS),
                                    repo("dev-portfolio", languages=TS, user_dates=monthly(100, 3))],
                 [("SKILL", "TypeScript")])
    texts = json.dumps([verdict["reasons"] + verdict["integrityNotes"] for verdict in data["verdicts"]])
    assert "secret-project" not in texts and "dev-portfolio" not in texts and "github.com" not in texts
    assert "repository A" in texts


def test_budget_truncates_and_reports(client, fake_net):
    data = _post(client, fake_net, [repo(f"r{index}", languages=TS) for index in range(5)], [("SKILL", "TypeScript")],
                 limits={"maxRepos": 30, "maxRequests": 8})
    assert data["usage"]["truncated"] is True and data["usage"]["requests"] <= 8
    assert "BUDGET_TRUNCATED" in data["warnings"]


def test_graphql_inventory_is_used_with_a_token(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS, files=REACT)], [("SKILL", "React")],
                 accessToken="synthetic-token")
    assert "POST /graphql" in fake_net.calls
    assert not any(call.endswith("/languages") for call in fake_net.calls)
    assert data["verdicts"][0]["verdict"] == "PARTLY_VERIFIED"


def test_contributor_stats_fallback_to_commit_sample(client, fake_net):
    data = _post(client, fake_net, [repo("a", languages=TS, stats=False, others=2)], [("SKILL", "TypeScript")])
    commits = next(item for item in data["evidence"] if item["key"] == "commits-1")
    assert commits["attributes"]["shareSource"] == "sample"
    assert commits["attributes"]["authoredShare"] == 6 / 8


def test_unknown_account_is_a_422(client, fake_net):
    response = client.post("/api/v1/verify/code", headers=HEADERS,
                           json={"provider": "github", "login": "nobody", "claims": []})
    assert response.status_code == 422 and response.json()["detail"]["code"] == "SOURCE_NOT_FOUND"


def test_login_validation(client):
    for login in ("../etc", "a/b", "-x", "x" * 120, "a b"):
        response = client.post("/api/v1/verify/code", headers=HEADERS,
                               json={"provider": "github", "login": login, "claims": []})
        assert response.status_code == 422


def test_gitlab(client, fake_net):
    fake_net.gitlab = True
    data = _post(client, fake_net, [repo("a", languages=TS, files=REACT), repo("b", languages=TS,
                                                                               user_dates=monthly(200, 3))],
                 [("SKILL", "TypeScript")], provider="gitlab")
    assert _verdicts(data) == ["VERIFIED"]
    assert "GITLAB_NAME_ATTRIBUTION" in data["warnings"]
    assert "999" not in json.dumps(data) and "500" not in json.dumps(data["evidence"][1]["attributes"])


# ------------------------------------------------------------------------------------- bug-hunt regressions
@pytest.mark.parametrize("status,headers,body", [
    (403, {"retry-after": "60", "x-ratelimit-remaining": "4000"}, {"message": "You have exceeded a secondary rate limit"}),
    (403, {}, {"message": "API rate limit exceeded for 1.2.3.4."}),
    (403, {"ratelimit-remaining": "0"}, {"message": "Forbidden"}),  # GitLab's header name
    (429, {}, {}),
])
def test_rate_limits_are_reported_not_mistaken_for_a_missing_account(status, headers, body):
    import httpx

    from app.sources.common import SourceError

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body, headers=headers)

    settings = load_settings({"AGENT_SERVICE_TOKEN": "x"})
    source = GitHubSource(settings, Budget(10, 30), None, transport=httpx.MockTransport(handler))
    with pytest.raises(SourceError) as error:
        source.profile("dev")
    assert error.value.code == "RATE_LIMITED"


def test_a_plain_403_is_still_not_found():
    import httpx

    from app.sources.common import SourceError

    source = GitHubSource(load_settings({"AGENT_SERVICE_TOKEN": "x"}), Budget(10, 30), None,
                          transport=httpx.MockTransport(lambda request: httpx.Response(403, json={"message": "Forbidden"})))
    with pytest.raises(SourceError) as error:
        source.profile("dev")
    assert error.value.code == "SOURCE_NOT_FOUND"


def test_gitlab_raw_files_are_streamed_and_capped(fake_net):
    import httpx

    from app.sources.common import MAX_FILE_BYTES

    fake_net.gitlab = True
    fake_net.accounts.append(FakeAccount(login="dev", name="Dev Person", repos=[
        repo("a", languages=TS, files={"package.json": "{}"})]))
    inner = fake_net.network().api_transport
    pulled = {"chunks": 0}

    def endless():
        while True:
            pulled["chunks"] += 1
            yield b" " * 65536

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/raw"):
            return httpx.Response(200, content=endless())
        return inner.handle_request(request)

    facts = GitLabSource(load_settings({"AGENT_SERVICE_TOKEN": "x"}), Budget(50, 30), None,
                         transport=httpx.MockTransport(handler)).collect("dev", 10)
    assert len(facts.repos[0].manifests["package.json"]) == MAX_FILE_BYTES
    assert pulled["chunks"] <= MAX_FILE_BYTES // 65536 + 2


def test_gitlab_raw_file_rate_limit_is_reported_like_github(fake_net):
    import httpx

    from app.sources.common import SourceError

    fake_net.gitlab = True
    fake_net.accounts.append(FakeAccount(login="dev", repos=[repo("a", languages=TS, files={"package.json": "{}"})]))
    inner = fake_net.network().api_transport

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/raw"):
            return httpx.Response(429, text="Retry later")
        return inner.handle_request(request)

    with pytest.raises(SourceError) as error:
        GitLabSource(load_settings({"AGENT_SERVICE_TOKEN": "x"}), Budget(50, 30), None,
                     transport=httpx.MockTransport(handler)).collect("dev", 10)
    assert error.value.code == "RATE_LIMITED"


def test_deeply_nested_manifests_do_not_crash_the_verification(client, fake_net):
    from app.manifests import parse_manifest

    assert parse_manifest("package.json", "[" * 100_000) == []
    assert parse_manifest("Cargo.toml", "a = " + "[" * 100_000) == []
    data = _post(client, fake_net, [repo("a", languages=TS, files={"package.json": "{\"a\":" * 50_000})],
                 [("SKILL", "TypeScript")])
    assert _verdicts(data) == ["PARTLY_VERIFIED"]
