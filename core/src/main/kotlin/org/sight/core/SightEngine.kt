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
    private var currentGoal = GoalState()
    private var resumableGoal: GoalState? = null
    private var inputAvailable = true
    private var captureCutoffMs = 0L
    private var lastNowMs = 0L
    private var lastFrame: Evidence? = null
    private var sourceId: String? = null
    private var candidateTrackId: Long? = null
    private var candidateFrames = 0
    private var lastNarrationAtMs: Long? = null
    private var lastHintAtMs: Long? = null
    private val spoken = linkedMapOf<String, Long>()
    private val memories = ArrayDeque<MemorySnapshot>()
    private var nextMemoryId = 1L
    private var lastText: String? = null
    private var lastTextEvidence: Evidence? = null
    private var lastTextBlocks: List<String> = emptyList()
    private var narrationProfile = NarrationProfile.STANDARD
    private var readIntent = ReadIntent.ALL
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
            previous != null && previous.sourceId == evidence.sourceId && evidence.frameId <= previous.frameId ->
                "OUT_OF_ORDER_FRAME" to "Duplicate or out-of-order frame identifier discarded."
            else -> null
        }
        if (reject != null) {
            trace(reject.first, reject.second, evidence)
            return update(expireGoal(nowMs))
        }
        if (sourceId != null && sourceId != evidence.sourceId) {
            tracker.clear()
            resetCandidate()
            spoken.clear()
            trace("SOURCE_CHANGED", "Image tracks reset for the new camera source.", evidence)
        }
        sourceId = evidence.sourceId
        lastFrame = evidence
        val detections = frame.detections.sortedByDescending { it.confidence }.take(config.maxDetections).map { it.copy(category = canonical(it.category)) }
        if (detections.any { it.category !in supported }) trace("UNSUPPORTED_DETECTION_LABEL", "Detector labels outside the declared category vocabulary were discarded.", evidence)
        val normalized = frame.copy(detections = detections.filter { it.category in supported })
        val entities = tracker.update(normalized, nowMs)
        trace("FRAME_ACCEPTED", "Fresh source evidence admitted to the image tracker.", evidence)
        if (evidence.timeBasis == TimeBasis.RECEIPT) trace("RECEIPT_TIME_ONLY", "Only time since camera-frame receipt is gated; exposure clock alignment and sensor-to-receipt delay are unverified.", evidence)
        val texts = frame.ocr.filter { it.text.isNotBlank() && (it.confidence == null || it.confidence >= 0.5f) }
            .sortedWith(compareBy<TextObservation> { it.box.top }.thenBy { it.box.left }).take(100)
        if (texts.isNotEmpty()) {
            lastTextBlocks = texts.flatMap { it.text.trim().split(Regex("\\n\\s*\\n")) }.filter { it.isNotBlank() }.take(100)
            lastText = texts.joinToString("\n") { it.text.trim() }.take(8_000)
            lastTextEvidence = evidence
        }
        val timeout = expireGoal(nowMs)
        if (timeout.isNotEmpty()) return update(timeout)
        val announcements = when {
            currentGoal.type == GoalType.FIND && currentGoal.phase in setOf(GoalPhase.SEARCHING, GoalPhase.VERIFYING) ->
                processFind(entities, evidence, nowMs)
            currentGoal.type == GoalType.READ && currentGoal.phase == GoalPhase.SEARCHING ->
                if (texts.isNotEmpty()) processRead(lastText.orEmpty(), evidence, nowMs) else emptyList()
            currentGoal.type == GoalType.EXPLORE && currentGoal.phase == GoalPhase.ACTIVE -> narrate(entities, evidence, nowMs)
            else -> emptyList()
        }
        return update(announcements)
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
            lastFrame = null
            sourceId = null
            spoken.clear()
            lastNarrationAtMs = null
        }
        resetCandidate()
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
            command in setOf("read", "read everything", "read this", "read text", "just tell me the price", "what is the price", "find the expiration date", "read the price", "read the date") -> {
                readIntent = when {
                    "price" in command -> ReadIntent.PRICE
                    "date" in command -> ReadIntent.DATE
                    else -> ReadIntent.ALL
                }
                currentGoal = GoalState(GoalType.READ, GoalPhase.SEARCHING, message = "Waiting for fresh recognized text.", startedAtMs = nowMs, deadlineAtMs = nowMs + config.readTimeoutMs)
                listOf(status("Reading started. Hold the camera steady with text in view."))
            }
            command.startsWith("remember ") || command.startsWith("save this as ") -> remember(command, nowMs)
            command in setOf("forget everything", "forget all", "delete memories", "clear memory") -> {
                memories.clear()
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
        return update(listOf(status("Session landmark memories deleted.")))
    }

    /** Final speech admission check; call again immediately before speaking queued live output. */
    @Synchronized
    fun isAnnouncementValid(announcement: Announcement, nowMs: Long): Boolean {
        if (nowMs < lastNowMs) return false
        if (announcement.kind in setOf(AnnouncementKind.MEMORY, AnnouncementKind.STATUS, AnnouncementKind.UNCERTAINTY) && announcement.evidence == null) return true
        val evidence = announcement.evidence ?: return false
        if (currentGoal.phase in setOf(GoalPhase.CANCELLED, GoalPhase.PAUSED)) return false
        if (!inputAvailable || evidence.sourceId != sourceId || evidence.capturedAtMs < captureCutoffMs) return false
        if (nowMs < evidence.capturedAtMs || nowMs - evidence.capturedAtMs > config.maxFrameAgeMs) return false
        if (announcement.validUntilMs != null && nowMs > announcement.validUntilMs) return false
        val current = tracker.snapshot().associateBy { it.id }
        return announcement.grounding.all { grounding ->
            val entity = current[grounding.trackId]
            entity != null && entity.visible && nowMs - entity.lastSeenAtMs <= config.maxFrameAgeMs &&
                entity.category == grounding.category && entity.direction == grounding.direction
        } && announcement.trackIds.all { id -> current[id]?.let { it.visible && nowMs - it.lastSeenAtMs <= config.maxFrameAgeMs } == true }
    }

    private fun processFind(entities: List<TrackedEntity>, evidence: Evidence, nowMs: Long): List<Announcement> {
        val target = currentGoal.target ?: return emptyList()
        val candidates = entities.filter { it.visible && it.category == target && it.confidence >= config.announcementThreshold }
        val candidate = candidates.firstOrNull { it.id == candidateTrackId } ?: candidates.maxByOrNull { it.confidence }
        if (candidate == null) {
            resetCandidate()
            currentGoal = currentGoal.copy(phase = GoalPhase.SEARCHING, message = "Target not detected; absence is not established.")
            val since = lastHintAtMs ?: currentGoal.startedAtMs
            if (nowMs - since < 6_000) return emptyList()
            lastHintAtMs = nowMs
            val surface = entities.firstOrNull { it.visible && it.consecutiveObservations >= 2 && it.confidence >= config.announcementThreshold && it.category in setOf("dining table", "chair", "couch") }
            return if (surface != null) listOf(live("I haven't detected a $target. A ${surface.category} is ${surface.direction.phrase} of the camera view; you can inspect that area with the camera.", SpeechPriority.TASK, AnnouncementKind.LIVE_SCENE, evidence, listOf(surface)))
            else listOf(status("I haven't detected a $target. A different camera view may provide more evidence.", AnnouncementKind.UNCERTAINTY))
        }
        if (candidateTrackId == candidate.id) candidateFrames++ else {
            candidateTrackId = candidate.id
            candidateFrames = 1
        }
        if (candidateFrames < 2) {
            currentGoal = currentGoal.copy(phase = GoalPhase.VERIFYING, message = "Candidate detected; awaiting a second fresh matching frame.")
            trace("FIND_VERIFYING", "Candidate ${candidate.id} requires a second accepted frame.", evidence)
            return emptyList()
        }
        currentGoal = currentGoal.copy(phase = GoalPhase.COMPLETED, message = "Supported category matched across two fresh camera frames.")
        trace("FIND_CONFIRMED", "Two-frame evidence confirms a visible category candidate; no physical-world location is inferred.", evidence)
        return listOf(live("A $target is ${candidate.direction.phrase} of the camera view, seen in two frames.", SpeechPriority.TASK, AnnouncementKind.FIND_RESULT, evidence, listOf(candidate)))
    }

    private fun narrate(entities: List<TrackedEntity>, evidence: Evidence, nowMs: Long): List<Announcement> {
        if (lastNarrationAtMs?.let { nowMs - it < config.narrationIntervalMs } == true) return emptyList()
        val stable = entities.filter { it.visible && it.consecutiveObservations >= 2 && it.confidence >= config.announcementThreshold }
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
        lastHintAtMs = null
        if (normalized in supported) {
            currentGoal = GoalState(GoalType.FIND, GoalPhase.SEARCHING, normalized, "Searching for fresh category evidence.", nowMs, nowMs + config.findTimeoutMs)
            return listOf(status("Looking for a $normalized. I will check a candidate across two fresh frames."))
        }
        val category = supported.sortedByDescending { it.length }.firstOrNull { normalized.endsWith(" $it") }
        val message = if (category != null) "I can detect $category objects, but cannot verify '${normalized.removeSuffix(category).trim()}' characteristics. Try 'find a $category'."
        else "The local detector does not support '$target'. Try a supported object such as a chair, bottle, backpack, or person."
        currentGoal = GoalState(GoalType.FIND, GoalPhase.UNSUPPORTED, normalized, message, nowMs)
        trace("UNSUPPORTED_TARGET", message)
        return listOf(status(message, AnnouncementKind.UNCERTAINTY))
    }

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

    private fun processRead(text: String, evidence: Evidence, nowMs: Long): List<Announcement> {
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
        return listOf(Announcement(result, SpeechPriority.USER_REQUEST, evidence, AnnouncementKind.OCR, minOf(nowMs + config.maxFrameAgeMs, evidence.capturedAtMs + config.maxFrameAgeMs), dedupKey = "ocr:${evidence.sourceId}:${evidence.frameId}"))
    }

    private fun remember(command: String, nowMs: Long): List<Announcement> {
        val evidence = freshEvidence(nowMs) ?: return listOf(status("I need a fresh camera observation before saving a scene.", AnnouncementKind.UNCERTAINTY))
        val visible = tracker.snapshot().filter { it.visible && it.consecutiveObservations >= 2 && it.confidence >= config.announcementThreshold }
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
        val entity = tracker.snapshot().filter { it.category == normalized }.maxByOrNull { it.lastSeenAtMs }
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
            announcements, memories.toList(), traces.toList(), lastText, narrationProfile, evidence, lastTextEvidence)
    }

    private fun live(text: String, priority: SpeechPriority, kind: AnnouncementKind, evidence: Evidence, entities: List<TrackedEntity>): Announcement =
        Announcement(text, priority, evidence, kind, evidence.capturedAtMs + config.maxFrameAgeMs,
            entities.map { it.id }.toSet(), "$kind:${entities.joinToString { entityKey(it) }}", entities.map { Grounding(it.id, it.category, it.direction) })

    private fun status(text: String, kind: AnnouncementKind = AnnouncementKind.STATUS): Announcement = Announcement(text, SpeechPriority.USER_REQUEST, kind = kind)
    private fun entityKey(entity: TrackedEntity): String = "${entity.id}:${entity.direction}"
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
