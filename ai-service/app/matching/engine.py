"""Requirement matching v4 - ``evidence-ladder-4`` (plan Section 6.1 / 6.2 item 8).

For every requirement the engine decides MATCHED / UNCLEAR / NOT_OBSERVED from typed facts and
the experience timeline, and reports the *tier* of the weakest evidence needed for the match:
it re-evaluates with only facts at or above each tier (INTERVIEW_VERIFIED down to SELF_CLAIMED)
and reports the highest tier at which the requirement is still MATCHED. Coverage then splits into

* ``verifiedCoverage`` - weighted share of requirements MATCHED at tier >= RESUME_STATED (the score);
* ``claimedCoverage``  - weighted share MATCHED at any tier, candidate claims included.

Weights: must-have 2, nice-to-have 1. OTHER requirements need human review: always NOT_OBSERVED
and excluded from the denominator. Candidate edits arrive as separate ``claim:*`` facts at
SELF_CLAIMED, so they raise claimed coverage only (finding C1). Work-sample (``ws:N:rM``) and
interview (``iv:N:rM``) facts are linked to requirement M and satisfy it directly.

v4 adds requirement *proficiency* for SKILL requirements (conservative depth rule): BASIC and
INTERMEDIATE are unchanged; an ADVANCED match also needs >= 3 years of relevant timeline evidence
for every named skill, or tier >= CORROBORATED, or an explicit level word (advanced, expert,
senior, lead) in the cited evidence; EXPERT needs >= 5 years, or tier >= DEMONSTRATED, or the word
"expert". Missing depth turns MATCHED into UNCLEAR with an explanation of what was missing; it
never produces NOT_OBSERVED.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from functools import lru_cache

from app.matching.certifications import certification_tokens, split_certification_options
from app.models.common import TIER_ORDER, tier_rank
from app.models.matching import (
    BatchMatchRequest, BatchMatchResponse, CandidateMatch, MatchFact, MatchRequest, MatchRequirement, MatchResponse,
    RequirementResult,
)
from app.models.resume import Timeline
from app.pipeline.dates import find_duration_months, merged_months
from app.pipeline.education import LEVELS, EducationInfo, degree_levels, fields_related, parse_education
from app.pipeline.text import content_tokens, stem
from app.taxonomy.taxonomy import Skill, get_taxonomy

ALGORITHM_VERSION = "evidence-ladder-4"
SCORE_LABEL = "Verified requirement evidence coverage (not a probability of success)"
RESUME_RANK = tier_rank("RESUME_STATED")
_STATUS_RANK = {"NOT_OBSERVED": 0, "UNCLEAR": 1, "MATCHED": 2}
_LINKED_RE = re.compile(r"^(?:ws|iv):\d+:r(\d+)$")
_YEARS_PHRASE_RE = re.compile(r"\b(?:minimum|min\.?|at\s+least|over)?\s*\d{1,2}(?:\.\d)?\s*\+?\s*(?:-|to)?\s*(?:\d{1,2})?\s*\+?\s*(?:years?|yrs?)\b(?:\s+of)?", re.I)
_GENERIC_TOPIC = frozenset(content_tokens(
    "background track record proven demonstrated role roles position domain area total overall combined post "
    "qualification hands-on practical prior previous similar capacity environment setting"
))
_PROVENANCE_BY_TIER = {
    "SELF_CLAIMED": "CANDIDATE_CLAIM", "RESUME_STATED": "RESUME", "CORROBORATED": "CORROBORATED",
    "DEMONSTRATED": "WORK_SAMPLE", "INTERVIEW_VERIFIED": "INTERVIEW",
}
_SOURCE_LABEL = {
    "RESUME": "Resume evidence", "CANDIDATE_CLAIM": "The candidate's own claim (not yet verified)",
    "CORROBORATED": "Corroborated evidence", "WORK_SAMPLE": "Work-sample evidence", "INTERVIEW": "Interview evidence",
}
_EVIDENCE_PREFIX = {"CANDIDATE_CLAIM": "Candidate claim: ", "WORK_SAMPLE": "Work sample: ", "INTERVIEW": "Interview: "}


# ------------------------------------------------------------------------------ fact preparation
@dataclass(frozen=True, eq=False)
class _Fact:
    raw: MatchFact
    rank: int
    origin: str  # fact | claim | ws | iv | other
    linked_requirement: int | None
    skills: frozenset[str]
    value_tokens: frozenset[str]
    text_tokens: frozenset[str]
    duration: int | None
    education: EducationInfo | None

    @property
    def provenance(self) -> str:
        if self.origin == "ws":
            return "WORK_SAMPLE"
        if self.origin == "iv":
            return "INTERVIEW"
        return _PROVENANCE_BY_TIER[self.raw.tier]


@dataclass(frozen=True, eq=False)
class _Role:
    fact: _Fact | None
    rank: int
    interval: tuple[int, int] | None
    months: int | None
    skills: frozenset[str]
    tokens: frozenset[str]
    label: str
    head: str | None  # stemmed last word of the title: "Mathematics Teacher" -> "teach"


@dataclass(frozen=True)
class _Context:
    facts: tuple[_Fact, ...]
    roles: tuple[_Role, ...]
    total_months: int | None

    def of_type(self, *types: str) -> list[_Fact]:
        return [fact for fact in self.facts if fact.raw.type in types]


@dataclass
class _Outcome:
    status: str
    facts: list[_Fact] = field(default_factory=list)
    explanation: str = ""
    synthetic_evidence: str | None = None  # timeline-only evidence without a fact to cite
    hint: str | None = None


def _origin(fact_id: str) -> str:
    prefix = fact_id.split(":", 1)[0]
    return prefix if prefix in {"fact", "claim", "ws", "iv"} else "other"


def _prepare(fact: MatchFact) -> _Fact:
    taxonomy = get_taxonomy()
    attributes = fact.attributes or {}
    text = f"{fact.value}\n{fact.evidence}" if fact.evidence and fact.evidence != fact.value else fact.value
    if fact.type == "SKILL":
        known = taxonomy.by_id(str(attributes.get("skillId", "")))
        skills = {known.id} if known else {mention.skill.id for mention in taxonomy.find_mentions(fact.value, list_context=True)}
    else:
        skills = {mention.skill.id for mention in taxonomy.find_mentions(text)}
    education = None
    if fact.type == "EDUCATION":
        education = parse_education(text)
        level = attributes.get("degreeLevel")
        if isinstance(level, str) and level in LEVELS and education.level != level:
            education = EducationInfo(level, education.fields, education.field_label, education.any_field)
    duration = find_duration_months(text) if fact.type == "EXPERIENCE" else None
    linked = _LINKED_RE.match(fact.id)
    return _Fact(fact, tier_rank(fact.tier), _origin(fact.id), int(linked.group(1)) if linked else None,
                 frozenset(skills), frozenset(content_tokens(fact.value)), frozenset(content_tokens(text)),
                 duration[0] if duration else None, education)


def _ordinal(value: object, *, is_end: bool) -> int | None:
    if not isinstance(value, str):
        return None
    match = re.fullmatch(r"(\d{4})(?:-(\d{2}))?", value.strip())
    if not match:
        return None
    month = int(match.group(2)) if match.group(2) else (6 if is_end else 7)
    return int(match.group(1)) * 12 + month - 1


def _interval(start: object, end: object, current: bool, months: int | None, today: date) -> tuple[int, int] | None:
    first = _ordinal(start, is_end=False)
    if first is None:
        return None
    if months:
        return first, first + months - 1
    last = (today.year * 12 + today.month - 1) if current else _ordinal(end, is_end=True)
    return (first, last) if last is not None and last >= first else None


def _build_roles(facts: list[_Fact], timeline: Timeline | None, today: date) -> list[_Role]:
    taxonomy = get_taxonomy()
    roles: list[_Role] = []
    used_timeline: set[int] = set()
    timeline_roles = list(timeline.roles) if timeline else []
    for fact in facts:
        attributes = fact.raw.attributes or {}
        if fact.raw.type != "EXPERIENCE" or fact.origin != "fact" or not ("months" in attributes or "startDate" in attributes):
            continue
        months = attributes.get("months") if isinstance(attributes.get("months"), int) else None
        interval = _interval(attributes.get("startDate"), attributes.get("endDate"), bool(attributes.get("current")), months, today)
        skills = set(fact.skills)
        for index, role in enumerate(timeline_roles):
            if index not in used_timeline and role.startDate == attributes.get("startDate") and role.months == months \
                    and (role.title or None) == (attributes.get("title") or None):
                used_timeline.add(index)
                skills.update(skill.id for name in role.skills if (skill := taxonomy.lookup(name)))
                break
        roles.append(_Role(fact, fact.rank, interval, months if months else (interval[1] - interval[0] + 1 if interval else None),
                           frozenset(skills), fact.text_tokens, fact.raw.value, _head(attributes.get("title"))))
    for index, role in enumerate(timeline_roles):
        if index in used_timeline:
            continue
        skills = frozenset(skill.id for name in role.skills if (skill := taxonomy.lookup(name)))
        label = ", ".join(part for part in (role.title, role.employer) if part) or "role"
        tokens = frozenset(content_tokens(" ".join([role.title or "", role.employer or "", *role.skills])))
        roles.append(_Role(None, RESUME_RANK, _interval(role.startDate, role.endDate, role.current, role.months, today),
                           role.months, skills, tokens, label, _head(role.title)))
    return roles


def _head(title: object) -> str | None:
    words = re.findall(r"[a-z0-9]+", title.lower()) if isinstance(title, str) else []
    return stem(words[-1]) if words else None


def _role_months(roles: list[_Role]) -> int:
    return merged_months([role.interval for role in roles if role.interval]) + sum(
        role.months or 0 for role in roles if role.interval is None)


# ------------------------------------------------------------------------------------- helpers
@lru_cache(maxsize=2048)
def skill_cnf(text: str) -> tuple[tuple[Skill, ...], ...]:
    """Skills named in a requirement as AND-of-OR groups: "Java, Spring and AWS or Azure" ->
    ((Java,), (Spring,), (AWS, Azure)). "X or Y", "X/Y" and "such as X, Y" are alternatives."""
    mentions = get_taxonomy().find_mentions(text, list_context=True)
    if not mentions:
        return ()
    connectors: list[str] = []
    for previous, current in zip(mentions, mentions[1:]):
        between = text[previous.end:current.start].lower().strip()
        if between in {"/", "|"} or re.search(r"\b(?:or|either|alternatively|such as|e\.g|eg|for example|like|including|incl)\b", between):
            connectors.append("OR")
        elif re.fullmatch(r"[,;\s]*", between):
            connectors.append("COMMA")
        else:
            connectors.append("AND")
    resolved: list[str] = []
    for index, connector in enumerate(connectors):
        if connector == "COMMA":
            following = next((item for item in connectors[index + 1:] if item != "COMMA"), "AND")
            connector = following
        resolved.append(connector)
    groups: list[list[Skill]] = [[mentions[0].skill]]
    for connector, mention in zip(resolved, mentions[1:]):
        if connector == "OR":
            groups[-1].append(mention.skill)
        else:
            groups.append([mention.skill])
    unique: list[tuple[Skill, ...]] = []
    for group in groups:
        members = tuple(dict.fromkeys(group))
        if members not in unique:
            unique.append(members)
    return tuple(unique)


def _covers(group: tuple[Skill, ...], skills: frozenset[str]) -> tuple[str, Skill, str] | None:
    """How a skill set satisfies an OR-group: ("direct"|"child", required skill, held skill id)."""
    taxonomy = get_taxonomy()
    for skill in group:
        if skill.id in skills:
            return "direct", skill, skill.id
    for skill in group:
        for held in sorted(skills):
            if taxonomy.is_ancestor(skill.id, held):
                return "child", skill, held
    return None


def _broader(group: tuple[Skill, ...], skills: frozenset[str]) -> tuple[Skill, str] | None:
    """A held skill that is only an ancestor of the required one (SQL held, MySQL required)."""
    taxonomy = get_taxonomy()
    for skill in group:
        for held in sorted(skills):
            if taxonomy.is_ancestor(held, skill.id):
                return skill, held
    return None


def _name(skill_id: str) -> str:
    skill = get_taxonomy().by_id(skill_id)
    return skill.name if skill else skill_id


def _source(facts: list[_Fact]) -> str:
    if not facts:
        return "Resume timeline"
    best = max(facts, key=lambda fact: fact.rank)
    return _SOURCE_LABEL[best.provenance]


def _years(months: int) -> str:
    years = months / 12
    text = f"{years:.1f}".rstrip("0").rstrip(".")
    return f"{text} year" if text == "1" else f"{text} years"


def _best(facts: list[_Fact]) -> list[_Fact]:
    """Facts ordered by tier (highest first), SKILL facts before others, then input order."""
    return sorted(facts, key=lambda fact: (-fact.rank, fact.raw.type != "SKILL"))


# ------------------------------------------------------------------------------- SKILL / lexical
def _eval_skill(text: str, ctx: _Context) -> _Outcome:
    groups = skill_cnf(text)
    if not groups:
        return _lexical(text, ctx.of_type("SKILL"), "skill")
    candidates = _best([fact for fact in ctx.facts if fact.raw.type in {"SKILL", "EXPERIENCE", "CERTIFICATION", "EDUCATION", "OTHER"}])
    cited: list[_Fact] = []
    found: list[str] = []
    partial: list[str] = []
    missing: list[str] = []
    for group in groups:
        names = " or ".join(skill.name for skill in group)
        hit = next(((fact, cover) for fact in candidates if (cover := _covers(group, fact.skills)) and cover[0] == "direct"), None) \
            or next(((fact, cover) for fact in candidates if (cover := _covers(group, fact.skills))), None)
        if hit:
            fact, (kind, required, held) = hit
            cited.append(fact)
            found.append(required.name if kind == "direct" else f"{_name(held)} (a specialisation of {required.name})")
            continue
        broader = next(((fact, info) for fact in candidates if (info := _broader(group, fact.skills))), None)
        if broader:
            fact, (required, held) = broader
            cited.append(fact)
            partial.append(f"{_name(held)} (broader than {required.name})")
        missing.append(names)
    cited = list(dict.fromkeys(cited))
    source = _source(cited)
    if not missing:
        return _Outcome("MATCHED", cited, f"{source} shows {', '.join(found)}.")
    if found or partial:
        shown = ", ".join(found + partial)
        return _Outcome("UNCLEAR", cited, f"{source} shows {shown}; not established: {', '.join(missing)}.",
                        hint=f"Probe {', '.join(missing)} specifically; the evidence only covers {shown}.")
    return _Outcome("NOT_OBSERVED", [], f"No evidence of {', '.join(missing)} was found in the extracted information; "
                                        "this does not mean the candidate lacks it.")


def _lexical(text: str, facts: list[_Fact], noun: str) -> _Outcome:
    """Token-overlap fallback restricted to facts of the requirement's own type (finding L12).

    A fact whose *value* holds the terms can match; terms found only in another fact's evidence
    line are reported as UNCLEAR and cite that line honestly instead of the unrelated fact's value."""
    tokens = content_tokens(text)
    if not tokens:
        return _Outcome("NOT_OBSERVED", [], "The requirement has no comparable terms; it needs human review.")
    by_value = max(_best(facts), key=lambda fact: len(tokens & fact.value_tokens), default=None)
    value_ratio = len(tokens & by_value.value_tokens) / len(tokens) if by_value else 0.0
    if by_value and value_ratio >= 0.67:
        return _Outcome("MATCHED", [by_value], f"{_source([by_value])} names '{by_value.raw.value[:80]}', "
                                               f"which contains the requirement's terms.")
    by_text = max(_best(facts), key=lambda fact: len(tokens & fact.text_tokens), default=None)
    text_ratio = len(tokens & by_text.text_tokens) / len(tokens) if by_text else 0.0
    if by_text and text_ratio >= 0.67:
        return _Outcome("UNCLEAR", [by_text], f"The requirement's terms appear in the resume line cited, but not as an "
                                              f"extracted {noun}; check the context.")
    if by_value and value_ratio > 0:
        return _Outcome("UNCLEAR", [by_value], "Some comparable terms were found, but the requirement is not fully verified.")
    return _Outcome("NOT_OBSERVED", [], f"No comparable {noun} terms were found in the extracted information; "
                                        "this does not mean the candidate lacks the requirement.")


