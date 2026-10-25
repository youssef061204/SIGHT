# Desktop model smoke comparison

Real inference on 16 seed-selected COCO val2017 CC BY 2.0 photographs. Not a phone benchmark or assistive-use evaluation.

| Model | Variant | AP50 | AP50:95 | Precision @ .5 | Recall @ .5 | p50 ms | p95 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| efficientdet_lite0_int8 | clean | 0.362 | 0.211 | 0.750 | 0.194 | 225.06 | 368.53 |
| efficientdet_lite0_int8 | dim_035 | 0.354 | 0.213 | 0.811 | 0.149 | 220.81 | 263.52 |
| efficientdet_lite0_int8 | motion_blur_15 | 0.165 | 0.094 | 0.812 | 0.065 | 45.32 | 269.63 |
| ssd_mobilenet_v2_float32 | clean | 0.310 | 0.191 | 0.879 | 0.144 | 40.26 | 61.38 |
| ssd_mobilenet_v2_float32 | dim_035 | 0.270 | 0.181 | 0.967 | 0.144 | 32.51 | 191.08 |
| ssd_mobilenet_v2_float32 | motion_blur_15 | 0.197 | 0.124 | 0.857 | 0.060 | 221.32 | 396.02 |

Official pycocotools COCOeval, IoU .50:.05:.95, maxDets=100, score floor .05 for ranking. Operating precision/recall use .5 confidence and .5 IoU; crowd ignores follow COCOeval. AP averages only categories present in this small sample, so it must not be compared to full COCO AP. Scores are model confidence, not calibrated probabilities.

Timing includes detect() native preprocessing and postprocessing, excludes decode and Image creation. 5 warmups per model, 5 repetitions per image per variant = 80 calls/variant. Repeated same-image timings are correlated. P95 is exploratory and P99 is omitted. CPU default XNNPACK on Intel i7-14700HX, 20 cores/28 logical processors; thread count is runtime controlled. Concurrent compilation can add noise.

dim_035 and motion_blur_15 are synthetic sensitivity probes of real photographs, not labeled low-light or motion-capture datasets. Boxes are unchanged because transforms preserve image geometry. No thresholds or model weights were tuned on these images.

Reproduce from repository root: `evaluation/.venv/Scripts/python.exe evaluation/benchmark.py --config evaluation/config.json --output evaluation/results/desktop-coco-smoke-v1`. Full config, environment, raw detections, per-call latencies, per-class metrics and annotated frames are adjacent to this report. Dataset sources, licenses and SHA256 checks are in `evaluation/data/manifest.json`.

Historical runtime: MediaPipe 0.10.21; exact original lock: `requirements-at-benchmark.txt`. Current v2 is a separate rerun on 1.1.1.
