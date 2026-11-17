"""Command line: ``python -m resumelab generate|validate|list``."""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path


def _csv(value: str | None) -> list[str] | None:
    return [item.strip() for item in value.split(",") if item.strip()] if value else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m resumelab", description="Realistic resume + job corpus generator with gold labels")
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("generate", help="generate a corpus")
    gen.add_argument("--out", required=True, type=Path)
    gen.add_argument("--count", type=int, default=60)
    gen.add_argument("--seed", type=int, default=1)
    gen.add_argument("--professions", help="comma list (see `list`); default: all")
    gen.add_argument("--formats", help="comma list of pdf,docx,scan,txt,doc; default: pdf 55%%, docx 30%%, scan 10%%, txt 5%%")
    gen.add_argument("--adversarial-rate", type=float, default=0.1)
    gen.add_argument("--llm", default="none", choices=("none", "gemini", "groq"), help="optional LLM rephrasing of free text")
    gen.add_argument("--as-of", help="YYYY-MM used for 'Present' (default: current month); pin it for byte-identical output")
    gen.add_argument("--jobs", type=int, help="number of jobs (default: count/15, at least 1)")
    val = sub.add_parser("validate", help="schema-check a generated corpus")
    val.add_argument("dir", type=Path)
    sub.add_parser("list", help="list professions and templates")
    args = parser.parse_args(argv)

    if args.command == "list":
        from resumelab.compose import TEMPLATES
        from resumelab.data.professions import registry
        print("Professions:")
        for key, prof in registry().items():
            print(f"  {key:22} {prof.label} ({prof.family})")
        print("Templates:")
        for name, (formats, text) in TEMPLATES.items():
            print(f"  {name:22} {'/'.join(formats):18} {text}")
        return 0
    if args.command == "validate":
        from resumelab.validate import validate
        problems = validate(args.dir)
        if problems:
            print(f"INVALID ({len(problems)} problems):")
            for problem in problems[:50]:
                print(f"  - {problem}")
            return 1
        print(f"OK: {args.dir} is a valid ResumeLab corpus")
        return 0
    from resumelab.generate import Options, generate
    formats = _csv(args.formats)
    if formats:
        bad = [f for f in formats if f not in {"pdf", "docx", "scan", "txt", "doc"}]
        if bad:
            parser.error(f"unknown format(s): {', '.join(bad)}")
    opts = Options(out=args.out, count=args.count, seed=args.seed, professions=_csv(args.professions), formats=formats,
                   adversarial_rate=args.adversarial_rate, llm=args.llm, as_of=args.as_of, jobs=args.jobs)
    print(f"ResumeLab: generating {opts.count} resumes (seed {opts.seed}) into {opts.out}")
    manifest = generate(opts)
    resumes = manifest["resumes"]
    print(f"Wrote {len(resumes)} resumes, jobs.json and manifest.json (as of {manifest['asOf']})")
    for key in ("format", "template", "quality", "profession"):
        counts = Counter(r[key] for r in resumes)
        print(f"  {key:10} " + ", ".join(f"{k}={v}" for k, v in counts.most_common()))
    labels = Counter(fit["label"] for r in resumes for job, fit in r["fit"].items() if job == r["targetJob"])
    print("  target-job labels " + ", ".join(f"{k}={v}" for k, v in labels.most_common()))
    adv = Counter(a for r in resumes for a in r["adversarial"])
    print("  adversarial " + (", ".join(f"{k}={v}" for k, v in adv.most_common()) or "none"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
