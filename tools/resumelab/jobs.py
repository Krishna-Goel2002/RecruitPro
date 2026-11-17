"""Job postings (backend JobCreateRequest + RequirementInput v2 shape) and gold requirement evaluation."""
from __future__ import annotations

import random
from dataclasses import dataclass

from resumelab.data.employers import EMPLOYERS
from resumelab.data.professions import JOB_LEVEL, JobT, Profession, ReqT
from resumelab.taxonomy import load as load_taxonomy

JOB_CITIES = ("Bengaluru, Karnataka", "Pune, Maharashtra", "Hyderabad, Telangana", "Gurugram, Haryana", "Mumbai, Maharashtra", "Chennai, Tamil Nadu",
              "Noida, Uttar Pradesh", "Kolkata, West Bengal", "Bhubaneswar, Odisha", "Ahmedabad, Gujarat", "Kochi, Kerala")
LABELS = ("strong", "good", "partial", "weak", "unrelated")


@dataclass
class Job:
    key: str
    profession: str
    level: str  # junior | mid | senior | lead
    template: JobT
    reqs: list[ReqT]
    proficiency: dict[int, str]  # requirement index -> proficiency
    payload: dict  # jobs.json entry

    def need_years(self, req: ReqT) -> int | None:
        return req.years.get(self.level) if req.years else None


def _req_payload(req: ReqT, level: str, proficiency: str | None) -> dict:
    minimum = req.years.get(level) if req.type == "EXPERIENCE" and req.years else None
    description = req.text
    if req.type == "EXPERIENCE" and minimum:
        first_word = req.text.split()[0]
        keep_case = any(ch.isupper() or ch.isdigit() for ch in first_word[1:])  # "B2B", "SaaS", "LL.B."
        lowered = req.text if keep_case else req.text[0].lower() + req.text[1:]
        description = f"{minimum}+ years: {lowered}" if not req.text[0].isdigit() else req.text
    return {"type": req.type, "description": description, "mandatory": req.mandatory, "knockout": bool(req.knockout and req.mandatory),
            "minimumYears": minimum, "alternatives": list(req.alternatives), "proficiency": proficiency}


