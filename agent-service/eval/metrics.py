"""Metrics: false-corroboration rate, Cohen's kappa and per-verdict precision/recall."""
from __future__ import annotations

from collections import Counter

VERDICTS = ("VERIFIED", "PARTLY_VERIFIED", "NOT_FOUND", "CONTRADICTED", "NOT_CHECKABLE")


def false_corroboration_rate(pairs: list[tuple[str, str]]) -> float:
    """Share of claims whose gold verdict is not VERIFIED that were predicted VERIFIED."""
    negatives = [predicted for gold, predicted in pairs if gold != "VERIFIED"]
    return sum(1 for predicted in negatives if predicted == "VERIFIED") / len(negatives) if negatives else 0.0


def false_discovery_rate(pairs: list[tuple[str, str]]) -> float:
    """Share of VERIFIED predictions whose gold verdict is not VERIFIED (reported, not gated)."""
    verified = [gold for gold, predicted in pairs if predicted == "VERIFIED"]
    return sum(1 for gold in verified if gold != "VERIFIED") / len(verified) if verified else 0.0


def cohen_kappa(pairs: list[tuple[str, str]]) -> float:
    if not pairs:
        return 1.0
    total = len(pairs)
    observed = sum(1 for gold, predicted in pairs if gold == predicted) / total
    gold_counts = Counter(gold for gold, _ in pairs)
    predicted_counts = Counter(predicted for _, predicted in pairs)
    expected = sum(gold_counts[label] * predicted_counts[label] for label in gold_counts) / (total * total)
    return 1.0 if expected == 1 else (observed - expected) / (1 - expected)


def per_verdict(pairs: list[tuple[str, str]]) -> dict[str, dict[str, float | int]]:
    result: dict[str, dict[str, float | int]] = {}
    for label in VERDICTS:
        tp = sum(1 for gold, predicted in pairs if gold == label and predicted == label)
        fp = sum(1 for gold, predicted in pairs if gold != label and predicted == label)
        fn = sum(1 for gold, predicted in pairs if gold == label and predicted != label)
        result[label] = {"support": tp + fn, "precision": tp / (tp + fp) if tp + fp else 1.0,
                         "recall": tp / (tp + fn) if tp + fn else 1.0}
    return result
