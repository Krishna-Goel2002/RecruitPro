"""Corpus orchestration: jobs -> per-resume fit plan -> career -> document -> gold -> rendered file -> manifest."""
from __future__ import annotations

import json
import os
import random
import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from resumelab.career import Plan, build_candidate, expand_exclusions
from resumelab.compose import TEMPLATES, Composer, Document
from resumelab.data.education import DEGREES
from resumelab.data.professions import Profession, registry
from resumelab.gold import build_gold
from resumelab.jobs import LABELS, Job, build_jobs, label_for, requirement_met
from resumelab.model import Candidate, ordinal
from resumelab.people import make_persona

DEFAULT_FORMAT_WEIGHTS = {"pdf": 55, "docx": 30, "scan": 10, "txt": 5}  # the backend accepts pdf/doc/docx only
LABEL_SHARES = {"strong": 0.2, "good": 0.25, "partial": 0.25, "weak": 0.15, "unrelated": 0.15}
QUALITY_WEIGHTS = {"polished": 35, "average": 40, "poor": 25}
ADVERSARIAL_WEIGHTS = {"hidden_keywords": 3, "prompt_injection": 3, "inflated_claims": 3, "duplicate_sections": 2, "very_long": 1,
                       "near_empty": 1, "non_english_section": 1, "photo_placeholder": 2}
AGE_BY_LEVEL = {"intern": (19, 22), "fresher": (21, 24), "junior": (23, 28), "mid": (26, 33), "senior": (30, 40), "lead": (35, 50)}
LEVEL_ORDER = ("intern", "fresher", "junior", "mid", "senior", "lead")


@dataclass
class Options:
    out: Path
    count: int
    seed: int
    professions: list[str] | None = None
    formats: list[str] | None = None
    adversarial_rate: float = 0.1
    llm: str = "none"
    as_of: str | None = None  # YYYY-MM
    jobs: int | None = None
    clean: bool = True


def _as_of(value: str | None) -> int:
    if value:
        year, month = value.split("-")
        return ordinal(int(year), int(month))
    now = datetime.now(timezone.utc)
    return ordinal(now.year, now.month)


def _pick_job_professions(rng: random.Random, keys: list[str], professions: dict[str, Profession], count: int) -> list[str]:
    if count <= len(keys):
        families: dict[str, list[str]] = {}
        for key in keys:
            families.setdefault(professions[key].family, []).append(key)
        order = list(families)
        rng.shuffle(order)
        picked: list[str] = []
        while len(picked) < count:
            for family in order:
                options = [k for k in families[family] if k not in picked]
                if options and len(picked) < count:
                    picked.append(rng.choice(options))
        return picked
    return [keys[i % len(keys)] for i in range(count)]


# --------------------------------------------------------------------------------------------- planning
def _seniority_for(rng: random.Random, job: Job, label: str) -> str:
    base = LEVEL_ORDER.index(job.level)
    if label in {"strong", "good"}:
        return LEVEL_ORDER[min(5, base + rng.choice((0, 0, 1)))]
    if label == "partial":
        return LEVEL_ORDER[max(1, base - rng.choice((0, 1, 1)))]
    return rng.choice(("intern", "fresher", "junior", "junior", "mid", "mid", "senior"))


def _profession_for(rng: random.Random, job: Job, label: str, professions: dict[str, Profession]) -> str:
    prof = professions[job.profession]
    if label in {"strong", "good"}:
        return prof.key
    if label == "partial":
        return prof.key if rng.random() < 0.7 or not prof.related else rng.choice(prof.related)
    if label == "weak":
        return rng.choice(prof.related) if prof.related and rng.random() < 0.7 else prof.key
    others = [p.key for p in professions.values() if p.family != prof.family and p.key not in prof.related]
    return rng.choice(others)


