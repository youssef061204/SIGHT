# SIGHT 3.0

An Android-first, offline-first visual companion. Camera observations become short-lived image tracks, evidence-backed scene state, goal-driven searches and selective speech. The app never infers traversable space from bounding boxes.

The existing native application has been upgraded in place. Read the [SIGHT 3 report](docs/engineering-report-v3.md), [updated architecture](docs/architecture-v3.md), [detector comparison](evaluation/results/v3-detection/report.md) and [demonstration](docs/demo-v3.md). The [SIGHT 2 report](docs/engineering-report.md) and its results remain historical evidence; baseline commit `f37f85d` preserves the original implementation.

The additional [YOLOX-Tiny evaluation](evaluation/results/v3-yolox/report.md) records a rejected candidate: its selection gains did not satisfy the independent calibration precision/recall gate, so it was not added to Android and its newly sealed final audit set remains unused.

## Build and install

Requirements: JDK 21, Android SDK platform 35 and build tools 35.0.0. Android Studio can install these. Set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=C:/path/to/Android/Sdk`. The included Gradle wrapper pins Gradle 8.11.1; no global Gradle installation is required.

```powershell
./scripts/fetch-models.ps1
./scripts/build.ps1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sight.app/.MainActivity
```

On macOS/Linux use `./gradlew`. `fetch-models.ps1` runs under PowerShell 7 on either platform. The shared perception library bundles checksum-verified Lite0 (4.60 MB) and selective Lite2 (7.52 MB) models. The native app requires Android 8/API 26 or newer. On-device voice commands additionally require Android 12/API 31 and an available on-device recognition service. Install an offline English TTS voice in Android settings. No network recognizer fallback is used.

MediaPipe Tasks Vision 1.1.1 packages ARM64, ARMv7, x86 and x86_64 native runtimes. The final SIGHT 3 APK passed all 12 native application checks on both Android 11 x86 and Android 15 x86_64 emulators, including actual CameraX, both detectors, bundled OCR, UI lifecycle and memory deletion. All eight project integration tests using the official Meta Mock Device Kit passed, including mock frames through real shared inference and Find. See [executed SIGHT 3 results](evaluation/results/v3-validation/summary.json), [actual screenshots](evaluation/results/v3-android35/README.md) and the [physical-device validation procedure](docs/device-validation.md). Physical phones and glasses remain untested. Older SIGHT 2 captures and results remain in their historical directories.

Two additional [native local-speech tests](evaluation/results/v3-android35-google/README.md) passed on the Google APIs image, producing nonempty PCM and verifying priority interruption with real engine callbacks. Audible speaker onset and microphone recognition were not measured.

## Use it

While stationary, start the rear camera and choose Explore, Find or Read. Descriptions use **left, center and right in the camera image**, which may differ from the direction the user faces.

Commands include:

- `explore`, `what is ahead`
- `what was on my left`, `what changed`, `what did you see a moment ago`
- `find a chair`, `find a bottle`, `find a backpack`
- `read everything`, `just tell me the price`, `find the expiration date`
- `read the second paragraph again` (returns OCR block two and discloses unverified paragraph layout)
- `remember this as my desk`, `where was my desk`, `where did you last see the chair`
- `cancel`, `resume`, `forget all`
- `inspect scene`, `not that one`, `find that same chair again`
- `destination Union Station`, `route overview`, `pause gps`, `recalculate route` (optional route consent and map-result selection remain required)

Find defaults to two fresh, sufficiently confident post-command observations, with configurable temporal confirmation. It continues after a match, reports loss and verifies reacquisition. Auto uses detailed inspection during search; Fast remains available. Small objects, bags and bottles remain weak despite aggregate detector gains. A blue backpack request is disclosed as unsupported because there is no validated color classifier. Keys, doors, elevators and generic signs are outside the deployed category set. Reading visible text is a separate asynchronous workflow. A saved scene name is the user's label, not a verified visual classification.

The Memory screen offers explicit encrypted persistence for future user-directed saves, deletion and historical recall. The Developer screen shows the actual camera, image graph, track confidence/history counts, goal, decision trace, stage timings, thermal status and bounded speech queue. Exporting diagnostics is a deliberate action and can include sensitive recognized/spoken text.

OCR runs locally. ML Kit can send SDK usage diagnostics to Google when connected; images and recognized text stay on device under its [published privacy terms](https://developers.google.com/ml-kit/terms). The app discloses this in onboarding and Settings. Airplane mode supports offline sessions; optional routing needs a connection.

## Reproduce the evidence

```powershell
uv venv --python 3.12 evaluation/.venv-modern
uv pip install --python evaluation/.venv-modern/Scripts/python.exe -r evaluation/requirements-lock.txt
./scripts/fetch-models.ps1 -All
evaluation/.venv-modern/Scripts/python.exe evaluation/prepare_v3_corpus.py
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py selection --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py calibration --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe evaluation/v3_detection.py held-out --output evaluation/results/v3-reproduction
evaluation/.venv-modern/Scripts/python.exe -m unittest discover -s evaluation/tests -v
```

See [evaluation instructions](evaluation/README.md) for replay, OCR scoring and ablations. Results include raw predictions, versioned configurations, SHA256 manifests, per-class metrics and annotated real photographs. Small-sample results must not be generalized to full COCO, phones or assistive use.

## Engineering package

- [Architecture and data boundaries](docs/architecture-v3.md)
- [Safety, privacy and accessibility](docs/safety-and-privacy.md)
- [Dependency audit and scope](docs/dependency-audit.md)
- [Physical-device acceptance procedure](docs/device-validation.md)
- [Research and model selection](docs/research.md)
- [Meta adapter and hardware gaps](docs/meta-v3.md)
- [Implementation status and next milestones](docs/engineering-report-v3.md)
- [SIGHT 3 dataset attribution](evaluation/data-v3/ATTRIBUTION.md) and [historical sample attribution](evaluation/data/ATTRIBUTION.md)

SIGHT has not been validated for independent mobility, collision prevention, road crossings, medical decisions or safety-critical reading. No blind-participant usability results or phone performance results are claimed. Use ordinary mobility aids and controlled, supervised testing.
