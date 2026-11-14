from eval.run import evaluate, failures


def test_evaluation_thresholds_hold_on_a_seeded_subset():
    metrics = evaluate(seed=7, per_domain=3, workers=1)
    assert metrics["resumes"] == 48 and metrics["counterfactual_checks"] == 72
    assert failures(metrics) == []
