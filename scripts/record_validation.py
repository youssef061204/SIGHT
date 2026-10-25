"""Collect executed-test evidence. Missing/crashed instrumentation is never a pass."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "evaluation/results/validation"
OUTPUT.mkdir(parents=True, exist_ok=True)
reports = {}
for name, directory in (
    ("core_jvm", "core/build/test-results/test"),
    ("app_jvm", "app/build/test-results/testDebugUnitTest"),
    ("meta_jvm", "integrations/meta/adapter/build/test-results/testDebugUnitTest"),
):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    files = sorted((ROOT / directory).glob("TEST-*.xml"))
    for file in files:
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
        shutil.copyfile(file, OUTPUT / f"{name}-{file.name}")
    reports[name] = {**counts, "status": "passed" if files and counts["failures"] == counts["errors"] == 0 else "not_passed"}

for name, relative, expected in (
    ("android35_native", "emulator-api35/native-perception.txt", 2),
    ("android35_camera", "emulator-api35/camera-flow.txt", 1),
    ("android35_atd_final_suite", "emulator-api35/atd-final-suite.txt", 3),
    ("meta_official_mdk", "emulator-api35/meta-instrumentation.txt", 5),
    ("meta_official_mdk_atd", "emulator-api35/atd-meta-instrumentation.txt", 5),
    ("android30_native", "emulator-api30/native-perception.txt", 2),
    ("android30_camera", "emulator-api30/camera-flow.txt", 1),
    ("android30_final_suite", "emulator-api30/final-suite.txt", 3),
):
    file = ROOT / "evaluation/results" / relative
    raw = file.read_bytes() if file.exists() else b""
    # Windows PowerShell native redirection historically produces UTF-16LE.
    encoding = "utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
    content = raw.decode(encoding, errors="replace")
    match = re.search(r"OK \((\d+) tests?\)", content)
    passed = bool(match and int(match[1]) == expected and "FAILURES!!!" not in content and "Process crashed" not in content)
    reports[name] = {"expected_tests": expected, "status": "passed" if passed else "not_run_or_failed", "raw_output": relative}

speech_file = ROOT / "evaluation/results/emulator-api35/speech-proof.json"
speech = json.loads(speech_file.read_text(encoding="utf-8-sig")) if speech_file.exists() else {}
reports["android35_offline_speech"] = {
    "status": "passed" if speech.get("synthesized") is True and speech.get("networkRequired") is False and speech.get("pcmBytes", 0) > 1_000 else "not_run_or_unavailable",
    "proof": speech,
}
python_output = (ROOT / "evaluation/results/python-tests.txt").read_text(encoding="utf-8")
reports["python_tools"] = {"tests": 9, "status": "passed" if "Ran 9 tests" in python_output and "\nOK\n" in python_output else "not_passed"}

artifacts = {}
for relative in (
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    "integrations/meta/adapter/build/outputs/apk/androidTest/debug/adapter-debug-androidTest.apk",
    "app/src/main/assets/efficientdet_lite0_int8.tflite",
):
    file = ROOT / relative
    if file.exists():
        artifacts[relative] = {"bytes": file.stat().st_size, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}

adb = str(ROOT / ".tools/android-sdk/platform-tools/adb.exe")
def property_value(serial, name):
    result = subprocess.run([adb, "-s", serial, "shell", "getprop", name], capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise OSError("Emulator unavailable")
    return result.stdout.strip()

devices = {}
for serial in ("emulator-5558", "emulator-5556"):
    try:
        devices[serial] = {name: property_value(serial, name) for name in ("ro.build.fingerprint", "ro.product.cpu.abilist", "ro.build.version.sdk", "sys.boot_completed")}
    except (OSError, subprocess.TimeoutExpired):
        archived = ROOT / "evaluation/results" / ("emulator-api35/atd-device.json" if serial == "emulator-5558" else "emulator-api30/device.json")
        devices[serial] = json.loads(archived.read_text()) if archived.exists() else {"status": "not_available_at_collection"}
        devices[serial]["collection"] = "Properties captured earlier in the executed run"

output = {"schema": "sight.validation.v1", "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
          "tests": reports, "artifacts": artifacts, "devices": devices,
          "historical_failures": "Google APIs Android 15 combined reruns had freshness timeouts with System UI ANRs; an interrupted rerun reports Process crashed after the test process was deliberately stopped. Logs are retained. Final camera validation uses AOSP ATD; freshness limits were unchanged.",
          "boundary": "Android emulators and official MDK simulated media, not physical phones/glasses or participant validation. Headless emulator audio output was disabled; no audible-hearing claim."}
(OUTPUT / "summary.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
print(json.dumps(output, indent=2))
