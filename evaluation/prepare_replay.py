"""Build explicitly constructed temporal replay from genuine saved model detections.

This tests policy on repeated photographs. It has NO temporal identity ground truth.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
data = json.loads((ROOT / "data/instances_smoke.json").read_text())
images = {image["id"]: image for image in data["images"]}
categories = {category["id"]: category["name"] for category in data["categories"]}
predictions = json.loads((ROOT / "results/desktop-coco-smoke-v1/efficientdet_lite0_int8/clean_predictions.json").read_text())
out = ROOT / "replay"
out.mkdir(exist_ok=True)
lines = ["# CONSTRUCTED REPLAY: genuine model detections repeated on static real COCO photos. Not live; no tracking ground-truth claims.",
         "# frameId\tcapturedAtMs\tnowMs\tsourceId\tcategory\tconfidence\tleft\ttop\tright\tbottom", "COMMAND\t0\texplore"]
frame_id = 0


def frame(image_id, captured, now):
    global frame_id
    frame_id += 1
    image = images[image_id]
    accepted = [prediction for prediction in predictions if prediction["image_id"] == image_id and prediction["score"] >= .5]
    for prediction in accepted:
        x, y, width, height = prediction["bbox"]
        left, top = max(0, x / image["width"]), max(0, y / image["height"])
        right, bottom = min(1, (x + width) / image["width"]), min(1, (y + height) / image["height"])
        lines.append(f"{frame_id}\t{captured}\t{now}\treplay-coco-{image_id}\t{categories[prediction['category_id']]}\t{prediction['score']:.8f}\t{left:.8f}\t{top:.8f}\t{right:.8f}\t{bottom:.8f}")
    if not accepted:
        lines.append(f"{frame_id}\t{captured}\t{now}\treplay-coco-{image_id}\t")


for index in range(8):
    captured = 1000 + index * 400
    frame(170099, captured, captured + 100)
# Empty frames are injected test conditions, NOT detections from another picture.
for captured in (5000, 5800):
    frame_id += 1
    lines.append(f"{frame_id}\t{captured}\t{captured+100}\treplay-coco-170099\t")
frame(170099, 2000, 7200)  # Intentionally stale evidence, not new inference.
lines.append("INPUT\t7600\tfalse")
lines.append("INPUT\t8000\ttrue")
lines.append("COMMAND\t8100\tfind a couch")
for captured in (8200, 8600, 9000, 9400):
    frame(277197, captured, captured + 100)
lines.append("COMMAND\t9800\tfind a chair")
for captured in (10000, 10400, 10800, 11200):
    frame(277197, captured, captured + 100)
lines.append("TICK\t75000")
(out / "real-detection-repeat.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
(out / "manifest.json").write_text(json.dumps({"id": "constructed-real-detection-repeat-v1", "source": "Actual EfficientDet Lite0 int8 clean predictions; COCO image IDs 170099 and 277197", "model_predictions": "evaluation/results/desktop-coco-smoke-v1/efficientdet_lite0_int8/clean_predictions.json", "frames": frame_id,
    "provenance": "Repeated photographs with constructed timestamps, empty frame injection, stale evidence injection, source outage and commands. Independent photographs do not establish continuous scene identity.",
    "valid_metrics": ["object mentions vs naive per-detection narration baseline", "stale result rejection", "input outage state", "goal confirmation and timeout"],
    "invalid_claims": ["IDF1", "identity switch rate on real video", "tracking accuracy", "real-time camera or speech performance", "user task success rate"]}, indent=2), encoding="utf-8")
print(f"Prepared constructed replay with {frame_id} frame events")
