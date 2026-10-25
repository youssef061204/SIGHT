# Engineering source review

Retrieved **2026-10-23**. This is source research and architecture rationale. Vendor benchmark figures are not SIGHT measurements; SIGHT's executed experiment results belong in the evaluation artifacts.

## Practical first stack

Use Kotlin/Android, CameraX, a small bundled detector through MediaPipe Tasks, bundled Latin-script ML Kit OCR, a platform speech output service, and a pure Kotlin temporal scene/goal/reliability engine. This gives Windows-hosted builds and a real phone camera path without a server or wearable. Separate the camera source from inference, temporal state, policy, and output so other sensors can feed the same engine after their frame semantics are validated.

CameraX supports an analysis buffer that retains only the latest image while inference runs. Its blocking alternative can stall all bound camera use cases. SIGHT therefore uses latest-frame processing and explicitly closes every analyzed frame. This is the basis for bounded input work, not proof of bounded end-to-end speech latency. [Android image analysis](https://developer.android.com/media/camera/camerax/analyze).

## Detector and runtime candidates

| Candidate | Source-backed capability | Engineering decision |
|---|---|---|
| EfficientDet-Lite0 int8, MediaPipe | Bundled 320×320 COCO detector, bounded supported class vocabulary | Initial accuracy/size tradeoff; benchmark actual downloaded weights. |
| SSD MobileNetV2 float32, MediaPipe | Bundled 256×256 COCO detector | Measured comparator using the exact pinned model in the manifest; same temporal policy so model changes are isolated. |
| EfficientDet-Lite2, MediaPipe | Larger 448×448 input | Candidate for a later accuracy-first device tier; no assumption it meets thermal limits. |

Google supplies models and labels for these tasks and documents their tradeoffs. Its published task timings are from a Pixel 6 and cannot establish performance on this workstation or the user's phone. MediaPipe outputs categories, model scores and image boxes; it does not provide persistent physical-world identities or metric depth. [Official model/task guide](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector). Android Tasks supports image, timestamped video, and asynchronous live modes; SIGHT can use video mode behind its own single-worker scheduler so overload and timestamps stay explicit. [Android implementation guide](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android).

ONNX Runtime is a credible alternative when an ONNX detector or a measured execution-provider advantage justifies a second runtime. Its mobile documentation includes Android/iOS deployment and model optimization. Introducing it before demonstrating such an advantage increases binary and preprocessing work. [ORT Mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

YOLO11n is a reasonable exported-model comparator, although newer Ultralytics models are also advertised in the current docs. Its COCO classes do not implement arbitrary text-described targets. Ultralytics supplies AGPL and commercial licensing options, so do not silently adopt its model distribution terms for a product. This review does not supply a legal determination or a SIGHT YOLO benchmark. [Official YOLO11 model documentation](https://docs.ultralytics.com/models/yolo11/).

## OCR and measurement

Bundled ML Kit text recognition provides its model at installation; the Play Services variant downloads the model dynamically. Bundling supports first-run offline reading at an app-size cost. Accuracy still depends on focus, resolution, script, and orientation. Preserve extracted text as evidence and label any interpretation separately. The current detailed prerequisites say API 23+, even though the page's generated summary mentions 21; the implemented phone minimum exceeds both. [ML Kit Android text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

Measure cold initialization, warm/steady inference, memory and full capture-to-policy timing separately. Google exposes on-device benchmark tools and explains that standalone benchmark timings can differ from an app's timings. Desktop runs are useful for real model execution and comparative experiments; they do not predict phone battery use or establish accessible real-world task completion. [LiteRT measurement guidance](https://ai.google.dev/edge/litert/performance/measurement).

## Spatial boundary

ARCore combines visual features and inertial measurements for phone pose estimation; this requires a real AR session and tracking quality management. Image-box association alone does not provide a persistent room map. [ARCore fundamentals](https://developers.google.com/ar/develop/fundamentals).

ARCore Depth is device-dependent and must be explicitly enabled. It combines depth from motion with supported hardware depth input, where available; texture, motion and range affect the result. No metric-depth or collision-clearance claim is enabled in this slice. Before enabling one, validate pose/depth timestamp alignment, sensor calibration, uncertainty and failure behavior on target hardware, then conduct a separate supervised assistive-use evaluation. [ARCore Depth](https://developers.google.com/ar/develop/java/depth/overview).

## Meta source review

DAT's latest accessible published version was verified as **1.0.1**, not assumed from the request's 1.0 reference. The implementation and the limits of this verification are recorded in [Meta readiness](meta-readiness.md). A transport adapter is not a validated mobility system or a tested glasses product.
