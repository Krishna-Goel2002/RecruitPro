"""report.json (everything), report.md (readable tables) and candidates.csv (one row per application)."""
from __future__ import annotations

import csv
import json
from pathlib import Path
from typing import Any, Iterable

CSV_COLUMNS = [
    "resumeId", "jobKey", "jobId", "applicationId", "email", "format", "template", "quality", "profession",
    "seniority", "adversarial", "traits", "twinGroup", "gender", "label", "gain", "expectedGroup", "actualGroup", "groupMatch", "position", "rank",
    "verifiedCoverage", "claimedCoverage", "knockoutsVerified", "matchStatus", "analysisStatus", "flags",
    "analysisSeconds", "matchSeconds", "totalSeconds",
]


def _fmt(value: Any, digits: int = 3) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _pct(value: float | None) -> str:
    return "n/a" if value is None else f"{value * 100:.1f}%"


def _table(headers: list[str], rows: Iterable[Iterable[Any]]) -> list[str]:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join(_fmt(c).replace("|", "\\|") for c in row) + " |")
    return out


def render_markdown(report: dict[str, Any]) -> str:
    o = report.get("overall") or {}
    md: list[str] = [f"# Simulation report `{report['runId']}`", ""]
    md += [f"- Stack: `{report['baseUrl']}`  corpus: `{report['corpus']}`",
           f"- Started {report['startedAt']}, took {report['durationSeconds']} s; "
           f"{report['succeeded']}/{report['processed']} resumes completed every step "
           f"({report['planned']} planned, {len(report.get('skipped') or [])} skipped for their format)."]
    if report.get("stopped"):
        md += [f"- **Stopped early:** {report['stopped']}"]
    md += [f"- API: {report['api']['requests']} requests, 429 retries {report['api']['retries_429']} "
           f"(waited {report['api']['retry_after_seconds']} s), 5xx retries {report['api']['retries_5xx']}, "
           f"transport retries {report['api']['retries_transport']}, re-sign-ins {report['api']['reauth']}, "
           f"client pacing {report['api']['paced_seconds']} s.", ""]

    md += ["## Jobs", ""]
    md += _table(["key", "job id", "title", "setup", "status", "requirements", "applicants (all)"],
                 ([k, j["id"], j["title"], "created" if j["created"] else "reused", j["status"],
                   j.get("requirements"), j.get("applicantsTotal")] for k, j in report["jobs"].items()))
    md += [""]
    if not o:
        return "\n".join(md) + "\n"

    md += ["## Ranking quality", "",
           "Gold gains strong=4, good=3, partial=2, weak=1, unrelated=0 (exponential DCG). Only this run's "
           "applications are scored, in HR ranking order (`sort=rank`: RANKED, then MUST_HAVES_UNVERIFIED, then "
           "UNSCORED). P@k = share of strong/good in the top k.", ""]
    md += _table(["job", "n", "NDCG@10", "NDCG@all", "P@5", "P@10", "pairwise", "group agreement", "groups"],
                 [[k, j["n"], j["ndcg@10"], j["ndcg@all"], j["precision@5"], j["precision@10"],
                   j["pairwiseAccuracy"], _pct(j["groupAgreement"]["accuracy"]),
                   ", ".join(f"{g}={n}" for g, n in sorted(j["groups"].items()))]
                  for k, j in report["jobMetrics"].items()]
                 + [["**mean**", o["applications"], o["ndcg@10"], o["ndcg@all"], o["precision@5"],
                     o["precision@10"], o["pairwiseAccuracy"], _pct(o["groupAgreement"]["accuracy"]), ""]])
    md += [""]

    ga = o["groupAgreement"]
    md += ["## Ranking-group agreement", "",
           f"{ga['agree']}/{ga['n']} applications landed in the expected group ({_pct(ga['accuracy'])}). "
           "Rows: expected (corpus), columns: actual (HR list).", ""]
    actual_cols = sorted({a for c in ga["confusion"].values() for a in c})
    md += _table(["expected \\ actual"] + actual_cols,
                 ([e] + [c.get(a, 0) for a in actual_cols] for e, c in ga["confusion"].items()))
    md += [""]

    for key, j in report["jobMetrics"].items():
        md += [f"### Top 10: {key}", ""]
        md += _table(["#", "resume", "gold label", "group", "verified", "claimed"],
                     ([t["position"], t["resumeId"], t["label"], t["group"], t["verifiedCoverage"],
                       t["claimedCoverage"]] for t in j["top10"]))
        md += [""]

    md += ["## Parser outcomes", ""]
    for dim, buckets in report["parser"].items():
        statuses = sorted({s for b in buckets.values() for s in b["statuses"]})
        md += [f"### By {dim}", ""]
        md += _table([dim, "n", "usable"] + statuses + ["analysis p50 s", "p90 s"],
                     ([k, b["n"], _pct(b["usableRate"])] + [b["statuses"].get(s, 0) for s in statuses]
                      + [b["analysisSeconds"]["p50"], b["analysisSeconds"]["p90"]] for k, b in buckets.items()))
        md += [""]

    adv = report["adversarial"]
    md += ["## Adversarial resumes", ""]
    if adv["n"] == 0:
        md += ["No adversarial (stuffed / injection / hidden-text) resumes in this corpus.", ""]
    else:
        md += [f"{adv['n']} adversarial applications (manipulation tags: hidden_keywords, prompt_injection, "
               f"inflated_claims). **Rose** above a better-fitting clean candidate: "
               f"{_pct(adv['roseRate'])}; low-fit in the top third (max 10): {_pct(adv['topKLowFitRate'])}; flagged to HR "
               f"(PROMPT_INJECTION_SUSPECTED / HIDDEN_TEXT): {_pct(adv['flagRate'])} of the {adv.get('flagExpected', 0)} "
               f"that should be (hidden keywords, prompt injection; inflated claims are visible text and are not "
               f"expected to be flagged); above its clean twin: {_pct(adv['aboveCleanTwinRate'])}. "
               "Target: 0% rose, 100% flagged.", ""]
        if adv.get("byKind"):
            md += _table(["kind", "n", "rose", "low-fit in top third", "flagged"],
                         ([k, v["n"], _pct(v["roseRate"]), _pct(v["topKLowFitRate"]), _pct(v["flagRate"])]
                          for k, v in adv["byKind"].items()))
            md += [""]
        md += _table(["resume", "job", "kind", "gold", "position", "group", "overtakes", "flagged", "flags"],
                     ([i["resumeId"], i["jobKey"], i["kind"], i["label"], i["position"], i["group"],
                       i["overtakes"], i["flagged"], ", ".join(i["flags"])] for i in adv["items"]))
        md += [""]
        md += ["Robustness traits (near_empty, non_english_section, duplicate_sections, very_long, "
               "photo_placeholder) are not manipulation: see *Parser outcomes by traits*.", ""]

    fair = report["fairness"]
    md += ["## Counterfactual fairness (twins)", ""]
    if fair["pairs"] == 0:
        md += ["No twin personas in this corpus (no `twinGroup`; ResumeLab does not generate twins yet).", ""]
    else:
        md += [f"{fair['pairs']} twin pairs; **flip rate** {_pct(fair['flipRate'])} (group or coverage differs), "
               f"group flips {_pct(fair['groupFlipRate'])}, mean position gap {_fmt(fair['meanPositionGap'])}. "
               "Target: 0%.", ""]
        flips = [p for p in fair["items"] if p["flip"]]
        if flips:
            md += _table(["twin group", "job", "a", "b", "group a", "group b", "coverage a", "coverage b",
                          "persona differs"],
                         ([p["twinGroup"], p["jobKey"], p["a"], p["b"], p["groupA"], p["groupB"],
                           "/".join(_fmt(x) for x in p["coverageA"]), "/".join(_fmt(x) for x in p["coverageB"]),
                           ", ".join(p["personaDiffers"])] for p in flips))
            md += [""]

    md += ["## Processing time", "",
           "analysis = upload start to terminal analysis status; match = apply to the first poll that saw the "
           "match settled (resolution = poll interval); total = registration to last match settled.", ""]
    md += _table(["phase", "n", "mean s", "p50 s", "p90 s", "max s"],
                 ([name, s["n"], s["mean"], s["p50"], s["p90"], s["max"]] for name, s in report["timings"].items()))
    md += [""]

    skipped = report.get("skipped") or []
    md += ["## Skipped resumes", ""]
    if not skipped:
        md += ["None: every selected resume has a type the backend accepts.", ""]
    else:
        md += [f"{len(skipped)} resume(s) were not uploaded because the backend only accepts "
               f"{', '.join('.' + t for t in report.get('config', {}).get('allowedTypes') or [])} "
               "(`app.file-storage.allowed-types`). Run with `--include-unsupported` to upload them anyway and "
               "test the rejection path.", ""]
        md += _table(["resume", "format", "file", "jobs"],
                     ([x["resumeId"], x["format"], x["file"], ", ".join(x["jobs"])] for x in skipped))
        md += [""]

    failures = report["failures"]
    md += ["## Failures and warnings", ""]
    if not failures:
        md += ["None.", ""]
    else:
        md += _table(["resume", "stage", "job", "error"],
                     ([f["resumeId"], f["stage"], f.get("jobKey", ""), f["error"]] for f in failures))
        md += [""]
    if report.get("corpusWarnings"):
        md += ["Corpus warnings:", ""] + [f"- {w}" for w in report["corpusWarnings"]] + [""]
    return "\n".join(md) + "\n"


def write_report(report: dict[str, Any], rows: list[dict[str, Any]], out_dir: str | Path) -> dict[str, Path]:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    data = dict(report)
    paths = {"json": out / "report.json", "markdown": out / "report.md", "csv": out / "candidates.csv"}
    paths["json"].write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
    paths["markdown"].write_text(render_markdown(data), encoding="utf-8")
    with paths["csv"].open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=CSV_COLUMNS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            flat = dict(row)
            flat["gender"] = (row.get("persona") or {}).get("gender")
            flat["flags"] = ";".join(row.get("flags") or [])
            flat["groupMatch"] = None if not row.get("expectedGroup") else row.get("expectedGroup") == row.get(
                "actualGroup")
            writer.writerow(flat)
    return paths
