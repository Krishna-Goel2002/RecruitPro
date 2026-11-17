"""Profession model and registry.

A profession is a skill graph (skills with a category, a first-usable year and surface variants),
titles per seniority, bullet templates, certifications, degrees, sectors and job templates.

Bullet templates name *categories* in braces (``{lang}``, ``{db}``, ``{tool}``, ``{any}``) which are
filled from the skills a role actually uses, so every skill rendered in a bullet is known and the
role it belongs to is known (per-skill months). ``{pct}``, ``{n}`` and friends are metrics. Fixed
bullets name their skills explicitly. Templates never mention a taxonomy skill in their fixed text
(the test suite checks this against the AI service taxonomy).
"""
from __future__ import annotations

from dataclasses import dataclass, field

LEVELS = ("intern", "fresher", "junior", "mid", "senior", "lead")
# Typical experience (months) for a candidate at each seniority.
LEVEL_MONTHS = {"intern": (0, 0), "fresher": (0, 8), "junior": (12, 36), "mid": (36, 78), "senior": (78, 132), "lead": (120, 216)}
JOB_LEVEL = {"intern": "ENTRY", "fresher": "ENTRY", "junior": "ENTRY", "mid": "MID", "senior": "SENIOR", "lead": "LEAD"}


@dataclass(frozen=True)
class SkillSpec:
    name: str
    cat: str
    since: int = 1990
    alt: tuple[str, ...] = ()  # other surface forms ("Postgres", "K8s", "MS Excel")


@dataclass(frozen=True)
class Cert:
    key: str
    name: str
    issuer: str
    skills: tuple[str, ...] = ()  # skills named inside ``name``
    since: int = 2000


@dataclass(frozen=True)
class ReqT:
    """Job requirement template. ``groups``: AND of OR-groups of skill names (SKILL) or the subject (EXPERIENCE)."""

    type: str
    text: str
    groups: tuple[tuple[str, ...], ...] = ()
    mandatory: bool = True
    knockout: bool = False
    years: dict[str, int] | None = None  # EXPERIENCE: minimum years per job level
    exp_professions: tuple[str, ...] = ()  # EXPERIENCE: roles of these professions count as relevant
    level: str | None = None  # EDUCATION: minimum level
    fields: tuple[str, ...] = ()  # EDUCATION: accepted field families (empty = any)
    cert: str | None = None  # CERTIFICATION: key (alternatives: other keys accepted)
    cert_alternatives: tuple[str, ...] = ()
    alternatives: tuple[str, ...] = ()  # SKILL: alternative descriptions (each one skill name)
    proficiency: str | None = None


def skill(text, *groups, mandatory=True, knockout=False, alternatives=(), proficiency=None):
    return ReqT("SKILL", text, tuple(tuple(g) for g in groups), mandatory, knockout, alternatives=tuple(alternatives), proficiency=proficiency)


def experience(text, years, professions, *subject, mandatory=True, knockout=False):
    return ReqT("EXPERIENCE", text, (tuple(subject),) if subject else (), mandatory, knockout, years=years, exp_professions=tuple(professions))


def education(text, level, *fields, mandatory=True, knockout=False):
    return ReqT("EDUCATION", text, (), mandatory, knockout, level=level, fields=tuple(fields))


def certification(text, key, *alternatives, mandatory=False, knockout=False):
    return ReqT("CERTIFICATION", text, (), mandatory, knockout, cert=key, cert_alternatives=tuple(alternatives))


def other(text, mandatory=False):
    return ReqT("OTHER", text, (), mandatory, False)


@dataclass(frozen=True)
class JobT:
    titles: dict[str, str]  # job level (junior|mid|senior|lead|intern) -> title
    department: str
    reqs: tuple[ReqT, ...]
    about: str  # one paragraph about the role; {company} {city}
    duties: tuple[str, ...]
    perks: tuple[str, ...] = ("Health insurance for you and your family", "Hybrid working (3 days in office)",
                              "Annual learning budget", "Performance bonus")


@dataclass(frozen=True)
class Profession:
    key: str
    label: str
    family: str  # tech | product | design | business | health | education | engineering | legal
    titles: dict[str, tuple[str, ...]]
    skills: tuple[SkillSpec, ...]
    bullets: tuple[str, ...]
    weak: tuple[str, ...]
    degrees: tuple[tuple[str, int], ...]  # (degree key, weight)
    masters: tuple[str, ...]
    certs: tuple[Cert, ...]
    sectors: tuple[str, ...]
    summaries: tuple[str, ...]
    related: tuple[str, ...]
    jobs: tuple[JobT, ...]
    fixed: tuple[tuple[str, tuple[str, ...]], ...] = ()
    stacks: dict[str, tuple[str, ...]] = field(default_factory=dict)  # coherent primary stacks (skill names)
    practice: str | None = None  # taxonomy skill naming the practice ("Backend Development")
    projects: tuple[str, ...] = ()  # project names (tech, engineering)
    soft: tuple[str, ...] = ()  # general skills that may appear (Team Leadership, Mentoring...)
    env_line: bool = False  # roles may end with "Environment: a, b, c" (Indian IT style)
    publications: tuple[str, ...] = ()  # academic titles, may contain {any}

    def skill(self, name: str) -> SkillSpec | None:
        return next((spec for spec in self.skills if spec.name == name), None)

    def by_cat(self, cat: str) -> list[SkillSpec]:
        return [spec for spec in self.skills if spec.cat == cat]


def registry() -> dict[str, Profession]:
    from resumelab.data.professions_other import OTHER
    from resumelab.data.professions_tech import TECH
    return {profession.key: profession for profession in TECH + OTHER}


# Generic skills usable in any profession (rendered only when declared).
GENERIC = (
    SkillSpec("Microsoft Office", "office", 1995, ("MS Office",)), SkillSpec("Excel", "office", 1995, ("MS Excel", "Microsoft Excel")),
    SkillSpec("PowerPoint", "office", 1995, ("MS PowerPoint",)), SkillSpec("Team Leadership", "soft"), SkillSpec("Mentoring", "soft"),
    SkillSpec("Public Speaking", "soft"), SkillSpec("Time Management", "soft"), SkillSpec("Report Writing", "soft"),
    SkillSpec("Presentation Skills", "soft"), SkillSpec("Cross-functional Collaboration", "soft"),
)
