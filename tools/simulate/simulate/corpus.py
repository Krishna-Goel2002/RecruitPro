"""Corpus loading (the ``python -m resumelab generate`` output contract, see ``tools/resumelab/README.md``).

``DIR/jobs.json``: list of jobs ``{key, title, department, location, jobType, experienceLevel, description, vacancies,
requirements[]}``. Every field except ``key`` is a backend ``JobCreateRequest`` field and every requirement is a
``RequirementInput`` without ``id`` (type, description, mandatory, knockout, minimumYears, alternatives,
proficiency). The loader checks enum values and size limits against those DTOs so a bad corpus fails before the run
instead of with a 400 halfway through. A ``{"jobs": [...]}`` wrapper is accepted too.

``DIR/manifest.json``: ``{"seed", "asOf", ..., "resumes": [{id, file, format, template, profession, seniority,
quality, adversarial[], persona{firstName,lastName,email,phone,city,gender}, gold{skills, roles, totalMonths,
education, certifications}, fit{<jobKey>: {label, expectedRankingGroup, metRequirements[], coverage}}, targetJob,
targetLabel, careerShape}]}``. ``format`` is ``pdf | docx | scan | txt | doc``; a scan is an image-only ``.pdf``, so
the upload type always comes from the file's extension.

Older or hand-made corpora are still read: optional attributes are looked up on the resume entry first, then in
``gold``, then in ``gold.meta``/``gold.render``, under any of the aliases in :data:`ATTRIBUTE_ALIASES`; missing ones
read as ``None`` and the related report section says "not in corpus". ResumeLab does not write twin groups yet; a
``twinGroup`` (or ``counterfactualGroup`` / ``twinOf``) attribute switches the fairness section on.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

LABELS = ("strong", "good", "partial", "weak", "unrelated")
LABEL_GAIN = {"strong": 4, "good": 3, "partial": 2, "weak": 1, "unrelated": 0}
RANKING_GROUPS = ("RANKED", "MUST_HAVES_UNVERIFIED", "UNSCORED")

# Backend upload whitelist: app.file-storage.allowed-types (backend application.yml) and ResumeService, which also
# hard-limits uploads to these three extensions. Other files are skipped unless --include-unsupported is given.
BACKEND_RESUME_TYPES = ("pdf", "doc", "docx")

# Adversarial tags. Manipulation tries to game the ranking; the report checks it does not rise and is flagged.
# ResumeLab: hidden_keywords, prompt_injection, inflated_claims; the rest are aliases from older corpora.
MANIPULATION_TAGS = frozenset({"hidden_keywords", "prompt_injection", "inflated_claims", "stuffed",
                               "keyword_stuffing", "injection", "hidden_text", "hidden", "adversarial"})
# Manipulation the parser can see in the document and should flag (HIDDEN_TEXT / PROMPT_INJECTION_SUSPECTED).
# inflated_claims (skills listed but never used in a role) is visible text, so no flag is expected for it.
FLAG_EXPECTED_TAGS = frozenset({"hidden_keywords", "prompt_injection", "stuffed", "keyword_stuffing", "injection",
                                "hidden_text", "hidden"})
# Everything else (duplicate_sections, very_long, near_empty, non_english_section, photo_placeholder) is a
# robustness trait: reported per trait in the parser section, but the resume counts as clean for the ranking checks.

# JobCreateRequest / RequirementInput (backend com.recruitpro.hr.dto) and their enums (com.recruitpro.hr.entity).
JOB_TYPES = ("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP")
EXPERIENCE_LEVELS = ("ENTRY", "MID", "SENIOR", "LEAD", "EXECUTIVE")
REQUIREMENT_TYPES = ("SKILL", "EDUCATION", "CERTIFICATION", "EXPERIENCE", "OTHER")
PROFICIENCIES = ("BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT")

ATTRIBUTE_ALIASES: dict[str, tuple[str, ...]] = {
    "template": ("template", "layout", "templateName"),
    "quality": ("quality", "noise", "renderQuality", "degradation"),
    "profession": ("profession",),
    "seniority": ("seniority",),
    "adversarial": ("adversarial", "attack", "adversarialType", "manipulation"),
    "twinGroup": ("twinGroup", "counterfactualGroup", "twinOf", "counterfactualOf", "twinId", "baseId"),
    "years": ("yearsOfExperience", "totalYears", "years", "experienceYears"),
    "currentTitle": ("currentTitle", "title", "currentDesignation", "headline"),
    "currentCompany": ("currentCompany", "company", "employer"),
    "summary": ("summary", "profileSummary"),
}

# Exactly the JobCreateRequest components (POST /api/v1/jobs); "key" and anything else in jobs.json is not sent.
JOB_FIELDS = ("title", "description", "department", "location", "jobType", "experienceLevel", "minimumSalary",
              "maximumSalary", "vacancies", "deadline")
# RequirementInput without "id" (PUT /api/v1/jobs/{id}/requirements, wrapped in RequirementsReplaceRequest).
REQUIREMENT_FIELDS = ("type", "description", "mandatory", "knockout", "minimumYears", "alternatives", "proficiency")


class CorpusError(ValueError):
    pass


@dataclass
class Job:
    key: str
    raw: dict[str, Any]

    @property
    def title(self) -> str:
        return self.raw["title"]

    def create_payload(self, title_suffix: str = "") -> dict[str, Any]:
        body = {k: self.raw[k] for k in JOB_FIELDS if self.raw.get(k) is not None}
        body["title"] = (self.title + title_suffix)[:255]
        body.setdefault("vacancies", 1)
        return body

    def requirements(self) -> list[dict[str, Any]]:
        out = []
        for req in self.raw.get("requirements") or []:
            item = {k: req[k] for k in REQUIREMENT_FIELDS if req.get(k) is not None}
            if isinstance(item.get("type"), str):
                item["type"] = item["type"].upper()
            if isinstance(item.get("proficiency"), str):
                item["proficiency"] = item["proficiency"].upper()
            out.append(item)
        return out


def validate_job(item: dict[str, Any]) -> list[str]:
    """The backend's bean-validation rules for JobCreateRequest and RequirementInput, as readable problems."""
    problems: list[str] = []

    def text(name: str, limit: int, value: Any) -> None:
        if not isinstance(value, str) or not value.strip():
            problems.append(f"{name} is blank")
        elif len(value) > limit:
            problems.append(f"{name} is longer than {limit} characters")

    for name, limit in (("title", 255), ("description", 50000), ("department", 100), ("location", 255)):
        text(name, limit, item.get(name))
    if item.get("jobType") not in JOB_TYPES:
        problems.append(f"jobType {item.get('jobType')!r} is not one of {', '.join(JOB_TYPES)}")
    if item.get("experienceLevel") not in EXPERIENCE_LEVELS:
        problems.append(f"experienceLevel {item.get('experienceLevel')!r} is not one of {', '.join(EXPERIENCE_LEVELS)}")
    vacancies = item.get("vacancies", 1)
    if vacancies is not None and (not isinstance(vacancies, int) or isinstance(vacancies, bool) or vacancies < 1):
        problems.append(f"vacancies {vacancies!r} must be a positive integer")
    reqs = item.get("requirements") or []
    if not isinstance(reqs, list) or len(reqs) > 100:
        problems.append("requirements must be a list of at most 100 items")
        return problems
    for i, req in enumerate(reqs):
        where = f"requirement {i}"
        if not isinstance(req, dict):
            problems.append(f"{where} is not an object")
            continue
        if str(req.get("type") or "").upper() not in REQUIREMENT_TYPES:
            problems.append(f"{where}: type {req.get('type')!r} is not one of {', '.join(REQUIREMENT_TYPES)}")
        text(f"{where}: description", 500, req.get("description"))
        years = req.get("minimumYears")
        if years is not None and (not isinstance(years, int) or isinstance(years, bool) or not 0 <= years <= 50):
            problems.append(f"{where}: minimumYears {years!r} must be an integer 0-50")
        alternatives = req.get("alternatives")
        if alternatives is not None and (not isinstance(alternatives, list) or len(alternatives) > 10):
            problems.append(f"{where}: alternatives must be a list of at most 10")
        proficiency = req.get("proficiency")
        if proficiency is not None and str(proficiency).upper() not in PROFICIENCIES:
            problems.append(f"{where}: proficiency {proficiency!r} is not one of {', '.join(PROFICIENCIES)}")
        for flag in ("mandatory", "knockout"):
            if req.get(flag) is not None and not isinstance(req[flag], bool):
                problems.append(f"{where}: {flag} must be true, false or null")
    return problems


