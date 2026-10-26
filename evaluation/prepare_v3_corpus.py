"""Build a fixed, credited COCO corpus before model selection or threshold tuning.

Only original CC BY 2.0 photos whose current Flickr attribution is recoverable
and still CC BY are accepted. All photos from one creator share a split. Close
dHash matches are grouped too. No inference is involved in selection/splitting.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

from PIL import Image

from fetch_data import ANNOTATION_SHA256, URL, RangeFile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "evaluation/data-v3"
SEED = "sight-coco-v3-author-grouped-20261025"
ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def short_url(image):
    number = int(re.search(r"/(\d+)_", image["flickr_url"]).group(1))
    encoded = ""
    while number:
        encoded = ALPHABET[number % 58] + encoded
        number //= 58
    return "https://flic.kr/p/" + encoded


def resolve(image):
    url = "https://www.flickr.com/services/oembed/?" + urllib.parse.urlencode(
        {"url": short_url(image), "format": "json"})
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "SIGHT-local-evaluation/3.0"})
        with urllib.request.urlopen(request, timeout=20) as response:
            info = json.load(response)
        if info.get("license") not in ("CC BY 2.0", "CC BY 4.0"):
            raise ValueError("Current Flickr license is not accepted CC BY: " + str(info.get("license")))
        return {"image_id": image["id"], "status": "resolved", "author_name": info["author_name"],
                "author_url": info["author_url"].replace("http://", "https://"),
                "title": info.get("title", "Untitled"), "source_url": info["web_page"],
                "license": info["license"], "license_url": info["license_url"], "oembed_url": url}
    except Exception as error:
        return {"image_id": image["id"], "status": "excluded", "error": str(error), "oembed_url": url}


def dhash(path):
    image = Image.open(path).convert("L").resize((9, 8))
    pixels = list(image.getdata())
    value = 0
    for row in range(8):
        for col in range(8):
            value = (value << 1) | int(pixels[row * 9 + col] > pixels[row * 9 + col + 1])
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=180)
    args = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    (BASE / "images").mkdir(exist_ok=True)
    sealed_path = BASE / "manifest.json"
    if sealed_path.exists():
        sealed = json.loads(sealed_path.read_text())
        if sealed["count"] != args.count:
            raise RuntimeError("Existing corpus is sealed; create a new version to change its count")
        for image in sealed["images"]:
            path = BASE / image["local_path"]
            if not path.exists():
                urllib.request.urlretrieve(image["download_url"], path)
            if sha(path) != image["sha256"]:
                raise RuntimeError("Sealed photo hash mismatch: " + str(image["id"]))
        print("Verified sealed corpus", sealed["split_counts"], flush=True)
        return
    annotation_path = ROOT / "evaluation/data/cache/instances_val2017.json"
    if not annotation_path.exists():
        annotation_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(RangeFile(URL)) as archive:
            annotation_path.write_bytes(archive.read("annotations/instances_val2017.json"))
    if sha(annotation_path) != ANNOTATION_SHA256:
        raise RuntimeError("Original COCO annotation hash mismatch")
    data = json.loads(annotation_path.read_text())
    old = json.loads((ROOT / "evaluation/data/manifest.json").read_text())
    old_ids = {image["id"] for image in old["images"]}
    old_attr = json.loads((ROOT / "evaluation/data/attribution.json").read_text())
    old_creators = {row["author_url"].replace("http://", "https://") for row in old_attr["images"] if row["status"] == "resolved"}
    candidates = [image for image in data["images"] if image["license"] == 4 and image["id"] not in old_ids]
    candidates.sort(key=lambda image: hashlib.sha256(f"{SEED}:{image['id']}".encode()).hexdigest())
    cache_path = BASE / "attribution-at-selection.json"
    cached = json.loads(cache_path.read_text()) if cache_path.exists() else {"resolved_at_utc": datetime.now(timezone.utc).isoformat(), "rows": []}
    by_id = {row["image_id"]: row for row in cached["rows"]}
    selected = []
    excluded = []
    old_hashes = [dhash(ROOT / "evaluation/data" / image["local_path"]) for image in old["images"]]
    # Batches preserve seed order despite network completion order.
    for start in range(0, len(candidates), 32):
        batch = candidates[start:start + 32]
        missing = [image for image in batch if image["id"] not in by_id]
        with ThreadPoolExecutor(max_workers=6) as pool:
            for row in pool.map(resolve, missing):
                by_id[row["image_id"]] = row
        cached["rows"] = list(by_id.values())
        cache_path.write_text(json.dumps(cached, indent=2), encoding="utf-8")
        for image in batch:
            credit = by_id[image["id"]]
            if credit["status"] != "resolved" or credit.get("author_url") in old_creators:
                excluded.append({"id": image["id"], "reason": credit.get("error", "creator shared with historic smoke set")})
                continue
            path = BASE / "images" / image["file_name"]
            url = "https://s3.amazonaws.com/images.cocodataset.org/val2017/" + image["file_name"]
            if not path.exists():
                urllib.request.urlretrieve(url, path)
            fingerprint = dhash(path)
            if any((fingerprint ^ old_hash).bit_count() <= 6 for old_hash in old_hashes):
                excluded.append({"id": image["id"], "reason": "near-duplicate dHash with historic smoke photo; do not use"})
                continue
            selected.append({**image, "download_url": url, "sha256": sha(path),
                             "local_path": "images/" + path.name, "dhash64": f"{fingerprint:016x}", "attribution": credit})
            print("accepted", len(selected), image["id"], credit["author_name"], flush=True)
            if len(selected) == args.count:
                break
        if len(selected) == args.count:
            break
    if len(selected) != args.count:
        raise RuntimeError("Insufficient resolved, still-CC-BY photographs")
    # Union connected creator groups and near duplicate photograph groups.
    parents = list(range(len(selected)))
    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i
    def union(a, b):
        parents[find(b)] = find(a)
    for index, image in enumerate(selected):
        for other_index, other in enumerate(selected[:index]):
            if (image["attribution"]["author_url"] == other["attribution"]["author_url"] or
                    (int(image["dhash64"], 16) ^ int(other["dhash64"], 16)).bit_count() <= 6):
                union(index, other_index)
    groups = {}
    for index, image in enumerate(selected):
        groups.setdefault(find(index), []).append(image)
    # Fixed 50/50/80 targets; whole creator/duplicate groups may shift counts.
    targets = {"selection": round(args.count * 5 / 18), "calibration": round(args.count * 5 / 18), "held_out": args.count - 2 * round(args.count * 5 / 18)}
    counts = {split: 0 for split in targets}
    ordered_groups = sorted(groups.values(), key=lambda group: (-len(group), hashlib.sha256(f"{SEED}:group:{min(i['id'] for i in group)}".encode()).hexdigest()))
    for group in ordered_groups:
        split = max(targets, key=lambda name: targets[name] - counts[name])
        counts[split] += len(group)
        for image in group:
            image["split"] = split
            image["scene_group"] = "creator-neardup-" + str(min(i["id"] for i in group))
    for split in targets:
        ids = {image["id"] for image in selected if image["split"] == split}
        subset = {"info": data["info"], "licenses": data["licenses"], "categories": data["categories"],
                  "images": [{key: value for key, value in image.items() if key in ("id", "file_name", "width", "height", "license", "flickr_url", "coco_url", "date_captured")} for image in selected if image["id"] in ids],
                  "annotations": [annotation for annotation in data["annotations"] if annotation["image_id"] in ids]}
        (BASE / f"instances_{split}.json").write_text(json.dumps(subset, indent=2), encoding="utf-8")
    manifest = {"id": SEED, "created_at_utc": datetime.now(timezone.utc).isoformat(), "count": len(selected), "split_counts": counts,
                "selection": "Seeded SHA256 order of COCO val2017 license4 images; original16 and their resolved creators excluded; current Flickr CC BY attribution required; no inference used",
                "split_rule": "Keep whole photographer groups and connected 64-bit dHash Hamming<=6 near-duplicate groups together; greedy largest-group-first 50/50/80 target allocation, seed hash tie-break; not guaranteed perfect semantic scene deduplication",
                "annotation_archive_url": URL, "annotation_member": "annotations/instances_val2017.json", "annotation_sha256": sha(annotation_path),
                "annotation_license": "CC BY 4.0", "terms_url": "https://cocodataset.org/#termsofuse", "images": selected, "excluded": excluded,
                "limitations": "Public COCO validation subset, no independent guarantee against model pretraining contamination; ordinary Flickr photography, not first-person assistive trials. dHash and creator grouping reduce scene leakage but cannot prove all scenes independent. Synthetic dim/blur are sensitivity probes only. No labeled object identities, depth, accessibility or text transcripts."}
    (BASE / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    lines = ["# SIGHT 3 evaluation photograph attribution", "", "Only current, attributed CC BY photographs are selected. Each original remains copyright its listed creator. Evaluation overlays add predicted/ground-truth rectangles and labels; sensitivity probes dim or horizontally blur originals. They do not imply endorsement. No photos are included in production app assets.", "", "COCO annotations: COCO Consortium, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [terms](https://cocodataset.org/#termsofuse). Exact file hashes, selection, creator grouping, and original oEmbed attribution evidence are in manifest.json. Attribution was retrieved before inference.", "", "| COCO ID | Split | Photograph | Creator | License |", "| --- | --- | --- | --- | --- |"]
    for image in selected:
        credit = image["attribution"]
        title = (credit["title"] or "Untitled").replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {image['id']} | {image['split']} | [{title}]({credit['source_url']}) | [{credit['author_name']}]({credit['author_url']}) | [{credit['license']}]({credit['license_url']}) |")
    (BASE / "ATTRIBUTION.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("DONE", counts, "bytes", sum((BASE / i["local_path"]).stat().st_size for i in selected), flush=True)


if __name__ == "__main__":
    main()
