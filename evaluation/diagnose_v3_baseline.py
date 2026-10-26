"""Inspect the legacy16 without using new held-out results for tuning.

Experiments isolate confidence, small-object difficulty, output caps, RGB order,
aspect handling, wrong orientation, quantization and post-hoc suppression effects.
They are diagnostic sensitivity probes, not a new independent test set.
"""
from __future__ import annotations

from collections import Counter
import contextlib
import io
import json
from pathlib import Path
import urllib.request

import cv2
import mediapipe as mp
import numpy as np
from PIL import Image, ImageDraw
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval

from v3_detection import cap_predictions, digest, metrics

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/v3-baseline-diagnostics"


def iou(first, second):
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    intersection = max(0, min(ax + aw, bx + bw) - max(ax, bx)) * max(0, min(ay + ah, by + bh) - max(ay, by))
    union = aw * ah + bw * bh - intersection
    return intersection / union if union else 0


def nms(predictions, threshold):
    retained = []
    for prediction in sorted(predictions, key=lambda row: -row["score"]):
        if not any(other["image_id"] == prediction["image_id"] and other["category_id"] == prediction["category_id"] and iou(other["bbox"], prediction["bbox"]) > threshold for other in retained):
            retained.append(prediction)
    return retained


def false_positive_overlap(coco, predictions):
    with contextlib.redirect_stdout(io.StringIO()):
        detections = coco.loadRes(cap_predictions(predictions, .5, 20))
        evaluator = COCOeval(coco, detections, "bbox")
        evaluator.evaluate()
    rows = []
    for item in evaluator.evalImgs:
        if item is None or item["aRng"] != evaluator.params.areaRng[0]:
            continue
        for index, dt_id in enumerate(item["dtIds"]):
            if item["dtIgnore"][0, index] or item["dtMatches"][0, index]:
                continue
            prediction = detections.anns[dt_id]
            ground_truth = [annotation for annotation in coco.imgToAnns[prediction["image_id"]] if annotation["category_id"] == prediction["category_id"] and not annotation["iscrowd"]]
            overlap = max((iou(prediction["bbox"], annotation["bbox"]) for annotation in ground_truth), default=0)
            rows.append({"image_id": prediction["image_id"], "category": coco.cats[prediction["category_id"]]["name"], "score": prediction["score"],
                         "bbox": prediction["bbox"], "best_same_class_annotation_iou": overlap,
                         "interpretation": "same-class match competition/duplicate" if overlap >= .5 else "localization mismatch at IoU.5" if overlap >= .1 else "no appreciable same-class annotation overlap"})
    return {"rows": rows, "counts": dict(Counter(row["interpretation"] for row in rows)),
            "scope": "COCO unmatched prediction diagnosis, not independent human annotation adjudication; some counted false positives may depict a real object with insufficient box overlap"}


def preprocess(rgb, mode):
    height, width = rgb.shape[:2]
    if mode == "bgr_wrong_order":
        return np.ascontiguousarray(rgb[:, :, ::-1]), lambda box: box
    if mode == "wrong_90deg_then_box_reproject":
        return np.ascontiguousarray(np.rot90(rgb, k=-1)), lambda box: [box[1], height - box[0] - box[2], box[3], box[2]]
    if mode == "manual_stretch_320":
        return cv2.resize(rgb, (320, 320), interpolation=cv2.INTER_LINEAR), lambda box: [box[0] * width / 320, box[1] * height / 320, box[2] * width / 320, box[3] * height / 320]
    if mode.startswith("manual_letterbox"):
        scale = min(320 / width, 320 / height)
        scaled_width, scaled_height = round(width * scale), round(height * scale)
        x_pad, y_pad = (320 - scaled_width) // 2, (320 - scaled_height) // 2
        canvas = np.full((320, 320, 3), 114 if mode.endswith("114") else 0, dtype=np.uint8)
        canvas[y_pad:y_pad + scaled_height, x_pad:x_pad + scaled_width] = cv2.resize(rgb, (scaled_width, scaled_height), interpolation=cv2.INTER_LINEAR)
        return canvas, lambda box: [(box[0] - x_pad) * width / scaled_width, (box[1] - y_pad) * height / scaled_height, box[2] * width / scaled_width, box[3] * height / scaled_height]
    return rgb, lambda box: box


