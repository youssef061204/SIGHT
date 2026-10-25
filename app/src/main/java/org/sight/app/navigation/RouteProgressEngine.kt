package org.sight.app.navigation

/** This estimates position against provider geometry; it does not validate a walking path. */
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
    private var lastArrivalFix: GpsFix? = null
    private var confirmed = false

    fun rehearsal(): String = route.rehearsal()

    @Synchronized fun update(fix: GpsFix, nowElapsedMs: Long): RouteProgress {
        if (confirmed) return RouteProgress(RouteProgressState.ARRIVED, detail = "Arrival confirmed by the user.")
        val age = nowElapsedMs - fix.capturedElapsedMs
        if (fix.capturedElapsedMs < 0 || age !in 0..maxFixAgeMs ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0 ||
            fix.accuracyMeters > maxAccuracyMeters || fix.capturedElapsedMs <= lastCapturedMs) {
            consecutiveOffRoute = 0
            lastArrivalFix = null
            return RouteProgress(RouteProgressState.GPS_UNCERTAIN,
                detail = "A fresh, sufficiently accurate GPS fix is required. Route progress is uncertain.")
        }
        lastCapturedMs = fix.capturedElapsedMs
        val projection = RouteGeometry.project(fix.point, route.geometry)
        val remainingGeometry = (projection.totalGeometryMeters - projection.alongRouteMeters).coerceAtLeast(0.0)
        // Normalize to the provider's route length; geometry can be quantized or simplified.
        val remaining = remainingGeometry / projection.totalGeometryMeters * route.distanceMeters
        val offThreshold = maxOf(offRouteMeters, fix.accuracyMeters * 2)
        consecutiveOffRoute = if (projection.distanceFromRouteMeters > offThreshold) consecutiveOffRoute + 1 else 0
        if (consecutiveOffRoute >= 3) {
            lastArrivalFix = null
            return RouteProgress(RouteProgressState.OFF_ROUTE, remaining,
                projection.distanceFromRouteMeters, detail =
                "Three GPS fixes disagree with the provider's route. A new route can be requested.")
        }
        if (consecutiveOffRoute > 0) {
            lastArrivalFix = null
            return RouteProgress(RouteProgressState.GPS_UNCERTAIN,
                distanceFromRouteMeters = projection.distanceFromRouteMeters,
                detail = "GPS differs from the route; gathering another fix before estimating progress.")
        }
        // Both the user's destination and the routed endpoint must be nearby: snapping can be wrong.
        val nearDestination = RouteGeometry.distanceMeters(fix.point, route.destination.point) <= arrivalRadiusMeters
        val nearEnd = RouteGeometry.distanceMeters(fix.point, route.geometry.last()) <= arrivalRadiusMeters
        if (nearDestination && nearEnd && remaining <= arrivalRadiusMeters * 2 && consecutiveOffRoute == 0) {
            lastArrivalFix = fix
            return RouteProgress(RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED, remaining,
                projection.distanceFromRouteMeters, detail =
                "GPS places you near the destination. Please confirm the place before marking arrival.")
        }
        lastArrivalFix = null
        var cumulative = 0.0
        val alongProvider = route.distanceMeters - remaining
        val nextStep = route.steps.firstOrNull { step ->
            val upcoming = cumulative > alongProvider + 3
            cumulative += step.distanceMeters
            upcoming
        }
        return RouteProgress(RouteProgressState.CURRENT, remaining,
            projection.distanceFromRouteMeters, nextStep,
            "Position estimate matched to the provider's route. Crossing and path conditions are unverified.")
    }

    @Synchronized fun confirmArrival(nowElapsedMs: Long): Boolean {
        val fix = lastArrivalFix ?: return false
        if (nowElapsedMs - fix.capturedElapsedMs !in 0..maxFixAgeMs) return false
        confirmed = true
        return true
    }
}
