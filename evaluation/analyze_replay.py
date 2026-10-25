"""Summarize actual JVM output. Never replaces core execution with expected results."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--input", type=Path, default=ROOT / "results/core-replay.jsonl")
args = parser.parse_args()
records = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
summary = next(record for record in records if record["type"] == "summary")
events = [record for record in records if record["type"] == "event"]
stale = [event for event in events if "STALE_FRAME" in event["rejectedCodes"]]
outages = [event for event in events if event["event"] == "INPUT" and event["reliability"] == "VISUAL_INPUT_UNAVAILABLE"]
timeouts = [event for event in events if event["phase"] == "TIMED_OUT"]
checks = {"stale_frame_rejected": bool(stale),
          "no_live_utterance_from_stale_frame": bool(stale) and all(event["coreLiveUtterances"] == 0 for event in stale),
          "input_outage_reported": bool(outages), "confirmed_find_completed": summary["findCompletions"] >= 1,
          "unconfirmed_search_times_out": bool(timeouts)}
fixture = ROOT / "replay/real-detection-repeat.tsv"
output = {"summary": summary, "policy_checks": checks, "input_fixture": str(fixture.relative_to(ROOT)),
          "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(), "jvm_output_sha256": hashlib.sha256(args.input.read_bytes()).hexdigest(),
          "evidence_scope": "Constructed repeated-photo replay using real detector results; actual production Kotlin policy. No camera, audio playback, real-video tracking or user study claims."}
(ROOT / "results/core-replay-summary.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
lines = ["# Core policy replay and narration baseline", "", output["evidence_scope"], "", "The naive baseline emits one mention for every detection above .5 confidence on every frame. Full SIGHT core uses persistence, goal relevance, novelty and freshness gates. These are two real policy executions over the same fixture; no intermediate tracking/world-model ablation is inferred.", "", "| Output | Measured count |", "| --- | ---: |",
         f"| Frame events | {summary['frames']} |", f"| Rejected frame events | {summary['rejectedFrames']} |",
         f"| Naive object mentions | {summary['naiveObjectMentions']} |", f"| Core object mentions | {summary['coreObjectMentions']} |",
         f"| Core live candidate utterances (may group objects) | {summary['coreLiveUtterances']} |",
         f"| Confirmed find completions | {summary['findCompletions']} |", "", "| Fixture check | Passed |", "| --- | --- |"]
lines.extend(f"| {name} | {passed} |" for name, passed in checks.items())
lines += ["", "Lower mention count demonstrates suppression on a repeated still image; it does not establish better user utility, recall of meaningful changes, participant task success or speech quality. The fixture injects stale timestamps, empty frames, source outage and commands. It has no real temporal identity labels and therefore cannot support IDF1, identity-switch or real-camera performance claims.", "", "Evidence: `core-replay.jsonl`, `core-replay-summary.json`, `../replay/manifest.json`, and `../replay/real-detection-repeat.tsv`. Rebuild fixture with `evaluation/.venv/Scripts/python.exe evaluation/prepare_replay.py`, run the actual JVM `:core:replay` task as documented in evaluation/README.md, then run `evaluation/.venv/Scripts/python.exe evaluation/analyze_replay.py`."]
(ROOT / "results/core-replay-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps(output, indent=2))
if not all(checks.values()):
    raise SystemExit("Replay exposed a policy failure; inspect actual output instead of publishing a passing claim")
