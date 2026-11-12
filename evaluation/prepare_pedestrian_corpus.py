"""Seal a road-balanced, credited COCO-Stuff corpus before running its models."""
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request
import urllib.parse

import numpy as np
from PIL import Image
from pycocotools import mask as mask_api

from prepare_v3_corpus import short_url, dhash
from pedestrian_evaluation import SOURCE_SHA, SOURCE_URL, sha, write

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "evaluation/data-pedestrian-balanced"
SEED = "sight-road-context-creator-balanced-20261026"

def publication_whitelists(manifest):
    # Preparation may download candidates later rejected for attribution or near-duplication.
    # Keep them local, but permit only final, credited manifest entries into source publication.
    for directory,key in (("images","imagePath"),("masks","maskPath")):
        names=sorted(Path(item[key]).name for item in manifest['images'])
        (BASE/directory/'.gitignore').write_text('*\n!.gitignore\n'+''.join('!'+name+'\n' for name in names),encoding='utf-8',newline='\n')


def resolve(image):
    url = "https://www.flickr.com/services/oembed/?" + urllib.parse.urlencode({"url": short_url(image), "format": "json"})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SIGHT-road-evaluation"}), timeout=20) as response:
            info = json.load(response)
        accepted = {"CC BY 2.0", "CC BY 4.0", "CC BY-SA 2.0", "CC BY-SA 4.0"}
        if info.get("license") not in accepted:
            raise ValueError("Current license not accepted: " + str(info.get("license")))
        return {"image_id": image["id"], "status": "resolved", "author_name": info["author_name"],
                "author_url": info["author_url"].replace("http://", "https://"), "title": info.get("title", "Untitled"),
                "source_url": info["web_page"], "license": info["license"], "license_url": info["license_url"], "oembed_url": url}
    except Exception as error:
        return {"image_id": image["id"], "status": "excluded", "error": str(error), "oembed_url": url}


