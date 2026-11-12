package org.sight.app.navigation

import kotlin.math.max

/** Local map matching estimates route progress, never sidewalk position or path clearance. */
class RouteProgressEngine(
    private val route: WalkingRoute,
    private val maxFixAgeMs: Long = 10_000,
    private val maxAccuracyMeters: Double = 25.0,
    private val offRouteMeters: Double = 35.0,
    private val arrivalRadiusMeters: Double = 25.0,
) {
    init {
        require(maxFixAgeMs > 0 && maxAccuracyMeters > 0 && offRouteMeters > 0 && arrivalRadiusMeters > 0)
        RouteGeometry.project(route.geometry.first(), route.geometry)
    }
    private var lastCapturedMs = -1L
    private var consecutiveOffRoute = 0
    private var offRouteSinceMs = -1L
    private var lastArrivalFix: GpsFix? = null
    private var lastMatchedFix: GpsFix? = null
    private var lastAlongMeters: Double? = null
    private var confirmed = false

    fun rehearsal(): String = route.rehearsal()

    @Synchronized fun update(fix: GpsFix, nowElapsedMs: Long): RouteProgress {
        if (confirmed) return RouteProgress(RouteProgressState.ARRIVED, detail = "Arrival confirmed by the user.")
        val age = nowElapsedMs - fix.capturedElapsedMs
        if (fix.capturedElapsedMs < 0 || age !in 0..maxFixAgeMs ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0 ||
            fix.accuracyMeters > maxAccuracyMeters || fix.capturedElapsedMs <= lastCapturedMs) {
            resetOffRoute(); lastArrivalFix = null
            return uncertain("A fresh GPS fix with accuracy of 25 metres or better is required. Route progress is uncertain.")
        }
        lastCapturedMs = fix.capturedElapsedMs
        val previous = lastMatchedFix
        val elapsedSeconds = previous?.let { (fix.capturedElapsedMs - it.capturedElapsedMs) / 1000.0 }
        // Do not turn a GNSS teleport or clearly nonwalking speed into route progress.
        val reportedSpeed = fix.speedMetersPerSecond
        if ((reportedSpeed != null && (!reportedSpeed.isFinite() || reportedSpeed < 0 || reportedSpeed > 5.0)) ||
            (previous != null && elapsedSeconds != null && elapsedSeconds in 0.001..30.0 &&
                RouteGeometry.distanceMeters(previous.point, fix.point) >
                max(60.0, elapsedSeconds * 4.0 + 2 * (previous.accuracyMeters + fix.accuracyMeters)))) {
            resetOffRoute(); lastArrivalFix = null
            return uncertain("Location changed faster than this walking guidance can verify. Waiting for another fix.")
        }
        val all = RouteGeometry.projections(fix.point, route.geometry)
        val global = all.minBy { it.distanceFromRouteMeters }
        val offThreshold = maxOf(offRouteMeters, fix.accuracyMeters * 2)
        if (global.distanceFromRouteMeters > offThreshold) {
            if (consecutiveOffRoute == 0) offRouteSinceMs = fix.capturedElapsedMs
            consecutiveOffRoute++
            lastArrivalFix = null
            if (consecutiveOffRoute >= 3 && fix.capturedElapsedMs - offRouteSinceMs >= 2_000) {
                return RouteProgress(RouteProgressState.OFF_ROUTE,
                    distanceFromRouteMeters = global.distanceFromRouteMeters,
                    detail = "Several accurate GPS fixes disagree with the mapped route. Recalculating may be needed.",
                    fixAccuracyMeters = fix.accuracyMeters)
            }
            return uncertain("GPS differs from the route. Gathering another fix before estimating progress.", global.distanceFromRouteMeters)
        }
        resetOffRoute()
        val lastAlong = lastAlongMeters
        val candidates = if (lastAlong != null && elapsedSeconds != null && elapsedSeconds < 30) {
            val travelWindow = maxOf(60.0, elapsedSeconds.coerceAtLeast(0.0) * 4 + fix.accuracyMeters * 2)
            all.filter { it.alongRouteMeters in (lastAlong - travelWindow)..(lastAlong + travelWindow) }
        } else all
        val projection = candidates.minByOrNull { it.distanceFromRouteMeters }
            ?: return uncertain("GPS could match more than one part of the route. Progress is uncertain.")
        if (projection.distanceFromRouteMeters > offThreshold) {
            lastArrivalFix = null
            return uncertain("GPS may have matched a different part of the route. Waiting before advancing directions.")
        }
        if (lastAlong == null && RouteGeometry.distanceMeters(fix.point, route.geometry.first()) > arrivalRadiusMeters) {
            val ambiguous = all.any { candidate ->
                candidate.distanceFromRouteMeters <= projection.distanceFromRouteMeters + fix.accuracyMeters &&
                    kotlin.math.abs(candidate.alongRouteMeters - projection.alongRouteMeters) > 80
            }
            if (ambiguous) return uncertain("Several parts of the route are near this GPS position. Progress is uncertain.")
        }
        lastMatchedFix = fix
        lastAlongMeters = projection.alongRouteMeters
        val remainingGeometry = (projection.totalGeometryMeters - projection.alongRouteMeters).coerceAtLeast(0.0)
        val remaining = remainingGeometry / projection.totalGeometryMeters * route.distanceMeters
        val endpointOffset = RouteGeometry.distanceMeters(route.geometry.last(), route.destination.point)
        // Geocoders often return a building centroid; it is not a verified entrance coordinate.
        val nearDestination = endpointOffset <= 100 &&
            RouteGeometry.distanceMeters(fix.point, route.destination.point) + fix.accuracyMeters <= arrivalRadiusMeters + endpointOffset
        val nearEnd = RouteGeometry.distanceMeters(fix.point, route.geometry.last()) + fix.accuracyMeters <= arrivalRadiusMeters
        if (nearDestination && nearEnd && remaining <= arrivalRadiusMeters * 2) {
            lastArrivalFix = fix
            val offset = if (endpointOffset > arrivalRadiusMeters)
                " The provider endpoint is about ${RouteSpeech.approximateMeters(endpointOffset)} metres from the selected map point." else ""
            val detail = "GPS places you near the mapped route endpoint for ${RouteSpeech.label(route.destination.label)}.$offset The entrance is unverified. Confirm the actual place before marking arrival."
            return RouteProgress(RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED, remaining,
                projection.distanceFromRouteMeters, detail = detail,
                alongRouteMeters = projection.alongRouteMeters, fixAccuracyMeters = fix.accuracyMeters,
                instruction = detail, announcementKey = "arrival")
        }
        lastArrivalFix = null
        val alongProvider = route.distanceMeters - remaining
        var cumulative = 0.0
        val next = route.steps.firstNotNullOfOrNull { step ->
            val beginsAt = cumulative
            cumulative += step.distanceMeters
            if (beginsAt > alongProvider + 3) step to (beginsAt - alongProvider) else null
        }
        val distance = next?.second
        val step = next?.first
        val band = when {
            distance == null -> "continue"
            distance <= maxOf(12.0, fix.accuracyMeters) -> "near"
            distance <= 35 -> "approach"
            distance <= 80 -> "advance"
            else -> "overview"
        }
        val road = step?.roadName?.let(RouteSpeech::label)?.takeIf { it.isNotBlank() }
        val action = step?.spokenAction ?: "continue along the mapped route"
        val instruction = when {
            step == null -> "Continue along the mapped route. The destination is approximately ${RouteSpeech.approximateMeters(remaining, fix.accuracyMeters)} metres along the route."
            band == "near" -> "The next mapped maneuver is nearby: $action${road?.let { " at $it" } ?: ""}. GPS cannot identify the exact turning point."
            else -> "In approximately ${RouteSpeech.approximateMeters(distance!!, fix.accuracyMeters)} metres along the route, $action${road?.let { " at $it" } ?: ""}."
        }
        return RouteProgress(RouteProgressState.CURRENT, remaining,
            projection.distanceFromRouteMeters, step,
            "Position estimate matched to mapped route geometry. Sidewalk and crossing conditions are unverified.",
            distance, projection.alongRouteMeters, fix.accuracyMeters, instruction, "${step?.index ?: -1}:$band")
    }

    private fun uncertain(detail: String, distance: Double? = null): RouteProgress {
        lastArrivalFix = null
        return RouteProgress(RouteProgressState.GPS_UNCERTAIN, distanceFromRouteMeters = distance, detail = detail)
    }
    private fun resetOffRoute() { consecutiveOffRoute = 0; offRouteSinceMs = -1 }

    @Synchronized fun confirmArrival(nowElapsedMs: Long): Boolean {
        val fix = lastArrivalFix ?: return false
        if (nowElapsedMs - fix.capturedElapsedMs !in 0..maxFixAgeMs) return false
        confirmed = true
        return true
    }
}
