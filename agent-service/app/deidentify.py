"""De-identification of reason, note, probe and summary texts (blind-first review).

Before reveal, staff see verdict reasons but not evidence, so reason texts must not name the candidate. Replacements:

* URLs (``http(s)://...``, ``www....``) and known site hosts -> "the site"
* e-mail addresses -> "the email address"
* repository names (``owner/repo`` or bare) -> "repository A", "repository B", ... in the order given; a bare
  name that is itself a taxonomy skill ("react", "tensorflow") is left alone, because it identifies nobody and
  replacing it would garble skill names
* account logins (``@login`` or bare) -> "the account"
* person names (display name, holder name; the full name and each part of 3+ letters) -> "the candidate"
  (a single name part or bare login that is also a skill name, e.g. "Ruby", is left alone for the same reason)
* organisation names given by the verifier (e.g. an employer) -> "the organisation"

Matching is case-insensitive on word boundaries; longer identifiers are replaced first.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

_URL = re.compile(r"\b(?:https?://|www\.)[^\s<>\"')\]]+", re.I)
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_STOP_NAME_PARTS = {"the", "and", "van", "von", "der", "den", "del", "mr", "mrs", "ms", "dr"}


def _is_skill_name(name: str) -> bool:
    from app.taxonomy.taxonomy import get_taxonomy

    return get_taxonomy().lookup(name) is not None or get_taxonomy().lookup(name.replace("-", " ")) is not None


def repo_label(index: int) -> str:
    """0 -> "repository A", 25 -> "repository Z", 26 -> "repository AA"."""
    letters = ""
    index += 1
    while index:
        index, remainder = divmod(index - 1, 26)
        letters = chr(65 + remainder) + letters
    return f"repository {letters}"


@dataclass
class Identities:
    logins: list[str] = field(default_factory=list)
    names: list[str] = field(default_factory=list)
    repos: list[str] = field(default_factory=list)
    hosts: list[str] = field(default_factory=list)
    organisations: list[str] = field(default_factory=list)

    def replacements(self) -> list[tuple[str, str]]:
        pairs: list[tuple[str, str]] = []
        for index, repo in enumerate(self.repos):
            label = repo_label(index)
            if "/" in repo:
                pairs.append((repo, label))
            bare = repo.rsplit("/", 1)[-1]
            if not _is_skill_name(bare):
                pairs.append((bare, label))
        pairs += [(host, "the site") for host in self.hosts]
        pairs += [(organisation, "the organisation") for organisation in self.organisations]
        for name in self.names:
            pairs.append((name, "the candidate"))
            pairs += [(part, "the candidate") for part in re.split(r"[\s,]+", name)
                      if len(part) >= 3 and part.lower() not in _STOP_NAME_PARTS and not _is_skill_name(part)]
        for login in self.logins:
            pairs.append(("@" + login, "the account"))
            if not _is_skill_name(login):
                pairs.append((login, "the account"))
        cleaned = [(source.strip(), target) for source, target in pairs if source and len(source.strip()) >= 2]
        return sorted(cleaned, key=lambda pair: len(pair[0]), reverse=True)


def deidentify(text: str | None, identities: Identities) -> str | None:
    if text is None:
        return None
    result = _URL.sub("the site", text)
    result = _EMAIL.sub("the email address", result)
    for source, target in identities.replacements():
        pattern = re.compile(r"(?<![A-Za-z0-9_])" + re.escape(source) + r"(?![A-Za-z0-9_])", re.I)
        result = pattern.sub(target, result)
    return result
