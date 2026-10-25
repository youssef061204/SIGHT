package org.sight.app.navigation

import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }
}

data class Destination(val id: String, val label: String, val point: GeoPoint)

data class RouteStep(
    val index: Int,
    val roadName: String,
    val maneuverType: String,
    val modifier: String?,
    val location: GeoPoint,
    val distanceMeters: Double,
    val durationSeconds: Double,
) {
    /** Describes provider data; it does not authorize movement or crossing. */
    val description: String get() {
        val action = listOfNotNull(modifier?.replace('_', ' '), maneuverType.replace('_', ' '))
            .joinToString(" ")
        val road = if (roadName.isBlank()) "an unnamed path" else roadName
        return "The route provider lists $action at $road."
    }
}

data class WalkingRoute(
    val id: String,
    val provider: String,
    val sourceUrl: String,
    val retrievedAtEpochMs: Long,
    val geometry: List<GeoPoint>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val destination: Destination,
    val dataVersion: String? = null,
) {
    init {
        require(geometry.size >= 2) { "A route needs at least two geometry points" }
        require(distanceMeters.isFinite() && distanceMeters > 0) { "Invalid route distance" }
        require(durationSeconds.isFinite() && durationSeconds > 0) { "Invalid route duration" }
    }

    fun rehearsal(): String {
        val summary = String.format(Locale.US,
            "%s estimates %.0f meters and %.0f minutes. ", provider, distanceMeters, durationSeconds / 60)
        val turns = steps.filter { it.maneuverType !in setOf("depart", "arrive", "continue") }.take(6)
            .joinToString(" ") { it.description }
        return summary + turns + " Sidewalk access, crossing conditions, steps, construction and accessibility are unverified. " +
            "GPS may be inaccurate near tall buildings or indoors."
    }
}

data class GpsFix(val point: GeoPoint, val accuracyMeters: Double, val capturedElapsedMs: Long)

enum class RouteProgressState {
    CURRENT, GPS_UNCERTAIN, OFF_ROUTE, ARRIVAL_CONFIRMATION_REQUIRED, ARRIVED,
}

data class RouteProgress(
    val state: RouteProgressState,
    val remainingMeters: Double? = null,
    val distanceFromRouteMeters: Double? = null,
    val nextStep: RouteStep? = null,
    val detail: String,
)

enum class RouteFailure {
    CONSENT_REQUIRED, INVALID_REQUEST, NETWORK, HTTP, RESPONSE_TOO_LARGE, INVALID_RESPONSE, NO_ROUTE,
}

class RouteProviderException(val reason: RouteFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)