def main():
    BASE.mkdir(parents=True, exist_ok=True)
    target = BASE / "manifest.json"
    if target.exists():
        manifest = json.loads(target.read_text(encoding="utf-8"))
        for item in manifest["images"]:
            for path_key, hash_key in (("imagePath", "imageSha256"), ("maskPath", "maskSha256")):
                path = ROOT / item[path_key]
                if sha(path) != item[hash_key]:
                    raise RuntimeError("Sealed corpus bytes changed")
        publication_whitelists(manifest)
        print("Verified sealed balanced corpus", manifest["splitCounts"])
        return
    source_path = ROOT / "evaluation/data/cache/stuff_val2017.json"
    if sha(source_path) != SOURCE_SHA:
        raise RuntimeError("Source annotation hash changed")
    source = json.loads(source_path.read_text(encoding="utf-8"))
    instances = json.loads((ROOT / "evaluation/data/cache/instances_val2017.json").read_text(encoding="utf-8"))
    excluded_ids, excluded_creators, previous_hashes = set(), set(), []
    for name in ("data", "data-v3", "data-v3-yolox"):
        manifest = json.loads((ROOT / "evaluation" / name / "manifest.json").read_text(encoding="utf-8"))
        for image in manifest["images"]:
            excluded_ids.add(image["id"])
            credit = image.get("attribution", {})
            if credit.get("author_url"):
                excluded_creators.add(credit["author_url"].replace("http://", "https://"))
            if image.get("dhash64"):
                previous_hashes.append(int(image["dhash64"], 16))
            else:
                image_path = ROOT / "evaluation" / name / image["local_path"]
                if image_path.exists(): previous_hashes.append(dhash(image_path))
    historic = json.loads((ROOT / "evaluation/data/attribution.json").read_text(encoding="utf-8"))
    excluded_creators.update(i["author_url"].replace("http://", "https://") for i in historic["images"] if i["status"] == "resolved")
    grouped = defaultdict(list)
    area = defaultdict(Counter)
    names = {c["id"]: c["name"] for c in source["categories"]}
    ground_ids = {c["id"] for c in source["categories"] if c["name"] in {
        "pavement", "floor-tile", "floor-stone", "floor-wood", "floor-other", "floor-marble", "carpet",
        "dirt", "grass", "gravel", "snow", "sand", "ground-other"}}
    for ann in source["annotations"]:
        grouped[ann["image_id"]].append(ann)
        area[ann["image_id"]][ann["category_id"]] += ann["area"]
    candidates = {True: [], False: []}
    for image in instances["images"]:
        if image["license"] not in (4, 5) or image["id"] in excluded_ids:
            continue
        size = image["width"] * image["height"]
        road_fraction = area[image["id"]][149] / size
        positive = road_fraction >= .02
        if not positive and (road_fraction > 0 or sum(area[image["id"]][key] for key in ground_ids) / size < .02):
            continue
        candidates[positive].append(image)
    for values in candidates.values():
        values.sort(key=lambda i: hashlib.sha256(f"{SEED}:{i['id']}".encode()).hexdigest())
    print("ELIGIBLE before current credit checks", {str(k): len(v) for k, v in candidates.items()}, flush=True)
    cache_path = BASE / "attribution-at-selection.json"
    cached = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {"resolvedAtUtc": datetime.now(timezone.utc).isoformat(), "rows": []}
    by_id = {r["image_id"]: r for r in cached["rows"]}
    accepted, exclusions, authors = [], [], Counter()
    count = Counter()
    for positive in (True, False):
        for start in range(0, len(candidates[positive]), 24):
            batch = candidates[positive][start:start + 24]
            with ThreadPoolExecutor(max_workers=4) as pool:
                for credit in pool.map(resolve, [i for i in batch if i["id"] not in by_id]):
                    by_id[credit["image_id"]] = credit
            cached["rows"] = list(by_id.values())
            write(cache_path, cached)
            for image in batch:
                credit = by_id[image["id"]]
                if credit["status"] != "resolved" or credit.get("author_url") in excluded_creators or authors[credit.get("author_url")] >= 2:
                    exclusions.append({"id": image["id"], "reason": credit.get("error", "prior creator or two-photo creator cap")})
                    continue
                path = BASE / "images" / image["file_name"]
                path.parent.mkdir(parents=True, exist_ok=True)
                url = "https://s3.amazonaws.com/images.cocodataset.org/val2017/" + image["file_name"]
                if not path.exists(): urllib.request.urlretrieve(url, path)
                fingerprint = dhash(path)
                if any((fingerprint ^ value).bit_count() <= 6 for value in previous_hashes + [int(i["dhash64"], 16) for i in accepted]):
                    exclusions.append({"id": image["id"], "reason": "near duplicate of prior or accepted photograph"})
                    continue
                truth = np.full((image["height"], image["width"]), 255, dtype=np.uint8)
                for ann in grouped[image["id"]]:
                    enc = ann["segmentation"].copy(); enc["counts"] = enc["counts"].encode("ascii")
                    if ann["category_id"] > 0:
                        truth[mask_api.decode(enc).astype(bool)] = 1 if ann["category_id"] == 149 else 2 if ann["category_id"] == 140 else 0
                mask_path = BASE / "masks" / (str(image["id"]).zfill(12) + ".png")
                mask_path.parent.mkdir(parents=True, exist_ok=True)
                Image.fromarray(truth).save(mask_path)
                item = {"id": image["id"], "width": image["width"], "height": image["height"],
                        "imagePath": path.relative_to(ROOT).as_posix(), "imageSha256": sha(path),
                        "maskPath": mask_path.relative_to(ROOT).as_posix(), "maskSha256": sha(mask_path),
                        "downloadUrl": url, "dhash64": f"{fingerprint:016x}", "attribution": credit,
                        "roadTruthPresent": positive, "roadPixels": int((truth == 1).sum()),
                        "pavementPixels": int((truth == 2).sum()), "validPixels": int((truth != 255).sum())}
                accepted.append(item); count[positive] += 1; authors[credit["author_url"]] += 1
                print("ACCEPT", positive, count[positive], image["id"], ascii(credit["author_name"]), flush=True)
                if count[positive] == 50: break
            if count[positive] == 50: break
        if count[positive] != 50:
            write(BASE / "selection-incomplete.json", {"accepted": accepted, "exclusions": exclusions, "counts": dict(count)})
            raise RuntimeError(f"Only{count[positive]} credited photos for roadPresence={positive}; cannot silently change target")
    # Each new photo is outside the near-duplicate radius. Keep whole creator groups together.
    groups = defaultdict(list)
    for item in accepted: groups[item["attribution"]["author_url"]].append(item)
    targets = {"selection": {True: 15, False: 15}, "calibration": {True: 15, False: 15}, "held_out": {True: 20, False: 20}}
    assigned = {name: Counter() for name in targets}
    for group in sorted(groups.values(), key=lambda g: (-len(g), hashlib.sha256(f"{SEED}:group:{min(i['id'] for i in g)}".encode()).hexdigest())):
        shape = Counter(i["roadTruthPresent"] for i in group)
        feasible = [name for name in targets if all(assigned[name][kind] + shape[kind] <= targets[name][kind] for kind in (True, False))]
        if not feasible: raise RuntimeError("Whole-creator groups cannot fit exact balanced targets; no split emitted")
        chosen = max(feasible, key=lambda name: sum(targets[name][kind] - assigned[name][kind] for kind in shape))
        assigned[chosen].update(shape)
        for item in group: item["split"] = chosen; item["sceneGroup"] = "creator-" + str(min(i["id"] for i in group))
    manifest = {"schema": "sight.pedestrian.dataset", "id": SEED, "count": len(accepted), "images": accepted,
                "createdAtUtc": datetime.now(timezone.utc).isoformat(), "splitCounts": {name: sum(assigned[name].values()) for name in assigned},
                "positiveCounts": {name: assigned[name][True] for name in assigned},
                "selection": "Before inference: COCO val2017 license4or5, seededSHAorder, road>=2percentGT positives; road-zero>=2percentground-class negatives; currentCCBYorCCBYSAFlickrcredit; max2photos/creator",
                "grouping": "Prior276IDs/creators and dHash<=6 excluded; near duplicates within new corpus excluded; whole creator groups stay in one split",
                "priorExcludedIds": sorted(excluded_ids), "priorExcludedCreators": sorted(excluded_creators),
                "sourceAnnotationUrl": SOURCE_URL, "sourceAnnotationSha256": SOURCE_SHA,
                "maskClasses": {"0": "other annotated classes", "1": "road149", "2": "pavement140", "255": "ignored"},
                "negativeGroundLabels": [names[k] for k in sorted(ground_ids)],
                "exclusions": exclusions,
                "licenses": {"photographs": "Individual current CC BY or CC BY-SA, per-image attribution. Photographic derivatives retain source share-alike terms where applicable; these licenses do not cover application code/model.", "annotations": "COCO-Stuff authors, CC BY4.0"},
                "limits": "Ordinary ground photographs, not first-person video, depth or supervised mobility trials. Training-set overlap cannot be independently ruled out. Road-balanced design changes prevalence; do not present as natural-frequency false-warning rate."}
    write(target, manifest)
    publication_whitelists(manifest)
    lines = ["# Pedestrian corpus attribution", "", "Photographs retain each named creator's listed CC BY or CC BY-SA license; photographic derivatives/overlays retain the source share-alike terms where applicable. These rights are separate from the application code/model. Ground-truth masks derive from COCO-Stuff by Holger Caesar, Jasper Uijlings and Vittorio Ferrari, [CC BY4.0](https://creativecommons.org/licenses/by/4.0/), [source](https://github.com/nightrome/cocostuff#licensing). Masks map road149 to1, pavement140 to2, other annotated classes to0 and unlabeled pixels to255. No images were inferred before this manifest was sealed.", "", "|ID|Split|Road GT|Photograph|Creator|License|", "|---|---|---|---|---|---|"]
    for item in accepted:
        credit = item["attribution"]
        title = credit["title"].replace("|", "\\|").replace("\n", " ")
        lines.append(f"|{item['id']}|{item['split']}|{item['roadTruthPresent']}|[{title}]({credit['source_url']})|[{credit['author_name']}]({credit['author_url']})|[{credit['license']}]({credit['license_url']})|")
    (BASE / "ATTRIBUTION.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print("SEALED", manifest["splitCounts"], manifest["positiveCounts"], sha(target), flush=True)


if __name__ == "__main__": main()
