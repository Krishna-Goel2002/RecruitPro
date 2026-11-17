import math

import pytest

from simulate import metrics as m


def test_dcg_matches_hand_computation():
    # (2^3-1)/log2(2) + (2^2-1)/log2(3) + (2^0-1)/log2(4)
    expected = 7 / 1 + 3 / math.log2(3) + 0
    assert m.dcg([3, 2, 0]) == pytest.approx(expected)
    assert m.dcg([3, 2, 0], k=1) == pytest.approx(7)


def test_ndcg_perfect_reversed_and_degenerate():
    assert m.ndcg([4, 3, 2, 1, 0]) == pytest.approx(1.0)
    worst = m.ndcg([0, 1, 2, 3, 4])
    assert 0 < worst < 0.6
    assert m.ndcg([0, 0, 0]) is None
    assert m.ndcg([]) is None
    # @k only looks at the top k, against the ideal top k
    assert m.ndcg([4, 0, 3], k=1) == pytest.approx(1.0)
    assert m.ndcg([0, 4, 3], k=1) == pytest.approx(0.0)


def test_ndcg_ideal_pool_penalises_missing_candidates():
    shown = [3, 1]
    assert m.ndcg(shown) == pytest.approx(1.0)
    assert m.ndcg(shown, ideal_pool=[4, 3, 1]) < 1.0


def test_precision_at_k():
    labels = ["strong", "weak", "good", "unrelated", "partial", "good"]
    assert m.precision_at_k(labels, 5) == pytest.approx(2 / 5)
    assert m.precision_at_k(labels, 1) == 1.0
    assert m.precision_at_k(labels[:2], 10) == pytest.approx(1 / 2)  # fewer than k: divide by shown
    assert m.precision_at_k([], 5) is None


def test_pairwise_accuracy():
    assert m.pairwise_accuracy([4, 3, 2]) == 1.0
    assert m.pairwise_accuracy([2, 3, 4]) == 0.0
    assert m.pairwise_accuracy([4, 2, 3]) == pytest.approx(2 / 3)
    assert m.pairwise_accuracy([1, 1]) is None


def test_group_agreement_and_confusion():
    res = m.group_agreement([("RANKED", "RANKED"), ("RANKED", "MUST_HAVES_UNVERIFIED"),
                             ("UNSCORED", None), (None, "RANKED")])
    assert res["n"] == 3 and res["agree"] == 1
    assert res["accuracy"] == pytest.approx(1 / 3)
    assert res["confusion"]["RANKED"] == {"RANKED": 1, "MUST_HAVES_UNVERIFIED": 1}
    assert res["confusion"]["UNSCORED"] == {"MISSING": 1}


def test_summarize_percentiles():
    s = m.summarize([5, 1, 3, None, 2, 4])
    assert s["n"] == 5 and s["p50"] == 3 and s["max"] == 5 and s["mean"] == pytest.approx(3)
    assert m.summarize([])["mean"] is None


def row(rid, job, label, pos, group="RANKED", v=0.5, c=0.5, **extra):
    return {"resumeId": rid, "jobKey": job, "label": label, "gain": m.gain_of(label), "position": pos,
            "actualGroup": group, "expectedGroup": extra.pop("expected", None), "verifiedCoverage": v,
            "claimedCoverage": c, **extra}


def test_job_metrics_orders_missing_last():
    rows = [row("a", "j", "weak", None), row("b", "j", "strong", 1), row("c", "j", "good", 2)]
    jm = m.job_metrics(rows)
    assert jm["missingFromRanking"] == 1
    assert [t["resumeId"] for t in jm["top10"]] == ["b", "c", "a"]
    assert jm["ndcg@all"] == pytest.approx(1.0)
    assert jm["precision@5"] == pytest.approx(2 / 3)


def test_adversarial_rose_flagged_and_twin():
    rows = [
        row("inj", "j", "weak", 1, adversarial="injection", flags=["PROMPT_INJECTION_SUSPECTED"], twinGroup="t"),
        row("clean-strong", "j", "strong", 2),
        row("clean-twin", "j", "weak", 3, twinGroup="t"),
        row("stuffed", "j", "unrelated", 4, adversarial="stuffed", flags=[]),
    ]
    adv = m.adversarial_report(rows)
    assert adv["n"] == 2
    by = {i["resumeId"]: i for i in adv["items"]}
    assert by["inj"]["rose"] and by["inj"]["overtakes"] == 1 and by["inj"]["flagged"]
    assert by["inj"]["aboveCleanTwin"] is True and by["inj"]["inTopKLowFit"]
    assert not by["stuffed"]["rose"] and not by["stuffed"]["flagged"]
    assert adv["roseRate"] == 0.5 and adv["flagRate"] == 0.5


