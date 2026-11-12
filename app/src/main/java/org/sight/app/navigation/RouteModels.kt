package org.sight.app.navigation

import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
    }
}

data class Destination(val id: String, val label: String, val point: GeoPoint)

enum class MappedRouteFeature(val phrase: String) {
    STAIRS("stairs"), ELEVATOR("an elevator"), ESCALATOR("an escalator"),
    BUILDING_ENTRANCE("a building entrance"), BUILDING_EXIT("a building exit"),
    GATE("a gate"), ROUGH_SURFACE("rough surface"), FERRY("a ferry segment"),
}
enum class RouteProviderPreference { STANDARD, MAPPED_DETAILS }

data class RouteStep(
    val index: Int,
    val roadName: String,
    val maneuverType: String,
    val modifier: String?,
    val location: GeoPoint,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val mappedFeatures: Set<MappedRouteFeature> = emptySet(),
) {
    /** Describes provider data; it does not authorize movement or crossing. */
    val description: String get() {
        val action = listOfNotNull(modifier?.replace('_', ' '), maneuverType.replace('_', ' '))
            .joinToString(" ")
        val road = RouteSpeech.label(roadName).ifBlank { "an unnamed path" }
        return "The route provider lists ${RouteSpeech.label(action)} at $road." +
            if (mappedFeatures.isEmpty()) "" else " Map data lists ${mappedFeatures.joinToString { it.phrase }}. Present conditions are unverified."
    }

    val spokenAction: String get() = if (mappedFeatures.isNotEmpty())
        "map data lists ${mappedFeatures.joinToString { it.phrase }}; present access is unverified" else when (maneuverType) {
        "arrive" -> "the mapped destination is nearby"
        "depart" -> "continue along the mapped route"
        "roundabout", "rotary" -> "the mapped route enters a roundabout"
        "exit roundabout", "exit rotary" -> "the mapped route leaves the roundabout"
        else -> when (modifier) {
            "left", "slight left", "sharp left" -> "the mapped route turns ${modifier.replace('_', ' ')}"
            "right", "slight right", "sharp right" -> "the mapped route turns ${modifier.replace('_', ' ')}"
            "uturn" -> "the mapped route changes to the opposite direction"
            else -> "continue along the mapped route"
        }
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
    val mappedDetailsRequested: Boolean = false,
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
        val mapped = steps.flatMap { it.mappedFeatures }.distinct()
        val details = if (mapped.isEmpty()) "" else " Map data also lists ${mapped.joinToString { it.phrase }}; this has not been observed by the camera. "
        return summary + turns + details + " Sidewalk access, crossing conditions, steps, construction and accessibility are unverified. " +
            "GPS may be inaccurate near tall buildings or indoors."
    }
}

data class GpsFix(
    val point: GeoPoint, val accuracyMeters: Double, val capturedElapsedMs: Long,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
)

enum class RouteProgressState {
    CURRENT, GPS_UNCERTAIN, OFF_ROUTE, ARRIVAL_CONFIRMATION_REQUIRED, ARRIVED,
}

data class RouteProgress(
    val state: RouteProgressState,
    val remainingMeters: Double? = null,
    val distanceFromRouteMeters: Double? = null,
    val nextStep: RouteStep? = null,
    val detail: String,
    val distanceToNextStepMeters: Double? = null,
    val alongRouteMeters: Double? = null,
    val fixAccuracyMeters: Double? = null,
    val instruction: String? = null,
    val announcementKey: String? = null,
)

enum class NavigationPhase { IDLE, SEARCHING, CHOOSING_DESTINATION, ACQUIRING_LOCATION, ROUTING, READY, GUIDING, PAUSED, ARRIVED }
enum class NavigationCueKind { STATUS, CHOICES, OVERVIEW, TURN, ARRIVAL }
data class NavigationCue(
    val text: String, val kind: NavigationCueKind,
    val capturedElapsedMs: Long? = null, val maximumAgeMs: Long? = null,
)

/** Route geometry and mapped turns are distinct from physical clearance or body orientation. */
object RouteSpeech {
    private val clearance = Regex("(?i)safe\\s+to\\s+cross|road\\s+is\\s+clear|path\\s+is\\s+clear|you\\s+can\\s+cross|cross\\s+now|walk\\s+into\\s+the\\s+intersection|no\\s+cars\\s+are\\s+coming")
    fun label(value: String): String {
        val clean = value.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(180)
        return if (clearance.containsMatchIn(clean)) "map location" else clean
    }
    fun approximateMeters(distance: Double, accuracy: Double = 0.0): Int {
        val quantum = if (distance >= 50 || accuracy >= 10) 10 else 5
        return ((distance.coerceAtLeast(0.0) / quantum).toInt() * quantum).coerceAtLeast(quantum)
    }
}

enum class RouteFailure {
    CONSENT_REQUIRED, INVALID_REQUEST, NETWORK, HTTP, RESPONSE_TOO_LARGE, INVALID_RESPONSE, NO_ROUTE,
}

class RouteProviderException(val reason: RouteFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)
