# SIGHT 3 engineering report

Work date: 2026-10-25. The existing application was upgraded in place from preserved commit `f37f85d`. This is experimental assistive software. Executed evidence and its limits take precedence over feature names or targets.

## A. What changed

Phone and Meta now share a production `perception` library, including checksum-pinned Lite0/Lite2 models, frame conversion and actual MediaPipe inference. CameraX retains only its latest pending frame; one worker owns adaptive detection/model switching. Bundled OCR runs independently with one retained native task, preserving its original capture evidence rather than blocking detection or assigning old text to a new image.

Motion-assisted image association, score history, configurable confirmation and bounded temporal metadata replace overlap-only short tracks. Current visibility and historical memory stay separate. Find continues through loss/reacquisition, accepts corrections and requests fresh inspection. The world model supports grounded earlier-side and change queries; image relationships never become invented physical support or distance.

Speech now has an independently tested bounded priority policy, duplicate suppression, cooldowns, interruption and checks at dispatch/start/continuation. The retained Android session preserves task intent across Activity recreation while stopping sensors in the background. The UI adds independent tab scrolling, global sensor state, large-font layout, model controls, historical visibility and genuine runtime diagnostics. Optional provider routing is preserved, with voice place search and explicit no-fix feedback.

See [architecture](architecture-v3.md), [walkthrough](demo-v3.md) and [Meta integration](meta-v3.md).

## B. What actually works

Executed JVM results: **58 core + 7 pixel + 30 app + 8 Meta = 103 tests**, with no failures/errors/skips. The original 35 core and 15 app tests remain present; the exact baseline checkout passed them before changes. Twelve Python checks pass. Tests cover stale/future/out-of-order input, geometry/model/source discontinuity, async OCR provenance, goal cancellation, confidence, deletion/expiration, continuing Find, ambiguous identity, supported relationships, routing parsing/provider failures and speech silence/interruption. Test counts describe coverage, not assistive reliability.

The [versioned validation manifest](../evaluation/results/v3-validation/summary.json) records the native suites, APK hashes and exact completion status; per-run Android artifacts supplement these JVM results. Historical SIGHT 2 emulator passes are not counted as SIGHT 3 execution.

## C. Before versus after

The original 16 photos reproduced **all six prediction files byte-for-byte and all 30 reported metric values**, including AP50 .3614 and recall .1940. That establishes reproduction, not broad accuracy. The expanded comparison uses 180 credited photographs from 159 photographers, split before inference into 50 selection / 50 calibration / 80 held-out images. Original creators and close perceptual duplicates are excluded or grouped.

| Identical held-out 80 photographs | Original FAST Lite0 .50 | Selective INSPECT Lite2 .55 |
|---|---:|---:|
| AP50 | .5009 | .6040 |
| AP50:95 | .3033 | .4158 |
| Operating precision | .9246 | .9491 |
| Operating recall | .3046 | .3394 |
| True / false positives | 184 / 15 | 205 / 11 |
| False positives per image | .1875 | .1375 |
| Small-object recall | .0410 | .0634 |

These are subset COCO metrics, not full COCO scores or real-world user outcomes. The stricter calibrated threshold improves held-out recall 3.48 percentage points while reducing false positives. Its calibration recall was worse (.2401 versus .2542); that regression is retained. At an equal .50 threshold, Lite2 recall was .3891 with 19 false positives, showing the precision/recall tradeoff. FAST's current cap40 versus historical cap20 changes none of these saved operating detections: every image had at most ten above-threshold proposals.

In a separate paired desktop run, Lite0 P50/P95 was **57.42/68.64 ms**, Lite2 **160.21/182.54 ms** (80 calls/model, same photos/order policy, i7-14700HX, CPU affinity 0–3). These are synchronous native detector calls, not Android capture or speech latency. The stronger model therefore remains selective.

Constructed labelled box trajectories isolate the temporal policy: overlap-only association had five identity switches and IDF1 .671; motion-assisted association had one and .865. Missing observations remained 14/84, false completions and stale speech admissions were zero in these fixtures. Continuing Find verified eight initial/return events versus four with continuation disabled. These are synthetic component ablations, not real-video improvements or a full six-stage detector/system trial. [Raw trajectories and report](../evaluation/results/v3-temporal/report.md) state this boundary explicitly.

## D. Machine learning improvements

The baseline diagnostic experiment checked scaling/aspect ratio, manual letterboxing, channels, rotation, clipping, thresholds, NMS/caps, class mapping, quantization and annotation difficulty. All 82 small objects in the original sample were missed at .50. Wrong orientation damaged AP; adding manual letterboxing before MediaPipe also regressed. The shipped adapter preserves the SDK's own resize/reprojection and adds independent known-pixel JVM/native tests. Lowering confidence indiscriminately increased false positives.

