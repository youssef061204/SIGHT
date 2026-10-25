"""Recover original Flickr author, title and current license via canonical short links."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
manifest = json.loads((ROOT / "data/manifest.json").read_text())
ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"


def resolve(image):
    photo_id = int(re.search(r"/(\d+)_", image["flickr_url"]).group(1))
    encoded = ""
    number = photo_id
    while number:
        encoded = ALPHABET[number % 58] + encoded
        number //= 58
    short_url = f"https://flic.kr/p/{encoded}"
    try:
        with urllib.request.urlopen(short_url, timeout=30) as response:
            canonical = response.url
        url = "https://www.flickr.com/services/oembed/?" + urllib.parse.urlencode({"url": canonical, "format": "json"})
        with urllib.request.urlopen(url, timeout=30) as response:
            info = json.load(response)
        return {"image_id": image["id"], "flickr_photo_id": photo_id, "short_url": short_url,
                "source_url": canonical, "author_name": info["author_name"], "author_url": info["author_url"],
                "title": info["title"], "license": info.get("license"), "license_url": info.get("license_url"),
                "status": "resolved", "oembed_url": url}
    except Exception as error:
        return {"image_id": image["id"], "flickr_photo_id": photo_id, "short_url": short_url,
                "source_url": image["flickr_url"], "status": "unavailable", "error": str(error),
                "license": "CC BY 2.0 per original COCO license_id=4; author unavailable; do not redistribute until attribution is resolved"}


with ThreadPoolExecutor(max_workers=4) as pool:
    rows = list(pool.map(resolve, manifest["images"]))
(ROOT / "data/attribution.json").write_text(json.dumps({"resolved_at_utc": datetime.now(timezone.utc).isoformat(), "images": rows}, indent=2), encoding="utf-8")
lines = ["# COCO smoke photograph attribution", "", "Photographs remain copyright their named Flickr creators. Original photographs are used locally for model evaluation. Annotated result frames add detector rectangles and scores; sensitivity probes dim images or apply a horizontal blur. The OCR crop of image 184324 crops and scales a small van label. A contact sheet scales and tiles the originals. These modifications do not imply the creators endorse SIGHT.", "", "COCO annotations: COCO Consortium, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); [terms](https://cocodataset.org/#termsofuse). Photo attribution below was recovered from Flickr oEmbed using original photo IDs on 2026-10-23. SHA256 and dataset provenance remain in manifest.json. Photos must not be included in production application assets; the explicitly attributed Android test image is a test fixture.", "", "| COCO image | Photograph | Creator | License |", "| --- | --- | --- | --- |"]
for row in rows:
    if row["status"] == "resolved":
        title = row["title"].replace("|", "\\|") or "Untitled"
        lines.append(f"| {row['image_id']} | [{title}]({row['source_url']}) | [{row['author_name']}]({row['author_url']}) | [{row['license']}]({row['license_url']}) |")
    else:
        lines.append(f"| {row['image_id']} | [Original]({row['source_url']}) | Unavailable; redistribution pending attribution | {row['license']} |")
(ROOT / "data/ATTRIBUTION.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps(rows, indent=2))