# ----------------------------------------------------------------------------------- EXPERIENCE
def _relevant(group: tuple[Skill, ...], skills: frozenset[str], tokens: frozenset[str], head: str | None = None) -> bool:
    """Evidence is relevant to an OR-group when it names a member (or a specialisation), contains every
    word of a multi-word member ("B2B SaaS sales" for B2B Sales), or its title names the practitioner
    of a one-word member ("Recruiter" for Recruitment, "Mathematics Teacher" for Teaching)."""
    if _covers(group, skills):
        return True
    for skill in group:
        name_tokens = content_tokens(skill.name)
        if len(name_tokens) >= 2 and name_tokens <= tokens:
            return True
        if len(name_tokens) == 1 and head is not None and name_tokens == {head}:
            return True
    return False


def _eval_experience(text: str, minimum_years: int | None, ctx: _Context) -> _Outcome:
    stated = find_duration_months(text)
    need = minimum_years * 12 if minimum_years else (stated[0] if stated else None)
    subject = " ".join(_YEARS_PHRASE_RE.sub(" ", text).split()) or text
    groups = skill_cnf(subject)
    # The topic is what the requirement says beyond the skills it names ("enterprise ... services").
    remainder = subject
    for mention in reversed(get_taxonomy().find_mentions(subject, list_context=True)):
        remainder = remainder[:mention.start] + " " + remainder[mention.end:]
    topic = content_tokens(remainder) - _GENERIC_TOPIC
    need_text = f" (minimum {_years(need)})" if need else ""

    def topic_share(tokens: frozenset[str]) -> float:
        return len(topic & tokens) / len(topic) if topic else 1.0

    def relevant(skills: frozenset[str], tokens: frozenset[str]) -> tuple[bool, bool]:
        """(skills satisfied, topic covered) for one piece of evidence."""
        skills_ok = all(_relevant(group, skills, tokens) for group in groups)
        return skills_ok, topic_share(tokens) >= 0.5

    # A) timeline roles
    per_group = [[role for role in ctx.roles if _relevant(group, role.skills, role.tokens, role.head)] for group in groups]
    if groups:
        skills_months = min(_role_months(roles) for roles in per_group)
        skills_roles = [role for roles in per_group for role in roles]
        topic_ok = topic_share(frozenset().union(*(role.tokens for role in skills_roles))) >= 0.5 if skills_roles else False
    else:
        skills_roles = [role for role in ctx.roles if topic_share(role.tokens) >= 0.5] if topic else list(ctx.roles)
        skills_months = _role_months(skills_roles)
        topic_ok = bool(skills_roles)
    role_facts = [role.fact for role in sorted(skills_roles, key=lambda role: -(role.months or 0)) if role.fact][:3]
    role_evidence = None if role_facts else "; ".join(sorted({role.label for role in skills_roles}))[:300] or None
    if skills_roles and topic_ok and (need is None or skills_months >= need):
        return _Outcome("MATCHED", role_facts, f"{_source(role_facts)} shows {_years(skills_months)} in roles relevant to "
                                              f"'{subject}'{need_text}.", synthetic_evidence=role_evidence)

    # B) explicit duration statements (claims, unsegmented resume lines)
    duration_facts = [fact for fact in _best(ctx.of_type("EXPERIENCE")) if fact.duration]
    for fact in duration_facts:
        skills_ok, covered = relevant(fact.skills, fact.text_tokens)
        if skills_ok and covered and (need is None or fact.duration >= need):
            message = (f"{_source([fact])} explicitly states a duration ({_years(fact.duration)}) meeting the minimum "
                       f"requirement ({_years(need)}) for {subject}." if need else
                       f"{_source([fact])} establishes relevant experience for '{subject}'.")
            return _Outcome("MATCHED", [fact], message)

    hint = f"Confirm how many years were spent on {subject}{need_text}: roles, dates and their own scope of work."
    # UNCLEAR: relevant but duration not stated
    undated = [fact for fact in _best(ctx.of_type("EXPERIENCE")) if not fact.duration and relevant(fact.skills, fact.text_tokens) == (True, True)
               and not any(role.fact is fact for role in ctx.roles)]
    if undated and need:
        return _Outcome("UNCLEAR", undated[:1], f"{_source(undated[:1])} establishes relevant experience in '{subject}', but "
                                                "the duration could not be verified from an explicit year count.", hint=hint)
    # UNCLEAR: duration met, relevance partial
    for fact in duration_facts:
        skills_ok, covered = relevant(fact.skills, fact.text_tokens)
        if skills_ok and not covered and (need is None or fact.duration >= need):
            return _Outcome("UNCLEAR", [fact], f"Duration requirement is met ({_years(fact.duration)}), but domain relevance to "
                                               f"'{subject}' is only partially established; specific requirement terms were not confirmed.",
                            hint=hint)
    if skills_roles and not topic_ok and (need is None or skills_months >= need):
        return _Outcome("UNCLEAR", role_facts, f"{_source(role_facts)} shows {_years(skills_months)} with the named skills, but "
                                               f"relevance to '{subject}' is only partially established.",
                        synthetic_evidence=role_evidence, hint=hint)
    # UNCLEAR: the skill is listed and the career is long enough, but the two are not tied together
    if groups:
        unlinked = [group for group, roles in zip(groups, per_group) if not roles]
        listed = [fact for fact in ctx.of_type("SKILL") if any(_covers(group, fact.skills) for group in unlinked)]
        linked_roles = [role for group, roles in zip(groups, per_group) if group not in unlinked for role in roles]
        topical = linked_roles or [role for role in ctx.roles if topic and topic_share(role.tokens) >= 0.5] or list(ctx.roles)
        career = _role_months(topical) if ctx.roles else (ctx.total_months or 0)
        if unlinked and listed and (need is None or career >= need):
            names = ", ".join(" or ".join(skill.name for skill in group) for group in unlinked)
            return _Outcome("UNCLEAR", listed[:1], f"{_source(listed[:1])} lists {names} and shows {_years(career)} of relevant "
                                                   f"experience, but does not tie {names} to those roles.",
                            hint=f"Ask how much of the {_years(career)} involved {names}{need_text}.")
    # NOT_OBSERVED: relevant but too short
    short_roles = skills_months if skills_roles and topic_ok else 0
    short_facts = [fact for fact in duration_facts if relevant(fact.skills, fact.text_tokens) == (True, True)]
    if need and (short_roles or short_facts):
        months = max([short_roles] + [fact.duration or 0 for fact in short_facts])
        cited = role_facts if short_roles >= max([0] + [fact.duration or 0 for fact in short_facts]) else short_facts[:1]
        return _Outcome("NOT_OBSERVED", cited, f"{_source(cited)} states a shorter duration ({_years(months)}) than the requested "
                                               f"minimum ({_years(need)}); other experience is not inferred.", hint=hint)
    # UNCLEAR: enough experience overall, relevance not observed
    total = max([ctx.total_months or 0, _role_months(list(ctx.roles))] + [fact.duration or 0 for fact in duration_facts])
    if total and (need is None or total >= need):
        cited = ([role.fact for role in ctx.roles if role.fact][:2]) or duration_facts[:1]
        return _Outcome("UNCLEAR", cited, f"{_source(cited)} establishes {_years(total)} of experience, but relevance to "
                                          f"'{subject}' was not observed in the extracted evidence.", hint=hint)
    return _Outcome("NOT_OBSERVED", [], "No comparable experience or duration was observed in the extracted information; "
                                        "this does not mean the candidate lacks the requirement.", hint=hint)