def plan_for(rng: random.Random, job: Job, label: str, professions: dict[str, Profession], as_of: int, adversarial: set[str]) -> Plan:
    profession = _profession_for(rng, job, label, professions)
    seniority = _seniority_for(rng, job, label)
    cand_prof = professions[profession]
    scorable = [i for i, r in enumerate(job.reqs) if r.type != "OTHER"]

    def feasible(index: int) -> bool:
        req = job.reqs[index]
        if req.type == "EXPERIENCE":
            return profession in req.exp_professions and seniority not in {"intern"}
        if req.type == "EDUCATION":
            options = [DEGREES[k] for k, _ in cand_prof.degrees] + [DEGREES[k] for k in cand_prof.masters]
            return any((req.level is None or _rank(d.level) >= _rank(req.level)) and (not req.fields or d.family in req.fields) for d in options)
        if req.type == "CERTIFICATION":
            return profession == job.profession or req.cert in {c.key for c in cand_prof.certs}
        if req.type == "SKILL":
            return label not in {"unrelated"} or rng.random() < 0.15
        return False

    def cannot_fail(index: int) -> bool:
        req = job.reqs[index]
        if req.type != "EDUCATION":
            return False
        own = [DEGREES[k] for k, _ in cand_prof.degrees]
        return all((req.level is None or _rank(d.level) >= _rank(req.level)) and (not req.fields or d.family in req.fields) for d in own)

    forced = {i for i in scorable if cannot_fail(i)}
    chosen: set[int] = set()
    for _ in range(300):
        subset = forced | {i for i in scorable if feasible(i) and rng.random() < {"strong": 0.97, "good": 0.75, "partial": 0.5, "weak": 0.25,
                                                                           "unrelated": 0.05}[label]}
        met = {i: (i in subset) for i in scorable}
        lab, coverage, knockouts = label_for(job, met)
        if lab == label:
            chosen = subset
            break
    else:
        chosen = ({i for i in scorable if feasible(i)} if label in {"strong", "good"} else set()) | forced
    include: dict[str, int] = {}
    exclude: set[str] = set()
    rel_months = None
    rel_professions: tuple[str, ...] = (profession,)
    edu = None
    certs_hold: list[str] = []
    certs_avoid: set[str] = set()
    for index in scorable:
        req = job.reqs[index]
        ok = index in chosen
        if req.type == "SKILL":
            need = {"ADVANCED": 36, "EXPERT": 60}.get(job.proficiency.get(index, ""), 0)
            options = [req.groups] + [((alt,),) for alt in req.alternatives]
            if ok:
                groups = rng.choice(options) if rng.random() < 0.3 else options[0]
                for group in groups:
                    include[rng.choice(group)] = need
            else:
                for groups in options:
                    victims = [rng.choice(groups)] if len(groups) > 1 and rng.random() < 0.6 else list(groups)
                    for group in victims:
                        exclude.update(group)
        elif req.type == "EXPERIENCE":
            need = (job.need_years(req) or 1) * 12
            rel_professions = req.exp_professions
            rel_months = (need, need + rng.randint(6, 60)) if ok else (max(0, need - rng.randint(6, 30)), need - 1)
        elif req.type == "EDUCATION":
            edu = ("ok" if ok else "fail", req.level, req.fields)
        elif req.type == "CERTIFICATION":
            if ok:
                certs_hold.append(req.cert)
            else:
                certs_avoid.update({req.cert, *req.cert_alternatives})
    exclude -= set(include)
    excluded = expand_exclusions(exclude) - set(include)
    if rel_months and seniority in {"intern", "fresher"} and rel_months[0] > 8:
        seniority = "junior"
    if rel_months:
        lo, hi = rel_months
        seniority = _level_for_months(hi if label in {"strong", "good"} else lo, seniority)
    listed_extra: tuple[str, ...] = ()
    if "inflated_claims" in adversarial:
        claims = [name for i, r in enumerate(job.reqs) if r.type == "SKILL" for group in r.groups for name in group]
        listed_extra = tuple(rng.sample(claims, min(len(claims), rng.randint(2, 4))))
        excluded = excluded - set(listed_extra)
    return Plan(profession=profession, seniority=seniority, rel_professions=rel_professions, rel_months=rel_months, include=include,
                exclude=frozenset(excluded), edu=edu, certs_hold=tuple(certs_hold), certs_avoid=frozenset(certs_avoid), listed_extra=listed_extra)


def _rank(level: str) -> int:
    from resumelab.data.education import LEVEL_RANK
    return LEVEL_RANK[level]


def _level_for_months(months: int, fallback: str) -> str:
    if months <= 0:
        return fallback if fallback in {"intern", "fresher"} else "fresher"
    if months < 12:
        return "fresher" if fallback != "intern" else "intern"
    if months < 36:
        return "junior"
    if months < 78:
        return "mid"
    if months < 126:
        return "senior"
    return "lead"


