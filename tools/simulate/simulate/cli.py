"""``python -m simulate run|cleanup`` (see README.md)."""
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

DEFAULT_BASE_URL = "http://localhost:8080/api/v1"


from .corpus import BACKEND_RESUME_TYPES


def _common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--base-url", default=os.environ.get("SIMULATE_BASE_URL", DEFAULT_BASE_URL),
                   help=f"backend API root (default {DEFAULT_BASE_URL})")
    p.add_argument("--hr-email", default=os.environ.get("SIMULATE_HR_EMAIL", "hr@acme.test"))
    p.add_argument("--hr-password", default=os.environ.get("SIMULATE_HR_PASSWORD"),
                   help="HR password (or env SIMULATE_HR_PASSWORD)")
    p.add_argument("--hr-totp-secret", default=os.environ.get("SIMULATE_HR_TOTP_SECRET"),
                   help="Base32 TOTP secret when the HR account uses two-step verification")
    p.add_argument("--runs-dir", default="runs", help="where run state lives (default ./runs)")
    p.add_argument("--pacing", choices=["auto", "off"], default="auto",
                   help="client-side pacing of rate-limited routes; 'off' only with RATE_LIMIT_ENABLED=false")
    p.add_argument("-v", "--verbose", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m simulate", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="run a simulation against a live stack")
    run.add_argument("--corpus", required=True, help="resumelab output dir (jobs.json, manifest.json, resumes/)")
    _common(run)
    run.add_argument("--jobs", help="comma-separated job keys (default: every job in jobs.json)")
    run.add_argument("--limit", type=int, help="process at most N resumes")
    run.add_argument("--concurrency", type=int, default=4, help="candidates processed in parallel (default 4)")
    run.add_argument("--publish", action=argparse.BooleanOptionalAction, default=True,
                     help="publish draft/on-hold jobs (default on; --no-publish stops after job setup)")
    run.add_argument("--report", help="report directory (default <runs-dir>/<run-id>/report)")
    run.add_argument("--run-id", help="reuse a run id to resume an interrupted run (default: new random id)")
    run.add_argument("--fresh-jobs", action="store_true",
                     help="append the run id to job titles instead of reusing jobs with the same title")
    run.add_argument("--apply-to", choices=["target", "fit", "all"], default="target",
                     help="target (default): the resume's manifest targetJob (falls back to 'fit' when the corpus "
                          "has none); fit: every selected job with a gold label; all: every selected job")
    run.add_argument("--allowed-types", default=",".join(BACKEND_RESUME_TYPES),
                     help="resume file types the backend accepts (app.file-storage.allowed-types; default "
                          f"{','.join(BACKEND_RESUME_TYPES)}); other files are skipped and listed in the report")
    run.add_argument("--include-unsupported", action="store_true",
                     help="upload files of other types too, to test the rejection path (UPLOAD_REJECTED)")
    run.add_argument("--analysis-timeout", type=float, default=300.0, help="seconds per resume (default 300)")
    run.add_argument("--match-timeout", type=float, default=900.0, help="seconds for all matching (default 900)")
    run.add_argument("--poll-interval", type=float, default=2.0, help="first poll interval, grows x1.5 (default 2)")
    run.add_argument("--max-poll-interval", type=float, default=15.0)
    run.add_argument("--request-timeout", type=float, default=60.0)
    run.add_argument("--email-domain", default="sim.recruitpro.test",
                     help="domain for candidate emails (default sim.recruitpro.test; 'persona' keeps the persona's)")
    run.add_argument("--mailpit-url",
                     help="e.g. http://localhost:8025: confirm emails via Mailpit when applying needs a verified email")

    clean = sub.add_parser("cleanup", help="withdraw/close what a run created (best effort)")
    clean.add_argument("--run-id", required=True)
    _common(clean)
    clean.add_argument("--delete-accounts", action="store_true",
                       help="also delete (anonymise) the run's candidate accounts; 5 per 15 min when rate-limited")
    clean.add_argument("--close-reused", action="store_true", help="also close jobs the run reused")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if args.verbose:
        logging.getLogger("httpx").setLevel(logging.INFO)
    else:
        logging.getLogger("httpx").setLevel(logging.WARNING)

    from .api import ApiError

    if args.command == "run":
        from .runner import RunConfig, Runner

        if not args.hr_password:
            print("--hr-password (or SIMULATE_HR_PASSWORD) is required", file=sys.stderr)
            return 2
        cfg = RunConfig(
            corpus=Path(args.corpus), base_url=args.base_url, hr_email=args.hr_email, hr_password=args.hr_password,
            hr_totp_secret=args.hr_totp_secret,
            jobs=[k.strip() for k in args.jobs.split(",") if k.strip()] if args.jobs else None,
            limit=args.limit, concurrency=args.concurrency, publish=args.publish,
            report_dir=Path(args.report) if args.report else None, runs_dir=Path(args.runs_dir),
            run_id=args.run_id, pacing=args.pacing == "auto", analysis_timeout=args.analysis_timeout,
            match_timeout=args.match_timeout, poll_interval=args.poll_interval,
            max_poll_interval=args.max_poll_interval, request_timeout=args.request_timeout,
            email_domain=None if args.email_domain == "persona" else args.email_domain,
            mailpit_url=args.mailpit_url, fresh_jobs=args.fresh_jobs, apply_to=args.apply_to,
            allowed_types=tuple(t.strip().lower().lstrip(".") for t in args.allowed_types.split(",") if t.strip()),
            include_unsupported=args.include_unsupported)
        runner = Runner(cfg)
        try:
            report = runner.run()
        except ApiError as exc:
            print(f"error: {exc}", file=sys.stderr)
            print(f"run state: {Path(args.runs_dir) / runner.run_id / 'state.json'} "
                  f"(resume with --run-id {runner.run_id}, undo with cleanup --run-id {runner.run_id})",
                  file=sys.stderr)
            return 1
        except KeyboardInterrupt:
            print(f"\ninterrupted; resume with --run-id {runner.run_id}", file=sys.stderr)
            return 130
        finally:
            runner.close()
        return 0 if report.get("succeeded", 0) == report.get("processed", 0) and not report.get("stopped") else 3

    from .cleanup import cleanup

    try:
        summary = cleanup(base_url=args.base_url, run_id=args.run_id, runs_dir=args.runs_dir,
                          hr_email=args.hr_email, hr_password=args.hr_password,
                          hr_totp_secret=args.hr_totp_secret, delete_accounts=args.delete_accounts,
                          close_reused=args.close_reused, pacing=args.pacing == "auto")
    except FileNotFoundError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except ApiError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0 if not summary["errors"] else 1
