"""Real MediaPipe model inference, official COCO scoring, and reproducible evidence."""
from __future__ import annotations

import argparse
import contextlib
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time

import cv2
import mediapipe as mp
import numpy as np
from PIL import Image, ImageDraw
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def transform(rgb, variant):
    if variant == "dim_035":
        return np.ascontiguousarray(np.clip(rgb.astype(np.float32) * .35, 0, 255).astype(np.uint8))
    if variant == "motion_blur_15":
        kernel = np.zeros((15, 15), dtype=np.float32)
        kernel[7, :] = 1 / 15
        return np.ascontiguousarray(cv2.filter2D(rgb, -1, kernel))
    return rgb


def score(coco, predictions, threshold):
    # The official evaluator implements crowd ignores, greedy same-class IoU
    # matching, area ranges and 101-point interpolated AP; avoid custom AP claims.
    with contextlib.redirect_stdout(io.StringIO()):
        detections = coco.loadRes(predictions) if predictions else empty_results(coco)
        evaluator = COCOeval(coco, detections, "bbox")
        evaluator.params.imgIds = sorted(coco.imgs)
        evaluator.evaluate()
        evaluator.accumulate()
        evaluator.summarize()
    precision = evaluator.eval["precision"]
    per_class = {}
    for index, category_id in enumerate(evaluator.params.catIds):
        array = precision[0, :, index, 0, -1]
        valid = array[array > -1]
        if valid.size:
            per_class[coco.cats[category_id]["name"]] = float(np.mean(valid))
    # Read actual official IoU=.5 matches at the deployment score threshold.
    tp = fp = fn = 0
    by_class = {}
    for item in evaluator.evalImgs:
        if item is None or item["aRng"] != evaluator.params.areaRng[0]:
            continue
        selected = np.asarray(item["dtScores"]) >= threshold
        ignored = item["dtIgnore"][0]
        matched = item["dtMatches"][0] > 0
        this_tp = int(np.sum(selected & ~ignored & matched))
        this_fp = int(np.sum(selected & ~ignored & ~matched))
        this_fn = int(np.sum(~np.asarray(item["gtIgnore"], dtype=bool))) - this_tp
        tp += this_tp
        fp += this_fp
        fn += this_fn
        name = coco.cats[item["category_id"]]["name"]
        aggregate = by_class.setdefault(name, {"tp": 0, "fp": 0, "fn": 0})
        for key, value in (("tp", this_tp), ("fp", this_fp), ("fn", this_fn)):
            aggregate[key] += value
    for values in by_class.values():
        values["precision"] = values["tp"] / (values["tp"] + values["fp"]) if values["tp"] + values["fp"] else None
        values["recall"] = values["tp"] / (values["tp"] + values["fn"]) if values["tp"] + values["fn"] else None
    return {"subset_AP50_95": float(evaluator.stats[0]), "subset_AP50": float(evaluator.stats[1]),
            "tp": tp, "fp": fp, "fn": fn, "precision_at_operating_threshold": tp / (tp + fp) if tp + fp else None,
            "recall_at_operating_threshold": tp / (tp + fn) if tp + fn else None,
            "per_class_AP50": per_class, "per_class_operating_counts": by_class}


def empty_results(coco):
    result = COCO()
    result.dataset = {"images": list(coco.imgs.values()), "categories": list(coco.cats.values()), "annotations": []}
    result.createIndex()
    return result


