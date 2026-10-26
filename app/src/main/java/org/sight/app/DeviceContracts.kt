package org.sight.app

import android.graphics.Bitmap

/** A source owns acquisition/lifecycle; consumers never retain a raw frame. */
interface VisualSource {
    val sourceId: String
    fun stop()
}

data class StageTiming(
    val frameId: Long,
    val receivedAtMs: Long,
    val sensorTimestampNs: Long,
    val conversionMs: Long,
    val detectionMs: Long,
    val ocrMs: Long,
    val totalMs: Long,
    val analyzed: Long,
    val skipped: Long,
    val timestampBasis: String,
    val modelId: String = "unspecified",
    val initializationMs: Long = 0,
    val completedAtMs: Long = receivedAtMs + totalMs,
    val capturedAtMs: Long? = null,
    val cameraFrames: Long = 0,
    val receivedFrames: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val geometryEpoch: Long = 0,
    val imageQuality: org.sight.perception.ImageQuality? = null,
)

interface LocalDetector : AutoCloseable {
    /** Borrows the caller's bitmap; implementations must not retain or recycle it. */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<org.sight.core.Detection>
}
