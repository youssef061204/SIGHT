# SIGHT engineering report

Work and validation: 2026-10-23 through 2026-10-25 local; individual artifacts record UTC run times. This is an Android engineering build with executed emulator evidence. It does not certify assistive or mobility safety.

## A. What SIGHT actually does

SIGHT connects a rear CameraX source to local detection, short-lived image tracking, structured evidence, deterministic Explore/Find/Read goals, selective speech and an inspectable interface. Bundled ML Kit reads text locally. Commands can be typed or spoken through an available Android on-device recognition service. Explicit named scene snapshots can be saved with opt-in encryption.

The actual Android camera/inference/core/UI pipeline executed on Android 11 and Android 15 emulators, including pause/restart. Android 15 produced local speech PCM. The official Meta Mock Device Kit delivered ordered simulated frames to a separately compiled SDK transport adapter. These are executable integration results, distinct from physical phones, glasses or human-experienced assistance.

Optional routing uses real Photon search and FOSSGIS OSRM foot data. It offers provider-labelled rehearsal, foreground GPS progress checks, off-route hysteresis, explicit recalculation and user-confirmed arrival. Accessibility and crossing conditions are unverified.

## B. Implemented capabilities

| Capability | Implementation and boundary |
|---|---|
| Perception | EfficientDet Lite0 int8, checksum-pinned 4.60 MB model; actual desktop and Android inference. Bundled Latin-script OCR with preserved source text. |
| Evolving state | Bounded category/IoU image identities, histories, visibility, expiration and image-only spatial relationships. No metric world coordinates. |
| Goal-aware behavior | Explore/Find/Read state machines, two fresh post-command observations for Find, evidence requests, cancellation/resume and timeouts. Unsupported categories and colors are disclosed. |
| Selective speech | Confidence/persistence/novelty filtering, 1/3/5-object detail profiles, bounded queue, priority interruption, freshness and grounding checks before dispatch and at actual start. Live statements stop when grounding changes. |
| Reading | OCR transcript, price/date-like extraction, captured-text playback and historical block repetition. Layout and interpretation remain fallible. |
| Memory | Explicit named historical descriptions; optional Android Keystore AES-GCM storage, deletion, maximum 20 entries and 30-day retention. No photo archive, visual place matching or localization. |
| Conversation | Deterministic task/recall/read commands; offline-only platform recognition when available. No general multimodal LLM is claimed. |
| Accessibility | Labelled Compose controls, large targets, headings, scalable text, haptics and local speech. TalkBack and participant validation remain outstanding. |
| Observability | Actual preview, image graph, tracks, evidence source, goal, reasons, stage timings, speech queue, battery/thermal state and explicit JSON export. |
| Meta | Official DAT 1.0.1 session/permission/stream adapter and official MDK tests. Raw YUV packets still require validated conversion before RGB perception. |

Depth, VIO/SLAM, precise distances, stable room maps, open-vocabulary/color matching, segmentation, remote helpers, cloud vision, wearable speech/motion and all-day background streaming remain deferred.

## C. Technical architecture

The phone app uses Kotlin 2.1.20, JVM target 17, AGP 8.9.2, Gradle 8.11.1, Android compile/target 35 and minimum 26. Compose BOM 2025.04.01, CameraX 1.4.2, MediaPipe Tasks Vision **1.1.1** and ML Kit text recognition 16.0.1 are pinned. The separate Meta library uses Kotlin 2.2.21 and DAT 1.0.1. Native inference supports ARM64, ARMv7, x86 and x86_64.

CameraX keeps the latest pending image; one executor owns conversion/inference. Scheduling adapts to goal, power and thermal state. Evidence uses the exposure clock only when Camera2 declares a shared monotonic time basis; unknown clocks use explicitly reduced receipt-time evidence. The pure Kotlin engine rejects stale, future, out-of-order and pre-session inputs. An independent final admission gate cannot be bypassed by task logic.

