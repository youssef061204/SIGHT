package org.sight.app.navigation

import kotlin.math.*

object RouteGeometry {
    private const val EARTH_METERS = 6_371_008.8

    private fun longitudeDelta(first: Double, second: Double): Double =
        ((second - first + 540.0) % 360.0) - 180.0

    fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(longitudeDelta(first.longitude, second.longitude))
        val a = (sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2))
            .coerceIn(0.0, 1.0)
        return 2 * EARTH_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    data class Projection(
        val distanceFromRouteMeters: Double,
        val alongRouteMeters: Double,
        val totalGeometryMeters: Double,
        val segmentIndex: Int,
    )

    /** Local tangent-plane projection for short pedestrian segments; no altitude/floor matching. */
    fun project(point: GeoPoint, geometry: List<GeoPoint>): Projection {
        return projections(point, geometry).minBy { it.distanceFromRouteMeters }
    }

    /** Retain candidate branches for continuity checks instead of snapping blindly across a loop. */
    fun projections(point: GeoPoint, geometry: List<GeoPoint>): List<Projection> {
        require(geometry.size >= 2)
        val scaleX = cos(Math.toRadians(point.latitude)) * EARTH_METERS
        var total = 0.0
        val candidates = mutableListOf<Projection>()
        for (index in 0 until geometry.lastIndex) {
            val start = geometry[index]
            val end = geometry[index + 1]
            val ax = Math.toRadians(longitudeDelta(point.longitude, start.longitude)) * scaleX
            val ay = Math.toRadians(start.latitude - point.latitude) * EARTH_METERS
            // Keep both endpoints in the same unwrapped segment across the date line.
            val bx = ax + Math.toRadians(longitudeDelta(start.longitude, end.longitude)) * scaleX
            val by = Math.toRadians(end.latitude - point.latitude) * EARTH_METERS
            val dx = bx - ax
            val dy = by - ay
            val norm = dx * dx + dy * dy
            val t = if (norm < 1e-12) 0.0 else (-(ax * dx + ay * dy) / norm).coerceIn(0.0, 1.0)
            val distance = hypot(ax + t * dx, ay + t * dy)
            val segmentLength = distanceMeters(start, end)
            candidates += Projection(distance, total + t * segmentLength, 0.0, index)
            total += segmentLength
        }
        require(total > 0 && total.isFinite()) { "Degenerate route geometry" }
        return candidates.map { it.copy(totalGeometryMeters = total) }
    }
}
