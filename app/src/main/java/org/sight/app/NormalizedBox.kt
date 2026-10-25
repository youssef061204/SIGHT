package org.sight.app

import org.sight.core.Box

/** A malformed individual model proposal must not crash the native camera lifecycle. */
internal fun normalizedBox(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): Box? {
    if (width <= 0 || height <= 0 || !listOf(left, top, right, bottom).all { it.isFinite() }) return null
    val l = (left / width).coerceIn(0f, 1f)
    val t = (top / height).coerceIn(0f, 1f)
    val r = (right / width).coerceIn(0f, 1f)
    val b = (bottom / height).coerceIn(0f, 1f)
    return if (l < r && t < b) Box(l, t, r, b) else null
}
