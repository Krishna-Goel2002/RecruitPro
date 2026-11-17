import hashlib
import json
import re
from pathlib import Path

import pytest

from conftest import AS_OF, extract_text, squash
from resumelab.generate import Options, generate
from resumelab.validate import validate

YM = re.compile(r"^(\d{4})-(\d{2})$")
ORDER = {"strong": 4, "good": 3, "partial": 2, "weak": 1, "unrelated": 0}


def _load(root: Path):
    return json.loads((root / "jobs.json").read_text(encoding="utf-8")), json.loads((root / "manifest.json").read_text(encoding="utf-8"))


def _ordinal(value: str) -> int:
    year, month = YM.match(value).groups()
    return int(year) * 12 + int(month) - 1


# ------------------------------------------------------------------------------------------------ schema / contract
def test_corpus_validates(corpus):
    assert validate(corpus) == []


def test_jobs_match_backend_dto(corpus):
    jobs, _ = _load(corpus)
    for job in jobs:
        assert set(job) == {"key", "title", "department", "location", "jobType", "experienceLevel", "description", "vacancies", "requirements"}
        assert job["jobType"] in {"FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP"}
        assert job["experienceLevel"] in {"ENTRY", "MID", "SENIOR", "LEAD", "EXECUTIVE"}
        for req in job["requirements"]:
            assert set(req) == {"type", "description", "mandatory", "knockout", "minimumYears", "alternatives", "proficiency"}
            assert req["type"] in {"SKILL", "EXPERIENCE", "EDUCATION", "CERTIFICATION", "OTHER"}
            assert not req["knockout"] or req["mandatory"]
            if req["type"] == "EXPERIENCE":
                assert isinstance(req["minimumYears"], int)


def test_manifest_contract_fields(corpus):
    _, manifest = _load(corpus)
    assert {"seed", "generatedAt", "count", "resumes"} <= set(manifest)
    entry = manifest["resumes"][0]
    assert {"id", "file", "format", "template", "profession", "seniority", "quality", "adversarial", "persona", "gold", "fit"} <= set(entry)
    assert set(entry["persona"]) == {"firstName", "lastName", "email", "phone", "city", "gender"}
    assert {"skills", "roles", "totalMonths", "education", "certifications"} <= set(entry["gold"])
    for fit in entry["fit"].values():
        assert {"label", "expectedRankingGroup", "metRequirements"} <= set(fit)


# ------------------------------------------------------------------------------------------------ determinism
def _digest(root: Path) -> dict:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    manifest.pop("generatedAt")
    files = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root / "resumes").iterdir())}
    return {"manifest": manifest, "jobs": (root / "jobs.json").read_text(encoding="utf-8"), "files": files}


def test_same_seed_same_corpus(tmp_path):
    """Byte-identical output for the same seed - also across processes with a different PYTHONHASHSEED."""
    import os
    import subprocess
    import sys
    a, b, c = tmp_path / "a", tmp_path / "b", tmp_path / "c"
    for out, seed in ((a, 5), (c, 6)):
        generate(Options(out=out, count=8, seed=seed, formats=["pdf", "docx", "scan", "txt"], adversarial_rate=0.5, as_of=AS_OF, jobs=2),
                 log=lambda *_: None)
    tools = Path(__file__).resolve().parents[2]
    env = dict(os.environ, PYTHONHASHSEED="12345", PYTHONPATH=str(tools))
    subprocess.run([sys.executable, "-m", "resumelab", "generate", "--out", str(b), "--count", "8", "--seed", "5",
                    "--formats", "pdf,docx,scan,txt", "--adversarial-rate", "0.5", "--as-of", AS_OF, "--jobs", "2"],
                   check=True, cwd=tools, env=env, stdout=subprocess.DEVNULL)
    assert _digest(a) == _digest(b)
    assert _digest(a)["manifest"] != _digest(c)["manifest"]


