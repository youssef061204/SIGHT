package org.sight.perception

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.sight.core.Evidence
import org.sight.core.SurfaceObservation
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Small purpose-built road/curb/marking network. No depth, sidewalk, crosswalk or signal-state head. */
class RoadSegmenter(context: Context) : AutoCloseable {
    companion object {
        const val MODEL_ID = "intel-road-segmentation-adas-0001-fp32"
        const val ASSET_NAME = "road_segmentation_adas_0001.tflite"
        const val WIDTH = 896
        const val HEIGHT = 512
    }
    private val model = context.assets.open(ASSET_NAME).use { stream ->
        val bytes = stream.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2)).apply {
        check(getInputTensor(0).dataType() == DataType.FLOAT32 &&
            getInputTensor(0).shape().contentEquals(intArrayOf(1, HEIGHT, WIDTH, 3)))
        check(getOutputTensor(0).dataType() == DataType.FLOAT32 &&
            getOutputTensor(0).shape().contentEquals(intArrayOf(1, HEIGHT, WIDTH, 4)))
    }
    private val input = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(WIDTH * HEIGHT)
    private var closed = false

    /** Caller owns its already oriented bitmap. Capture/source/geometry evidence is retained unchanged. */
    @Synchronized fun segment(bitmap: Bitmap, evidence: Evidence): SurfaceObservation {
        check(!closed && !bitmap.isRecycled)
        val scaled = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, true)
        try {
            scaled.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            input.rewind()
            RoadMaskProcessor.putBgr(pixels, input.asFloatBuffer())
            output.rewind()
            interpreter.run(input, output)
            output.rewind()
            return SurfaceObservation(evidence.copy(modelId = MODEL_ID),
                // Selection-only pixel IoU chose the unthresholded winner. Voice admission is a
                // separate calibrated policy and remains disabled after its held-out FP gate failed.
                RoadMaskProcessor.regions(output.asFloatBuffer(), WIDTH, HEIGHT, minimumPosterior = 0f))
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; interpreter.close() }
    }
}
