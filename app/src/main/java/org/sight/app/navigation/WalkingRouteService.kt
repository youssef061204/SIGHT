package org.sight.app.navigation

/** Production network provider can be replaced with recorded responses for integration replay. */
interface WalkingRouteService {
    suspend fun search(query: String, bias: GeoPoint? = null): List<Destination>
    suspend fun route(origin: GeoPoint, destination: Destination): WalkingRoute
}
