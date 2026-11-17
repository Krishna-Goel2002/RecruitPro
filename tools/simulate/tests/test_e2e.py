"""End to end against the in-memory fake backend (no network): the whole run, retries, timeouts, cleanup."""
import csv
import json
from pathlib import Path

import pytest

from simulate.clock import FakeClock
from simulate.cleanup import cleanup
from simulate.runner import RunConfig, Runner
from tests.fake_api import FakeMailpit, FakeRecruitPro, Faults, gold_scores as label_scores

BASE = "http://fake/api/v1"


def config(corpus_dir, tmp_path, **kw):
    base = dict(corpus=corpus_dir, base_url=BASE, hr_email="hr@acme.test", hr_password="ChangeMe-123!",
                runs_dir=tmp_path / "runs", report_dir=tmp_path / "report", concurrency=3,
                analysis_timeout=120, match_timeout=300, poll_interval=1, max_poll_interval=5)
    base.update(kw)
    return RunConfig(**base)


def run(cfg, fake, clock, **kw):
    lines = []
    runner = Runner(cfg, transport=fake.transport(), clock=clock, out=lines.append, **kw)
    try:
        return runner.run(), runner, lines
    finally:
        runner.close()


def default_faults():
    return Faults(scanned={"r009"}, injected={"r006"})


def test_full_flow_with_rate_limits_errors_and_expiring_tokens(corpus_dir, tmp_path):
    clock = FakeClock()
    faults = default_faults()
    faults.register_limit = (2, 60.0)        # stricter than the client pacing -> real 429 + Retry-After
    faults.upload_503_once = True
    faults.lost_apply_response_once = True   # server applied, client saw a timeout -> retry gets 409
    faults.lost_register_response_once = True
    faults.token_max_uses = 6                # tokens "expire" quickly -> 401 -> sign in again
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), faults)
    # --include-unsupported: the .txt resume is uploaded too, to exercise the rejection path
    report, runner, lines = run(config(corpus_dir, tmp_path, include_unsupported=True), fake, clock)

    # jobs: created, requirements set, published
    assert {j["title"] for j in fake.jobs.values()} == {"Senior Backend Engineer (Java)", "Data Analyst"}
    assert all(j["status"] == "PUBLISHED" and len(j["requirements"]) == 3 for j in fake.jobs.values())
    assert all(j["created"] for j in report["jobs"].values())

    # candidates: unique run-tagged emails, candidate portal header, no duplicate applications
    emails = [u for u, v in fake.users.items() if v["role"] == "CANDIDATE"]
    assert len(emails) == 10 and all(f"+{runner.run_id}-" in e and e.endswith("@sim.recruitpro.test")
                                      for e in emails)
    pairs = [(a["owner"], a["jobId"]) for a in fake.applications.values()]
    assert len(pairs) == len(set(pairs)) == 14  # 9 uploadable resumes: 5 x2 jobs + 4 x1
    assert all(p.get("city") for p in fake.profiles.values())

    # outcomes
    assert report["processed"] == 10 and report["succeeded"] == 9
    failure = next(f for f in report["failures"] if f["stage"] == "upload")
    assert failure["resumeId"] == "r010" and "400" in failure["error"]
    assert report["parser"]["format"]["txt"]["statuses"] == {"UPLOAD_REJECTED": 1}
    assert report["parser"]["quality"]["scanned"]["statuses"] == {"NEEDS_OCR": 1}
    assert report["parser"]["format"]["pdf"]["n"] == 7

    # retries were exercised
    api = report["api"]
    assert api["retries_429"] >= 1 and api["retry_after_seconds"] > 0
    assert api["retries_5xx"] >= 1 and api["retries_transport"] >= 2 and api["reauth"] >= 1

    # metrics: the fake matcher follows the gold labels, so the ranking is ideal except the unscored scan
    backend = report["jobMetrics"]["backend-java"]
    assert backend["n"] == 9 and backend["ndcg@10"] > 0.95
    assert report["jobMetrics"]["data-analyst"]["ndcg@all"] == pytest.approx(1.0)
    assert report["overall"]["precision@5"] > 0.5
    scan = next(r for r in backend["top10"] if r["resumeId"] == "r009")
    assert scan["group"] == "UNSCORED" and scan["position"] == 9
    ga = report["overall"]["groupAgreement"]
    assert ga["n"] == 14 and 0 < ga["accuracy"] < 1  # unrelated resumes are scored (MUST_HAVES_UNVERIFIED)
    assert report["adversarial"]["n"] == 1 and report["adversarial"]["roseRate"] == 0
    assert report["adversarial"]["flagRate"] == 1.0
    assert report["fairness"]["pairs"] == 1 and report["fairness"]["flipRate"] == 0
    assert report["timings"]["analysisSeconds"]["n"] == 9 and report["timings"]["matchSeconds"]["n"] == 14

    # files
    rep = tmp_path / "report"
    data = json.loads((rep / "report.json").read_text(encoding="utf-8"))
    assert data["runId"] == runner.run_id
    md = (rep / "report.md").read_text(encoding="utf-8")
    for heading in ("## Ranking quality", "## Ranking-group agreement", "## Parser outcomes",
                    "## Adversarial resumes", "## Counterfactual fairness", "## Processing time", "## Failures"):
        assert heading in md
    with (rep / "candidates.csv").open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert len(rows) == 14 and {r["jobKey"] for r in rows} == {"backend-java", "data-analyst"}
    assert any("== simulation" in line for line in lines)

    # state is complete enough for cleanup
    state = json.loads((tmp_path / "runs" / runner.run_id / "state.json").read_text(encoding="utf-8"))
    assert sum(len(c.get("applications", {})) for c in state["candidates"].values()) == 14


