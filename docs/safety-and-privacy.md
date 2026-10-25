# Safety, privacy and accessibility

## Supported claims

The core describes detected objects/text and their position **in a camera image**. Repeated agreement reduces transient messages but does not prove classification correctness. Scene facts can be observed, historical, uncertain or unavailable; the system has no certified geometry, collision detector or free-space estimator.

It never announces safe crossings, obstacle-free paths, clearance, precise monocular distances, collision prevention or directions to step into unvalidated space. Outdoor route text comes from a provider and cannot establish sidewalk condition or crossing safety. There is no indoor routing graph, ARCore/ARKit pose integration, semantic SLAM or validated time-to-collision.

Real model errors include missed objects, false categories and blur sensitivity. Holding the camera away from an object, darkness, glare, small text, atypical packaging, rapid motion and occlusion can invalidate results. OCR quotations and regex-extracted price/date-like strings remain fallible. Date format and product safety are not verified. Warning interpretation is disclosed as unsupported.

## Recovery and privacy

- Pause stops camera analysis and invalidates live observations. Activity backgrounding also pauses; no background camera/voice service exists.
- Stop speaking and task interrupts output and cancels the task. Cancel/resume preserves task intent while requiring fresh evidence.
- Camera and microphone each have explicit runtime permissions and visible states. Recognition is user-initiated and restricted to platform on-device services; unsupported devices retain typed commands.
- Offline English TTS is required. The app does not silently select a network-required voice.
- Persistent memory is off at launch. Enabling it affects future explicit `remember` commands, not ambient observations. Memory may contain sensitive OCR text, so the UI explains persistence and deletion.
- Android Keystore encrypts saved payloads; app backup is disabled. Rooted or compromised operating systems remain outside this protection boundary.
- Diagnostic export can contain utterances and OCR-derived text. SIGHT does not automatically upload these exports. Clear session memory/close the app before sensitive sharing.
- Bundled ML Kit performs OCR on device but can send SDK diagnostics to Google, including device/app details, an installation identifier and usage/performance metrics. Its published terms say images and recognized output are not sent. This is disclosed in onboarding and Settings. Use airplane mode for a session with no network traffic. No claim of a supported SDK telemetry opt-out is made. [Google's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure), [ML Kit privacy terms](https://developers.google.com/ml-kit/terms).
- No face recognition, person identification, audio recording retention, remote helper, cloud vision or API credential is included.

## Accessibility implementation and validation boundary

Compose uses descriptive text controls, headings, semantic status feedback, logical screen order, minimum 56–64 dp action heights, scalable text, high-contrast colors, a mono-compatible spoken experience and optional haptics. Core use does not depend on the developer graph. No critical information is communicated only by color or a spatial sound. Developer graphics have text alternatives.

These are implementation properties, not an accessibility-compliance certification. TalkBack behavior, focus order under actual font scaling, overlapping Android accessibility/TTS audio, speech comprehension, one-handed reachability and blind-user usefulness still require physical and participant evaluation.

## Controlled participant protocol

Begin with bench-top object finding and static signage, with informed consent, a sighted facilitator and an orientation-and-mobility specialist. No traffic or unsupervised walking tasks. Include different phone models, heights, camera orientations, lighting and hearing preferences. Offer screen-reader, voice and typed access without requiring developer controls.

Measure time to find an annotated object, false confirmation rate, redundant messages, repeated requests, task completion, OCR success, speech comprehension and perceived workload. Record device/software/model versions and test conditions; preregister success thresholds with participants and specialists. Collect only explicitly consented session data and offer deletion. Do not extrapolate tabletop success to independent travel. No participant study has been conducted in this package.
