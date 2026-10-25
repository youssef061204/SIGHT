"""Balanced follow-up timing. Real inference; CPU affinity explicitly bounded.

Separates the accuracy sweep's sensitivity probes from latency comparisons.
Both model instances exist in one process and order alternates per paired call.
"""
from __future__ import annotations

import ctypes
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import random
import time

ROOT = Path(__file__).resolve().parent.parent
if platform.system() == "Windows":
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    kernel.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    if not kernel.SetProcessAffinityMask(kernel.GetCurrentProcess(), 0xF):
        raise ctypes.WinError(ctypes.get_last_error())

import mediapipe as mp
import numpy as np
from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="evaluation/results/paired-clean-latency-v2")
    parser.add_argument("--id", default="paired-clean-latency-v2")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "models/manifest.json").read_text())
    dataset = json.loads((ROOT / "evaluation/data/manifest.json").read_text())
    for entry in dataset["images"]:
        path = ROOT / "evaluation/data" / entry["local_path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise RuntimeError("Dataset image hash mismatch: " + entry["local_path"])
    config = {"id": args.id, "seed": 20261023, "warmup": 5, "repeats": 5,
              "cpu_affinity_mask": "0xF (Windows logical CPUs 0-3)", "runtime_threads": "MediaPipe runtime default; not overridden",
              "score_floor": .05, "max_results": 100, "input_count": len(dataset["images"]),
              "scope": "detect() includes native input preprocessing, model and postprocessing; file decode and mp.Image creation excluded"}
    frames = [(image["id"], mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(Image.open(ROOT / "evaluation/data" / image["local_path"]).convert("RGB")))) for image in dataset["images"]]
    detectors = {}
    for model in manifest["models"]:
        path = ROOT / model["path"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != model["sha256"]:
            raise RuntimeError("Model hash mismatch")
        options = mp.tasks.vision.ObjectDetectorOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(path), delegate=mp.tasks.BaseOptions.Delegate.CPU),
            running_mode=mp.tasks.vision.RunningMode.IMAGE, max_results=config["max_results"], score_threshold=config["score_floor"])
        detectors[model["id"]] = mp.tasks.vision.ObjectDetector.create_from_options(options)
        for _ in range(config["warmup"]):
            detectors[model["id"]].detect(frames[0][1])
    trials = [(repeat, image_id, frame) for repeat in range(config["repeats"]) for image_id, frame in frames]
    random.Random(config["seed"]).shuffle(trials)
    samples = []
    for index, (repeat, image_id, frame) in enumerate(trials):
        order = list(detectors)
        if index % 2:
            order.reverse()
        for model_id in order:
            start = time.perf_counter_ns()
            detectors[model_id].detect(frame)
            samples.append({"model_id": model_id, "trial": index, "image_id": image_id, "repeat": repeat, "ms": (time.perf_counter_ns() - start) / 1e6})
    for detector in detectors.values():
        detector.close()
    metrics = {}
    for model_id in detectors:
        latencies = np.array([sample["ms"] for sample in samples if sample["model_id"] == model_id])
        metrics[model_id] = {"n": len(latencies), "mean_ms": float(np.mean(latencies)), "p50_ms": float(np.median(latencies)), "p95_ms": float(np.percentile(latencies, 95)), "min_ms": float(np.min(latencies)), "max_ms": float(np.max(latencies))}
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps({"created_at_utc": datetime.now(timezone.utc).isoformat(), "config": config, "platform": platform.platform(), "cpu": "Intel Core i7-14700HX (host 20 cores,28 logical processors)", "mediapipe": mp.__version__, "dataset_manifest_sha256": hashlib.sha256((ROOT / "evaluation/data/manifest.json").read_bytes()).hexdigest(), "model_manifest_sha256": hashlib.sha256((ROOT / "models/manifest.json").read_bytes()).hexdigest(), "models": metrics, "samples": samples}, indent=2))
    lines = ["# Paired desktop CPU latency", "", f"Real inference; 16 original COCO photographs, five repetitions each (80 calls per model). Five unmeasured warmups per model. Shuffled image/repeat pairs; model order alternates within pairs. Windows CPU affinity fixed to logical CPUs 0-3 (`0xF`) to reduce scheduling variation. MediaPipe runtime thread count is left at its default. Intel Core i7-14700HX, Windows, Python 3.12, MediaPipe {mp.__version__}.", "", "| Model | n | p50 ms | p95 ms |", "| --- | ---: | ---: | ---: |"]
    for model_id, values in metrics.items():
        lines.append(f"| {model_id} | {values['n']} | {values['p50_ms']:.2f} | {values['p95_ms']:.2f} |")
    lines += ["", "P95 is exploratory (only 80 correlated calls/model); P99 omitted. Detect-call scope includes native resize/inference/postprocessing and excludes disk decode and mp.Image creation. Host load, caching and power policy still matter. This does not measure phone latency, capture FPS, energy, end-to-end speech or memory usage. Accuracy is in `../desktop-coco-smoke-v2/report.md`; compare latency only within this explicitly bounded run.", "", "Reproduce: `evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py`. Exact config and all raw latency samples are in `summary.json`."]
    (output / "report.md").write_text("\n".join(lines) + "\n")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
