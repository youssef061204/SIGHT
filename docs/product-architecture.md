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

CameraX → latest-frame analyzer → shared perception → evidence/goal policy
  → bounded priority speech queue → freshness and support checks → offline TTS

Camera frame → separate async road segmentation diagnostic
  → manual diagnostic result only; automatic road speech is disabled
```

## Navigation path

Photon/komoot searches place names after explicit service consent. The user selects a numbered result. FOSSGIS OSRM foot routing is the standard route provider; the user may explicitly request additional mapped details through FOSSGIS Valhalla. Provider responses are bounded, parsed into route geometry and structured maneuvers, and retain provider/source provenance. Optional saved destinations are encrypted and explicitly user-created.

The navigation controller acquires Android location only after permission. It discards stale or poor-accuracy fixes, pauses when the app leaves the foreground, and updates a local route-progress engine against provider geometry. Repeated accurate deviations can prompt a reroute. Progress and approximate distances are route estimates based on GNSS, not sidewalk position. Ambiguous, stale, or implausible fixes produce uncertainty. Arrival requires user confirmation near the mapped endpoint. Provider data cannot tell whether a sidewalk, crossing, entrance, or indoor path is usable.

Network requests are scoped to destination search and route coordinates, with session consent and user-visible provider disclosure. Camera observations and images are not submitted for routing. See [routing details](routing.md).

## Visual and spoken assistance

CameraX uses a latest-frame policy and a serialized worker for the shared Lite0/Lite2 MediaPipe detectors. OCR is asynchronous and tied to its captured image. Evidence carries timestamps, source/model/geometry identity, and bounded temporal history. Find requires fresh observations and can continue through loss and reacquisition; a matched track is an image continuity hypothesis, not proof of physical identity. Read speaks text from its captured frame. Explicit encrypted memory is separate from short-term visual history.

Speech commands are single-turn and user initiated. Platform on-device recognition is preferred; bundled offline English recognition supports fallback configurations. No wake-word or background listening is present. A bounded speech queue prioritizes route and task announcements, suppresses duplicates, and rejects stale live observations. The app requires an offline-capable installed TTS voice for spoken output. Voice input and speech synthesis have different validation evidence; neither is proven as human-audible or reliably understood in outdoor use.

## Road diagnostic boundary

The road reader is a separate asynchronous path with one owned frame copy and one native task. Its diagnostic/status can be inspected by the user or developer; it does not drive autonomous spoken warnings. Automatic road voice remains disabled: the balanced 40-image held-out presence set scored 17 TP, 1 FP, 3 FN, 19 TN, with 0.025 false events per image against a maximum of 0.02. Strict road-pixel IoU was 0.6448. The frozen calibration operating point (coverage ≥0.02, mean score ≥0.75) yielded 11 TP, 0 FP, 4 FN, and 15 TN on a separate 30-image split. See the [balanced road report](../evaluation/results/pedestrian-balanced/report.md) and its [protocol](../evaluation/results/pedestrian-balanced/protocol.json), [calibration](../evaluation/results/pedestrian-balanced/calibration.json), and [held-out result](../evaluation/results/pedestrian-balanced/held-out.json). Output does not establish crossing markings, signal state, vehicle approach, obstacle presence/absence, free space, or safe movement. Do not feed this output into navigation speech as a clearance decision.

## Lifecycle, consent, and accessibility

The retained app session owns task state, not an Activity or camera. Camera, microphone, and GPS stop in the background; restarting sensors requires foreground interaction. Location permission is distinct from route-service consent. Controls expose a typed route and command path, large touch targets, semantic status/headings, scalable text, and optional one-command voice. Volume keys start a command or stop assistance only while SIGHT is open. These are implementation choices; no human TalkBack evaluation is available.

## Meta boundary

The Meta Wearables Mock Device Kit adapter is an isolated mock integration and separate APK. It imports shared core/perception sources and demonstrates decoded mock-frame conversion through real inference and Find logic. It does not provide a physical glasses host experience or prove optics, microphone, audio routing, clock synchronization, or performance on glasses. See [historical Meta integration record](meta-v3.md).

## Validation record

Current JVM checks pass core 67, app 59, and perception 12 (138 total). Final Meta/native/lint totals are pending. Older detector/runtime/native results are in [SIGHT 3 report](engineering-report-v3.md) and [validation manifest](../evaluation/results/v3-validation/summary.json). New integrated totals are pending in [product-validation summary](../evaluation/results/product-validation/summary.json). No diagram or architecture boundary implies physical-world safety validation.