def test_unsupported_formats_are_skipped_by_default(corpus_dir, tmp_path):
    clock = FakeClock()
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), default_faults())
    report, _, lines = run(config(corpus_dir, tmp_path), fake, clock)
    assert report["processed"] == report["succeeded"] == 9 and report["planned"] == 9
    assert [(x["resumeId"], x["ext"]) for x in report["skipped"]] == [("r010", "txt")]
    assert not any(name.endswith(".txt") for name, _ in fake.uploads)
    assert "txt" not in report["parser"]["format"] and not report["failures"]
    assert any("skipping 1 resume" in line for line in lines)
    md = (tmp_path / "report" / "report.md").read_text(encoding="utf-8")
    assert "## Skipped resumes" in md and "| r010 | txt | r010.txt |" in md


def test_timeouts_are_reported_not_fatal(corpus_dir, tmp_path):
    clock = FakeClock()
    faults = default_faults()
    faults.never_complete = {"r002"}
    faults.match_never_settles = {"r003"}
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), faults)
    report, _, lines = run(config(corpus_dir, tmp_path, analysis_timeout=30, match_timeout=60, limit=4),
                           fake, clock)
    by_stage = {(f["resumeId"], f["stage"]) for f in report["failures"]}
    assert ("r003", "match") in by_stage
    rows = [r for r in csv.DictReader((tmp_path / "report" / "candidates.csv").open(encoding="utf-8"))]
    r002 = [r for r in rows if r["resumeId"] == "r002"]
    assert r002 and all(r["analysisStatus"] == "PROCESSING" for r in r002)  # HR still sees it processing
    assert report["parser"]["format"]["docx"]["statuses"] == {"TIMEOUT": 1}
    assert any("matching timeout" in line for line in lines)
    assert {r["matchStatus"] for r in rows if r["resumeId"] == "r003"} == {"MATCH_TIMEOUT"}


def test_reuses_job_by_title_syncs_requirements_and_resumes_run(corpus_dir, tmp_path):
    clock = FakeClock()
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), default_faults())
    old = fake.add_job({"title": "Data Analyst", "description": "old", "department": "X", "location": "Y",
                        "jobType": "FULL_TIME", "experienceLevel": "MID", "vacancies": 1}, status="DRAFT",
                       requirements=[{"id": 1, "type": "SKILL", "description": "Excel", "mandatory": True,
                                      "knockout": True, "alternatives": [], "displayOrder": 0}])
    fake.add_job({"title": "Data Analyst", "description": "closed", "department": "X", "location": "Y",
                  "jobType": "FULL_TIME", "experienceLevel": "MID", "vacancies": 1}, status="CLOSED")
    cfg = config(corpus_dir, tmp_path, jobs=["data-analyst"], run_id="simfixed1")
    report, _, _ = run(cfg, fake, clock)
    job = report["jobs"]["data-analyst"]
    assert job["id"] == old["id"] and job["created"] is False and job["status"] == "PUBLISHED"
    assert [r["description"] for r in fake.jobs[old["id"]]["requirements"]] == ["SQL", "Python", "Tableau"]
    assert report["processed"] == 5  # resumes with a data-analyst fit
    registered = fake.counts["POST auth/register"]
    applications = len(fake.applications)

    # same run id again: nothing is registered, uploaded or applied twice
    report2, _, _ = run(cfg, fake, clock)
    assert fake.counts["POST auth/register"] == registered
    assert len(fake.applications) == applications
    assert report2["succeeded"] == report["succeeded"]
    assert report2["jobMetrics"]["data-analyst"]["ndcg@all"] == pytest.approx(
        report["jobMetrics"]["data-analyst"]["ndcg@all"])


