# Meta integration evidence and remaining work

Research date: **2026-10-23**. No Meta glasses were available. No physical pairing, glasses camera inference, wearable microphone audio, or validated navigation result is claimed.

## Verified release and provenance

The official Android and iOS changelogs identify **1.0.1 (2026-10-09)** as their latest listed version. Android Maven metadata independently reports release/latest `1.0.1`. Android dependencies can be fetched from Maven Central without credentials. iOS's public GitHub tag `1.0.1` resolves to `0bd369298eef6a980ad0bdb6c8a5502f2c02a08c`; no iOS build ran on this Windows host. [Android changelog](https://github.com/facebook/meta-wearables-dat-android/blob/main/CHANGELOG.md), [iOS changelog](https://github.com/facebook/meta-wearables-dat-ios/blob/main/CHANGELOG.md), [Maven metadata](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/maven-metadata.xml).

The Android source snapshot inspected was `f4371bc9f90249d51eb07d7f8a61e8d829294f16`. Its README publishes `com.meta.wearable:mwdat-core:1.0.1`, `mwdat-camera:1.0.1` and `mwdat-mockdevice:1.0.1`. These are real AARs; their public class signatures were examined with `javap`. The core AAR declares Android minimum API 29; this isolated adapter uses 31 to match the official sample. SDK analytics and crash capture default on, so the optional library manifest explicitly opts out of both. [Official Android repository](https://github.com/facebook/meta-wearables-dat-android).

| Downloaded AAR | SHA-256 |
|---|---|
| mwdat-core 1.0.1 | `9d3c72f7e273c793418f8e86f68cf0d85b4ab2a4dfff3c964d304275224ba5fc` |
| mwdat-camera 1.0.1 | `2a0a69fb89e533d627ace09abb1f4c367cd9599d69891b14ab32ca5bd2638bdc` |
| mwdat-mockdevice 1.0.1 | `9cd18f7e8a197a49879bd986cbceff820db7b0b2ece678aeb3dd063586135661` |

The supplied [Toolkit overview](https://developers.meta.com/wearables/device-access-toolkit/) was reachable and now advertises camera, motion, speech, displays and input. The supplied [FAQ](https://developers.meta.com/wearables/faq/) timed out twice. Linked Developer Center guides/API pages returned a login wall or were inaccessible to the browser tool, so specific API decisions are based on the accessible official GitHub guides, current sample, changelogs and actual published binary signatures. Do not interpret a login wall as a lack of that capability.

For a later iOS port, pin Swift Package Manager to the `1.0.1` tag; that tag contains the actual `Package.swift`, although `main/Package.swift` returned 404. Its products include `MWDATCore`, `MWDATCamera`, `MWDATMockDevice` and `MWDATMockDeviceTestClient`, plus optional display/input/motion/speech binaries. The current setup guide calls for Xcode 26.4+, Swift 6.3+ and iOS 17.2+. This host has not linked or run those binaries. [Tagged package manifest](https://github.com/facebook/meta-wearables-dat-ios/blob/1.0.1/Package.swift), [official iOS setup guide](https://github.com/facebook/meta-wearables-dat-ios/blob/main/plugins/mwdat-ios/skills/getting-started/SKILL.md).

## Adapter boundary and current APIs

The isolated [Meta library](../integrations/meta/README.md) imports actual SDK classes. `Wearables.initialize(context)` returns `DatResult`; registration and DAT camera permission are checked before `Wearables.createSession(AutoDeviceSelector())`. The adapter observes session state/errors before calling `session.start()`, then calls `session.addCamera(StreamConfiguration(...))` only after `DeviceSessionState.STARTED`. `camera.stream` owns video/state/errors. A stopped session must be recreated. [Official lifecycle guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/session-lifecycle/SKILL.md).

`DeviceSession.addStream()` was removed in 0.9.0; copying older examples would be wrong for 1.0.1. The current bridge requests uncompressed medium video at 7 FPS, subscribes before `stream.start()`, owns copied bytes, drops superseded queued packets, and clears packets on pause/stop. `VideoFrame` provides `buffer`, dimensions and `presentationTimeUs`. Compressed HEVC input is explicitly rejected because no decoder is configured. Pixel layout, image rotation and capture-clock synchronization remain unvalidated, so raw packets are deliberately not wired into SIGHT's phone RGB source. [Official camera guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/camera-streaming/SKILL.md).

Frame presentation time belongs to the device stream; handset `elapsedRealtimeNanos()` is a separate clock. The gate checks ordering and time waiting locally. A freshly received packet may still represent delayed capture; `captureClockSynchronized` remains false. No geometric fusion or precise live-age guarantee crosses that boundary.

The host must implement Meta AI registration, URI callback, Android Bluetooth permissions, and DAT `RequestPermissionContract` camera requests. Developer Mode accepts documented `0` app ID/token placeholders. Production requires credentials and settings from Wearables Developer Center. SDK download access does not establish permission to use a real device. [Official registration/permission guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/permissions-registration/SKILL.md).

## Capability availability

| Capability | Current official evidence | SIGHT status |
|---|---|---|
| Video frames | Camera/Stream API in 1.0.1 | SDK transport adapter; hardware conversion/latency unverified. |
| In-stream still capture | `Stream.capturePhoto()` | Not implemented in SIGHT adapter. |
| Standalone high-quality photos | Experimental `Camera.photo` | Deferred. |
| Wearable PCM audio | Experimental `Stream.audioStream` plus non-null `audioCodec` | Deferred. |
| Speech recognition / Hey Meta invocation | Experimental speech and voice invocation APIs | Deferred; no supported invocation claim in this app. |
| Inertial/orientation samples | Experimental motion module | Deferred; no glasses-based odometry or depth. |
| Display/input | Model-dependent display API; experimental inputs | Deferred; no glasses-display experience. |

The 1.0.0 changelogs flag motion, speech, voice invocation, standalone capture and camera audio as experimental, and say apps using experimental features cannot yet be published. This is distinct from testing those APIs. Do not promise production availability from the marketing overview. [Android release notes](https://github.com/facebook/meta-wearables-dat-android/blob/main/CHANGELOG.md).

Wearable PCM requires approved camera/audio capability and both camera and microphone permission; rates include 16, 44.1 and 48 kHz. The official guide explicitly says MDK does not expose deterministic audio-frame injection, so successful mock stream negotiation cannot establish delivered PCM. Bluetooth speaker/TTS routing must be tested separately from wearable microphone acquisition. [Official audio guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/audio-streaming/SKILL.md).

Motion samples can contain null sensor/orientation values. Their nanosecond timestamps use a device monotonic clock. The official module supports accelerometer/gyro/magnetometer/orientation with configurable rates, but it does not establish a camera-to-IMU calibration, stable world pose, or depth. [Official motion guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/motion/SKILL.md).

## Validation record

| Check | Evidence/status |
|---|---|
| Current artifacts and API signatures | Downloaded and inspected, as recorded above. |
| Adapter compilation / host tests | Official SDK bridge compiled; debug AAR assembled. Four host-JVM ordering/freshness/lifecycle gate tests passed, zero failures/errors. JUnit XML: `integrations/meta/adapter/build/test-results/testDebugUnitTest/TEST-org.sight.meta.MetaFrameGateTest.xml`. |
| Official Mock Device Kit | **Five instrumentation tests passed** on the WHPX-backed API 35 Google APIs x86_64 emulator: official simulated pair/unpair, unregistered gating, denied camera permission, registered-without-device handling, and ordered camera-frame delivery/stop. [Raw result](../evaluation/results/emulator-api35/meta-instrumentation.txt), [test source](../integrations/meta/adapter/src/androidTest/java/org/sight/meta/MetaMockDeviceContractTest.kt). |
| Mock camera streaming | Official MDK delivered nonempty frames with increasing presentation timestamps from a generated HEVC file feed. The test verified mock provenance, unsynchronized capture-clock metadata, `STOPPED` state and an empty pending-frame slot after stop. This is simulated SDK transport/decoder/lifecycle evidence; raw frames are not yet connected to SIGHT inference. |
| Real Meta glasses | Not tested; no hardware available. |
| iOS | Release researched; no implementation/build claimed. |

The official kit supports `enable()`, `pairGlasses(GlassesModel.RAYBAN_META)`, power/unfold/don lifecycle, permission simulation and camera media feeds. A handset-camera mock feed needs the handset's separate Android camera permission. The initial emulator-camera feed failed to emit the HEVC codec configuration; the passing test instead uses a four-second, 320×240, 7 FPS mathematical FFmpeg `testsrc2` pattern encoded with libx265. It is generated media, not recorded glasses footage. The [fixture provenance and generation command](../integrations/meta/adapter/src/androidTest/assets/README.md) are checked in. Initial failures remain in [the raw failure record](../evaluation/results/emulator-api35/meta-initial-failures.txt). [Official MDK guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/mockdevice-testing/SKILL.md).

The adapter's default first-frame deadline is **20 seconds**. The simulated-stream instrumentation case explicitly allows **60 seconds**, with a 70-second outer state wait, to accommodate emulator startup. These are test deadlines, not measured device latency. The five-test passing run took 16.8 seconds overall; it does not measure per-frame latency, Bluetooth reliability, or session endurance. The Meta adapter requires API 31+, so this suite was not run on the separate API 30 emulator.

Executed on Windows with Adoptium JDK 21.0.9, Gradle 8.11.1, Android platform 35, AGP 8.9.2 and Kotlin 2.2.21:

```powershell
$env:ANDROID_HOME = (Resolve-Path .tools/android-sdk).Path
& ./.tools/gradle-8.11.1/bin/gradle.bat -p integrations/meta :adapter:testDebugUnitTest :adapter:assembleDebug :adapter:assembleDebugAndroidTest
python integrations/meta/verify_artifacts.py

# Boot the API 35 AVD and wait for Android to finish booting before this command.
./scripts/test-emulator.ps1 -Meta -Serial emulator-5554 -Adb .tools/android-sdk/platform-tools/adb.exe -Output evaluation/results/emulator-api35/meta-instrumentation.txt
```

The script installs the library instrumentation APK and runs `org.sight.meta.test/androidx.test.runner.AndroidJUnitRunner`. To select only the official MDK class, add `-Classes org.sight.meta.MetaMockDeviceContractTest`. Choose the actual serial from `adb devices -l`; `emulator-5554` identifies the API 35 run in this session. The device must be fully booted and unlocked. A detected `adb` transport alone does not establish that Android's package service is ready.

These executed Android checks establish simulated stream delivery in addition to compilation and host policy. They do not demonstrate a physical glasses stream, real Bluetooth pairing, glasses microphone acquisition, or speech heard through glasses.

The same official MDK suite also passed all five tests on the API 35 **AOSP ATD x86_64** image: [raw output](../evaluation/results/emulator-api35/atd-meta-instrumentation.txt). This second simulated run does not validate physical Bluetooth or camera conversion.

## Required hardware acceptance work

1. Extend the passing API 35 official MDK suite with permission revocation during streaming, reconnect and pause/resume scenarios. Repeat on a representative API 31+ handset. Preserve the existing generated-HEVC ordered-delivery, denial, no-device and stop assertions, and label every feed as simulated.
2. Configure a real Wearables Developer Center app or documented Developer Mode, callback and runtime permission flow. Pair through the supported Meta AI procedure; test explicit cancellation and unavailable companion app.
3. Establish YUV layout, RGB conversion, portrait/landscape and front-facing geometry against a color/rotation chart. Only then adapt raw packets to the common inference input.
4. Measure capture-to-receipt and receipt-to-inference latency, clock drift, dropped frames, Bluetooth/Wi-Fi interruption and 20+ minute thermal/battery behavior. Verify pause/doff/fold and background/foreground transitions.
5. Verify TTS routing, interruption, mono operation, hearing environmental audio, user-visible recording state, and screen-reader focus. Evaluate future PCM/speech/motion modules separately under their publication restrictions.
6. Re-run the same perception/goal/safety regression scenarios with glasses input, followed by supervised accessibility research. Mock or compile success does not establish real-device or assistive-use reliability.
