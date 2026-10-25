package org.sight.core

/** Deterministic, category-gated greedy IoU matching. All geometry is image-relative. */
internal class ImageTracker(private val config: EngineConfig) {
    private val tracks = linkedMapOf<Long, TrackedEntity>()
    private var nextId = 1L

    fun clear() { tracks.clear() }
    fun snapshot(): List<TrackedEntity> = tracks.values.toList()
    fun expire(nowMs: Long, hide: Boolean = false) {
        tracks.entries.removeAll { nowMs - it.value.lastSeenAtMs > config.maxTrackAgeMs }
        tracks.replaceAll { _, track ->
            if (hide || nowMs - track.lastSeenAtMs > config.maxFrameAgeMs) track.copy(visible = false, consecutiveObservations = 0) else track
        }
    }

    fun update(frame: FrameObservation, nowMs: Long): List<TrackedEntity> {
        expire(nowMs)
        val detections = frame.detections.filter { it.confidence >= config.detectionThreshold }
            .sortedByDescending { it.confidence }.take(config.maxDetections)
        data class Match(val trackId: Long, val detectionIndex: Int, val iou: Float)
        val candidates = tracks.values.flatMap { track ->
            if (frame.evidence.capturedAtMs - track.lastSeenAtMs > config.maxAssociationGapMs) emptyList()
            else detections.mapIndexedNotNull { index, detection ->
                val iou = track.box.iou(detection.box)
                if (track.category == detection.category && iou >= config.associationIou) Match(track.id, index, iou) else null
            }
        }.sortedWith(compareByDescending<Match> { it.iou }.thenBy { it.trackId }.thenBy { it.detectionIndex })
        val assignedTracks = mutableSetOf<Long>()
        val assignedDetections = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackId in assignedTracks || candidate.detectionIndex in assignedDetections) continue
            assignedTracks += candidate.trackId
            assignedDetections += candidate.detectionIndex
            val prior = tracks.getValue(candidate.trackId)
            val detection = detections[candidate.detectionIndex]
            val sample = TrackSample(detection.box, detection.confidence, frame.evidence)
            tracks[prior.id] = prior.copy(
                confidence = detection.confidence,
                box = detection.box,
                lastSeenAtMs = frame.evidence.capturedAtMs,
                visible = true,
                observations = if (prior.observations == Int.MAX_VALUE) prior.observations else prior.observations + 1,
                consecutiveObservations = if (prior.visible) (prior.consecutiveObservations + 1).coerceAtMost(1_000_000) else 1,
                evidence = frame.evidence,
                history = (prior.history + sample).takeLast(config.maxTrackHistory),
            )
        }
        tracks.replaceAll { id, track ->
            if (id !in assignedTracks) track.copy(visible = false, consecutiveObservations = 0) else track
        }
        detections.forEachIndexed { index, detection ->
            if (index !in assignedDetections) {
                val id = nextId++
                tracks[id] = TrackedEntity(
                    id, detection.category, detection.confidence, detection.box,
                    frame.evidence.capturedAtMs, frame.evidence.capturedAtMs,
                    true, 1, 1, frame.evidence,
                    listOf(TrackSample(detection.box, detection.confidence, frame.evidence)),
                )
            }
        }
        while (tracks.size > config.maxTracks) {
            val remove = tracks.values.minWith(compareBy<TrackedEntity> { it.visible }.thenBy { it.lastSeenAtMs }.thenBy { it.confidence })
            tracks.remove(remove.id)
        }
        return snapshot()
    }
}

internal fun relationships(entities: List<TrackedEntity>, evidence: Evidence): List<SceneRelationship> {
    val visible = entities.filter { it.visible }.take(30)
    val results = mutableListOf<SceneRelationship>()
    for (i in visible.indices) for (j in i + 1 until visible.size) {
        val a = visible[i]
        val b = visible[j]
        if (a.box.right + 0.03f < b.box.left) results += SceneRelationship(a.id, b.id, RelationshipKind.LEFT_OF_IN_IMAGE, evidence)
        else if (b.box.right + 0.03f < a.box.left) results += SceneRelationship(b.id, a.id, RelationshipKind.LEFT_OF_IN_IMAGE, evidence)
        if (a.box.bottom + 0.03f < b.box.top) results += SceneRelationship(a.id, b.id, RelationshipKind.ABOVE_IN_IMAGE, evidence)
        else if (b.box.bottom + 0.03f < a.box.top) results += SceneRelationship(b.id, a.id, RelationshipKind.ABOVE_IN_IMAGE, evidence)
        if (a.box.iou(b.box) > 0.1f) results += SceneRelationship(a.id, b.id, RelationshipKind.OVERLAPS_IN_IMAGE, evidence)
    }
    return results
}