Native testing exposed bitmap ownership: closing a MediaPipe MPImage recycles its supplied bitmap. The adapter now owns a copy, preserving the camera bitmap for OCR; repeated native inference tests check this contract. One outstanding OCR task owns its copy until completion, including after a timeout. OCR failure does not discard successful object detection, and a cooldown prevents task accumulation.

Baseline functions operate offline. Google ML Kit can send SDK usage diagnostics when connected; images and recognized output stay on device under its published terms. Onboarding and Settings disclose that behavior. Airplane mode supports offline sessions. Routing has a separate consent boundary and never consumes camera images. See [architecture](architecture.md), [privacy](safety-and-privacy.md), [routing](routing.md) and [research](research.md).

## D. Performance

Current accuracy and paired latency results use MediaPipe **1.1.1**. The policy replay uses archived v1 detector outputs and the production Kotlin engine. These are desktop measurements, not phone/glasses performance:

| Measurement | Result | Scope |
|---|---|---|
| EfficientDet clean AP50 / AP50:95 | 0.3614 / 0.2125 | 16 preselected COCO val2017 CC BY 2.0 photos, 38 represented categories. Not full COCO AP. |
| SSD MobileNet v2 clean AP50 | 0.3100 | Same held-out photos and official COCOeval. |
| EfficientDet precision / recall | 0.750 / 0.194 | Score >=0.50 and IoU >=0.50: 39 TP, 13 FP, 162 FN across 201 nonignored boxes. Low recall is a material limitation. |
| EfficientDet / SSD blur AP50 | 0.1748 / 0.1969 | Synthetic 15-pixel horizontal blur on the same photos; SSD performs better here. |
| Paired current-runtime latency | EfficientDet P50/P95 **64.92 / 80.96 ms**; SSD **56.45 / 72.51 ms** | 80 correlated detect calls per model, 16 photos repeated five times, alternating model order, Windows logical CPUs 0-3. |
| Production-core replay | 34 naive mentions versus 3 mentions in 2 grouped utterances | 19 constructed repeat-photo events from genuine detector output; 1 stale result rejected, 1 Find completion, all 5 declared policy checks passed. |

Host: Intel Core i7-14700HX, Windows, approximately 16 GB RAM, Python 3.12.15, CPU/XNNPACK default runtime thread configuration. The broad accuracy sweep ran under variable host load; its raw timings are retained but excluded from speed comparisons. A separately paired run records its own conditions and samples. P95 is exploratory; P99 is omitted.

[Current accuracy](../evaluation/results/desktop-coco-smoke-v2/report.md), [paired timing](../evaluation/results/paired-clean-latency-v2/report.md), [policy replay](../evaluation/results/core-replay-report.md) and [OCR evidence](../evaluation/results/ocr-metrics.json) retain raw results/configurations. Windows OCR probes are separate from Android ML Kit: three synthetic samples transcribed perfectly, while the real tiny POLICE crop returned empty text. This failure remains recorded.

SSD was faster in this current paired run and had higher operating precision and blur AP50. EfficientDet remains the conditional default because of its higher clean AP50/recall and smaller model (4.60 versus 11.32 MB). It does not dominate all metrics. The historical v1 timing ordering differed; do not attribute that change solely to a runtime upgrade.

The upgrade from MediaPipe 0.10.21 passed all 12 declared AP50/recall comparisons on identical photos/scoring. Individual declines, including dim-image AP50, remain in [the regression record](../evaluation/results/runtime-upgrade-regression.json). No cross-version speed attribution or safety conclusion follows from that gate. Original v1 metrics and environment locks remain archived.

## E. Validation

Executed evidence:

