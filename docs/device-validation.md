# Android runtime evidence and physical-device acceptance

## Executed boundary

The development host is Windows with an Intel Core i7-14700HX, approximately 16 GB RAM and Temurin JDK 21. Android runtime tests executed on three WHPX-backed emulator images: **API 35 Google APIs x86_64**, **API 35 AOSP ATD x86_64** and **API 30 AOSP ATD x86**. The Android MediaPipe runtime is `tasks-vision:1.1.1`, which was exercised on both emulator architectures. No physical phone or Meta glasses were available. No person hearing the audio, Android microphone recognition, TalkBack session, participant study or physical-device reliability is claimed.

| Executed Android check | API 35 Google APIs x86_64 | API 30 AOSP ATD x86 | Established boundary |
|---|---|---|---|
| `NativePerceptionTest` | [2 passed](../evaluation/results/emulator-api35/native-perception.txt) | [2 passed](../evaluation/results/emulator-api30/native-perception.txt) | Real bundled MediaPipe detection on a credited photograph feeds the production two-frame Find workflow; stale speech admission is rejected. Real bundled ML Kit reads generated text and retains its original OCR result. |
| `CameraFlowTest` | [1 passed](../evaluation/results/emulator-api35/camera-flow.txt) | [1 passed](../evaluation/results/emulator-api30/camera-flow.txt) | CameraX emulator acquisition, native inference, fresh accepted core state and Developer UI frame progression, including pause/restart and continued accepted frames. |
| `NativeSpeechTest` | [1 passed](../evaluation/results/emulator-api35/native-speech.txt) | Not executed | Installed local English Android TTS synthesized a nonempty WAV containing a nonzero PCM signal. This is synthesis evidence, not a human listening result. |
| Official Meta MDK contract | [5 passed](../evaluation/results/emulator-api35/meta-instrumentation.txt) | Not applicable: adapter minimum API 31 | Official simulated registration/permission/no-device behavior and ordered generated-HEVC frame delivery with stop clearing. See [Meta readiness](meta-readiness.md). |

The API 35 speech test selected `en-us-x-tpf-local`; its [metadata captured from adb](../evaluation/results/emulator-api35/speech-proof.json) reports `synthesized: true`, `networkRequired: false` and **183,930 WAV bytes**. [The test](../app/src/androidTest/java/org/sight/app/NativeSpeechTest.kt) checks RIFF/WAVE headers, more than 1,000 PCM payload bytes and a nonzero signal. An `OK` instrumentation result alone would be insufficient because the test explicitly skips when no usable offline voice is installed. The WAV remained in the emulator cache and was not copied into this repository. No speaker output, intelligibility, Bluetooth routing or environmental-audio masking was measured.

These emulator checks execute real Android native components. The camera source is emulated; the detector smoke photograph and generated OCR text are fixtures. The independent desktop laboratory uses licensed photographs and the production JVM engine. Neither boundary substitutes for the physical scene, accessibility and sustained-session acceptance below, and the component passes do not establish a human-experienced camera-to-speech session. Initial Android/Meta failures remain alongside the passing raw outputs for inspection.

The final APK passed [all three tests on API 30](../evaluation/results/emulator-api30/final-suite.txt) after the last source change. It also passed [all three perception/CameraX tests on API 35 AOSP ATD](../evaluation/results/emulator-api35/atd-final-suite.txt), and the official MDK passed [all five tests there](../evaluation/results/emulator-api35/atd-meta-instrumentation.txt). Device fingerprints are retained in `atd-device.json` and the API 30 `device.json`. AOSP ATD has no installed offline English TTS voice; the separate synthesis result above belongs to the Google APIs image.

Later combined Google APIs reruns encountered freshness timeouts alongside System UI ANRs. One hung rerun was deliberately stopped; its `Process crashed` line is an interrupted test, not a passing run. These logs remain in the results directory. The final AOSP camera run retained the same 1.5-second freshness limit. No definite app clock arithmetic defect explained the Google-image failure; a separate startup clock-provenance race was fixed by using one timing-basis snapshot per frame.

Actual [Assist](../evaluation/results/emulator-api35/assist.png), [Developer preview](../evaluation/results/emulator-api35/developer.png) and [image graph](../evaluation/results/emulator-api35/developer-graph.png) screenshots were captured from the AOSP emulator. ATD normally disables drawing; after instrumented checks, `adb shell setprop debug.hwui.drawing_enabled true` enabled rendering, and `wm size 540x1200` / `wm density 210` set the screenshot viewport. The preview is Android's simulated virtual scene. UI hierarchy XML and a single emulator memory snapshot are adjacent. Neither screenshot nor that memory snapshot is a physical-phone benchmark.

## Reproduce emulator checks

Build the application and instrumentation APKs, then run against a booted, unlocked emulator. Select the actual serial using `adb devices -l`:

```powershell
./scripts/build.ps1
./gradlew.bat --stop
./scripts/test-emulator.ps1 -Serial emulator-5554 -Adb .tools/android-sdk/platform-tools/adb.exe -Classes "org.sight.app.NativePerceptionTest,org.sight.app.CameraFlowTest,org.sight.app.NativeSpeechTest" -Output evaluation/results/emulator-api35/app-instrumentation.txt

# API 30 has no speech synthesis result in the recorded evidence.
./scripts/test-emulator.ps1 -Serial emulator-5556 -Adb .tools/android-sdk/platform-tools/adb.exe -Classes "org.sight.app.NativePerceptionTest,org.sight.app.CameraFlowTest" -Output evaluation/results/emulator-api30/app-instrumentation.txt
```