def test_fresh_jobs_and_no_publish_stop_after_setup(corpus_dir, tmp_path):
    clock = FakeClock()
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), default_faults())
    report, runner, lines = run(config(corpus_dir, tmp_path, fresh_jobs=True, publish=False), fake, clock)
    assert report["stopped"] and not fake.applications
    assert all(j["title"].endswith(f"[{runner.run_id}]") and j["status"] == "DRAFT" for j in fake.jobs.values())
    assert (tmp_path / "report" / "report.md").exists()
    assert fake.counts["POST auth/register"] == 0


def test_adversarial_rise_is_detected(corpus_dir, tmp_path):
    clock = FakeClock()
    fake = FakeRecruitPro(clock, label_scores(corpus_dir, boost={"r006"}), default_faults())
    report, _, _ = run(config(corpus_dir, tmp_path, jobs=["backend-java"]), fake, clock)
    adv = report["adversarial"]
    assert adv["n"] == 1 and adv["roseRate"] == 1.0 and adv["items"][0]["position"] == 1
    assert adv["items"][0]["inTopKLowFit"]


def test_verified_email_required_uses_mailpit(corpus_dir, tmp_path):
    clock = FakeClock()
    faults = default_faults()
    faults.require_verified_email = True
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), faults)
    cfg = config(corpus_dir, tmp_path, limit=2, jobs=["backend-java"], mailpit_url="http://mailpit:8025")
    report, _, _ = run(cfg, fake, clock, mailpit_transport=FakeMailpit(fake).transport())
    assert report["succeeded"] == 2 and len(fake.applications) == 2

    # without Mailpit the 403 is reported with a hint
    fake2 = FakeRecruitPro(FakeClock(), label_scores(corpus_dir), faults)
    report2, _, _ = run(config(corpus_dir, tmp_path / "b", limit=1, jobs=["backend-java"]), fake2, FakeClock())
    assert report2["succeeded"] == 0
    assert "AUTH_REQUIRE_VERIFIED_EMAIL_TO_APPLY" in report2["failures"][0]["error"]


def test_hr_mfa_with_totp(corpus_dir, tmp_path):
    clock = FakeClock()
    faults = default_faults()
    faults.hr_mfa_secret = "JBSWY3DPEHPK3PXP"
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), faults)
    report, _, _ = run(config(corpus_dir, tmp_path, limit=1, hr_totp_secret="JBSWY3DPEHPK3PXP"), fake, clock)
    assert report["succeeded"] == 1


def test_cleanup_withdraws_closes_and_deletes(corpus_dir, tmp_path):
    clock = FakeClock()
    fake = FakeRecruitPro(clock, label_scores(corpus_dir), default_faults())
    reused = fake.add_job({"title": "Data Analyst", "description": "d", "department": "X", "location": "Y",
                           "jobType": "FULL_TIME", "experienceLevel": "MID", "vacancies": 1}, status="PUBLISHED")
    report, runner, _ = run(config(corpus_dir, tmp_path), fake, clock)
    lines = []
    summary = cleanup(base_url=BASE, run_id=runner.run_id, runs_dir=str(tmp_path / "runs"),
                      hr_email="hr@acme.test", hr_password="ChangeMe-123!", delete_accounts=True,
                      transport=fake.transport(), clock=clock, out=lines.append)
    assert summary["withdrawn"] == len(fake.applications) == 14
    assert all(a["status"] == "WITHDRAWN" for a in fake.applications.values())
    assert summary["accountsDeleted"] == 9   # the .txt resume was skipped: no account
    assert sum(1 for u in fake.users.values() if u.get("deleted")) == 9
    created = [j for j in fake.jobs.values() if j["id"] != reused["id"]]
    assert all(j["status"] == "CLOSED" for j in created) and fake.jobs[reused["id"]]["status"] == "PUBLISHED"
    assert any("reused" in s for s in summary["skipped"])
    # DELETE /candidates/me is paced at 5 per 15 minutes
    assert summary["api"]["paced_seconds"] >= 900

    # idempotent second pass
    again = cleanup(base_url=BASE, run_id=runner.run_id, runs_dir=str(tmp_path / "runs"),
                    hr_email="hr@acme.test", hr_password="ChangeMe-123!", delete_accounts=True,
                    transport=fake.transport(), clock=clock, out=lines.append)
    assert again["withdrawn"] == 0 and again["accountsDeleted"] == 0 and not again["errors"]


def test_cli_parser_defaults():
    from simulate.cli import build_parser
    args = build_parser().parse_args(["run", "--corpus", "c", "--hr-password", "x", "--jobs", "a,b",
                                      "--no-publish", "--pacing", "off"])
    assert args.jobs == "a,b" and args.publish is False and args.pacing == "off" and args.concurrency == 4
    assert args.base_url == "http://localhost:8080/api/v1" and args.hr_email == "hr@acme.test"
    args = build_parser().parse_args(["cleanup", "--run-id", "simabc"])
    assert args.run_id == "simabc" and not args.delete_accounts
