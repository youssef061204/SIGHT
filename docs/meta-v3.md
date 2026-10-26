# SIGHT 3.0 Meta camera integration

No physical Meta glasses are available in this workspace. The adapter remains an optional isolated Android library, outside the phone APK. Its native inference path now imports the same `:perception` library and `:core` sources used by the phone; it no longer ends at a raw-packet placeholder.

## Implemented path

Official MDK file feed -> DAT 1.0.1 HEVC decoder -> owned packed I420 packet -> explicit frame calibration -> shared RGB conversion -> shared MediaPipe detector -> production `SightEngine` -> evidence-grounded Find result.

The queue contains at most one unprocessed packet. Inference is serialized on a background dispatcher. Source generations invalidate results that were taken before a pause, stop, reconnect, or terminal failure. Session recreation checks registration and camera permission again. Registration and permission revocation are rechecked during the session, and SDK permission/disconnection errors have typed source states. An informational `STREAM_ERROR` is retained as diagnostics; it no longer tears down the source as if it were a critical error.

The new Meta path runs object detection and the shared goal/world-model logic. Phone-specific ML Kit OCR, microphone recognition, UI controls and speech output are not silently presented as glasses features. A host must still wire these explicitly before offering a full wearable Read or voice workflow.

`session.deviceInfo` supplies actual device type, compatibility, link state, battery and thermal metadata. `cameraAttached` is set only after the actual SDK attachment succeeds. Display capability follows the SDK device accessor. Camera success does not imply microphone support, frame calibration, reliable speech routing, or physical glasses availability.

## Format and orientation evidence

The [official camera guide](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/camera-streaming/SKILL.md) documents decoded YUV frames and compressed HEVC as separate configurations, but does not publish raw plane strides or orientation. The published 1.0.1 `mwdat-camera` binary's `VideoFormat.Companion.getDefaultFormat()` requests color format **19**, Android's [planar YUV420 constant](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#COLOR_FormatYUV420Planar). `VideoDecoder` writes that value into the decoder's `MediaFormat`. Public `VideoFrame` exposes no stride or rotation field. This is evidence for the pinned implementation, not a guarantee for every decoder or future SDK release.

`MetaFrameCalibration` therefore requires exact even-dimension packed I420 (`width * height * 3 / 2` bytes), explicit 0/90/180/270-degree rotation, explicit mirroring, and a calibration-record identifier. The converter's color contract is BT.601 limited range, explicitly encoded into both new fixtures. A mock profile cannot authorize physical glasses packets. Unsupported padding, odd dimensions, wrong provenance and pretend capture-clock synchronization fail closed. The default mock profile applies only to the explicitly generated zero-degree, unmirrored file fixtures. A physical host must independently validate chroma, color range and geometry before supplying its own calibration; there is no automatic claim of camera-to-head orientation or world pose.

Shared pixel tests cover conversion and coordinate geometry. The added official MDK color-chart test independently checks red/green/blue/white quadrants after the SDK's actual video decode. The known licensed person photograph then checks actual model output through the production Find workflow. [Fixture provenance and generation commands](../integrations/meta/adapter/src/androidTest/assets/README.md).

## Timestamps and metrics

The original stream `presentationTimeUs` is retained independently from handset `elapsedRealtimeNanos()` receipt time. Core `Evidence` uses `TimeBasis.RECEIPT` and the source `meta-dat-mock` or `meta-dat-glasses`. No raw device presentation time is relabeled as a handset exposure timestamp. Conversion time, inference time and receipt-to-result time are measured separately with the handset monotonic clock. Capture-to-receipt latency and exposure freshness remain unknown until hardware clock alignment is measured.

## Audio boundary

`MetaAudioInputPort` and `MetaAudioOutputPort` let a host connect microphone acquisition and its existing goal-aware TTS policy without coupling camera inference to an assumed audio route. `MetaAudioRouteProbe` reports only devices actually returned by Android's audio manager. It does not select a route or establish audibility. The adapter requests no microphone stream. Wearable PCM is [experimental and separately permissioned](https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/audio-streaming/SKILL.md); the official MDK has no deterministic PCM injection API. Physical microphone delivery, Bluetooth speaker routing and interruption remain hardware tests.

## Validation status

The original five official MDK tests are preserved. Four existing frame-gate tests are preserved. Four calibration-contract tests and three native integration tests were added. Added native scenarios are decoded color quadrants, a real detector/core Find result from the official mock photo feed, and permission revocation followed by explicit fresh-session recovery.

**Execution status:** new SIGHT 3.0 checks are authored and await the coordinated build/emulator run. Historical five-test passes are recorded in [Meta readiness](meta-readiness.md); they must not be confused with execution of the newly added tests. This section will be updated with actual results after validation.

Build from the repository root with the configured Android SDK:

```powershell
./gradlew.bat -p integrations/meta :adapter:testDebugUnitTest :adapter:assembleDebug :adapter:assembleDebugAndroidTest --max-workers=2 --console=plain
./scripts/test-emulator.ps1 -Meta -Serial emulator-5554 -Adb .tools/android-sdk/platform-tools/adb.exe -Output evaluation/results/v3/meta-instrumentation.txt
```

The isolated project assigns shared modules separate build directories to avoid mixing the phone's Kotlin compilation cache with the SDK's Kotlin 2.2.21 toolchain. The MDK artifact stays in `androidTestImplementation`; it cannot silently replace a physical device in production.

## Remaining device acceptance work

Configure real registration callbacks and user permission UI in a foreground host, pair through Meta AI, calibrate chroma/stride/rotation against a physical chart, measure device-to-phone clock drift and capture latency, test doff/fold/power/disconnection under sustained load, and validate speech with the person wearing the glasses. Test thermal/battery behavior and supervised accessibility separately. The optional library does not add a glasses connection button to the phone app or certify mobility guidance.
