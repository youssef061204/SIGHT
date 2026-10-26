"""Render measured artifacts; estimate paired creator-cluster operating uncertainty."""
from __future__ import annotations

from collections import Counter
import contextlib
import csv
import io
import json
from pathlib import Path

import numpy as np
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

from v3_detection import cap_predictions, digest

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/v3-detection"


def operating_image_counts(coco, predictions, threshold, limit):
    with contextlib.redirect_stdout(io.StringIO()):
        detections = coco.loadRes(cap_predictions(predictions, threshold, limit))
        evaluator = COCOeval(coco, detections, "bbox")
        evaluator.evaluate()
    counts = {image_id: {"tp": 0, "fp": 0, "gt": 0} for image_id in coco.imgs}
    for item in evaluator.evalImgs:
        if item is None or item["aRng"] != evaluator.params.areaRng[0]:
            continue
        row = counts[int(item["image_id"])]
        row["tp"] += int(np.sum((item["dtMatches"][0] > 0) & ~item["dtIgnore"][0]))
        row["fp"] += int(np.sum((item["dtMatches"][0] == 0) & ~item["dtIgnore"][0]))
        row["gt"] += int(np.sum(~np.asarray(item["gtIgnore"], dtype=bool)))
    return counts


def bootstrap(manifest, comparison, calibration):
    with contextlib.redirect_stdout(io.StringIO()):
        coco = COCO(str(ROOT / "evaluation/data-v3/instances_held_out.json"))
    baseline_path = OUT / "held_out/efficientdet_lite0_int8/clean_predictions.json"
    candidate_path = OUT / ("held_out/" + calibration["selected_model"] + "/clean_predictions.json")
    baseline = operating_image_counts(coco, json.loads(baseline_path.read_text()), .5, 20)
    candidate = operating_image_counts(coco, json.loads(candidate_path.read_text()), calibration["operating_score_threshold"], calibration["deployment_limit"])
    groups = {}
    for image in manifest["images"]:
        if image["split"] != "held_out":
            continue
        row = groups.setdefault(image["scene_group"], [0, 0, 0, 0, 0])
        old, new = baseline[image["id"]], candidate[image["id"]]
        for index, value in enumerate((old["tp"], old["fp"], new["tp"], new["fp"], old["gt"])):
            row[index] += value
    matrix = np.asarray(list(groups.values()), dtype=np.float64)
    rng = np.random.default_rng(20261025)
    sums = matrix[rng.integers(0, len(matrix), (5000, len(matrix)))].sum(axis=1)
    recall_delta = (sums[:, 2] - sums[:, 0]) / sums[:, 4]
    precision_delta = sums[:, 2] / (sums[:, 2] + sums[:, 3]) - sums[:, 0] / (sums[:, 0] + sums[:, 1])
    return {"seed": 20261025, "resamples": 5000, "independent_units": len(groups), "unit": "creator/near-duplicate scene group",
            "recall_delta_percentile95": np.percentile(recall_delta, (2.5, 97.5)).tolist(),
            "precision_delta_percentile95": np.percentile(precision_delta, (2.5, 97.5)).tolist(),
            "scope": "exploratory paired cluster-bootstrap operating metrics only; not AP confidence intervals, no proof of scene independence or population/assistive-use generalization",
            "per_group_counts": {key: dict(zip(("baseline_tp", "baseline_fp", "candidate_tp", "candidate_fp", "gt"), values)) for key, values in groups.items()}}


