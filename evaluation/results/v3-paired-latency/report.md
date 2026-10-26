# Paired desktop CPU latency

Real inference; 16 original COCO photographs, five repetitions each (80 calls per model). Five unmeasured warmups per model. Shuffled image/repeat pairs; model order alternates within pairs. Windows CPU affinity fixed to logical CPUs 0-3 (`0xF`) to reduce scheduling variation. MediaPipe runtime thread count is left at its default. Intel Core i7-14700HX, Windows, Python 3.12, MediaPipe 1.1.1.

| Model | n | p50 ms | p95 ms |
| --- | ---: | ---: | ---: |
| efficientdet_lite0_int8 | 80 | 57.42 | 68.64 |
| efficientdet_lite2_int8 | 80 | 160.21 | 182.54 |
| ssd_mobilenet_v2_float32 | 80 | 53.21 | 61.62 |

P95 is exploratory (only 80 correlated calls/model); P99 omitted. Detect-call scope includes native resize/inference/postprocessing and excludes disk decode and mp.Image creation. Host load, caching and power policy still matter. This does not measure phone latency, capture FPS, energy, end-to-end speech or memory usage. Expanded accuracy is in [SIGHT3 detector report](../v3-detection/report.md); compare latency only within this explicitly bounded run.

Reproduce: `evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py --id paired-model-latency-v3 --output evaluation/results/v3-paired-latency --model-ids efficientdet_lite0_int8,efficientdet_lite2_int8,ssd_mobilenet_v2_float32`. Exact config and all raw latency samples are in `summary.json`.
