# SIGHT 3 detector comparison

Measured desktop inference, not phone performance or an assistive-use validation. Baseline reproduction, model selection, calibration and final testing are separate artifacts. The original 16 photos reproduced byte-for-byte across all 6 prediction files and 30 reported metric checks; their AP50=.3614 and recall=.1940 were confirmed before improvements.

## Corpus and decisions

180 current, attributed CC BY COCO val2017 photographs (29.7 MB), 159 photographers; 50 model-selection, 50 calibration, 80 held-out. Entire creator groups and dHash <= 6 near-duplicate components stay together. Original 16 and their recovered creators are excluded. Splitting used image/author metadata only, before inference. See [manifest](../../data-v3/manifest.json) and [180 credits](../../data-v3/ATTRIBUTION.md). COCO annotations are CC BY 4.0. This conservative grouping reduces leakage, but cannot prove every scene is independent or that public validation images never influenced upstream model development.

Selection at confidence .50 chose Lite2: clean AP50 .6050 versus Lite0 .5150 and SSD .4757, while both precision and recall exceeded baseline. The separate calibration set chose confidence .55, using the predeclared precision>=baseline and bounded-FP/image rule. Calibration recall decreased from .2542 to .2401 while precision rose .9000 to .9444; this unfavorable tradeoff is retained. The selection and calibration decisions were sealed before held-out inference. Neither decision changed after seeing final results.

## Held-out results

| Condition | Policy | AP50 | AP50:95 | Precision | Recall | FP/image | TP / FP / FN |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| clean | FAST Lite0 .50 / cap20 | 0.5009 | 0.3033 | 0.9246 | 0.3046 | 0.1875 | 184 / 15 / 420 |
| clean | Lite2 .50 / cap20 | 0.6040 | 0.4158 | 0.9252 | 0.3891 | 0.2375 | 235 / 19 / 369 |
| clean | INSPECT Lite2 .55 / cap40 | 0.6040 | 0.4158 | 0.9491 | 0.3394 | 0.1375 | 205 / 11 / 399 |
| dim_035 | FAST Lite0 .50 / cap20 | 0.4205 | 0.2537 | 0.9382 | 0.2765 | 0.1375 | 167 / 11 / 437 |
| dim_035 | Lite2 .50 / cap20 | 0.5709 | 0.3849 | 0.9079 | 0.3427 | 0.2625 | 207 / 21 / 397 |
| dim_035 | INSPECT Lite2 .55 / cap40 | 0.5709 | 0.3849 | 0.9378 | 0.2997 | 0.1500 | 181 / 12 / 423 |
| motion_blur_15 | FAST Lite0 .50 / cap20 | 0.3077 | 0.1641 | 0.8776 | 0.2136 | 0.2250 | 129 / 18 / 475 |
| motion_blur_15 | Lite2 .50 / cap20 | 0.4316 | 0.2626 | 0.8966 | 0.2583 | 0.2250 | 156 / 18 / 448 |
| motion_blur_15 | INSPECT Lite2 .55 / cap40 | 0.4316 | 0.2626 | 0.9262 | 0.2285 | 0.1375 | 138 / 11 / 466 |

AP uses the same ranked score floor .05 / max100 detections and official pycocotools COCOeval IoU .50:.05:.95 with crowd/area ignores. Operating precision/recall use IoU .50 and the listed deployment thresholds/caps. AP is unaffected by which deployment threshold row is displayed. All rows use identical annotated photos and scoring. This is subset AP, not full COCO mAP; per-class operating counts and AP50 are in [raw comparison](held-out-comparison.json). Synthetic dim_035 and horizontal 15 px blur transform the same 80 photos; they add no independent scenes and are not real low-light/moving-camera datasets.

The chosen clean INSPECT operating point increases observed recall by 3.48 percentage points (21 additional true positives), raises precision 2.45 points and reduces FP/image by .05. Equal .50 threshold comparison isolates the model change: recall +8.44 points with four additional false positives across 80 photos. The stricter calibration threshold deliberately trades some of that recall for fewer false positives. Dim-image chosen-policy precision falls slightly and FP/image rises; no blanket robustness claim is made.

An exploratory 5000-resample paired creator/near-duplicate cluster bootstrap (62 groups) gives a 95% percentile interval of [0.0131,0.0568] for the chosen clean recall difference. It is not a guarantee of population improvement; details and raw group counts are in [bootstrap](operating-cluster-bootstrap.json).

## Small objects and class coverage

| Clean object area | Baseline TP / eligible GT | Recall | INSPECT TP / eligible GT | Recall |
| --- | --- | ---: | --- | ---: |
| small | 11 / 268 | 0.0410 | 17 / 268 | 0.0634 |
| medium | 79 / 199 | 0.3970 | 92 / 199 | 0.4623 |
| large | 95 / 137 | 0.6934 | 97 / 137 | 0.7080 |