# --------------------------------------------------------------------------------------------- one resume
def _choose_template(rng: random.Random, fmt: str, cand: Candidate, quality: str, professions: dict[str, Profession]) -> str:
    fmt_key = "pdf" if fmt == "scan" else ("docx" if fmt == "doc" else fmt)
    options = [name for name, (formats, _) in TEMPLATES.items() if fmt_key in formats or (fmt == "scan" and "scan" in formats)]
    weights = []
    family = professions[cand.profession].family
    has_postgrad = any(r.level in {"MASTER", "DOCTORATE"} for r in cand.education)
    for name in options:
        w = 3.0
        if name == "fresher_indian":
            w = 12 if cand.seniority in {"intern", "fresher"} and cand.persona.indian else (0.5 if cand.seniority == "junior" else 0)
        elif name == "academic_cv":
            w = 6 if has_postgrad and family in {"tech", "education", "health", "legal"} and cand.seniority not in {"intern", "fresher"} else 0.2
        elif name == "indian_traditional":
            w = (6 if quality != "polished" else 1) if cand.persona.indian and family in {"health", "education", "engineering", "business", "legal"} else \
                (1.5 if cand.persona.indian else 0.2)
        elif name in {"modern_sidebar", "creative_color"}:
            w = 4 if family in {"design", "tech", "product", "business"} else 2
        elif name == "ats_classic":
            w = 5
        elif name == "table_grid":
            w = 1.8
        elif name == "classic_serif":
            w = 2.2
        elif name == "compact_dense":
            w = 3.2
        weights.append(w)
    if sum(weights) <= 0:
        return options[0]
    return rng.choices(options, weights=weights)[0]


def _adversarial(rng: random.Random, rate: float, fmt: str) -> set[str]:
    if rng.random() >= rate:
        return set()
    kinds = dict(ADVERSARIAL_WEIGHTS)
    if fmt in {"scan"}:
        kinds.pop("hidden_keywords"), kinds.pop("prompt_injection")
    if fmt == "txt":
        kinds.pop("hidden_keywords"), kinds.pop("photo_placeholder")
    picked = {rng.choices(list(kinds), weights=list(kinds.values()))[0]}
    if rng.random() < 0.2:
        picked.add(rng.choices(list(kinds), weights=list(kinds.values()))[0])
    if "near_empty" in picked:
        picked = {"near_empty"}
    return picked


def _fit(jobs: list[Job], evidence) -> dict:
    fit = {}
    for job in jobs:
        met = {i: requirement_met(job, i, evidence) for i in range(len(job.reqs))}
        label, coverage, knockouts = label_for(job, met)
        fit[job.key] = {"label": label, "expectedRankingGroup": "RANKED" if knockouts else "MUST_HAVES_UNVERIFIED",
                        "metRequirements": sorted(i for i, value in met.items() if value), "coverage": round(coverage, 3)}
    return fit


