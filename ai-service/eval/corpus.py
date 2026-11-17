"""Parser and matcher evaluation on a ResumeLab corpus (``python -m eval.run --corpus DIR``).

The corpus (tools/resumelab) holds real document files plus gold labels written by the generator:
visible skills (with taxonomy ids, months and evidence lines), roles with dates, total months,
education, certifications, adversarial tags and per-job fit labels. Every file goes through the real
``parse_resume`` (bytes in), then every job's requirements go through ``match_requirements``.

This is a report, not a gate: ``--check`` keeps using the built-in synthetic set.
"""
from __future__ import annotations

import json
import os
import re
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from dataclasses import replace
from datetime import date
from pathlib import Path
from statistics import median

from app.core.config import load_settings
from app.matching.engine import match_requirements
from app.models.matching import MatchFact, MatchRequest, MatchRequirement
from app.models.resume import ParseResponse
from app.pipeline.extract import parse_resume
from app.taxonomy.taxonomy import get_taxonomy
from eval.metrics import ndcg_at_k, precision_recall_f1

GAIN = {"strong": 4, "good": 3, "partial": 2, "weak": 1, "unrelated": 0}
GROUP_ORDER = {"RANKED": 0, "MUST_HAVES_UNVERIFIED": 1, "UNSCORED": 2}
_LEVEL_RANK = {"SCHOOL": 0, "DIPLOMA": 1, "BACHELOR": 2, "PROFESSIONAL": 3, "MASTER": 3, "DOCTORATE": 4}


def _today(as_of: str) -> date:
    year, month = (int(part) for part in as_of.split("-"))
    return date(year, month, 15)


def _parse(args: tuple[str, str, str]) -> tuple[str, dict]:
    resume_id, path, as_of = args
    settings = replace(load_settings(), llm_provider="none")
    content = Path(path).read_bytes()
    result = parse_resume(Path(path).name, content, settings=settings, today=_today(as_of))
    return resume_id, result.model_dump()


def _ym(value: str | None) -> str | None:
    return value[:7] if value else None


def _role_match(gold: dict, parsed_roles: list[dict]) -> bool:
    """Same start and end (or both current) at the precision the resume shows."""
    year_only = gold.get("datePrecision") == "year"
    for role in parsed_roles:
        start, end = _ym(role.get("startDate")), _ym(role.get("endDate"))
        current = bool(role.get("current"))
        if year_only:
            if start and start[:4] == gold["start"][:4] and ((gold["end"] is None and current) or (end and gold["end"] and end[:4] == gold["end"][:4])):
                return True
            continue
        if start == gold["start"] and ((gold["end"] is None and current) or (not current and end == gold["end"])):
            return True
    return False


def _named_in(skill_id: str, names: set[str]) -> bool:
    skill = get_taxonomy().by_id(skill_id)
    return skill is not None and any(name.lower() in names for name in (skill.name, *skill.aliases))


def _requirements(job: dict) -> list[MatchRequirement]:
    return [MatchRequirement(id=index + 1, type=req["type"], description=req["description"], mandatory=req["mandatory"],
                             knockout=req["knockout"], minimumYears=req["minimumYears"], alternatives=req["alternatives"],
                             proficiency=req["proficiency"]) for index, req in enumerate(job["requirements"])]


