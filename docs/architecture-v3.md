# SIGHT 3 architecture

This extends the existing native application in place. The preserved baseline is commit `f37f85d`; the original engineering report and validation artifacts remain historical evidence.

## Data flow

`CameraX KEEP_ONLY_LATEST → oriented RGBA bitmap → adaptive shared detector → timestamp admission → image association → temporal metadata → goal → grounded announcement → priority queue → final speech validation`.

The `perception` Android library owns checksum-verified EfficientDet Lite0 and Lite2 assets, MediaPipe inference and independently testable pixel operations. Both the phone and the isolated Meta project depend on this same implementation and the same `core` engine. MediaPipe handles its own resizing and box reprojection; introducing a second letterbox reduced accuracy in the diagnostic experiment and is deliberately avoided.

CameraX conversion respects the buffer window and row stride. Rotation and mirroring precede detection. Camera2's declared REALTIME sensor clock permits exposure timestamps; other sources explicitly use monotonic receipt time. Resolution/rotation changes advance geometry epochs; camera restarts receive distinct source IDs. Source/model/geometry discontinuities invalidate image identities rather than silently transferring them.

One camera worker owns model initialization, switching and inference. Read requests submit one retained bitmap copy to asynchronous bundled ML Kit; object inference does not wait for text recognition. Completion preserves the original capture evidence. A separately gated captured-text snapshot can be admitted within eight seconds, only for the current read request, source and geometry. It is spoken as captured text and never updates current object tracks. Live image claims retain their 1.5-second age limit. Native OCR owns its copy until completion because ML Kit exposes no public cancellation primitive.

Auto selects Lite0 for Explore/Read and Lite2 for Find. Explicit inspection and settings can request the larger model. Thermal, low-memory and power-saving constraints select Fast; three inspection calls over 1.1 seconds select Fast for 15 seconds. Cold initialization is recorded separately. These are engineering policies, not calibrated guarantees of phone responsiveness.

## Temporal reasoning

Association combines observed overlap with bounded image velocity. It never fills detector gaps with allegedly visible objects. Ambiguous matches are rejected. Four recent model scores support configurable confirmation, including category overrides; scores are not calibrated probabilities. Image identities are continuity hypotheses, not physical re-identification.

Metadata retains at most 160 entities for 60 seconds, distinguishes current/recent/historical/estimated/unknown and records capture provenance and bounded history. Only confirmed historical entities can enter spoken recall. Deletion prevents pre-clear track samples from being reintroduced. Relationships describe normalized image layout, including overlap, left/right and above/below; they do not prove physical support, occupancy or proximity in meters.

Find continues after a confirmed match, reports loss once and requires fresh verification on reacquisition. Corrections exclude the rejected image track. Queries for earlier image sides, detector changes and same-chair continuity use retained evidence or abstain. Unsupported attributes/categories are disclosed. Deterministic parsing and evidence references avoid an unconstrained language model inventing visual facts.

## Speech and lifecycle

A pure bounded priority policy admits three pending announcements plus one active item, suppresses duplicates and ambient bursts, interrupts lower priority speech, and rejects stale events at dispatch and actual TTS start. Ongoing live speech must retain visible, fresh, sufficiently confident category/side and relationship support. Captured text can finish its admitted reading. Repeat preserves provenance and cannot repackage an expired live statement as a new status.

The Android ViewModel retains task intent and metadata across Activity recreation, with application context only. Camera, microphone and GPS stop when the Activity leaves the foreground. Resuming requires a user action. The preview occupies one stable composition position across tabs; each tab keeps its own scrolling. Status, headings, target sizes and a two-row large-font navigation layout support accessibility. Automated semantics tests do not establish human TalkBack usability.

## Runtime evidence

Diagnostics distinguish camera capture callbacks, analyzer deliveries, scheduler skips and completed inference. Unobserved CameraX drops are unknown. Rolling detection percentiles include sample counts; P99 is withheld below 200 samples. Android process PSS and managed heap are separate. OCR native-task duration, model initialization, exposure-to-completion when calibrated, TTS request and TTS engine callback times remain separate. The callback clock is not audible speaker onset. Exports can contain narration and recognized text and require explicit user action.

## Depth and broader visual recognition decision

ARCore Depth requires a supported device, an ARCore session, motion and explicit capability checks; depth-from-motion can lack data on weakly textured surfaces or without motion. See the official [Depth guide](https://developers.google.com/ar/develop/java/depth/developer-guide) and [capability overview](https://developers.google.com/ar/develop/depth). This workspace has no physical calibrated phone/glasses, no independent metric-depth ground truth, and no evaluated sensor synchronization. Adding an unvalidated depth model would not justify distances or navigation. Metric depth, VIO/SLAM and navigable 3D maps remain disabled.

An ImageNet [MobileNet image embedder](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder) produces similarity vectors, not arbitrary object boxes or verified text/image category alignment. It cannot supply evidence for an unsupported category simply by comparing a prompt. Open-vocabulary models considered in the detector research require different licensing, exports and evaluation. No new cloud image transmission or unmeasured visual-language fallback is introduced. See [model research](../evaluation/results/v3-detection/model-research.md).

## Meta boundary

The official DAT/Mock Device Kit project remains separately built to accommodate its Kotlin runtime requirement. It imports the actual production core/perception modules with isolated build directories. Strict packed I420 conversion is enabled only for the validated mock profile; public SDK frames do not expose enough color/stride/orientation metadata to assume every real glasses stream has that layout. Mock receipt timestamps are explicit. Stop/reconnect generations, permission rechecks and bounded latest-frame delivery prevent old tasks reentering the engine. Audio input/output ports and Android route discovery do not prove glasses microphone/speaker operation. See [Meta details](meta-v3.md).
