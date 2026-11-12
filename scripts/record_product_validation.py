"""Aggregate current SIGHT product evidence without inferring results from exit codes or history."""
from datetime import datetime, timezone
import hashlib
import json
from datetime import datetime as dt
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evaluation/results/product-validation"

def read_text(path):
    if not path.is_file(): return ""
    raw = path.read_bytes()
    decoded = raw.decode("utf-16" if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig", errors="replace")
    return re.sub(r"\r+\n", "\n", decoded)

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""): h.update(block)
    return {"bytes": path.stat().st_size, "sha256": h.hexdigest(),
            "modifiedAtUtc": dt.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()}

def xml_counts(paths, key):
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    found, published = [], []
    for path in paths:
        try: root = ET.parse(path).getroot()
        except (ET.ParseError, OSError): continue
        suites = [root] if root.tag.endswith("testsuite") else [x for x in root.iter() if x.tag.endswith("testsuite")]
        for s in suites:
            for count_key in counts:
                try: counts[count_key] += int(s.get(count_key, 0))
                except ValueError: pass
        found.append(path.relative_to(ROOT).as_posix())
        destination = OUT / f"{key}-{path.name}"
        shutil.copyfile(path, destination)
        published.append(destination.relative_to(ROOT).as_posix())
    return counts, found, published

def suite_gate(name, phase, expected):
    folder = ROOT / "evaluation/results" / ("product-" + phase)
    candidates = [folder / x for x in ("instrumentation-suite.txt", "app-suite.txt", "meta-suite.txt", "speech-suite.txt", "suite.txt")]
    path = next((x for x in candidates if x.is_file()), None)
    raw = read_text(path) if path else ""
    match = re.search(r"\bOK\s*\(\s*(\d+)\s+tests?\s*\)", raw)
    bad = re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|error:|FAILURE:", raw, re.I)
    actual = int(match.group(1)) if match else None
    return {"status": "passed" if actual == expected and not bad else ("failed" if match or bad else "missing"),
            "expectedTests": expected, "reportedTests": actual,
            "raw": path.relative_to(ROOT).as_posix() if path else None,
            "reason": None if actual == expected and not bad else ("runner reported failure" if bad else "exact successful runner count unavailable")}

def python_gate():
    p = ROOT / "evaluation/results/product-python-tests.txt"
    raw = read_text(p)
    n = re.search(r"Ran\s+(\d+)\s+tests?", raw)
    ok = bool(n and int(n.group(1)) == 14 and re.search(r"\nOK\s*(?:\n|$)", raw))
    return {"status": "passed" if ok else ("failed" if re.search(r"FAILED\s*\(|\bERROR\b", raw) else "missing"),
            "expectedTests": 14, "reportedTests": int(n.group(1)) if n else None,
            "raw": p.relative_to(ROOT).as_posix() if p.is_file() else None}

