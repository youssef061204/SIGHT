# SIGHT product walkthrough

This walkthrough uses the current Android app and actual public map services. It is not a scripted simulation or a field-safety demonstration. For route and object tasks, stay stationary or use a controlled, supervised setting with ordinary mobility aids. Do not make crossing decisions from SIGHT output.

## Build

Install JDK 21 and Android SDK platform 35 / build tools 35.0.0, then set `ANDROID_HOME` or provide an untracked `local.properties` with `sdk.dir`.

```powershell
./scripts/build.ps1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.sight.app/.MainActivity
```

The script fetches the checksum-verified object models and bundled offline voice model, then runs project validation, lint, and builds the debug APK. For API-by-API evaluation commands see [evaluation instructions](../evaluation/README.md). The current consolidated product run is still pending; see [product-validation summary](../evaluation/results/product-validation/summary.json) when generated.

## Try walking-route guidance

1. Open **Routes** and enable public route services. This is explicit consent to send the submitted destination query and route coordinates to Photon/komoot and FOSSGIS. Camera images are not sent.
2. Enter a public place name or say “take me to Union Station Toronto.” Start one voice command with **Use voice** or Volume Up; select a numbered result yourself.
3. Grant Android location permission when prompted. Choose **Start spoken navigation**. Use a fresh, sufficiently accurate GPS fix; poor or stale fixes are announced as uncertain.
4. Listen to the mapped maneuver cue. Use **Repeat current direction**, **Hear distance to next turn**, or **Hear route overview**. Pause and resume GPS, recalculate from current GPS, or stop navigation from Routes.
5. At the mapped endpoint, SIGHT can request confirmation that you recognize the actual destination. GPS proximity does not verify the correct entrance.

Public route data may include unsuitable sidewalks, stairs, indoor passages, or crossings. No provider data verifies accessibility or live conditions. Route progress estimates position against map geometry; it does not establish walking direction, sidewalk position, or path clearance. Do not infer that a crossing is safe.

## Try local camera assistance

1. In **Assist**, start the rear camera and choose Explore, Find, or Read.
2. Say or type `explore`, `find a chair`, or `read text`. Use supported categories and a clearly visible target. Direction words refer to the camera image.
3. To inspect diagnostics, open **Developer** for source status, model, frame age, track and speech information. A detected object is not a verified obstacle distance or clear route.
4. Request **road diagnostics** to inspect the experimental road segmentation status. Automatic road voice alerts are disabled: on the balanced 40-image held-out set it produced 17 TP, 1 FP, 3 FN, 19 TN and 0.025 false events/image, which exceeds the 0.02 limit. Strict road-pixel IoU was 0.6448. See the [balanced road evaluation](../evaluation/results/pedestrian-balanced/report.md). The output does not identify a safe crossing or prove the absence of traffic.
5. Explicitly ask to remember a landmark before it is persisted. Use Memory to review or delete saved information.

Small objects and some common categories remain poorly detected. The held-out detector metrics in the [historical report](engineering-report-v3.md) should not be generalized to daily performance.

## Voice and controls

Voice recognition is user initiated, on-device, and limited to one command at a time. There is no wake word or background microphone service. Android on-device recognition is preferred; bundled offline English recognition can be used on supported devices. The app exposes typed controls as an alternative. Volume Up starts one voice command and Volume Down stops camera and route assistance while SIGHT is open; those keys replace volume adjustment in that state. Speech output depends on a suitable offline English TTS voice.

Automated checks have exercised UI semantics, text scaling, and sensor lifecycle in earlier emulator runs. Human TalkBack use, actual microphone recognition, outdoor speech comprehension, speaker audibility, and blind-user task success remain unverified.

## Meta mock integration

The optional Meta Mock Device Kit demo is a separate project and APK. It can route mock decoded frames through shared inference. It is not actual glasses hardware and does not demonstrate glasses microphone/speaker use, physical camera orientation, or wearable operation. See [Meta integration status](meta-v3.md).

## Demo media

Planned video path: `docs/media/sight-demo.mp4` (pending). No demo video is present yet. Existing historical Android emulator screenshots are linked from [the SIGHT 3 capture index](../evaluation/results/v3-android35/README.md); they are not a recording of the newer integrated navigation flow.