@dataclass
class Resume:
    id: str
    file: Path
    format: str
    persona: dict[str, Any]
    gold: dict[str, Any]
    fit: dict[str, dict[str, Any]]
    raw: dict[str, Any] = field(repr=False)
    index: int = 0

    def attr(self, name: str) -> Any:
        aliases = ATTRIBUTE_ALIASES.get(name, (name,))
        scopes = [self.raw, self.gold]
        for nested in ("meta", "render", "rendering", "variant"):
            if isinstance(self.gold.get(nested), dict):
                scopes.append(self.gold[nested])
        for scope in scopes:
            for alias in aliases:
                if alias in scope and scope[alias] not in (None, ""):
                    return scope[alias]
        return None

    @property
    def ext(self) -> str:
        """Upload type: the file's extension (a ResumeLab ``scan`` is a ``.pdf``)."""
        return self.file.suffix.lstrip(".").lower() or self.format

    @property
    def traits(self) -> list[str]:
        """Every adversarial tag of the resume (ResumeLab writes a list; older corpora a string or a flag)."""
        value = self.attr("adversarial")
        if value in (None, False, "none", "None", ""):
            tags = self.raw.get("tags") or self.gold.get("tags") or []
            return [str(t).lower() for t in tags if str(t).lower() in MANIPULATION_TAGS]
        if value is True:
            return ["adversarial"]
        if isinstance(value, dict):
            return [str(value.get("type") or value.get("kind") or "adversarial").lower()]
        if isinstance(value, (list, tuple)):
            return [str(v).lower() for v in value if v not in (None, "")]
        return [t.strip().lower() for t in str(value).split(",") if t.strip()]

    @property
    def adversarial(self) -> str | None:
        """The manipulation tags (comma-joined), ``None`` for a clean resume or one with robustness traits only."""
        hits = [t for t in self.traits if t in MANIPULATION_TAGS]
        return ",".join(hits) or None

    @property
    def flag_expected(self) -> bool:
        return any(t in FLAG_EXPECTED_TAGS for t in self.traits)

    @property
    def years_of_experience(self) -> int | None:
        years = self.attr("years")
        if isinstance(years, (int, float)) and not isinstance(years, bool):
            return int(years)
        months = self.gold.get("totalMonths")
        if isinstance(months, int) and not isinstance(months, bool):
            return months // 12
        return None

    def current_role(self) -> dict[str, Any]:
        """``{"title", "employer"}`` of the current role (``end: null``), else the latest one, from ``gold.roles``."""
        roles = [r for r in self.gold.get("roles") or [] if isinstance(r, dict)]
        if not roles:
            return {}
        current = [r for r in roles if r.get("end") is None]
        pool = current or roles
        return max(pool, key=lambda r: (str(r.get("end") or "9999"), str(r.get("start") or "")))

    def target_jobs(self) -> list[str]:
        target = self.raw.get("targetJob")
        if isinstance(target, str) and target:
            return [target]
        if isinstance(target, list):
            return [str(t) for t in target]
        return []

    @property
    def twin_group(self) -> str | None:
        value = self.attr("twinGroup")
        return None if value is None else str(value)

    def label(self, job_key: str) -> str | None:
        fit = self.fit.get(job_key)
        return None if not fit else fit.get("label")

    def expected_group(self, job_key: str) -> str | None:
        fit = self.fit.get(job_key)
        return None if not fit else fit.get("expectedRankingGroup")