def annotate(rgb, predictions, categories, target, threshold):
    image = Image.fromarray(rgb)
    draw = ImageDraw.Draw(image)
    for prediction in predictions:
        if prediction["score"] < threshold:
            continue
        x, y, width, height = prediction["bbox"]
        draw.rectangle((x, y, x + width, y + height), outline="#00ff88", width=3)
        text = f"{categories[prediction['category_id']]['name']} {prediction['score']:.2f}"
        draw.rectangle(draw.textbbox((x, y), text), fill="black")
        draw.text((x, y), text, fill="#00ff88")
    image.save(target)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="evaluation/config-v2.json")
    parser.add_argument("--output", default="evaluation/results/desktop-coco-smoke-v2")
    args = parser.parse_args()
    config_path = ROOT / args.config
    config = json.loads(config_path.read_text(encoding="utf-8"))
    if config.get("runtime_version") and config["runtime_version"] != mp.__version__:
        raise RuntimeError("The selected experiment requires MediaPipe " + config["runtime_version"])
    manifest_path = ROOT / "models/manifest.json"
    model_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    data_manifest = json.loads((ROOT / config["dataset_manifest"]).read_text(encoding="utf-8"))
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    for source, destination in ((manifest_path, "model_manifest_at_run.json"), (config_path, "config_at_run.json"),
                                (ROOT / config["dataset_manifest"], "dataset_manifest_at_run.json")):
        shutil.copyfile(source, output / destination)
    coco = COCO(str(ROOT / config["annotation_path"]))
    category_ids = {category["name"]: category["id"] for category in coco.cats.values()}
    frames = []
    for image in data_manifest["images"]:
        path = ROOT / "evaluation/data" / image["local_path"]
        if digest(path) != image["sha256"]:
            raise RuntimeError(f"Dataset hash mismatch: {path}")
        frames.append((image["id"], np.asarray(Image.open(path).convert("RGB"))))
    hardware = subprocess.check_output(["powershell.exe", "-NoProfile", "-Command",
        "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json"], text=True).strip()
    result = {"experiment_id": config["experiment_id"], "created_at_utc": datetime.now(timezone.utc).isoformat(),
              "config": config, "config_sha256": digest(config_path), "model_manifest_sha256": digest(manifest_path),
              "dataset_manifest_sha256": digest(ROOT / config["dataset_manifest"]),
              "input_snapshots": {"model_manifest": "model_manifest_at_run.json", "dataset_manifest": "dataset_manifest_at_run.json", "config": "config_at_run.json"},
              "environment": {"python": platform.python_version(), "platform": platform.platform(),
                              "mediapipe": mp.__version__, "numpy": np.__version__, "cpu": json.loads(hardware),
                              "process_logical_cpu_count": os.cpu_count(),
                              "delegate": "CPU; MediaPipe runtime default thread configuration; not overridden",
                              "caveat": "Desktop, mains power; concurrent build processes may affect timings. Phone performance not measured."},
              "models": {}, "timing_scope": "Synchronous detect() call, including native resize/normalization/inference/postprocessing; excludes disk decode and mp.Image creation. P95 exploratory with 80 samples per variant; P99 omitted."}
    for model in model_manifest["models"]:
        if model["id"] not in config["model_ids"]:
            continue
        model_path = ROOT / model["path"]
        if digest(model_path) != model["sha256"]:
            raise RuntimeError(f"Model hash mismatch: {model_path}")
        options = mp.tasks.vision.ObjectDetectorOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path), delegate=mp.tasks.BaseOptions.Delegate.CPU),
            running_mode=mp.tasks.vision.RunningMode.IMAGE, score_threshold=config["score_floor"], max_results=config["max_results"])
        startup = time.perf_counter_ns()
        detector = mp.tasks.vision.ObjectDetector.create_from_options(options)
        model_result = {"sha256": model["sha256"], "startup_ms": (time.perf_counter_ns() - startup) / 1e6, "variants": {}}
        model_output = output / model["id"]
        model_output.mkdir(exist_ok=True)
        for _ in range(config["warmup_calls"]):
            detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=frames[0][1]))
        for variant in config["variants"]:
            predictions, times = [], []
            annotation_dir = model_output / variant
            annotation_dir.mkdir(exist_ok=True)
            for image_id, original in frames:
                rgb = transform(original, variant)
                frame = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                first_detections = None
                for repetition in range(config["repeats"]):
                    start = time.perf_counter_ns()
                    detected = detector.detect(frame)
                    elapsed_ms = (time.perf_counter_ns() - start) / 1e6
                    times.append({"image_id": image_id, "repetition": repetition, "ms": elapsed_ms})
                    if first_detections is None:
                        first_detections = detected
                image_predictions = []
                for detection in first_detections.detections:
                    category = detection.categories[0]
                    if category.category_name not in category_ids:
                        raise RuntimeError(f"Unknown model class: {category.category_name}")
                    box = detection.bounding_box
                    image_predictions.append({"image_id": image_id, "category_id": category_ids[category.category_name],
                                              "bbox": [box.origin_x, box.origin_y, box.width, box.height], "score": category.score})
                predictions.extend(image_predictions)
                annotate(rgb, image_predictions, coco.cats, annotation_dir / f"{image_id:012d}.jpg", config["operating_score_threshold"])
            (model_output / f"{variant}_predictions.json").write_text(json.dumps(predictions, indent=2), encoding="utf-8")
            (model_output / f"{variant}_latencies.json").write_text(json.dumps(times, indent=2), encoding="utf-8")
            latencies = np.array([row["ms"] for row in times])
            metrics = score(coco, predictions, config["operating_score_threshold"])
            metrics["latency_ms"] = {"n": len(times), "mean": float(np.mean(latencies)), "p50": float(np.median(latencies)), "p95": float(np.percentile(latencies, 95)), "min": float(np.min(latencies)), "max": float(np.max(latencies))}
            model_result["variants"][variant] = metrics
            print(model["id"], variant, "AP50", round(metrics["subset_AP50"], 4), "P/R", round(metrics["precision_at_operating_threshold"] or 0, 4), round(metrics["recall_at_operating_threshold"] or 0, 4), "p50 ms", round(metrics["latency_ms"]["p50"], 2), flush=True)
        detector.close()
        result["models"][model["id"]] = model_result
    (output / "summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    report = ["# Desktop model smoke comparison", "", "Real inference on 16 seed-selected COCO val2017 CC BY 2.0 photographs. Not a phone benchmark or assistive-use evaluation.", "", "| Model | Variant | AP50 | AP50:95 | Precision @ .5 | Recall @ .5 | p50 ms | p95 ms |", "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for model_id, model_result in result["models"].items():
        for variant, values in model_result["variants"].items():
            report.append(f"| {model_id} | {variant} | {values['subset_AP50']:.3f} | {values['subset_AP50_95']:.3f} | {values['precision_at_operating_threshold'] or 0:.3f} | {values['recall_at_operating_threshold'] or 0:.3f} | {values['latency_ms']['p50']:.2f} | {values['latency_ms']['p95']:.2f} |")
    report += ["", "Official pycocotools COCOeval, IoU .50:.05:.95, maxDets=100, score floor .05 for ranking. Operating precision/recall use .5 confidence and .5 IoU; crowd ignores follow COCOeval. AP averages only categories present in this small sample, so it must not be compared to full COCO AP. Scores are model confidence, not calibrated probabilities.", "", "Timing includes detect() native preprocessing and postprocessing, excludes decode and Image creation. 5 warmups per model, 5 repetitions per image per variant = 80 calls/variant. Repeated same-image timings are correlated. P95 is exploratory and P99 is omitted. CPU default XNNPACK on Intel i7-14700HX, 20 cores/28 logical processors; thread count is runtime controlled. Concurrent compilation can add noise.", "", "dim_035 and motion_blur_15 are synthetic sensitivity probes of real photographs, not labeled low-light or motion-capture datasets. Boxes are unchanged because transforms preserve image geometry. No thresholds or model weights were tuned on these images.", "", "Reproduce from repository root: `evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark.py`. Full config, environment, raw detections, per-call latencies, per-class metrics and annotated frames are adjacent to this report. Dataset sources, licenses and SHA256 checks are in `evaluation/data/manifest.json`."]
    (output / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
