# Reproducible local evidence

SIGHT 3 adds a separate [180-photo detector experiment](results/v3-detection/report.md):50 model-selection photos,50 calibration photos and80 held-out photos, with recovered CC BY attribution, whole-creator scene grouping and near-duplicate checks. It first [reproduced the original 16](results/v3-baseline-reproduction/reproduction-check.json) exactly and [diagnosed their misses](results/v3-baseline-diagnostics/report.md). On the final 80, Lite2 INSPECT at its independently selected .55 threshold raised clean AP50 .5009 to .6040 and recall .3046 to .3394 while lowering FP/image .1875 to .1375. The slower model remains selective; the old Lite0 FAST path remains. Per-class/area counts, calibration regression, synthetic-condition regressions and uncertainty are preserved in the report. These are desktop detector measurements, not full-system task or physical-phone results.

After the environment/model setup below, use these commands for SIGHT 3. Selection/calibration outputs are sealed, so reproduction goes to a fresh output directory and cannot overwrite decisions behind the reported final-test claims:

```powershell
evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_v3_corpus.py
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py selection --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py calibration --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py held-out --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py --id paired-model-latency-v3 --output evaluation/results/v3-paired-latency-reproduction --model-ids efficientdet_lite0_int8,efficientdet_lite2_int8,ssd_mobilenet_v2_float32
evaluation/.venv-modern/Scripts/python.exe -m unittest discover -s evaluation/tests -v
```

`prepare_v3_corpus.py` verifies sealed hashes, restoring missing photos from pinned source URLs. It downloads only credited originals and the ranged annotation member if not cached. No unresolved-credit photos enter the new corpus. `v3_detection.py` uses real MediaPipe 1.1.1 CPU inference and official COCOeval, identical scoring for candidates, a .05 ranked-score floor/max100 for AP, separately capped deployment precision/recall, isolated per-model process memory and exact raw boxes. A fresh reproduction still uses the original fixed splits/rules; it is not permission to tune against the final 80. The current evidence also includes 12 passing Python checks.

The remaining sections preserve the smaller SIGHT 2 experiment and its commands as historical evidence.

This laboratory runs genuine desktop inference and records failures alongside successes. Its desktop metrics are separate from the Android camera pipeline. Native CameraX, detector and bundled OCR execution also passed on Android 11 and 15 emulators; see [device validation](../docs/device-validation.md). Physical phone power/thermal performance and participant usability remain unvalidated.

From the repository root on Windows:

```powershell
uv venv --python 3.12 evaluation/.venv-modern
uv pip install --python evaluation/.venv-modern/Scripts/python.exe -r evaluation/requirements-lock.txt
powershell -File scripts/fetch-models.ps1 -All
evaluation/.venv-modern/Scripts/python.exe evaluation/fetch_data.py
evaluation/.venv-modern/Scripts/python.exe evaluation/resolve_attribution.py
evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark.py
evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py
evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_ocr.py
powershell -File evaluation/run-windows-ocr.ps1
evaluation/.venv-modern/Scripts/python.exe evaluation/ocr_metrics.py evaluation/results/windows-ocr.jsonl
evaluation/.venv-modern/Scripts/python.exe -m unittest discover -s evaluation/tests -v
evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_replay.py
```

After Android/Gradle setup, run the core replay sequentially with builds; parallel Kotlin compilation can contend for incremental caches:

```powershell
./gradlew.bat :core:replay "-PreplayInput=evaluation/replay/real-detection-repeat.tsv" "-PreplayOutput=evaluation/results/core-replay.jsonl" --console=plain
evaluation/.venv-modern/Scripts/python.exe evaluation/analyze_replay.py
```

`fetch_data.py` downloads only the validation annotation member using HTTP byte ranges and 16 selected photographs. It refuses servers that ignore ranges; it never downloads the whole COCO image collection or training annotation set. Fixed SHA256-based sampling uses only original license metadata before inference. Exact file hashes and original Flickr links are in data/manifest.json, official boxes in data/instances_smoke.json. COCO Consortium annotations are CC BY 4.0. Photograph credits and licenses are in [data/ATTRIBUTION.md](data/ATTRIBUTION.md); eleven original creators were resolved, five originals no longer expose attribution. Those five photographs and their derivatives are retained locally for measured evidence and excluded from git until attribution is resolved.