def evaluate_corpus(root: str | Path, workers: int = 1, limit: int | None = None) -> dict:
    root = Path(root)
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    jobs = json.loads((root / "jobs.json").read_text(encoding="utf-8"))
    as_of = manifest.get("asOf") or manifest["generatedAt"][:7]
    resumes = manifest["resumes"][:limit] if limit else manifest["resumes"]
    tasks = [(entry["id"], str(root / entry["file"]), as_of) for entry in resumes]
    if workers > 1:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            parsed = dict(pool.map(_parse, tasks, chunksize=4))
    else:
        parsed = dict(map(_parse, tasks))

    status = Counter()
    status_by_format: dict[str, Counter] = defaultdict(Counter)
    skill_counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])  # breakdown -> tp, fp, fn
    recall_by_section: dict[str, list[int]] = defaultdict(lambda: [0, 0])  # hit, total
    missed_skills = Counter()
    false_skills = Counter()
    role_hits = role_total = 0
    date_misses: list[str] = []
    role_count_errors = []
    total_errors = []
    skill_month_errors = []
    flag_rows = []
    pii_leaks = Counter()
    edu_hits = edu_total = 0
    for entry in resumes:
        result = ParseResponse.model_validate(parsed[entry["id"]])
        status[result.status] += 1
        status_by_format[entry["format"]][result.status] += 1
        gold = entry["gold"]
        gold_ids = {s["taxonomyId"]: s for s in gold["skills"] if s.get("taxonomyId")}
        found = {str(f.attributes["skillId"]) for f in result.facts if f.type == "SKILL" and "skillId" in f.attributes}
        # Gold lists every visible skill, but gives a taxonomy id only to skills the taxonomy had when the corpus
        # was generated. A skill added to the taxonomy since (CATIA, Zendesk ...) is gold without an id: finding
        # it is neither a true nor a false positive, so it is left out of the counts.
        untracked = {name.lower() for skill in gold["skills"] if not skill.get("taxonomyId")
                     for name in (skill["name"], skill.get("surface") or "")}
        found = {skill_id for skill_id in found if not _named_in(skill_id, untracked)}
        tp, fp, fn = len(found & set(gold_ids)), len(found - set(gold_ids)), len(set(gold_ids) - found)
        for key in ("all", f"format:{entry['format']}", f"template:{entry['template']}", f"quality:{entry['quality']}",
                    f"family:{entry['profession']}"):
            counts = skill_counts[key]
            counts[0] += tp
            counts[1] += fp
            counts[2] += fn
        for skill_id, skill in gold_ids.items():
            section = skill.get("section", "?")
            recall_by_section[section][1] += 1
            recall_by_section[section][0] += skill_id in found
            if skill_id not in found and entry["format"] != "scan":
                missed_skills[f"{skill['name']} ({section}: '{skill.get('surface', skill['name'])}')"] += 1
        if entry["format"] != "scan":
            for skill_id in found - set(gold_ids):
                false_skills[skill_id] += 1
        timeline = result.timeline
        parsed_roles = [role.model_dump() for role in timeline.roles] if timeline else []
        for role in gold["roles"]:
            role_total += 1
            hit = _role_match(role, parsed_roles)
            role_hits += hit
            if not hit and result.status in {"COMPLETED", "PARTIAL"}:
                date_misses.append(f"{entry['id']} {entry['template']}/{entry['format']}: gold {role['start']}..{role['end'] or 'now'} "
                                   f"({role['title']}) not found")
        if result.status in {"COMPLETED", "PARTIAL"}:
            role_count_errors.append(len(parsed_roles) - len(gold["roles"]))
            month_precision = all(role.get("datePrecision") != "year" for role in gold["roles"])
            if gold["roles"] and month_precision:
                total_errors.append(abs((timeline.totalMonths if timeline else 0) - gold["totalMonths"]))
            for skill_id, skill in gold_ids.items():
                if skill.get("months") and month_precision:
                    known = get_taxonomy().by_id(skill_id)
                    months_by_skill = timeline.skillMonths if timeline else {}
                    got = months_by_skill.get(known.name if known else skill["name"], months_by_skill.get(skill_id)) or 0
                    skill_month_errors.append(abs(got - skill["months"]))
        codes = {flag.code for flag in result.flags}
        if result.status in {"COMPLETED", "PARTIAL"}:  # unreadable files (NEEDS_OCR, FAILED) cannot be screened
            flag_rows.append((entry, codes))
        # Personal details and identity must never become professional evidence.
        names = {entry["persona"]["firstName"], entry["persona"]["lastName"]}
        for fact in result.facts:
            if fact.type in {"EMAIL", "PHONE", "LINK"}:
                continue
            text = f"{fact.value} {fact.evidence}"
            if re.search(r"date of birth|marital|father'?s name|nationality|\bgender\b", text, re.I):
                pii_leaks["personal-detail text in a fact"] += 1
            if any(len(name) >= 4 and re.search(rf"\b{re.escape(name)}\b", text) for name in names):
                pii_leaks["candidate name in a fact"] += 1
        gold_levels = [_LEVEL_RANK.get(e.get("level", ""), -1) for e in gold["education"] if e.get("level") not in {None, "SCHOOL"} and not e.get("pursuing")]
        if gold_levels and result.status in {"COMPLETED", "PARTIAL"}:
            edu_total += 1
            parsed_levels = [_LEVEL_RANK.get(str(f.attributes.get("degreeLevel")), -1) for f in result.facts if f.type == "EDUCATION"]
            edu_hits += bool(parsed_levels) and max(parsed_levels) >= min(max(gold_levels), 3)

    # Flags
    def rate(rows, predicate):
        rows = list(rows)
        return (sum(1 for row in rows if predicate(row)), len(rows))

    injection = rate((codes for e, codes in flag_rows if "prompt_injection" in e["adversarial"] and e["format"] != "scan"),
                     lambda codes: "PROMPT_INJECTION_SUSPECTED" in codes)
    stuffing = rate((codes for e, codes in flag_rows if "hidden_keywords" in e["adversarial"]),
                    lambda codes: "PROMPT_INJECTION_SUSPECTED" in codes)
    hidden = rate((codes for e, codes in flag_rows if "hidden_keywords" in e["adversarial"] and e["format"] in {"pdf", "docx"}),
                  lambda codes: "HIDDEN_TEXT" in codes)
    clean = [(e, codes) for e, codes in flag_rows if not {"prompt_injection", "hidden_keywords"} & set(e["adversarial"])]
    false_injection = rate((codes for _, codes in clean), lambda codes: "PROMPT_INJECTION_SUSPECTED" in codes)
    false_hidden = rate((codes for _, codes in clean), lambda codes: "HIDDEN_TEXT" in codes)
    false_hidden_templates = Counter(e["template"] for e, codes in clean if "HIDDEN_TEXT" in codes)
    two_column = rate((codes for e, codes in flag_rows if e["template"] == "modern_sidebar" and e["format"] == "pdf"),
                      lambda codes: "TWO_COLUMN_LAYOUT" in codes)

    # Matching and ranking
    by_type: dict[str, Counter] = defaultdict(Counter)  # type -> tp/fp/fn/tn (gold met vs predicted MATCHED)
    group_agree = group_total = 0
    group_confusion = Counter()
    ndcgs = {}
    stuffed_lift = []
    for job in jobs:
        requirements = _requirements(job)
        ranking = []
        for entry in resumes:
            result = ParseResponse.model_validate(parsed[entry["id"]])
            gold_fit = entry["fit"][job["key"]]
            facts = [MatchFact(id=f"fact:{i}", type=f.type, value=f.value, evidence=f.evidence, sourceSection=f.sourceSection,
                               tier="RESUME_STATED", attributes=f.attributes)
                     for i, f in enumerate(result.facts) if f.type not in {"EMAIL", "PHONE", "LINK"}]
            if result.status not in {"COMPLETED", "PARTIAL"}:
                predicted_group, verified, claimed = "UNSCORED", None, None
                statuses = ["NOT_OBSERVED"] * len(requirements)
            else:
                match = match_requirements(MatchRequest(requirements=requirements, facts=facts, timeline=result.timeline), today=_today(as_of))
                verified, claimed = match.verifiedCoverage, match.claimedCoverage
                predicted_group = "UNSCORED" if verified is None else ("RANKED" if match.knockoutsVerified else "MUST_HAVES_UNVERIFIED")
                statuses = [r.status for r in match.requirements]
            met = set(gold_fit["metRequirements"])
            for index, (req, got) in enumerate(zip(job["requirements"], statuses)):
                if req["type"] == "OTHER":
                    continue
                key = ("tp" if got == "MATCHED" else "fn") if index in met else ("fp" if got == "MATCHED" else "tn")
                by_type[req["type"]][key] += 1
                by_type["ALL"][key] += 1
            group_total += 1
            group_agree += predicted_group == gold_fit["expectedRankingGroup"]
            group_confusion[f"{gold_fit['expectedRankingGroup']} -> {predicted_group}"] += 1
            ranking.append((GROUP_ORDER[predicted_group], -(verified or 0), -(claimed or 0), entry["id"], GAIN[gold_fit["label"]], entry))
        ranking.sort(key=lambda row: row[:4])
        ndcgs[job["key"]] = round(ndcg_at_k([row[4] for row in ranking], 10), 4)
        positions = {row[3]: pos for pos, row in enumerate(ranking)}
        for entry in resumes:
            if "hidden_keywords" in entry["adversarial"] or "inflated_claims" in entry["adversarial"]:
                gain = GAIN[entry["fit"][job["key"]]["label"]]
                first = sum(1 for row in ranking if row[4] > gain) + 1
                last = first + sum(1 for row in ranking if row[4] == gain) - 1
                stuffed_lift.append((entry["id"], job["key"], ",".join(entry["adversarial"]), entry["fit"][job["key"]]["label"], positions[entry["id"]] + 1,
                                     f"{first}-{last}"))

    def prf(key):
        precision, recall, f1 = precision_recall_f1(*skill_counts[key])
        return {"precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4), "tp": skill_counts[key][0],
                "fp": skill_counts[key][1], "fn": skill_counts[key][2]}

    requirement_metrics = {}
    for req_type, counts in by_type.items():
        precision, recall, f1 = precision_recall_f1(counts["tp"], counts["fp"], counts["fn"])
        total = sum(counts.values())
        requirement_metrics[req_type] = {"precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4),
                                         "accuracy": round((counts["tp"] + counts["tn"]) / total, 4) if total else 1.0, "n": total}
    return {
        "corpus": str(root), "resumes": len(resumes), "jobs": len(jobs), "asOf": as_of,
        "parse_status": dict(status), "parse_status_by_format": {k: dict(v) for k, v in status_by_format.items()},
        "skills": prf("all"),
        "skills_by_format": {k.split(":", 1)[1]: prf(k) for k in sorted(skill_counts) if k.startswith("format:")},
        "skills_by_quality": {k.split(":", 1)[1]: prf(k) for k in sorted(skill_counts) if k.startswith("quality:")},
        "skills_by_template": {k.split(":", 1)[1]: prf(k) for k in sorted(skill_counts) if k.startswith("template:")},
        "skills_by_profession": {k.split(":", 1)[1]: prf(k) for k in sorted(skill_counts) if k.startswith("family:")},
        "skill_recall_by_gold_section": {k: round(h / t, 4) for k, (h, t) in sorted(recall_by_section.items()) if t},
        "top_missed_skills": missed_skills.most_common(25),
        "top_false_skills": false_skills.most_common(15),
        "date_accuracy": round(role_hits / role_total, 4) if role_total else 1.0, "gold_roles": role_total,
        "date_misses_sample": date_misses[:25],
        "role_count_error": {"mean_abs": round(sum(abs(e) for e in role_count_errors) / len(role_count_errors), 3) if role_count_errors else 0,
                             "exact_share": round(sum(1 for e in role_count_errors if e == 0) / len(role_count_errors), 4) if role_count_errors else 1.0,
                             "over": sum(1 for e in role_count_errors if e > 0), "under": sum(1 for e in role_count_errors if e < 0)},
        "total_months_error": {"mae": round(sum(total_errors) / len(total_errors), 2) if total_errors else 0,
                               "median": median(total_errors) if total_errors else 0,
                               "within_3_months": round(sum(1 for e in total_errors if e <= 3) / len(total_errors), 4) if total_errors else 1.0,
                               "n": len(total_errors)},
        "skill_months_error": {"mae": round(sum(skill_month_errors) / len(skill_month_errors), 2) if skill_month_errors else 0,
                               "within_3_months": round(sum(1 for e in skill_month_errors if e <= 3) / len(skill_month_errors), 4)
                               if skill_month_errors else 1.0, "n": len(skill_month_errors)},
        "education_level_detected": round(edu_hits / edu_total, 4) if edu_total else 1.0,
        "flags": {"prompt_injection_detected": f"{injection[0]}/{injection[1]}", "keyword_stuffing_flagged": f"{stuffing[0]}/{stuffing[1]}",
                  "hidden_text_detected": f"{hidden[0]}/{hidden[1]}", "false_injection_on_clean": f"{false_injection[0]}/{false_injection[1]}",
                  "false_hidden_text_on_clean": f"{false_hidden[0]}/{false_hidden[1]}", "false_hidden_text_templates": dict(false_hidden_templates),
                  "two_column_detected_on_sidebar_pdfs": f"{two_column[0]}/{two_column[1]}"},
        "pii_in_facts": dict(pii_leaks),
        "requirements_vs_gold": requirement_metrics,
        "ranking_group_agreement": round(group_agree / group_total, 4) if group_total else 1.0,
        "ranking_group_confusion": dict(group_confusion.most_common()),
        "ndcg_at_10": ndcgs,
        "adversarial_rank_positions": [{"resume": r, "job": j, "adversarial": a, "gold": g, "rank": p, "gold_rank_band": i} for r, j, a, g, p, i in stuffed_lift],
    }


def print_report(metrics: dict) -> None:
    print(f"RecruitPro AI service - ResumeLab corpus evaluation ({metrics['resumes']} resumes, {metrics['jobs']} jobs, as of {metrics['asOf']})")
    for key, value in metrics.items():
        if key in {"corpus", "resumes", "jobs", "asOf"}:
            continue
        if isinstance(value, dict) and value and all(isinstance(v, dict) for v in value.values()):
            print(f"  {key}:")
            for sub, inner in value.items():
                print(f"    {sub:24} {json.dumps(inner)}")
        elif isinstance(value, list):
            print(f"  {key}:")
            for item in value:
                print(f"    {item}")
        else:
            print(f"  {key:28} {json.dumps(value) if isinstance(value, dict) else value}")


def default_workers() -> int:
    return min(4, os.cpu_count() or 1)
