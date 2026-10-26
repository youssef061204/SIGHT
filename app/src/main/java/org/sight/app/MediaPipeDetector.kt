package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import org.sight.perception.DetectorProfile

/** Compatibility adapter; phone and Meta use the same shared detector. */
class MediaPipeDetector(context: Context, val profile: DetectorProfile = DetectorProfile.FAST) : LocalDetector {
    private val delegate = org.sight.perception.MediaPipeDetector(context, profile)
    val modelId: String get() = delegate.modelId
    override fun detect(bitmap: Bitmap, timestampMs: Long) = delegate.detect(bitmap, timestampMs)
    override fun close() = delegate.close()
}
