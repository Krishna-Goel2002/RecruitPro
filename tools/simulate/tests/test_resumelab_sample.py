"""The real ResumeLab sample corpus (tools/resumelab/sample: 60 resumes, 4 jobs) through the runner.

Checks that the runner reads ResumeLab's actual field names and maps jobs exactly onto the backend DTOs, then runs
the whole simulation against the fake backend (real PDF/DOCX files are uploaded) and checks the report.
"""
import csv
import json
from collections import Counter
from pathlib import Path

import pytest

from simulate.clock import FakeClock
from simulate.corpus import (BACKEND_RESUME_TYPES, JOB_FIELDS, REQUIREMENT_FIELDS, load_corpus, validate_job)
from simulate.runner import RunConfig, Runner
from tests.fake_api import (JOB_CREATE_FIELDS, REQUIREMENT_INPUT_FIELDS, FakeRecruitPro, Faults, gold_scores)

SAMPLE = Path(__file__).resolve().parents[2] / "resumelab" / "sample"

pytestmark = pytest.mark.skipif(not (SAMPLE / "manifest.json").exists(), reason="tools/resumelab/sample missing")


def manifest():
    return json.loads((SAMPLE / "manifest.json").read_text(encoding="utf-8"))


def jobs_json():
    return json.loads((SAMPLE / "jobs.json").read_text(encoding="utf-8"))


def test_reads_resumelab_field_names():
    corpus = load_corpus(SAMPLE)
    raw = {r["id"]: r for r in manifest()["resumes"]}
    assert not corpus.warnings
    assert len(corpus.resumes) == 60 and len(corpus.jobs) == 4
    assert corpus.meta["asOf"] == "2026-11" and corpus.meta["seed"] == 2026
    for r in corpus.resumes:
        entry = raw[r.id]
        assert r.file.exists()
        assert r.attr("template") == entry["template"] and r.attr("quality") == entry["quality"]
        assert r.attr("profession") == entry["profession"] and r.attr("seniority") == entry["seniority"]
        assert r.traits == entry["adversarial"]
        assert r.target_jobs() == [entry["targetJob"]]
        assert set(r.fit) == set(corpus.jobs)
        for key, fit in entry["fit"].items():
            assert r.label(key) == fit["label"] and r.expected_group(key) == fit["expectedRankingGroup"]
        assert r.years_of_experience == entry["gold"]["totalMonths"] // 12
        assert r.twin_group is None  # ResumeLab writes no twin groups
    assert len({r.attr("template") for r in corpus.resumes}) == 11
    assert {r.attr("quality") for r in corpus.resumes} == {"polished", "average", "poor"}

    by_id = {r.id: r for r in corpus.resumes}
    # a scan is an image-only .pdf: format "scan", uploaded as pdf
    scans = [r for r in corpus.resumes if r.format == "scan"]
    assert scans and all(r.ext == "pdf" for r in scans)
    assert Counter(r.ext for r in corpus.resumes) == Counter(
        Path(e["file"]).suffix.lstrip(".") for e in raw.values())
    # manipulation vs robustness traits
    assert by_id["r0002"].adversarial == "inflated_claims" and not by_id["r0002"].flag_expected
    assert by_id["r0038"].adversarial == "hidden_keywords" and by_id["r0038"].flag_expected
    assert by_id["r0019"].adversarial == "inflated_claims,prompt_injection" and by_id["r0019"].flag_expected
    assert by_id["r0041"].traits == ["near_empty"] and by_id["r0041"].adversarial is None
    # current role for the profile
    role = by_id["r0001"].current_role()
    assert role["end"] is None and role["title"] and role["employer"]


