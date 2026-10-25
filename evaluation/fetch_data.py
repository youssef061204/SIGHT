"""Fetch a fixed, small COCO val2017 sample without downloading either full dataset.

Only the val annotation member of the official archive is transferred (HTTP ranges).
Photographs retain their individual Flickr licenses and original source links.
"""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
URL = "https://s3.amazonaws.com/images.cocodataset.org/annotations/annotations_trainval2017.zip"
SELECTION = "sight-coco-smoke-v1"
COUNT = 16
ANNOTATION_SHA256 = "e8c7f7908f1d7278341fae127d0da654f102f11bd7b21d8aeefa635b8c810b6f"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class RangeFile(io.RawIOBase):
    def __init__(self, url: str):
        self.url = url
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as response:
            self.size = int(response.headers["Content-Length"])
        self.position = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        self.position = offset + (self.position if whence == 1 else self.size if whence == 2 else 0)
        return self.position

    def read(self, size=-1):
        end = self.size if size < 0 else min(self.position + size, self.size)
        if end <= self.position:
            return b""
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={self.position}-{end - 1}"})
        with urllib.request.urlopen(request, timeout=90) as response:
            if response.status != 206:
                raise RuntimeError("Server did not honor Range; refusing a full dataset download")
            data = response.read()
        if len(data) != end - self.position:
            raise RuntimeError("Incomplete range download")
        self.position = end
        return data


def main():
    cache = ROOT / "data" / "cache"
    cache.mkdir(parents=True, exist_ok=True)
    annotation_path = cache / "instances_val2017.json"
    if not annotation_path.exists():
        with zipfile.ZipFile(RangeFile(URL)) as archive:
            annotation_path.write_bytes(archive.read("annotations/instances_val2017.json"))
    if sha256(annotation_path) != ANNOTATION_SHA256:
        raise RuntimeError("Official annotation hash changed; refusing silent dataset drift")
    data = json.loads(annotation_path.read_text(encoding="utf-8"))
    # CC BY 2.0 only: commercial reuse and annotated derivatives allowed with attribution.
    candidates = [image for image in data["images"] if image["license"] == 4]
    candidates.sort(key=lambda image: hashlib.sha256(f"{SELECTION}:{image['id']}".encode()).hexdigest())
    selected = candidates[:COUNT]
    selected_ids = {image["id"] for image in selected}
    images_dir = ROOT / "data" / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = ROOT / "data" / "manifest.json"
    pinned_images = {image["id"]: image["sha256"] for image in json.loads(manifest_path.read_text(encoding="utf-8"))["images"]} if manifest_path.exists() else {}
    entries = []
    for image in selected:
        path = images_dir / image["file_name"]
        url = f"https://s3.amazonaws.com/images.cocodataset.org/val2017/{image['file_name']}"
        if not path.exists():
            urllib.request.urlretrieve(url, path)
        if image["id"] in pinned_images and sha256(path) != pinned_images[image["id"]]:
            raise RuntimeError(f"Photograph hash changed: {image['id']}; refusing silent dataset drift")
        entries.append({**image, "download_url": url, "sha256": sha256(path), "local_path": f"images/{path.name}"})
        print(f"Fetched {path.name}", flush=True)
    subset = {"info": data["info"], "licenses": data["licenses"], "images": selected,
              "annotations": [ann for ann in data["annotations"] if ann["image_id"] in selected_ids],
              "categories": data["categories"]}
    (ROOT / "data" / "instances_smoke.json").write_text(json.dumps(subset, indent=2), encoding="utf-8")
    manifest = {"id": SELECTION, "count": COUNT, "selection": "First 16 CC BY 2.0 (license 4) val2017 images sorted by SHA256(seed:image_id), fixed before running models",
                "annotation_archive_url": URL, "annotation_member": "annotations/instances_val2017.json",
                "annotation_sha256": sha256(annotation_path), "annotation_license": "CC BY 4.0",
                "terms_url": "https://cocodataset.org/#termsofuse", "images": entries,
                "limitations": "Tiny convenience smoke sample. COCO validation images, no SIGHT tuning. Not an independent assistive-use test or full COCO mAP. No claim these public images were unseen by all model training."}
    (ROOT / "data" / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
