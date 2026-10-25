# Core policy replay and narration baseline

Constructed repeated-photo replay using real detector results; actual production Kotlin policy. No camera, audio playback, real-video tracking or user study claims.

The naive baseline emits one mention for every detection above .5 confidence on every frame. Full SIGHT core uses persistence, goal relevance, novelty and freshness gates. These are two real policy executions over the same fixture; no intermediate tracking/world-model ablation is inferred.

| Output | Measured count |
| --- | ---: |
| Frame events | 19 |
| Rejected frame events | 1 |
| Naive object mentions | 34 |
| Core object mentions | 3 |
| Core live candidate utterances (may group objects) | 2 |
| Confirmed find completions | 1 |

| Fixture check | Passed |
| --- | --- |
| stale_frame_rejected | True |
| no_live_utterance_from_stale_frame | True |
| input_outage_reported | True |
| confirmed_find_completed | True |
| unconfirmed_search_times_out | True |

Lower mention count demonstrates suppression on a repeated still image; it does not establish better user utility, recall of meaningful changes, participant task success or speech quality. The fixture injects stale timestamps, empty frames, source outage and commands. It has no real temporal identity labels and therefore cannot support IDF1, identity-switch or real-camera performance claims.

Evidence: `core-replay.jsonl`, `core-replay-summary.json`, `../replay/manifest.json`, and `../replay/real-detection-repeat.tsv`. Rebuild fixture with `evaluation/.venv/Scripts/python.exe evaluation/prepare_replay.py`, run the actual JVM `:core:replay` task as documented in evaluation/README.md, then run `evaluation/.venv/Scripts/python.exe evaluation/analyze_replay.py`.