def build_jobs(rng: random.Random, professions: dict[str, Profession], keys: list[str], count: int) -> list[Job]:
    jobs: list[Job] = []
    levels = ["mid", "senior", "junior", "mid", "lead", "senior", "junior"]
    for index in range(count):
        prof = professions[keys[index % len(keys)]]
        template = prof.jobs[(index // len(keys)) % len(prof.jobs)]
        level = levels[index % len(levels)] if index < len(levels) else rng.choice(("junior", "mid", "mid", "senior", "lead"))
        level = level if level in template.titles else "mid"
        reqs = list(template.reqs)
        proficiency: dict[int, str] = {}
        if level in {"senior", "lead"}:
            candidates = [i for i, r in enumerate(reqs) if r.type == "SKILL" and not r.knockout and len(r.groups) == 1 and not r.alternatives]
            if candidates:
                proficiency[rng.choice(candidates)] = "ADVANCED"
        company = rng.choice(EMPLOYERS[rng.choice(prof.sectors)])[0]
        city = rng.choice(JOB_CITIES)
        mode = rng.choice(("Hybrid", "On-site", "On-site", "Remote")) if prof.family in {"tech", "product", "design"} else "On-site"
        job_type = "CONTRACT" if rng.random() < 0.08 else ("PART_TIME" if prof.key in {"teacher", "customer_support"} and rng.random() < 0.1 else "FULL_TIME")
        title = template.titles[level]
        key = f"job-{index + 1:02d}-{prof.key.replace('_', '-')}-{level}"
        payload_reqs = [_req_payload(r, level, proficiency.get(i)) for i, r in enumerate(reqs)]
        exp_years = next((p["minimumYears"] for p in payload_reqs if p["type"] == "EXPERIENCE" and p["minimumYears"]), None)
        description = _description(template, title, company, city, mode, payload_reqs, exp_years)
        payload = {"key": key, "title": title, "department": template.department, "location": f"{city} ({mode})", "jobType": job_type,
                   "experienceLevel": JOB_LEVEL[level], "description": description, "vacancies": rng.choice((1, 1, 2, 2, 3, 5)),
                   "requirements": payload_reqs}
        jobs.append(Job(key, prof.key, level, template, reqs, proficiency, payload))
    return jobs


def _description(template: JobT, title: str, company: str, city: str, mode: str, reqs: list[dict], exp_years: int | None) -> str:
    must = [r["description"] for r in reqs if r["mandatory"] and r["type"] != "OTHER"]
    nice = [r["description"] for r in reqs if not r["mandatory"]]
    other = [r["description"] for r in reqs if r["type"] == "OTHER" and r["mandatory"]]
    parts = [f"{title} - {company}", "", template.about.format(company=company, city=city), "",
             f"Location: {city} ({mode})" + (f" | Experience: {exp_years}+ years" if exp_years else ""), "", "What you will do:"]
    parts += [f"- {duty}" for duty in template.duties]
    parts += ["", "What we are looking for:"] + [f"- {item}" for item in must + other]
    if nice:
        parts += ["", "Nice to have:"] + [f"- {item}" for item in nice]
    parts += ["", "What we offer:"] + [f"- {perk}" for perk in template.perks]
    parts += ["", f"{company} is an equal opportunity employer. We welcome applicants of every gender, religion, caste, disability and background."]
    return "\n".join(parts)


# --------------------------------------------------------------------------------------------- gold evaluation
@dataclass
class Evidence:
    """What a reader can establish from one rendered resume (built from the Document)."""

    skills: dict[str, int | None]  # canonical name -> months (None = listed only)
    role_intervals: list[tuple[int, int, str]]  # (start, end, profession)
    degrees: list  # Education records (completed)
    certs: set[str]


def _held(name: str, skills: dict[str, int | None]) -> int | None | bool:
    """Months for ``name`` (or a taxonomy descendant), None if only listed, False if absent."""
    if name in skills:
        return skills[name]
    taxonomy = load_taxonomy()
    if taxonomy is None:
        return False
    target = taxonomy.resolve(name)
    if target is None:
        return False
    best: int | None | bool = False
    for held, months in skills.items():
        skill = taxonomy.resolve(held)
        if skill and target.id in taxonomy.ancestors(skill.id):
            if best is False or (months or 0) > (best or 0):
                best = months
    return best


def requirement_met(job: Job, index: int, evidence: Evidence) -> bool | None:
    """Gold status of one requirement for one resume; None for OTHER (never scored from a resume)."""
    from resumelab.career import _edu_meets  # local import: career imports this module's peers
    from resumelab.model import union_months

    req = job.reqs[index]
    if req.type == "OTHER":
        return None
    if req.type == "SKILL":
        options = [req.groups] + [((alt,),) for alt in req.alternatives]
        need = {"ADVANCED": 36, "EXPERT": 60}.get(job.proficiency.get(index, ""), 0)
        for groups in options:
            ok = True
            for group in groups:
                held = [_held(name, evidence.skills) for name in group]
                if not any(h is not False and (need == 0 or (h or 0) >= need) for h in held):
                    ok = False
                    break
            if ok:
                return True
        return False
    if req.type == "EXPERIENCE":
        need = (job.need_years(req) or 0) * 12
        months = union_months([(s, e) for s, e, p in evidence.role_intervals if p in req.exp_professions])
        return months >= need and months > 0
    if req.type == "EDUCATION":
        return any(record.degree is not None and _edu_meets(record.degree, req.level, req.fields) for record in evidence.degrees)
    if req.type == "CERTIFICATION":
        return bool({req.cert, *req.cert_alternatives} & evidence.certs)
    return None


def label_for(job: Job, met: dict[int, bool | None]) -> tuple[str, float, bool]:
    """(label, weighted coverage, knockouts met). Weights: mandatory 2, optional 1; OTHER excluded."""
    total = got = 0
    knockouts_ok = True
    for index, req in enumerate(job.reqs):
        if req.type == "OTHER":
            continue
        weight = 2 if req.mandatory else 1
        total += weight
        if met.get(index):
            got += weight
        elif req.knockout and req.mandatory:
            knockouts_ok = False
    coverage = got / total if total else 0.0
    if knockouts_ok and coverage >= 0.9:
        label = "strong"
    elif knockouts_ok and coverage >= 0.65:
        label = "good"
    elif coverage >= 0.4 or (knockouts_ok and coverage > 0):
        label = "partial"
    elif coverage >= 0.2:
        label = "weak"
    else:
        label = "unrelated"
    return label, coverage, knockouts_ok
