# Paired desktop CPU latency

Real inference; 16 original COCO photographs, five repetitions each (80 calls per model). Five unmeasured warmups per model. Shuffled image/repeat pairs; model order alternates within pairs. Windows CPU affinity fixed to logical CPUs 0-3 (`0xF`) to reduce scheduling variation. MediaPipe runtime thread count is left at its default. Intel Core i7-14700HX, Windows, Python 3.12, MediaPipe 1.1.1.

| Model | n | p50 ms | p95 ms |
| --- | ---: | ---: | ---: |
| efficientdet_lite0_int8 | 80 | 64.92 | 80.96 |
| ssd_mobilenet_v2_float32 | 80 | 56.45 | 72.51 |

P95 is exploratory (only 80 correlated calls/model); P99 omitted. Detect-call scope includes native resize/inference/postprocessing and excludes disk decode and mp.Image creation. Host load, caching and power policy still matter. This does not measure phone latency, capture FPS, energy, end-to-end speech or memory usage. Accuracy is in `../desktop-coco-smoke-v2/report.md`; compare latency only within this explicitly bounded run.

Reproduce: `evaluation/.venv-modern/Scripts/python.exe evaluation/benchmark_latency.py`. Exact config and all raw latency samples are in `summary.json`.
