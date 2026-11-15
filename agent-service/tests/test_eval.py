"""The evaluation gate runs inside the test suite too."""
from __future__ import annotations

from eval.metrics import cohen_kappa, false_corroboration_rate, per_verdict
from eval.run import evaluate, failures


def test_evaluation_meets_thresholds():
    metrics = evaluate()
    assert metrics["cases"] >= 60 and metrics["adversarial_cases"] >= 10
    assert failures(metrics) == [], metrics["mismatches"] + metrics["errors"]


def test_metrics():
    pairs = [("VERIFIED", "VERIFIED"), ("NOT_FOUND", "VERIFIED"), ("NOT_FOUND", "NOT_FOUND"),
             ("CONTRADICTED", "CONTRADICTED")]
    assert false_corroboration_rate(pairs) == 1 / 3
    assert round(cohen_kappa(pairs), 3) == 0.636
    assert per_verdict(pairs)["VERIFIED"]["precision"] == 0.5
