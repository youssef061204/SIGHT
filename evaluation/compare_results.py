"""Compare compatible real accuracy-run artifacts with explicit regression tolerances."""
from __future__ import annotations
import argparse
import json
from pathlib import Path

SCORING_FIELDS = ("score_floor", "operating_score_threshold", "max_results", "iou_threshold")


def compare(baseline, candidate, ap_tolerance=.02, recall_tolerance=.03):
    if baseline["dataset_manifest_sha256"] != candidate["dataset_manifest_sha256"]:
        raise ValueError("Dataset manifests differ; metric deltas are not a controlled regression comparison")
    for field in SCORING_FIELDS:
        if baseline["config"][field] != candidate["config"][field]:
            raise ValueError(f"Scoring configuration differs: {field}")
    rows = []
    for model_id, base_model in baseline["models"].items():
        if model_id not in candidate["models"]:
            raise ValueError(f"Candidate missing baseline model: {model_id}")
        for variant, base_metrics in base_model["variants"].items():
            if variant not in candidate["models"][model_id]["variants"]:
                raise ValueError(f"Candidate missing baseline condition: {variant}")
            actual = candidate["models"][model_id]["variants"][variant]
            for metric, tolerance in (("subset_AP50", ap_tolerance), ("recall_at_operating_threshold", recall_tolerance)):
                old, new = base_metrics[metric], actual[metric]
                if old is None or new is None:
                    raise ValueError(f"Undefined {metric}; refusing silent comparison")
                delta = new - old
                rows.append({"model_id": model_id, "variant": variant, "metric": metric,
                             "baseline": old, "candidate": new, "delta": delta,
                             "allowed_absolute_drop": tolerance, "passed": delta >= -tolerance})
    return {"passed": all(row["passed"] for row in rows), "comparisons": rows,
            "scope": "Accuracy regression gate on identical data/scoring only. Tolerances are engineering settings, not safety certification. Timing excluded because host load/affinity/order must be matched independently."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("evaluation/results/regression.json"))
    parser.add_argument("--ap-tolerance", type=float, default=.02)
    parser.add_argument("--recall-tolerance", type=float, default=.03)
    args = parser.parse_args()
    if min(args.ap_tolerance, args.recall_tolerance) < 0:
        parser.error("Tolerances must be nonnegative")
    result = compare(json.loads(args.baseline.read_text()), json.loads(args.candidate.read_text()), args.ap_tolerance, args.recall_tolerance)
    result.update(baseline=str(args.baseline), candidate=str(args.candidate))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
