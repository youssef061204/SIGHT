# SIGHT

SIGHT is an Android visual companion with provider-backed walking directions, on-device object finding, scene descriptions, text reading, and user-initiated offline voice commands. It is experimental assistive software, not an independent mobility aid. It does not decide when a road is safe to cross or whether a path is clear.

## What works now

- Search real destinations, select a numbered match, request a walking route, and receive route progress from fresh GPS fixes while SIGHT stays open. Route services are opt-in. Directions come from public map data; sidewalk access, crossings, entrances, and current conditions are not verified.
- Explore a live camera view, find supported detector categories, read captured text, ask bounded questions about recent image observations, and optionally save encrypted landmarks. Camera descriptions refer to left, center, and right in the image.
- Start one voice command on demand. Android on-device recognition is preferred; bundled offline English recognition is available on supported configurations. There is no wake word or continuous background listening. Volume Up starts a command and Volume Down stops assistance while the app is open.
- Request a road-scene diagnostic. Automatic road voice alerts are **off**: the balanced 40-image held-out set measured 17 TP, 1 FP, 3 FN, 19 TN and 0.025 false events/image, above the 0.02 limit. Strict road-pixel IoU was 0.6448. Road pixels are not crossing or hazard clearance evidence; see the [balanced road evaluation](evaluation/results/pedestrian-balanced/report.md).

The Android UI has Assist, Routes, Memory, Settings, and Developer areas. App backgrounding pauses camera, microphone, and GPS. See the [product engineering report](docs/product-engineering-report.md), [architecture](docs/product-architecture.md), and [walkthrough](docs/product-demo.md) for implementation status and evidence.

## Build and install

Requirements: JDK 21 and Android SDK platform 35 / build tools 35.0.0. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=C:/path/to/Android/Sdk`.

```powershell
./scripts/build.ps1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sight.app/.MainActivity
```

The build script fetches checksum-verified model assets and the offline voice model before running project checks and assembling the debug APK. For detailed setup, reproducible evaluations, and emulator commands, see [evaluation/README.md](evaluation/README.md) and [docs/product-demo.md](docs/product-demo.md).

## Evidence and limits

The SIGHT 3 detector study reported AP50 0.6040 and operating precision 0.9491 / recall 0.3394 on a fixed 80-image held-out COCO subset. These are historical benchmark results, not full-dataset, phone, or assistive-use accuracy. Current local JVM checks pass (core 67, app 59, perception 12; 138 total). New native/Meta and lint validation is still being finalized. Its expected evidence entry is [evaluation/results/product-validation/summary.json](evaluation/results/product-validation/summary.json) (pending until generated).

No physical phone, actual glasses, microphone recognition, human TalkBack usability, blind-participant usability, battery endurance, or outdoor navigation study is claimed. Keep using ordinary mobility aids and established crossing practices. SIGHT can miss objects and cannot establish clearance or safe crossing.

## Project and publication

Planned public repository: [github.com/youssef061204/SIGHT](https://github.com/youssef061204/SIGHT), prerelease `public-preview` (publication status to be verified). Planned demonstration location: `docs/media/sight-demo.mp4` (pending; no video is included yet). Links to screenshots and reports are included only where the corresponding repository artifact exists.

## Preserved project history

SIGHT was upgraded in place. Historic reports and artifacts remain available: [SIGHT 3 engineering report](docs/engineering-report-v3.md), [SIGHT 3 architecture](docs/architecture-v3.md), [SIGHT 3 demonstration](docs/demo-v3.md), [SIGHT 2 engineering report](docs/engineering-report.md), and [detector evaluation](evaluation/results/v3-detection/report.md). These preserve their original names and metrics as historical evidence.
