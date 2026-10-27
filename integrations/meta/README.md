# Optional Meta DAT camera and perception integration

This is an isolated Android library project using the official **Meta DAT 1.0.1** artifacts. It is intentionally outside SIGHT's phone application dependency graph. The phone APK does not initialize Meta, ask for Meta credentials, or offer a pretend glasses connection.

`MetaDatCameraAdapter` requests an actual DAT session, checks actual registration and camera permission, awaits session readiness, subscribes before starting the camera stream, and exposes a one-frame queue containing an owned copy of the SDK's uncompressed buffer. Pause/stop flush the queue. Frames carry the stream presentation timestamp, separate handset receipt time, and an explicit mock/glasses provenance label. No host-side dummy device implements the official SDK interfaces.

`MetaSightPerception` now converts explicitly calibrated packed I420 frames with SIGHT's shared `FramePixels`, executes the same shared `MediaPipeDetector`, and submits actual detections to the production `SightEngine`. A mock frame-calibration profile cannot authorize a physical glasses packet. Raw stream PTS and handset receipt time remain separate; a receipt-age check cannot certify capture freshness. Camera permission must be requested through a host Activity using `Wearables.RequestPermissionContract()`; this library never treats denial as success. See [the SIGHT 3.0 implementation and validation status](../../docs/meta-v3.md).

## Build and host checks

From the repository root after the main Android tooling setup:

```powershell
# Use ANDROID_HOME for your installed SDK, or the root local.properties.
# This workspace used .tools/android-sdk.
./gradlew.bat -p integrations/meta :adapter:testDebugUnitTest :adapter:assembleDebug :adapter:assembleDebugAndroidTest
```

`python integrations/meta/verify_artifacts.py` independently downloads the three official AARs into an ignored build directory and checks their recorded SHA-256 digests. It ran successfully against the 1.0.1 artifacts on 2026-10-23; it does not execute Android code.

The isolated project uses Kotlin 2.2.21 because DAT's published dependencies have newer Kotlin metadata than SIGHT's initial phone stack. Its `:core` and `:perception` projects use the same source directories as the phone, with separate build directories to prevent compilation-cache collisions. Library minimum SDK is deliberately 31, matching Meta's current sample; the downloaded DAT core AAR itself declares minimum 29.

## Official Mock Device Kit checks

The upgraded suite's **eight instrumentation tests passed on the API 35 AOSP ATD x86_64 emulator** on 2026-10-25, including all five preserved MDK contracts and three new conversion/inference/recovery checks. [Current raw result](../../evaluation/results/v3-android35/meta-suite.txt) records `OK (8 tests)` in 22.928 seconds. All eight adapter JVM tests also passed. The historical five-test pass on the API 35 Google APIs image remains in [the original raw result](../../evaluation/results/emulator-api35/meta-instrumentation.txt). On an attached, fully booted and unlocked Android API 31+ emulator or phone:

```powershell
./gradlew.bat -p integrations/meta :adapter:assembleDebugAndroidTest
./scripts/test-emulator.ps1 -Meta -Serial emulator-5558 -Adb .tools/android-sdk/platform-tools/adb.exe -Output evaluation/results/v3-android35/meta-suite.txt
```

Choose the actual serial from `adb devices -l`. `MetaMockDeviceContractTest` uses the real `mwdat-mockdevice` artifact for simulated pair/unpair, lack of registration, camera denial, registered-without-device handling and ordered camera-frame delivery/stop. The stream test feeds a [generated HEVC mathematical pattern](adapter/src/androidTest/assets/README.md) through the official MDK, verifies nonempty owned frame bytes and increasing presentation timestamps, and verifies that stop clears pending frames. This establishes a delivered **simulated file-fed stream**, not Bluetooth transport, physical glasses imaging, microphone audio or hardware reliability.

SIGHT 3.0 adds `MetaPerceptionIntegrationTest`: decoded color-chart values, a licensed photograph through actual RGB inference and the core Find workflow, and permission revocation followed by explicit session recovery. All three passed. [Actual color samples](../../evaluation/results/v3-android35/meta-color-chart-proof.json) and [actual detector/core output](../../evaluation/results/v3-android35/meta-real-pipeline-proof.json) are archived from the test host. Find completed on result four with actual person confidence 0.8008–0.8164; the first result was rejected by the lifecycle cutoff. Four short emulator samples measured 44.38–91.30 ms conversion and 456.64–1075.83 ms receipt-to-result; these are not sustained or physical-device performance claims. See [Meta 3.0](../../docs/meta-v3.md) for the initial failures, subsequent changes and limits. Physical-camera conversion still requires its own calibration record.

The adapter's default first-frame timeout is 20 seconds; the emulator stream test explicitly extends it to 60 seconds. These are deadlines, not measured latency. The initial emulator-camera feed failed to emit HEVC codec configuration; the passing file feed avoids claiming that the emulator camera path succeeded. [Initial failures](../../evaluation/results/emulator-api35/meta-initial-failures.txt) remain inspectable. Mock capture-clock synchronization is explicitly false. The separate API 30 emulator cannot run this API 31+ adapter.

A production host must supply its Wearables Developer Center application ID/client token, callback URI, foreground lifecycle, Bluetooth runtime permission flow, and user-visible registration controls. Developer Mode can use the documented `0` placeholders. Keep `mwdat-mockdevice` restricted to debug/instrumentation dependencies; do not ship simulated devices as a runtime fallback.

See [the readiness record](../../docs/meta-readiness.md) for evidence, current API references, and the hardware acceptance checklist.

The original five MDK tests were also executed successfully on API 35 AOSP ATD x86_64 before the overhaul. [Historical second raw run](../../evaluation/results/emulator-api35/atd-meta-instrumentation.txt).