def build_one(index: int, opts: Options, jobs: list[Job], target_job: Job, target_label: str, professions: dict[str, Profession],
              as_of: int, fmt: str, used_names: set[str] | None = None) -> tuple[dict, Document, Candidate, list[Job]]:
    rng = random.Random(f"resumelab:{opts.seed}:{index}")
    quality = rng.choices(list(QUALITY_WEIGHTS), weights=list(QUALITY_WEIGHTS.values()))[0]
    adversarial = _adversarial(rng, opts.adversarial_rate, fmt)
    best = None
    for attempt in range(25):
        plan = plan_for(rng, target_job, target_label, professions, as_of, adversarial)
        if "very_long" in adversarial:
            plan.shape = ("very_long",)
        prof = professions[plan.profession]
        age = rng.randint(*AGE_BY_LEVEL[plan.seniority])
        persona = make_persona(rng, prof.family, as_of // 12, age, used_names)
        cand = build_candidate(rng, plan, persona, professions, as_of, quality)
        if "very_long" in adversarial:
            _lengthen(rng, cand, professions, quality, as_of)
        template = _choose_template(rng, fmt, cand, quality, professions)
        composer = Composer(rng, professions, as_of)
        job_skills = [name for r in target_job.reqs if r.type == "SKILL" for group in r.groups for name in group]
        doc = composer.compose(cand, template, quality, adversarial, job_skills)
        gold, evidence = build_gold(doc, cand, as_of)
        fit = _fit(jobs, evidence)
        best = (gold, fit, doc, cand, template, quality, adversarial)
        if fit[target_job.key]["label"] == target_label or "near_empty" in adversarial:
            break
    gold, fit, doc, cand, template, quality, adversarial = best
    persona = cand.persona
    entry = {
        "id": f"r{index + 1:04d}", "format": fmt, "template": template, "profession": cand.profession, "seniority": cand.seniority,
        "quality": quality, "adversarial": sorted(adversarial),
        "persona": {"firstName": persona.first, "lastName": persona.last, "email": persona.email, "phone": persona.phone, "city": persona.city,
                    "gender": persona.gender},
        "gold": gold, "fit": fit, "targetJob": target_job.key, "targetLabel": target_label, "careerShape": cand.shape,
    }
    return entry, doc, cand, jobs


def _lengthen(rng: random.Random, cand: Candidate, professions: dict[str, Profession], quality: str, as_of: int) -> None:
    """Very long resumes: every role gets many more bullets (re-using its own skills, so gold stays exact)."""
    from resumelab.career import CareerBuilder

    builder = CareerBuilder(rng, professions, as_of, quality)
    plan = Plan(profession=cand.profession, seniority=cand.seniority, exclude=cand.excluded)
    for role in cand.roles:
        before = list(role.bullets)
        for _ in range(3):
            builder._bullets(role, cand.persona, plan)
            seen = {" ".join(x.text.split()[:4]).lower() for x in before}
            before += [b for b in role.bullets if " ".join(b.text.split()[:4]).lower() not in seen]
        role.bullets = before
        mapping = dict(zip(role.env, role.env_surfaces))
        role.env = [name for name in role.env if not any(name in b.skills for b in role.bullets)]
        role.env_surfaces = [mapping.get(name, name) for name in role.env]


# --------------------------------------------------------------------------------------------- corpus
def generate(opts: Options, log=print) -> dict:
    from resumelab.render import render_document

    professions = registry()
    keys = opts.professions or list(professions)
    unknown = [k for k in keys if k not in professions]
    if unknown:
        raise SystemExit(f"unknown profession(s): {', '.join(unknown)}; known: {', '.join(professions)}")
    formats = opts.formats or list(DEFAULT_FORMAT_WEIGHTS)
    as_of = _as_of(opts.as_of)
    master = random.Random(f"resumelab:{opts.seed}:master")
    n_jobs = opts.jobs or max(1, min(len(keys) if opts.professions else 8, round(opts.count / 15)))
    jobs = build_jobs(master, professions, _pick_job_professions(master, keys, professions, n_jobs), n_jobs)
    # Label quota per job
    per_job: dict[str, list[str]] = {}
    for j, job in enumerate(jobs):
        n = len(range(j, opts.count, len(jobs)))
        labels = []
        for label, share in LABEL_SHARES.items():
            labels += [label] * round(n * share)
        while len(labels) < n:
            labels.append(master.choice(LABELS))
        labels = labels[:n]
        master.shuffle(labels)
        per_job[job.key] = labels
    out = opts.out
    if opts.clean and (out / "resumes").exists():
        shutil.rmtree(out / "resumes")
    (out / "resumes").mkdir(parents=True, exist_ok=True)
    format_weights = [DEFAULT_FORMAT_WEIGHTS.get(f, 10) for f in formats]
    entries = []
    counters: dict[str, int] = {job.key: 0 for job in jobs}
    used_names: set[str] = set()
    llm = None
    if opts.llm and opts.llm != "none":
        from resumelab.llm import Rephraser
        llm = Rephraser(opts.llm, log=log)
    for index in range(opts.count):
        job = jobs[index % len(jobs)]
        label = per_job[job.key][counters[job.key]]
        counters[job.key] += 1
        fmt = random.Random(f"resumelab:{opts.seed}:{index}:fmt").choices(formats, weights=format_weights)[0]
        entry, doc, cand, _ = build_one(index, opts, jobs, job, label, professions, as_of, fmt, used_names)
        used_names.add(cand.persona.full_name)
        if llm is not None:
            llm.rephrase(doc)
            entry["gold"] = _regold(doc, cand, as_of, entry["gold"])
            entry["llm"] = opts.llm
        ext, actual_fmt = render_document(doc, fmt, out / "resumes" / entry["id"], seed=f"{opts.seed}:{index}")
        entry["format"] = actual_fmt
        entry["file"] = f"resumes/{entry['id']}.{ext}"
        entries.append({k: entry[k] for k in ("id", "file", "format", "template", "profession", "seniority", "quality", "adversarial", "persona",
                                              "gold", "fit", "targetJob", "targetLabel", "careerShape") if k in entry})
        if (index + 1) % 20 == 0:
            log(f"  {index + 1}/{opts.count} resumes")
    generated_at = os.environ.get("SOURCE_DATE_EPOCH")
    stamp = datetime.fromtimestamp(int(generated_at), timezone.utc) if generated_at else datetime.now(timezone.utc)
    manifest = {"seed": opts.seed, "generatedAt": stamp.strftime("%Y-%m-%dT%H:%M:%SZ"), "asOf": f"{as_of // 12:04d}-{as_of % 12 + 1:02d}",
                "count": len(entries), "generator": "resumelab 1.0.0", "resumes": entries}
    (out / "jobs.json").write_text(json.dumps([job.payload for job in jobs], indent=2, ensure_ascii=False), encoding="utf-8")
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    return manifest


def _regold(doc: Document, cand: Candidate, as_of: int, previous: dict) -> dict:
    gold, _ = build_gold(doc, cand, as_of)
    return gold


__all__ = ["Options", "generate"]