On a memory-limited host, stop build daemons and run one emulator at a time. The script wakes/unlocks the screen and verifies installed bytecode before starting the suite. For the final API 35 AOSP run, use serial `emulator-5558` and the two perception/camera classes, writing `atd-final-suite.txt`.

Pass the comma-separated `-Classes` value as one argument; individual classes may also run separately to reproduce the linked per-suite outputs. The API 35 serial was `emulator-5554`; select the current API 30 serial rather than assuming the example `emulator-5556` is present. Camera tests keep the emulator awake and exercise actual app controls; their waits accommodate emulator startup rather than establishing a latency target. To reproduce the project's separate suite against the official Meta MDK, build its Android test APK and use the same script with `-Meta`, as documented in [Meta readiness](meta-readiness.md).

## Install

Connect a consenting Android device via USB with developer options and USB debugging enabled. Run from the repository root:

```powershell
adb devices -l
./scripts/build.ps1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.sight.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n org.sight.app/.MainActivity
```

Native tests use a credited COCO smoke image and generated OCR text, explicitly distinct from held-out evaluation. The emulator passes above verify those component behaviors; rerun them on each physical target before the controlled acceptance trials. Save the instrumented test output with device model/API, APK/model SHA256 and time. A local voice may be absent on a physical device; treat an assumption skip as unavailable speech capability rather than successful synthesis.

## Golden slice acceptance

Test while stationary with a sighted facilitator. Use a controlled tabletop/room; never traffic, stairs or independent walking. Include known supported objects with varied orientation and distance. Annotate the visible objects independently before judging detection output.

1. Review onboarding using TalkBack. Verify headings, focus order, all buttons, 200% font size, portrait/landscape and navigation without seeing the screen.
2. Deny the camera permission. Verify a clear unavailable state and recovery; then grant it and start the rear camera.
3. Use Explore. Confirm the physical camera drives observed tracks, frame counters and selective speech. Confirm left/right refers to the image, not device/user heading. Compare Developer image boxes with scene arrangement.
4. Hold a supported object stable. Find it. Check at least two fresh post-command observations precede success. Cover/remove it and confirm live claims stop; no previous result may be spoken as current.
5. Request an unsupported object or attribute, for example keys or a blue backpack. Confirm the limitation is disclosed without fabricated success.
6. Pause/resume repeatedly, background/foreground, revoke permission and interrupt capture by opening another camera app. Check new frame IDs are accepted, old tracks are not reused across pauses, no stale callbacks stop a newly started session and no background camera remains active.
7. Force task cancellation while inference/OCR runs. Verify bounded work and no delayed success. Confirm timeout says 'not detected', without asserting absence.
8. Read a printed sign and price/date label. Compare original OCR text character-for-character against transcription. Test two columns, blur, glare and empty frames. Preserve the failed attempts as well as successes.
9. Verify an OCR reading longer than 1.5 seconds finishes as captured text. Live scene narration must stop if its grounding changes. Verify repeat explains expired live observations; `repeat text` labels history.
10. Test offline speech with Wi-Fi/mobile data off. Verify the selected TTS voice is local. Test on-device recognition availability, microphone denial, cancel, timeout and unsupported devices. Do not silently permit cloud fallback.
11. Save a named scene with persistence off; restart and confirm it is gone. Enable persistence, explicitly save, restart and inspect the dated historical description. Delete it and confirm session/persistent recall are removed. Test expiration and Keystore failure/reinstall behavior without claiming recovery of encrypted data.
12. Enable route services deliberately. Inspect the transmitted query/coordinates and absence of camera uploads. Rehearse a provider route without moving. Inject supervised GPS traces to check stale/inaccurate fixes, off-route hysteresis, recalculation and user-confirmed arrival. Disable consent and verify requests/GPS cease. Do not infer an accessible sidewalk from OSRM foot data.

## Measurements

Export diagnostics from Developer only after explicit consent. Record lighting, objects/annotations, camera orientation, model, app version, device, battery start/end, thermal state, screen and audio settings. Per-stage metrics are conversion, detector and OCR durations; label any total as analyzer-receipt-to-result unless an exposure clock is verified. Exported speech events include their evidence timing basis. CameraX-dropped frame counts are unavailable and must not be inferred from scheduler-skipped counts.

Use enough independent frames/trials for the intended statistic. Report P50/P95 with sample counts; omit P99 for short runs. Repeated processing of the same frame is not independent real-world performance data. Compare identical conditions and order-balanced runs for optimization. Preserve accuracy while changing runtime delegates or quantization.

For memory and system diagnostics:

```powershell
adb shell dumpsys meminfo org.sight.app
adb shell dumpsys thermalservice
adb shell dumpsys battery
adb shell screencap -p /sdcard/sight-validation.png
adb pull /sdcard/sight-validation.png evaluation/device-runs/
```

Screenshots may contain sensitive surroundings/text. Collect only consented scenes, retain the source/timestamp and delete temporary phone captures when no longer needed. No screenshot should substitute for an inference/event trace.

Use a controlled idle baseline and a sustained session on the same phone for battery/thermal experiments. Record wall duration and start/end charge; do not claim NPU utilization, joules or battery mWh unless actually measured with appropriate tools. Add at least two representative devices before selecting final mobile inference settings.

## Specialist and hardware gates

Blind/visually impaired participants and orientation-and-mobility specialists should set success criteria before any mobility study. The initial scope is object/text assistance. Meta hardware validation additionally requires enrollment, pairing, official permissions, verified frame format/time, interruptions, thermal/battery/session duration, publication eligibility and physical audio behavior. See [Meta readiness](meta-readiness.md). Mock-device success never establishes physical reliability.
