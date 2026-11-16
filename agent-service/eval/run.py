"""Evaluation harness: ``python -m eval.run [--check] [--json FILE] [--verbose]``.

Runs every SYNTHETIC gold case (eval/cases.py) through the real verifier and the shared verdict layer, offline, and
reports claim-level metrics: false-corroboration rate (predicted VERIFIED where gold is not VERIFIED, over all
non-VERIFIED gold claims), false-discovery share of VERIFIED predictions, Cohen's kappa over the five verdicts,
per-verdict precision/recall, and per-verifier agreement. ``--check`` exits 1 when FCR > 0.02 or kappa < 0.70, or
when any case crashes.

Synthetic cases are far cleaner than real sources; the backend's shadow-mode labelling (plan section 5) must
confirm these numbers on real data before any organisation switches to VISIBLE.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict

from eval.cases import all_cases
from eval.metrics import cohen_kappa, false_corroboration_rate, false_discovery_rate, per_verdict

THRESHOLDS = {"false_corroboration_rate": ("<=", 0.02), "kappa": (">=", 0.70)}
MIN_CASES = 60


def evaluate(verbose: bool = False) -> dict:
    cases = all_cases()
    pairs: list[tuple[str, str]] = []
    by_verifier: dict[str, list[tuple[str, str]]] = defaultdict(list)
    adversarial: list[tuple[str, str]] = []
    mismatches = []
    errors = []
    for case in cases:
        try:
            response = case.run()
            predicted = {verdict.claim_id: verdict.verdict for verdict in response.verdicts}
        except Exception as exc:  # noqa: BLE001 - a crashing case counts as a wrong NOT_FOUND
            errors.append(f"{case.id}: {type(exc).__name__}: {exc}")
            predicted = {}
        for claim_id, gold in case.gold.items():
            pair = (gold, predicted.get(claim_id, "NOT_FOUND"))
            pairs.append(pair)
            by_verifier[case.verifier].append(pair)
            if case.adversarial:
                adversarial.append(pair)
            if pair[0] != pair[1]:
                mismatches.append(f"{case.id}/{claim_id}: gold {pair[0]} predicted {pair[1]}")
    metrics = {
        "cases": len(cases),
        "adversarial_cases": sum(1 for case in cases if case.adversarial),
        "claims": len(pairs),
        "false_corroboration_rate": round(false_corroboration_rate(pairs), 4),
        "false_discovery_rate": round(false_discovery_rate(pairs), 4),
        "kappa": round(cohen_kappa(pairs), 4),
        "agreement": round(sum(1 for gold, predicted in pairs if gold == predicted) / len(pairs), 4) if pairs else 1.0,
        "adversarial_false_corroboration_rate": round(false_corroboration_rate(adversarial), 4),
        "per_verdict": {label: {key: round(value, 4) if isinstance(value, float) else value
                                for key, value in values.items()} for label, values in per_verdict(pairs).items()},
        "by_verifier": {name: {"claims": len(items), "agreement": round(
            sum(1 for gold, predicted in items if gold == predicted) / len(items), 4)}
            for name, items in sorted(by_verifier.items())},
        "mismatches": mismatches,
        "errors": errors,
    }
    if verbose:
        for line in mismatches + errors:
            print(line, file=sys.stderr)
    return metrics


def failures(metrics: dict) -> list[str]:
    problems = []
    for name, (op, threshold) in THRESHOLDS.items():
        value = metrics[name]
        if (op == "<=" and value > threshold) or (op == ">=" and value < threshold):
            problems.append(f"{name}={value} (threshold {op} {threshold})")
    if metrics["cases"] < MIN_CASES:
        problems.append(f"only {metrics['cases']} cases (at least {MIN_CASES} required)")
    # A crashing case is scored as NOT_FOUND, which hides the crash whenever the gold verdict is NOT_FOUND too.
    problems += [f"case crashed: {error}" for error in metrics.get("errors", [])]
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="agent-service evaluation (synthetic gold cases)")
    parser.add_argument("--check", action="store_true", help="exit 1 when a threshold is missed")
    parser.add_argument("--json", help="write the metrics to this file")
    parser.add_argument("--verbose", action="store_true", help="print mismatching cases")
    args = parser.parse_args(argv)
    metrics = evaluate(verbose=args.verbose)
    summary = {key: value for key, value in metrics.items() if key not in {"mismatches", "errors"}}
    print(json.dumps(summary, indent=2))
    print(f"mismatches: {len(metrics['mismatches'])}, errors: {len(metrics['errors'])}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(metrics, handle, indent=2)
    problems = failures(metrics)
    for problem in problems:
        print(f"THRESHOLD MISSED: {problem}", file=sys.stderr)
    return 1 if args.check and problems else 0


if __name__ == "__main__":
    sys.exit(main())
