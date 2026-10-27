# Reproducible SIGHT 3 demonstration

Build from the repository root with JDK 21 and Android SDK 35:

```powershell
$env:ANDROID_HOME = (Resolve-Path '.tools/android-sdk').Path # or your installed SDK
./scripts/fetch-models.ps1
./gradlew.bat :core:test :perception:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=1 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sight.app/.MainActivity
```

Use a stationary, supervised setup and ordinary mobility aids. Start camera explicitly. In Explore, observe a supported large object from two frames; empty detections are not evidence of a clear path. In Developer inspect the real model, freshness clock, frame counters and image tracks. Moving between tabs should keep the camera session; leaving the app pauses camera, microphone and GPS. Return to Assist to restart explicitly.

Enter `find chair`, keep the target visible for confirmation, move it out of view and return it promptly. A continued image-track hypothesis can require reacquisition; it does not prove the same physical chair. Two similar chairs can remain ambiguous. `not that one` excludes the current candidate; `inspect scene` requests a bounded higher-resolution inspection. Settings offers Auto/Fast/Inspect, with resource and latency fallback. Do not infer Find success for a bottle or bag from aggregate model accuracy: held-out class results remain poor.

Read a clearly lit printed phrase with `Read text`. The transcript belongs to its original captured frame. Poor exposure or low detail can produce positioning feedback. Use `just tell me the price`, `repeat text` or `read the second paragraph again`; block order is not verified document structure. Moving the camera while OCR completes must not relabel old text as current object evidence. Pausing/restarting or changing the goal invalidates old read requests.

After a confirmed observation, try `what was on my left`, `what changed` and `what did you see a moment ago`. Replies distinguish history from current visibility. Explicitly `remember this as my desk`; persistence remains opt-in and encrypted. Delete all memories and verify that pre-deletion track history is not restored by subsequent frames.

For optional routing, open Routes and enable provider services before submitting a place name. Voice/typed `destination Union Station` searches actual public map data; choose the result yourself, select a start and load the walking route. `route overview` rehearses provider instructions. GPS needs separate permission and sufficiently accurate, fresh fixes; `pause gps` stops it. Route instructions and visual observations remain separate sources and do not establish accessible or physically safe travel.

Run native checks on an available Android emulator:

```powershell
./scripts/test-emulator.ps1 -Serial emulator-5558 -Adb .tools/android-sdk/platform-tools/adb.exe -Classes 'org.sight.app.PixelPipelineTest,org.sight.app.NativePerceptionTest,org.sight.app.InspectionRuntimeTest,org.sight.app.CameraFlowTest,org.sight.app.SessionUiTest,org.sight.app.MemoryDeletionTest' -Output evaluation/results/v3-android35/app-suite.txt
./scripts/test-emulator.ps1 -Serial emulator-5558 -Adb .tools/android-sdk/platform-tools/adb.exe -Meta -Output evaluation/results/v3-android35/meta-suite.txt
```

The actual SDK/MDK tests deliver a HEVC color chart and a credited photograph, convert SDK I420 frames to RGB, run the shared real model and confirm Find through the production core. They do not emulate real optics, head motion, physical capture timing or wearable audio. Copy JSON proof files from each debug package's cache using `adb exec-out run-as` before uninstalling. The engineering report records which checks actually executed; commands alone are not evidence of a pass.

Before the Meta checks, build its test APK with the emulator stopped: `./gradlew.bat -p integrations/meta :adapter:testDebugUnitTest :adapter:assembleDebugAndroidTest --max-workers=1 --console=plain`. Repeat the application suite on API 30 with `-Serial emulator-5556` and a separate output directory. Run `org.sight.app.NativeSpeechTest,org.sight.app.NativeSpeechQueueTest` on the Google APIs API 35 image with an installed offline English voice. Collect `sight-speech-proof.json` and `speech-queue-native-v3.json`; an assumption skip does not prove synthesis or interruption. Stop Gradle daemons before booting a resource-constrained emulator, and stop the emulator before the next build.

[Actual SIGHT 3 screenshots](../evaluation/results/v3-android35/README.md) include Assist, the live Developer dashboard, narration/model settings, empty Memory and double-size Android text. These were captured directly from the running native application after instrumentation; screenshot counters are separate from the timed test export.

Accuracy reproduction is in [evaluation instructions](../evaluation/README.md). Temporal component experiments run with `./gradlew.bat :core:temporalEvaluation -PtemporalOutput=evaluation/results/v3-temporal-reproduction`; these labelled synthetic boxes isolate policy effects, not real-video tracking performance.

After native runs, copy JSON proof files with `python scripts/collect_android_v3.py --serial emulator-5558 --output evaluation/results/v3-android35`. For the separate speech image, use its serial/output directory and add `--audio` to retain the original test WAV. `python evaluation/analyze_android_v3.py` summarizes the saved final Android 30/35 raw measurements; `python scripts/record_validation_v3.py` records JUnit counts, native statuses, lint counts and APK hashes. These collection commands perform no inference.