Three real TFLite candidates were compared. Lite2 won on the selection split; .55 was chosen on separate calibration before final testing. Official SSD MobileNetV2 was faster but weaker in selection accuracy. A fourth candidate, official Apache-2.0 YOLOX-Tiny ONNX, passed selection but failed calibration: preserving precision required .75, yielding recall .2514 versus baseline .2542. It is not bundled. Its independently sealed additional 80-image holdout remains unused after rejection. [YOLOX evidence](../evaluation/results/v3-yolox/report.md) includes the failed gate and archived contended timings.

The deployed pair still has 80 COCO categories. It adds no doors, stairs, curbs, crossing recognition, color/identity classifier or open-vocabulary reasoning. On the clean held-out set, inspection detected no backpacks (0/10), handbags (0/9), bottles (0/12) or books (0/24). Aggregate gains do not justify advertising improved bag/bottle search. Dim-image precision regressed slightly. Synthetic dimming/blur add conditions, not independent scenes. [Full comparison](../evaluation/results/v3-detection/report.md), [per-class CSV](../evaluation/results/v3-detection/per-class-held-out.csv), licenses and raw predictions are retained.

## E. Real-world usability

Explore, continuing Find, captured-text reading, short-term recall, explicit encrypted landmarks and real provider routes remain available. Recognized text has no invented ML Kit confidence; image detail/exposure feedback is heuristic and permits bounded fallback attempts so a white document cannot be permanently vetoed. Repeat preserves evidence. There is no word-level audio resume cursor or independently evaluated document-layout understanding.

Automated UI checks target labelled states, heading semantics, touch dimensions, independent scrolling, double font size, Activity recreation and sensor lifecycle. They cannot establish nonvisual task success or human TalkBack focus quality. No physical phone/glasses or blind-participant sessions were available. Foreground GPS field accuracy, voice-service availability, environmental sound masking and practical object-search success remain unvalidated. Metric depth, safe-path/crossing decisions and collision avoidance remain disabled.

## F. Android performance

Runtime evidence belongs to the actual Android run, separately from desktop benchmarks. The Android tests record cold model initialization, warm fixed-photo detection samples, process PSS and live CameraX stage/counter traces. A fixed-photo call is not camera FPS; PSS is whole-process memory. Capture-to-completion is reported only with a declared shared exposure clock; otherwise timing starts at receipt. TTS request and engine-start callbacks remain separate from unmeasured audible speaker onset. P99 is withheld for small samples. No emulator result establishes physical-phone battery or sustained thermal behavior.

The host is Windows on an Intel i7-14700HX with 16 GB RAM. Android emulator configurations and native measurements are retained alongside [validation](../evaluation/results/v3-validation/summary.json). Resource-constrained runs are serialized with Gradle daemons stopped before emulation. The app retains its strict 1.5-second live age gate, including on a slow VM.

## G. Meta glasses readiness

The isolated DAT 1.0.1 adapter builds against the shared production core/perception modules; eight JVM tests pass. The native suite preserves all five original MDK tests and adds SDK-decoded color-chart conversion, actual photograph → RGB → real detector → Find, and permission-revocation/reconnect checks. Raw proof files and executed native status are recorded in validation.

The explicit mock calibration accepts only packed I420, BT.601 limited, known orientation and unsynchronized receipt-time evidence. Real SDK frames do not expose enough public metadata to assume that calibration. Physical glasses optics, color/layout/orientation, capture-clock calibration, disconnect behavior, battery and audio routing still require device-only validation. Audio ports and Android route discovery are implementation scaffolding, not glasses microphone/speaker proof. No real glasses hardware result is claimed.

## H. Remaining weaknesses

The largest weakness remains missed small/common objects and unsupported categories, followed by uncertain image identity under camera motion and occlusion. The measured detector gain is useful but modest at the conservative operating point. Real annotated video, consented field recordings, physical Android latency/energy tests and accessibility participant trials are the next highest-impact work. General VQA, open-vocabulary grounding, calibrated depth and wearable audio need independent evidence before activation.

The full six-stage system study, population task-completion/abstention rates, real-video IDF1, calibrated audible-onset latency, battery cost and human TalkBack flows are **not achieved results**. The present detector study, deterministic regressions and bounded synthetic ablations answer narrower questions. The [42-package direct dependency audit](../evaluation/results/v3-dependency-audit.json) found no OSV advisories at query time; it excludes resolved Maven transitives, native contents, OS and build plugins and is not a full security assessment.
