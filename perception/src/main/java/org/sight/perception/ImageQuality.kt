package org.sight.perception

data class ImageQuality(val meanLuma: Double, val clippedFraction: Double, val detailVariance: Double) {
    val dark: Boolean get() = meanLuma < 22
    val overexposed: Boolean get() = meanLuma > 248 && clippedFraction > .85
    val lowDetail: Boolean get() = detailVariance < 20
    val usableForText: Boolean get() = !dark && !overexposed && !lowDetail
    val feedback: String? get() = when {
        dark -> "The camera image is dark. Try more light before reading."
        overexposed -> "Most of the camera image is very bright. Try a different angle."
        lowDetail -> "The view has little sharp detail. Hold steady and bring the text into view."
        else -> null
    }
    companion object {
        /** Image-quality heuristics, not calibrated blur probabilities or OCR confidence. */
        fun measure(frame: PixelFrame): ImageQuality {
            val gray = IntArray(frame.argb.size) { i -> val c = frame.argb[i]
                (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8 }
            var sum = 0.0; var squared = 0.0; var n = 0
            for (y in 1 until frame.height - 1) for (x in 1 until frame.width - 1) {
                val i = y * frame.width + x
                val lap = gray[i - 1] + gray[i + 1] + gray[i - frame.width] + gray[i + frame.width] - 4 * gray[i]
                sum += lap; squared += lap.toDouble() * lap; n++
            }
            val variance = if (n == 0) 0.0 else (squared / n - (sum / n) * (sum / n)).coerceAtLeast(0.0)
            return ImageQuality(gray.average(), gray.count { it < 5 || it > 250 }.toDouble() / gray.size, variance)
        }
    }
}
