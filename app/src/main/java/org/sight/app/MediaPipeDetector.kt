package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.sight.core.Box
import org.sight.core.Detection

class MediaPipeDetector(context: Context) : LocalDetector {
    private val detector = ObjectDetector.createFromOptions(context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0_int8.tflite").build())
            .setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(0.5f).setMaxResults(20).build())

    override fun detect(bitmap: Bitmap, timestampMs: Long): List<Detection> {
        check(!bitmap.isRecycled) { "Inference requires a live camera bitmap" }
        // MPImage.close() recycles BitmapImageContainer's bitmap. The caller still owns
        // its frame (and may pass it to OCR), so transfer only an independent copy.
        val ownedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val image = try { BitmapImageBuilder(ownedBitmap).build() }
        catch (error: Exception) { ownedBitmap.recycle(); throw error }
        return try {
            detector.detectForVideo(image, timestampMs).detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val b = detection.boundingBox()
                if (!category.score().isFinite() || category.score() !in 0f..1f) return@mapNotNull null
                val box = normalizedBox(b.left, b.top, b.right, b.bottom, bitmap.width, bitmap.height) ?: return@mapNotNull null
                Detection(category.categoryName(), category.score(), box)
            }
        } finally { image.close() }
    }

    override fun close() = detector.close()
}
