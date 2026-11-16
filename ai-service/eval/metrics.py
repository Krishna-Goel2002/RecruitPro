"""Metric functions for the evaluation harness."""
from __future__ import annotations

import math
from collections import Counter


def precision_recall_f1(true_positive: int, false_positive: int, false_negative: int) -> tuple[float, float, float]:
    precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 1.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def cohen_kappa(pairs: list[tuple[str, str]]) -> float:
    """Cohen's kappa between gold (first) and predicted (second) labels."""
    if not pairs:
        return 1.0
    total = len(pairs)
    observed = sum(1 for gold, predicted in pairs if gold == predicted) / total
    gold_counts = Counter(gold for gold, _ in pairs)
    predicted_counts = Counter(predicted for _, predicted in pairs)
    expected = sum(gold_counts[label] * predicted_counts[label] for label in gold_counts) / (total * total)
    return 1.0 if expected == 1 else (observed - expected) / (1 - expected)


def ndcg_at_k(ranked_gains: list[float], k: int = 10) -> float:
    """NDCG@k of a ranking given each ranked item's gold gain."""
    def dcg(gains: list[float]) -> float:
        return sum((2 ** gain - 1) / math.log2(position + 2) for position, gain in enumerate(gains[:k]))

    ideal = dcg(sorted(ranked_gains, reverse=True))
    return dcg(ranked_gains) / ideal if ideal else 1.0


def adverse_impact_ratio(selection_rates: dict[str, float | None]) -> float:
    """Lowest group selection rate divided by the highest (the four-fifths rule compares it with 0.8)."""
    rates = [rate for rate in selection_rates.values() if rate is not None]
    if not rates or max(rates) == 0:
        return 1.0
    return min(rates) / max(rates)