@dataclass
class Corpus:
    root: Path
    jobs: dict[str, Job]
    resumes: list[Resume]
    warnings: list[str]
    meta: dict[str, Any] = field(default_factory=dict)


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise CorpusError(f"missing {path.name} in {path.parent}") from exc
    except json.JSONDecodeError as exc:
        raise CorpusError(f"{path} is not valid JSON: {exc}") from exc


def load_corpus(root: str | Path) -> Corpus:
    root = Path(root)
    warnings: list[str] = []
    jobs_raw = _read_json(root / "jobs.json")
    if isinstance(jobs_raw, dict):
        jobs_raw = jobs_raw.get("jobs", [])
    if not isinstance(jobs_raw, list) or not jobs_raw:
        raise CorpusError("jobs.json must be a non-empty list of jobs")
    jobs: dict[str, Job] = {}
    for item in jobs_raw:
        if not isinstance(item, dict) or not item.get("key") or not item.get("title"):
            raise CorpusError(f"every job needs a key and a title: {item!r:.120}")
        problems = validate_job(item)
        if problems:
            raise CorpusError(f"job {item['key']} does not fit the backend job DTOs: {'; '.join(problems)}")
        if item["key"] in jobs:
            raise CorpusError(f"duplicate job key {item['key']}")
        jobs[item["key"]] = Job(item["key"], item)

    manifest = _read_json(root / "manifest.json")
    entries = manifest.get("resumes") if isinstance(manifest, dict) else manifest
    if not isinstance(entries, list) or not entries:
        raise CorpusError("manifest.json must hold a non-empty 'resumes' list")
    resumes: list[Resume] = []
    seen: set[str] = set()
    for index, entry in enumerate(entries):
        rid = str(entry.get("id") or "")
        if not rid or rid in seen:
            raise CorpusError(f"resume #{index} has a missing or duplicate id")
        seen.add(rid)
        rel = entry.get("file") or ""
        path = (root / rel) if rel else root / "resumes" / f"{rid}.{entry.get('format', 'pdf')}"
        if not path.exists() and (root / "resumes" / Path(rel).name).exists():
            path = root / "resumes" / Path(rel).name
        if not path.exists():
            warnings.append(f"resume {rid}: file {rel} not found")
        fmt = str(entry.get("format") or path.suffix.lstrip(".")).lower()
        fit = entry.get("fit") or {}
        target = entry.get("targetJob")
        if isinstance(target, str) and target and target not in jobs:
            warnings.append(f"resume {rid}: targetJob {target} is not in jobs.json")
        for job_key, value in fit.items():
            if job_key not in jobs:
                warnings.append(f"resume {rid}: fit names unknown job {job_key}")
            if value.get("label") not in LABELS:
                warnings.append(f"resume {rid}: unknown fit label {value.get('label')!r} for {job_key}")
            group = value.get("expectedRankingGroup")
            if group is not None and group not in RANKING_GROUPS:
                warnings.append(f"resume {rid}: unknown expectedRankingGroup {group!r} for {job_key}")
        resumes.append(Resume(id=rid, file=path, format=fmt, persona=entry.get("persona") or {},
                              gold=entry.get("gold") or {}, fit=fit, raw=entry, index=index))
    meta = {k: v for k, v in manifest.items() if k != "resumes"} if isinstance(manifest, dict) else {}
    return Corpus(root=root, jobs=jobs, resumes=resumes, warnings=warnings, meta=meta)