def visual_examples(coco, predictions, manifest):
    with contextlib.redirect_stdout(io.StringIO()):
        detections = coco.loadRes(cap_predictions(predictions, .5, 20))
        evaluator = COCOeval(coco, detections, "bbox")
        evaluator.evaluate()
    by_image = {}
    for item in evaluator.evalImgs:
        if item is None or item["aRng"] != evaluator.params.areaRng[0]:
            continue
        image = by_image.setdefault(int(item["image_id"]), {"tp": [], "fp": [], "fn": []})
        for index, dt_id in enumerate(item["dtIds"]):
            if item["dtIgnore"][0, index]:
                continue
            image["tp" if item["dtMatches"][0, index] else "fp"].append(detections.anns[dt_id])
        for index, gt_id in enumerate(item["gtIds"]):
            if not item["gtIgnore"][index] and not item["gtMatches"][0, index]:
                image["fn"].append(coco.anns[gt_id])
    credits = {row["image_id"]: row for row in json.loads((ROOT / "evaluation/data/attribution.json").read_text())["images"]}
    resolved = {image_id: row for image_id, row in by_image.items() if credits[image_id]["status"] == "resolved"}
    selected = []
    for key in ("tp", "fp", "fn"):
        image_id = max(resolved, key=lambda image_id: len(resolved[image_id][key]))
        if image_id not in selected:
            selected.append(image_id)
    entries = {image["id"]: image for image in manifest["images"]}
    examples = []
    for image_id in selected:
        image = Image.open(ROOT / "evaluation/data" / entries[image_id]["local_path"]).convert("RGB")
        draw = ImageDraw.Draw(image)
        row = by_image[image_id]
        for key, color in (("fn", "#ffbd44"), ("tp", "#00dd88"), ("fp", "#ff5555")):
            for box in row[key]:
                x, y, width, height = box["bbox"]
                draw.rectangle((x, y, x + width, y + height), outline=color, width=2)
                label = key.upper() + " " + coco.cats[box["category_id"]]["name"]
                draw.text((max(0, x), max(0, y)), label, fill=color, stroke_width=1, stroke_fill="black")
        path = OUT / f"example_{image_id:012d}.jpg"
        image.save(path)
        examples.append({"image_id": image_id, "file": path.name, "counts": {key: len(values) for key, values in row.items()},
                         "attribution": credits[image_id], "false_negative_categories": dict(Counter(coco.cats[annotation["category_id"]]["name"] for annotation in row["fn"]))})
    return examples


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / "evaluation/data/manifest.json").read_text())
    with contextlib.redirect_stdout(io.StringIO()):
        coco = COCO(str(ROOT / "evaluation/data/instances_smoke.json"))
    path = ROOT / "evaluation/results/v3-baseline-reproduction/efficientdet_lite0_int8/clean_predictions.json"
    baseline = json.loads(path.read_text())
    result = {"scope": "legacy16diagnostic only; confidence sweeps never tune new held-out80", "reproduced_predictions_sha256": digest(path),
              "threshold_sweep": [{"threshold": threshold, "metrics": metrics(coco, baseline, threshold, 20)} for threshold in (.1, .2, .25, .3, .35, .4, .5, .6)],
              "baseline": metrics(coco, baseline, .5, 20), "image_count": len(coco.imgs),
              "maximum_count_per_image_above_05": max(Counter(prediction["image_id"] for prediction in baseline if prediction["score"] >= .5).values()),
              "invalid_nonpositive_boxes": sum(prediction["bbox"][2] <= 0 or prediction["bbox"][3] <= 0 for prediction in baseline),
              "posthoc_extra_nms": {}, "preprocessing_variants": {},
              "unsupported_categories": ["door", "stairs", "curb", "crosswalk", "elevator", "keys"],
              "unsupported_note": "Absent from the80model classes; not annotated as negatives or positives by COCO. Cannot estimate recall for unsupported categories here."}
    for threshold in (.3, .5, .7):
        result["posthoc_extra_nms"][str(threshold)] = metrics(coco, nms(baseline, threshold), .5, 20)
    clipped = []
    for prediction in baseline:
        width, height = coco.imgs[prediction["image_id"]]["width"], coco.imgs[prediction["image_id"]]["height"]
        x, y, bw, bh = prediction["bbox"]
        x1, y1, x2, y2 = max(0, x), max(0, y), min(width, x + bw), min(height, y + bh)
        if x2 > x1 and y2 > y1:
            clipped.append({**prediction, "bbox": [x1, y1, x2 - x1, y2 - y1]})
    result["clip_to_image_like_android"] = metrics(coco, clipped, .5, 20)
    categories = {category["name"]: category["id"] for category in coco.cats.values()}
    model_path = ROOT / "models/efficientdet_lite0_int8.tflite"
    options = mp.tasks.vision.ObjectDetectorOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path)), score_threshold=.05, max_results=100)
    with mp.tasks.vision.ObjectDetector.create_from_options(options) as detector:
        for variant in ("bgr_wrong_order", "manual_stretch_320", "manual_letterbox_0", "manual_letterbox_114", "wrong_90deg_then_box_reproject"):
            predictions = []
            for image in manifest["images"]:
                rgb = np.asarray(Image.open(ROOT / "evaluation/data" / image["local_path"]).convert("RGB"))
                transformed, project = preprocess(rgb, variant)
                detected = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=transformed))
                for detection in detected.detections:
                    box = detection.bounding_box
                    category = detection.categories[0]
                    predictions.append({"image_id": image["id"], "category_id": categories[category.category_name], "score": category.score,
                                        "bbox": project([box.origin_x, box.origin_y, box.width, box.height])})
            result["preprocessing_variants"][variant] = metrics(coco, predictions, .5, 20)
            (OUT / f"{variant}_predictions.json").write_text(json.dumps(predictions, indent=2))
            print(variant, result["preprocessing_variants"][variant]["subset_AP50"], flush=True)
    # Official float32 artifact, same Lite0 family; exact export provenance is
    # recorded. This diagnostic does not alter production model selection.
    float_path = ROOT / ".tools/efficientdet_lite0_float32.tflite"
    float_url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite"
    if not float_path.exists():
        urllib.request.urlretrieve(float_url, float_path)
    if digest(float_path) != "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58":
        raise RuntimeError("Official float32 diagnostic model hash changed")
    predictions = []
    options = mp.tasks.vision.ObjectDetectorOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(float_path)), score_threshold=.05, max_results=100)
    with mp.tasks.vision.ObjectDetector.create_from_options(options) as detector:
        for image in manifest["images"]:
            rgb = np.asarray(Image.open(ROOT / "evaluation/data" / image["local_path"]).convert("RGB"))
            detected = detector.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
            for detection in detected.detections:
                box = detection.bounding_box
                category = detection.categories[0]
                predictions.append({"image_id": image["id"], "category_id": categories[category.category_name], "score": category.score,
                                    "bbox": [box.origin_x, box.origin_y, box.width, box.height]})
    result["float32_probe"] = {"url": float_url, "sha256": digest(float_path), "bytes": float_path.stat().st_size, "metrics": metrics(coco, predictions, .5, 20)}
    (OUT / "float32_predictions.json").write_text(json.dumps(predictions, indent=2))
    result["examples"] = visual_examples(coco, baseline, manifest)
    result["false_positive_overlap_diagnosis"] = false_positive_overlap(coco, baseline)
    result["limitations"] = "These controlled RGB stills cannot validate Android YUV/RGBA conversion, sensor timestamps, rotation/mirror implementation, lifecycle, physical camera domain shift or real video tracking. App pixel tests and emulator camera tests are separate. Extra NMS cannot recover proposals already suppressed inside model. Float vs int8 artifacts can differ beyond quantization; not isolated quantizer causal inference."
    (OUT / "summary.json").write_text(json.dumps(result, indent=2))
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
