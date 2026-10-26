package org.sight.perception

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.sight.core.Detection

enum class DetectorProfile(val modelId: String, val assetName: String, val scoreThreshold: Float, val inputSize: Int) {
    FAST("efficientdet_lite0_int8", "efficientdet_lite0_int8.tflite", .5f, 320),
    INSPECT("efficientdet_lite2_int8", "efficientdet_lite2_int8.tflite", .55f, 448)
}

/** Same actual native inference for phone and optional Meta adapters. Caller owns its bitmap. */
class MediaPipeDetector(context: Context, val profile: DetectorProfile = DetectorProfile.FAST) : AutoCloseable {
    val modelId: String get() = profile.modelId
    private var lastTimestamp = -1L
    private val detector = ObjectDetector.createFromOptions(context,
        ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder()
            .setModelAssetPath(profile.assetName).build()).setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(profile.scoreThreshold).setMaxResults(40).build())
    @Synchronized fun detect(bitmap: Bitmap, timestampMs: Long): List<Detection> {
        check(!bitmap.isRecycled)
        require(timestampMs >= 0)
        val input = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val image = try { BitmapImageBuilder(input).build() } catch (e: Exception) { input.recycle(); throw e }
        lastTimestamp = maxOf(timestampMs, lastTimestamp + 1)
        return try { detector.detectForVideo(image, lastTimestamp).detections().mapNotNull { detected ->
            val category = detected.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            if (!category.score().isFinite() || category.score() !in 0f..1f) return@mapNotNull null
            val b = detected.boundingBox()
            val box = normalizedBox(b.left, b.top, b.right, b.bottom, bitmap.width, bitmap.height) ?: return@mapNotNull null
            Detection(category.categoryName(), category.score(), box)
        } } finally { image.close() }
    }
    override fun close() = detector.close()
}