def proof(name, phase):
    p = ROOT / "evaluation/results" / ("product-" + phase) / name
    if not p.is_file(): return None, {"status": "missing", "path": p.relative_to(ROOT).as_posix()}
    try: v = json.loads(read_text(p))
    except (ValueError, UnicodeError): return None, {"status": "invalid", "path": p.relative_to(ROOT).as_posix()}
    return v, {"status": "present", "path": p.relative_to(ROOT).as_posix()}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old_xml in OUT.glob("*-TEST-*.xml"):
        old_xml.unlink()
    tests = {}
    for key, relative in (("core_jvm", "core/build/test-results/test"), ("app_jvm", "app/build/test-results/testDebugUnitTest"),
                          ("perception_jvm", "perception/build/test-results/testDebugUnitTest"),
                          ("meta_jvm", "integrations/meta/adapter/build/test-results/testDebugUnitTest")):
        counts, files, public_files = xml_counts((ROOT / relative).glob("TEST-*.xml"), key)
        tests[key] = {**counts, "status": "passed" if files and counts["tests"] > 0 and not counts["failures"] and not counts["errors"] else ("failed" if counts["failures"] or counts["errors"] else "missing"),
                      "xml": public_files, "xmlInternal": files}
    tests["android35_app"] = suite_gate("android35_app", "android35", 15)
    tests["android30_app"] = suite_gate("android30_app", "android30", 15)
    tests["android35_google_speech"] = suite_gate("android35_google_speech", "google-speech", 2)
    tests["android35_meta"] = suite_gate("android35_meta", "meta", 8)
    tests["python"] = python_gate()
    proofs = {}
    road_ok = True
    for phase in ("android35", "android30"):
        road, item = proof("native-road-pipeline-proof.json", phase)
        valid = bool(road and road.get("sourceBitmapOwnedByCaller") is True and road.get("captureProvenanceRetained") is True and
                     road.get("automaticRoadVoiceEnabled") is False and len(road.get("samplesMs", [])) == 30 and
                     isinstance(road.get("p50Ms"), (int,float)) and isinstance(road.get("p95Ms"), (int,float)) and road.get("p95Ms", -1) >= 0)
        item["gate"] = "passed" if valid else "not_passed"
        proofs["native_road_pipeline_" + phase] = item
        road_ok &= valid
    voice_ok = True
    for phase in ("android35", "android30"):
        voice, item = proof("native-voice-recognition-proof.json", phase)
        cases = voice.get("cases", []) if voice else []
        valid = bool(voice and voice.get("allCasesPassed") is True and len(cases) == 3 and all(c.get("intentMatched") is True for c in cases)
                     and voice.get("oneSecondSyntheticSilenceTranscript") == "" and voice.get("networkRecognizerUsed") is False)
        item["gate"] = "passed" if valid else "not_passed"
        proofs["native_voice_recognition_" + phase] = item
        voice_ok &= valid
    synthesis, proofs["native_speech_synthesis"] = proof("sight-speech-proof.json", "google-speech")
    interrupt, proofs["native_speech_interruption"] = proof("speech-queue-native-v3.json", "google-speech")
    speech_ok = bool(synthesis and synthesis.get("offlineVoiceAvailable") is True and synthesis.get("synthesized") is True
                     and synthesis.get("networkRequired") is False and synthesis.get("pcmBytes", 0) > 1000
                     and interrupt and interrupt.get("offlineVoiceAvailable") is True and interrupt.get("taskStarted") is True
                     and interrupt.get("ambientInterrupted") is True)
    proofs["native_speech_synthesis"]["gate"] = proofs["native_speech_interruption"]["gate"] = "passed" if speech_ok else "not_passed"
    nav_ok = True
    nav_fields = ("explicitConsent", "explicitDestinationChoice", "routePreservedDuringTextSearch", "backgroundStoppedSensors", "actualCameraReachedRoadModel", "allAssistanceStopped")
    for phase in ("android35", "android30"):
        nav, item = proof("integrated-navigation-proof.json", phase)
        valid = bool(nav and all(nav.get(k) is True for k in nav_fields) and nav.get("physicalGpsOrMicrophone") is False and nav.get("liveCameraDetectorFrames", 0) >= 3)
        item["gate"] = "passed" if valid else "not_passed"
        proofs["integrated_navigation_" + phase] = item
        nav_ok &= valid
    runtime_ok = True
    for phase in ("android35", "android30"):
        for filename in ("inspection-runtime-v3.json", "camera-runtime-v3.json"):
            value, item = proof(filename, phase)
            item["gate"] = "passed" if value is not None else "not_passed"
            proofs[phase + "_" + filename.removesuffix(".json")] = item
            runtime_ok &= value is not None
    color, proofs["meta_color_chart"] = proof("meta-color-chart-proof.json", "meta")
    color_ok = bool(color and color.get("source") == "meta-dat-mock" and color.get("width", 0) > 0 and color.get("height", 0) > 0
                    and color.get("layout") == "packed-I420" and color.get("rotationDegrees") == 0
                    and isinstance(color.get("rgb"), list) and len(color["rgb"]) == 4)
    proofs["meta_color_chart"]["gate"] = "passed" if color_ok else "not_passed"
    meta, proofs["meta_real_pipeline"] = proof("meta-real-pipeline-proof.json", "meta")
    samples = meta.get("samples", []) if meta else []
    meta_ok = bool(meta and meta.get("findCompleted") is True and meta.get("failure") is None and len(samples) >= 2
                   and any(s.get("goalPhase") == "COMPLETED" for s in samples) and meta.get("physicalGlassesTested") is False)
    proofs["meta_real_pipeline"]["gate"] = "passed" if meta_ok else "not_passed"
    artifacts = {}
    hash_roots = ("app/src", "core/src", "perception/src", "integrations/meta/adapter/src")
    for rel in hash_roots:
        for path in sorted((ROOT / rel).rglob("*")):
            if path.is_file(): artifacts[path.relative_to(ROOT).as_posix()] = digest(path)
    for rel in ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                "integrations/meta/adapter/build/outputs/apk/androidTest/debug/adapter-debug-androidTest.apk"):
        path = ROOT / rel
        if path.is_file(): artifacts[rel] = digest(path)
    required_artifacts = ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                         "integrations/meta/adapter/build/outputs/apk/androidTest/debug/adapter-debug-androidTest.apk")
    freshness = {}
    for key, source_roots, apk, xml_key in (
        ("app", ("app/src", "core/src", "perception/src"), required_artifacts[0], "app_jvm"),
        # Test code lives in a separate APK. Main implementation changes can legitimately
        # leave this APK UP-TO-DATE; the installed main APK is checked independently above.
        ("app_android_test", ("app/src/androidTest",), required_artifacts[1], "app_jvm"),
        ("meta", ("integrations/meta/adapter/src", "core/src", "perception/src"), required_artifacts[2], "meta_jvm"),
    ):
        srcs = [p for root in source_roots for p in (ROOT / root).rglob("*") if p.is_file()]
        newest_src = max((p.stat().st_mtime for p in srcs), default=0)
        apk_path = ROOT / apk
        apk_fresh = apk_path.is_file() and apk_path.stat().st_mtime >= newest_src
        xml_files = [ROOT / x for x in tests[xml_key].get("xmlInternal", [])]
        xml_fresh = bool(xml_files) and all(x.is_file() and x.stat().st_mtime >= newest_src for x in xml_files)
        freshness[key] = {"latestSourceModifiedAtUtc": dt.fromtimestamp(newest_src, timezone.utc).isoformat() if newest_src else None,
                          "apkAtOrAfterSources": apk_fresh, "testXmlAtOrAfterSources": xml_fresh}
    build_logs = {}
    for key, filename in (("android", "android-build.txt"), ("meta", "metabuild.txt")):
        path = OUT / filename
        raw = read_text(path)
        success = bool(path.is_file() and re.search(r"BUILD SUCCESSFUL", raw) and not re.search(r"BUILD FAILED|FAILURE:", raw))
        build_logs[key] = {"status": "passed" if success else "missing_or_failed", "path": path.relative_to(ROOT).as_posix() if path.is_file() else None}
    artifacts_ok = all(x in artifacts for x in required_artifacts) and all(v["apkAtOrAfterSources"] and v["testXmlAtOrAfterSources"] for v in freshness.values()) and all(v["status"] == "passed" for v in build_logs.values())
    gate_keys = ("core_jvm", "app_jvm", "perception_jvm", "meta_jvm", "android35_app", "android30_app", "android35_google_speech", "android35_meta", "python")
    complete = all(tests[k]["status"] == "passed" for k in gate_keys) and road_ok and voice_ok and nav_ok and runtime_ok and speech_ok and color_ok and meta_ok and artifacts_ok
    value = {"schema": "sight.product-validation.v1", "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
             "status": "passed" if complete else "incomplete_or_failed", "tests": tests, "proofs": proofs,
             "artifacts": artifacts, "artifactFreshness": freshness, "buildLogs": build_logs,
             "artifactGate": "passed" if artifacts_ok else "missing_apk_stale_build_or_missing_build_log",
             "sourceHashes": "All files under app/src, core/src, perception/src and integrations/meta/adapter/src are SHA-256 hashed in artifacts.",
             "boundary": "Current product outputs only. No historical v3/emulator results are read. Android tests ran in emulators; fixtures, recorded HTTP and constructed GPS do not establish field use, physical microphone/GPS, independent held-out accuracy or human assistive usability."}
    (OUT / "summary.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(value, indent=2))
    raise SystemExit(0 if complete else 1)

if __name__ == "__main__": main()
