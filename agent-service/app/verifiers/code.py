"""Code verifier (GitHub / GitLab). Reads public metadata through the APIs; never clones or runs code and never reads
stars, watchers or followers.

Repositories considered: owned by the login, non-forks, and forks with the user's own commits made after the fork
was created (other forks are ignored with integrity note ``FORK_ONLY``).

Per repository: languages (share of bytes, >= 5 % counts), manifest dependencies, topics, README excerpt, the user's
commit count and authored share (user commits / all commits from contributor statistics, else from the latest 100
commits; 1.0 when the user is the only author), first/last commit dates. Languages, dependencies and topics are
mapped to taxonomy skills (``app.skills``).

SKILL claim:
* ``VERIFIED`` when >= 2 repositories show the skill with authored share >= 0.5 and the user's commits in those
  repositories together span >= 30 days (the shared ownership cap lowers this to PARTLY_VERIFIED when ownership is
  not proven). Repositories flagged ``BULK_HISTORY`` do not count towards these two (they still show the skill);
* ``PARTLY_VERIFIED`` when any considered repository shows the skill;
* ``NOT_FOUND`` otherwise. Other claim types -> ``NOT_CHECKABLE``. Code never yields ``CONTRADICTED``.

Integrity notes (never verdicts): ``BULK_HISTORY`` (repository created < 14 days ago and all of the user's commits on
one day), ``FORK_ONLY`` (fork without own commits ignored), ``UNLINKED_AUTHOR`` (commits with the account's name but
an e-mail address not linked to the account), ``TEMPLATE_README`` (README matches a known project template).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from app.core.budget import Budget
from app.core.config import Settings
from app.deidentify import Identities, repo_label
from app.evidence import make_item
from app.manifests import parse_manifest
from app.models import ClaimIn, CodeVerifyRequest, EvidenceItem, IntegrityNote, Reason, SkillEvidence, Usage
from app.network import Network
from app.skills import (SkillRef, covers, resolve_claim_skill, skill_for_dependency, skill_for_language,
                        skill_for_topic)
from app.sources.common import AccountFacts, RepoFacts
from app.sources.github import GitHubSource
from app.sources.gitlab import GitLabSource
from app.verdicts import DraftVerdict, VerifierOutput

LANGUAGE_MIN_SHARE = 0.05
STRONG_SHARE = 0.5
SPAN_DAYS = 30
BULK_WINDOW = timedelta(days=14)
README_EXCERPT = 1000
TEMPLATE_SIGNATURES = (
    "this project was bootstrapped with [create react app]",
    "this project was bootstrapped with create react app",
    "this template provides a minimal setup to get react working in vite",
    "this is a [next.js](https://nextjs.org) project bootstrapped with",
    "this is a next.js project bootstrapped with",
    "this project was generated with [angular cli]",
    "this project was generated using [angular cli]",
    "this template should help get you started developing with vue 3 in vite",
    "everything you need to build a svelte project, powered by",
    "for further reference, please consider the following sections: * [official apache maven documentation]",
)
TEMPLATE_MAX_CHARS = 4000


@dataclass
class _Shown:
    keys: list[str] = field(default_factory=list)
    quotes: list[tuple[str, str]] = field(default_factory=list)  # (evidence key, verbatim quote)


@dataclass
class _Repo:
    index: int
    facts: RepoFacts
    key: str
    commits_key: str
    readme_key: str | None
    manifest_keys: dict[str, str]
    skills: dict[str, _Shown] = field(default_factory=dict)  # skill id -> where it shows
    raw_names: dict[str, _Shown] = field(default_factory=dict)  # lower-case language/topic/dependency -> where
    bulk: bool = False


def _article(word: str) -> str:
    return "an" if word[:1] in "aeiou" else "a"


def _pct(share: float) -> str:
    return f"{share * 100:.1f}%"


def _day(moment: datetime | None) -> str:
    return moment.date().isoformat() if moment else "unknown"


def is_bulk_history(repo: RepoFacts, now: datetime) -> bool:
    if repo.created_at is None or now - repo.created_at >= BULK_WINDOW or not repo.user_commit_dates:
        return False
    authored_days = {moment.date() for moment in repo.user_commit_dates}
    committed_days = {moment.date() for moment in repo.user_committer_dates} or authored_days
    return len(authored_days) == 1 or len(committed_days) == 1


def is_template_readme(text: str | None) -> bool:
    if not text:
        return False
    normalised = " ".join(text.lower().split())
    return len(normalised) <= TEMPLATE_MAX_CHARS and any(signature in normalised for signature in TEMPLATE_SIGNATURES)


def _repo_snapshot(repo: RepoFacts) -> str:
    languages = ", ".join(f"{name} {_pct(share)}" for name, share in
                          sorted(repo.languages.items(), key=lambda pair: -pair[1])) or "none reported"
    return "\n".join([
        f"Repository: {repo.full_name}",
        f"URL: {repo.url or 'unknown'}",
        f"Fork: {'yes' if repo.is_fork else 'no'}",
        f"Created: {_day(repo.created_at)}",
        f"Last push: {_day(repo.pushed_at)}",
        f"Languages: {languages}",
        f"Topics: {', '.join(repo.topics) if repo.topics else 'none'}",
    ])


def _commit_snapshot(repo: RepoFacts) -> str:
    dates = sorted(repo.user_commit_dates)
    share = "unknown" if repo.authored_share is None else f"{repo.authored_share:.2f}"
    return "\n".join([
        f"Commits sampled: {repo.commits_sampled}",
        f"Commits by the account: {repo.user_commits}",
        f"Authored share: {share} (source: {'contributor statistics' if repo.share_source == 'stats' else 'latest commits'})",
        f"First commit by the account: {_day(dates[0]) if dates else 'none'}",
        f"Last commit by the account: {_day(dates[-1]) if dates else 'none'}",
        f"Commits with the account's name but an unlinked e-mail address: {repo.unlinked_commits}",
    ])


def _profile_snapshot(facts: AccountFacts, provider: str) -> str:
    profile = facts.profile
    return "\n".join([
        f"Provider: {provider}",
        f"Account: {profile.login}",
        f"Name: {profile.name or 'not given'}",
        f"Bio: {profile.bio or 'not given'}",
        f"Created: {_day(profile.created_at)}",
        f"URL: {profile.url or 'unknown'}",
    ])


def _add(target: dict[str, _Shown], name: str, key: str, quote: str) -> None:
    shown = target.setdefault(name, _Shown())
    if key not in shown.keys:
        shown.keys.append(key)
    shown.quotes.append((key, quote))


def _analyse(repo: _Repo) -> None:
    facts = repo.facts
    for language, share in facts.languages.items():
        if share < LANGUAGE_MIN_SHARE:
            continue
        quote = f"{language} {_pct(share)}"
        _add(repo.raw_names, language.lower(), repo.key, quote)
        skill = skill_for_language(language)
        if skill:
            _add(repo.skills, skill.id, repo.key, quote)
    for topic in facts.topics:
        _add(repo.raw_names, topic.lower(), repo.key, topic)
        skill = skill_for_topic(topic)
        if skill:
            _add(repo.skills, skill.id, repo.key, topic)
    for path, text in facts.manifests.items():
        key = repo.manifest_keys[path]
        for dependency in parse_manifest(path, text):
            pattern = re.compile(r"(?<![A-Za-z0-9_.@/-])" + re.escape(dependency) + r"(?![A-Za-z0-9_-])", re.I)
            match = next((line.strip() for line in text.split("\n") if pattern.search(line)), dependency)
            quote = match if len(match) <= 200 else dependency
            _add(repo.raw_names, dependency.lower(), key, quote)
            skill = skill_for_dependency(dependency)
            if skill:
                _add(repo.skills, skill.id, key, quote)


def _strong(repo: _Repo) -> bool:
    share = repo.facts.authored_share
    return share is not None and share >= STRONG_SHARE and repo.facts.user_commits > 0 and not repo.bulk


def _skill_verdict(claim: ClaimIn, repos: list[_Repo]) -> DraftVerdict:
    skill: SkillRef | None = resolve_claim_skill(claim.value)
    showing: list[tuple[_Repo, _Shown]] = []
    for repo in repos:
        if skill is not None:
            matches = [shown for skill_id, shown in repo.skills.items() if covers(skill.id, skill_id)]
        else:
            matches = [shown for name, shown in repo.raw_names.items() if name == claim.value.strip().lower()]
        if matches:
            merged = _Shown()
            for shown in matches:
                for key, quote in shown.quotes:
                    _add({"x": merged}, "x", key, quote)
            showing.append((repo, merged))
    label = skill.name if skill else claim.value
    if not showing:
        return DraftVerdict(claim, "NOT_FOUND", [Reason(
            text=f"No repository considered for the account shows {label} in its languages, dependencies or topics.",
            evidence_keys=[], quote=None)])
    names = ", ".join(repo_label(repo.index - 1) for repo, _ in showing)
    first_key, first_quote = showing[0][1].quotes[0]
    reasons = [Reason(text=f"{len(showing)} repositor{'y' if len(showing) == 1 else 'ies'} of the account "
                           f"show{'s' if len(showing) == 1 else ''} {label}: {names}.",
                      evidence_keys=list(dict.fromkeys(key for _, shown in showing for key in shown.keys)),
                      quote=first_quote)]
    strong = [repo for repo, _ in showing if _strong(repo)]
    dates = [moment for repo in strong for moment in repo.facts.user_commit_dates]
    span = (max(dates) - min(dates)).days if dates else 0
    commit_keys = [repo.commits_key for repo in strong]
    if len(strong) >= 2 and span >= SPAN_DAYS:
        reasons.append(Reason(
            text=f"In {len(strong)} of them the account authored at least half of the commits, and those commits "
                 f"span {span} days.", evidence_keys=commit_keys, quote="Commits by the account"))
        return DraftVerdict(claim, "VERIFIED", reasons)
    if len(strong) < 2:
        detail = (f"Only {len(strong)} of them {'has' if len(strong) == 1 else 'have'} the account as the author of "
                  f"at least half of the commits (two are needed).")
    else:
        detail = f"The account's commits in them span {span} days (30 are needed)."
    reasons.append(Reason(text=detail, evidence_keys=commit_keys or [repo.commits_key for repo, _ in showing],
                          quote=None))
    return DraftVerdict(claim, "PARTLY_VERIFIED", reasons)


def build_output(request: CodeVerifyRequest, facts: AccountFacts, *, now: datetime, budget: Budget,
                 demo: bool = False) -> VerifierOutput:
    """Turn collected account facts into evidence, draft verdicts, skills and integrity notes."""
    attributes = {"demoFixture": True, "synthetic": True} if demo else {}
    evidence: list[EvidenceItem] = [make_item(
        "profile", "PROFILE", url=facts.profile.url, title=f"{request.provider} account",
        text=_profile_snapshot(facts, request.provider), retrieved_at=now,
        attributes={"provider": request.provider, **attributes})]
    repos: list[_Repo] = []
    notes: list[IntegrityNote] = []
    for index, repo in enumerate(facts.repos, start=1):
        item = _Repo(index=index, facts=repo, key=f"repo-{index}", commits_key=f"commits-{index}",
                     readme_key=f"readme-{index}" if repo.readme else None,
                     manifest_keys={path: f"manifest-{index}-{number}"
                                    for number, path in enumerate(repo.manifests, start=1)})
        item.bulk = is_bulk_history(repo, now)
        _analyse(item)
        repos.append(item)
        evidence.append(make_item(item.key, "REPOSITORY", url=repo.url, title=repo.full_name, text=_repo_snapshot(repo),
                                  retrieved_at=now, attributes={"fork": repo.is_fork, "partial": repo.partial,
                                                                "skills": sorted(item.skills), **attributes}))
        dates = sorted(repo.user_commit_dates)
        evidence.append(make_item(item.commits_key, "COMMIT_SUMMARY", url=repo.url, title=f"Commits in {repo.full_name}",
                                  text=_commit_snapshot(repo), retrieved_at=now, attributes={
                                      "userCommits": repo.user_commits, "commitsSampled": repo.commits_sampled,
                                      "authoredShare": repo.authored_share, "shareSource": repo.share_source,
                                      "firstCommitAt": dates[0].isoformat() if dates else None,
                                      "lastCommitAt": dates[-1].isoformat() if dates else None, **attributes}))
        for path, key in item.manifest_keys.items():
            evidence.append(make_item(key, "MANIFEST", url=repo.url, title=f"{path} in {repo.full_name}",
                                      text=repo.manifests[path], retrieved_at=now,
                                      attributes={"path": path, "dependencies": parse_manifest(path, repo.manifests[path])[:100],
                                                  **attributes}))
        if item.readme_key and repo.readme:
            evidence.append(make_item(item.readme_key, "README", url=repo.url, title=f"README of {repo.full_name}",
                                      text=repo.readme, retrieved_at=now,
                                      excerpt=repo.readme[:README_EXCERPT], attributes={"path": repo.readme_path,
                                                                                        **attributes}))
        if item.bulk:
            notes.append(IntegrityNote(code="BULK_HISTORY", evidence_keys=[item.key, item.commits_key], text=(
                f"All of the account's commits in {repo_label(index - 1)} fall on one day and the repository was created less "
                "than 14 days ago; it does not count towards a verified result.")))
        if repo.unlinked_commits:
            notes.append(IntegrityNote(code="UNLINKED_AUTHOR", evidence_keys=[item.commits_key], text=(
                f"{repo.unlinked_commits} commit(s) in {repo_label(index - 1)} carry the account's name but an e-mail address "
                "that is not linked to the account; they were not counted.")))
        if item.readme_key and is_template_readme(repo.readme):
            notes.append(IntegrityNote(code="TEMPLATE_README", evidence_keys=[item.readme_key], text=(
                f"The README of {repo_label(index - 1)} matches a known project template.")))
    if facts.skipped_forks:
        notes.append(IntegrityNote(code="FORK_ONLY", evidence_keys=["profile"], text=(
            f"{len(facts.skipped_forks)} forked repositor{'y' if len(facts.skipped_forks) == 1 else 'ies'} without the "
            "account's own commits were ignored.")))
    drafts = []
    for claim in request.claims:
        if claim.type != "SKILL":
            drafts.append(DraftVerdict(claim, "NOT_CHECKABLE", [Reason(
                text=f"Code repositories cannot confirm {_article(claim.type.lower())} {claim.type.lower()} claim.", evidence_keys=[], quote=None)]))
        else:
            drafts.append(_skill_verdict(claim, repos))
    skills = _skills(repos)
    profile = facts.profile
    identities = Identities(logins=[profile.login, request.login], names=[profile.name] if profile.name else [],
                            repos=[repo.facts.full_name for repo in repos] + facts.skipped_forks)
    warnings = list(facts.warnings) + (["DEMO_FIXTURES: synthetic recorded data"] if demo else [])
    if facts.truncated:
        warnings.append("BUDGET_TRUNCATED")
    return VerifierOutput(verifier="code", evidence=evidence, drafts=drafts, ownership_proven=request.ownership_proven,
                          identities=identities, skills=skills, notes=notes, warnings=warnings,
                          usage=Usage(requests=budget.requests, llm_calls=0, truncated=facts.truncated or budget.truncated))


def _skills(repos: list[_Repo]) -> list[SkillEvidence]:
    from app.taxonomy.taxonomy import get_taxonomy

    taxonomy = get_taxonomy()
    collected: dict[str, tuple[list[str], set[int]]] = {}
    for repo in repos:
        for skill_id, shown in repo.skills.items():
            keys, indexes = collected.setdefault(skill_id, ([], set()))
            keys.extend(key for key in shown.keys if key not in keys)
            indexes.add(repo.index)
    result = []
    for skill_id, (keys, indexes) in sorted(collected.items(), key=lambda pair: (-len(pair[1][1]), pair[0])):
        skill = taxonomy.by_id(skill_id)
        result.append(SkillEvidence(skill_id=skill_id, name=skill.name if skill else skill_id, evidence_keys=keys,
                                    repo_count=len(indexes)))
    return result


def verify_code(request: CodeVerifyRequest, *, settings: Settings, network: Network, now: datetime) -> VerifierOutput:
    budget = Budget(request.limits.max_requests, settings.request_deadline_seconds)
    source_cls = GitHubSource if request.provider == "github" else GitLabSource
    source = source_cls(settings, budget, request.access_token, transport=network.api_transport)
    facts = source.collect(request.login, request.limits.max_repos)
    return build_output(request, facts, now=now, budget=budget, demo=network.demo)
