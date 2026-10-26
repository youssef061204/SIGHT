"""SIGHT 3 controlled detector selection, calibration, and sealed held-out scoring.

The held-out command requires the prior selection/calibration decision artifact.
No threshold or candidate is chosen by the held-out command. All inference is real.
"""
from __future__ import annotations

import argparse
from collections import Counter
import contextlib
import ctypes
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import time

if platform.system() == "Windows":
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    kernel.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    if not kernel.SetProcessAffinityMask(kernel.GetCurrentProcess(), 0xF):
        raise ctypes.WinError(ctypes.get_last_error())

import mediapipe as mp
import numpy as np
from PIL import Image, ImageDraw
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

from benchmark import empty_results, score, transform

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "evaluation/data-v3"
OUT = ROOT / "evaluation/results/v3-detection"
MODEL_IDS = ("efficientdet_lite0_int8", "efficientdet_lite2_int8", "ssd_mobilenet_v2_float32")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def memory():
    if platform.system() != "Windows":
        return {"working_set_bytes": None, "peak_working_set_bytes": None, "scope": "not measured on this host"}
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    psapi = ctypes.WinDLL("psapi")
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
        raise ctypes.WinError()
    return {"working_set_bytes": counters.WorkingSetSize, "peak_working_set_bytes": counters.PeakWorkingSetSize,
            "scope": "entire isolated Python process including imports, runtime, model, image decode and scoring; not model-only or Android memory"}


def get_coco(split):
    with contextlib.redirect_stdout(io.StringIO()):
        return COCO(str(BASE / f"instances_{split}.json"))


def get_manifest():
    manifest = json.loads((BASE / "manifest.json").read_text())
    ids = {image["id"] for image in manifest["images"]}
    if len(ids) != len(manifest["images"]):
        raise RuntimeError("Duplicate image ids")
    for group_key in ("scene_group",):
        groups = {}
        for image in manifest["images"]:
            groups.setdefault(image[group_key], set()).add(image["split"])
        if any(len(splits) > 1 for splits in groups.values()):
            raise RuntimeError("Scene group leaked across splits")
    return manifest


def cap_predictions(predictions, threshold, limit):
    result = []
    for image_id in sorted({prediction["image_id"] for prediction in predictions}):
        candidates = sorted((prediction for prediction in predictions if prediction["image_id"] == image_id and prediction["score"] >= threshold), key=lambda row: -row["score"])
        result.extend(candidates[:limit])
    return result


def metrics(coco, predictions, threshold, deployment_limit=40):
    result = score(coco, predictions, threshold)
    # AP always uses the same ranked .05 floor/max100 outputs, not a threshold-
    # truncated curve. Operating metrics below use the actual deployment cap.
    operating = cap_predictions(predictions, threshold, deployment_limit)
    operating_result = score(coco, operating, threshold)
    for key in ("tp", "fp", "fn", "precision_at_operating_threshold", "recall_at_operating_threshold", "per_class_operating_counts"):
        result[key] = operating_result[key]
    result["operating_score_threshold"] = threshold
    result["deployment_limit"] = deployment_limit
    result["false_positives_per_image"] = result["fp"] / len(coco.imgs)
    result["image_count"] = len(coco.imgs)
    with contextlib.redirect_stdout(io.StringIO()):
        detections = coco.loadRes(operating) if operating else empty_results(coco)
        evaluator = COCOeval(coco, detections, "bbox")
        evaluator.evaluate()
    result["area_operating_counts"] = {}
    for area_index, label in enumerate(("all", "small", "medium", "large")):
        tp = fn = 0
        for item in evaluator.evalImgs:
            if item is None or item["aRng"] != evaluator.params.areaRng[area_index]:
                continue
            matched = (item["dtMatches"][0] > 0) & ~item["dtIgnore"][0]
            hits = int(np.sum(matched))
            tp += hits
            fn += int(np.sum(~np.asarray(item["gtIgnore"], dtype=bool))) - hits
        result["area_operating_counts"][label] = {"tp": tp, "fn": fn, "recall": tp / (tp + fn) if tp + fn else None}
    return result


