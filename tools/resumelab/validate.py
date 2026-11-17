"""Schema validation of a generated corpus (no external schema library needed)."""
from __future__ import annotations

import json
import re
from pathlib import Path

JOB_TYPES = {"FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP"}
EXPERIENCE_LEVELS = {"ENTRY", "MID", "SENIOR", "LEAD", "EXECUTIVE"}
REQUIREMENT_TYPES = {"SKILL", "EXPERIENCE", "EDUCATION", "CERTIFICATION", "OTHER"}
PROFICIENCY = {None, "BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT"}
LABELS = {"strong", "good", "partial", "weak", "unrelated"}
GROUPS = {"RANKED", "MUST_HAVES_UNVERIFIED", "UNSCORED"}
FORMATS = {"pdf", "docx", "scan", "txt", "doc"}
YM = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def validate_jobs(jobs, problems: list[str]) -> set[str]:
    keys = set()
    if not isinstance(jobs, list) or not jobs:
        problems.append("jobs.json: must be a non-empty list")
        return keys
    for i, job in enumerate(jobs):
        where = f"jobs[{i}]"
        required = {"key", "title", "department", "location", "jobType", "experienceLevel", "description", "vacancies", "requirements"}
        missing = required - set(job)
        if missing:
            problems.append(f"{where}: missing {sorted(missing)}")
            continue
        keys.add(job["key"])
        for field, limit in (("title", 255), ("department", 100), ("location", 255), ("description", 50000)):
            if not isinstance(job[field], str) or not job[field].strip() or len(job[field]) > limit:
                problems.append(f"{where}.{field}: must be a non-blank string <= {limit} chars")
        if job["jobType"] not in JOB_TYPES:
            problems.append(f"{where}.jobType: {job['jobType']!r} not in {sorted(JOB_TYPES)}")
        if job["experienceLevel"] not in EXPERIENCE_LEVELS:
            problems.append(f"{where}.experienceLevel: {job['experienceLevel']!r}")
        if not isinstance(job["vacancies"], int) or job["vacancies"] <= 0:
            problems.append(f"{where}.vacancies: must be a positive integer")
        for j, req in enumerate(job["requirements"]):
            rw = f"{where}.requirements[{j}]"
            expected = {"type", "description", "mandatory", "knockout", "minimumYears", "alternatives", "proficiency"}
            if set(req) != expected:
                problems.append(f"{rw}: keys {sorted(req)} != {sorted(expected)}")
                continue
            if req["type"] not in REQUIREMENT_TYPES:
                problems.append(f"{rw}.type: {req['type']!r}")
            if not isinstance(req["description"], str) or not req["description"].strip() or len(req["description"]) > 500:
                problems.append(f"{rw}.description: 1-500 chars")
            if not isinstance(req["mandatory"], bool) or not isinstance(req["knockout"], bool):
                problems.append(f"{rw}: mandatory/knockout must be booleans")
            if req["knockout"] and not req["mandatory"]:
                problems.append(f"{rw}: knockout requires mandatory")
            if req["minimumYears"] is not None and (not isinstance(req["minimumYears"], int) or not 0 <= req["minimumYears"] <= 50):
                problems.append(f"{rw}.minimumYears: int 0..50 or null")
            if not isinstance(req["alternatives"], list) or len(req["alternatives"]) > 10 or not all(isinstance(a, str) for a in req["alternatives"]):
                problems.append(f"{rw}.alternatives: list of <= 10 strings")
            if req["proficiency"] not in PROFICIENCY:
                problems.append(f"{rw}.proficiency: {req['proficiency']!r}")
    return keys


def validate_manifest(manifest, job_keys: set[str], root: Path, problems: list[str]) -> None:
    for field in ("seed", "generatedAt", "count", "resumes"):
        if field not in manifest:
            problems.append(f"manifest: missing {field}")
    resumes = manifest.get("resumes", [])
    if manifest.get("count") != len(resumes):
        problems.append("manifest: count != len(resumes)")
    ids = set()
    for i, entry in enumerate(resumes):
        where = f"resumes[{i}]"
        required = {"id", "file", "format", "template", "profession", "seniority", "quality", "adversarial", "persona", "gold", "fit"}
        missing = required - set(entry)
        if missing:
            problems.append(f"{where}: missing {sorted(missing)}")
            continue
        if entry["id"] in ids:
            problems.append(f"{where}: duplicate id {entry['id']}")
        ids.add(entry["id"])
        if entry["format"] not in FORMATS:
            problems.append(f"{where}.format: {entry['format']!r}")
        if not (root / entry["file"]).is_file():
            problems.append(f"{where}.file: {entry['file']} does not exist")
        if entry["quality"] not in {"polished", "average", "poor"}:
            problems.append(f"{where}.quality: {entry['quality']!r}")
        persona = entry["persona"]
        for field in ("firstName", "lastName", "email", "phone", "city", "gender"):
            if not isinstance(persona.get(field), str) or not persona.get(field):
                problems.append(f"{where}.persona.{field}: non-empty string")
        gold = entry["gold"]
        for field in ("skills", "roles", "totalMonths", "education", "certifications"):
            if field not in gold:
                problems.append(f"{where}.gold: missing {field}")
        for skill in gold.get("skills", []):
            if not isinstance(skill.get("name"), str) or "evidence" not in skill or "taxonomyId" not in skill or "months" not in skill:
                problems.append(f"{where}.gold.skills: bad entry {skill}")
                break
            if skill["months"] is not None and (not isinstance(skill["months"], int) or skill["months"] <= 0):
                problems.append(f"{where}.gold.skills[{skill['name']}].months: positive int or null")
        for role in gold.get("roles", []):
            if not YM.match(str(role.get("start"))) or (role.get("end") is not None and not YM.match(str(role["end"]))):
                problems.append(f"{where}.gold.roles: bad dates {role}")
            elif role.get("end") is not None and role["end"] < role["start"]:
                problems.append(f"{where}.gold.roles: end before start {role}")
            for field in ("title", "employer"):
                if not isinstance(role.get(field), str) or not role.get(field):
                    problems.append(f"{where}.gold.roles: missing {field}")
        if not isinstance(gold.get("totalMonths"), int) or gold["totalMonths"] < 0:
            problems.append(f"{where}.gold.totalMonths: non-negative int")
        for edu in gold.get("education", []):
            if not {"degree", "field", "institution", "year", "grade"} <= set(edu):
                problems.append(f"{where}.gold.education: bad entry {edu}")
        if set(entry["fit"]) != job_keys:
            problems.append(f"{where}.fit: keys {sorted(entry['fit'])} != jobs {sorted(job_keys)}")
        for key, fit in entry["fit"].items():
            if fit.get("label") not in LABELS or fit.get("expectedRankingGroup") not in GROUPS or not isinstance(fit.get("metRequirements"), list):
                problems.append(f"{where}.fit[{key}]: bad {fit}")


def validate(root: Path) -> list[str]:
    problems: list[str] = []
    try:
        jobs = json.loads((root / "jobs.json").read_text(encoding="utf-8"))
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"cannot read corpus: {exc}"]
    keys = validate_jobs(jobs, problems)
    validate_manifest(manifest, keys, root, problems)
    for job in jobs if isinstance(jobs, list) else []:
        n = len(job.get("requirements", []))
        for entry in manifest.get("resumes", []):
            fit = entry.get("fit", {}).get(job.get("key"))
            if fit and any(not isinstance(i, int) or not 0 <= i < n for i in fit.get("metRequirements", [])):
                problems.append(f"{entry.get('id')}.fit[{job.get('key')}]: requirement index out of range")
    return problems