# ------------------------------------------------------------------------------------ EDUCATION
def _eval_education(text: str, ctx: _Context) -> _Outcome:
    levels = degree_levels(text)
    required = parse_education(text)
    level = min(levels, key=LEVELS.__getitem__) if levels else None
    fields = required.fields if not required.any_field else frozenset()
    related_ok = bool(re.search(r"\brelated\b|\bequivalent\b|\bsimilar\b", text, re.I))
    facts = [fact for fact in _best(ctx.of_type("EDUCATION")) if fact.education]
    if level is None and not fields:
        return _lexical(text, ctx.of_type("EDUCATION"), "education")
    hint = f"Ask for the degree certificate or transcript ({text})."
    best: tuple[int, _Fact, str] | None = None
    for fact in facts:
        info = fact.education
        level_ok = level is None or (info.level is not None and LEVELS[info.level] >= LEVELS[level])
        field_ok = not fields or bool(fields & info.fields)
        related = bool(fields) and fields_related(fields, info.fields)
        if level_ok and (field_ok or (related and related_ok)):
            how = "in a related field" if not field_ok else ""
            return _Outcome("MATCHED", [fact], f"{_source([fact])} shows {_degree(info)}{' ' + how if how else ''}, "
                                               f"which meets '{text}'.")
        if level_ok and not info.fields and fields:
            reason = f"shows {_degree(info)}, but the field of study is not stated"
        elif level_ok:
            reason = f"shows {_degree(info)}, which is a different field" + (" (related)" if related else "")
        elif field_ok and info.level:
            reason = f"shows {_degree(info)}, below the required level"
        elif field_ok and fields:
            reason = f"shows study in {', '.join(sorted(info.fields))}, but the degree level is not stated"
        else:
            continue
        score = 2 if related or not info.fields else 1
        if best is None or score > best[0]:
            best = (score, fact, reason)
    if best:
        return _Outcome("UNCLEAR", [best[1]], f"{_source([best[1]])} {best[2]}.",
                        hint=hint + " Consider whether equivalent experience is acceptable.")
    return _Outcome("NOT_OBSERVED", [], "No comparable education was found in the extracted information; this does not "
                                        "mean the candidate lacks the requirement.", hint=hint)


