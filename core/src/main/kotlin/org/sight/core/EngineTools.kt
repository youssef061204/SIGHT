package org.sight.core

enum class ToolStatus { SUCCESS, NEEDS_CONFIRMATION, NO_EVIDENCE, STALE, UNAVAILABLE, UNSUPPORTED, ERROR }
data class ToolError(val code: String, val message: String)

/** A null confidence means confidence was not measured; it must never be interpreted as certainty. */
data class ToolResult<T>(
    val status: ToolStatus,
    val evaluatedAtMs: Long,
    val data: T? = null,
    val evidence: Evidence? = null,
    val confidence: Float? = null,
    val errors: List<ToolError> = emptyList(),
)

data class ObservedScene(val entities: List<TrackedEntity>, val relationships: List<SceneRelationship>)
data class RecognizedText(val originalText: String, val readingOrder: String = "OCR image order; columns and paragraph boundaries are unverified")
data class MemoryRecall(val snapshot: MemorySnapshot, val ageMs: Long, val presentLocationKnown: Boolean = false)
enum class InformationGap { TARGET_NOT_DETECTED, TEXT_NOT_RECOGNIZED, CAMERA_UNAVAILABLE }
data class ViewRequest(val gap: InformationGap, val message: String)
enum class UncertaintyReason { UNVALIDATED_DEPTH, UNSUPPORTED_ATTRIBUTES, TARGET_NOT_DETECTED, STALE_OBSERVATION }
data class UncertaintyReport(val reason: UncertaintyReason, val message: String)

/**
 * Typed access to the same state used for voice and narration. It does not run a second detector,
 * call a remote model, or turn a request for evidence into a successful perception result.
 * saveLandmark must be called only for an explicit user request; it stores a user-named scene.
 */
class EngineTools(private val engine: SightEngine) {
    fun observeScene(nowMs: Long): ToolResult<ObservedScene> {
        val update = engine.tick(nowMs)
        val evidence = update.liveEvidence
        if (evidence == null) return unavailableScene(update, nowMs)
        val data = ObservedScene(update.entities.filter { it.visible }, update.relationships)
        return observed(data, evidence, nowMs)
    }

    /** Returns fresh detector observations admitted by the engine's confidence/bounds policy. */
    fun detectObjects(nowMs: Long): ToolResult<List<Detection>> {
        val scene = observeScene(nowMs)
        return ToolResult(scene.status, nowMs, scene.data?.entities?.map { Detection(it.category, it.confidence, it.box) }, scene.evidence, errors = scene.errors)
    }

    fun trackTarget(trackId: Long, nowMs: Long): ToolResult<TrackedEntity> {
        val update = engine.tick(nowMs)
        val entity = update.entities.firstOrNull { it.id == trackId }
            ?: return ToolResult(ToolStatus.NO_EVIDENCE, nowMs, errors = listOf(ToolError("UNKNOWN_TRACK", "No retained image track has this identifier.")))
        if (!entity.visible || update.liveEvidence == null) return ToolResult(ToolStatus.STALE, nowMs, evidence = entity.evidence,
            errors = listOf(ToolError("TRACK_NOT_CURRENT", "The historical image track is not currently visible; present location is unknown.")))
        return observed(entity, entity.evidence, nowMs, entity.confidence)
    }

    fun readText(nowMs: Long): ToolResult<RecognizedText> {
        val update = engine.tick(nowMs)
        val text = update.extractedText
        val evidence = update.textEvidence
        if (text == null || evidence == null) return ToolResult(ToolStatus.NO_EVIDENCE, nowMs,
            errors = listOf(ToolError("NO_RECOGNIZED_TEXT", "Request the Read task and obtain a fresh OCR result.")))
        val gate = Announcement("", SpeechPriority.TASK, evidence, AnnouncementKind.OCR)
        if (!engine.isAnnouncementValid(gate, nowMs)) return ToolResult(ToolStatus.STALE, nowMs, evidence = evidence,
            errors = listOf(ToolError("TEXT_NOT_CURRENT", "Session text is historical and cannot be returned as fresh OCR.")))
        return observed(RecognizedText(text), evidence, nowMs)
    }

    fun requestReadText(nowMs: Long): ToolResult<GoalState> {
        val update = engine.command("read", nowMs)
        return ToolResult(ToolStatus.NEEDS_CONFIRMATION, nowMs, update.goal,
            errors = listOf(ToolError("AWAITING_OCR", "A reading goal has started; no recognized text is established by this request.")))
    }