- Debug app/test APK assembly and Android lint: zero errors, 16 warnings.
- 35 core JVM tests, 15 app JVM tests and 4 isolated Meta JVM tests: 54 total, zero failures/errors.
- Nine Python tests for COCO scoring oracle cases, OCR metrics and regression guards.
- Android 15 Google APIs x86_64: 4 individually executed app tests, covering native detector/Find/freshness, bundled OCR, CameraX-to-UI pause/restart and offline TTS synthesis.
- Final APK on Android 15 AOSP ATD x86_64: 3 perception/CameraX tests passed; the official MDK also passed all 5 tests on this image.
- Android 11 AOSP ATD x86 without Google Play services: 3 detector/OCR/CameraX instrumentation tests.
- Official Meta Mock Device Kit on Android 15: 5 tests for pair/unpair, registration denial, camera denial, no-device state and ordered simulated HEVC frames with queue clearing on stop.
- Actual two-model desktop inference, paired timing, production Kotlin replay, a real public Toronto route-provider fixture and SDK artifact integrity checks.

See [collected validation](../evaluation/results/validation/summary.json), [Android evidence](device-validation.md), [Meta evidence](meta-readiness.md) and [screenshots](../evaluation/results/emulator-api35/assist.png). Native PCM synthesis is not a human listening test. Emulator camera scenes and generated MDK media are labelled simulated. Initial crashes/timeouts remain archived beside passing outputs. Combined Google APIs reruns encountered freshness timeouts alongside System UI ANRs during host memory pressure. A hung rerun was deliberately stopped. Final camera acceptance used AOSP ATD, with bytecode preparation and isolated execution recorded in the reproduction procedure. Freshness thresholds were not relaxed.

CI workflows are authored; no remote GitHub Actions execution is claimed. The dependency audit found no reported affected packages among 41 queried direct Maven/full Python-lock entries. It excludes resolved Maven transitives, native binary contents, OS and build plugins; see [scope](dependency-audit.md).

## F. Known limitations

Missing detections never imply object absence or a clear path. Two-frame agreement is not calibrated correctness. IoU identities can switch or fragment; there is no labelled-video tracking accuracy result. OCR layout, dates and prices can be wrong. Named memory is historical metadata, not a spatial map. Voice features depend on installed offline services. Activity recreation resets ephemeral controller state. Landmark expiry filters recall on load; expired encrypted records may remain until the next write or deletion.

No physical phone benchmark, phone power/thermal measurement, participant study, TalkBack validation, validated distance estimate, indoor navigation, road-crossing capability, safe-path certification or real glasses session exists. Optional public map services have usage limits and no availability guarantee. This universal debug APK is an engineering artifact, not a Play Store release.

## G. Hardware integration

Phone native components ran on two Android emulator architectures and three system images. Official Meta DAT 1.0.1 classes compiled and executed through the real MDK. The generated 320x240, 7 FPS HEVC test pattern established ordered raw transport and lifecycle behavior; it does not establish physical camera perception. The default first-frame deadline is 20 seconds; the emulator MDK test allows 60 seconds for cold decoding.

No Meta glasses were available. Real enrollment/pairing, YUV layout/rotation, camera-to-phone clock alignment, Bluetooth/audio routing, interruption/reconnection and sustained battery/thermal behavior remain hardware gates. Raw Meta packets are not connected to phone inference until conversion and capture freshness are validated. Experimental SDK modules and iOS execution remain deferred. See [Meta readiness](meta-readiness.md).

## H. Next major improvements

1. Run the controlled phone-camera/TTS/TalkBack acceptance protocol on representative physical devices, then validate the Meta conversion/clock/audio boundary on real glasses.
2. Establish independently annotated temporal object/text tasks with blind users and orientation-and-mobility specialists; measure false confirmations, missed relevant events, workload and task time.
3. Improve low recall and blur sensitivity through held-out mobile model comparisons, resolution/delegate profiling and uncertainty calibration.
4. Add labelled temporal tracking and genuine module ablations. Repeated still images cannot establish IDF1 or active-search utility.
5. Preserve ephemeral task state across supported Activity recreation and complete sustained-session permission, backgrounding, persistence and route/GPS acceptance.