def test_fairness_flip_rate_and_twin_of_base_id():
    rows = [
        row("base", "j", "good", 1, v=0.8, c=0.9, persona={"firstName": "Grace", "gender": "female"}),
        row("twin1", "j", "good", 2, v=0.8, c=0.9, twinGroup="base",
            persona={"firstName": "Greg", "gender": "male"}),
        row("twin2", "j", "good", 3, group="MUST_HAVES_UNVERIFIED", v=0.8, c=0.9, twinGroup="base",
            persona={"firstName": "Gita", "gender": "female"}),
        row("adv", "j", "good", 4, twinGroup="base", adversarial="stuffed"),  # excluded
    ]
    fair = m.fairness_report(rows)
    assert fair["pairs"] == 3
    flips = [p for p in fair["items"] if p["flip"]]
    assert len(flips) == 2 and all(p["groupFlip"] for p in flips)
    assert fair["flipRate"] == pytest.approx(2 / 3)
    base_twin1 = next(p for p in fair["items"] if {p["a"], p["b"]} == {"base", "twin1"})
    assert base_twin1["personaDiffers"] == ["firstName", "gender"] and not base_twin1["flip"]


def test_fairness_coverage_flip_without_group_change():
    rows = [row("a", "j", "good", 1, v=0.8, twinGroup="g"), row("b", "j", "good", 2, v=0.7, twinGroup="g")]
    fair = m.fairness_report(rows)
    assert fair["flipRate"] == 1.0 and fair["groupFlipRate"] == 0.0


def test_parser_outcomes_by_dimension():
    rr = [{"format": "pdf", "analysisStatus": "COMPLETED", "analysisSeconds": 2},
          {"format": "pdf", "analysisStatus": "NEEDS_OCR", "analysisSeconds": 4},
          {"format": "txt", "analysisStatus": "UPLOAD_REJECTED", "analysisSeconds": None}]
    out = m.parser_outcomes(rr, "format")
    assert out["pdf"]["n"] == 2 and out["pdf"]["usableRate"] == 0.5
    assert out["pdf"]["statuses"] == {"COMPLETED": 1, "NEEDS_OCR": 1}
    assert out["txt"]["analysisSeconds"]["n"] == 0
    assert "n/a" in m.parser_outcomes(rr, "template")


def test_evaluate_macro_averages():
    rows = [row("a", "j1", "strong", 1, expected="RANKED"), row("b", "j1", "weak", 2, expected="MUST_HAVES_UNVERIFIED"),
            row("c", "j2", "weak", 1), row("d", "j2", "strong", 2)]
    ev = m.evaluate(rows, [{"resumeId": x, "format": "pdf", "analysisStatus": "COMPLETED"} for x in "abcd"])
    assert ev["jobMetrics"]["j1"]["ndcg@all"] == pytest.approx(1.0)
    assert ev["overall"]["ndcg@all"] == pytest.approx((1.0 + ev["jobMetrics"]["j2"]["ndcg@all"]) / 2)
    assert ev["overall"]["groupAgreement"]["accuracy"] == 0.5
    assert ev["overall"]["groups"]["RANKED"] == 4


def test_adversarial_ignores_unscored_clean_candidates():
    rows = [row("adv", "j", "weak", 1, group="MUST_HAVES_UNVERIFIED", adversarial="stuffed"),
            row("scan", "j", "strong", 2, group="UNSCORED", v=None, c=None),
            row("ok", "j", "unrelated", 3, group="MUST_HAVES_UNVERIFIED")]
    adv = m.adversarial_report(rows)
    assert adv["items"][0]["overtakes"] == 0 and adv["roseRate"] == 0
    assert adv["items"][0]["inTopKLowFit"]  # top third of 3 = position 1


def test_flag_rate_counts_only_kinds_the_parser_can_see():
    rows = [
        row("hid", "j", "weak", 3, adversarial="hidden_keywords", flags=["HIDDEN_TEXT"]),
        row("infl", "j", "good", 1, adversarial="inflated_claims", flags=[]),
        row("both", "j", "partial", 2, adversarial="inflated_claims,prompt_injection", flags=[]),
        row("clean", "j", "strong", 4),
    ]
    adv = m.adversarial_report(rows)
    assert adv["n"] == 3 and adv["flagExpected"] == 2
    assert adv["flagRate"] == 0.5  # hid flagged, both not; infl is not expected to be flagged
    assert adv["byKind"]["inflated_claims"]["n"] == 2 and adv["byKind"]["prompt_injection"]["flagRate"] == 0.0
    assert adv["roseRate"] == 1.0  # all three sit above the clean strong candidate