def _degree(info: EducationInfo) -> str:
    level = {"DIPLOMA": "a diploma", "BACHELOR": "a bachelor's degree", "MASTER": "a master's degree",
             "DOCTORATE": "a doctorate"}.get(info.level or "", "a qualification")
    return f"{level} in {info.field_label}" if info.field_label else level


# -------------------------------------------------------------------------------- CERTIFICATION
def _eval_certification(text: str, ctx: _Context) -> _Outcome:
    facts = _best(ctx.of_type("CERTIFICATION"))
    best: tuple[float, _Fact, str] | None = None
    for option in split_certification_options(text):
        required = certification_tokens(option)
        if not required:
            continue
        for fact in facts:
            held = certification_tokens(f"{fact.raw.value} {fact.raw.evidence}")
            ratio = len(required & held) / len(required)
            if best is None or ratio > best[0]:
                best = (ratio, fact, option)
    hint = f"Ask for the certificate ID or a verification link for {text}."
    if best and best[0] >= 0.8:
        return _Outcome("MATCHED", [best[1]], f"{_source([best[1]])} lists a certification matching '{best[2]}'.")
    if best and best[0] >= 0.34:
        return _Outcome("UNCLEAR", [best[1]], f"{_source([best[1]])} lists a related certification; it only partly matches "
                                              f"'{best[2]}'.", hint=hint)
    return _Outcome("NOT_OBSERVED", [], "No matching certification was found in the extracted information; this does not "
                                        "mean the candidate lacks it.", hint=hint)


