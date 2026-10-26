"""One additional licensed candidate, with a newly sealed final80-photo audit.

Earlier80held-out outcomes are never opened here. Selection/calibration reuse
their original development splits; a separate corpus excludes every earlier
photo, recovered creator and close perceptual duplicate.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import random
import time

import mediapipe as mp
import numpy as np
import onnxruntime as ort
from PIL import Image
from pycocotools.coco import COCO

from benchmark import transform
from v3_detection import digest, memory, metrics
from yolox_adapter import YoloXTiny, classes, verify_reference

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/v3-yolox"
BASE = ROOT / "evaluation/data-v3-yolox"
PRIOR = ROOT / "evaluation/results/v3-detection"
YOLO_ID = "yolox_tiny_onnx"
MP_IDS = ("efficientdet_lite0_int8", "efficientdet_lite2_int8")


def read(path):
    return json.loads(path.read_text())


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def new_plan():
    # Called before candidate inference; a sealed fresh corpus must exist first.
    target = OUT / "experiment-plan.json"
    if target.exists():
        return read(target)
    if not (BASE / "manifest.json").exists():
        raise RuntimeError("Seal fresh80before comparing candidate")
    model = read(ROOT / "models/yolox_tiny_manifest.json")
    if digest(ROOT / model["path"]) != model["sha256"]:
        raise RuntimeError("Candidate model hash mismatch")
    for entry in read(ROOT / "evaluation/yolox-reference/provenance.json")["files"]:
        if digest(ROOT / entry["local_path"]) != entry["sha256"]:
            raise RuntimeError("Pinned upstream source changed")
    checks = verify_reference()
    value = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "candidate": model,
             "fresh_audit_manifest_sha256": digest(BASE / "manifest.json"),
             "selection_calibration_manifest_sha256": digest(ROOT / "evaluation/data-v3/manifest.json"),
             "prior_test_use": "Old80test is excluded from this additional candidate selection and final claims; it motivated investigating another model only",
             "selection_rule": {"fast_replacement": "selection AP50>=FAST+.04 AND AP50:95>=FAST+.04 AND paired full-task P50<=FAST*1.30",
                                "inspect_replacement": "selection AP50>=INSPECT-.03 AND AP50:95>=INSPECT-.03 AND paired full-task P50<=INSPECT*.70",
                                "precision_threshold_choice": "Selection uses ranked AP, not confidence probability comparisons. Threshold selection occurs only on calibration; calibration must preserve reference precision and bounded FP/image, and improve recall by.02 before freshaudit"},
             "calibration_grid": [round(value, 2) for value in np.arange(.30, .901, .05)],
             "ranking_score_floor": .05, "max_ranked_results": 100, "deployment_cap": 40,
             "onnxruntime": ort.__version__, "mediapipe": mp.__version__,
             "onnxruntime_cpu_threads": {"intra_op": 4, "inter_op": 1, "execution": "sequential"},
             "upstream_verification": checks, "paired_timing": {"seed": 20261026, "selection_images": 50, "repetitions": 2, "warmups_per_model": 5,
                               "model_order": "rotate all3model orders across shuffled identical image/repeat trials", "affinity": "Windows logical CPUs0-3 (0xF)"}}
    save(target, value)
    return value


def data(split):
    if split == "audit":
        manifest = read(BASE / "manifest.json")
        entries = manifest["images"]
        directory = BASE
        annotation = BASE / "instances_audit.json"
    else:
        manifest = read(ROOT / "evaluation/data-v3/manifest.json")
        entries = [image for image in manifest["images"] if image["split"] == split]
        directory = ROOT / "evaluation/data-v3"
        annotation = directory / f"instances_{split}.json"
    coco = COCO(str(annotation))
    return entries, directory, coco


def yolo_inference(split, variants=("clean",)):
    entries, directory, coco = data(split)
    model = read(ROOT / "models/yolox_tiny_manifest.json")
    if digest(ROOT / model["path"]) != model["sha256"]:
        raise RuntimeError("Candidate weights changed")
    before = memory()
    detector = YoloXTiny(ROOT / model["path"])
    first = np.asarray(Image.open(directory / entries[0]["local_path"]).convert("RGB"))
    for _ in range(5):
        detector.detect(first)
    warm = memory()
    mapping = {category["name"]: category["id"] for category in coco.cats.values()}
    labels = classes()
    if len(labels) != 80 or set(labels) != set(mapping):
        raise RuntimeError("COCO class mapping mismatch")
    result = {"model": model, "split": split, "memory_before": before, "memory_after_warmup": warm, "variants": {},
              "onnxruntime": ort.__version__, "plan_sha256": digest(OUT / "experiment-plan.json")}
    for variant in variants:
        predictions, timings = [], []
        for entry in entries:
            path = directory / entry["local_path"]
            if digest(path) != entry["sha256"]:
                raise RuntimeError("Photo hash mismatch")
            rgb = transform(np.asarray(Image.open(path).convert("RGB")), variant)
            started = time.perf_counter_ns()
            detections = detector.detect(rgb)
            timings.append({"image_id": entry["id"], "total_ms": (time.perf_counter_ns() - started) / 1e6})
            for box, confidence, category in detections:
                predictions.append({"image_id": entry["id"], "category_id": mapping[labels[category]], "score": confidence,
                                    "bbox": [float(box[0]), float(box[1]), float(box[2] - box[0]), float(box[3] - box[1])]})
        save(OUT / split / YOLO_ID / f"{variant}_predictions.json", predictions)
        values = [timing["total_ms"] for timing in timings]
        measured = metrics(coco, predictions, .5, 40)
        measured["timing"] = {"scope": "RGB->BGR/resize/padding/CHW float conversion+ORT CPU inference+grid/stride decode+NMS; disk image decode excluded",
                              "p50_ms": float(np.median(values)), "p95_ms": float(np.percentile(values, 95)), "samples": timings}
        result["variants"][variant] = measured
        print(split, variant, "AP50", measured["subset_AP50"], "AP50:95", measured["subset_AP50_95"], "P/R", measured["precision_at_operating_threshold"], measured["recall_at_operating_threshold"], flush=True)
    result["memory_end"] = memory()
    save(OUT / split / YOLO_ID / "summary.json", result)
    return result


def make_mediapipe(model_id):
    manifest = read(ROOT / "models/manifest.json")
    model = next(model for model in manifest["models"] if model["id"] == model_id)
    if digest(ROOT / model["path"]) != model["sha256"]:
        raise RuntimeError("Baseline model changed")
    return mp.tasks.vision.ObjectDetector.create_from_options(mp.tasks.vision.ObjectDetectorOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(ROOT / model["path"])),
        score_threshold=.05, max_results=100))


def paired_timing(output_name="paired-selection-timing.json", load_label="contended/exploratory"):
    entries, directory, _ = data("selection")
    frames = [(entry["id"], np.asarray(Image.open(directory / entry["local_path"]).convert("RGB"))) for entry in entries]
    detectors = {model_id: make_mediapipe(model_id) for model_id in MP_IDS}
    detectors[YOLO_ID] = YoloXTiny(ROOT / "models/yolox_tiny.onnx")
    for model_id, detector in detectors.items():
        for _ in range(5):
            if model_id == YOLO_ID:
                detector.detect(frames[0][1])
            else:
                detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=frames[0][1]))
    trials = [(repeat, image_id, rgb) for repeat in range(2) for image_id, rgb in frames]
    random.Random(20261026).shuffle(trials)
    samples = []
    for trial_index, (repeat, image_id, rgb) in enumerate(trials):
        model_ids = list(detectors)
        shift = trial_index % len(model_ids)
        model_ids = model_ids[shift:] + model_ids[:shift]
        frame = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        for model_id in model_ids:
            started = time.perf_counter_ns()
            detectors[model_id].detect(rgb if model_id == YOLO_ID else frame)
            samples.append({"model_id": model_id, "image_id": image_id, "repeat": repeat, "trial": trial_index,
                            "total_ms": (time.perf_counter_ns() - started) / 1e6})
    for model_id, detector in detectors.items():
        if model_id in MP_IDS:
            detector.close()
    result = {"samples": samples, "models": {}, "plan_sha256": digest(OUT / "experiment-plan.json"),
              "memory_all_models_coexist": memory(), "scope": "total synchronous detector call; MediaPipe native preprocessing/inference/postprocess; YOLOX Python preprocessing/ORT/decode/NMS; both exclude file decode and MediaPipe Image construction",
              "host": "Windows/i7-14700HX/mains; affinitylogical0-3; ORT4intra/1inter threads; MediaPipe runtime defaults; no phone/battery conclusion",
              "host_load_label": load_label}
    for model_id in detectors:
        values = [sample["total_ms"] for sample in samples if sample["model_id"] == model_id]
        result["models"][model_id] = {"n": len(values), "p50_ms": float(np.median(values)), "p95_ms": float(np.percentile(values, 95))}
    save(OUT / output_name, result)
    return result


def selection():
    if (OUT / "selection-decision.json").exists():
        raise RuntimeError("Selection already sealed")
    candidate = yolo_inference("selection")["variants"]["clean"]
    timings = paired_timing()
    decision = selection_from_metrics(candidate, timings)
    save(OUT / "selection-decision.json", decision)
    print("SELECTION", decision["eligible_fast"], decision["eligible_inspect"], json.dumps(timings["models"]), flush=True)


def selection_from_metrics(candidate, timings):
    prior = {model_id: read(PRIOR / "selection" / model_id / "summary.json")["variants"]["clean"] for model_id in MP_IDS}
    fast, inspect = (prior[model_id] for model_id in MP_IDS)
    latency = timings["models"]
    fast_ok = (candidate["subset_AP50"] >= fast["subset_AP50"] + .04 and candidate["subset_AP50_95"] >= fast["subset_AP50_95"] + .04 and latency[YOLO_ID]["p50_ms"] <= latency[MP_IDS[0]]["p50_ms"] * 1.30)
    inspect_ok = (candidate["subset_AP50"] >= inspect["subset_AP50"] - .03 and candidate["subset_AP50_95"] >= inspect["subset_AP50_95"] - .03 and latency[YOLO_ID]["p50_ms"] <= latency[MP_IDS[1]]["p50_ms"] * .70)
    decision = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "plan_sha256": digest(OUT / "experiment-plan.json"),
                "eligible_fast": fast_ok, "eligible_inspect": inspect_ok, "proceed_to_calibration": fast_ok or inspect_ok,
                "candidate": candidate, "baseline_fast": fast, "baseline_inspect": inspect, "paired_latency": latency,
                "fresh_audit_inference_executed": False, "reference_policy": "FAST" if fast_ok else "INSPECT" if inspect_ok else "REJECTED"}
    return decision


def quiet_selection():
    # Preserve the first run and its provisional decision explicitly; root
    # identified concurrent Gradle load before calibration/final inference.
    # Accuracy outputs are unchanged. Only the same predeclared timing gate is
    # reevaluated under a root-coordinated quiet host interval.
    for source, archived in (("paired-selection-timing.json", "paired-selection-timing-contended.json"),
                             ("selection-decision.json", "selection-decision-contended.json")):
        path = OUT / source
        destination = OUT / archived
        if path.exists() and not destination.exists():
            path.rename(destination)
    timings = paired_timing("paired-selection-timing-quiet.json", "root paused Gradle/emulator work for this run; other OS load remains uncontrolled")
    candidate = read(OUT / "selection" / YOLO_ID / "summary.json")["variants"]["clean"]
    decision = selection_from_metrics(candidate, timings)
    decision["timing_artifact"] = "paired-selection-timing-quiet.json"
    decision["contended_initial_decision_archived"] = "selection-decision-contended.json"
    save(OUT / "selection-decision.json", decision)
    print("QUIETSELECTION", decision["eligible_fast"], decision["eligible_inspect"], json.dumps(timings["models"]), flush=True)


def calibration():
    decision = read(OUT / "selection-decision.json")
    if not decision["proceed_to_calibration"]:
        raise RuntimeError("Candidate rejected by predeclared selection gate; do not spend freshaudit")
    if (OUT / "calibration-decision.json").exists():
        raise RuntimeError("Calibration already sealed")
    yolo_inference("calibration")
    _, _, coco = data("calibration")
    predictions = read(OUT / "calibration" / YOLO_ID / "clean_predictions.json")
    reference_id = MP_IDS[0] if decision["reference_policy"] == "FAST" else MP_IDS[1]
    reference_threshold, reference_cap = (.5, 20) if reference_id == MP_IDS[0] else (.55, 40)
    reference = metrics(coco, read(PRIOR / "calibration" / reference_id / "clean_predictions.json"), reference_threshold, reference_cap)
    plan = read(OUT / "experiment-plan.json")
    rows = [{"threshold": threshold, "metrics": metrics(coco, predictions, threshold, 40)} for threshold in plan["calibration_grid"]]
    eligible = [row for row in rows if row["metrics"]["precision_at_operating_threshold"] >= reference["precision_at_operating_threshold"] and row["metrics"]["false_positives_per_image"] <= reference["false_positives_per_image"] * 1.25 + .05]
    chosen = max(eligible, key=lambda row: (row["metrics"]["recall_at_operating_threshold"], row["threshold"])) if eligible else None
    proceed = chosen is not None and chosen["metrics"]["recall_at_operating_threshold"] >= reference["recall_at_operating_threshold"] + .02
    value = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "reference_policy": decision["reference_policy"], "reference": reference,
             "grid": rows, "chosen": chosen, "proceed_to_fresh_audit": proceed, "selection_sha256": digest(OUT / "selection-decision.json"),
             "rule": "preserve calibration reference precision, boundFP/image<=reference*1.25+.05, then require recall improvement>=.02; ties choosehigherthreshold",
             "fresh_audit_inference_executed": False}
    save(OUT / "calibration-decision.json", value)
    print("CALIBRATION", proceed, chosen["threshold"] if chosen else None, flush=True)


def mediapipe_audit(model_id, variants):
    entries, directory, coco = data("audit")
    detector = make_mediapipe(model_id)
    mapping = {category["name"]: category["id"] for category in coco.cats.values()}
    for variant in variants:
        predictions = []
        for entry in entries:
            path = directory / entry["local_path"]
            if digest(path) != entry["sha256"]:
                raise RuntimeError("Photo hash changed")
            rgb = transform(np.asarray(Image.open(path).convert("RGB")), variant)
            detected = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
            for detection in detected.detections:
                box = detection.bounding_box
                category = detection.categories[0]
                predictions.append({"image_id": entry["id"], "category_id": mapping[category.category_name], "score": category.score,
                                    "bbox": [box.origin_x, box.origin_y, box.width, box.height]})
        save(OUT / "audit" / model_id / f"{variant}_predictions.json", predictions)
    detector.close()


def audit():
    calibrated = read(OUT / "calibration-decision.json")
    if not calibrated["proceed_to_fresh_audit"]:
        raise RuntimeError("Calibration rejected candidate; final80must remain unused")
    if calibrated["selection_sha256"] != digest(OUT / "selection-decision.json"):
        raise RuntimeError("Sealed decision changed")
    variants = ("clean", "dim_035", "motion_blur_15")
    yolo_inference("audit", variants)
    for model_id in MP_IDS:
        mediapipe_audit(model_id, variants)
    _, _, coco = data("audit")
    result = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "calibration_sha256": digest(OUT / "calibration-decision.json"),
              "fresh_audit_manifest_sha256": digest(BASE / "manifest.json"), "variants": {}, "earlier80test_read": False}
    for variant in variants:
        result["variants"][variant] = {}
        for model_id, threshold, cap in ((MP_IDS[0], .5, 20), (MP_IDS[1], .55, 40), (YOLO_ID, calibrated["chosen"]["threshold"], 40)):
            predictions = read(OUT / "audit" / model_id / f"{variant}_predictions.json")
            result["variants"][variant][model_id] = metrics(coco, predictions, threshold, cap)
    save(OUT / "fresh-audit-comparison.json", result)
    print("AUDIT", json.dumps({model_id: {key: value[key] for key in ("subset_AP50", "subset_AP50_95", "precision_at_operating_threshold", "recall_at_operating_threshold", "false_positives_per_image")} for model_id, value in result["variants"]["clean"].items()}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("selection", "quiet-timing", "calibration", "audit", "verify-reference"))
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.stage == "verify-reference":
        print(verify_reference())
        return
    plan = new_plan()
    if plan["fresh_audit_manifest_sha256"] != digest(BASE / "manifest.json") or plan["selection_calibration_manifest_sha256"] != digest(ROOT / "evaluation/data-v3/manifest.json"):
        raise RuntimeError("Corpus changed after experiment plan")
    if args.stage == "selection": selection()
    elif args.stage == "quiet-timing": quiet_selection()
    elif args.stage == "calibration": calibration()
    elif args.stage == "audit": audit()


if __name__ == "__main__":
    main()