# ------------------------------------------------------------------------------------------------ timelines
def test_timelines_are_consistent(corpus):
    _, manifest = _load(corpus)
    as_of = _ordinal(manifest["asOf"])
    for entry in manifest["resumes"]:
        roles = entry["gold"]["roles"]
        covered = set()
        for role in roles:
            start = _ordinal(role["start"])
            end = as_of if role["end"] is None else _ordinal(role["end"])
            assert start <= end <= as_of, (entry["id"], role)
            covered.update(range(start, end + 1))
        assert entry["gold"]["totalMonths"] == len(covered), entry["id"]
        degrees = [e for e in entry["gold"]["education"] if e["level"] in {"BACHELOR", "DIPLOMA"} and not e["pursuing"]]
        full_time = [r for r in roles if r["kind"] == "full_time"]
        if degrees and full_time and "changer" not in entry.get("careerShape", []):
            first_job_year = min(_ordinal(r["start"]) for r in full_time) // 12
            assert min(d["year"] for d in degrees) <= first_job_year, entry["id"]
        school = sorted(e["year"] for e in entry["gold"]["education"] if e["level"] == "SCHOOL")
        if len(school) == 2:
            assert school[1] - school[0] == 2, entry["id"]
        for skill in entry["gold"]["skills"]:
            if skill["months"] is not None:
                assert 0 < skill["months"] <= entry["gold"]["totalMonths"], (entry["id"], skill["name"])


# ------------------------------------------------------------------------------------------------ gold vs document
def test_gold_skills_and_evidence_appear_in_document_text(corpus):
    _, manifest = _load(corpus)
    checked = evidence_hits = evidence_total = 0
    for entry in manifest["resumes"]:
        if entry["format"] == "scan":
            continue
        text = squash(extract_text(corpus / entry["file"]))
        for skill in entry["gold"]["skills"]:
            assert squash(skill["surface"]) in text, (entry["id"], entry["template"], skill["name"], skill["surface"])
            checked += 1
            evidence_total += 1
            evidence_hits += squash(skill["evidence"]) in text
        for role in entry["gold"]["roles"]:
            assert squash(role["employer"]) in text and squash(role["title"]) in text, (entry["id"], role)
    assert checked > 200
    assert evidence_hits / evidence_total >= 0.97


def test_hidden_text_is_never_gold(corpus):
    _, manifest = _load(corpus)
    for entry in manifest["resumes"]:
        if "near_empty" in entry["adversarial"]:
            assert len(entry["gold"]["skills"]) <= 3 and entry["gold"]["roles"] == []
        for skill in entry["gold"]["skills"]:
            assert skill["section"] not in {"INTERESTS", "REFERENCES", "DECLARATION"}


def test_scans_have_no_text_layer(corpus):
    _, manifest = _load(corpus)
    scans = [e for e in manifest["resumes"] if e["format"] == "scan"]
    assert scans
    for entry in scans:
        assert extract_text(corpus / entry["file"]).strip() == ""


# ------------------------------------------------------------------------------------------------ fit labels
def _label(job, met):
    total = got = 0
    knockouts = True
    for index, req in enumerate(job["requirements"]):
        if req["type"] == "OTHER":
            assert index not in met
            continue
        weight = 2 if req["mandatory"] else 1
        total += weight
        if index in met:
            got += weight
        elif req["knockout"]:
            knockouts = False
    coverage = got / total
    if knockouts and coverage >= 0.9:
        return "strong", knockouts
    if knockouts and coverage >= 0.65:
        return "good", knockouts
    if coverage >= 0.4 or (knockouts and coverage > 0):
        return "partial", knockouts
    return ("weak" if coverage >= 0.2 else "unrelated"), knockouts


def test_fit_labels_follow_requirements(corpus):
    jobs, manifest = _load(corpus)
    by_key = {job["key"]: job for job in jobs}
    hits = 0
    for entry in manifest["resumes"]:
        for key, fit in entry["fit"].items():
            label, knockouts = _label(by_key[key], set(fit["metRequirements"]))
            assert fit["label"] == label, (entry["id"], key)
            assert fit["expectedRankingGroup"] == ("RANKED" if knockouts else "MUST_HAVES_UNVERIFIED")
        hits += entry["fit"][entry["targetJob"]]["label"] == entry["targetLabel"]
    assert hits / len(manifest["resumes"]) >= 0.85


def test_met_skill_requirements_are_visible(corpus):
    """Every met SKILL requirement names something the gold skills (or a narrower skill) contain."""
    from resumelab.taxonomy import load
    taxonomy = load()
    jobs, manifest = _load(corpus)
    for entry in manifest["resumes"]:
        held = {s["name"].lower() for s in entry["gold"]["skills"]}
        held_ids = {s["taxonomyId"] for s in entry["gold"]["skills"] if s["taxonomyId"]}
        for job in jobs:
            for index in entry["fit"][job["key"]]["metRequirements"]:
                req = job["requirements"][index]
                if req["type"] != "SKILL":
                    continue
                options = [req["description"]] + req["alternatives"]
                found = False
                for option in options:
                    names = [skill.name.lower() for skill, _ in taxonomy.mentions(option)] if taxonomy else []
                    ids = {skill.id for skill, _ in taxonomy.mentions(option)} if taxonomy else set()
                    descendants = {i for i in held_ids if taxonomy and taxonomy.ancestors(i) & ids}
                    if set(names) & held or descendants or any(n in held for n in names):
                        found = True
                assert found, (entry["id"], job["key"], req["description"])