Area matching follows COCO's area-ignore rules; boundary/ignore matching can make area-bin TP counts nonadditive. Small objects remain a major weakness. Clean held-out coverage: 604 nonignored instances; per-class evidence is sparse and category-level improvements/regressions must be read with their denominators. Full class metrics are available as [CSV](per-class-held-out.csv). Doors, stairs, curbs, crossings, keys and arbitrary categories remain unsupported. More pixels never supply missing class coverage.

| Relevant category | Eligible GT | Baseline recall | INSPECT recall | Baseline / INSPECT FP |
| --- | ---: | ---: | ---: | ---: |
| person | 176 | 0.494 | 0.500 | 4 / 2 |
| chair | 31 | 0.065 | 0.129 | 1 / 0 |
| dining table | 13 | 0.231 | 0.231 | 3 / 1 |
| couch | 2 | 0.500 | 0.500 | 0 / 0 |
| backpack | 10 | 0.100 | 0.000 | 0 / 1 |
| handbag | 9 | 0.000 | 0.000 | 0 / 0 |
| bottle | 12 | 0.000 | 0.000 | 0 / 0 |
| cup | 22 | 0.364 | 0.364 | 0 / 0 |
| car | 34 | 0.206 | 0.265 | 0 / 1 |
| bicycle | 1 | 0.000 | 0.000 | 0 / 0 |
| truck | 5 | 0.000 | 0.000 | 0 / 0 |
| bench | 1 | 1.000 | 1.000 | 0 / 0 |
| traffic light | 14 | 0.071 | 0.071 | 0 / 0 |
| stop sign | 0 | unmeasured | unmeasured | 0 / 0 |
| book | 24 | 0.000 | 0.000 | 0 / 0 |
| potted plant | 3 | 0.000 | 0.000 | 0 / 0 |

These are closed-set image detections, with no proof of identity, ownership or a navigable path. Several category results regress or have very small support; the aggregate gain does not imply every category improves.

## Runtime and selection consequence

| Model | Bytes | Paired desktop P50 / P95 ms | Whole-process warm RSS MiB |
| --- | ---: | ---: | ---: |
| efficientdet_lite0_int8 | 4602795 | 57.42 / 68.64 | 138.8 |
| efficientdet_lite2_int8 | 7515971 | 160.21 / 182.54 | 163.5 |
| ssd_mobilenet_v2_float32 | 11316189 | 53.21 / 61.62 | 152.9 |

Paired timing: same 16 legacy photos, 80 calls/model, 5 warmups, shuffled image/repeat order and alternating model order, i7-14700HX, Windows, MediaPipe 1.1.1 CPU, affinity logical CPUs 0-3. Runtime threads default. Three models coexist for timing. Warm RSS comes from separate per-model processes during the expanded accuracy run and includes Python/runtime/imports/image memory; it is not model-only memory and SSD uses selection rather than held-out. Raw [paired timings](../v3-paired-latency/summary.json) and per-model memory snapshots preserve scope. P95 is exploratory; P99 omitted. Current measurements cannot be compared causally to historical 64.92 ms because host load and experiment composition differ. Phone FPS, latency, thermals and battery require separate Android/device evidence.

Lite2 is materially slower, so FAST stays Lite0 for continuous updates. Higher-detail INSPECT is selective, with deadline/thermal fallback in the Android scheduler; it does not replace every camera frame. The Android implementation must be evaluated separately before claiming the policy's real-world task benefit. [Research rationale](model-research.md) records why this compatible, permissively sourced export was selected over an unvalidated new runtime.

## Reproduce

```powershell
evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_v3_corpus.py
evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark.py --config evaluation/config-v2.json --output evaluation/results/v3-baseline-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/diagnose_v3_baseline.py
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py selection
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py calibration
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py held-out
evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py --id paired-model-latency-v3 --output evaluation/results/v3-paired-latency --model-ids efficientdet_lite0_int8,efficientdet_lite2_int8,ssd_mobilenet_v2_float32
evaluation/.venv-modern/Scripts/python.exe evaluation/report_v3_detection.py
evaluation/.venv-modern/Scripts/python.exe -m unittest discover -s evaluation/tests -v
```

Corpus re-runs verify sealed photo hashes and restore missing originals from pinned URLs. Selection/calibration commands refuse to overwrite sealed decisions; to reproduce inference without changing decisions run `v3_detection.py worker --split selection --model <id>` (and calibration/held_out as needed), then compare new raw predictions to versioned evidence. Use --output evaluation/results/v3-reproduction on all three stage commands to reproduce in a fresh directory without altering sealed decisions. Do not delete decisions to tune final-test outcomes. Dataset and model hashes are recorded in every inference result; exact upstream artifacts are pinned in models/manifest.json.
