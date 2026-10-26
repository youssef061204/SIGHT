package org.sight.perception

import android.graphics.Bitmap
import android.graphics.Matrix
import java.nio.ByteBuffer

object FramePixels {
    /** CameraX R,G,B,A order. Retains neither plane nor proxy; respects row padding. */
    fun rgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int = 4): Bitmap {
        require(width in 1..4096 && height in 1..4096 && pixelStride == 4 && rowStride >= width * 4)
        val source = buffer.duplicate()
        val start = source.position()
        require(start.toLong() + (height - 1L) * rowStride + width * 4 <= source.limit())
        val packed = if (rowStride == width * 4) source else ByteBuffer.allocateDirect(width * height * 4).apply {
            for (row in 0 until height) {
                val line = source.duplicate()
                line.position(start + row * rowStride); line.limit(start + row * rowStride + width * 4)
                put(line)
            }
            rewind()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(packed) }
    }
    fun i420(bytes: ByteArray, width: Int, height: Int, rotationDegrees: Int = 0, mirrored: Boolean = false): Bitmap {
        val pixels = PixelTransforms.orient(PixelTransforms.i420(bytes, width, height), rotationDegrees, mirrored)
        return Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
    }
    /** Borrows input. Returns input for identity transform; caller checks identity before recycling. */
    fun orient(bitmap: Bitmap, rotationDegrees: Int, mirrored: Boolean = false): Bitmap {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        if (rotationDegrees == 0 && !mirrored) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()); if (mirrored) postScale(-1f, 1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }
    fun quality(bitmap: Bitmap): ImageQuality {
        val thumb = Bitmap.createScaledBitmap(bitmap, 96, 72, true)
        return try {
            val pixels = IntArray(thumb.width * thumb.height)
            thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
            ImageQuality.measure(PixelFrame(thumb.width, thumb.height, pixels))
        } finally { if (thumb !== bitmap) thumb.recycle() }
    }
}
