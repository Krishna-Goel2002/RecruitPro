"""Career generation: timelines, education, certifications and role skills.

The timeline is built backwards from the as-of month: current role (or a recent end), earlier roles
with promotions and switches, optional gaps (with reasons), returners, career changers and
overlapping freelance work, then graduation and internships. Every skill a role uses is rendered in
that role's block (bullets, title or an "Environment:" line), so per-skill months are exact.
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field

from resumelab.data.education import BOARDS_10, BOARDS_12, DEGREES, LEVEL_RANK, SCHOOL_NAMES, STREAMS_12, Degree, affiliating_university, institution
from resumelab.data.employers import EMPLOYERS
from resumelab.data.professions import LEVEL_MONTHS, Profession
from resumelab.data.writing import ACHIEVEMENTS, BREAK_REASONS, WEAK_VERBS
from resumelab.model import Bullet, Candidate, Education, Gap, HeldCert, Persona, Project, Role, union_months
from resumelab.taxonomy import load as load_taxonomy
from resumelab.textgen import fill, finish_sentence, spec_for, surface

CHANGER_SOURCES = {  # target profession -> earlier professions people often move from
    "data_analyst": ("accountant", "teacher", "mechanical_engineer", "customer_support", "operations"),
    "ux_designer": ("marketing", "frontend", "teacher"), "qa": ("customer_support", "teacher"), "product_manager": ("backend", "sales", "data_analyst"),
    "marketing": ("sales", "customer_support"), "hr_recruiter": ("customer_support", "sales", "teacher"), "frontend": ("ux_designer", "qa"),
    "devops": ("qa", "backend"), "sales": ("customer_support", "marketing"), "operations": ("mechanical_engineer", "sales"),
    "fullstack": ("qa", "teacher", "mechanical_engineer"), "backend": ("qa", "mechanical_engineer"), "data_scientist": ("data_analyst", "backend"),
}
SOFT_SENTENCES = {  # skill -> (surface, sentence)
    "Team Leadership": ("team leadership", "Provided {shown} to a group of {n} colleagues"),
    "Mentoring": ("Mentored", "{shown} {n} new joiners during their first quarter"),
    "Code Review": ("code reviews", "Took part in {shown} for every release"),
    "Public Speaking": ("public speaking", "Represented the team at internal town halls ({shown})"),
    "Time Management": ("time management", "Balanced multiple deadlines with disciplined {shown}"),
    "Presentation Skills": ("presentation skills", "Used strong {shown} in weekly reviews with leadership"),
    "Report Writing": ("report writing", "Handled {shown} for monthly reviews"),
    "Stakeholder Management": ("stakeholder management", "Owned {shown} with business and operations heads"),
    "Cross-functional Collaboration": ("cross-functional collaboration", "Drove {shown} across {n} teams"),
}
FREELANCE_TITLES = {"nurse": "Home Care Nurse (Part-time)", "teacher": "Home Tutor (Part-time)", "legal_associate": "Independent Practice",
                    "accountant": "Freelance Accountant", "civil_engineer": "Freelance Structural Consultant", "customer_support": "Part-time Chat Support",
                    "sales": "Freelance Sales Consultant", "hr_recruiter": "Freelance Recruiter", "operations": "Part-time Operations Consultant",
                    "mechanical_engineer": "Freelance Design Engineer"}


@dataclass
class Plan:
    """What the career must (or must not) contain - set by the fit planner."""

    profession: str
    seniority: str
    rel_professions: tuple[str, ...] = ()
    rel_months: tuple[int, int] | None = None  # inclusive range of months in relevant roles
    include: dict[str, int] = field(default_factory=dict)  # skill -> minimum months of role usage (0 = anywhere visible)
    exclude: frozenset[str] = frozenset()
    edu: tuple[str, str | None, tuple[str, ...]] | None = None  # ("ok"|"fail", level, fields)
    certs_hold: tuple[str, ...] = ()
    certs_avoid: frozenset[str] = frozenset()
    listed_extra: tuple[str, ...] = ()  # inflated claims: listed in skills, never used in roles
    shape: tuple[str, ...] = ()  # forced shape tags


def expand_exclusions(names: set[str]) -> frozenset[str]:
    """Excluding a skill also excludes its taxonomy descendants (MySQL satisfies a SQL requirement)."""
    taxonomy = load_taxonomy()
    result = set(names)
    if taxonomy is None:
        return frozenset(result)
    excluded_ids = {skill.id for name in names if (skill := taxonomy.resolve(name))}
    for skill in taxonomy.skills.values():
        if taxonomy.ancestors(skill.id) & excluded_ids:
            result.add(skill.name)
    return frozenset(result)


class CareerBuilder:
    def __init__(self, rng: random.Random, professions: dict[str, Profession], as_of: int, quality: str) -> None:
        self.rng = rng
        self.professions = professions
        self.as_of = as_of
        self.as_of_year = as_of // 12
        self.quality = quality
        self.used_templates: set[str] = set()
        self.stack_name: str | None = None
        self.title_ok: set[str] | None = None  # skills a title may name (the candidate's stack), None = any
        self.home: str | None = None

    # ------------------------------------------------------------------------------------------ timeline
    def _title(self, profession: Profession, level: str, excluded: frozenset[str]) -> tuple[str, tuple[str, ...]]:
        taxonomy = load_taxonomy()
        options = list(profession.titles.get(level) or profession.titles["junior"])
        self.rng.shuffle(options)
        for title in options:
            skills = tuple(dict.fromkeys(skill.name for skill, _ in taxonomy.mentions(title))) if taxonomy else ()
            if set(skills) & excluded:
                continue
            if profession.stacks and self.title_ok is not None and profession.key == self.home and not set(skills) <= self.title_ok:
                continue  # an "iOS Developer" title for an Android stack, a "Java Developer" title for a Python stack
            return title, skills
        return f"{profession.label}", ()

    @staticmethod
    def title_surfaces(title: str) -> dict[str, str]:
        taxonomy = load_taxonomy()
        return {skill.name: shown for skill, shown in taxonomy.mentions(title)} if taxonomy else {}

    def _employer(self, profession: Profession, indian: bool, avoid: set[str]) -> tuple[str, str]:
        sectors = list(profession.sectors)
        if not indian and "intl_tech" in EMPLOYERS and profession.family == "tech":
            sectors.append("intl_tech")
        for _ in range(10):
            name, _size = self.rng.choice(EMPLOYERS[self.rng.choice(sectors)])
            if name not in avoid:
                return name, _size
        return name, _size

    @staticmethod
    def _level_for(months_before: int, cap: str) -> str:
        level = "junior" if months_before < 30 else "mid" if months_before < 72 else "senior" if months_before < 120 else "lead"
        order = ("intern", "fresher", "junior", "mid", "senior", "lead")
        return level if order.index(level) <= order.index(cap) else cap

    def timeline(self, plan: Plan, persona: Persona) -> tuple[list[Role], list[Gap], list[str], int]:
        """Roles (most recent first), gaps, shape tags and the graduation month ordinal."""
        rng, prof = self.rng, self.professions[plan.profession]
        shape: list[str] = list(plan.shape)
        lo, hi = LEVEL_MONTHS[plan.seniority]
        if plan.rel_months:
            lo, hi = max(lo if plan.profession in plan.rel_professions else 0, plan.rel_months[0]), plan.rel_months[1]
            lo = min(lo, hi)
        if plan.seniority == "intern":
            grad = self.as_of + rng.randint(4, 20)  # still studying
            roles = self._internships(prof, persona, grad, rng.choice((0, 1, 1, 2)), plan.exclude, before=self.as_of)
            for role in roles:
                role.title_surfaces = self.title_surfaces(role.title)
            return roles, [], shape + ["student"], grad
        total = rng.randint(lo, max(lo, hi))
        roles: list[Role] = []
        gaps: list[Gap] = []
        current = rng.random() < (0.85 if plan.seniority not in {"fresher"} else 0.5)
        cursor = self.as_of if current else self.as_of - rng.randint(1, 7)
        if total == 0:
            grad = cursor - rng.randint(0, 6)
            roles = self._internships(prof, persona, grad, rng.choice((1, 1, 2)), plan.exclude)
            for role in roles:
                role.title_surfaces = self.title_surfaces(role.title)
            return roles, gaps, shape + ["fresher"], grad
        returner = "returner" in shape or (plan.seniority in {"mid", "senior"} and rng.random() < 0.06)
        changer = "changer" in shape or (plan.seniority in {"mid", "senior", "lead"} and plan.profession in CHANGER_SOURCES and rng.random() < 0.09)
        n_employers = {"fresher": 1, "junior": rng.choice((1, 1, 2)), "mid": rng.choice((1, 2, 2, 3)), "senior": rng.choice((2, 3, 3, 4)),
                       "lead": rng.choice((3, 4, 4, 5))}[plan.seniority]
        n_employers = max(1, min(n_employers, total // 8 or 1))
        cuts = sorted(rng.sample(range(6, max(7, total - 5)), n_employers - 1)) if n_employers > 1 and total > 12 else []
        lengths = [b - a for a, b in zip([0] + cuts, cuts + [total])]
        lengths = [length for length in lengths if length > 0] or [total]
        used_employers: set[str] = set()
        first_start = None
        months_before_each = []
        acc = 0
        for length in reversed(lengths):  # oldest first, to know experience at each start
            months_before_each.append(acc)
            acc += length
        months_before_each.reverse()
        for position, length in enumerate(lengths):  # most recent first
            if returner and position == 1:
                gap_len = rng.randint(18, 54)
                gaps.append(Gap(cursor - gap_len + 1, cursor, rng.choice(BREAK_REASONS), rng.random() < 0.6))
                cursor -= gap_len
                shape.append("returner")
            elif position > 0:
                if rng.random() < 0.12:
                    gap_len = rng.randint(3, 10)
                    gaps.append(Gap(cursor - gap_len + 1, cursor, rng.choice(BREAK_REASONS), rng.random() < 0.5))
                    cursor -= gap_len
                    shape.append("gap")
                else:
                    cursor -= rng.choice((0, 0, 1, 1, 2))
            employer, _size = self._employer(prof, persona.indian, used_employers)
            used_employers.add(employer)
            city = persona.city if rng.random() < 0.7 or not persona.indian else rng.choice(
                ("Bengaluru", "Pune", "Hyderabad", "Gurugram", "Mumbai", "Chennai", "Noida", "Kolkata", "Remote"))
            end = None if position == 0 and current and cursor == self.as_of else cursor
            start = cursor - length + 1
            promote = length >= 30 and rng.random() < 0.4
            level_now = self._level_for(months_before_each[position] + length - 1, plan.seniority)
            if promote:
                split = rng.randint(12, length - 12)
                level_old = self._level_for(months_before_each[position], plan.seniority)
                if level_old == level_now:
                    order = ("junior", "mid", "senior", "lead")
                    level_old = order[max(0, order.index(level_now) - 1)] if level_now in order else level_now
                title_new, skills_new = self._title(prof, level_now, plan.exclude)
                title_old, skills_old = self._title(prof, level_old, plan.exclude)
                if title_old == title_new:
                    promote = False
                else:
                    roles.append(Role(title_new, employer, city, start + split, end, "full_time", prof.key, skills_new))
                    roles.append(Role(title_old, employer, city, start, start + split - 1, "full_time", prof.key, skills_old))
                    shape.append("promotion")
            if not promote:
                level = level_now if months_before_each[position] > 0 or plan.seniority != "fresher" else "fresher"
                if months_before_each[position] == 0 and length <= 14 and rng.random() < 0.5:
                    level = "fresher"
                title, title_skills = self._title(prof, level, plan.exclude)
                roles.append(Role(title, employer, city, start, end, "full_time", prof.key, title_skills))
            cursor = start - 1
            first_start = start
        if changer:
            source = self.professions[rng.choice(CHANGER_SOURCES[plan.profession])]
            cursor -= rng.choice((0, 1, 2, 3))
            for _ in range(rng.choice((1, 1, 2))):
                length = rng.randint(14, 40)
                employer, _ = self._employer(source, persona.indian, used_employers)
                title, title_skills = self._title(source, "junior" if not roles or rng.random() < 0.6 else "mid", plan.exclude)
                roles.append(Role(title, employer, persona.city, cursor - length + 1, cursor, "full_time", source.key, title_skills))
                cursor -= length + rng.choice((0, 1, 2))
                first_start = cursor + 1
            shape.append("changer")
        if rng.random() < 0.1 and roles and plan.seniority not in {"fresher"}:
            anchor = rng.choice(roles)
            anchor_end = anchor.end_or(self.as_of)
            length = rng.randint(4, 14)
            start = max(anchor.start, anchor_end - length - rng.randint(0, 10))
            end = min(anchor_end, start + length - 1)
            if end > start:
                title = FREELANCE_TITLES.get(prof.key, f"Freelance {prof.label}")
                roles.append(Role(title, rng.choice(("Freelance", "Self-employed", "Independent")), "Remote", start, end, "freelance", prof.key, ()))
                shape.append("freelance_overlap")
        roles.sort(key=lambda role: (-(role.end_or(self.as_of)), -role.start))
        for role in roles:
            role.title_surfaces = self.title_surfaces(role.title)
        grad = (first_start or cursor) - rng.choice((1, 1, 2, 3, 4))
        if rng.random() < (0.45 if plan.seniority in {"fresher", "junior"} else 0.15):
            roles += self._internships(prof, persona, grad, 1, plan.exclude)
        for role in roles:
            role.title_surfaces = self.title_surfaces(role.title)
        return roles, gaps, shape, grad

    def _internships(self, prof: Profession, persona: Persona, grad: int, count: int, excluded: frozenset[str], before: int | None = None) -> list[Role]:
        roles = []
        cursor = min(grad - 1, before if before is not None else grad - 1)
        for _ in range(count):
            length = self.rng.choice((2, 2, 3, 3, 4, 6))
            employer, _ = self._employer(prof, persona.indian, set())
            title, skills = self._title(prof, "intern", excluded)
            roles.append(Role(title, employer, persona.city, cursor - length + 1, cursor, "internship", prof.key, skills))
            cursor -= length + self.rng.randint(3, 8)
        return roles

    # ------------------------------------------------------------------------------------------ education
    def education(self, plan: Plan, persona: Persona, grad: int) -> list[Education]:
        rng, prof = self.rng, self.professions[plan.profession]
        degrees = [DEGREES[key] for key, _ in prof.degrees]
        weights = [weight for _, weight in prof.degrees]
        bachelor = rng.choices(degrees, weights=weights)[0]
        masters: Degree | None = None
        if plan.edu and plan.edu[0] == "fail":
            _, level, fields = plan.edu
            own = [d for d in degrees if not _edu_meets(d, level, fields)]
            related = [DEGREES[k] for r in prof.related for k, _ in self.professions[r].degrees if not _edu_meets(DEGREES[k], level, fields)]
            pool = own or related or [d for d in DEGREES.values() if d.level in {"BACHELOR", "DIPLOMA"} and not _edu_meets(d, level, fields)]
            bachelor = rng.choice(pool)
        elif plan.edu and plan.edu[0] == "ok" and plan.edu[1] != "MASTER":
            _, level, fields = plan.edu
            fitting = [d for d in degrees if _edu_meets(d, level, fields)]
            if not _edu_meets(bachelor, level, fields):
                bachelor = rng.choice(fitting or [d for d in DEGREES.values() if _edu_meets(d, level, fields) and d.level in {"BACHELOR", "DIPLOMA"}])
        options = [DEGREES[k] for k in prof.masters if compatible(bachelor, DEGREES[k])]
        if plan.seniority not in {"intern", "fresher"} and options and rng.random() < 0.3:
            masters = rng.choice(options)
        if plan.edu:
            kind, level, fields = plan.edu
            if kind == "ok" and level == "MASTER" and not (masters and _edu_meets(masters, level, fields)):
                fitting = [d for d in DEGREES.values() if d.level in {"MASTER", "DOCTORATE"} and _edu_meets(d, level, fields) and compatible(bachelor, d)]
                masters = rng.choice(fitting) if fitting else masters
            if kind == "fail" and masters and _edu_meets(masters, level, fields):
                masters = None
        if bachelor.key in {"bed", "dled"}:  # teaching degrees follow a first degree
            first = DEGREES[rng.choice(("ba_eng", "bsc_maths", "bsc_physics"))]
            masters, bachelor = bachelor, first
        records: list[Education] = []
        pursuing = grad > self.as_of
        # Degrees complete in May-July: the degree year is the last such summer on or before ``grad``.
        grad_year = grad // 12 if pursuing or grad % 12 >= 4 else grad // 12 - 1
        if masters:
            if masters.level == "PROFESSIONAL" or masters.key in {"bed", "dled"}:
                m_end = min(grad_year + masters.years, self.as_of_year)
            else:
                m_end = grad_year
                grad_year = m_end - masters.years
            records.append(Education(masters.level, masters, rng.choice(masters.short), masters.field, institution(rng, masters, persona.indian),
                                     m_end - masters.years, m_end, self._grade(rng, masters, persona.indian),
                                     board=affiliating_university(rng) if persona.indian and masters.kind != "professional" else None))
        records.append(Education(bachelor.level, bachelor, rng.choice(bachelor.short), bachelor.field, institution(rng, bachelor, persona.indian),
                                 grad_year - bachelor.years, grad_year, self._grade(rng, bachelor, persona.indian) if not pursuing else
                                 f"{rng.uniform(6.8, 9.3):.2f} CGPA (till {rng.choice(('5th', '6th', '7th'))} semester)", pursuing=pursuing,
                                 board=affiliating_university(rng) if persona.indian else None))
        if persona.indian:
            twelfth = grad_year - bachelor.years
            stream = {"commerce": "commerce", "business administration": "commerce", "nursing": "bio", "english": "arts", "psychology": "arts",
                      "law": "arts", "economics": "commerce"}.get(bachelor.family, "science")
            index = rng.randrange(len(BOARDS_12))
            board12 = BOARDS_12[index]
            board10 = BOARDS_10[index] if rng.random() < 0.8 else rng.choice(BOARDS_10)
            records.append(Education("SCHOOL", None, rng.choice(("Class XII", "12th", "HSC", "Intermediate", "Senior Secondary (XII)")),
                                     STREAMS_12[stream], rng.choice(SCHOOL_NAMES), twelfth - 2, twelfth, f"{rng.uniform(62, 95):.1f}%", board=board12))
            grade10 = f"{rng.uniform(65, 97):.1f}%" if board10 != "CBSE" or rng.random() < 0.5 else f"{rng.choice((8.2, 8.6, 9.0, 9.4, 9.8, 10.0))} CGPA"
            schools = [name for name in SCHOOL_NAMES if "Junior College" not in name]
            records.append(Education("SCHOOL", None, rng.choice(("Class X", "10th", "SSC", "Matriculation", "Secondary (X)")), "",
                                     rng.choice(schools), twelfth - 4, twelfth - 2, grade10, board=board10))
        return records

    @staticmethod
    def _grade(rng: random.Random, degree: Degree, indian: bool) -> str:
        if degree.level == "PROFESSIONAL":
            return rng.choice(("Qualified", f"Cleared in {rng.choice(('first', 'second'))} attempt", "All India Rank holder"))
        if not indian:
            return rng.choice((f"GPA {rng.uniform(3.0, 3.9):.2f}/4.0", "Honours", "Cum laude", ""))
        style = rng.choice(("cgpa", "cgpa", "percent", "class"))
        if style == "cgpa":
            return f"{rng.uniform(6.5, 9.4):.2f} CGPA" if rng.random() < 0.6 else f"CGPA: {rng.uniform(6.5, 9.4):.1f}/10"
        if style == "percent":
            return f"{rng.uniform(58, 86):.1f}%"
        return rng.choice(("First Class with Distinction", "First Class", "Second Class"))

    # ------------------------------------------------------------------------------------------ certifications
    def certifications(self, plan: Plan, grad: int, prof: Profession) -> list[HeldCert]:
        rng = self.rng
        held: list[HeldCert] = []
        all_certs = {cert.key: cert for p in self.professions.values() for cert in p.certs}
        wanted = list(plan.certs_hold)
        chance = {"polished": 0.6, "average": 0.45, "poor": 0.3}[self.quality]
        for cert in prof.certs:
            if cert.key not in wanted and cert.key not in plan.certs_avoid and rng.random() < chance / 2 and plan.seniority != "intern":
                wanted.append(cert.key)
        for key in wanted:
            cert = all_certs[key]
            if cert.key in plan.certs_avoid or set(cert_skills(cert.name, cert.skills)) & plan.exclude:
                continue
            low = max(cert.since, grad // 12 - 1)
            year = rng.randint(min(low, self.as_of_year), self.as_of_year)
            held.append(HeldCert(cert.key, cert.name, cert.issuer, year, cert_skills(cert.name, cert.skills)))
        return held

    # ------------------------------------------------------------------------------------------ skills & bullets
    def choose_stack(self, plan: Plan) -> None:
        rng = self.rng
        prof = self.professions[plan.profession]
        self.home = prof.key
        if not prof.stacks:
            return
        scored = sorted(prof.stacks.items(), key=lambda item: (-len(set(item[1]) & set(plan.include)), rng.random()))
        stack_name = scored[0][0]
        if len(set(scored[0][1]) & plan.exclude) > len(scored[0][1]) / 2:
            clean = [name for name, skills in prof.stacks.items() if not set(skills) & plan.exclude]
            stack_name = rng.choice(clean) if clean else stack_name
        self.stack_name = stack_name
        self.title_ok = set(prof.stacks[stack_name]) | set(plan.include)

    def assign_skills(self, plan: Plan, roles: list[Role], persona: Persona, certs: list[HeldCert]) -> tuple[str | None, list[str], list[Project]]:
        """Choose skills per role, render bullets, return (stack, listed-only skills, academic projects)."""
        rng = self.rng
        prof = self.professions[plan.profession]
        stack_name = self.stack_name
        stack = [s for s in (prof.stacks.get(stack_name, ()) if stack_name else ()) if s not in plan.exclude]
        must = dict(plan.include)
        relevant = [role for role in roles if role.profession in (plan.rel_professions or (plan.profession,)) or role.profession == plan.profession]
        relevant = [role for role in relevant if role.kind != "freelance"] + [role for role in relevant if role.kind == "freelance"]
        # Place must-have skills on the most recent relevant roles until the required months are covered.
        placements: dict[int, set[str]] = {id(role): set() for role in roles}
        for name, need in must.items():
            covered = 0
            for role in sorted(relevant, key=lambda r: -r.end_or(self.as_of)):
                spec = spec_for(name, [self.professions[role.profession]])
                if spec is None or spec.since > role.end_or(self.as_of) // 12:
                    continue  # a skill outside the role's own profession is only listed, never invented into its bullets
                placements[id(role)].add(name)
                covered += role.end_or(self.as_of) - role.start + 1
                if covered >= max(need, 1) and (need > 0 or rng.random() < 0.6):
                    break
        for role in roles:
            role_prof = self.professions[role.profession]
            base = stack if role.profession == plan.profession else []
            pool = [s.name for s in role_prof.skills if s.since <= role.end_or(self.as_of) // 12 and s.name not in plan.exclude]
            core = [name for name in base if name in pool]
            extra = [name for name in pool if name not in core]
            months = role.end_or(self.as_of) - role.start + 1
            k = 2 if role.kind in {"internship", "freelance"} else min(9, 3 + months // 10)
            k = max(2, min(k, rng.randint(3, 8)))
            chosen = core[:max(1, int(k * 0.7))] if core else []
            rng.shuffle(extra)
            for name in extra:
                if len(chosen) >= k:
                    break
                if name not in chosen and (role.profession != plan.profession or name not in must or id(role) in placements and name in placements[id(role)]):
                    chosen.append(name)
            # Must-haves that were not placed here are removed from this role (so months stay exact) unless placed.
            chosen = [name for name in chosen if name not in must or name in placements[id(role)]]
            for name in sorted(placements[id(role)]):
                if name not in chosen:
                    chosen.append(name)
            if role.profession == plan.profession and prof.soft and rng.random() < 0.3 and role.kind == "full_time":
                soft = rng.choice(prof.soft)
                if soft not in plan.exclude and soft not in chosen:
                    chosen.append(soft)
            role.skills = list(dict.fromkeys(chosen))
            self._bullets(role, persona, plan)
        # Skills section: used skills plus a few listed-only ones (common, and a source of "unverified" claims).
        used = set().union(*(role.mentioned() for role in roles)) if roles else set()
        listable = [s.name for s in prof.skills if s.name not in used and s.name not in plan.exclude and s.since <= self.as_of_year]
        rng.shuffle(listable)
        listed_only = listable[:rng.randint(1, 5)] if listable else []
        listed_only += [name for name in plan.listed_extra if name not in used and name not in listed_only and name not in plan.exclude]
        for name, need in must.items():  # must-haves that found no role (freshers, other professions) are listed
            if name not in used and name not in listed_only and need == 0:
                listed_only.append(name)
        projects = self._academic_projects(plan, persona, used | set(listed_only))
        return stack_name, listed_only, projects

    def _pool(self, role: Role, prof: Profession) -> dict[str, list[str]]:
        """Category -> skills for template slots. Only the role's own profession (and generic skills) give a skill its
        category, so a nurse's "Patient Care" can never fill a teacher template's {core} slot."""
        own = self.professions[role.profession]
        pool: dict[str, list[str]] = {"any": [n for n in role.skills if spec_for(n, [own])]}
        for name in role.skills:
            spec = spec_for(name, [own])
            pool.setdefault(spec.cat if spec else "foreign", []).append(name)
        return pool

    def _specs(self, role: Role):
        order = [self.professions[role.profession]] + list(self.professions.values())
        return lambda name: spec_for(name, order)

    def _bullets(self, role: Role, persona: Persona, plan: Plan) -> None:
        rng, quality = self.rng, self.quality
        prof = self.professions[role.profession]
        pool = self._pool(role, prof)
        specs = self._specs(role)
        months = role.end_or(self.as_of) - role.start + 1
        if role.kind == "internship":
            target = rng.randint(1, 3)
        elif role.kind == "freelance":
            target = rng.randint(1, 2)
        else:
            recent = role.end is None or role.end >= self.as_of - 24
            target = rng.randint(3, 6) if recent else rng.randint(2, 4)
            if quality == "polished":
                target += 1
            if months < 8:
                target = min(target, 2)
        weak_rate = {"polished": 0.0, "average": 0.15, "poor": 0.45}[quality]
        templates = list(prof.bullets) + [text for text, _ in prof.fixed]
        rng.shuffle(templates)
        templates.sort(key=lambda t: t in self.used_templates)  # templates already used by another role go last
        rendered: set[str] = set(role.title_skills)
        bullets: list[Bullet] = []
        attempts = 0
        queue = list(templates)  # unused-by-other-roles first
        while len(bullets) < target and attempts < 80:
            attempts += 1
            missing = set(role.skills) - rendered
            weak = rng.random() < weak_rate
            if weak:
                template = rng.choice(prof.weak)
            elif queue:
                template = queue.pop(0 if rng.random() < 0.75 else rng.randrange(len(queue)))
            else:
                break
            filled = fill(template, rng, pool=pool, specs=specs, quality=quality, indian=persona.indian, as_of_year=self.as_of_year,
                          excluded=plan.exclude, prefer=missing)
            if filled is None:
                continue
            if any(bullet.text == filled.text for bullet in bullets):
                continue
            new_skills = [name for name in filled.skills if name not in role.skills]
            if new_skills and (role.profession != plan.profession and set(new_skills) & set(plan.include)):
                continue  # never let an inline declaration put a must-have into an unrelated role
            if set(filled.skills) & set(plan.include) - set(role.skills):
                continue  # must-have skills appear only in the roles they were placed in
            text = filled.text
            if quality == "poor" and rng.random() < 0.15 and not filled.skills:
                text = rng.choice(WEAK_VERBS) + " " + text[0].lower() + text[1:]
            bullets.append(Bullet(finish_sentence(text, rng, quality), filled.skills, dict(filled.surfaces)))
            rendered.update(filled.skills)
            self.used_templates.add(template)
        if quality == "poor" and len(bullets) >= 2 and rng.random() < 0.3:  # run-on bullet
            first, second = bullets[0], bullets[1]
            joined = f"{first.text.rstrip('.')} and also {second.text[0].lower() + second.text[1:]}"
            bullets[:2] = [Bullet(joined, tuple(dict.fromkeys(first.skills + second.skills)), {**second.surfaces, **first.surfaces})]
        role.bullets = bullets
        for bullet in bullets:
            for name in bullet.skills:
                if name not in role.skills:
                    role.skills.append(name)
        missing = [name for name in role.skills if name not in rendered and name not in role.title_skills]
        missing = [name for name in missing if not any(name in bullet.skills for bullet in bullets)]
        for name in [n for n in missing if n in SOFT_SENTENCES]:  # soft skills read as a sentence, not as a "tool"
            shown = SOFT_SENTENCES[name][0]
            text = SOFT_SENTENCES[name][1].format(shown=shown, n=rng.randint(2, 9))
            role.bullets.append(Bullet(finish_sentence(text, rng, quality), (name,), {name: shown}))
            missing.remove(name)
        if missing:
            surfaces = [surface(rng, specs(name), name, quality) for name in missing]
            if prof.env_line or rng.random() < 0.5:
                role.env = list(missing)
                role.env_surfaces = surfaces
            else:
                text = rng.choice(("Tools used: {}", "Worked with {}", "Key tools: {}", "Used {} day to day")).format(", ".join(surfaces))
                role.bullets.append(Bullet(text, tuple(missing), dict(zip(missing, surfaces))))

    def _academic_projects(self, plan: Plan, persona: Persona, available: set[str]) -> list[Project]:
        rng, prof = self.rng, self.professions[plan.profession]
        if plan.seniority not in {"intern", "fresher", "junior"} or prof.family not in {"tech", "engineering", "design", "product"}:
            return []
        names = list(prof.projects) or ["Final Year Project", "Mini Project"]
        rng.shuffle(names)
        projects = []
        pool_names = [name for name in sorted(available) if name not in plan.exclude] or [s.name for s in prof.skills if s.name not in plan.exclude][:4]
        for name in names[:rng.randint(1, 3)]:
            skills = rng.sample(pool_names, min(len(pool_names), rng.randint(2, 3)))
            specs = lambda n: spec_for(n, [prof] + list(self.professions.values()))  # noqa: E731
            surfaces = [surface(rng, specs(s), s, self.quality) for s in skills]
            what = rng.choice(("Built a {} as the final-year project using {}.", "Developed a {} with {} as a team of {}.",
                               "Designed and implemented a {} using {}; demoed to faculty and industry mentors."))
            body = what.format(name.lower() if rng.random() < 0.5 else name, " and ".join(surfaces[:2]) if len(surfaces) > 1 else surfaces[0],
                               rng.randint(2, 4)) if "team of" in what else what.format(name, ", ".join(surfaces))
            projects.append(Project(name, None, None, None, None, None, [], [Bullet(body, tuple(skills), dict(zip(skills, surfaces)))]))
        return projects

    def achievements(self, persona: Persona) -> list[str]:
        rng = self.rng
        picked = rng.sample(ACHIEVEMENTS, rng.randint(0, 3))
        return [text.format(year=self.as_of_year - rng.randint(1, 6), q=rng.randint(1, 4), n=rng.choice((300, 800, 1500))) for text in picked]


def cert_skills(name: str, declared: tuple[str, ...]) -> tuple[str, ...]:
    """Skills a certification name mentions: its declared skills plus any taxonomy skill the name spells out."""
    taxonomy = load_taxonomy()
    found = [skill.name for skill, _ in taxonomy.mentions(name)] if taxonomy else []
    return tuple(dict.fromkeys(list(declared) + found))


def compatible(bachelor: Degree, masters: Degree) -> bool:
    """Whether a postgraduate or professional qualification plausibly follows this first degree."""
    if masters.family == "business administration" or masters.key in {"bed", "dled", "ca", "cma", "cs", "phd_edu"}:
        return True
    if masters.key == "msc_nursing":
        return bachelor.key == "bsc_nursing"
    if masters.key == "mca":
        return bachelor.family in {"computer science", "mathematics", "statistics", "physics"}
    if masters.family in {"data science", "statistics"}:
        return bachelor.family in {"computer science", "statistics", "mathematics", "electronics", "physics"}
    if masters.family == "computer science":
        return bachelor.family in {"computer science", "electronics", "electrical", "mathematics"}
    return masters.family == bachelor.family


def _edu_meets(degree: Degree, level: str | None, fields: tuple[str, ...]) -> bool:
    level_ok = level is None or LEVEL_RANK[degree.level] >= LEVEL_RANK[level]
    field_ok = not fields or degree.family in fields
    return level_ok and field_ok


def edu_meets(records: list[Education], level: str | None, fields: tuple[str, ...]) -> bool:
    return any(record.degree is not None and not record.pursuing and _edu_meets(record.degree, level, fields) for record in records)


def build_candidate(rng: random.Random, plan: Plan, persona: Persona, professions: dict[str, Profession], as_of: int, quality: str) -> Candidate:
    builder = CareerBuilder(rng, professions, as_of, quality)
    builder.choose_stack(plan)
    roles, gaps, shape, grad = builder.timeline(plan, persona)
    education = builder.education(plan, persona, grad)
    prof = professions[plan.profession]
    certs = builder.certifications(plan, grad, prof)
    stack, listed_only, projects = builder.assign_skills(plan, roles, persona, certs)
    return Candidate(persona=persona, profession=plan.profession, seniority=plan.seniority, stack=stack, roles=roles, gaps=gaps,
                     education=education, certs=certs, listed_only=listed_only, projects=projects, achievements=builder.achievements(persona),
                     shape=sorted(set(shape)), excluded=plan.exclude)


def relevant_months(candidate: Candidate, professions: tuple[str, ...], as_of: int) -> int:
    return union_months([(role.start, role.end_or(as_of)) for role in candidate.roles if role.profession in professions])


def skill_months(candidate: Candidate, name: str, as_of: int) -> int:
    return union_months([(role.start, role.end_or(as_of)) for role in candidate.roles if name in role.mentioned()])
