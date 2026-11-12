# SIGHT product engineering report

Status snapshot: 2026-11-11. This report separates current implementation from executed evidence. SIGHT is experimental assistive software; it is not a certified independent mobility aid.

## Readiness matrix

| Product area | Implemented in current product | Evidence / release boundary |
|---|---|---|
| Walking navigation | Photon destination search, explicit numbered selection, OSRM foot routes, optional Valhalla mapped details, foreground GPS progress, route overview, pause/resume, repeat, reroute, saved destinations, confirmation-based arrival | Provider samples and production-engine tests/replays exist. The new integrated Android run is still being recorded. No outdoor walking, entrance, accessibility, or safe-crossing validation. |
| Road scene | LiteRT/MediaPipe segmentation and manual diagnostics for road, curb, and marking labels | Balanced static-image evaluation exists. Automatic road voice is **disabled** because final false events/image exceeded the frozen maximum. No curb/marking/signal/hazard interpretation. |
| Obstacle/hazard awareness | General object detection and image-position descriptions | No dedicated free-space, collision, depth, vehicle-approach, or clearance subsystem is validated. Never claim an obstacle-free path or absence of hazards. |
| Object finding / reading | On-device 80-class detection, temporal Find, captured-frame OCR, short-term visual recall, explicit encrypted landmark saves | SIGHT 3 detector metrics are historical subset results; difficult categories have zero recall in that held-out sample. No physical task-success study. |
| Spoken interaction | One-command on-device recognition, bundled offline English fallback, offline TTS queue, voice route commands | JVM evidence is current; new native totals remain pending. No human mic-recognition, audible-onset, outdoor comprehension, or TalkBack study. |
| Android accessibility | Semantic controls/status, large targets, scalable type, typed alternatives, one-command voice controls | Automated earlier emulator checks exist. Human TalkBack and participant usability are untested. |
| Meta | Separate Meta Wearables Mock Device Kit adapter test APK using shared core/perception | Mock integration only. Final Meta run pending; actual glasses hardware, microphone, and speaker remain untested. |
| Runtime | Camera, voice, routing, and road-processing paths are implemented with bounded queues/lifecycles | Historical emulator measurements are labeled below. No physical-device latency, thermal, or battery validation. |

## A. Product

The Android app combines spoken walking-route guidance with separate camera-based Explore, Find, and Read workflows. Users can submit a destination by voice or text, choose a numbered search result, start a provider walking route, and receive route progress from GPS while the app is foregrounded. They can also explore a camera view, search supported object classes, read captured text, review recent visual observations, and explicitly save encrypted landmarks. These capabilities are integrated in the Android application, but no completed real-world end-to-end walking study is claimed.

Voice commands are requested one at a time. On-device Android speech recognition is preferred; bundled offline English recognition is the fallback. SIGHT has no wake-word listener. Speech synthesis requires a suitable installed offline English voice. Physical microphone recognition and human confirmation that synthesized speech is audible have not been evaluated.

## B. Navigation

Photon/komoot supplies place search. The default walking route comes from FOSSGIS OSRM's `routed-foot` service; an explicit preference can request additional mapped details from FOSSGIS Valhalla. Public routing is disabled until the user consents. Search and route coordinates are sent to those providers; camera images are not. Providers may log requests and do not provide a route accessibility guarantee.

The app supports numbered destination choices, route overview, foreground GPS guidance, pause/resume, repeat, distance to the next mapped maneuver, cancel, and recalculation after repeated accurate off-route fixes. Progress uses route geometry and screened GPS fixes. Stale, inaccurate, duplicate, or implausible fixes lead to uncertainty. Arrival requires a fresh nearby fix and user confirmation; the mapped point may not be the correct entrance. There is no lane positioning, sidewalk-position estimate, or indoor map matching.

Executed evidence: public fixed-landmark requests returned search results and walking routes; JVM tests/replays exercise production route parsing and progress behavior. The updated integrated navigation/native validation record is pending at [product-validation summary](../evaluation/results/product-validation/summary.json). Constructed GPS traces and provider smoke requests do not measure a person walking, destination-search quality, accessibility, or field GPS performance. Routes can contain unsuitable paths, steps, or crossings.

## C. Road awareness

The app contains an experimental MediaPipe road-scene segmentation path, a bounded asynchronous reader, diagnostic timing/status, and an explicit manual diagnostic action. It is not integrated as an automatic spoken road-warning feature. Its output does not establish a curb, crossing, signal state, moving-vehicle hazard, obstacle, clear path, or permission to cross.

Automatic road voice is **disabled** after the balanced sealed 40-image held-out presence evaluation failed one gate. At the frozen calibration operating point (component coverage at least 0.02; mean posterior at least 0.75), held-out counts were 17 TP, 1 FP, 3 FN, and 19 TN. Precision was 0.9444 and recall 0.8500, but false events/image was 0.025, exceeding the maximum 0.02. The same held-out set had strict road-pixel IoU 0.6448. Calibration selected that operating point on a separate 30-image split: 11 TP, 0 FP, 4 FN, and 15 TN. Selection, calibration, and held-out splits each have 30, 30, and 40 images; no parameters were tuned on the final split. See the [balanced evaluation report](../evaluation/results/pedestrian-balanced/report.md), [frozen protocol](../evaluation/results/pedestrian-balanced/protocol.json), [selection](../evaluation/results/pedestrian-balanced/selection.json), [calibration](../evaluation/results/pedestrian-balanced/calibration.json), and [held-out results](../evaluation/results/pedestrian-balanced/held-out.json). The older [initial general-photo probe](../evaluation/results/pedestrian/calibration.json) is a distinct failed experiment and is not the final road result. The new combined Android/native run remains pending in [product-validation summary](../evaluation/results/product-validation/summary.json).

