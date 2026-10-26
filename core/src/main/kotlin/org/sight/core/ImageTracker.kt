package org.sight.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Category-gated one-to-one overlap matching with bounded constant image-velocity prediction. */
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
        data class Match(val trackId: Long, val detectionIndex: Int, val score: Float, val predicted: Boolean)
        val candidates = tracks.values.flatMap { track ->
            val gap = frame.evidence.capturedAtMs - track.lastSeenAtMs
            if (gap !in 1..config.maxAssociationGapMs || track.evidence.sourceId != frame.evidence.sourceId ||
                track.evidence.modelId != frame.evidence.modelId || track.evidence.geometryEpoch != frame.evidence.geometryEpoch) emptyList()
            else detections.mapIndexedNotNull { index, detection ->
                val iou = track.box.iou(detection.box)
                val prediction = if (config.useMotionPrediction && gap <= config.maxPredictionMs) predict(track, gap) else null
                val predictedIou = prediction?.iou(detection.box) ?: 0f
                val scaleRatio = detection.box.area / track.box.area
                // With a supported velocity, prefer its expected location over the old box.
                // Otherwise two crossing objects can inherit each other's last positions.
                val score = if (prediction != null) predictedIou * 0.8f + iou * 0.2f else iou
                if (track.category == detection.category && scaleRatio in 0.3f..3.0f && score >= config.associationIou)
                    Match(track.id, index, score, prediction != null && predictedIou > iou) else null
            }
        }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.trackId }.thenBy { it.detectionIndex })
        val assignedTracks = mutableSetOf<Long>()
        val assignedDetections = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackId in assignedTracks || candidate.detectionIndex in assignedDetections) continue
            // Prediction cannot resolve competing same-category identities with comparable scores.
            // Fragmentation is preferable to silently declaring a motion-based identity match.
            if (candidate.predicted && candidates.any { other ->
                    other != candidate && (other.trackId == candidate.trackId || other.detectionIndex == candidate.detectionIndex) &&
                        other.trackId !in assignedTracks && other.detectionIndex !in assignedDetections &&
                        abs(other.score - candidate.score) < config.predictionAmbiguityMargin
                }) continue
            assignedTracks += candidate.trackId
            assignedDetections += candidate.detectionIndex
            val prior = tracks.getValue(candidate.trackId)
            val detection = detections[candidate.detectionIndex]
            val sample = TrackSample(detection.box, detection.confidence, frame.evidence)
            val history = (prior.history + sample).takeLast(config.maxTrackHistory)
            tracks[prior.id] = prior.copy(
                confidence = detection.confidence,
                box = detection.box,
                lastSeenAtMs = frame.evidence.capturedAtMs,
                visible = true,
                observations = if (prior.observations == Int.MAX_VALUE) prior.observations else prior.observations + 1,
                consecutiveObservations = if (prior.visible) (prior.consecutiveObservations + 1).coerceAtMost(1_000_000) else 1,
                evidence = frame.evidence,
                history = history,
                aggregateConfidence = history.takeLast(config.confidenceWindow).map { it.confidence }.average().toFloat(),
                motion = motion(history),
                association = if (candidate.predicted) ImageAssociation.MOTION_PREDICTED else ImageAssociation.OVERLAP,
                associationScore = candidate.score,
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

    private fun motion(history: List<TrackSample>): ImageMotion? {
        if (history.size < 2) return null
        val before = history[history.lastIndex - 1]
        val after = history.last()
        val interval = after.evidence.capturedAtMs - before.evidence.capturedAtMs
        if (interval !in 1..config.maxAssociationGapMs) return null
        val x = (after.box.centerX - before.box.centerX) * 1_000f / interval
        val y = (after.box.centerY - before.box.centerY) * 1_000f / interval
        if (hypot(x, y) > config.maxImageSpeedPerSecond) return null
        return ImageMotion(x, y, interval)
    }

    private fun predict(track: TrackedEntity, gapMs: Long): Box? {
        val velocity = track.motion ?: return null
        val dx = velocity.xPerSecond * gapMs / 1_000f
        val dy = velocity.yPerSecond * gapMs / 1_000f
        // Do not extrapolate across a large fraction of the frame, even inside the time limit.
        if (hypot(dx, dy) > 0.25f) return null
        val width = track.box.right - track.box.left
        val height = track.box.bottom - track.box.top
        val left = (track.box.left + dx).coerceIn(0f, max(0f, 1f - width))
        val top = (track.box.top + dy).coerceIn(0f, max(0f, 1f - height))
        return Box(left, top, min(1f, left + width), min(1f, top + height))
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
