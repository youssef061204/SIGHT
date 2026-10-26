package org.sight.core

/** Bounded, process-only observation metadata. This stores neither images nor physical positions. */
internal class TemporalWorld(private val config: EngineConfig) {
    private data class Entry(val entity: TrackedEntity, val continuityId: Long, val confirmed: Boolean, val observationsSinceClear: Int)
    private data class Scene(val evidence: Evidence, val entities: List<TrackedEntity>, val continuityId: Long)
    private val entries = linkedMapOf<Long, Entry>()
    private var continuityId = 0L
    private var previousScene: Scene? = null
    private var lastClearAtMs = -1L
    var changes: SceneChangeSet? = null
        private set

    fun breakContinuity() { continuityId++ }

    fun observe(entities: List<TrackedEntity>, evidence: Evidence, nowMs: Long) {
        if (!config.enableWorldMemory) return
        expire(nowMs)
        val fresh = entities.filter { it.visible && it.evidence == evidence && it.evidence.capturedAtMs > lastClearAtMs }.map { entity ->
            entity.copy(history = entity.history.filter { it.evidence.capturedAtMs > lastClearAtMs })
        }
        fresh.forEach { entity ->
            val prior = entries[entity.id]
            val policy = config.confirmationFor(entity.category)
            val observed = ((prior?.observationsSinceClear ?: 0) + 1).coerceAtMost(1_000_000)
            val confirmed = prior?.confirmed == true || entity.consecutiveObservations >= policy.minimumFrames &&
                entity.confidence >= config.announcementThreshold &&
                entity.aggregateConfidence >= (policy.minimumAggregateConfidence ?: config.announcementThreshold) &&
                (lastClearAtMs < 0 || observed >= policy.minimumFrames)
            entries[entity.id] = Entry(entity, continuityId, confirmed, observed)
        }
        while (entries.size > config.maxWorldEntities) entries.remove(entries.minBy { it.value.entity.lastSeenAtMs }.key)
        val stable = fresh.filter { entity ->
            val policy = config.confirmationFor(entity.category)
            entity.visible && entity.consecutiveObservations >= policy.minimumFrames &&
                entity.confidence >= config.announcementThreshold &&
                entity.aggregateConfidence >= (policy.minimumAggregateConfidence ?: config.announcementThreshold)
        }
        val prior = previousScene
        if (prior != null) {
            val comparable = prior.continuityId == continuityId
            val before = prior.entities.associateBy { it.id }
            val after = stable.associateBy { it.id }
            changes = SceneChangeSet(prior.evidence, evidence,
                if (comparable) stable.filter { it.id !in before } else emptyList(),
                if (comparable) prior.entities.filter { it.id !in after } else emptyList(),
                if (comparable) stable.mapNotNull { entity -> before[entity.id]?.takeIf { it.direction != entity.direction }?.let {
                    ImageSideChange(entity.id, entity.category, it.direction, entity.direction)
                } } else emptyList(), comparable)
        }
        previousScene = Scene(evidence, stable, continuityId)
    }

    fun snapshot(nowMs: Long, current: List<TrackedEntity>): List<WorldEntity> {
        if (!config.enableWorldMemory) return emptyList()
        expire(nowMs)
        val visible = current.filter { it.visible }.associateBy { it.id }
        return entries.values.sortedByDescending { it.entity.lastSeenAtMs }.map { entry ->
            val age = (nowMs - entry.entity.lastSeenAtMs).coerceAtLeast(0)
            val live = visible[entry.entity.id]?.takeIf { entry.continuityId == continuityId && age <= config.maxFrameAgeMs }
            WorldEntity(entry.entity.copy(visible = live != null), when {
                live != null -> VisibilityStatus.CURRENTLY_OBSERVED
                age <= config.recentObservationMs -> VisibilityStatus.RECENTLY_OBSERVED
                else -> VisibilityStatus.PREVIOUSLY_REMEMBERED
            }, age, entry.continuityId, entry.confirmed)
        }
    }

    /** Historical side queries inspect retained actual boxes, never extrapolated boxes. */
    fun historicalSide(side: ImageDirection, nowMs: Long): List<TrackedEntity> {
        expire(nowMs)
        return entries.values.filter { it.confirmed }.mapNotNull { entry ->
            entry.entity.history.lastOrNull { it.box.direction == side && nowMs - it.evidence.capturedAtMs <= config.shortTermMemoryMs }?.let { sample ->
                entry.entity.copy(box = sample.box, confidence = sample.confidence, evidence = sample.evidence,
                    lastSeenAtMs = sample.evidence.capturedAtMs, visible = false)
            }
        }.sortedByDescending { it.lastSeenAtMs }
    }

    fun clear(nowMs: Long) { entries.clear(); previousScene = null; changes = null; continuityId++; lastClearAtMs = nowMs }

    fun expire(nowMs: Long) {
        entries.entries.removeAll { nowMs - it.value.entity.lastSeenAtMs > config.shortTermMemoryMs }
        if (previousScene?.let { nowMs - it.evidence.capturedAtMs > config.shortTermMemoryMs } == true) { previousScene = null; changes = null }
        if (changes?.let { nowMs - it.previousEvidence.capturedAtMs > config.shortTermMemoryMs } == true) changes = null
    }
}