def test_jobs_map_exactly_onto_backend_dtos():
    corpus = load_corpus(SAMPLE)
    assert set(JOB_FIELDS) == JOB_CREATE_FIELDS
    assert set(REQUIREMENT_FIELDS) == REQUIREMENT_INPUT_FIELDS - {"id"}
    for raw in jobs_json():
        assert validate_job(raw) == []
        job = corpus.jobs[raw["key"]]
        payload = job.create_payload()
        expected = {k: v for k, v in raw.items() if k not in ("key", "requirements") and v is not None}
        assert payload == expected  # every jobs.json field except key is a JobCreateRequest field, unchanged
        reqs = job.requirements()
        assert len(reqs) == len(raw["requirements"])
        for got, src in zip(reqs, raw["requirements"]):
            assert got == {k: v for k, v in src.items() if v is not None}
            assert set(got) <= set(REQUIREMENT_FIELDS)


def test_validate_job_reports_dto_violations():
    bad = dict(jobs_json()[0], jobType="FULLTIME", department="x" * 101,
               requirements=[{"type": "skill", "description": "", "minimumYears": 60, "proficiency": "GURU",
                              "alternatives": list("abcdefghijk")}])
    problems = validate_job(bad)
    joined = " | ".join(problems)
    for needle in ("jobType", "department", "description is blank", "minimumYears", "proficiency", "alternatives"):
        assert needle in joined
    assert "type 'skill'" not in joined  # type is upper-cased like the enum


def config(tmp_path, **kw):
    base = dict(corpus=SAMPLE, base_url="http://fake/api/v1", hr_email="hr@acme.test", hr_password="ChangeMe-123!",
                runs_dir=tmp_path / "runs", report_dir=tmp_path / "report", concurrency=4, pacing=False,
                analysis_timeout=120, match_timeout=600, poll_interval=1, max_poll_interval=5)
    base.update(kw)
    return RunConfig(**base)


def test_plan_apply_modes_and_skipped_formats(tmp_path):
    corpus = load_corpus(SAMPLE)
    keys = list(corpus.jobs)
    accepted = [r for r in corpus.resumes if r.ext in BACKEND_RESUME_TYPES]
    for mode, per_resume in (("target", 1), ("fit", 4), ("all", 4)):
        runner = Runner(config(tmp_path, apply_to=mode), out=lambda _: None)
        try:
            plan = runner._select_resumes(corpus, keys)
        finally:
            runner.close()
        assert len(plan) == len(accepted) == 58
        assert all(len(targets) == per_resume for _, targets in plan)
        assert sorted(x["resumeId"] for x in runner.skipped) == ["r0033", "r0059"]
        if mode == "target":
            assert all(targets == r.target_jobs() for r, targets in plan)
    runner = Runner(config(tmp_path, jobs=["job-04-backend-mid"]), out=lambda _: None)
    try:
        plan = runner._select_resumes(corpus, ["job-04-backend-mid"])
    finally:
        runner.close()
    assert plan and all(t == ["job-04-backend-mid"] for _, t in plan)