# ------------------------------------------------------------------------------------- dispatch
def _evaluate_option(requirement: MatchRequirement, text: str, ctx: _Context) -> _Outcome:
    if requirement.type == "SKILL":
        return _eval_skill(text, ctx)
    if requirement.type == "EXPERIENCE":
        return _eval_experience(text, requirement.minimumYears, ctx)
    if requirement.type == "EDUCATION":
        return _eval_education(text, ctx)
    return _eval_certification(text, ctx)


def _evaluate(requirement: MatchRequirement, ctx: _Context) -> _Outcome:
    options = [requirement.description] + [alt for alt in requirement.alternatives if alt.strip()]
    best: _Outcome | None = None
    for index, option in enumerate(options):
        outcome = _evaluate_option(requirement, option, ctx)
        if index and outcome.status == "MATCHED":
            outcome.explanation = f"Accepted alternative '{option}': {outcome.explanation}"
        if best is None or _STATUS_RANK[outcome.status] > _STATUS_RANK[best.status]:
            best = outcome
    return best


def _hint(requirement: MatchRequirement, outcome: _Outcome, status: str, tier: str | None) -> str | None:
    if status == "MATCHED" and tier is not None and tier_rank(tier) >= RESUME_RANK:
        return None
    if status == "MATCHED":
        return (f"Only the candidate's own claim supports '{requirement.description}'; check the linked evidence or test "
                "it in a work sample or interview.")
    if outcome.hint:
        return outcome.hint
    if requirement.type == "SKILL":
        return (f"Ask for a recent, concrete example of using {requirement.description}: what they did themselves and "
                "the result - or assign a work sample that exercises it.")
    return f"Verify '{requirement.description}' directly with the candidate."


