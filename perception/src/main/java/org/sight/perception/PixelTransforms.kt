package org.sight.perception

import org.sight.core.Box
import kotlin.math.min

data class PixelFrame(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width in 1..4096 && height in 1..4096 && argb.size == width * height) }
}
data class PixelPlane(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int = 1, val offset: Int = 0) {
    internal fun validate(width: Int, height: Int) {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0 && offset >= 0)
        require(rowStride >= (width - 1L) * pixelStride + 1)
        require(offset.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride < bytes.size)
    }
    internal fun at(x: Int, y: Int) = bytes[offset + y * rowStride + x * pixelStride].toInt() and 255
}

/** Explicit byte order, plane stride and orientation; no vendor or Android dependency. */
object PixelTransforms {
    fun rgba(bytes: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int = 4): PixelFrame {
        require(width in 1..4096 && height in 1..4096 && pixelStride >= 4)
        require(rowStride >= (width - 1L) * pixelStride + 4)
        require((height - 1L) * rowStride + (width - 1L) * pixelStride + 4 <= bytes.size)
        val result = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * rowStride + x * pixelStride
            result[y * width + x] = ((bytes[i + 3].toInt() and 255) shl 24) or
                ((bytes[i].toInt() and 255) shl 16) or ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i + 2].toInt() and 255)
        }
        return PixelFrame(width, height, result)
    }
    fun yuv420(width: Int, height: Int, y: PixelPlane, u: PixelPlane, v: PixelPlane): PixelFrame {
        require(width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
        y.validate(width, height); u.validate(width / 2, height / 2); v.validate(width / 2, height / 2)
        val result = IntArray(width * height)
        for (row in 0 until height) for (col in 0 until width) {
            val c = (y.at(col, row) - 16).coerceAtLeast(0)
            val d = u.at(col / 2, row / 2) - 128; val e = v.at(col / 2, row / 2) - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            result[row * width + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelFrame(width, height, result)
    }
    /** Packed I420 Y,U,V; BT.601 limited-range conversion. Unknown padding is rejected. */
    fun i420(bytes: ByteArray, width: Int, height: Int): PixelFrame {
        require(width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
        val size = width * height
        require(bytes.size == size + size / 2) { "Expected tightly packed even-dimension I420" }
        return yuv420(width, height, PixelPlane(bytes, width),
            PixelPlane(bytes, width / 2, offset = size), PixelPlane(bytes, width / 2, offset = size + size / 4))
    }
    fun orient(input: PixelFrame, rotationDegrees: Int, mirrored: Boolean = false): PixelFrame {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        // An already oriented frame can be borrowed directly. Besides copying every pixel, the
        // previous loop allocated a boxed coordinate Pair for every pixel in this common path.
        if (rotationDegrees == 0 && !mirrored) return input
        val w = if (rotationDegrees % 180 == 0) input.width else input.height
        val h = if (rotationDegrees % 180 == 0) input.height else input.width
        val result = IntArray(w * h)
        for (y in 0 until input.height) for (x in 0 until input.width) {
            val dx: Int
            val dy: Int
            when (rotationDegrees) {
                90 -> { dx = input.height - 1 - y; dy = x }
                180 -> { dx = input.width - 1 - x; dy = input.height - 1 - y }
                270 -> { dx = y; dy = input.width - 1 - x }
                else -> { dx = x; dy = y }
            }
            val outputX = if (mirrored) w - 1 - dx else dx
            result[dy * w + outputX] = input.argb[y * input.width + x]
        }
        return PixelFrame(w, h, result)
    }
}

/** For runtimes that explicitly letterbox. MediaPipe already reprojects; do not apply twice. */
data class LetterboxGeometry(val sourceWidth: Int, val sourceHeight: Int, val targetWidth: Int, val targetHeight: Int) {
    init { require(listOf(sourceWidth, sourceHeight, targetWidth, targetHeight).all { it > 0 }) }
    private val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
    private val padX = (targetWidth - sourceWidth * scale) / 2f
    private val padY = (targetHeight - sourceHeight * scale) / 2f
    fun toSource(box: Box): Box? = normalizedBox((box.left * targetWidth - padX) / scale,
        (box.top * targetHeight - padY) / scale, (box.right * targetWidth - padX) / scale,
        (box.bottom * targetHeight - padY) / scale, sourceWidth, sourceHeight)
}
fun normalizedBox(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): Box? {
    if (width <= 0 || height <= 0 || !listOf(left, top, right, bottom).all { it.isFinite() }) return null
    val l = (left / width).coerceIn(0f, 1f); val t = (top / height).coerceIn(0f, 1f)
    val r = (right / width).coerceIn(0f, 1f); val b = (bottom / height).coerceIn(0f, 1f)
    return if (l < r && t < b) Box(l, t, r, b) else null
}