def main():
    manifest = json.loads((ROOT / "evaluation/data-v3/manifest.json").read_text())
    comparison = json.loads((OUT / "held-out-comparison.json").read_text())
    selection = json.loads((OUT / "selection-decision.json").read_text())
    calibration = json.loads((OUT / "calibration-decision.json").read_text())
    latency = json.loads((ROOT / "evaluation/results/v3-paired-latency/summary.json").read_text())
    uncertainty = bootstrap(manifest, comparison, calibration)
    (OUT / "operating-cluster-bootstrap.json").write_text(json.dumps(uncertainty, indent=2))
    clean = comparison["variants"]["clean"]
    with (OUT / "per-class-held-out.csv").open("w", encoding="utf-8", newline="") as stream:
        fields = ["category", "eligible_ground_truth", "baseline_tp", "baseline_fp", "baseline_precision", "baseline_recall", "inspect_tp", "inspect_fp", "inspect_precision", "inspect_recall", "baseline_AP50", "inspect_AP50"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        classes = sorted(set(clean["baseline"]["per_class_operating_counts"]) | set(clean["candidate"]["per_class_operating_counts"]))
        for category in classes:
            old = clean["baseline"]["per_class_operating_counts"].get(category, {"tp": 0, "fp": 0, "fn": 0, "precision": None, "recall": None})
            new = clean["candidate"]["per_class_operating_counts"].get(category, {"tp": 0, "fp": 0, "fn": 0, "precision": None, "recall": None})
            writer.writerow({"category": category, "eligible_ground_truth": old["tp"] + old["fn"],
                             "baseline_tp": old["tp"], "baseline_fp": old["fp"], "baseline_precision": old["precision"], "baseline_recall": old["recall"],
                             "inspect_tp": new["tp"], "inspect_fp": new["fp"], "inspect_precision": new["precision"], "inspect_recall": new["recall"],
                             "baseline_AP50": clean["baseline"]["per_class_AP50"].get(category), "inspect_AP50": clean["candidate"]["per_class_AP50"].get(category)})
    lines = ["# SIGHT 3 detector comparison", "",
             "Measured desktop inference, not phone performance or an assistive-use validation. Baseline reproduction, model selection, calibration and final testing are separate artifacts. The original 16 photos reproduced byte-for-byte across all 6 prediction files and 30 reported metric checks; their AP50=.3614 and recall=.1940 were confirmed before improvements.", "",
             "## Corpus and decisions", "",
             "180 current, attributed CC BY COCO val2017 photographs (29.7 MB), 159 photographers; 50 model-selection, 50 calibration, 80 held-out. Entire creator groups and dHash <= 6 near-duplicate components stay together. Original 16 and their recovered creators are excluded. Splitting used image/author metadata only, before inference. See [manifest](../../data-v3/manifest.json) and [180 credits](../../data-v3/ATTRIBUTION.md). COCO annotations are CC BY 4.0. This conservative grouping reduces leakage, but cannot prove every scene is independent or that public validation images never influenced upstream model development.", "",
             "Selection at confidence .50 chose Lite2: clean AP50 .6050 versus Lite0 .5150 and SSD .4757, while both precision and recall exceeded baseline. The separate calibration set chose confidence .55, using the predeclared precision>=baseline and bounded-FP/image rule. Calibration recall decreased from .2542 to .2401 while precision rose .9000 to .9444; this unfavorable tradeoff is retained. The selection and calibration decisions were sealed before held-out inference. Neither decision changed after seeing final results.", "",
             "## Held-out results", "",
             "| Condition | Policy | AP50 | AP50:95 | Precision | Recall | FP/image | TP / FP / FN |", "| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |"]
    for variant, row in comparison["variants"].items():
        for policy, label in (("baseline", "FAST Lite0 .50 / cap20"), ("candidate_same_operating_point_as_baseline", "Lite2 .50 / cap20"), ("candidate", "INSPECT Lite2 .55 / cap40")):
            value = row[policy]
            lines.append(f"| {variant} | {label} | {value['subset_AP50']:.4f} | {value['subset_AP50_95']:.4f} | {value['precision_at_operating_threshold']:.4f} | {value['recall_at_operating_threshold']:.4f} | {value['false_positives_per_image']:.4f} | {value['tp']} / {value['fp']} / {value['fn']} |")
    rec_ci = uncertainty["recall_delta_percentile95"]
    lines += ["", "AP uses the same ranked score floor .05 / max100 detections and official pycocotools COCOeval IoU .50:.05:.95 with crowd/area ignores. Operating precision/recall use IoU .50 and the listed deployment thresholds/caps. AP is unaffected by which deployment threshold row is displayed. All rows use identical annotated photos and scoring. This is subset AP, not full COCO mAP; per-class operating counts and AP50 are in [raw comparison](held-out-comparison.json). Synthetic dim_035 and horizontal 15 px blur transform the same 80 photos; they add no independent scenes and are not real low-light/moving-camera datasets.", "",
              "The chosen clean INSPECT operating point increases observed recall by 3.48 percentage points (21 additional true positives), raises precision 2.45 points and reduces FP/image by .05. Equal .50 threshold comparison isolates the model change: recall +8.44 points with four additional false positives across 80 photos. The stricter calibration threshold deliberately trades some of that recall for fewer false positives. Dim-image chosen-policy precision falls slightly and FP/image rises; no blanket robustness claim is made.", "",
              f"An exploratory 5000-resample paired creator/near-duplicate cluster bootstrap ({uncertainty['independent_units']} groups) gives a 95% percentile interval of [{rec_ci[0]:.4f},{rec_ci[1]:.4f}] for the chosen clean recall difference. It is not a guarantee of population improvement; details and raw group counts are in [bootstrap](operating-cluster-bootstrap.json).", "",
              "## Small objects and class coverage", "",
              "| Clean object area | Baseline TP / eligible GT | Recall | INSPECT TP / eligible GT | Recall |", "| --- | --- | ---: | --- | ---: |"]
    for label in ("small", "medium", "large"):
        old = comparison["variants"]["clean"]["baseline"]["area_operating_counts"][label]
        new = comparison["variants"]["clean"]["candidate"]["area_operating_counts"][label]
        lines.append(f"| {label} | {old['tp']} / {old['tp']+old['fn']} | {old['recall']:.4f} | {new['tp']} / {new['tp']+new['fn']} | {new['recall']:.4f} |")
    lines += ["", "Area matching follows COCO's area-ignore rules; boundary/ignore matching can make area-bin TP counts nonadditive. Small objects remain a major weakness. Clean held-out coverage: 604 nonignored instances; per-class evidence is sparse and category-level improvements/regressions must be read with their denominators. Full class metrics are available as [CSV](per-class-held-out.csv). Doors, stairs, curbs, crossings, keys and arbitrary categories remain unsupported. More pixels never supply missing class coverage.", "",
              "| Relevant category | Eligible GT | Baseline recall | INSPECT recall | Baseline / INSPECT FP |", "| --- | ---: | ---: | ---: | ---: |"]
    for category in ("person", "chair", "dining table", "couch", "backpack", "handbag", "bottle", "cup", "car", "bicycle", "truck", "bench", "traffic light", "stop sign", "book", "potted plant"):
        old = clean["baseline"]["per_class_operating_counts"].get(category)
        new = clean["candidate"]["per_class_operating_counts"].get(category)
        if old is None or old["recall"] is None:
            lines.append(f"| {category} | 0 | unmeasured | unmeasured | {old['fp'] if old else 0} / {new['fp'] if new else 0} |")
        else:
            lines.append(f"| {category} | {old['tp']+old['fn']} | {old['recall']:.3f} | {new['recall']:.3f} | {old['fp']} / {new['fp']} |")
    lines += ["", "These are closed-set image detections, with no proof of identity, ownership or a navigable path. Several category results regress or have very small support; the aggregate gain does not imply every category improves.", "",
              "## Runtime and selection consequence", "",
              "| Model | Bytes | Paired desktop P50 / P95 ms | Whole-process warm RSS MiB |", "| --- | ---: | ---: | ---: |"]
    for model_id in selection["candidates"]:
        split = "held_out" if model_id != "ssd_mobilenet_v2_float32" else "selection"
        summary = json.loads((OUT / split / model_id / "summary.json").read_text())
        timed = latency["models"][model_id]
        lines.append(f"| {model_id} | {summary['model_bytes']} | {timed['p50_ms']:.2f} / {timed['p95_ms']:.2f} | {summary['memory_after_warmup']['working_set_bytes']/1048576:.1f} |")
    lines += ["", "Paired timing: same 16 legacy photos, 80 calls/model, 5 warmups, shuffled image/repeat order and alternating model order, i7-14700HX, Windows, MediaPipe 1.1.1 CPU, affinity logical CPUs 0-3. Runtime threads default. Three models coexist for timing. Warm RSS comes from separate per-model processes during the expanded accuracy run and includes Python/runtime/imports/image memory; it is not model-only memory and SSD uses selection rather than held-out. Raw [paired timings](../v3-paired-latency/summary.json) and per-model memory snapshots preserve scope. P95 is exploratory; P99 omitted. Current measurements cannot be compared causally to historical 64.92 ms because host load and experiment composition differ. Phone FPS, latency, thermals and battery require separate Android/device evidence.", "",
              "Lite2 is materially slower, so FAST stays Lite0 for continuous updates. Higher-detail INSPECT is selective, with deadline/thermal fallback in the Android scheduler; it does not replace every camera frame. The Android implementation must be evaluated separately before claiming the policy's real-world task benefit. [Research rationale](model-research.md) records why this compatible, permissively sourced export was selected over an unvalidated new runtime.", "",
              "## Reproduce", "", "```powershell", "evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_v3_corpus.py", "evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark.py --config evaluation/config-v2.json --output evaluation/results/v3-baseline-reproduction", "evaluation/.venv-modern/Scripts/python.exe evaluation/diagnose_v3_baseline.py", "evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py selection", "evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py calibration", "evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py held-out", "evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py --id paired-model-latency-v3 --output evaluation/results/v3-paired-latency --model-ids efficientdet_lite0_int8,efficientdet_lite2_int8,ssd_mobilenet_v2_float32", "evaluation/.venv-modern/Scripts/python.exe evaluation/report_v3_detection.py", "evaluation/.venv-modern/Scripts/python.exe -m unittest discover -s evaluation/tests -v", "```", "",
              "Corpus re-runs verify sealed photo hashes and restore missing originals from pinned URLs. Selection/calibration commands refuse to overwrite sealed decisions; to reproduce inference without changing decisions run `v3_detection.py worker --split selection --model <id>` (and calibration/held_out as needed), then compare new raw predictions to versioned evidence. Use --output evaluation/results/v3-reproduction on all three stage commands to reproduce in a fresh directory without altering sealed decisions. Do not delete decisions to tune final-test outcomes. Dataset and model hashes are recorded in every inference result; exact upstream artifacts are pinned in models/manifest.json."]
    (OUT / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in uncertainty.items() if key != "per_group_counts"}, indent=2))


if __name__ == "__main__":
    main()