def _result(requirement: MatchRequirement, outcome: _Outcome, tier: str | None) -> RequirementResult:
    facts = outcome.facts
    best = max(facts, key=lambda fact: fact.rank) if facts else None
    provenance = best.provenance if best else ("RESUME" if outcome.synthetic_evidence and outcome.status != "NOT_OBSERVED" else None)
    pieces = [_EVIDENCE_PREFIX.get(fact.provenance, "") + (fact.raw.evidence or fact.raw.value) for fact in facts]
    if outcome.synthetic_evidence:
        pieces.append(f"Resume timeline: {outcome.synthetic_evidence}")
    evidence = " | ".join(dict.fromkeys(pieces))[:500] or None
    sections = "; ".join(dict.fromkeys(fact.raw.sourceSection for fact in facts if fact.raw.sourceSection))[:80] or None
    if sections is None and outcome.synthetic_evidence:
        sections = "experience"
    if tier is None and facts:
        tier = best.raw.tier
    if tier is None and outcome.synthetic_evidence and outcome.status != "NOT_OBSERVED":
        tier = "RESUME_STATED"
    return RequirementResult(
        requirementId=requirement.id, status=outcome.status, tier=tier if outcome.status != "NOT_OBSERVED" else None,
        evidence=evidence, factIds=[fact.raw.id for fact in facts], sourceSection=sections, provenance=provenance,
        explanation=outcome.explanation, verificationHint=_hint(requirement, outcome, outcome.status, tier),
    )


