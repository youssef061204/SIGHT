package org.sight.app.navigation

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RouteProviderTest {
    private val destination = Destination("public-union", "Union Station public coordinate",
        GeoPoint(43.6453, -79.3806))

    private fun fixture(name: String): String {
        val relative = "evaluation/results/route-provider-smoke/$name"
        return generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, relative) }.first { it.isFile }.readText()
    }

    @Test fun parsesActualPhotonResponseAndKeepsGeoJsonCoordinateOrder() {
        val found = PublicWalkingRouteProvider.parseSearch(fixture("photon-search.json"))
        assertEquals(3, found.size)
        assertTrue(found.first().label.contains("CN Tower"))
        assertEquals(43.6425637, found.first().point.latitude, 0.0000001)
        assertEquals(-79.3870872, found.first().point.longitude, 0.0000001)
    }

    @Test fun parsesActualFootRouteAndRehearsalIsExplicitlyProviderSourced() {
        val route = PublicWalkingRouteProvider.parseRoute(fixture("walking-route.json"), destination,
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/public-test", 1_792_794_054_000)
        assertEquals(820.5, route.distanceMeters, 0.01)
        assertEquals(656.2, route.durationSeconds, 0.01)
        assertEquals(61, route.geometry.size)
        assertEquals(15, route.steps.size)
        assertTrue(route.steps.any { it.roadName.contains("PATH") })
        assertTrue(route.rehearsal().contains("unverified"))
        assertTrue(route.rehearsal().contains("route provider lists"))
        assertFalse(route.rehearsal().contains("safe to cross"))
    }

    @Test fun deniesNetworkBeforeTransportAndDiscardsAfterConsentRevocation() = runBlocking {
        var count = 0
        val denied = PublicWalkingRouteProvider({ false }, JsonTransport { count++; "{}" })
        try { denied.search("Toronto"); fail("Consent must gate transport") }
        catch (failure: RouteProviderException) { assertEquals(RouteFailure.CONSENT_REQUIRED, failure.reason) }
        assertEquals(0, count)
        var allowed = true
        val revoked = PublicWalkingRouteProvider({ allowed }, JsonTransport {
            allowed = false
            fixture("photon-search.json")
        })
        try { revoked.search("Toronto"); fail("Revocation must discard results") }
        catch (failure: RouteProviderException) { assertEquals(RouteFailure.CONSENT_REQUIRED, failure.reason) }
    }

    @Test fun propagatesCancellationInsteadOfTurningItIntoProviderFailure() = runBlocking {
        val provider = PublicWalkingRouteProvider({ true }, JsonTransport { throw CancellationException("user stopped") })
        try { provider.search("Toronto"); fail("Cancellation expected") }
        catch (cancelled: CancellationException) { assertEquals("user stopped", cancelled.message) }
    }

    @Test fun rejectsUnavailableOrMalformedRouteInsteadOfCreatingSuccess() {
        try {
            PublicWalkingRouteProvider.parseRoute("{\"code\":\"NoRoute\"}", destination, "source", 0)
            fail("No route expected")
        } catch (failure: RouteProviderException) { assertEquals(RouteFailure.NO_ROUTE, failure.reason) }
        try {
            PublicWalkingRouteProvider.parseRoute("{\"code\":\"Ok\",\"routes\":[]}", destination, "source", 0)
            fail("Invalid route expected")
        } catch (failure: RouteProviderException) { assertEquals(RouteFailure.INVALID_RESPONSE, failure.reason) }
        try {
            PublicWalkingRouteProvider.parseSearch("{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,1000]}}]}")
            fail("Invalid coordinate expected")
        } catch (failure: RouteProviderException) { assertEquals(RouteFailure.INVALID_RESPONSE, failure.reason) }
    }

    @Test fun footUrlSelectsActualFootBackendAndUsesLocaleIndependentLongitudeLatitude() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val url = PublicWalkingRouteProvider.routeUrl(GeoPoint(43.6426, -79.3871), destination.point)
            assertTrue(url.contains("/routed-foot/route/v1/foot/-79.3871000,43.6426000;"))
            assertTrue(url.contains("geometries=geojson"))
            assertTrue(PublicWalkingRouteProvider.searchUrl("CN Tower & café").contains("%26"))
        } finally { java.util.Locale.setDefault(previous) }
    }
}
