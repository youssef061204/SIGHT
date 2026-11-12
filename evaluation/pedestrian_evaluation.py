"""Actual road segmentation evaluation on credited photos and independent COCO-Stuff masks.

Uses a separate pinned LiteRT/OpenVINO environment; never sends photographs to a service.
Commands: prepare, reference, calibration, held-out. Timing is a separate explicit command.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import time
import urllib.request
import zipfile

import numpy as np
from PIL import Image
from pycocotools import mask as mask_api

from fetch_data import RangeFile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "evaluation/data-pedestrian"
DEFAULT_OUTPUT = ROOT / "evaluation/results/pedestrian"
PHOTO_MANIFEST = ROOT / "evaluation/data-v3/manifest.json"
SOURCE_URL = "https://s3.amazonaws.com/images.cocodataset.org/annotations/stuff_annotations_trainval2017.zip"
SOURCE_SHA = "5ff9b896d593d78a52114e62bf2d6f6b76f0f6eaf4226cc834cdc572b0adbf36"
MODEL_SHA = "8cbfa699891f82f6edae8493b8a75e648ddbbe8725165a86c6fc4c1f78e6a5b4"
MODEL_URL = "https://huggingface.co/AutowareFoundation/yabloc_pose_initializer/resolve/282a7fa0eb53ab5f8f0f179708ca57eacd25eaf1/saved_model/model_float32.tflite"
MODEL_PATH = ROOT / "perception/src/main/assets/road_segmentation_adas_0001.tflite"
WIDTH, HEIGHT = 896, 512


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def checked_model():
    if not MODEL_PATH.exists():
        MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        raise RuntimeError("Missing repaired bundled road model; run evaluation/convert_road_model.py")
    if sha(MODEL_PATH) != MODEL_SHA:
        raise RuntimeError("Road model hash changed")
    return MODEL_PATH


def load_inputs():
    manifest = json.loads((BASE / "manifest.json").read_text(encoding="utf-8"))
    for item in manifest["images"]:
        image = ROOT / item["imagePath"]
        target = ROOT / item["maskPath"]
        if sha(image) != item["imageSha256"] or sha(target) != item["maskSha256"]:
            raise RuntimeError(f"Dataset drift: {item['id']}")
    return manifest


def prepare():
    cache = ROOT / "evaluation/data/cache/stuff_val2017.json"
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(RangeFile(SOURCE_URL)) as archive:
            cache.write_bytes(archive.read("annotations/stuff_val2017.json"))
    if sha(cache) != SOURCE_SHA:
        raise RuntimeError("Official COCO-Stuff annotation bytes changed")
    source = json.loads(cache.read_text(encoding="utf-8"))
    photos = json.loads(PHOTO_MANIFEST.read_text(encoding="utf-8"))
    lookup = {i["id"]: i for i in photos["images"]}
    grouped = defaultdict(list)
    for annotation in source["annotations"]:
        if annotation["image_id"] in lookup:
            grouped[annotation["image_id"]].append(annotation)
    entries = []
    for identity, item in lookup.items():
        ground_truth = np.full((item["height"], item["width"]), 255, dtype=np.uint8)
        for annotation in grouped[identity]:
            encoded = annotation["segmentation"].copy()
            encoded["counts"] = encoded["counts"].encode("ascii")
            selected = mask_api.decode(encoded).astype(bool)
            category = annotation["category_id"]
            # Class0/unannotated is ignored; every other annotated category is a valid negative.
            if category > 0:
                ground_truth[selected] = 1 if category == 149 else 2 if category == 140 else 0
        mask_path = BASE / "masks" / (str(identity).zfill(12) + ".png")
        mask_path.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(ground_truth).save(mask_path)
        image_path = ROOT / "evaluation/data-v3" / item["local_path"]
        entries.append({"id": identity, "split": item["split"], "width": item["width"], "height": item["height"],
                        "imagePath": image_path.relative_to(ROOT).as_posix(), "imageSha256": sha(image_path),
                        "maskPath": mask_path.relative_to(ROOT).as_posix(), "maskSha256": sha(mask_path),
                        "attribution": item["attribution"], "roadPixels": int((ground_truth == 1).sum()),
                        "pavementPixels": int((ground_truth == 2).sum()), "validPixels": int((ground_truth != 255).sum())})
    manifest = {"schema": "sight.pedestrian.dataset", "images": entries,
                "count": len(entries), "sourceAnnotationUrl": SOURCE_URL,
                "sourceAnnotationMember": "annotations/stuff_val2017.json", "sourceAnnotationSha256": SOURCE_SHA,
                "photographManifest": PHOTO_MANIFEST.relative_to(ROOT).as_posix(), "photographManifestSha256": sha(PHOTO_MANIFEST),
                "maskClasses": {"0": "all other annotated classes", "1": "COCO-Stuff road (149)",
                                "2": "COCO-Stuff pavement (140), not inferred from curb", "255": "unannotated/ignored"},
                "splitRule": "Unchanged pre-existing 50 selection/50 calibration/80 held-out, photographer and close-dHash grouped",
                "licenses": {"photos": "Individual CC BY licenses; attribution retained per photograph",
                             "masks": "COCO-Stuff annotations, Holger Caesar/Jasper Uijlings/Vittorio Ferrari, CC BY 4.0",
                             "terms": "https://github.com/nightrome/cocostuff#licensing"},
                "limits": "Only 18 of these 180 photos contain annotated road. No curb/marking/crosswalk/signal-state ground truth. Not first-person video or field validation; model-training overlap cannot be independently ruled out."}
    write(BASE / "manifest.json", manifest)
    protocol = {"modelId": "intel-road-segmentation-adas-0001-fp32", "modelSha256": MODEL_SHA,
                "originalModelUrl": MODEL_URL, "modelCorrection": "Five bilinear resize flags now match Intel align_corners=true; trained weights unchanged",
                "pixelPosterior": .85, "componentMinimumCoverage": .002,
                "componentConnectivity": 4, "maximumComponents": 12,
                "voiceCoverageGrid": [.06, .10, .20, .30, .40], "voiceMinimumMeanPosterior": .85,
                "truthRoadPresence": "At least2percent of full original-image pixels annotated as road",
                "calibrationGate": {"minimumPrecision": .90, "maximumFalseEventsPerImage": .02,
                                    "minimumTruePositiveEvents": 3, "minimumRecall": .40},
                "choice": "Highest recall meeting gate; then fewer false positives, then larger coverage. If none pass, automatic surface speech disabled.",
                "primaryPixelMetric": "Strict road class1 at posterior>=.85 versus COCO-Stuff road149; model markings remain separate",
                "secondaryPixelMetric": "Road+marking(1or3) versus road149, reported explicitly and never treated as crosswalk",
                "unsupportedVoiceClasses": ["curb", "road_marking", "sidewalk", "crosswalk", "stairs", "entrance", "pedestrian_signal_state"],
                "safety": "Even an admitted road description does not establish crossing permission, clearance, passability, physical distance or body direction"}
    path = BASE / "protocol.json"
    if path.exists() and json.loads(path.read_text(encoding="utf-8")) != protocol:
        raise RuntimeError("Refusing to replace a different sealed evaluation protocol")
    write(path, protocol)
    print("Prepared", len(entries), "photos; ground truth and protocol sealed before inference")


def interpreter():
    from ai_edge_litert.interpreter import Interpreter
    instance = Interpreter(model_path=str(checked_model()), num_threads=2)
    instance.allocate_tensors()
    return instance


def image_tensor(path):
    image = Image.open(path).convert("RGB").resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)
    return np.asarray(image, dtype=np.float32)[..., ::-1].copy()[None]


def infer(instance, tensor):
    instance.set_tensor(instance.get_input_details()[0]["index"], tensor)
    start = time.perf_counter_ns()
    instance.invoke()
    elapsed = (time.perf_counter_ns() - start) / 1e6
    posterior = instance.get_tensor(instance.get_output_details()[0]["index"])[0]
    if posterior.shape != (HEIGHT, WIDTH, 4) or not np.isfinite(posterior).all():
        raise RuntimeError("Invalid native output")
    if np.max(np.abs(posterior.sum(axis=2) - 1)) > .03:
        raise RuntimeError("Output is not four-class softmax")
    return posterior, elapsed


def components(posterior, cutoff=.85):
    # OpenCV is not required: scipy is not required either; use Pillow floodfill equivalent via
    # the native OpenVINO CPU extension? Keep this exact algorithm explicit and independent.
    from collections import deque
    best = np.argmax(posterior, axis=2)
    scores = np.max(posterior, axis=2)
    best[scores < cutoff] = 0
    regions = []
    # A simple 4-neighbor flood fill over only foreground; no bounding-box unions across components.
    for category in (1, 2, 3):
        mask = best == category
        for y, x in np.argwhere(mask):
            if not mask[y, x]:
                continue
            queue = deque([(int(x), int(y))]); mask[y, x] = False
            count, total = 0, 0.0
            left, right, top, bottom = WIDTH, 0, HEIGHT, 0
            while queue:
                px, py = queue.popleft(); count += 1; total += float(scores[py, px])
                left, right = min(left, px), max(right, px + 1)
                top, bottom = min(top, py), max(bottom, py + 1)
                for nx, ny in ((px - 1, py), (px + 1, py), (px, py - 1), (px, py + 1)):
                    if 0 <= nx < WIDTH and 0 <= ny < HEIGHT and mask[ny, nx]:
                        mask[ny, nx] = False; queue.append((nx, ny))
            coverage = count / (WIDTH * HEIGHT)
            if coverage >= .002:
                regions.append({"category": category, "coverage": coverage, "meanPosterior": total / count,
                                "box": [left / WIDTH, top / HEIGHT, right / WIDTH, bottom / HEIGHT]})
    return sorted(regions, key=lambda r: -r["coverage"])[:12]


def binary_counts(predicted, truth, valid):
    return {"tp": int((predicted & truth & valid).sum()), "fp": int((predicted & ~truth & valid).sum()),
            "fn": int((~predicted & truth & valid).sum()), "tn": int((~predicted & ~truth & valid).sum())}


def statistics(rows):
    sums = {key: sum(row[key] for row in rows) for key in ("tp", "fp", "fn", "tn")}
    tp, fp, fn = sums["tp"], sums["fp"], sums["fn"]
    return {**sums, "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None, "iou": tp / (tp + fp + fn) if tp + fp + fn else None}


def evaluate_split(split, out):
    inputs = load_inputs()
    existing = out / (split + "-predictions.jsonl")
    if existing.exists():
        raise RuntimeError("Use a fresh output directory; refusing to overwrite saved predictions")
    runtime = interpreter()
    records = []
    for item in inputs["images"]:
        if item["split"] != split:
            continue
        posterior, elapsed = infer(runtime, image_tensor(ROOT / item["imagePath"]))
        winner = np.argmax(posterior, axis=2).astype(np.uint8)
        confidence = np.max(posterior, axis=2)
        accepted = winner.copy(); accepted[confidence < .85] = 0
        truth = np.asarray(Image.open(ROOT / item["maskPath"]), dtype=np.uint8)
        # Categorical nearest-neighbor reprojection to the original image; unknown GT stays ignored.
        predicted = np.asarray(Image.fromarray(accepted).resize((item["width"], item["height"]), Image.Resampling.NEAREST))
        regions = components(posterior)
        prediction_path = out / "masks" / (str(item["id"]).zfill(12) + ".png")
        prediction_path.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(predicted).save(prediction_path)
        record = {"id": item["id"], "split": split, "modelSha256": MODEL_SHA, "invokeMsUnderCurrentHostLoad": elapsed,
                  "regions": regions, "maskPath": prediction_path.relative_to(out).as_posix(), "maskSha256": sha(prediction_path),
                  "roadTruthPresent": item["roadPixels"] / (item["width"] * item["height"]) >= .02,
                  "roadOnly": binary_counts(predicted == 1, truth == 1, truth != 255),
                  "roadOrMarking": binary_counts(np.isin(predicted, [1, 3]), truth == 1, truth != 255),
                  "pavementPredictedAsRoadPixels": int(((predicted == 1) & (truth == 2)).sum()),
                  "pavementTruthPixels": item["pavementPixels"]}
        records.append(record)
        print(split, item["id"], "roadGT", record["roadTruthPresent"], "regions", len(regions), flush=True)
    existing.parent.mkdir(parents=True, exist_ok=True)
    existing.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8", newline="\n")
    return records


def event_statistics(records, coverage):
    rows = []
    for row in records:
        predicted = any(r["category"] == 1 and r["meanPosterior"] >= .85 and r["coverage"] >= coverage for r in row["regions"])
        truth = row["roadTruthPresent"]
        rows.append({"tp": int(predicted and truth), "fp": int(predicted and not truth),
                     "fn": int(not predicted and truth), "tn": int(not predicted and not truth)})
    stats = statistics(rows)
    return {"coverage": coverage, **stats, "falseEventsPerImage": stats["fp"] / len(rows)}


def calibration(out):
    records = evaluate_split("calibration", out)
    protocol = json.loads((BASE / "protocol.json").read_text(encoding="utf-8"))
    candidates = [event_statistics(records, coverage) for coverage in protocol["voiceCoverageGrid"]]
    gate = protocol["calibrationGate"]
    passed = [c for c in candidates if (c["precision"] or 0) >= gate["minimumPrecision"] and
              c["falseEventsPerImage"] <= gate["maximumFalseEventsPerImage"] and
              c["tp"] >= gate["minimumTruePositiveEvents"] and (c["recall"] or 0) >= gate["minimumRecall"]]
    chosen = max(passed, key=lambda c: (c["recall"], -c["fp"], c["coverage"])) if passed else None
    write(out / "calibration.json", {"protocolSha256": sha(BASE / "protocol.json"), "candidates": candidates,
                                     "automaticRoadVoiceEnabled": chosen is not None, "chosen": chosen,
                                     "roadPixelMetrics": statistics([r["roadOnly"] for r in records]),
                                     "roadOrMarkingPixelMetrics": statistics([r["roadOrMarking"] for r in records])})
    print("CALIBRATION", chosen)


def held_out(out):
    sealed = json.loads((out / "calibration.json").read_text(encoding="utf-8"))
    if sealed["protocolSha256"] != sha(BASE / "protocol.json"):
        raise RuntimeError("Protocol changed after calibration")
    records = evaluate_split("held_out", out)
    chosen = sealed["chosen"]
    summary = {"n": len(records), "groundTruthRoadPresent": sum(r["roadTruthPresent"] for r in records),
               "roadOnlyPixels": statistics([r["roadOnly"] for r in records]),
               "roadOrMarkingPixels": statistics([r["roadOrMarking"] for r in records]),
               "automaticRoadVoiceEnabledByCalibration": sealed["automaticRoadVoiceEnabled"],
               "voiceEventMetricsAtCalibratedGate": event_statistics(records, chosen["coverage"]) if chosen else None,
               "disabledDefaultGateDiagnosticOnly": event_statistics(records, .06) if not chosen else None,
               "pavementPredictedAsRoadPixels": sum(r["pavementPredictedAsRoadPixels"] for r in records),
               "pavementTruthPixels": sum(r["pavementTruthPixels"] for r in records),
               "modelSha256": MODEL_SHA, "datasetManifestSha256": sha(BASE / "manifest.json"),
               "protocolSha256": sha(BASE / "protocol.json"),
               "notMeasured": ["curb accuracy", "marking accuracy", "sidewalk segmentation", "crosswalk recognition",
                               "signal state", "field hazard recall", "real-video stability", "physical depth", "safe crossing"]}
    write(out / "held-out.json", summary)
    print(json.dumps(summary, indent=2))


def reference(out):
    import openvino as ov
    paths = ROOT / ".tools/pedestrian-model"
    paths.mkdir(parents=True, exist_ok=True)
    if (out / "reference-parity.json").exists():
        raise RuntimeError("Use a fresh output directory; refusing to overwrite reference parity evidence")
    prefix = "https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/models_bin/1/road-segmentation-adas-0001/FP32/road-segmentation-adas-0001."
    expected = {"xml": "228e4ef841a6cea468fc8cdf2c17b2cb27e7acc153ea81cf18773094043eadf9",
                "bin": "e708aff5c4cd9813b575c72011f2cef9a4f5adb7aa382d81dc7adbb395dc9d8c"}
    for suffix, digest in expected.items():
        path = paths / ("road." + suffix)
        if not path.exists(): urllib.request.urlretrieve(prefix + suffix, path)
        if sha(path) != digest: raise RuntimeError("Intel reference bytes changed: " + suffix)
    core = ov.Core()
    model = core.compile_model(core.read_model(paths / "road.xml", paths / "road.bin"), "CPU",
                               {"INFERENCE_NUM_THREADS": 2, "INFERENCE_PRECISION_HINT": "f32"})
    runtime = interpreter()
    inputs = load_inputs()
    # Reference-only inputs come from selection; include every road-positive selection photo
    # and the aspect-ratio extremes, rather than checking only easy all-background cases.
    selection = sorted((i for i in inputs["images"] if i["split"] == "selection"), key=lambda i: i["width"] / i["height"])
    chosen = {i["id"]: i for i in selection if i["roadPixels"] > 0}
    chosen.update({i["id"]: i for i in (selection[0], selection[len(selection) // 2], selection[-1])})
    cases = []
    for item in sorted(chosen.values(), key=lambda i: i["id"]):
        tensor = image_tensor(ROOT / item["imagePath"])
        actual, _ = infer(runtime, tensor)
        expected = model([tensor.transpose(0,3,1,2)])[0][0].transpose(1,2,0)
        error = np.abs(actual - expected)
        cases.append({"imageId": item["id"], "maximumPosteriorError": float(error.max()),
                      "meanPosteriorError": float(error.mean()),
                      "argmaxAgreement": float((actual.argmax(2) == expected.argmax(2)).mean())})
    write(out / "reference-parity.json", {"cases": cases,
          "modelSha256": MODEL_SHA, "referenceXmlSha256": sha(paths / "road.xml"), "referenceBinSha256": sha(paths / "road.bin"),
          "scope": "Same raw BGR float tensor into Intel official OpenVINO reference and redistributed TFLite conversion; not Android resize equivalence"})
    if min(c["argmaxAgreement"] for c in cases) < .99:
        raise RuntimeError("Conversion parity gate failed; do not ship this conversion")
    print(json.dumps(cases, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["prepare", "reference", "calibration", "held-out"])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    {"prepare": lambda: prepare(), "reference": lambda: reference(out),
     "calibration": lambda: calibration(out), "held-out": lambda: held_out(out)}[args.stage]()


if __name__ == "__main__":
    main()
