"""Hand-labelled proficiency cases for ``evidence-ladder-4`` (requirement proficiency in matching).

Each case is a SKILL requirement with a proficiency level, a small set of facts (and optionally
dated roles) and the gold status written from the rule, not from the engine's output:

* BASIC / INTERMEDIATE - unchanged;
* ADVANCED - MATCHED needs >= 3 years of relevant timeline roles per named skill, or tier >=
  CORROBORATED, or one of the words advanced / expert / senior / lead in the evidence;
* EXPERT - >= 5 years, or tier >= DEMONSTRATED, or the word expert;
* missing depth is UNCLEAR; proficiency never produces NOT_OBSERVED by itself.

Like the rest of the harness these cases are synthetic regression guards, not accuracy claims.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.models.matching import MatchFact, MatchRequest, MatchRequirement


@dataclass(frozen=True)
class ProficiencyCase:
    name: str
    description: str
    proficiency: str
    facts: tuple[MatchFact, ...]
    gold: str


def _skill(ident: str, value: str, evidence: str | None = None, tier: str = "RESUME_STATED") -> MatchFact:
    return MatchFact(id=ident, type="SKILL", value=value, evidence=evidence or value, sourceSection="skills", tier=tier)


def _role(ident: str, title: str, start: str, months: int, text: str) -> MatchFact:
    return MatchFact(id=ident, type="EXPERIENCE", value=f"{title}, Example Ltd", evidence=f"{title}, Example Ltd · {text}",
                     sourceSection="experience", tier="RESUME_STATED",
                     attributes={"title": title, "startDate": start, "months": months})


CASES: tuple[ProficiencyCase, ...] = (
    ProficiencyCase("basic-listed", "Python", "BASIC", (_skill("fact:1", "Python"),), "MATCHED"),
    ProficiencyCase("intermediate-listed", "SQL", "INTERMEDIATE", (_skill("fact:1", "PostgreSQL"),), "MATCHED"),
    ProficiencyCase("advanced-listed-only", "Python", "ADVANCED", (_skill("fact:1", "Python"),), "UNCLEAR"),
    ProficiencyCase("advanced-short-role", "Python", "ADVANCED",
                    (_skill("fact:1", "Python"), _role("fact:2", "Engineer", "2024-03", 20, "Python services")), "UNCLEAR"),
    ProficiencyCase("advanced-long-role", "Python", "ADVANCED",
                    (_skill("fact:1", "Python"), _role("fact:2", "Engineer", "2021-01", 44, "Python services")), "MATCHED"),
    ProficiencyCase("advanced-two-roles-merged", "Java", "ADVANCED",
                    (_skill("fact:1", "Java"), _role("fact:2", "Developer", "2020-01", 20, "Java APIs"),
                     _role("fact:3", "Developer", "2022-01", 20, "Java batch jobs")), "MATCHED"),
    ProficiencyCase("advanced-senior-title", "Java", "ADVANCED",
                    (_skill("fact:1", "Java"), _role("fact:2", "Senior Java Developer", "2025-01", 10, "Java APIs")), "MATCHED"),
    ProficiencyCase("advanced-word-in-skills-line", "React", "ADVANCED",
                    (_skill("fact:1", "React", "Advanced: React, TypeScript"),), "MATCHED"),
    ProficiencyCase("advanced-corroborated", "React", "ADVANCED", (_skill("vf:1", "React", tier="CORROBORATED"),), "MATCHED"),
    ProficiencyCase("advanced-skill-name-not-a-level", "Lead Generation", "ADVANCED",
                    (_skill("fact:1", "Lead Generation"),), "UNCLEAR"),
    ProficiencyCase("advanced-conjunction-one-shallow", "Java and Kafka", "ADVANCED",
                    (_skill("fact:1", "Java"), _skill("fact:2", "Kafka"), _role("fact:3", "Engineer", "2019-01", 60, "Java services"),
                     _role("fact:4", "Engineer", "2024-01", 12, "Kafka consumers")), "UNCLEAR"),
    ProficiencyCase("advanced-absent-stays-not-observed", "Rust", "ADVANCED", (_skill("fact:1", "Python"),), "NOT_OBSERVED"),
    ProficiencyCase("advanced-partial-stays-unclear", "Python and Kafka", "ADVANCED", (_skill("fact:1", "Python"),), "UNCLEAR"),
    ProficiencyCase("expert-senior-is-not-enough", "Python", "EXPERT",
                    (_skill("fact:1", "Python"), _role("fact:2", "Senior Engineer", "2022-01", 40, "Python platform")), "UNCLEAR"),
    ProficiencyCase("expert-six-years", "Python", "EXPERT",
                    (_skill("fact:1", "Python"), _role("fact:2", "Engineer", "2019-01", 72, "Python platform")), "MATCHED"),
    ProficiencyCase("expert-word", "Python", "EXPERT", (_skill("fact:1", "Python", "Expert in Python and Django"),), "MATCHED"),
    ProficiencyCase("expert-corroborated-not-enough", "Python", "EXPERT", (_skill("vf:1", "Python", tier="CORROBORATED"),), "UNCLEAR"),
    ProficiencyCase("expert-work-sample", "Python", "EXPERT",
                    (_skill("ws:3:r1", "Rubric: Python data pipeline", tier="DEMONSTRATED"),), "MATCHED"),
)


def request(case: ProficiencyCase) -> MatchRequest:
    requirement = MatchRequirement(id=1, type="SKILL", description=case.description, mandatory=True,
                                   knockout=True, proficiency=case.proficiency)
    return MatchRequest(requirements=[requirement], facts=list(case.facts))
