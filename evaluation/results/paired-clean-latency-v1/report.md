# Paired desktop CPU latency

Real inference; 16 original COCO photographs, five repetitions each (80 calls per model). Five unmeasured warmups per model. Shuffled image/repeat pairs; model order alternates within pairs. Windows CPU affinity fixed to logical CPUs 0-3 (`0xF`) to reduce scheduling variation. MediaPipe CPU/XNNPACK uses its default thread count, which Tasks does not expose. Intel Core i7-14700HX, Windows, Python 3.12, MediaPipe 0.10.21.

| Model | n | p50 ms | p95 ms |
| --- | ---: | ---: | ---: |
| efficientdet_lite0_int8 | 80 | 74.57 | 96.84 |
| ssd_mobilenet_v2_float32 | 80 | 85.86 | 113.82 |

P95 is exploratory (only 80 correlated calls/model); P99 omitted. Detect-call scope includes native resize/inference/postprocessing and excludes disk decode and mp.Image creation. Host load, caching and power policy still matter. This does not measure phone latency, capture FPS, energy, end-to-end speech or memory usage. Accuracy is in `../desktop-coco-smoke-v1/report.md`; compare latency only within this explicitly bounded run.

Reproduce: `evaluation/.venv/Scripts/python.exe evaluation/benchmark_latency.py --id paired-clean-latency-v1 --output evaluation/results/paired-clean-latency-v1`. Exact config and all raw latency samples are in `summary.json`.