    fun queryVisualMemory(name: String, nowMs: Long): ToolResult<MemoryRecall> {
        val snapshot = engine.tick(nowMs).memory.lastOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: return ToolResult(ToolStatus.NO_EVIDENCE, nowMs,
                errors = listOf(ToolError("NO_NAMED_MEMORY", "No explicitly saved session scene has that name.")))
        return ToolResult(ToolStatus.SUCCESS, nowMs, MemoryRecall(snapshot, (nowMs - snapshot.evidence.capturedAtMs).coerceAtLeast(0)), snapshot.evidence)
    }

    fun saveLandmark(name: String, nowMs: Long): ToolResult<MemorySnapshot> {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return ToolResult(ToolStatus.ERROR, nowMs, errors = listOf(ToolError("INVALID_NAME", "A nonempty user-supplied scene name is required.")))
        val before = engine.tick(nowMs).memory.map { it.id }.toSet()
        val update = engine.command("remember this as $clean", nowMs)
        val saved = update.memory.firstOrNull { it.id !in before && it.name.equals(clean, ignoreCase = true) }
            ?: return ToolResult(ToolStatus.NO_EVIDENCE, nowMs,
                errors = listOf(ToolError("SCENE_NOT_SAVED", update.announcements.firstOrNull()?.text ?: "Fresh stable observations are required.")))
        return ToolResult(ToolStatus.SUCCESS, nowMs, saved, saved.evidence)
    }

    fun requestNewView(gap: InformationGap, nowMs: Long): ToolResult<ViewRequest> {
        val update = engine.tick(nowMs)
        val message = when (gap) {
            InformationGap.TARGET_NOT_DETECTED -> "A different camera view may provide evidence. The target's absence is not established."
            InformationGap.TEXT_NOT_RECOGNIZED -> "Hold the camera steady with text in view. No readable text has been established."
            InformationGap.CAMERA_UNAVAILABLE -> "Restore camera permission or resume camera input before requesting a view."
        }
        return ToolResult(ToolStatus.NEEDS_CONFIRMATION, nowMs, ViewRequest(gap, message), update.liveEvidence)
    }

    fun reportUncertainty(reason: UncertaintyReason, nowMs: Long): ToolResult<UncertaintyReport> {
        val update = engine.tick(nowMs)
        val message = when (reason) {
            UncertaintyReason.UNVALIDATED_DEPTH -> "Physical distance is unknown because validated depth measurements are unavailable."
            UncertaintyReason.UNSUPPORTED_ATTRIBUTES -> "The local detector cannot verify the requested visual characteristics."
            UncertaintyReason.TARGET_NOT_DETECTED -> "The target was not detected; that does not establish that it is absent."
            UncertaintyReason.STALE_OBSERVATION -> "A historical observation does not establish the object's present location."
        }
        return ToolResult(ToolStatus.SUCCESS, nowMs, UncertaintyReport(reason, message), update.liveEvidence)
    }

    fun getLocation(nowMs: Long): ToolResult<Unit> = unavailable("LOCATION_UNAVAILABLE", "This core has no GPS/location provider.", nowMs)
    fun measureDepth(nowMs: Long): ToolResult<Unit> = unavailable("DEPTH_UNAVAILABLE", "No calibrated depth input is connected.", nowMs)
    fun requestRoute(nowMs: Long): ToolResult<Unit> = unavailable("ROUTING_UNAVAILABLE", "No routing provider is connected to this core.", nowMs)

    private fun <T> observed(data: T, evidence: Evidence, nowMs: Long, confidence: Float? = null): ToolResult<T> =
        if (nowMs < evidence.capturedAtMs) ToolResult(ToolStatus.ERROR, nowMs, errors = listOf(ToolError("CLOCK_REGRESSION", "The requested time precedes the observation timestamp.")))
        else if (evidence.timeBasis == TimeBasis.RECEIPT) ToolResult(ToolStatus.NEEDS_CONFIRMATION, nowMs, data, evidence, confidence,
            listOf(ToolError("EXPOSURE_CLOCK_UNVERIFIED", "Freshness is measured from frame receipt; sensor-to-receipt delay has not been validated.")))
        else ToolResult(ToolStatus.SUCCESS, nowMs, data, evidence, confidence)

    private fun <T> unavailableScene(update: EngineUpdate, nowMs: Long): ToolResult<T> =
        if (update.reliability == ReliabilityState.VISUAL_INPUT_UNAVAILABLE) ToolResult(ToolStatus.UNAVAILABLE, nowMs,
            errors = listOf(ToolError("CAMERA_UNAVAILABLE", "Visual input is unavailable.")))
        else ToolResult(ToolStatus.NO_EVIDENCE, nowMs, errors = listOf(ToolError("NO_FRESH_VIEW", "No current camera observation is established.")))

    private fun unavailable(code: String, message: String, nowMs: Long): ToolResult<Unit> =
        ToolResult(ToolStatus.UNAVAILABLE, nowMs, errors = listOf(ToolError(code, message)))
}
