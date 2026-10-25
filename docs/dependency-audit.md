# Dependency audit

`python scripts/audit_dependencies.py` queries OSV for pinned direct Maven coordinates and the full Python evaluation lock. The current recorded audit covers **41 package/version pairs with zero reported affected packages**. Its scope excludes resolved Maven transitives, native binary contents, operating systems and build plugins. Pillow was upgraded from 11.3.0 to 13.0.0 after findings. Decoded RGB inputs remained byte-identical for all 16 benchmark photographs; digests are recorded in `evaluation/results/pillow-before-rgb-sha256.json`.

The current Android and desktop runtimes use MediaPipe **1.1.1**. The current Python lock no longer includes protobuf. The archived MediaPipe 0.10.21 reproduction environment used protobuf 4.25.9, flagged for JSON recursion-depth bypass (GHSA-7gcm-g887-7qv7 / PYSEC-2026-1805). That historical finding remains in [the legacy audit](../evaluation/results/dependency-audit-legacy.json), alongside the original benchmark lock. Use `evaluation/.venv-modern` and the current lock for new work. Python packages are not packaged in the Android app.

The runtime upgrade was tested on the same held-out photographs and scoring configuration. All 12 AP50/recall comparisons passed the declared absolute-drop tolerances; individual regressions remain visible in [the comparison](../evaluation/results/runtime-upgrade-regression.json). This is an engineering regression gate, not safety validation.

The Gradle wrapper verifies the official Gradle distribution SHA256. The app verifies the packaged detector SHA256 at build time. Meta artifact integrity checks pin the three official 1.0.1 AARs. All direct versions are fixed. Updates should run the actual model, scene, safety and runtime regression suite before promotion.

Audit data: [current OSV results](../evaluation/results/dependency-audit.json). [Historical advisory](https://osv.dev/vulnerability/GHSA-7gcm-g887-7qv7). No claim that all transitive or bundled native libraries are free of vulnerabilities is made.