Results:

- [Accuracy and sensitivity](results/desktop-coco-smoke-v2/report.md): actual CPU inference for two official MediaPipe models; official pycocotools AP50/AP50:95, operating precision/recall, raw detections, per-class metrics and annotated images. Six model/condition combinations, 16 images each, 80 timed calls each. Synthetic dimming and horizontal blur probe sensitivity; they are not low-light or video datasets.
- [Paired CPU timing](results/paired-clean-latency-v2/report.md): separate balanced real-inference run, shuffled image pairs and alternating model order; Windows affinity logical CPUs 0-3. Use these timings for the within-host comparison. Initial unbounded sweep timings varied strongly under host load and remain in the raw accuracy report for transparency.
- [OCR transcripts](results/windows-ocr.jsonl) and [CER/WER](results/ocr-metrics.json): actual Windows.Media.Ocr en-US, three clearly marked synthetic rendered typography samples and one real hand-transcribed tiny text crop. Synthetic text transcribed perfectly; the real `POLICE` crop returned empty text (CER/WER 1.0). This is a failure demonstration and metric harness, not evidence of app ML Kit quality. The combined corpus is dominated by synthetic text and should not be used as a headline accuracy score.
- [Constructed replay manifest](replay/manifest.json): genuine saved detections repeated with constructed timestamps, injected empty/stale frames, input outage and goal commands. The JVM runner executes the actual SIGHT core and naive per-detection narration baseline. Valid for duplicate narration, freshness, outage and task-policy checks. No IDF1, real-video tracking accuracy, audio playback latency or user-task success claim follows from repeated still photographs.

EfficientDet Lite0 int8 remains the CPU default candidate because it is smaller (4.60 MB) and has higher clean AP50/recall on this tiny sample. SSD had higher precision and better blur AP50 on this sample; the regression is preserved. In the current 1.1.1 paired run SSD is faster (P50 56.45 ms versus 64.92 ms); the original v1 ordering differed. Do not attribute cross-run timing differences solely to the runtime. These are limited engineering signals, not a statistically robust model ranking. The decision remains conditional on real phone profiling and a substantially larger, independent accessibility-oriented evaluation.

Future compatible runs can be checked with `python evaluation/compare_results.py --baseline <baseline-summary.json> --candidate <new-summary.json>`. The gate refuses changed dataset manifests or scoring settings, records every AP50/recall delta, and exits nonzero on drops beyond explicit tolerances (defaults .02 AP50 and .03 recall, absolute). Timing is deliberately excluded from that accuracy gate because host load, affinity and ordering require their own controlled comparison. These tolerances are development settings and have no validated safety meaning.

Not measured: IDF1/identity switches on labeled video, grounded VQA accuracy, human task-finding success, speech observation-to-audio delay, capture FPS, RAM, GPU/NPU use, battery, thermal throttling, navigation accuracy or participant usability. Their absence is deliberate; no values are manufactured. Collect these from consented controlled device trials using the hardware procedure in the main project documentation.

## Runtime upgrade and historical reproduction

Current v2 results use MediaPipe 1.1.1 and the current full Python lock. Both models ran again on exactly the original photos. All 12 declared AP50/recall regression checks passed; [every delta](results/runtime-upgrade-regression.json) remains recorded, including the dim-image decline. This gate has no validated safety meaning.

Original v1 accuracy and paired latency artifacts remain under their versioned directories. Their MediaPipe 0.10.21 environment is specified by `results/desktop-coco-smoke-v1/requirements-at-benchmark.txt`; it contains a documented historical protobuf vulnerability. Reproduction must use a separate legacy environment and explicit output paths so it cannot overwrite v2:

```powershell
evaluation/.venv/Scripts/python.exe evaluation/benchmark.py --config evaluation/config.json --output evaluation/results/desktop-coco-smoke-v1
evaluation/.venv/Scripts/python.exe evaluation/benchmark_latency.py --id paired-clean-latency-v1 --output evaluation/results/paired-clean-latency-v1
```

The runtime argument defaults now select v2. Keep the historical lock, measured outputs and runtime versions together. Current and legacy timing runs are separate experiments; do not attribute their difference solely to the runtime upgrade.
