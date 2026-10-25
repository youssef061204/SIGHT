package org.sight.core

import kotlin.math.max
import kotlin.math.min

/** Bounds in the oriented camera image. They convey no world position or physical size. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom) { "Box must have positive area" }
    }
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = (right - left) * (bottom - top)
    val direction: ImageDirection get() = when {
        centerX < 0.35f -> ImageDirection.LEFT
        centerX > 0.65f -> ImageDirection.RIGHT
        else -> ImageDirection.CENTER
    }
    fun iou(other: Box): Float {
        val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
        return intersection / (area + other.area - intersection)
    }
}

data class Detection(val category: String, val confidence: Float, val box: Box) {
    init {
        require(category.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

/** Times use the source's shared monotonic clock, never wall clock time. */
enum class TimeBasis { CAPTURE, RECEIPT }
data class Evidence(
    val frameId: Long,
    val capturedAtMs: Long,
    val sourceId: String = "phone-camera",
    val timeBasis: TimeBasis = TimeBasis.CAPTURE,
) {
    init { require(frameId >= 0 && capturedAtMs >= 0 && sourceId.isNotBlank()) }
}

data class TextObservation(val text: String, val box: Box, val confidence: Float? = null) {
    init { require(confidence == null || confidence.isFinite() && confidence in 0f..1f) }
}

data class FrameObservation(
    val evidence: Evidence,
    val detections: List<Detection>,
    val ocr: List<TextObservation> = emptyList(),
)

enum class ImageDirection(val phrase: String) {
    LEFT("on the left"), CENTER("in the center"), RIGHT("on the right"),
}

data class TrackSample(val box: Box, val confidence: Float, val evidence: Evidence)

/** Identity is a short image-overlap hypothesis; it is not a localized physical-world identity. */
data class TrackedEntity(
    val id: Long,
    val category: String,
    val confidence: Float,
    val box: Box,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val visible: Boolean,
    val observations: Int,
    val consecutiveObservations: Int,
    val evidence: Evidence,
    val history: List<TrackSample>,
) {
    val direction: ImageDirection get() = box.direction
}

enum class RelationshipKind { LEFT_OF_IN_IMAGE, ABOVE_IN_IMAGE, OVERLAPS_IN_IMAGE }
data class SceneRelationship(
    val subjectId: Long,
    val objectId: Long,
    val kind: RelationshipKind,
    val evidence: Evidence,
)

enum class GoalType { IDLE, EXPLORE, FIND, READ }
enum class NarrationProfile { CONCISE, STANDARD, DETAILED }
enum class GoalPhase { ACTIVE, SEARCHING, VERIFYING, COMPLETED, CANCELLED, TIMED_OUT, UNSUPPORTED, PAUSED }
data class GoalState(
    val type: GoalType = GoalType.EXPLORE,
    val phase: GoalPhase = GoalPhase.ACTIVE,
    val target: String? = null,
    val message: String = "Describing stable observations in the camera view.",
    val startedAtMs: Long = 0,
    val deadlineAtMs: Long? = null,
)

enum class ReliabilityState { OPERATIONAL, NEEDS_CONFIRMATION, VISUAL_INPUT_UNAVAILABLE, REDUCED_CAPABILITY, UNSUPPORTED_REQUEST }
enum class SpeechPriority { AMBIENT, TASK, USER_REQUEST, STATUS }
enum class AnnouncementKind { LIVE_SCENE, FIND_RESULT, OCR, MEMORY, STATUS, UNCERTAINTY }
data class Grounding(val trackId: Long, val category: String, val direction: ImageDirection)
data class Announcement(
    val text: String,
    val priority: SpeechPriority,
    val evidence: Evidence? = null,
    val kind: AnnouncementKind = AnnouncementKind.STATUS,
    val validUntilMs: Long? = null,
    val trackIds: Set<Long> = emptySet(),
    val dedupKey: String = text,
    val grounding: List<Grounding> = emptyList(),
)

data class RememberedEntity(val category: String, val direction: ImageDirection, val confidence: Float)
/** Explicitly named historical scene; a user label is not a verified detected object category. */
data class MemorySnapshot(
    val id: Long,
    val name: String,
    val savedAtMs: Long,
    val evidence: Evidence,
    val entities: List<RememberedEntity>,
    val text: String?,
)

data class DecisionTrace(val code: String, val message: String, val evidence: Evidence? = null)
data class EngineUpdate(
    val entities: List<TrackedEntity>,
    val relationships: List<SceneRelationship>,
    val goal: GoalState,
    val reliability: ReliabilityState,
    val announcements: List<Announcement>,
    val memory: List<MemorySnapshot>,
    val diagnostics: List<DecisionTrace>,
    val extractedText: String? = null,
    val narrationProfile: NarrationProfile = NarrationProfile.STANDARD,
    val liveEvidence: Evidence? = null,
    val textEvidence: Evidence? = null,
)

data class EngineConfig(
    val maxFrameAgeMs: Long = 1_500,
    val maxTrackAgeMs: Long = 5_000,
    val maxAssociationGapMs: Long = 1_000,
    val maxTrackHistory: Int = 12,
    val maxTracks: Int = 80,
    val maxDetections: Int = 80,
    val maxMemories: Int = 20,
    val detectionThreshold: Float = 0.50f,
    val announcementThreshold: Float = 0.65f,
    val associationIou: Float = 0.25f,
    val findTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 30_000,
    val narrationIntervalMs: Long = 3_000,
    val narrationRepeatMs: Long = 15_000,
) {
    init {
        require(maxFrameAgeMs > 0 && maxTrackAgeMs >= maxFrameAgeMs && maxAssociationGapMs > 0)
        require(maxTrackHistory in 1..100 && maxTracks in 1..500 && maxDetections in 1..500 && maxMemories in 1..100)
        require(detectionThreshold in 0f..1f && announcementThreshold in detectionThreshold..1f && associationIou in 0f..1f)
        require(findTimeoutMs > 0 && readTimeoutMs > 0 && narrationIntervalMs >= 0 && narrationRepeatMs > 0)
    }
}

object CocoCategories {
    val all: Set<String> = setOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
        "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
        "scissors", "teddy bear", "hair drier", "toothbrush",
    )
}