def test_sample_corpus_end_to_end_report(tmp_path):
    corpus = load_corpus(SAMPLE)
    stems = lambda pred: {r.file.stem for r in corpus.resumes if pred(r)}  # noqa: E731
    faults = Faults(scanned=stems(lambda r: r.format == "scan"),
                    injected=stems(lambda r: "prompt_injection" in r.traits),
                    hidden=stems(lambda r: "hidden_keywords" in r.traits))
    clock = FakeClock()
    fake = FakeRecruitPro(clock, gold_scores(SAMPLE), faults)
    lines: list[str] = []
    runner = Runner(config(tmp_path), transport=fake.transport(), clock=clock, out=lines.append)
    try:
        report = runner.run()
    finally:
        runner.close()

    # jobs: exactly the DTO fields, requirements as in jobs.json, published
    assert not fake.unknown_fields.get("POST jobs") and not fake.unknown_fields.get("PUT jobs/{id}/requirements")
    by_title = {j["title"]: j for j in fake.jobs.values()}
    for raw in jobs_json():
        job = by_title[raw["title"]]
        assert job["status"] == "PUBLISHED" and job["jobType"] == raw["jobType"]
        assert job["experienceLevel"] == raw["experienceLevel"] and job["vacancies"] == raw["vacancies"]
        assert [r["description"] for r in job["requirements"]] == [r["description"] for r in raw["requirements"]]
        assert [r["knockout"] for r in job["requirements"]] == [r["knockout"] for r in raw["requirements"]]

    # candidates: the two .txt resumes are skipped, everything else uploads its real file and applies once
    assert report["planned"] == report["processed"] == report["succeeded"] == 58
    assert sorted(x["resumeId"] for x in report["skipped"]) == ["r0033", "r0059"]
    assert len(fake.uploads) == 58 and not any(n.endswith(".txt") for n, _ in fake.uploads)
    types = {n.rsplit(".", 1)[1]: t for n, t in fake.uploads}
    assert types == {"pdf": "application/pdf",
                     "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}
    assert len(fake.applications) == 58 and report["overall"]["applications"] == 58
    assert not fake.unknown_fields.get("PUT candidates/me")
    profiles = list(fake.profiles.values())
    assert len(profiles) == 58 and all("," not in p.get("city", "") for p in profiles)
    assert any(p.get("state") for p in profiles)
    with_roles = sum(1 for r in corpus.resumes if r.ext in BACKEND_RESUME_TYPES and r.gold.get("roles"))
    assert sum(1 for p in profiles if p.get("currentDesignation") and p.get("currentCompany")) == with_roles

    # metrics: the fake matcher follows the gold, so the ranking is near ideal; scans are UNSCORED (no OCR)
    assert set(report["jobMetrics"]) == set(corpus.jobs)
    assert report["overall"]["ndcg@all"] > 0.9
    n_scan = sum(1 for r in corpus.resumes if r.format == "scan")
    ga = report["overall"]["groupAgreement"]
    assert ga["n"] == 58 and ga["agree"] == 58 - n_scan
    assert report["parser"]["format"]["scan"]["statuses"] == {"NEEDS_OCR": n_scan}
    assert set(report["parser"]["quality"]) == {"polished", "average", "poor"}
    assert len(report["parser"]["template"]) >= 10
    assert "near_empty" in report["parser"]["traits"] and "none" in report["parser"]["traits"]

    adv = report["adversarial"]
    manipulated = [r for r in corpus.resumes if r.adversarial and r.ext in BACKEND_RESUME_TYPES]
    assert adv["n"] == len(manipulated)
    assert adv["flagExpected"] == sum(1 for r in manipulated if r.flag_expected)
    assert adv["flagRate"] == 1.0
    assert {"inflated_claims", "prompt_injection", "hidden_keywords"} <= set(adv["byKind"])
    assert adv["byKind"]["inflated_claims"]["flagRate"] < 1.0  # not expected to be flagged
    assert report["fairness"]["pairs"] == 0

    # report files
    rep = tmp_path / "report"
    md = (rep / "report.md").read_text(encoding="utf-8")
    for heading in ("## Ranking quality", "## Ranking-group agreement", "## Parser outcomes", "### By template",
                    "### By traits", "## Adversarial resumes", "## Counterfactual fairness", "## Skipped resumes",
                    "## Processing time", "## Failures and warnings"):
        assert heading in md
    assert "| r0033 | txt | r0033.txt | job-01-nurse-mid |" in md
    for key in corpus.jobs:
        assert f"### Top 10: {key}" in md
    with (rep / "candidates.csv").open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert len(rows) == 58
    assert {r["template"] for r in rows} >= {"ats_classic", "indian_traditional"}
    assert all(r["profession"] and r["seniority"] for r in rows)
    data = json.loads((rep / "report.json").read_text(encoding="utf-8"))
    assert data["corpusMeta"]["generator"].startswith("resumelab")
    assert any("skipping 2 resume(s)" in line for line in lines)
    assert any("== simulation" in line for line in lines)
