# SIGHT product architecture

This describes the current integrated Android product. Historical implementation notes remain in [SIGHT 3 architecture](architecture-v3.md). Navigation, image perception, and road diagnostics use separate evidence paths; none can certify physical safety.

## Product flows

```text
User command / Routes controls
  ├─ on-device voice input or typed text
  │    ├─ local Assist intent → camera task / Find / Read / Explore
  │    └─ route intent → consent + place search → numbered user choice
  └─ Routes screen → same navigation controller

Route selection → fresh location permission/fix → walking provider
  → route geometry + maneuvers → GPS-to-route progress → gated spoken cues

CameraX → latest-frame analyzer → MediaPipe COCO object detector → evidence/goal policy
  ├─ Explore / Find / Read / recent visual recall
  ├─ optional traffic-object awareness → evidence + safety checks → offline TTS
  └─ async ML Kit OCR → captured text / bounded text-landmark matching

Camera frame → separate async Intel road-segmentation-adas-0001 LiteRT model
  → road / curb / marking diagnostic; automatic surface speech is disabled

Rotation-vector sensor → recent rear-camera pitch / magnetic camera heading
  → never body direction or walking-lane direction
```

## Navigation path

Photon/komoot searches place names after explicit service consent. The user selects a numbered result. FOSSGIS OSRM foot routing is the standard route provider; the user may explicitly request additional mapped details through FOSSGIS Valhalla. Provider responses are bounded, parsed into route geometry and structured maneuvers, and retain provider/source provenance. Optional saved destinations are encrypted and explicitly user-created.

The navigation controller acquires Android location only after permission. It discards stale or poor-accuracy fixes, pauses when the app leaves the foreground, and updates a local route-progress engine against provider geometry. Repeated accurate deviations can prompt a reroute. Progress and approximate distances are route estimates based on GNSS, not sidewalk position. Ambiguous, stale, or implausible fixes produce uncertainty. Arrival requires user confirmation near the mapped endpoint. Provider data cannot tell whether a sidewalk, crossing, entrance, or indoor path is usable.

Network requests are scoped to destination search and route coordinates, with session consent and user-visible provider disclosure. Camera observations and images are not submitted for routing. See [routing details](routing.md).

## Visual and spoken assistance

CameraX uses a latest-frame policy and a serialized worker for the Lite0/Lite2 MediaPipe object detectors. A separate single-worker LiteRT interpreter runs the Intel road-segmentation model only after the user starts road diagnostics; it owns one copied frame and drops new work while busy. Its classes are background, road, curb, and marking. The model is separate from MediaPipe and has no sidewalk, crosswalk, signal-state, or depth output. Automatic road descriptions remain disabled after the balanced held-out false-event gate failed; the class labels remain diagnostic only.

OCR is asynchronous and tied to its captured image. Besides reading text, a bounded search maps selected requests for door/entrance/exit, elevator/lift, or bus-stop wording to ML Kit recognized text. This reports matching captured words; it does not detect the corresponding physical object or establish where it is. Evidence carries timestamps, source/model/geometry identity, and bounded temporal history. Find requires fresh observations and can continue through loss and reacquisition; a matched track is an image continuity hypothesis, not proof of physical identity. Explicit encrypted memory is separate from short-term visual history.

When the user enables walking awareness, or route guidance is active, the environmental object policy can speak a selected eligible object track after two consecutive observations. It requires per-frame and aggregate confidence of at least 0.70, box area at least 0.02, and live evidence no more than 1.5 seconds old. It speaks a camera-image side and says movement is unverified; traffic-light state is explicitly unknown. It chooses at most the largest eligible object for an update and suppresses the same category/direction pair for 20 seconds. This is object visibility narration, not vehicle-motion or hazard-event detection. The camera-alignment command can report a recent rear-camera pitch and a magnetic camera heading only when sensor accuracy permits; it never derives body heading or gait direction.

Speech commands are single-turn and user initiated. Platform on-device recognition is preferred; bundled offline English recognition supports fallback configurations. No wake-word or background listening is present. A bounded speech queue prioritizes route and task announcements, suppresses duplicates, and rejects stale live observations. A deterministic safety layer checks freshness and grounding at final admission and again during playback, blocks safe-crossing/clear-path/avoidance language and visual metric-distance claims, and invalidates route cues on navigation revision change or expiry deadline. The app requires an offline-capable installed TTS voice for spoken output. No physical microphone use or human audibility is established.

## Road diagnostic boundary

The separate road model is a direct LiteRT interpreter, not MediaPipe. It produces diagnostic road/curb/marking masks; automatic road speech is disabled. The balanced 40-image held-out presence set scored 17 TP, 1 FP, 3 FN, 19 TN; 0.025 false events per image exceeded the maximum 0.02. Strict road-pixel IoU was 0.6448. The frozen calibration operating point (coverage ≥0.02, mean score ≥0.75) yielded 11 TP, 0 FP, 4 FN, and 15 TN on a separate 30-image split. See the [balanced road report](../evaluation/results/pedestrian-balanced/report.md) and its [protocol](../evaluation/results/pedestrian-balanced/protocol.json), [calibration](../evaluation/results/pedestrian-balanced/calibration.json), and [held-out result](../evaluation/results/pedestrian-balanced/held-out.json). Road markings do not identify crosswalks; curb output is not independently validated. The model does not establish signal state, vehicle approach, obstacle presence/absence, free space, or safe movement.

## Lifecycle, consent, and accessibility

The retained app session owns task state, not an Activity or camera. Camera, microphone, and GPS stop in the background; restarting sensors requires foreground interaction. Location permission is distinct from route-service consent. Controls expose a typed route and command path, large touch targets, semantic status/headings, scalable text, and optional one-command voice. Volume keys start a command or stop assistance only while SIGHT is open. These are implementation choices; no human TalkBack evaluation is available.

## Meta boundary

The Meta Wearables Mock Device Kit adapter is an isolated mock integration and separate APK. It imports shared core/perception sources and demonstrates decoded mock-frame conversion through real inference and Find logic. It does not provide a physical glasses host experience or prove optics, microphone, audio routing, clock synchronization, or performance on glasses. See [historical Meta integration record](meta-v3.md).

## Validation record

The [passed product-validation manifest](../evaluation/results/product-validation/summary.json) records JVM 146, Python 14, API 35 app 15, API 30 app 15, offline speech 2, and Meta MDK 8. Lint reported zero errors and 18 warnings. See [API 35 app output](../evaluation/results/product-android35/instrumentation-suite.txt), [API 30 app output](../evaluation/results/product-android30/instrumentation-suite.txt), [Meta output](../evaluation/results/product-meta/instrumentation-suite.txt), and [navigation evidence](../evaluation/results/product-navigation/summary.json). Remote CI passed on `61c9069`; proof and media artifacts are in `50b83d2`. No diagram or architecture boundary implies physical-world safety validation.
