"""Evaluation harness: ``python -m eval.run [--check] [--per-domain N] [--seed S] [--json FILE]``.

Generates the seeded synthetic gold set, runs every document through the real parser (file bytes
in, ``ParseResponse`` out) and the matcher, and reports skill-extraction precision/recall/F1,
date accuracy, requirement agreement (Cohen's kappa over MATCHED/UNCLEAR/NOT_OBSERVED), ranking
NDCG@10, the name/gender counterfactual flip rate and the adverse-impact ratio. Requirement
proficiency (evidence-ladder-4) is checked twice: hand-labelled cases (``eval.proficiency``) and an
invariant over the synthetic set (re-matching every SKILL requirement as ADVANCED and EXPERT may only
turn MATCHED into UNCLEAR, never anything into NOT_OBSERVED). ``--check`` exits
with status 1 when a metric misses its threshold.

Synthetic data is far cleaner than real resumes, so these numbers overstate real-world accuracy;
a recruiter-labelled real set must replace this one before accuracy is quoted (see README).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from dataclasses import replace

from app.core.config import load_settings
from app.matching.engine import match_requirements
from app.models.matching import MatchFact, MatchRequest, MatchRequirement, MatchResponse
from app.models.resume import ParseResponse
from app.pipeline.education import LEVELS, RELATED_FIELDS
from app.pipeline.extract import parse_resume
from app.taxonomy.taxonomy import get_taxonomy
from eval.domains import DOMAINS, JobRequirementSpec, JobSpec
from eval.generator import EVAL_TODAY, SyntheticResume, generate, render
from eval.metrics import adverse_impact_ratio, cohen_kappa, ndcg_at_k, precision_recall_f1
from eval.proficiency import CASES as PROFICIENCY_CASES, request as proficiency_request

DEFAULT_SEED = 20261108
DEFAULT_PER_DOMAIN = 26
SELECTION_THRESHOLD = 60.0  # verified coverage counted as "advanced" for the adverse-impact ratio
CROSS_DOMAIN_PER_JOB = 8
THRESHOLDS = {  # metric: (comparison, threshold)
    "skill_f1": (">=", 0.85),
    "requirement_kappa": (">=", 0.70),
    "ndcg_at_10": (">=", 0.80),
    "name_swap_flip_rate": ("<=", 0.0),
    "adverse_impact_ratio": (">=", 0.80),
    "date_accuracy": (">=", 0.90),
    "proficiency_case_accuracy": (">=", 1.0),
    "proficiency_invariant_violations": ("<=", 0),
}


# ------------------------------------------------------------------------------------- parsing
def _parse_one(resume: SyntheticResume) -> ParseResponse:
    filename, content = render(resume)
    return parse_resume(filename, content, settings=replace(load_settings(), llm_provider="none"), today=EVAL_TODAY)


def _parse_all(resumes: list[SyntheticResume], workers: int) -> dict[str, ParseResponse]:
    if workers <= 1:
        return {resume.id: _parse_one(resume) for resume in resumes}
    with ProcessPoolExecutor(max_workers=workers) as pool:
        return dict(zip((resume.id for resume in resumes), pool.map(_parse_one, resumes, chunksize=8)))


# ------------------------------------------------------------------------------------ gold labels
def _covers(group: tuple[str, ...], skills: set[str] | frozenset[str]) -> bool:
    taxonomy = get_taxonomy()
    held = {taxonomy.lookup(name).id for name in skills}
    for name in group:
        required = taxonomy.lookup(name).id
        if required in held or any(taxonomy.is_ancestor(required, other) for other in held):
            return True
    return False


def _broader(group: tuple[str, ...], skills: set[str]) -> bool:
    taxonomy = get_taxonomy()
    held = {taxonomy.lookup(name).id for name in skills}
    return any(taxonomy.is_ancestor(other, taxonomy.lookup(name).id) for name in group for other in held)


def _union_months(intervals: list[tuple[int, int]]) -> int:
    covered: set[int] = set()
    for start, end in intervals:
        covered.update(range(start, end + 1))
    return len(covered)


def gold_status(spec: JobRequirementSpec, resume: SyntheticResume, domain_families) -> str:
    if spec.type == "SKILL":
        satisfied = [_covers(group, resume.gold_skills) for group in spec.groups]
        if all(satisfied):
            return "MATCHED"
        if any(satisfied) or any(_broader(group, resume.gold_skills) for group in spec.groups):
            return "UNCLEAR"
        return "NOT_OBSERVED"
    if spec.type == "EXPERIENCE":
        need = (spec.minimum_years or 0) * 12
        total = _union_months([(role.start, role.end) for role in resume.roles])
        if not spec.groups:
            return "MATCHED" if total >= need else "NOT_OBSERVED"
        group = spec.groups[0]
        tied = [role for role in resume.roles if _covers(group, role.skills | role.implied)]
        months = _union_months([(role.start, role.end) for role in tied])
        if tied:
            return "MATCHED" if months >= need else "NOT_OBSERVED"
        return "UNCLEAR" if total >= need else "NOT_OBSERVED"
    if spec.type == "EDUCATION":
        level, families = resume.degree
        level_ok = spec.level is None or LEVELS[level] >= LEVELS[spec.level]
        field_ok = not spec.fields or bool(set(spec.fields) & set(families))
        related = any(family in RELATED_FIELDS.get(required, frozenset()) for required in spec.fields for family in families)
        if level_ok and (field_ok or (related and spec.related_ok)):
            return "MATCHED"
        return "UNCLEAR" if level_ok or field_ok else "NOT_OBSERVED"
    if spec.type == "CERTIFICATION":
        if spec.certification in resume.certifications:
            return "MATCHED"
        related = any(spec.certification in family and resume.certifications & family for family in domain_families)
        return "UNCLEAR" if related else "NOT_OBSERVED"
    return "NOT_OBSERVED"


def _requirements(job: JobSpec, proficiency: str | None = None) -> list[MatchRequirement]:
    return [MatchRequirement(id=index + 1, type=spec.type, description=spec.description, mandatory=spec.mandatory,
                             knockout=spec.mandatory and spec.type != "OTHER", minimumYears=spec.minimum_years,
                             proficiency=proficiency if spec.type == "SKILL" else None)
            for index, spec in enumerate(job.requirements)]


def _match(job: JobSpec, parsed: ParseResponse, proficiency: str | None = None) -> MatchResponse:
    facts = [MatchFact(id=f"fact:{index}", type=fact.type, value=fact.value, evidence=fact.evidence,
                       sourceSection=fact.sourceSection, tier="RESUME_STATED", attributes=fact.attributes)
             for index, fact in enumerate(parsed.facts) if fact.type not in {"EMAIL", "PHONE", "LINK"}]
    return match_requirements(MatchRequest(requirements=_requirements(job, proficiency), facts=facts, timeline=parsed.timeline),
                              today=EVAL_TODAY)


def _proficiency_cases() -> tuple[float, list[str]]:
    wrong = []
    for case in PROFICIENCY_CASES:
        status = match_requirements(proficiency_request(case), today=EVAL_TODAY).requirements[0].status
        if status != case.gold:
            wrong.append(f"{case.name}: {status} (gold {case.gold})")
    return (len(PROFICIENCY_CASES) - len(wrong)) / len(PROFICIENCY_CASES), wrong


def _proficiency_violations(job: JobSpec, base: MatchResponse, deeper: MatchResponse) -> tuple[int, int, int]:
    """(violations, MATCHED SKILL requirements, of which downgraded) for one re-match with a proficiency.

    The only change a proficiency may cause is MATCHED -> UNCLEAR on a SKILL requirement."""
    violations = matched = downgraded = 0
    for spec, before, after in zip(job.requirements, base.requirements, deeper.requirements):
        if before.status != after.status and (spec.type != "SKILL" or (before.status, after.status) != ("MATCHED", "UNCLEAR")):
            violations += 1
        if spec.type == "SKILL" and before.status == "MATCHED":
            matched += 1
            downgraded += after.status != "MATCHED"
    return violations, matched, downgraded


def _gold_coverage(job: JobSpec, statuses: list[str]) -> float:
    weights = [(2 if spec.mandatory else 1) for spec in job.requirements]
    scored = [(weight, status) for weight, status, spec in zip(weights, statuses, job.requirements) if spec.type != "OTHER"]
    total = sum(weight for weight, _ in scored)
    return sum(weight for weight, status in scored if status == "MATCHED") / total if total else 0.0


# ------------------------------------------------------------------------------------- evaluation
def evaluate(seed: int = DEFAULT_SEED, per_domain: int = DEFAULT_PER_DOMAIN, workers: int = 1) -> dict:
    taxonomy = get_taxonomy()
    resumes = generate(seed, per_domain)
    parsed = _parse_all(resumes, workers)
    base = [resume for resume in resumes if resume.twin_of is None]

    # Skill extraction (taxonomy skills) on base resumes.
    tp = fp = fn = 0
    for resume in base:
        gold = {taxonomy.lookup(name).id for name in resume.gold_skills}
        found = {str(fact.attributes["skillId"]) for fact in parsed[resume.id].facts if fact.type == "SKILL" and "skillId" in fact.attributes}
        tp, fp, fn = tp + len(gold & found), fp + len(found - gold), fn + len(gold - found)
    precision, recall, f1 = precision_recall_f1(tp, fp, fn)

    # Dates: every gold role must appear in the timeline with the same start and end.
    correct = expected = 0
    for resume in base:
        timeline = parsed[resume.id].timeline
        found_roles = {(role.startDate, role.endDate if not role.current else None) for role in (timeline.roles if timeline else [])}
        for role in resume.roles:
            expected += 1
            correct += (role.expected_start, role.expected_end) in found_roles

    # Flags.
    flag_hits = flag_total = 0
    for resume in base:
        codes = {flag.code for flag in parsed[resume.id].flags}
        flag_total += 1
        # Hidden text is either stuffed skills or an injection; both must raise PROMPT_INJECTION_SUSPECTED.
        flag_hits += (("HIDDEN_TEXT" in codes) == bool(resume.hidden)) and (("PROMPT_INJECTION_SUSPECTED" in codes) == bool(resume.hidden))

    # Matching, ranking and fairness.
    pairs: list[tuple[str, str]] = []
    ndcgs: list[float] = []
    flips = twin_checks = 0
    selected: dict[str, list[bool]] = {"F": [], "M": []}
    proficiency_violations = 0
    proficiency_matched = {"ADVANCED": 0, "EXPERT": 0}
    proficiency_downgraded = {"ADVANCED": 0, "EXPERT": 0}
    by_domain = {domain.key: [resume for resume in resumes if resume.domain.key == domain.key] for domain in DOMAINS}
    for domain in DOMAINS:
        others = [resume for resume in base if resume.domain.key != domain.key]
        cross = others[:: max(1, len(others) // CROSS_DOMAIN_PER_JOB)][:CROSS_DOMAIN_PER_JOB]
        for job in domain.jobs:
            results: dict[str, MatchResponse] = {}
            ranking: list[tuple[float, float, str, float]] = []
            for resume in by_domain[domain.key] + cross:
                result = _match(job, parsed[resume.id])
                results[resume.id] = result
                gold = [gold_status(spec, resume, domain.certification_families) for spec in job.requirements]
                ranking.append((-(result.verifiedCoverage or 0), -(result.claimedCoverage or 0), resume.id, _gold_coverage(job, gold)))
                if resume.domain.key == domain.key:
                    pairs.extend((label, outcome.status) for label, outcome, spec in zip(gold, result.requirements, job.requirements)
                                 if spec.type != "OTHER")
                    selected[resume.gender].append((result.verifiedCoverage or 0) >= SELECTION_THRESHOLD)
                    if resume.twin_of is None and any(spec.type == "SKILL" for spec in job.requirements):
                        for level in ("ADVANCED", "EXPERT"):
                            bad, matched, downgraded = _proficiency_violations(job, result, _match(job, parsed[resume.id], level))
                            proficiency_violations += bad
                            proficiency_matched[level] += matched
                            proficiency_downgraded[level] += downgraded
            ndcgs.append(ndcg_at_k([gain * 3 for *_, gain in sorted(ranking)], 10))
            for resume in by_domain[domain.key]:
                if resume.twin_of is not None:
                    twin_checks += 1
                    original, twin = results[resume.twin_of], results[resume.id]
                    same = original.verifiedCoverage == twin.verifiedCoverage and \
                        [r.status for r in original.requirements] == [r.status for r in twin.requirements]
                    flips += not same

    # A group with no members has no selection rate (None), rather than a rate of 0 that would fail the AIR.
    rates = {group: (sum(values) / len(values) if values else None) for group, values in selected.items()}
    case_accuracy, wrong_cases = _proficiency_cases()
    return {
        "resumes": len(resumes), "base_resumes": len(base), "jobs": sum(len(domain.jobs) for domain in DOMAINS),
        "skill_precision": round(precision, 4), "skill_recall": round(recall, 4), "skill_f1": round(f1, 4),
        "date_accuracy": round(correct / expected, 4) if expected else 1.0,
        "flag_accuracy": round(flag_hits / flag_total, 4) if flag_total else 1.0,
        "requirement_kappa": round(cohen_kappa(pairs), 4), "requirement_pairs": len(pairs),
        "requirement_agreement": round(sum(gold == predicted for gold, predicted in pairs) / len(pairs), 4) if pairs else 1.0,
        "ndcg_at_10": round(sum(ndcgs) / len(ndcgs), 4) if ndcgs else 1.0,
        "name_swap_flip_rate": round(flips / twin_checks, 4) if twin_checks else 0.0, "counterfactual_checks": twin_checks,
        "selection_rates": {group: (round(rate, 4) if rate is not None else None) for group, rate in rates.items()},
        "adverse_impact_ratio": round(adverse_impact_ratio(rates), 4),
        "proficiency_case_accuracy": round(case_accuracy, 4), "proficiency_cases": len(PROFICIENCY_CASES),
        "proficiency_case_failures": wrong_cases,
        "proficiency_invariant_violations": proficiency_violations,
        # Informational: share of MATCHED SKILL requirements that the proficiency turns into UNCLEAR.
        "proficiency_downgrade_share": {level: round(proficiency_downgraded[level] / proficiency_matched[level], 4)
                                        if proficiency_matched[level] else 0.0 for level in proficiency_matched},
    }


def failures(metrics: dict) -> list[str]:
    problems = []
    for name, (comparison, threshold) in THRESHOLDS.items():
        value = metrics[name]
        ok = value >= threshold if comparison == ">=" else value <= threshold
        if not ok:
            problems.append(f"{name} = {value} (required {comparison} {threshold})")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 when a metric misses its threshold")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--per-domain", type=int, default=DEFAULT_PER_DOMAIN, help="base resumes per domain (each gets a twin)")
    parser.add_argument("--workers", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument("--json", help="also write the metrics to this file")
    args = parser.parse_args(argv)
    metrics = evaluate(args.seed, args.per_domain, args.workers)
    print("RecruitPro AI service - synthetic evaluation (synthetic data overstates real accuracy)")
    for key, value in metrics.items():
        marker = ""
        if key in THRESHOLDS:
            comparison, threshold = THRESHOLDS[key]
            marker = f"   threshold {comparison} {threshold}"
        print(f"  {key:28} {value}{marker}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(metrics, handle, indent=2)
    problems = failures(metrics)
    if args.check and problems:
        print("CHECK FAILED:\n  " + "\n  ".join(problems))
        return 1
    if args.check:
        print("CHECK PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
