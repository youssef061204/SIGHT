"""Fetch the pinned official public artifacts and verify their recorded digests.

Standard library only. This validates dependency bytes, not SDK execution/hardware.
"""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile


def main() -> None:
    root = Path(__file__).resolve().parent
    lock = json.loads((root / "artifacts.lock.json").read_text(encoding="utf-8"))
    output = root / ".build" / "verified-artifacts"
    output.mkdir(parents=True, exist_ok=True)
    base = "https://repo.maven.apache.org/maven2/com/meta/wearable"
    with urlopen(f"{base}/mwdat-core/maven-metadata.xml", timeout=30) as response:
        metadata = response.read()
    latest = ET.fromstring(metadata).findtext("versioning/latest")
    checks = []
    for artifact, expected in lock["artifacts"].items():
        version = lock["version"]
        url = f"{base}/{artifact}/{version}/{artifact}-{version}.aar"
        with urlopen(url, timeout=60) as response:
            data = response.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest != expected:
            raise ValueError(f"Checksum mismatch for {artifact}: {digest}")
        path = output / f"{artifact}-{version}.aar"
        path.write_bytes(data)
        with zipfile.ZipFile(path) as archive:
            if "classes.jar" not in archive.namelist():
                raise ValueError(f"{artifact} has no classes.jar")
        checks.append({"artifact": artifact, "version": version, "sha256": digest, "url": url})
    evidence = {"mavenLatestAtRun": latest, "pinnedVersion": lock["version"], "verified": checks,
                "validation": "Artifact integrity only; no Android or hardware execution"}
    (output / "verification.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
