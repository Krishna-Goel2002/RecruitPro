"""Data model shared by the generator, the composer and the renderers.

Dates are month ordinals (``year * 12 + month - 1``). A role with ``end=None`` is current.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from resumelab.data.education import Degree


def ordinal(year: int, month: int) -> int:
    return year * 12 + month - 1


def ym(value: int) -> str:
    year, month = divmod(value, 12)
    return f"{year:04d}-{month + 1:02d}"


def union_months(intervals: list[tuple[int, int]]) -> int:
    """Inclusive month intervals merged (overlaps counted once) - the same rule the parser uses."""
    covered: set[int] = set()
    for start, end in intervals:
        covered.update(range(start, end + 1))
    return len(covered)


@dataclass
class Persona:
    first: str
    last: str
    gender: str  # F | M | X
    region: str
    indian: bool
    city: str
    country: str
    email: str
    phone: str
    linkedin: str | None
    github: str | None
    portfolio: str | None
    dob: date
    marital: str
    nationality: str
    father: str | None
    languages: list[str]
    interests: list[str]

    @property
    def full_name(self) -> str:
        return f"{self.first} {self.last}"


@dataclass
class Bullet:
    text: str
    skills: tuple[str, ...] = ()
    surfaces: dict[str, str] = field(default_factory=dict)  # skill -> text as rendered


@dataclass
class Project:
    name: str
    client: str | None
    start: int | None
    end: int | None
    role: str | None
    team: int | None
    env: list[str]
    bullets: list[Bullet]


@dataclass
class Role:
    title: str
    employer: str
    city: str
    start: int
    end: int | None  # None = current
    kind: str  # full_time | internship | freelance | part_time | contract
    profession: str
    title_skills: tuple[str, ...] = ()
    title_surfaces: dict[str, str] = field(default_factory=dict)  # title skill -> text as written in the title
    skills: list[str] = field(default_factory=list)  # skills chosen for the role (all rendered in its block)
    bullets: list[Bullet] = field(default_factory=list)
    env: list[str] = field(default_factory=list)  # "Environment:" / "Tools:" line
    env_surfaces: list[str] = field(default_factory=list)
    projects: list[Project] = field(default_factory=list)

    def end_or(self, as_of: int) -> int:
        return as_of if self.end is None else self.end

    def mentioned(self) -> set[str]:
        found = set(self.title_skills) | set(self.env)
        for bullet in self.bullets:
            found.update(bullet.skills)
        for project in self.projects:
            found.update(project.env)
            for bullet in project.bullets:
                found.update(bullet.skills)
        return found


@dataclass
class Gap:
    start: int
    end: int
    reason: str
    shown: bool


@dataclass
class Education:
    level: str  # SCHOOL | DIPLOMA | BACHELOR | MASTER | DOCTORATE | PROFESSIONAL
    degree: Degree | None
    label: str  # rendered degree, e.g. "B.Tech" / "Class XII"
    field: str
    institution: str
    start_year: int
    end_year: int
    grade: str  # rendered: "8.2 CGPA", "78.4%", "First Class"
    board: str | None = None
    pursuing: bool = False
    coursework: list[str] = field(default_factory=list)  # skills (declared)


@dataclass
class HeldCert:
    key: str
    name: str
    issuer: str
    year: int
    skills: tuple[str, ...]


@dataclass
class Candidate:
    persona: Persona
    profession: str
    seniority: str
    stack: str | None
    roles: list[Role]
    gaps: list[Gap]
    education: list[Education]
    certs: list[HeldCert]
    listed_only: list[str]  # skills listed in the skills section but never used in a role
    projects: list[Project]  # academic / personal projects (not roles)
    achievements: list[str]
    shape: list[str]  # career shape tags: changer, returner, freelance_overlap, gap, promotion, fresher, student, intern
    excluded: frozenset[str] = frozenset()

    def all_used(self) -> set[str]:
        used: set[str] = set()
        for role in self.roles:
            used |= role.mentioned()
        return used
