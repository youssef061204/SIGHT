"""Seal80new credited photographs, isolated from every earlier SIGHT photo/creator."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request

from prepare_v3_corpus import resolve, dhash, sha

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "evaluation/data-v3-yolox"
SEED = "sight-yolox-fresh-audit-20261025"


def main():
    BASE.mkdir(exist_ok=True)
    (BASE / "images").mkdir(exist_ok=True)
    target = BASE / "manifest.json"
    if target.exists():
        sealed = json.loads(target.read_text())
        for image in sealed["images"]:
            path = BASE / image["local_path"]
            if not path.exists():
                urllib.request.urlretrieve(image["download_url"], path)
            if sha(path) != image["sha256"]:
                raise RuntimeError("Sealed photo hash changed")
        print("Verified sealed80", flush=True)
        return
    old = json.loads((ROOT / "evaluation/data/manifest.json").read_text())
    prior = json.loads((ROOT / "evaluation/data-v3/manifest.json").read_text())
    old_credits = json.loads((ROOT / "evaluation/data/attribution.json").read_text())["images"]
    excluded_creators = {credit["author_url"].replace("http://", "https://") for credit in old_credits if credit["status"] == "resolved"} | {image["attribution"]["author_url"] for image in prior["images"]}
    excluded_ids = {image["id"] for image in old["images"] + prior["images"]}
    hashes = [dhash(ROOT / "evaluation/data" / image["local_path"]) for image in old["images"]]
    hashes += [int(image["dhash64"], 16) for image in prior["images"]]
    data = json.loads((ROOT / "evaluation/data/cache/instances_val2017.json").read_text())
    candidates = [image for image in data["images"] if image["license"] == 4 and image["id"] not in excluded_ids]
    candidates.sort(key=lambda image: hashlib.sha256(f"{SEED}:{image['id']}".encode()).hexdigest())
    cache_file = BASE / "attribution-at-selection.json"
    cached = json.loads(cache_file.read_text()) if cache_file.exists() else {"retrieved_at_utc": datetime.now(timezone.utc).isoformat(), "rows": []}
    by_id = {row["image_id"]: row for row in cached["rows"]}
    selected, exclusions = [], []
    for start in range(0, len(candidates), 32):
        batch = candidates[start:start+32]
        with ThreadPoolExecutor(max_workers=6) as pool:
            for credit in pool.map(resolve, [image for image in batch if image["id"] not in by_id]):
                by_id[credit["image_id"]] = credit
        cached["rows"] = list(by_id.values())
        cache_file.write_text(json.dumps(cached, indent=2))
        for image in batch:
            credit = by_id[image["id"]]
            if credit["status"] != "resolved" or credit["author_url"] in excluded_creators:
                exclusions.append({"image_id": image["id"], "reason": credit.get("error", "creator shared with196earlier images")})
                continue
            path = BASE / "images" / image["file_name"]
            url = "https://s3.amazonaws.com/images.cocodataset.org/val2017/" + image["file_name"]
            if not path.exists():
                urllib.request.urlretrieve(url, path)
            fingerprint = dhash(path)
            if any((fingerprint ^ previous).bit_count() <= 6 for previous in hashes):
                exclusions.append({"image_id": image["id"], "reason": "dHash Hamming<=6 with earlier image"})
                path.unlink()
                continue
            selected.append({**image, "attribution": credit, "sha256": sha(path), "dhash64": f"{fingerprint:016x}",
                             "local_path": "images/" + path.name, "download_url": url})
            print("accepted", len(selected), image["id"], credit["author_name"].encode("ascii", "backslashreplace").decode(), flush=True)
            if len(selected) == 80:
                break
        if len(selected) == 80:
            break
    if len(selected) != 80:
        raise RuntimeError("Not enough entirely new credited photos; do not weaken separation")
    ids = {image["id"] for image in selected}
    subset = {"info": data["info"], "licenses": data["licenses"], "categories": data["categories"],
              "images": [image for image in data["images"] if image["id"] in ids],
              "annotations": [annotation for annotation in data["annotations"] if annotation["image_id"] in ids]}
    annotations = BASE / "instances_audit.json"
    annotations.write_text(json.dumps(subset, indent=2))
    manifest = {"id": SEED, "created_at_utc": datetime.now(timezone.utc).isoformat(), "count": 80,
                "selection": "Before YOLOX candidate inference, seeded SHA256 order of license4images with recovered current CC BY authors; exclude all prior196IDs/known creators and dHash<=6 near duplicates",
                "excluded_prior_manifest_sha256": [sha(ROOT / "evaluation/data/manifest.json"), sha(ROOT / "evaluation/data-v3/manifest.json")],
                "annotation_sha256": sha(annotations), "annotation_source_sha256": sha(ROOT / "evaluation/data/cache/instances_val2017.json"),
                "annotation_license": "CC BY 4.0", "images": selected, "exclusions": exclusions,
                "limitations": "New final audit only for one additional candidate; prior50selection/50calibration may be reused for development, old80test is never used for new selection or final claims. Public COCO validation photos, no guarantee against upstream training contamination; ordinary photographs not first-person accessibility study. Creator/near-duplicate exclusion does not prove perfect semantic scene independence."}
    target.write_text(json.dumps(manifest, indent=2))
    lines = ["# New YOLOX audit photograph attribution", "", "80new held-out photographs, sealed before candidate comparison. No image or recovered photographer appears in the earlier180or16-photo corpus. Same-source near duplicates are screened with64bit dHash distance<=6. All originals remain copyright the credited creator. Detector overlays/blur/dim transforms are derivatives and do not imply endorsement. No photograph is a production asset. COCO annotations: COCO Consortium [CC BY4.0](https://creativecommons.org/licenses/by/4.0/). Exact originals, hashes and exclusion rules are in manifest.json.", "", "| COCO ID | Photograph | Creator | License |", "| --- | --- | --- | --- |"]
    for image in selected:
        credit = image["attribution"]
        title = credit["title"].replace("|", "\\|").replace("\n", " ") or "Untitled"
        lines.append(f"| {image['id']} | [{title}]({credit['source_url']}) | [{credit['author_name']}]({credit['author_url']}) | [{credit['license']}]({credit['license_url']}) |")
    (BASE / "ATTRIBUTION.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("SEALED80", sha(target), flush=True)


if __name__ == "__main__":
    main()
