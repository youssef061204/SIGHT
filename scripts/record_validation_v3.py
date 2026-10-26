"""Collect SIGHT3 evidence without overwriting historical SIGHT2 results."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/v3-validation"


def read_text(path):
    raw = path.read_bytes() if path.exists() else b""
    return raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig", errors="replace")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    tests = {}
    for name, relative in (
        ("core_jvm", "core/build/test-results/test"),
        ("perception_jvm", "perception/build/test-results/testDebugUnitTest"),
        ("app_jvm", "app/build/test-results/testDebugUnitTest"),
        ("meta_jvm", "integrations/meta/adapter/build/test-results/testDebugUnitTest"),
    ):
        files = list((ROOT / relative).glob("TEST-*.xml"))
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        for path in files:
            suite = ET.parse(path).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, 0))
            shutil.copyfile(path, OUT / f"{name}-{path.name}")
        tests[name] = {**counts, "status": "passed" if files and counts["failures"] == counts["errors"] == 0 else "not_passed"}
    for name, relative, expected in (
        ("android35_app", "v3-android35/app-suite.txt", 11),
        ("android35_meta", "v3-android35/meta-suite.txt", 8),
        ("android30_regression", "v3-android30/app-suite.txt", 6),
    ):
        raw = read_text(ROOT / "evaluation/results" / relative)
        matched = re.search(r"OK \((\d+) tests?\)", raw)
        passed = matched and int(matched[1]) == expected and not re.search(r"FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED", raw)
        tests[name] = {"status": "passed" if passed else "not_run_or_failed", "expected": expected, "raw": relative}
    python_raw = read_text(ROOT / "evaluation/results/v3-python-tests.txt")
    match = re.search(r"Ran (\d+) tests", python_raw)
    tests["python"] = {"tests": int(match[1]) if match else 0, "status": "passed" if match and re.search(r"\nOK\s", python_raw) else "not_passed"}
    artifacts = {}
    for relative in (
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        "integrations/meta/adapter/build/outputs/apk/androidTest/debug/adapter-debug-androidTest.apk",
        "perception/src/main/assets/efficientdet_lite0_int8.tflite",
        "perception/src/main/assets/efficientdet_lite2_int8.tflite",
    ):
        path = ROOT / relative
        if path.exists():
            artifacts[relative] = {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    for name in ("v3-core-perception-rerun.log", "v3-app-build-final.log", "v3-meta-build.log"):
        path = ROOT / ".tools" / name
        if path.exists():
            (OUT / name).write_text(read_text(path), encoding="utf-8")
    value = {"schema": "sight.validation.v3", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
             "baselineCommit": "f37f85d", "tests": tests, "artifacts": artifacts,
             "boundary": "Android emulators and official Meta MDK simulated media. No physical phone/glasses, human TalkBack, assistive usability, battery or calibrated audible-onset validation."}
    (OUT / "summary.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(value, indent=2))


if __name__ == "__main__":
    main()
