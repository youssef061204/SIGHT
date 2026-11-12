package org.sight.perception

import java.nio.FloatBuffer
import org.sight.core.Box
import org.sight.core.PedestrianSurface
import org.sight.core.SurfaceRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Intel class IDs are background, road, curb, marking. A marking is never a crosswalk label. */
object RoadMaskProcessor {
    val labels = listOf(null, PedestrianSurface.ROAD, PedestrianSurface.CURB, PedestrianSurface.ROAD_MARKING)

    /**
     * Extracts distinct 4-connected components instead of inventing one box across disconnected regions.
     * Score is the mean winning softmax posterior within a component, not calibrated correctness.
     * Coverage counts accepted component pixels / the entire model output grid (including background).
     * Stretch resize preserves normalized coordinates; there is no crop or letterbox to undo.
     */
    fun regions(
        posterior: FloatBuffer,
        width: Int,
        height: Int,
        minimumPosterior: Float = .85f,
        minimumCoverage: Float = .002f,
        maximumRegions: Int = 12,
    ): List<SurfaceRegion> {
        require(width > 0 && height > 0 && width.toLong() * height <= 1_000_000)
        require(posterior.limit().toLong() == width.toLong() * height * 4)
        require(minimumPosterior.isFinite() && minimumPosterior in 0f..1f)
        require(minimumCoverage.isFinite() && minimumCoverage in 0f..1f && maximumRegions in 1..32)
        val size = width * height
        val mask = ByteArray(size)
        val scores = FloatArray(size)
        for (pixel in 0 until size) {
            var winner = 0
            var score = -1f
            var sum = 0f
            var valid = true
            for (category in 0..3) {
                val value = posterior[pixel * 4 + category]
                if (!value.isFinite() || value !in 0f..1f) valid = false
                sum += value
                if (value > score) { winner = category; score = value }
            }
            if (valid && abs(sum - 1f) <= .03f && winner > 0 && score >= minimumPosterior) {
                mask[pixel] = winner.toByte()
                scores[pixel] = score
            }
        }
        val queue = IntArray(size)
        val regions = mutableListOf<SurfaceRegion>()
        for (start in 0 until size) {
            val category = mask[start]
            if (category <= 0) continue
            var read = 0
            var count = 1
            queue[0] = start
            mask[start] = 0
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            var totalScore = 0.0
            while (read < count) {
                val pixel = queue[read++]
                val x = pixel % width
                val y = pixel / width
                left = min(left, x); right = max(right, x + 1)
                top = min(top, y); bottom = max(bottom, y + 1)
                totalScore += scores[pixel]
                fun visit(next: Int) {
                    if (mask[next] == category) { mask[next] = 0; queue[count++] = next }
                }
                if (x > 0) visit(pixel - 1)
                if (x + 1 < width) visit(pixel + 1)
                if (y > 0) visit(pixel - width)
                if (y + 1 < height) visit(pixel + width)
            }
            val coverage = count.toFloat() / size
            if (coverage >= minimumCoverage) regions += SurfaceRegion(
                labels[category.toInt()]!!, (totalScore / count).toFloat().coerceIn(0f, 1f), coverage,
                Box(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height))
        }
        return regions.sortedByDescending { it.coverage }.take(maximumRegions)
    }

    /** Raw BGR float input, 0..255; alpha is ignored and no ImageNet normalization is applied. */
    fun putBgr(argb: IntArray, output: FloatBuffer) {
        require(output.remaining() == argb.size * 3)
        argb.forEach { color ->
            output.put((color and 255).toFloat())
            output.put(((color ushr 8) and 255).toFloat())
            output.put(((color ushr 16) and 255).toFloat())
        }
    }
}