def infer_worker(split, model_id, variants):
    manifest = get_manifest()
    models = json.loads((ROOT / "models/manifest.json").read_text())
    model = next(model for model in models["models"] if model["id"] == model_id)
    model_path = ROOT / model["path"]
    if digest(model_path) != model["sha256"]:
        raise RuntimeError("Model hash mismatch")
    memory_before = memory()
    started = time.perf_counter_ns()
    detector = mp.tasks.vision.ObjectDetector.create_from_options(mp.tasks.vision.ObjectDetectorOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path), delegate=mp.tasks.BaseOptions.Delegate.CPU),
        running_mode=mp.tasks.vision.RunningMode.IMAGE, score_threshold=.05, max_results=100))
    startup_ms = (time.perf_counter_ns() - started) / 1e6
    coco = get_coco(split)
    category_ids = {category["name"]: category["id"] for category in coco.cats.values()}
    entries = [image for image in manifest["images"] if image["split"] == split]
    first = np.asarray(Image.open(BASE / entries[0]["local_path"]).convert("RGB"))
    for _ in range(5):
        detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=first))
    memory_warm = memory()
    output = OUT / split / model_id
    output.mkdir(parents=True, exist_ok=True)
    result = {"model_id": model_id, "model_sha256": model["sha256"], "model_bytes": model_path.stat().st_size,
              "startup_ms": startup_ms, "memory_before": memory_before, "memory_after_warmup": memory_warm,
              "variants": {}, "environment": {"python": platform.python_version(), "platform": platform.platform(),
                "mediapipe": mp.__version__, "cpu": "Intel Core i7-14700HX", "cpu_affinity": "Windows logical CPUs0-3 (0xF)",
                "runtime_threads": "MediaPipe default; not overridden", "delegate": "CPU", "timing_caveat": "accuracy-sweep timing only; host work can add noise; no phone or battery claim"},
              "dataset_manifest_sha256": digest(BASE / "manifest.json"), "annotation_sha256": digest(BASE / f"instances_{split}.json")}
    for variant in variants:
        predictions, latencies = [], []
        for entry in entries:
            path = BASE / entry["local_path"]
            if digest(path) != entry["sha256"]:
                raise RuntimeError("Photo hash mismatch")
            rgb = transform(np.asarray(Image.open(path).convert("RGB")), variant)
            frame = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            started = time.perf_counter_ns()
            detected = detector.detect(frame)
            latencies.append({"image_id": entry["id"], "ms": (time.perf_counter_ns() - started) / 1e6})
            for detection in detected.detections:
                category = detection.categories[0]
                if category.category_name not in category_ids:
                    raise RuntimeError("Unknown class label: " + category.category_name)
                box = detection.bounding_box
                predictions.append({"image_id": entry["id"], "category_id": category_ids[category.category_name],
                                    "bbox": [box.origin_x, box.origin_y, box.width, box.height], "score": category.score})
        (output / f"{variant}_predictions.json").write_text(json.dumps(predictions, indent=2), encoding="utf-8")
        values = np.asarray([sample["ms"] for sample in latencies])
        scored = metrics(coco, predictions, .5)
        scored["latency_ms"] = {"n": len(latencies), "p50": float(np.median(values)), "p95": float(np.percentile(values, 95)),
                                "scope": "detect call includes native resize/inference/postprocess; decode/mp.Image creation excluded; exploratory samples"}
        scored["latencies"] = latencies
        result["variants"][variant] = scored
        print(split, model_id, variant, "AP50", round(scored["subset_AP50"], 4), "P/R", scored["precision_at_operating_threshold"], scored["recall_at_operating_threshold"], flush=True)
    result["memory_end"] = memory()
    detector.close()
    (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


def run_workers(split, model_ids, variants):
    for model_id in model_ids:
        command = [os.fspath(Path(os.sys.executable)), os.fspath(Path(__file__).resolve()), "worker", "--split", split, "--model", model_id, "--variants", ",".join(variants), "--output", os.fspath(OUT)]
        subprocess.run(command, check=True)


def main():
    global OUT
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("selection", "calibration", "held-out", "worker"))
    parser.add_argument("--split", choices=("selection", "calibration", "held_out"))
    parser.add_argument("--model", choices=MODEL_IDS)
    parser.add_argument("--variants", default="clean")
    parser.add_argument("--output", default="evaluation/results/v3-detection")
    args = parser.parse_args()
    OUT = ROOT / args.output
    if mp.__version__ != "1.1.1":
        raise RuntimeError("This versioned evaluation requires MediaPipe1.1.1")
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = get_manifest()
    if args.stage == "worker":
        infer_worker(args.split, args.model, args.variants.split(","))
        return
    selection_path = OUT / "selection-decision.json"
    calibration_path = OUT / "calibration-decision.json"
    if args.stage == "selection":
        if selection_path.exists():
            raise RuntimeError("Selection is sealed; use a new versioned experiment to change candidates")
        run_workers("selection", MODEL_IDS, ("clean",))
        summaries = {model_id: json.loads((OUT / "selection" / model_id / "summary.json").read_text()) for model_id in MODEL_IDS}
        # Predeclared: select highest clean AP50 among existing CPU-compatible
        # metadata models with >= baseline operating precision and recall.
        baseline = summaries[MODEL_IDS[0]]["variants"]["clean"]
        eligible = [model_id for model_id in MODEL_IDS if summaries[model_id]["variants"]["clean"]["precision_at_operating_threshold"] >= baseline["precision_at_operating_threshold"] and summaries[model_id]["variants"]["clean"]["recall_at_operating_threshold"] >= baseline["recall_at_operating_threshold"]]
        chosen = max(eligible, key=lambda model_id: summaries[model_id]["variants"]["clean"]["subset_AP50"])
        decision = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "dataset_manifest_sha256": digest(BASE / "manifest.json"), "split_counts": manifest["split_counts"],
                    "candidates": list(MODEL_IDS), "selected_model": chosen, "baseline_model": MODEL_IDS[0],
                    "rule": "highest selection clean AP50 among metadata/CPU-compatible candidates with precision AND recall at .5 at least baseline; slower candidate is selective inspection, not automatic continuous default",
                    "observations": {model_id: summary["variants"]["clean"] for model_id, summary in summaries.items()}, "held_out_read": False}
        selection_path.write_text(json.dumps(decision, indent=2), encoding="utf-8")
        print("SELECTED", chosen, flush=True)
    elif args.stage == "calibration":
        if calibration_path.exists():
            raise RuntimeError("Calibration is sealed; use a new experiment to change policy")
        selection = json.loads(selection_path.read_text())
        if selection["dataset_manifest_sha256"] != digest(BASE / "manifest.json"):
            raise RuntimeError("Dataset changed after selection")
        selected = selection["selected_model"]
        run_workers("calibration", list(dict.fromkeys((MODEL_IDS[0], selected))), ("clean",))
        coco = get_coco("calibration")
        baseline_predictions = json.loads((OUT / "calibration" / MODEL_IDS[0] / "clean_predictions.json").read_text())
        candidate_predictions = json.loads((OUT / "calibration" / selected / "clean_predictions.json").read_text())
        baseline = metrics(coco, baseline_predictions, .5, 20)
        thresholds = (.3, .35, .4, .45, .5, .55, .6, .65)
        rows = [{"threshold": threshold, "metrics": metrics(coco, candidate_predictions, threshold)} for threshold in thresholds]
        # False-positive rate is explicitly bounded relative to the actual old
        # deployment policy, in addition to preserving calibration precision.
        max_fp = baseline["false_positives_per_image"] * 1.25 + .05
        eligible = [row for row in rows if row["metrics"]["precision_at_operating_threshold"] >= baseline["precision_at_operating_threshold"] and row["metrics"]["false_positives_per_image"] <= max_fp]
        if not eligible:
            raise RuntimeError("No candidate threshold preserves the calibration precision/FP guard; retain baseline")
        chosen = max(eligible, key=lambda row: (row["metrics"]["recall_at_operating_threshold"], row["threshold"]))
        decision = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "selected_model": selected,
                    "operating_score_threshold": chosen["threshold"], "deployment_limit": 40, "dataset_manifest_sha256": digest(BASE / "manifest.json"),
                    "selection_decision_sha256": digest(selection_path), "baseline": baseline, "grid": rows,
                    "rule": "maximize calibration recall subject to precision >= baseline Lite0@.5 and FP/image <= baseline FP/image*1.25+.05; ties prefer higher confidence; max40 candidate vs original20",
                    "fp_per_image_limit": max_fp, "selected_metrics": chosen["metrics"], "held_out_read": False}
        calibration_path.write_text(json.dumps(decision, indent=2), encoding="utf-8")
        print("CALIBRATED", selected, chosen["threshold"], flush=True)
    elif args.stage == "held-out":
        selection = json.loads(selection_path.read_text())
        calibration = json.loads(calibration_path.read_text())
        if calibration["dataset_manifest_sha256"] != digest(BASE / "manifest.json") or calibration["selection_decision_sha256"] != digest(selection_path):
            raise RuntimeError("Sealed selection/calibration changed")
        selected = selection["selected_model"]
        run_workers("held_out", list(dict.fromkeys((MODEL_IDS[0], selected))), ("clean", "dim_035", "motion_blur_15"))
        coco = get_coco("held_out")
        result = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "selection_sha256": digest(selection_path),
                  "calibration_sha256": digest(calibration_path), "dataset_manifest_sha256": digest(BASE / "manifest.json"), "variants": {}}
        for variant in ("clean", "dim_035", "motion_blur_15"):
            baseline_predictions = json.loads((OUT / "held_out" / MODEL_IDS[0] / f"{variant}_predictions.json").read_text())
            candidate_predictions = json.loads((OUT / "held_out" / selected / f"{variant}_predictions.json").read_text())
            baseline = metrics(coco, baseline_predictions, .5, 20)
            candidate = metrics(coco, candidate_predictions, calibration["operating_score_threshold"], calibration["deployment_limit"])
            equal_threshold = metrics(coco, candidate_predictions, .5, 20)
            result["variants"][variant] = {"baseline": baseline, "candidate": candidate, "candidate_same_operating_point_as_baseline": equal_threshold,
                                           "delta": {"AP50": candidate["subset_AP50"] - baseline["subset_AP50"], "AP50_95": candidate["subset_AP50_95"] - baseline["subset_AP50_95"],
                                                     "precision": candidate["precision_at_operating_threshold"] - baseline["precision_at_operating_threshold"],
                                                     "recall": candidate["recall_at_operating_threshold"] - baseline["recall_at_operating_threshold"],
                                                     "fp_per_image": candidate["false_positives_per_image"] - baseline["false_positives_per_image"]}}
        result["limitations"] = "80-photo public COCO subset, no phone or physical-assistive-use conclusion. Model/threshold decisions sealed before held-out inference; held-out synthetic perturbations do not add independent photos. Sparse per-class samples; report raw counts. No claim all Flickr scenes or model-training samples independent."
        (OUT / "held-out-comparison.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print("HELD OUT", json.dumps({variant: row["delta"] for variant, row in result["variants"].items()}, indent=2), flush=True)


if __name__ == "__main__":
    main()
