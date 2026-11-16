"""De-identification of reason, note, probe and summary texts (blind-first review).

Before reveal, staff see verdict reasons but not evidence, so reason texts must not name the candidate. Replacements:

* URLs (``http(s)://...``, ``www....``) and known site hosts -> "the site"
* e-mail addresses -> "the email address"
* phone numbers (``+`` and 8+ digits, or 10-15 digits in one number) -> "the phone number"
* repository names (``owner/repo`` or bare) -> "repository A", "repository B", ... in the order given; a bare
  name that is itself a taxonomy skill ("react", "tensorflow") is left alone, because it identifies nobody and
  replacing it would garble skill names
* account logins (``@login`` or bare) -> "the account"
* person names (display name, holder name; the full name and each part of 3+ letters) -> "the candidate"
  (a single name part or bare login that is also a skill name, e.g. "Ruby", is left alone for the same reason)
* organisation names given by the verifier (e.g. an employer) -> "the organisation"

Matching is case-insensitive on word boundaries and done in one pass (longer identifiers win; a replacement is never
replaced again). Two context rules keep single name parts from garbling other words, while the full name is always
replaced:

* a name part directly followed by an organisation word ("Rao Institute", "Shah Labs", "Patel & Co") is part of an
  organisation's name and is kept;
* a name part that is also a common English word ("Page", "Mark", "Will", "Rose") is replaced only where it is
  capitalised, so "stated on a page of the site" stays readable for a candidate called Page.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

_URL = r"\b(?:https?://|www\.)[^\s<>\"')\]]+"
_EMAIL = r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
_PHONE = r"(?<![\w+])\+?\(?\d[\d ().-]{6,}\d(?![\w])"
_STOP_NAME_PARTS = {"the", "and", "van", "von", "der", "den", "del", "mr", "mrs", "ms", "dr"}
# Capitalised words that make a preceding name part part of an organisation's name.
_ORG_WORDS = (
    "Institute", "Institution", "University", "College", "School", "Academy", "Polytechnic", "Labs", "Lab",
    "Laboratory", "Laboratories", "Inc", "Incorporated", "Ltd", "Limited", "LLC", "LLP", "PLC", "Pvt", "Private",
    "Corp", "Corporation", "Company", "Co", "Group", "Foundation", "Trust", "Society", "Association", "Associates",
    "Partners", "Consulting", "Consultants", "Technologies", "Technology", "Tech", "Systems", "Solutions", "Software",
    "Industries", "Enterprises", "Holdings", "Ventures", "Capital", "Bank", "Hospital", "Clinic", "Studios", "Studio",
    "Agency", "Media", "Press", "Publishing", "Center", "Centre", "Museum", "Library", "Sons", "Brothers", "Bros",
    "GmbH", "AG", "SA", "BV", "Pte", "Pty", "Memorial", "Prize", "Award", "Scholarship", "Fellowship", "Hall",
    "Road", "Street", "Nagar", "Marg",
)
_ORG_CONTEXT = re.compile(r"(?:'s)?\s+(?:&\s+|and\s+)?(?:" + "|".join(_ORG_WORDS) + r")\b\.?")
# Name parts that are also everyday English words: only a capitalised occurrence is taken as the name.
_COMMON_WORDS = frozenset("""
page mark case price rich young long short little bond key will grant hope faith joy may june april august bill dean
rose lily ivy daisy dawn sky rain summer autumn winter hunter baker cook walker king bell hall wood stone field brown
white green black gray grey love art guy pat sue ray rob jack frank eve noble wise sage major chase miles hill ford
marsh moon star sun gold silver rice lane park north west south banks waters fox wolf lamb bird swift cross church
mill miller hunt lord duke prince rule law best good small strong sharp bright smart sweet free story reason domain
site work rank part note claim post read able early fair hale hardy hart head holmes key knight lee light low man
mason more moore new page penny pound power reading reed rush salt sands short snow spring stout street summers
tanner taylor turner vale wall ward watt webb wells wing worth wright cash amber crystal hazel heather iris jade
pearl ruby violet coral olive basil cliff glen dale forest river ocean storm sunny honey candy angel bishop cardinal
pope saint baron earl squire carter cooper fisher fletcher gardner glass harper potter sawyer shepherd slater spencer
""".split())


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


@dataclass(frozen=True)
class _Rule:
    source: str
    target: str
    part: bool = False  # a single part of a person's name (context rules apply)


@dataclass
class Identities:
    logins: list[str] = field(default_factory=list)
    names: list[str] = field(default_factory=list)
    repos: list[str] = field(default_factory=list)
    hosts: list[str] = field(default_factory=list)
    organisations: list[str] = field(default_factory=list)

    def rules(self) -> list[_Rule]:
        rules: list[_Rule] = []
        for index, repo in enumerate(self.repos):
            label = repo_label(index)
            if "/" in repo:
                rules.append(_Rule(repo, label))
            bare = repo.rsplit("/", 1)[-1]
            if not _is_skill_name(bare):
                rules.append(_Rule(bare, label))
        rules += [_Rule(host, "the site") for host in self.hosts]
        rules += [_Rule(organisation, "the organisation") for organisation in self.organisations]
        for name in self.names:
            rules.append(_Rule(name, "the candidate"))
            parts = [part for part in re.split(r"[\s,]+", name.strip()) if part]
            rules += [_Rule(part, "the candidate", part=len(parts) > 1) for part in parts
                      if len(part) >= 3 and part.lower() not in _STOP_NAME_PARTS and not _is_skill_name(part)]
        for login in self.logins:
            rules.append(_Rule("@" + login, "the account"))
            if not _is_skill_name(login):
                rules.append(_Rule(login, "the account"))
        cleaned = [_Rule(rule.source.strip(), rule.target, rule.part) for rule in rules
                   if rule.source and len(rule.source.strip()) >= 2]
        return sorted(cleaned, key=lambda rule: len(rule.source), reverse=True)

    def replacements(self) -> list[tuple[str, str]]:
        return [(rule.source, rule.target) for rule in self.rules()]


def _keep_name_part(rule: _Rule, matched: str, text: str, end: int) -> bool:
    if rule.source.lower() in _COMMON_WORDS and not matched[:1].isupper():
        return True
    return bool(_ORG_CONTEXT.match(text, end))


def deidentify(text: str | None, identities: Identities) -> str | None:
    if text is None:
        return None
    rules = identities.rules()
    by_source: dict[str, _Rule] = {}
    for rule in rules:
        by_source.setdefault(rule.source.lower(), rule)  # equal sources: the first given wins (as before)
    alternatives = [f"(?P<url>{_URL})", f"(?P<email>{_EMAIL})"]
    if by_source:
        alternatives.append(r"(?P<id>(?<![A-Za-z0-9_])(?:" + "|".join(re.escape(rule.source) for rule in rules)
                            + r")(?![A-Za-z0-9_]))")
    alternatives.append(f"(?P<phone>{_PHONE})")
    pattern = re.compile("|".join(alternatives), re.I)

    def replace(match: re.Match[str]) -> str:
        if match.group("url") is not None:
            return "the site"
        if match.group("email") is not None:
            return "the email address"
        if match.lastgroup == "phone":
            value = match.group("phone")
            digits = sum(char.isdigit() for char in value)
            groups = re.findall(r"\d+", value)
            if all(len(group) == 4 and group[:2] in {"19", "20"} for group in groups):
                return value  # a run of years ("2019 2020 2021"), not a phone number
            if (value.startswith("+") and digits >= 8) or 10 <= digits <= 15:
                return "the phone number"
            return value
        matched = match.group("id")
        rule = by_source[matched.lower()]
        if rule.part and _keep_name_part(rule, matched, match.string, match.end()):
            return matched
        return rule.target

    return pattern.sub(replace, text)
