"""Summarize saved Android measurements; performs no inference or device operations.

Run from any directory: python evaluation/analyze_android_v3.py
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "evaluation/results"
OUTPUT = RESULTS / "v3-android-runtime-summary.json"


def number(value, label):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError(f"{label}: expected a finite nonnegative measurement, got {value!r}")
    return value


def percentiles(values):
    """Nearest rank: sorted[ceil(p*n)-1], including all supplied observations."""
    ordered = sorted(number(value, "sample") for value in values)
    if not ordered:
        return {"n": 0, "p50Ms": None, "p95Ms": None}
    return {
        "n": len(ordered),
        "p50Ms": ordered[math.ceil(.50 * len(ordered)) - 1],
        "p95Ms": ordered[math.ceil(.95 * len(ordered)) - 1],
        "minMs": ordered[0],
        "maxMs": ordered[-1],
    }


def read_input(path):
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-8-sig")), {
        "path": path.relative_to(ROOT).as_posix(),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def process_memory(kib):
    return {"processPssKiB": number(kib, "processPssKb"),
            "processPssMiB": round(kib / 1024, 6),
            "scope": "Whole-process PSS snapshot, not model-only memory or peak memory"}


def stage_statistics(stages):
    return {
        "receiptToResult": percentiles(stage["totalMs"] for stage in stages),
        "detection": percentiles(stage["detectionMs"] for stage in stages),
        "conversion": percentiles(stage["conversionMs"] for stage in stages),
    }


def summarize_run(name):
    fixed, fixed_ref = read_input(RESULTS / name / "inspection-runtime-v3.json")
    camera, camera_ref = read_input(RESULTS / name / "camera-runtime-v3.json")
    if fixed["androidApi"] != camera["androidApi"]:
        raise ValueError(f"{name}: fixed-photo and camera API levels disagree")

    profiles = []
    for profile in fixed["profiles"]:
        stats = percentiles(profile["samples"])
        # Verify the producer's saved statistics instead of silently reporting a different method.
        for key in ("p50Ms", "p95Ms"):
            if profile[key] != stats[key]:
                raise ValueError(f"{name}/{profile['profile']}: saved {key} disagrees with raw samples")
        profiles.append({
            "profile": profile["profile"], "modelId": profile["modelId"],
            "initializationMs": number(profile["initializationMs"], "initializationMs"),
            "unmeasuredWarmupCalls": number(profile["warmups"], "warmups"),
            "timedCalls": stats,
            **process_memory(profile["processPssKb"]),
        })

    stages = camera["stages"]
    if not stages or stages[0]["frameId"] != 1:
        raise ValueError(f"{name}: source-start result is absent; cannot label its exclusion")
    first, last = stages[0], stages[-1]
    previous = None
    for stage in stages:
        for field in ("totalMs", "detectionMs", "conversionMs", "initializationMs"):
            number(stage[field], field)
        if stage["completedAtMs"] - stage["receivedAtMs"] != stage["totalMs"]:
            raise ValueError(f"{name}: frame {stage['frameId']} receipt interval disagrees with totalMs")
        if previous is not None and (stage["frameId"] <= previous["frameId"] or
                                     stage["completedAtMs"] < previous["completedAtMs"]):
            raise ValueError(f"{name}: stages are not ordered")
        previous = stage

    seconds = (last["completedAtMs"] - first["completedAtMs"]) / 1000
    counter_fields = ("cameraFrames", "analyzerFrames", "analyzed", "skipped")
    first_counters = {field: number(first[field], field) for field in counter_fields}
    last_counters = {field: number(last[field], field) for field in counter_fields}
    deltas = {field: last_counters[field] - first_counters[field] for field in counter_fields}
    if any(value < 0 for value in deltas.values()):
        raise ValueError(f"{name}: counters reset within a purported single-source window")
    rates = {field: round(deltas[field] / seconds, 6) if seconds > 0 else None
             for field in counter_fields}

    return {
        "run": name, "androidApi": fixed["androidApi"],
        "hardware": fixed["hardware"], "model": fixed["model"],
        "inputs": [fixed_ref, camera_ref],
        "fixedPhotoProfiles": profiles,
        "liveCamera": {
            "source": camera["source"], "declaredTimestampBasis": camera["timestampBasis"],
            "detectorMode": camera["detectorMode"],
            "modelsObserved": sorted({stage["model"] for stage in stages}),
            "includingFirstSourceStartResult": stage_statistics(stages),
            "excludingOnlyFirstSourceStartResult": stage_statistics(stages[1:]),
            "firstSourceStartResult": {field: first[field] for field in (
                "frameId", "receivedAtMs", "completedAtMs", "initializationMs", "conversionMs",
                "detectionMs", "totalMs", "width", "height", "rotation", "geometryEpoch")},
            "counterWindow": {
                "basis": "First retained source-start completion to last retained completion",
                "firstCompletedAtMs": first["completedAtMs"],
                "lastCompletedAtMs": last["completedAtMs"], "durationSeconds": seconds,
                "firstCounters": first_counters, "lastCounters": last_counters,
                "counterDeltas": deltas, "deltaPerSecond": rates,
                "counterMeaning": {"cameraFrames": "Camera capture callbacks",
                                   "analyzerFrames": "Analyzer deliveries",
                                   "analyzed": "Completed detector calls",
                                   "skipped": "Scheduler skips"},
                "cameraXDrops": "Unknown; callback-minus-analyzer counts are not a drop measurement",
            },
            "memory": process_memory(camera["processPssKb"]),
            "thermalStateAtExport": camera["thermalLevel"],
            "cachedUiSummary": camera["runtimeSummary"],
            "cachedUiSummaryScope": "May precede final stage; statistics above use raw stages, not this text",
        },
    }


def main():
    summary = {
        "schema": "sight.android-runtime-summary.v3",
        "scope": "Saved Android 15 x86_64 and Android 11 x86 emulator measurements",
        "method": {
            "percentiles": "Nearest rank: ascending sample at ceil(p*n)-1; no interpolation",
            "fixedPhoto": "One licensed photograph repeated; three warmups excluded, initialization separate",
            "liveIntervals": "Analyzer receipt to processing completion, using declared receipt clock",
            "sourceStartExclusion": "Remove frame 1 only; any later initialization remains included",
            "rates": "Counter delta divided by elapsed first-to-last completion time",
        },
        "limits": [
            "No P99 estimates from these short samples",
            "No speech latency or audible-onset measurement is derived",
            "Receipt timing does not establish exposure-to-receipt delay or capture freshness",
            "Cold timing alone is not proof that a particular result was rejected by the core",
            "Fixed-photo calls are not live camera FPS; whole-process PSS is not model-only memory",
            "Different AVDs are not a controlled OS or ABI comparison",
            "No physical-phone performance, battery, sustained thermal or assistive-use claim",
        ],
        "runs": [summarize_run(name) for name in ("v3-android35", "v3-android30")],
    }
    OUTPUT.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(OUTPUT.relative_to(ROOT).as_posix())
    for run in summary["runs"]:
        live = run["liveCamera"]["includingFirstSourceStartResult"]["receiptToResult"]
        print(f"API {run['androidApi']}: {live['n']} live stages, receipt-to-result "
              f"P50/P95 {live['p50Ms']}/{live['p95Ms']} ms including source start")


if __name__ == "__main__":
    main()