# ----------------------------------------------------------------------------------- proficiency
@dataclass(frozen=True)
class _Depth:
    months: int          # minimum timeline months per named skill
    tier: str            # minimum evidence tier
    words: tuple[str, ...]  # explicit level words accepted in the evidence


_DEPTH = {
    "ADVANCED": _Depth(36, "CORROBORATED", ("advanced", "expert", "senior", "lead")),
    "EXPERT": _Depth(60, "DEMONSTRATED", ("expert",)),
}
_SKIP_ATTRIBUTES = frozenset({"skillId", "startDate", "endDate", "months", "current", "degreeLevel"})


def _level_text(text: str) -> str:
    """``text`` with skill names removed, so "Lead Generation" or "Advanced Cardiac Life Support" is
    not read as a level word; a qualifier in front of the canonical name ("Advanced Excel") stays."""
    for mention in reversed(get_taxonomy().find_mentions(text, list_context=True)):
        surface = mention.surface.lower().split()
        if len(surface) >= 2 and " ".join(surface[1:]) == mention.skill.name.lower():
            continue
        text = text[:mention.start] + " " + text[mention.end:]
    return text


def _level_word(texts: list[str], words: tuple[str, ...]) -> str | None:
    pattern = re.compile(r"\b(" + "|".join(words) + r")\b", re.I)
    for text in texts:
        found = pattern.search(_level_text(text))
        if found:
            return found.group(1).lower()
    return None


def _fact_texts(fact: _Fact) -> list[str]:
    attributes = fact.raw.attributes or {}
    return [fact.raw.value, fact.raw.evidence or ""] + [str(value) for key, value in attributes.items()
                                                       if key not in _SKIP_ATTRIBUTES and isinstance(value, str)]


def _apply_proficiency(requirement: MatchRequirement, outcome: _Outcome, tier: str, roles: list[_Role]) -> _Outcome:
    """Conservative depth rule for ADVANCED/EXPERT SKILL requirements (see the module docstring).

    Depth is satisfied by the tier of the match, or - for every skill the requirement names - by
    enough months in relevant timeline roles or an explicit level word in the cited facts or the
    relevant roles. A requirement without taxonomy skills can only use the tier or a level word in
    the cited facts. Missing depth turns MATCHED into UNCLEAR (never NOT_OBSERVED)."""
    level = (requirement.proficiency or "").strip().upper()
    depth = _DEPTH.get(level)
    if depth is None or requirement.type != "SKILL" or outcome.status != "MATCHED":
        return outcome
    if tier_rank(tier) >= tier_rank(depth.tier):
        outcome.explanation += f" {level.capitalize()} proficiency: the evidence is at tier {tier}."
        return outcome
    cited_texts = [text for fact in outcome.facts for text in _fact_texts(fact)]
    options = [requirement.description] + [alt for alt in requirement.alternatives if alt.strip()]
    groups = next((cnf for option in options if (cnf := skill_cnf(option))
                   and all(any(_covers(group, fact.skills) for fact in outcome.facts) for group in cnf)), ())
    satisfied: list[str] = []
    missing: list[str] = []
    for group in groups:
        names = " or ".join(skill.name for skill in group)
        relevant = [role for role in roles if _relevant(group, role.skills, role.tokens, role.head)]
        months = _role_months(relevant) if relevant else 0
        role_texts = [text for role in relevant for text in (_fact_texts(role.fact) if role.fact else [role.label])]
        word = _level_word(cited_texts + role_texts, depth.words)
        if months >= depth.months:
            satisfied.append(f"{names}: {_years(months)} in the resume timeline")
        elif word:
            satisfied.append(f"{names}: the evidence says '{word}'")
        else:
            missing.append(f"{names} ({_years(months) if months else 'no dated roles'} in the resume timeline, "
                           f"{_years(depth.months)} needed)")
    if not groups:
        word = _level_word(cited_texts, depth.words)
        if word:
            satisfied.append(f"the evidence says '{word}'")
        else:
            missing.append("no dated roles can be tied to this requirement")
    if not missing:
        outcome.explanation += f" {level.capitalize()} proficiency: {'; '.join(satisfied)}."
        return outcome
    accepted = ", ".join(f"'{word}'" for word in depth.words)
    outcome.status = "UNCLEAR"
    outcome.explanation = (f"{outcome.explanation} The requirement asks for {level} proficiency, but depth was not "
                           f"established: {'; '.join(missing)}; the evidence is at tier {tier} (depth needs "
                           f"{depth.tier} or higher) and states no level ({accepted}).")
    outcome.hint = (f"Probe depth in {requirement.description} at {level} level: ask for the most complex recent work "
                    f"they did with it and their own role, or assign a work sample or interview question that tests it.")
    return outcome


