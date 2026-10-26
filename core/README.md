# Temporal engine

The module has no Android, model-vendor, image storage or network dependency. `SightEngine` serializes detector observations, independent asynchronous OCR callbacks, commands and lifecycle updates using one monotonic clock.

`Evidence` carries the actual source capture/receipt time, frame ID, source ID, model manifest identifier and oriented-image geometry epoch. A source, model, geometry or input-session discontinuity invalidates short image identities. Model identifiers default to `unspecified` for compatibility; adapters should supply a real manifest ID. Source exposure-clock uncertainty remains visible through `TimeBasis.RECEIPT`.

`ImageTracker` associates same-category boxes one to one. It uses observed overlap or, where two accepted samples support a bounded image velocity, 80% predicted overlap and 20% last-box overlap. Velocity above 1.5 normalized image units/second is rejected, extrapolation beyond a quarter of the image is rejected, and association/prediction gaps default to at most one second. Competing prediction scores inside an ambiguity margin are not used to force an identity. This is a local image-motion hypothesis, with no optical flow, embedding re-identification, inertial pose, physical identity or 3D position. The engine never reports an extrapolated box as visible.

Each entity retains the latest uncalibrated detector score and the arithmetic mean of up to four retained scores. Both the latest and aggregate must pass narration policy. `ConfirmationPolicy` configures post-request matching frames and optional aggregate score by category; defaults preserve two-frame confirmation. Find counts only accepted captures at or after the request, can continue after losing a confirmed candidate, and requires fresh verification on return. “Same chair” searches require a unique retained image-track reference and explicitly disclaim physical identity. “Not that one” excludes the rejected image track for that search without rewriting detector labels.

`TemporalWorld` keeps at most 160 metadata entries for 60 seconds, including a maximum of 12 observed history samples per track by default. Current/recent/previously remembered states differ explicitly; the recent interval defaults to five seconds. Entries from a discontinuous view remain historical. `confirmedHistorically` separates diagnostic candidates from temporally confirmed observations; one-frame candidates cannot become confident spoken historical answers. `ESTIMATED` and `UNKNOWN` exist in the type vocabulary but the engine does not manufacture estimated visible objects. Named memories retain their separate explicit-save semantics. Clearing memory clears temporal metadata and prevents earlier tracker samples from returning; live camera tracking continues. No raw frames or appearance descriptors enter this module.

Commands cover current image sides/center, historical sides, recent observations, last sightings, detector-view changes, retained image-track searches, rejection and reinspection. “On the table” can return actual overlapping/above image-box relationships with an explicit physical-support disclaimer. The final speech validator rechecks the required pairwise relationships as well as latest/aggregate confidence, source, geometry, visibility, age and image direction. Unsupported categories, colors, chair occupancy, depth and physical route safety cannot produce successful visual claims. Detector differences do not establish physical object movement or absence.

`onTextObservation(evidence, text, nowMs)` accepts independently completed OCR. It does not update object tracks or reset the detector's model continuity. The source, geometry, lifecycle, pending Read request, original capture time and independent callback order are checked. An admitted async snapshot is prefixed `Captured text:` and expires after at most eight seconds at initial speech admission. Legacy in-frame OCR remains subject to the 1.5-second live admission gate. This snapshot exception never admits old live scene claims; already admitted fixed text may finish through the Android speech policy.

`AdaptiveScheduler` admits Find detection at 150 ms, Explore/Read detection at 250 ms and Read OCR at 1,000 ms by default. These are configurable admission intervals, not measured throughput. Thermal multipliers and critical suspension apply to all modes. Camera adapters remain responsible for capacity-one work and resource ownership.

From the repository root:

```powershell
.\gradlew.bat :core:test --max-workers=2
.\gradlew.bat :core:temporalEvaluation "-PtemporalOutput=evaluation/results/temporal-v3" --max-workers=2
.\gradlew.bat :core:replay "-PreplayInput=evaluation/replay/real-detection-repeat.tsv" "-PreplayOutput=evaluation/results/core-replay-v3.jsonl" --max-workers=2
```

The temporal evaluation uses identical constructed labelled image-box trajectories for controlled component ablations. It exports truth and detector observations, restricted-domain identity switches/IDF1/stability, missed observations, false completions, history responses, active hints and stale speech admissions. It does not establish real-video tracking accuracy, model accuracy, Android performance or assistive safety. Existing replay inputs remain supported; choose an existing TSV path when reproducing that separate replay.
