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
)

interface LocalDetector : AutoCloseable {
    /** Borrows the caller's bitmap; implementations must not retain or recycle it. */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<org.sight.core.Detection>
}
