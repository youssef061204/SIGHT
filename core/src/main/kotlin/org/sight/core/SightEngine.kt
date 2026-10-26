package org.sight.core

import java.util.Locale

/**
 * Deterministic device-independent perception/intent loop. All calls share a monotonic millisecond
 * clock with Evidence.capturedAtMs. No raw images, network operations or world coordinates are held.
 * Public methods are serialized so camera, lifecycle and voice callbacks may safely share an engine.
 */
class SightEngine(
    supportedCategories: Set<String> = CocoCategories.all,
    private val config: EngineConfig = EngineConfig(),
) {
    private val supported = supportedCategories.map(::canonical).toSet()
    private val tracker = ImageTracker(config)
    private val world = TemporalWorld(config)
    private var currentGoal = GoalState()
    private var resumableGoal: GoalState? = null
    private var inputAvailable = true
    private var captureCutoffMs = 0L
    private var lastNowMs = 0L
    private var lastFrame: Evidence? = null
    private var sourceId: String? = null
    private var candidateTrackId: Long? = null
    private var candidateFrames = 0
    private var confirmedTrackId: Long? = null
    private val rejectedTrackIds = linkedSetOf<Long>()
    private var lastNarrationAtMs: Long? = null
    private var lastHintAtMs: Long? = null
    private var lastOcrHintAtMs: Long? = null
    private val spoken = linkedMapOf<String, Long>()
    private val memories = ArrayDeque<MemorySnapshot>()
    private var nextMemoryId = 1L
    private var lastText: String? = null
    private var lastTextEvidence: Evidence? = null
    private var lastOcrCallbackEvidence: Evidence? = null
    private var lastTextIsSnapshot = false
    private var lastTextBlocks: List<String> = emptyList()
    private var narrationProfile = NarrationProfile.STANDARD
    private var readIntent = ReadIntent.ALL
    private var reinspectionAfterMs: Long? = null
    private val traces = ArrayDeque<DecisionTrace>()

    @Synchronized
    fun onFrame(frame: FrameObservation, nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        val evidence = frame.evidence
        val previous = lastFrame
        val reject = when {
            !inputAvailable -> "INPUT_UNAVAILABLE" to "Result discarded while visual input is unavailable."
            evidence.capturedAtMs < captureCutoffMs -> "LIFECYCLE_RESULT" to "Result was captured before the current input session."
            evidence.capturedAtMs > nowMs -> "FUTURE_FRAME" to "Capture timestamp is ahead of the shared monotonic clock."
            nowMs - evidence.capturedAtMs > config.maxFrameAgeMs -> "STALE_FRAME" to "Capture is too old to describe as live."
            previous != null && evidence.capturedAtMs <= previous.capturedAtMs ->
                "OUT_OF_ORDER_FRAME" to "Duplicate or out-of-order capture timestamp discarded."
            previous != null && previous.sourceId == evidence.sourceId && previous.modelId == evidence.modelId &&
                previous.geometryEpoch == evidence.geometryEpoch && evidence.frameId <= previous.frameId ->
                "OUT_OF_ORDER_FRAME" to "Duplicate or out-of-order frame identifier discarded."
            else -> null
        }
        if (reject != null) {
            trace(reject.first, reject.second, evidence)
            return update(expireGoal(nowMs))
        }
        if (previous != null && (previous.sourceId != evidence.sourceId || previous.modelId != evidence.modelId || previous.geometryEpoch != evidence.geometryEpoch)) {
            tracker.clear()
            world.breakContinuity()
            captureCutoffMs = maxOf(captureCutoffMs, evidence.capturedAtMs)
            lastOcrCallbackEvidence = null
            resetCandidate()
            confirmedTrackId = null
            spoken.clear()
            if (currentGoal.type == GoalType.FIND && currentGoal.phase == GoalPhase.COMPLETED && config.continueFindAfterMatch)
                currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, continuing = true, deadlineAtMs = nowMs + config.findTimeoutMs)
            trace(if (previous.sourceId != evidence.sourceId) "SOURCE_CHANGED" else "PERCEPTION_CHANGED", "Image identities reset because source, model or oriented geometry changed.", evidence)
        }
        sourceId = evidence.sourceId
        lastFrame = evidence
        val detections = frame.detections.sortedByDescending { it.confidence }.take(config.maxDetections).map { it.copy(category = canonical(it.category)) }
        if (detections.any { it.category !in supported }) trace("UNSUPPORTED_DETECTION_LABEL", "Detector labels outside the declared category vocabulary were discarded.", evidence)
        val normalized = frame.copy(detections = detections.filter { it.category in supported })
        val entities = tracker.update(normalized, nowMs)
        world.observe(entities, evidence, nowMs)
        trace("FRAME_ACCEPTED", "Fresh source evidence admitted to the image tracker.", evidence)
        if (evidence.timeBasis == TimeBasis.RECEIPT) trace("RECEIPT_TIME_ONLY", "Only time since camera-frame receipt is gated; exposure clock alignment and sensor-to-receipt delay are unverified.", evidence)
        val texts = frame.ocr.filter { it.text.isNotBlank() && (it.confidence == null || it.confidence >= 0.5f) }
            .sortedWith(compareBy<TextObservation> { it.box.top }.thenBy { it.box.left }).take(100)
        if (texts.isNotEmpty()) {
            lastTextBlocks = texts.flatMap { it.text.trim().split(Regex("\\n\\s*\\n")) }.filter { it.isNotBlank() }.take(100)
            lastText = texts.joinToString("\n") { it.text.trim() }.take(8_000)
            lastTextEvidence = evidence
            lastTextIsSnapshot = false
        }
        val timeout = expireGoal(nowMs)
        if (timeout.isNotEmpty()) return update(timeout)
        if (reinspectionAfterMs?.let { evidence.capturedAtMs < it } == true) {
            trace("PRE_REINSPECTION_EVIDENCE", "Previously captured frame cannot answer a fresh-view request.", evidence)
            return update(emptyList())
        }
        reinspectionAfterMs = null
        val announcements = when {
            currentGoal.type == GoalType.FIND && (currentGoal.phase in setOf(GoalPhase.SEARCHING, GoalPhase.VERIFYING) ||
                currentGoal.phase == GoalPhase.COMPLETED && config.continueFindAfterMatch) ->
                processFind(entities, evidence, nowMs)
            currentGoal.type == GoalType.READ && currentGoal.phase == GoalPhase.SEARCHING ->
                if (texts.isNotEmpty() && evidence.capturedAtMs >= currentGoal.startedAtMs) processRead(lastText.orEmpty(), evidence, nowMs) else emptyList()
            currentGoal.type == GoalType.EXPLORE && currentGoal.phase == GoalPhase.ACTIVE -> narrate(entities, evidence, nowMs)
            else -> emptyList()
        }
        return update(announcements)
    }

    /** Independent asynchronous OCR result, retaining its actual image capture time and geometry. */
    @Synchronized
    fun onTextObservation(evidence: Evidence, text: List<TextObservation>, nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        val current = lastFrame
        val prior = lastOcrCallbackEvidence
        val reject = when {
            !inputAvailable || current == null -> "OCR_INPUT_UNAVAILABLE" to "No active camera session can accept captured text."
            evidence.sourceId != current.sourceId || evidence.geometryEpoch != current.geometryEpoch || evidence.capturedAtMs < captureCutoffMs ->
                "OCR_SESSION_CHANGED" to "OCR belongs to another camera session or oriented geometry."
            evidence.capturedAtMs > nowMs -> "OCR_FUTURE" to "OCR capture timestamp is ahead of the shared clock."
            nowMs - evidence.capturedAtMs > config.maxOcrAgeMs -> "OCR_TOO_OLD" to "Captured text exceeded the separately bounded snapshot age."
            currentGoal.type != GoalType.READ || currentGoal.phase != GoalPhase.SEARCHING -> "OCR_GOAL_INACTIVE" to "No pending Read task can consume this OCR result."
            evidence.capturedAtMs < currentGoal.startedAtMs -> "OCR_PRE_GOAL" to "OCR was captured before the current Read request."
            prior != null && prior.sourceId == evidence.sourceId && prior.geometryEpoch == evidence.geometryEpoch &&
                (evidence.capturedAtMs <= prior.capturedAtMs || evidence.frameId <= prior.frameId) -> "OCR_OUT_OF_ORDER" to "Duplicate or out-of-order OCR callback discarded."
            else -> null
        }
        if (reject != null) { trace(reject.first, reject.second, evidence); return update(expireGoal(nowMs)) }
        lastOcrCallbackEvidence = evidence
        val admitted = text.filter { it.text.isNotBlank() && (it.confidence == null || it.confidence >= 0.5f) }
            .sortedWith(compareBy<TextObservation> { it.box.top }.thenBy { it.box.left }).take(100)
        if (admitted.isEmpty()) {
            trace("OCR_EMPTY", "No reliable text was recognized; object observations remain independent.", evidence)
            val expired = expireGoal(nowMs)
            if (expired.isNotEmpty()) return update(expired)
            if (config.enableActivePerception && nowMs - (lastOcrHintAtMs ?: currentGoal.startedAtMs) >= 6_000) {
                lastOcrHintAtMs = nowMs
                return update(listOf(status("No reliable text was recognized in that captured view. Hold the camera steady with the text in view, then ask me to check again.", AnnouncementKind.UNCERTAINTY)))
            }
            return update(emptyList())
        }
        lastTextBlocks = admitted.flatMap { it.text.trim().split(Regex("\\n\\s*\\n")) }.filter { it.isNotBlank() }.take(100)
        lastText = admitted.joinToString("\n") { it.text.trim() }.take(8_000)
        lastTextEvidence = evidence
        lastTextIsSnapshot = true
        trace("OCR_SNAPSHOT_ACCEPTED", "Captured text admitted without updating detector tracks or pretending its capture is current.", evidence)
        val expired = expireGoal(nowMs)
        return update(if (expired.isNotEmpty()) expired else processRead(lastText.orEmpty(), evidence, nowMs, capturedSnapshot = true))
    }

    @Synchronized
    fun tick(nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        return update(expireGoal(nowMs))
    }

    @Synchronized
    fun setInputAvailable(available: Boolean, nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        if (available == inputAvailable) return update(emptyList())
        inputAvailable = available
        captureCutoffMs = nowMs
        tracker.expire(nowMs, hide = true)
        if (available) {
            // Device adapters may restart their per-session frame counter. No image identity is
            // assumed to survive an unobserved pause, even if the physical camera is unchanged.
            tracker.clear()
            world.breakContinuity()
            lastFrame = null
            lastOcrCallbackEvidence = null
            sourceId = null
            spoken.clear()
            lastNarrationAtMs = null
            if (currentGoal.type == GoalType.FIND && currentGoal.phase == GoalPhase.COMPLETED && config.continueFindAfterMatch)
                currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, continuing = true, deadlineAtMs = nowMs + config.findTimeoutMs)
        }
        resetCandidate()
        confirmedTrackId = null
        trace("INPUT_LIFECYCLE", if (available) "Waiting for a newly captured frame." else "Live evidence invalidated because visual input is unavailable.")
        return update(listOf(status(if (available) "Camera input resumed. Waiting for a fresh view." else "Camera input is unavailable. Visual descriptions are paused.")))
    }

    @Synchronized
    fun command(text: String, nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        val command = text.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        if (command.isEmpty()) return update(listOf(status("Say explore, find an object, read, remember this as a name, or cancel.")))
        val announcements = when {
            command in setOf("cancel", "stop", "stop task", "cancel task", "pause") -> {
                if (currentGoal.type != GoalType.IDLE && currentGoal.phase !in setOf(GoalPhase.CANCELLED, GoalPhase.UNSUPPORTED)) resumableGoal = currentGoal
                currentGoal = currentGoal.copy(phase = if (command == "pause") GoalPhase.PAUSED else GoalPhase.CANCELLED, message = "Task stopped by the user.")
                resetCandidate()
                listOf(status("Task stopped."))
            }
            command in setOf("resume", "continue", "continue task", "resume task") -> resume(nowMs)
            command in setOf("not that one", "wrong object", "that is wrong", "that's wrong", "reject that", "try another") -> rejectCandidate(nowMs)
            command in setOf("inspect again", "reinspect", "check again", "look again", "verify again") -> reinspect(nowMs)
            command in setOf("what was on my left", "what was on the left") -> recallSide(ImageDirection.LEFT, nowMs)
            command in setOf("what was on my right", "what was on the right") -> recallSide(ImageDirection.RIGHT, nowMs)
            command in setOf("what changed", "what has changed", "what changed since i last looked") -> describeChanges(nowMs)
            command in setOf("what did you see a moment ago", "what did you just see", "what was in the previous view") -> recallRecent(nowMs)
            Regex("^(?:was there|did you see) (?:a |an |the )?(.+?)(?: in the previous view| a moment ago)?$").matches(command) -> queryHistoricalCategory(command, nowMs)
            command in setOf("what is directly in front of me", "what's directly in front of me", "what is in front of me") -> describeCenter(nowMs)
            command in setOf("what is on my left", "what's on my left", "what is on the left") -> describeSide(ImageDirection.LEFT, nowMs)
            command in setOf("what is on my right", "what's on my right", "what is on the right") -> describeSide(ImageDirection.RIGHT, nowMs)
            command in setOf("which objects are on the table", "what is on the table") -> describeTableLayout(nowMs)
            command in setOf("is that chair occupied", "is the chair occupied") -> listOf(status("Image boxes do not establish chair occupancy. I do not have a validated occupancy model.", AnnouncementKind.UNCERTAINTY))
            command.startsWith("is there ") -> visualCategoryQuestion(command.removePrefix("is there "), nowMs)
            Regex("^(?:can you )?(?:find|look for) (?:that |the )?same (.+?)(?: again)?$").matches(command) -> startSameTrackFind(command, nowMs)
            command in setOf("explore", "explore my surroundings", "describe", "describe the scene", "what's ahead", "what is ahead", "what do you see", "look around") -> {
                currentGoal = GoalState(GoalType.EXPLORE, GoalPhase.ACTIVE, startedAtMs = nowMs)
                lastNarrationAtMs = null
                spoken.clear()
                val evidence = freshEvidence(nowMs)
                if (evidence != null) narrate(tracker.snapshot(), evidence, nowMs).ifEmpty {
                    listOf(status("Exploring. Waiting for objects to persist across fresh camera frames."))
                } else listOf(status("Explore started. Waiting for a fresh camera view."))
            }
            (command.startsWith("find ") && command != "find the expiration date") || command.startsWith("look for ") -> startFind(command.substringAfter(if (command.startsWith("find ")) "find " else "look for "), nowMs)
            command in setOf("read", "read everything", "read this", "read text", "what does that sign say", "what does this sign say", "what is written here", "just tell me the price", "what is the price", "find the expiration date", "read the price", "read the date") -> {
                readIntent = when {
                    "price" in command -> ReadIntent.PRICE
                    "date" in command -> ReadIntent.DATE
                    else -> ReadIntent.ALL
                }
                currentGoal = GoalState(GoalType.READ, GoalPhase.SEARCHING, message = "Waiting for fresh recognized text.", startedAtMs = nowMs, deadlineAtMs = nowMs + config.readTimeoutMs)
                lastOcrHintAtMs = null
                listOf(status("Reading started. Hold the camera steady with text in view."))
            }
            command.startsWith("remember ") || command.startsWith("save this as ") -> remember(command, nowMs)
            command in setOf("forget everything", "forget all", "delete memories", "clear memory") -> {
                memories.clear()
                world.clear(nowMs)
                listOf(status("Session landmark memories deleted."))
            }
            command.startsWith("forget ") -> {
                val name = command.removePrefix("forget ").trim()
                val countBefore = memories.size
                memories.removeAll { it.name.equals(name, ignoreCase = true) }
                listOf(status(if (memories.size < countBefore) "Deleted the session memory named $name." else "There is no session memory named $name."))
            }
            command in setOf("what was that sign", "repeat text", "read that again", "repeat the text") -> recallText(nowMs)
            Regex("^read (?:the )?(first|second|third|fourth|fifth|\\d+) (?:paragraph|block)(?: again)?$").matches(command) -> recallTextBlock(command, nowMs)
            command in setOf("what does this warning mean", "what does the warning mean", "explain the warning") -> listOf(status("I can quote recognized warning text, but do not have a validated interpretation tool. ${lastText?.let { "Previously recognized text: ${it.take(1_000)}" } ?: "Ask me to read the warning first."}", AnnouncementKind.MEMORY))
            command.startsWith("where was ") || command.startsWith("where did you last see ") || command.startsWith("where is ") || command.startsWith("recall ") -> recall(command, nowMs)
            command.contains("closer") || command.contains("farther") || command.contains("distance") -> listOf(status("I do not have validated depth measurements, so I cannot compare physical distance.", AnnouncementKind.UNCERTAINTY))
            command in setOf("list memories", "what do you remember") -> listOf(status(if (memories.isEmpty()) "No named scenes are saved in this session." else "Saved session scenes: ${memories.joinToString { it.name }}.", AnnouncementKind.MEMORY))
            else -> listOf(status("I can explore, find supported object classes, read text, remember a scene, recall it, or cancel. Try 'find a chair'.", AnnouncementKind.UNCERTAINTY))
        }
        return update(announcements)
    }

    @Synchronized
    fun setNarrationProfile(profile: NarrationProfile, nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        narrationProfile = profile
        return update(listOf(status("Narration set to ${profile.name.lowercase(Locale.ROOT)}.")))
    }

    @Synchronized
    fun clearMemory(nowMs: Long): EngineUpdate {
        if (!advance(nowMs)) return update(emptyList())
        memories.clear()
        world.clear(nowMs)
        return update(listOf(status("Session landmark memories deleted.")))
    }

    /** Final speech admission check; call again immediately before speaking queued live output. */
    @Synchronized
    fun isAnnouncementValid(announcement: Announcement, nowMs: Long): Boolean {
        if (nowMs < lastNowMs) return false
        if (announcement.kind == AnnouncementKind.MEMORY) return announcement.evidence?.let { it.capturedAtMs <= nowMs } ?: true
        if (announcement.kind in setOf(AnnouncementKind.MEMORY, AnnouncementKind.STATUS, AnnouncementKind.UNCERTAINTY) && announcement.evidence == null) return true
        val evidence = announcement.evidence ?: return false
        if (currentGoal.phase in setOf(GoalPhase.CANCELLED, GoalPhase.PAUSED)) return false
        if (announcement.kind == AnnouncementKind.OCR && lastTextIsSnapshot && evidence == lastTextEvidence) {
            return inputAvailable && evidence.sourceId == sourceId && evidence.geometryEpoch == lastFrame?.geometryEpoch &&
                evidence.capturedAtMs >= captureCutoffMs && evidence.capturedAtMs <= nowMs &&
                nowMs - evidence.capturedAtMs <= config.maxOcrAgeMs &&
                (announcement.validUntilMs == null || nowMs <= announcement.validUntilMs)
        }
        if (!inputAvailable || evidence.sourceId != sourceId || evidence.capturedAtMs < captureCutoffMs ||
            evidence.modelId != lastFrame?.modelId || evidence.geometryEpoch != lastFrame?.geometryEpoch) return false
        if (nowMs < evidence.capturedAtMs || nowMs - evidence.capturedAtMs > config.maxFrameAgeMs) return false
        if (announcement.validUntilMs != null && nowMs > announcement.validUntilMs) return false
        val current = tracker.snapshot().associateBy { it.id }
        val claimsSupported = announcement.requiredRelationships.all { required ->
            relationships(current.values.toList(), lastFrame ?: evidence).any {
                it.subjectId == required.subjectId && it.objectId == required.objectId && it.kind == required.kind
            }
        }
        return claimsSupported && announcement.grounding.all { grounding ->
            val entity = current[grounding.trackId]
            entity != null && entity.visible && nowMs - entity.lastSeenAtMs <= config.maxFrameAgeMs &&
                confident(entity) && entity.category == grounding.category && entity.direction == grounding.direction
        } && announcement.trackIds.all { id -> current[id]?.let { it.visible && confident(it) && nowMs - it.lastSeenAtMs <= config.maxFrameAgeMs } == true }
    }

    private fun processFind(entities: List<TrackedEntity>, evidence: Evidence, nowMs: Long): List<Announcement> {
        val target = currentGoal.target ?: return emptyList()
        if (evidence.capturedAtMs < currentGoal.startedAtMs) {
            trace("PRE_GOAL_EVIDENCE", "Frame may update the scene but cannot verify a task started after its capture.", evidence)
            return emptyList()
        }
        val policy = config.confirmationFor(target)
        val categoryCandidates = entities.filter { it.visible && it.category == target && confident(it) && it.id !in rejectedTrackIds }
        val candidates = categoryCandidates.filter { currentGoal.identityTrackId == null || currentGoal.identityTrackId == it.id }
        currentGoal = currentGoal.copy(candidateCount = categoryCandidates.size)
        if (currentGoal.phase == GoalPhase.COMPLETED) {
            if (candidates.any { it.id == confirmedTrackId }) return emptyList()
            val last = confirmedTrackId
            resetCandidate()
            currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, continuing = true,
                deadlineAtMs = nowMs + config.findTimeoutMs, message = "Previously confirmed candidate is no longer detected; search continues.")
            trace("FIND_LOST", "Confirmed image track $last is not currently detected; no physical absence is asserted.", evidence)
            return listOf(status("The $target candidate is no longer reliably detected in this view. I am continuing the search; its current location is unknown.", AnnouncementKind.UNCERTAINTY))
        }
        val candidate = candidates.firstOrNull { it.id == candidateTrackId } ?: candidates.maxByOrNull { it.confidence }
        if (candidate == null) {
            resetCandidate()
            currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, message = if (currentGoal.identityTrackId != null && categoryCandidates.isNotEmpty())
                "Category candidates detected, but identity with the earlier image track cannot be established." else "Target not detected; absence is not established.")
            if (!config.enableActivePerception) return emptyList()
            val since = lastHintAtMs ?: currentGoal.startedAtMs
            if (nowMs - since < 6_000) return emptyList()
            lastHintAtMs = nowMs
            if (currentGoal.identityTrackId != null && categoryCandidates.isNotEmpty()) return listOf(status("I detect a $target category candidate, but cannot establish that it is the earlier object. Say 'find a $target' to start a category search.", AnnouncementKind.UNCERTAINTY))
            val surface = entities.firstOrNull { stable(it) && it.category in setOf("dining table", "chair", "couch") }
            return if (surface != null) listOf(live("I haven't detected a $target. A ${surface.category} is ${surface.direction.phrase} of the camera view; you can inspect that area with the camera.", SpeechPriority.TASK, AnnouncementKind.LIVE_SCENE, evidence, listOf(surface)))
            else listOf(status("I haven't detected a $target. A different camera view may provide more evidence.", AnnouncementKind.UNCERTAINTY))
        }
        if (candidateTrackId == candidate.id) candidateFrames++ else {
            candidateTrackId = candidate.id
            candidateFrames = 1
        }
        if (candidateFrames < policy.minimumFrames) {
            currentGoal = currentGoal.copy(phase = GoalPhase.VERIFYING, message = "Candidate detected; $candidateFrames of ${policy.minimumFrames} fresh matching observations.")
            trace("FIND_VERIFYING", "Candidate ${candidate.id}: $candidateFrames/${policy.minimumFrames} accepted post-command frames.", evidence)
            return emptyList()
        }
        val reacquired = currentGoal.continuing
        confirmedTrackId = candidate.id
        currentGoal = currentGoal.copy(phase = GoalPhase.COMPLETED, message = "Category candidate verified with ${policy.minimumFrames} fresh observations; monitoring continues.")
        trace(if (reacquired) "FIND_REACQUIRED" else "FIND_CONFIRMED", "Configured temporal evidence confirms a visible category candidate; physical identity/location remain unverified.", evidence)
        val identity = if (currentGoal.identityTrackId != null) "The earlier image-track hypothesis matches again; physical identity is unverified. " else if (reacquired) "A $target category candidate is detected again. " else ""
        val ambiguity = if (categoryCandidates.size > 1) "There are ${categoryCandidates.size} candidates; this describes one category match. " else ""
        val frameCount = if (policy.minimumFrames == 2) "two" else policy.minimumFrames.toString()
        return listOf(live("$identity${ambiguity}A $target is ${candidate.direction.phrase} of the camera view, seen in $frameCount frames.", SpeechPriority.TASK, AnnouncementKind.FIND_RESULT, evidence, listOf(candidate)))
    }

    private fun narrate(entities: List<TrackedEntity>, evidence: Evidence, nowMs: Long): List<Announcement> {
        if (lastNarrationAtMs?.let { nowMs - it < config.narrationIntervalMs } == true) return emptyList()
        val stable = entities.filter(::stable)
        val count = when (narrationProfile) { NarrationProfile.CONCISE -> 1; NarrationProfile.STANDARD -> 3; NarrationProfile.DETAILED -> 5 }
        val selected = stable.filter { entity -> entityKey(entity) !in spoken }
            .sortedWith(compareByDescending<TrackedEntity> { it.confidence }.thenBy { it.id }).take(count)
        if (selected.isEmpty()) {
            if (entities.any { it.visible }) trace("NARRATION_SUPPRESSED", "Observations need confidence, persistence, novelty or narration cooldown.", evidence)
            return emptyList()
        }
        selected.forEach { spoken[entityKey(it)] = nowMs }
        while (spoken.size > 256) spoken.remove(spoken.keys.first())
        lastNarrationAtMs = nowMs
        val description = selected.joinToString("; ") { "a ${it.category} ${it.direction.phrase}" }
        trace("NARRATION_SELECTED", "Stable, confident image observations selected for narration.", evidence)
        return listOf(live("In the camera view: $description.", SpeechPriority.AMBIENT, AnnouncementKind.LIVE_SCENE, evidence, selected))
    }

    private fun startFind(rawTarget: String, nowMs: Long): List<Announcement> {
        val target = rawTarget.trim().replace(Regex("^(?:a|an|the|my)\\s+"), "").take(120)
        val normalized = canonical(target)
        resetCandidate()
        confirmedTrackId = null
        rejectedTrackIds.clear()
        lastHintAtMs = null
        if (normalized in supported) {
            currentGoal = GoalState(GoalType.FIND, GoalPhase.SEARCHING, normalized, "Searching for fresh category evidence.", nowMs, nowMs + config.findTimeoutMs)
            return listOf(status("Looking for a $normalized. I will check a candidate across ${config.confirmationFor(normalized).minimumFrames} fresh frames."))
        }
        val category = supported.sortedByDescending { it.length }.firstOrNull { normalized.endsWith(" $it") }
        val message = if (category != null) "I can detect $category objects, but cannot verify '${normalized.removeSuffix(category).trim()}' characteristics. Try 'find a $category'."
        else "The local detector does not support '$target'. Try a supported object such as a chair, bottle, backpack, or person."
        currentGoal = GoalState(GoalType.FIND, GoalPhase.UNSUPPORTED, normalized, message, nowMs)
        trace("UNSUPPORTED_TARGET", message)
        return listOf(status(message, AnnouncementKind.UNCERTAINTY))
    }

    private fun startSameTrackFind(command: String, nowMs: Long): List<Announcement> {
        val raw = Regex("^(?:can you )?(?:find|look for) (?:that |the )?same (.+?)(?: again)?$").matchEntire(command)!!.groupValues[1]
        val category = canonical(raw)
        if (category !in supported) return startFind(raw, nowMs)
        val retained = tracker.snapshot().filter { it.category == category && confident(it) }
        val reference = retained.firstOrNull { it.id == confirmedTrackId } ?: retained.singleOrNull()
        if (reference == null) {
            trace("IDENTITY_UNAVAILABLE", "No unique retained image-track reference supports a same-object search.")
            return listOf(status("I cannot establish which earlier $category you mean or preserve identity across an expired or changed view. Say 'find a $category' for a new category search.", AnnouncementKind.UNCERTAINTY))
        }
        startFind(category, nowMs)
        currentGoal = currentGoal.copy(identityTrackId = reference.id, message = "Checking the retained image-track hypothesis; physical identity is unverified.")
        return listOf(status("Checking for the earlier $category image track across fresh frames. This cannot verify physical object identity."))
    }

    private fun rejectCandidate(nowMs: Long): List<Announcement> {
        if (currentGoal.type != GoalType.FIND || currentGoal.target == null) return listOf(status("There is no active object-search candidate to reject.", AnnouncementKind.UNCERTAINTY))
        val rejected = candidateTrackId ?: confirmedTrackId
        if (rejected != null) rejectedTrackIds += rejected
        while (rejectedTrackIds.size > config.maxTracks) rejectedTrackIds.remove(rejectedTrackIds.first())
        resetCandidate()
        confirmedTrackId = null
        currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, startedAtMs = nowMs, deadlineAtMs = nowMs + config.findTimeoutMs,
            candidateCount = 0, identityTrackId = null, message = "User rejected the image candidate; waiting for another fresh candidate.")
        trace("USER_REJECTED_CANDIDATE", "Rejected image track $rejected for this search; detector class labels were not rewritten.")
        return listOf(status(if (rejected != null) "I rejected that image-track candidate for this search. Looking for another ${currentGoal.target}." else "No candidate was confirmed. I will inspect fresh views for ${currentGoal.target}."))
    }

    private fun reinspect(nowMs: Long): List<Announcement> {
        resetCandidate()
        confirmedTrackId = null
        reinspectionAfterMs = nowMs
        return when (currentGoal.type) {
            GoalType.FIND -> {
                if (currentGoal.phase == GoalPhase.UNSUPPORTED) return listOf(status(currentGoal.message, AnnouncementKind.UNCERTAINTY))
                currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, startedAtMs = nowMs,
                    deadlineAtMs = nowMs + config.findTimeoutMs, message = "Reinspection requires newly captured task evidence.")
                listOf(status("Rechecking ${currentGoal.target}. Waiting for newly captured matching frames."))
            }
            GoalType.READ -> {
                currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, startedAtMs = nowMs, deadlineAtMs = nowMs + config.readTimeoutMs)
                listOf(status("Rechecking the text in a newly captured view. Hold the camera steady."))
            }
            else -> {
                currentGoal = GoalState(GoalType.EXPLORE, GoalPhase.ACTIVE, startedAtMs = nowMs)
                spoken.clear(); lastNarrationAtMs = null
                listOf(status("Reinspection started. Waiting for a newly captured scene."))
            }
        }
    }

    private fun recallSide(side: ImageDirection, nowMs: Long): List<Announcement> {
        val entities = world.historicalSide(side, nowMs).filter(::confident).take(5)
        if (entities.isEmpty()) return listOf(status("I have no retained reliable detection ${side.phrase} of an earlier camera view. Image-left is not a physical location.", AnnouncementKind.UNCERTAINTY))
        val descriptions = entities.joinToString("; ") { "a ${it.category}, ${ageSeconds(it.evidence, nowMs)} seconds ago" }
        return listOf(historical("Previously ${side.phrase} of the camera image: $descriptions. These are past image observations, not their current physical locations.", entities.first().evidence))
    }

    private fun recallRecent(nowMs: Long): List<Announcement> {
        val retained = world.snapshot(nowMs, tracker.snapshot()).filter { it.confirmedHistorically && confident(it.entity) }.take(5)
        if (retained.isEmpty()) return listOf(status("No reliable recent visual observations are retained.", AnnouncementKind.UNCERTAINTY))
        return listOf(historical("Recent detector observations: ${retained.joinToString("; ") { "a ${it.entity.category} ${it.entity.direction.phrase} of that camera image, ${it.ageMs / 1_000} seconds ago" }}. Past views do not establish present locations.", retained.first().entity.evidence))
    }

    private fun queryHistoricalCategory(command: String, nowMs: Long): List<Announcement> {
        val raw = command.removePrefix("was there ").removePrefix("did you see ")
            .replace(Regex("^(?:a|an|the)\\s+"), "").removeSuffix(" in the previous view").removeSuffix(" a moment ago")
        val category = canonical(raw)
        if (category !in supported) return listOf(status("The detector does not support '$raw', so the previous view cannot establish whether one was there.", AnnouncementKind.UNCERTAINTY))
        val entry = world.snapshot(nowMs, tracker.snapshot()).firstOrNull { it.confirmedHistorically && it.entity.category == category && confident(it.entity) }
            ?: return listOf(status("No reliable recent $category detection is retained. That does not establish it was absent.", AnnouncementKind.UNCERTAINTY))
        return listOf(historical("A $category was detected ${entry.ageMs / 1_000} seconds ago, ${entry.entity.direction.phrase} of that camera image. Its present physical location is unknown.", entry.entity.evidence))
    }

    private fun describeChanges(nowMs: Long): List<Announcement> {
        val changes = world.changes ?: return listOf(status("I need two retained camera observations to compare detected scene changes.", AnnouncementKind.UNCERTAINTY))
        if (!changes.identitiesComparable) return listOf(status("The camera session, model or image geometry changed. I cannot compare image identities across these views.", AnnouncementKind.UNCERTAINTY))
        val phrases = mutableListOf<String>()
        if (changes.newlyObserved.isNotEmpty()) phrases += "newly stable detections: ${changes.newlyObserved.take(5).joinToString { "${it.category} ${it.direction.phrase}" }}"
        if (changes.noLongerObserved.isNotEmpty()) phrases += "no longer stably detected in the later view: ${changes.noLongerObserved.take(5).joinToString { it.category }}"
        if (changes.changedImageSide.isNotEmpty()) phrases += changes.changedImageSide.take(5).joinToString { "${it.category} changed image position from ${it.from.phrase} to ${it.to.phrase}" }
        trace("TEMPORAL_QUERY", "Compared actual retained boxes; no physical movement or absence is inferred.", changes.currentEvidence)
        return listOf(historical("Comparing detector views from ${ageSeconds(changes.previousEvidence, nowMs)} and ${ageSeconds(changes.currentEvidence, nowMs)} seconds ago: ${phrases.joinToString("; ").ifEmpty { "no stable detector change was recorded" }}. Camera motion or missed detections can cause these differences.", changes.currentEvidence))
    }

    private fun describeCenter(nowMs: Long): List<Announcement> {
        val evidence = freshEvidence(nowMs) ?: return listOf(status("I need a fresh camera view to describe its center.", AnnouncementKind.UNCERTAINTY))
        val centered = tracker.snapshot().filter { stable(it) && it.direction == ImageDirection.CENTER }.take(5)
        if (centered.isEmpty()) return listOf(status("No stable supported object is detected in the image center. This does not establish a clear path.", AnnouncementKind.UNCERTAINTY))
        return listOf(live("In the camera image center: ${centered.joinToString { "a ${it.category}" }}. The image center does not establish your physical direction or a clear path.", SpeechPriority.USER_REQUEST, AnnouncementKind.LIVE_SCENE, evidence, centered))
    }

    private fun describeTableLayout(nowMs: Long): List<Announcement> {
        val evidence = freshEvidence(nowMs)
            ?: return listOf(status("Image boxes do not establish physical support. A fresh view is needed to describe their image layout.", AnnouncementKind.UNCERTAINTY))
        val stable = tracker.snapshot().filter(::stable)
        val table = stable.firstOrNull { it.category == "dining table" }
            ?: return listOf(status("Image boxes do not establish physical support, and no stable dining-table detection is established in this view.", AnnouncementKind.UNCERTAINTY))
        val graph = relationships(stable, evidence)
        val related = stable.filter { entity -> entity.id != table.id && graph.any { relation ->
            relation.kind == RelationshipKind.OVERLAPS_IN_IMAGE && setOf(relation.subjectId, relation.objectId) == setOf(entity.id, table.id) ||
                relation.kind == RelationshipKind.ABOVE_IN_IMAGE && relation.subjectId == entity.id && relation.objectId == table.id
        } }.take(5)
        if (related.isEmpty()) return listOf(live("A dining table is detected ${table.direction.phrase} of the image. I cannot establish which objects are physically on it.", SpeechPriority.USER_REQUEST, AnnouncementKind.LIVE_SCENE, evidence, listOf(table)))
        val descriptions = related.joinToString("; ") { entity ->
            val overlap = entity.box.iou(table.box) > 0.1f
            "a ${entity.category} box ${if (overlap) "overlaps" else "is above"} the dining-table box in the image"
        }
        val claims = graph.filter { relation -> related.any { entity ->
            relation.kind == RelationshipKind.OVERLAPS_IN_IMAGE && setOf(relation.subjectId, relation.objectId) == setOf(entity.id, table.id) ||
                relation.kind == RelationshipKind.ABOVE_IN_IMAGE && relation.subjectId == entity.id && relation.objectId == table.id
        } }
        return listOf(live("Image boxes do not establish physical support. $descriptions. This does not verify that these objects are on the table.", SpeechPriority.USER_REQUEST, AnnouncementKind.LIVE_SCENE, evidence, related + table, claims))
    }

    private fun describeSide(side: ImageDirection, nowMs: Long): List<Announcement> {
        val evidence = freshEvidence(nowMs) ?: return listOf(status("I need a fresh camera view to describe its ${side.name.lowercase(Locale.ROOT)} side.", AnnouncementKind.UNCERTAINTY))
        val selected = tracker.snapshot().filter { stable(it) && it.direction == side }.take(5)
        if (selected.isEmpty()) return listOf(status("No stable supported object is detected ${side.phrase} of the camera image. That does not establish absence.", AnnouncementKind.UNCERTAINTY))
        return listOf(live("${side.phrase.replaceFirstChar { it.uppercase() }} of the camera image: ${selected.joinToString { "a ${it.category}" }}. Image direction is relative to the camera view.", SpeechPriority.USER_REQUEST, AnnouncementKind.LIVE_SCENE, evidence, selected))
    }

    private fun visualCategoryQuestion(raw: String, nowMs: Long): List<Announcement> {
        val category = canonical(raw.replace(Regex("^(?:a|an|the)\\s+"), ""))
        if (category !in supported) {
            val known = supported.sortedByDescending { it.length }.firstOrNull { category.endsWith(" $it") }
            return listOf(status(if (known != null) "I can detect $known category candidates but cannot verify the requested attributes."
                else "The local detector does not support '$category'; this view cannot establish it.", AnnouncementKind.UNCERTAINTY))
        }
        val evidence = freshEvidence(nowMs) ?: return listOf(status("I need a fresh camera observation to answer that.", AnnouncementKind.UNCERTAINTY))
        val entities = tracker.snapshot().filter { stable(it) && it.category == category }
        if (entities.isEmpty()) return listOf(status("No stable $category detection is established in this view. That does not establish absence.", AnnouncementKind.UNCERTAINTY))
        return listOf(live("A $category category candidate is detected ${entities.first().direction.phrase} of the camera view.", SpeechPriority.USER_REQUEST, AnnouncementKind.LIVE_SCENE, evidence, entities.take(1)))
    }

    private fun ageSeconds(evidence: Evidence, nowMs: Long): Long = (nowMs - evidence.capturedAtMs).coerceAtLeast(0) / 1_000
    private fun historical(text: String, evidence: Evidence): Announcement = Announcement(text, SpeechPriority.USER_REQUEST, evidence, AnnouncementKind.MEMORY)

    private fun resume(nowMs: Long): List<Announcement> {
        val previous = resumableGoal ?: return listOf(status("There is no paused or cancelled task to resume."))
        resumableGoal = null
        return when (previous.type) {
            GoalType.FIND -> startFind(previous.target.orEmpty(), nowMs)
            GoalType.READ -> {
                currentGoal = previous.copy(phase = GoalPhase.SEARCHING, startedAtMs = nowMs, deadlineAtMs = nowMs + config.readTimeoutMs)
                listOf(status("Reading resumed. Waiting for fresh recognized text."))
            }
            GoalType.EXPLORE -> {
                currentGoal = previous.copy(phase = GoalPhase.ACTIVE, startedAtMs = nowMs)
                lastNarrationAtMs = null
                listOf(status("Explore resumed."))
            }
            GoalType.IDLE -> listOf(status("There is no task to resume."))
        }
    }

    private fun processRead(text: String, evidence: Evidence, nowMs: Long, capturedSnapshot: Boolean = false): List<Announcement> {
        val result = when (readIntent) {
            ReadIntent.ALL -> "Recognized text: ${text.take(2_000)}${if (text.length > 2_000) ". More text is available in the session transcript." else ""}"
            ReadIntent.PRICE -> {
                val prices = Regex("(?:[$€£]\\s?\\d+(?:[.,]\\d{2})?|\\d+[.,]\\d{2}\\s?(?:USD|CAD|EUR|GBP))", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.distinct().take(10).toList()
                if (prices.isEmpty()) "I recognized text but did not identify a clearly marked price. You can ask me to read everything."
                else "Price-like text: ${prices.joinToString()}. Verify the label before relying on it."
            }
            ReadIntent.DATE -> {
                val lines = text.lines().filter { Regex("\\b(?:exp|expiry|expires|expiration|best before|use by)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                if (lines.isEmpty()) "I recognized text but could not identify a labelled expiration date. You can ask me to read everything."
                else "Date label text: ${lines.joinToString("; ").take(800)}. I have not verified the date format."
            }
        }
        currentGoal = currentGoal.copy(phase = GoalPhase.COMPLETED, message = "Returned OCR evidence, without ungrounded visual interpretation.")
        trace("OCR_READ", "Fresh text returned; reading order is top-to-bottom image order and may not preserve columns.", evidence)
        val maxAge = if (capturedSnapshot) config.maxOcrAgeMs else config.maxFrameAgeMs
        return listOf(Announcement(if (capturedSnapshot) "Captured text: $result" else result, SpeechPriority.USER_REQUEST, evidence, AnnouncementKind.OCR,
            minOf(nowMs + maxAge, evidence.capturedAtMs + maxAge), dedupKey = "ocr:${evidence.sourceId}:${evidence.frameId}"))
    }

    private fun remember(command: String, nowMs: Long): List<Announcement> {
        val evidence = freshEvidence(nowMs) ?: return listOf(status("I need a fresh camera observation before saving a scene.", AnnouncementKind.UNCERTAINTY))
        val visible = tracker.snapshot().filter(::stable)
        val text = if (lastTextEvidence == evidence) lastText else null
        if (visible.isEmpty() && text == null) return listOf(status("There is not enough confirmed visual evidence to remember this scene yet.", AnnouncementKind.UNCERTAINTY))
        val name = command.removePrefix("remember ").removePrefix("save this as ")
            .removePrefix("this as ").removePrefix("this ").trim().take(80).ifBlank { "scene ${nextMemoryId}" }
        memories.removeAll { it.name.equals(name, ignoreCase = true) }
        memories.addLast(MemorySnapshot(nextMemoryId++, name, nowMs, evidence, visible.take(20).map { RememberedEntity(it.category, it.direction, it.confidence) }, text?.take(2_000)))
        while (memories.size > config.maxMemories) memories.removeFirst()
        trace("MEMORY_SAVED", "User-named historical scene metadata saved in session memory; label is not a classification.", evidence)
        return listOf(status("Saved the observed scene as '$name' in this session. The name is your label; its present location will not be assumed.", AnnouncementKind.MEMORY))
    }

    private fun recall(command: String, nowMs: Long): List<Announcement> {
        val query = command.removePrefix("where did you last see ").removePrefix("where was ")
            .removePrefix("where is ").removePrefix("recall ").replace(Regex("^(?:a|an|the|my)\\s+"), "").trim()
        val normalized = canonical(query)
        val memory = memories.lastOrNull { it.name.equals(query, ignoreCase = true) || it.entities.any { entity -> entity.category == normalized } }
        if (memory != null) {
            val age = ((nowMs - memory.evidence.capturedAtMs).coerceAtLeast(0) / 1_000)
            val oldEntity = memory.entities.firstOrNull { it.category == normalized }
            val side = oldEntity?.let { " ${it.direction.phrase} of that earlier camera view" }.orEmpty()
            return listOf(status("The scene named '${memory.name}' was observed $age seconds ago$side. This is a saved observation; its current location is unknown.", AnnouncementKind.MEMORY))
        }
        val entity = (world.snapshot(nowMs, tracker.snapshot()).filter { it.confirmedHistorically }.map { it.entity } + tracker.snapshot().filter(::stable)).filter { it.category == normalized }.maxByOrNull { it.lastSeenAtMs }
        if (entity != null) {
            val age = ((nowMs - entity.lastSeenAtMs).coerceAtLeast(0) / 1_000)
            return listOf(status("I last observed a ${entity.category} $age seconds ago, ${entity.direction.phrase} of that camera view. I cannot confirm its current physical location.", AnnouncementKind.MEMORY))
        }
        return listOf(status("I do not have a recorded observation for '$query'. You can ask me to find a supported object.", AnnouncementKind.UNCERTAINTY))
    }

    private fun recallText(nowMs: Long): List<Announcement> {
        val text = lastText ?: return listOf(status("No recognized text is available in this session.", AnnouncementKind.UNCERTAINTY))
        val age = ((nowMs - (lastTextEvidence?.capturedAtMs ?: nowMs)).coerceAtLeast(0) / 1_000)
        return listOf(status("Text recognized $age seconds ago: ${text.take(2_000)}", AnnouncementKind.MEMORY))
    }

    private fun recallTextBlock(command: String, nowMs: Long): List<Announcement> {
        val token = Regex("first|second|third|fourth|fifth|\\d+").find(command)?.value ?: return emptyList()
        val number = when (token) { "first" -> 1; "second" -> 2; "third" -> 3; "fourth" -> 4; "fifth" -> 5; else -> token.toIntOrNull() ?: 0 }
        val block = lastTextBlocks.getOrNull(number - 1)
        if (block == null) return listOf(status("I do not have recognized text block $number. OCR does not establish document paragraphs reliably.", AnnouncementKind.UNCERTAINTY))
        val age = ((nowMs - (lastTextEvidence?.capturedAtMs ?: nowMs)).coerceAtLeast(0) / 1_000)
        return listOf(status("Recognized text block $number from $age seconds ago: ${block.take(2_000)}. These are OCR blocks; paragraph boundaries are unverified.", AnnouncementKind.MEMORY))
    }

    private fun expireGoal(nowMs: Long): List<Announcement> {
        val deadline = currentGoal.deadlineAtMs
        if (deadline != null && nowMs >= deadline && currentGoal.phase in setOf(GoalPhase.SEARCHING, GoalPhase.VERIFYING)) {
            currentGoal = currentGoal.copy(phase = GoalPhase.TIMED_OUT, message = "Task deadline reached without sufficient fresh evidence.")
            resetCandidate()
            return listOf(status(if (currentGoal.type == GoalType.FIND) "Search timed out without confirming a ${currentGoal.target}. That does not establish it is absent." else "Reading timed out without fresh recognized text. Try a steadier, closer camera view.", AnnouncementKind.UNCERTAINTY))
        }
        return emptyList()
    }

    private fun advance(nowMs: Long): Boolean {
        require(nowMs >= 0) { "Use nonnegative monotonic milliseconds" }
        if (nowMs < lastNowMs) {
            trace("CLOCK_REGRESSION", "Call ignored because the shared monotonic clock moved backwards.")
            return false
        }
        lastNowMs = nowMs
        tracker.expire(nowMs, hide = !inputAvailable)
        world.expire(nowMs)
        return true
    }

    private fun freshEvidence(nowMs: Long): Evidence? = lastFrame?.takeIf {
        inputAvailable && it.capturedAtMs >= captureCutoffMs && nowMs >= it.capturedAtMs && nowMs - it.capturedAtMs <= config.maxFrameAgeMs
    }

    private fun update(announcements: List<Announcement>): EngineUpdate {
        val entities = tracker.snapshot()
        val evidence = freshEvidence(lastNowMs)
        val reliability = when {
            !inputAvailable -> ReliabilityState.VISUAL_INPUT_UNAVAILABLE
            evidence == null -> ReliabilityState.NEEDS_CONFIRMATION
            currentGoal.phase == GoalPhase.UNSUPPORTED -> ReliabilityState.UNSUPPORTED_REQUEST
            evidence.timeBasis == TimeBasis.RECEIPT -> ReliabilityState.REDUCED_CAPABILITY
            currentGoal.phase == GoalPhase.VERIFYING -> ReliabilityState.NEEDS_CONFIRMATION
            else -> ReliabilityState.OPERATIONAL
        }
        return EngineUpdate(entities, if (evidence != null) relationships(entities, evidence) else emptyList(), currentGoal, reliability,
            announcements, memories.toList(), traces.toList(), lastText, narrationProfile, evidence, lastTextEvidence,
            world.snapshot(lastNowMs, entities), world.changes)
    }

    private fun live(text: String, priority: SpeechPriority, kind: AnnouncementKind, evidence: Evidence, entities: List<TrackedEntity>, claims: List<SceneRelationship> = emptyList()): Announcement =
        Announcement(text, priority, evidence, kind, evidence.capturedAtMs + config.maxFrameAgeMs,
            entities.map { it.id }.toSet(), "$kind:${entities.joinToString { entityKey(it) }}", entities.map { Grounding(it.id, it.category, it.direction) }, claims)

    private fun status(text: String, kind: AnnouncementKind = AnnouncementKind.STATUS): Announcement = Announcement(text, SpeechPriority.USER_REQUEST, kind = kind)
    private fun entityKey(entity: TrackedEntity): String = "${entity.id}:${entity.direction}"
    private fun confident(entity: TrackedEntity): Boolean = entity.confidence >= config.announcementThreshold &&
        entity.aggregateConfidence >= (config.confirmationFor(entity.category).minimumAggregateConfidence ?: config.announcementThreshold)
    private fun stable(entity: TrackedEntity): Boolean = entity.visible && confident(entity) &&
        entity.consecutiveObservations >= config.confirmationFor(entity.category).minimumFrames
    private fun resetCandidate() { candidateTrackId = null; candidateFrames = 0 }
    private fun trace(code: String, message: String, evidence: Evidence? = null) {
        traces.addLast(DecisionTrace(code, message, evidence))
        while (traces.size > 30) traces.removeFirst()
    }

    private enum class ReadIntent { ALL, PRICE, DATE }
}

private fun canonical(input: String): String {
    val text = input.lowercase(Locale.ROOT).trim()
    return when (text) {
        "sofa", "sofas", "couches" -> "couch"
        "table", "tables", "dining tables" -> "dining table"
        "television", "televisions", "tvs" -> "tv"
        "phone", "phones", "cell phones", "mobile phone" -> "cell phone"
        "people", "persons" -> "person"
        "chairs" -> "chair"
        "bottles" -> "bottle"
        "backpacks" -> "backpack"
        "buses" -> "bus"
        else -> text
    }
}
