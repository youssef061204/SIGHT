package org.sight.app.navigation

import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Public demonstration providers. Host must obtain consent before sending query/coordinates. */
class PublicWalkingRouteProvider(
    private val networkAllowed: () -> Boolean,
    private val transport: JsonTransport = BoundedHttpsTransport(),
) : WalkingRouteService {
    override suspend fun search(query: String, bias: GeoPoint?): List<Destination> {
        consent()
        val normalized = query.trim()
        if (normalized.length !in 2..160) throw RouteProviderException(RouteFailure.INVALID_REQUEST,
            "Destination queries must contain 2 to 160 characters.")
        val body = transport.get(searchUrl(normalized, bias))
        currentCoroutineContext().ensureActive()
        consent()
        return withContext(Dispatchers.Default) { parseSearch(body) }
    }

    override suspend fun route(origin: GeoPoint, destination: Destination): WalkingRoute {
        consent()
        val directDistance = RouteGeometry.distanceMeters(origin, destination.point)
        if (directDistance < 10 || directDistance > 50_000) {
            throw RouteProviderException(RouteFailure.INVALID_REQUEST,
                "This walking-route slice supports destinations 10 meters to 50 kilometers away.")
        }
        val url = routeUrl(origin, destination.point)
        val body = transport.get(url)
        currentCoroutineContext().ensureActive()
        consent()
        return withContext(Dispatchers.Default) {
            parseRoute(body, destination, url, System.currentTimeMillis())
        }
    }

    private fun consent() {
        if (!networkAllowed()) throw RouteProviderException(RouteFailure.CONSENT_REQUIRED,
            "Enable network route assistance before sending place names or coordinates.")
    }

    companion object {
        const val ATTRIBUTION = "© OpenStreetMap contributors. Routing: FOSSGIS OSRM. Search: Photon / komoot."
        const val MAP_FIX_URL = "https://www.openstreetmap.org/fixthemap"
        const val SERVICE_POLICY_URL = "https://routing.openstreetmap.de/about.html"

        fun searchUrl(query: String, bias: GeoPoint? = null): String =
            "https://photon.komoot.io/api/?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&limit=5&lang=en" +
                (bias?.let { String.format(Locale.US, "&lat=%.7f&lon=%.7f", it.latitude, it.longitude) } ?: "")

        fun routeUrl(origin: GeoPoint, destination: GeoPoint): String = String.format(Locale.US,
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/%.7f,%.7f;%.7f,%.7f" +
                "?overview=full&geometries=geojson&steps=true&alternatives=false",
            origin.longitude, origin.latitude, destination.longitude, destination.latitude)

        fun parseSearch(body: String): List<Destination> = parse {
            val features = JSONObject(body).getJSONArray("features")
            if (features.length() > 100) error("Too many search results")
            (0 until minOf(features.length(), 5)).map { index ->
                val feature = features.getJSONObject(index)
                val geometry = feature.getJSONObject("geometry")
                require(geometry.getString("type") == "Point")
                val point = point(geometry.getJSONArray("coordinates"))
                val properties = feature.getJSONObject("properties")
                val parts = listOf("name", "housenumber", "street", "city", "country")
                    .mapNotNull { key -> properties.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                    .distinct()
                require(parts.isNotEmpty()) { "Destination has no label" }
                val label = RouteSpeech.label(parts.joinToString(", "))
                val id = "${properties.optString("osm_type", "unknown").take(20)}:${properties.optString("osm_id", "$index").take(100)}:${point.latitude}:${point.longitude}"
                Destination(id, label, point)
            }
        }

        fun parseRoute(body: String, destination: Destination, sourceUrl: String, retrievedAtEpochMs: Long): WalkingRoute = parse {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") throw RouteProviderException(RouteFailure.NO_ROUTE,
                "The provider could not establish a walking route (${root.optString("code", "unknown").take(60)}).")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val geometry = route.getJSONObject("geometry")
            require(geometry.getString("type") == "LineString")
            val coordinates = geometry.getJSONArray("coordinates")
            require(coordinates.length() in 2..50_000)
            val points = (0 until coordinates.length()).map { point(coordinates.getJSONArray(it)) }
            val projection = RouteGeometry.project(points.first(), points)
            require(projection.totalGeometryMeters in 1.0..150_000.0) { "Unsupported geometry length" }
            require(RouteGeometry.distanceMeters(points.last(), destination.point) <= 100.0) {
                "Provider endpoint is too far from the selected destination"
            }
            val distance = positive(route.getDouble("distance"))
            val duration = positive(route.getDouble("duration"))
            require(distance <= 150_000 && duration <= 172_800) { "Unsupported route length/duration" }
            val legs = route.getJSONArray("legs")
            require(legs.length() in 1..20)
            val steps = mutableListOf<RouteStep>()
            for (legIndex in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(legIndex).getJSONArray("steps")
                require(legSteps.length() <= 1_000)
                for (stepIndex in 0 until legSteps.length()) {
                    require(steps.size < 1_000)
                    val step = legSteps.getJSONObject(stepIndex)
                    val maneuver = step.getJSONObject("maneuver")
                    steps += RouteStep(steps.size, step.optString("name").take(160),
                        maneuver.getString("type").take(60),
                        maneuver.optString("modifier").takeIf { it.isNotBlank() && it != "null" }?.take(60),
                        point(maneuver.getJSONArray("location")), nonnegative(step.getDouble("distance")),
                        nonnegative(step.getDouble("duration")))
                }
            }
            require(steps.isNotEmpty()) { "Provider omitted route steps" }
            WalkingRoute(UUID.randomUUID().toString(), "FOSSGIS OSRM foot", sourceUrl, retrievedAtEpochMs,
                points, steps, distance, duration, destination,
                root.optString("data_version").takeIf { it.isNotBlank() && it != "null" })
        }

        private fun point(coordinates: JSONArray): GeoPoint {
            require(coordinates.length() >= 2)
            return GeoPoint(coordinates.getDouble(1), coordinates.getDouble(0))
        }
        private fun nonnegative(value: Double): Double { require(value.isFinite() && value >= 0); return value }
        private fun positive(value: Double): Double { require(value.isFinite() && value > 0); return value }
        private inline fun <T> parse(block: () -> T): T = try { block() } catch (known: RouteProviderException) {
            throw known
        } catch (failure: Exception) {
            throw RouteProviderException(RouteFailure.INVALID_RESPONSE, "The provider response is incomplete or invalid.", failure)
        }
    }
}