def _finish(requirement: MatchRequirement, outcome: _Outcome, tier: str, roles: list[_Role]) -> RequirementResult:
    return _result(requirement, _apply_proficiency(requirement, outcome, tier, roles), tier)


def _match_one(requirement: MatchRequirement, facts: list[_Fact], roles: list[_Role], total: int | None) -> RequirementResult:
    if requirement.type == "OTHER":
        return RequirementResult(
            requirementId=requirement.id, status="NOT_OBSERVED",
            explanation="This requirement category is not scored automatically and needs human review.",
            verificationHint="Not assessed automatically - evaluate it in the interview or with a reference check.",
        )
    linked = [fact for fact in facts if fact.linked_requirement == requirement.id]
    if linked:
        best = max(linked, key=lambda fact: fact.rank)
        outcome = _Outcome("MATCHED", [best], f"{_SOURCE_LABEL[best.provenance]} was rated as meeting this requirement.")
        return _finish(requirement, outcome, best.raw.tier, roles)
    textual = [fact for fact in facts if fact.linked_requirement is None]

    def context(minimum: int) -> _Context:
        return _Context(tuple(fact for fact in textual if fact.rank >= minimum),
                        tuple(role for role in roles if role.rank >= minimum),
                        total if minimum <= RESUME_RANK else None)

    outcome = _evaluate(requirement, context(0))
    if outcome.status != "MATCHED":
        return _result(requirement, outcome, None)
    for minimum in range(len(TIER_ORDER) - 1, 0, -1):
        candidate = context(minimum)
        if not candidate.facts and not candidate.roles:
            continue
        stricter = _evaluate(requirement, candidate)
        if stricter.status == "MATCHED":
            return _finish(requirement, stricter, TIER_ORDER[minimum], roles)
    return _finish(requirement, outcome, "SELF_CLAIMED", roles)


def match_requirements(request: MatchRequest, today: date | None = None) -> MatchResponse:
    today = today or date.today()
    facts = [_prepare(fact) for fact in request.facts if fact.type not in {"EMAIL", "PHONE", "LINK"}]
    roles = _build_roles(facts, request.timeline, today)
    total = request.timeline.totalMonths if request.timeline else (_role_months(roles) if roles else None)
    results = [_match_one(requirement, facts, roles, total) for requirement in request.requirements]

    weights = {requirement.id: 2 if requirement.mandatory else 1 for requirement in request.requirements}
    scored = [(requirement, result) for requirement, result in zip(request.requirements, results) if requirement.type != "OTHER"]
    denominator = sum(weights[requirement.id] for requirement, _ in scored)

    def verified(result: RequirementResult) -> bool:
        return result.status == "MATCHED" and result.tier is not None and tier_rank(result.tier) >= RESUME_RANK

    verified_weight = sum(weights[requirement.id] for requirement, result in scored if verified(result))
    claimed_weight = sum(weights[requirement.id] for requirement, result in scored if result.status == "MATCHED")
    verified_coverage = round(100 * verified_weight / denominator, 1) if denominator else None
    unverified_knockouts = [requirement.id for requirement, result in zip(request.requirements, results)
                            if requirement.knockout and not verified(result)]
    return MatchResponse(
        scoreLabel=SCORE_LABEL,
        verifiedCoverage=verified_coverage,
        claimedCoverage=round(100 * claimed_weight / denominator, 1) if denominator else None,
        knockoutsVerified=not unverified_knockouts,
        unverifiedKnockouts=unverified_knockouts,
        score=verified_coverage,
        requirements=results,
    )


def match_batch(request: BatchMatchRequest, today: date | None = None) -> BatchMatchResponse:
    candidates = []
    for candidate in request.candidates:
        result = match_requirements(MatchRequest(requirements=request.requirements, facts=candidate.facts,
                                                 timeline=candidate.timeline), today)
        candidates.append(CandidateMatch(applicationId=candidate.applicationId, **result.model_dump()))
    return BatchMatchResponse(candidates=candidates)