def test_label_spread_and_adversarial_mix(corpus):
    _, manifest = _load(corpus)
    labels = {entry["fit"][entry["targetJob"]]["label"] for entry in manifest["resumes"]}
    assert {"strong", "partial", "unrelated"} <= labels
    adversarial = {a for entry in manifest["resumes"] for a in entry["adversarial"]}
    assert len(adversarial) >= 3


# ------------------------------------------------------------------------------------------------ files open
def test_every_format_opens(corpus):
    from docx import Document
    from pypdf import PdfReader
    _, manifest = _load(corpus)
    formats = set()
    for entry in manifest["resumes"]:
        path = corpus / entry["file"]
        formats.add(entry["format"])
        if path.suffix == ".pdf":
            assert len(PdfReader(str(path)).pages) >= 1
        elif path.suffix == ".docx":
            assert Document(str(path)).paragraphs is not None
        elif path.suffix == ".txt":
            assert path.read_text(encoding="utf-8").strip()
    assert formats == {"pdf", "docx", "scan", "txt"}


def test_templates_never_mention_undeclared_skills():
    """Literal template text may only name taxonomy skills it declares with [[Skill|surface]]."""
    from resumelab.data.professions import registry
    from resumelab.taxonomy import load
    taxonomy = load()
    if taxonomy is None:
        pytest.skip("taxonomy not available")
    declared_re = re.compile(r"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]")
    problems = []
    for prof in registry().values():
        texts = list(prof.bullets) + list(prof.weak) + list(prof.summaries) + [t for t, _ in prof.fixed] + list(prof.projects) + list(prof.publications)
        for cert in prof.certs:  # declared certification skills must really be named in the certificate title
            for name in cert.skills:
                if name.lower() not in cert.name.lower():
                    problems.append((prof.key, name, cert.name))
        for text in texts:
            declared = {m.group(1) for m in declared_re.finditer(text)}
            literal = re.sub(r"\{[^}]+\}", " ", declared_re.sub(" ", text))
            for skill, surface in taxonomy.mentions(literal):
                if skill.name not in declared:
                    problems.append((prof.key, skill.name, text))
    assert problems == []


def test_llm_rephrase_keeps_gold(monkeypatch):
    from resumelab.compose import Bullets, Document, Header, Line, Section
    from resumelab.llm import Rephraser
    monkeypatch.setenv("GROQ_API_KEY", "test-key")
    rephraser = Rephraser("groq", log=lambda *_: None)
    keep = Line("Built dashboards in Power BI for sales", ("Power BI",), {"Power BI": "Power BI"})
    drop = Line("Wrote SQL reports", ("SQL",), {"SQL": "SQL"})
    sneak = Line("Helped the team", (), {})
    doc = Document(Header("A B", None, []), [Section("EXPERIENCE", "Experience", [Bullets([keep, drop, sneak])])], "ats_classic", {})
    monkeypatch.setattr(rephraser, "_call", lambda prompt: json.dumps(
        ["Created Power BI dashboards for the sales team", "Wrote weekly reports", "Helped the team with Kubernetes"]))
    rephraser.rephrase(doc)
    assert keep.text == "Created Power BI dashboards for the sales team"
    assert drop.text == "Wrote SQL reports"  # lost its skill surface -> rejected
    assert sneak.text == "Helped the team"  # introduced a new skill -> rejected


def test_validate_reports_broken_corpus(tmp_path, corpus):
    import shutil
    broken = tmp_path / "broken"
    shutil.copytree(corpus, broken)
    manifest = json.loads((broken / "manifest.json").read_text(encoding="utf-8"))
    manifest["resumes"][0]["gold"]["roles"].append({"title": "X", "employer": "Y", "start": "2020-13", "end": None})
    manifest["resumes"][1]["fit"] = {}
    (broken / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    jobs = json.loads((broken / "jobs.json").read_text(encoding="utf-8"))
    jobs[0]["jobType"] = "FULLTIME"
    (broken / "jobs.json").write_text(json.dumps(jobs), encoding="utf-8")
    problems = validate(broken)
    assert any("jobType" in p for p in problems)
    assert any("bad dates" in p for p in problems)
    assert any(".fit" in p for p in problems)