The detector does not provide a validated score for sidewalks, curbs, crosswalks, pedestrian signals, traffic lights, construction, or vehicle approach. No measured road-hazard performance is claimed.

## D. Object finding

The deployed object detectors cover 80 COCO categories, with Fast Lite0 and selective Inspect Lite2. Find uses fresh camera evidence and temporal confirmation; uncertain or unsupported categories can be declined. Position descriptions use the camera image, not the user's body or walking direction. There is no color classifier, identity verification, metric depth, door/entrance detector, stair detector, or validated free-space analysis.

Historical SIGHT 3 held-out evaluation on 80 credited photographs reported Lite2 AP50 0.6040, AP50:95 0.4158, operating precision 0.9491, recall 0.3394, and 11 false positives. This is a fixed subset detector benchmark. It is not a new product-validation result or an estimate of real task success. Recall for backpacks (0/10), handbags (0/9), bottles (0/12), and books (0/24) was zero in that held-out slice. See the [original detector report](../evaluation/results/v3-detection/report.md) and [SIGHT 3 report](engineering-report-v3.md).

## E. Performance

Current local JVM status: **138 passed** (core 67, app 59, perception 12). The Meta adapter's final status, native integrated totals, and lint status are pending; they will be reconciled against [evaluation/results/product-validation/summary.json](../evaluation/results/product-validation/summary.json) after the run completes. Do not substitute historical tests or emulator measurements for that final run.

Historical SIGHT 3 evidence: on Android 15 x86_64 emulator, fixed-photo Lite0 detection P50/P95 was 230/285 ms and Lite2 was 591/682 ms (30 calls/profile, three warmups excluded); live CameraX receipt-to-result P50/P95 was 744/1,178 ms over 30 results, including the first result. Whole-process PSS was 176.4 MiB in that run. These are emulator measurements, not physical-phone performance. No sustained thermal or battery result exists. The 2026-10-25 engineering report gives methods and caveats in [section F](engineering-report-v3.md).

## F. Accessibility

Compose provides labelled actions, headings and status feedback, scalable text, large touch targets, a two-row large-font navigation layout, typed alternatives, and user-initiated voice controls. Volume Up starts one command and Volume Down stops assistance while the app is open; these keys replace normal volume adjustment during that time. There is no always-listening mode.

Historical automated Android checks exercised screen semantics, large text, tab scrolling, lifecycle, and session controls. The final new integrated test totals are pending. There has been no human TalkBack usability evaluation, blind-participant study, or physical-device accessible task-completion study. Implementation features are not an accessibility certification.

## G. Meta glasses

The optional Meta Wearables Mock Device Kit adapter is a separate mock APK/project. It imports shared production core and perception code and has passed historical mock-frame conversion, detector/Find, and permission-recovery tests. This is not the phone app controlling real glasses. It does not establish physical camera calibration, optics, head orientation, synchronized capture timing, microphone input, speaker routing, comfort, or battery life. No real glasses hardware has been tested. See [Meta integration limits](meta-v3.md).

## H. Testing

The current local JVM run passed 67 core, 59 app, and 12 perception tests (138 total). The final Meta JVM/native results, Android instrumentation totals, Python checks, and lint outcome are pending. The earlier SIGHT 3 validation manifest reports 60 core, 38 app, 7 perception, and 8 Meta JVM checks; 12 Python checks; 12 Android application instrumentation checks on each of API 30 and API 35 emulators; 8 Meta mock instrumentation checks; and 2 local speech synthesis/queue checks, with lint at zero errors and 16 warnings. Those are historical counts, not current-run totals; see the [old validation manifest](../evaluation/results/v3-validation/summary.json).

The new navigation, voice, and road work has separate tests and sealed image evaluation. Latest combined JVM/native totals, run identity, failures, and raw evidence links will be recorded at [product-validation summary](../evaluation/results/product-validation/summary.json); it does not exist yet. JVM total 138 is confirmed; do not report pending native or Meta totals as passed until that manifest exists and is reviewed. No physical-device, human accessibility, field hazard, or outdoor walking validation is available.

## I. GitHub

Target public repository: [https://github.com/youssef061204/SIGHT](https://github.com/youssef061204/SIGHT). Target prerelease tag: `public-preview`. Publication and remote commit verification are pending; this report does not claim the repository, tag, README media, or links have been verified live.

## J. Portfolio

An existing portfolio project entry and its public deployment have not been verified in this report. No portfolio URL, deployment commit, or live project link is claimed.

## K. Remaining blockers

- Automatic road voice remains disabled because the held-out presence gate failed. A stronger, independently evaluated road model and a passing release gate are needed before activation.
- The combined current JVM/native/navigation evidence record is not yet final. Generate and inspect `evaluation/results/product-validation/summary.json` before publishing pass totals.
- Physical Android performance, sustained runtime, battery, real microphone behavior, human TalkBack and blind-participant usability, outdoor route quality, and actual glasses behavior remain unverified.
- Public GitHub publication, prerelease verification, portfolio discovery/update, and demo video are pending. The planned video path is `docs/media/sight-demo.mp4`; the file does not yet exist.

## Evidence policy

Historic reports keep their existing names and dates. Their metrics remain historical and are linked as such; this report does not renumber or rebrand the project. A test procedure, constructed replay, public HTTP request, or mock device run is not represented as a field outcome. No result here establishes safe crossing, obstacle clearance, collision avoidance, or independent mobility.
