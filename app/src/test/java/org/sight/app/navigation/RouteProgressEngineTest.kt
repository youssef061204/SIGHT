package org.sight.app.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteProgressEngineTest {
    private val start = GeoPoint(43.64, -79.39)
    private val end = GeoPoint(43.64, -79.38)
    private val middle = GeoPoint(43.64, -79.385)
    private val route = WalkingRoute("test", "test provider", "test-fixture", 0,
        listOf(start, middle, end), emptyList(), RouteGeometry.distanceMeters(start, end), 600.0,
        Destination("test-end", "Test endpoint", end))

    @Test fun projectsOntoPolylineAndEstimatesRemainingDistance() {
        val projection = RouteGeometry.project(middle, route.geometry)
        assertEquals(0.0, projection.distanceFromRouteMeters, 0.01)
        assertEquals(projection.totalGeometryMeters / 2, projection.alongRouteMeters, 0.1)
        val progress = RouteProgressEngine(route).update(GpsFix(middle, 5.0, 1000), 1000)
        assertEquals(RouteProgressState.CURRENT, progress.state)
        assertEquals(route.distanceMeters / 2, progress.remainingMeters!!, 0.1)
    }

    @Test fun staleInaccurateFutureAndDuplicateFixesCannotAdvanceProgress() {
        val engine = RouteProgressEngine(route)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(end, 5.0, 0), 20_000).state)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(end, 200.0, 21_000), 21_000).state)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(end, 5.0, 22_000), 21_000).state)
        assertEquals(RouteProgressState.CURRENT, engine.update(GpsFix(start, 5.0, 21_000), 21_000).state)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(end, 5.0, 21_000), 21_001).state)
        assertFalse(engine.confirmArrival(21_001))
    }

    @Test fun requiresThreeConsecutiveFreshAccurateOffRouteFixes() {
        val engine = RouteProgressEngine(route)
        val away = GeoPoint(43.645, -79.385)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(away, 5.0, 1000), 1000).state)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(away, 5.0, 2000), 2000).state)
        assertEquals(RouteProgressState.OFF_ROUTE, engine.update(GpsFix(away, 5.0, 3000), 3000).state)
        assertEquals(RouteProgressState.CURRENT, engine.update(GpsFix(middle, 5.0, 4000), 4000).state)
        assertEquals(RouteProgressState.GPS_UNCERTAIN, engine.update(GpsFix(away, 5.0, 5000), 5000).state)
    }

    @Test fun arrivalRequiresFreshProximityAndExplicitUserConfirmation() {
        val engine = RouteProgressEngine(route)
        assertFalse(engine.confirmArrival(0))
        assertEquals(RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED,
            engine.update(GpsFix(end, 5.0, 1000), 1000).state)
        assertFalse(engine.confirmArrival(20_000))
        assertEquals(RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED,
            engine.update(GpsFix(end, 5.0, 21_000), 21_000).state)
        assertTrue(engine.confirmArrival(21_000))
        assertEquals(RouteProgressState.ARRIVED, engine.update(GpsFix(end, 5.0, 22_000), 22_000).state)
    }

    @Test fun invalidGeometryAndCoordinatesAreRejected() {
        try { GeoPoint(Double.NaN, 0.0); fail("NaN must be rejected") }
        catch (_: IllegalArgumentException) { /* expected */ }
        try { RouteGeometry.project(start, listOf(start, start)); fail("Degenerate geometry must be rejected") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun dateLineSegmentDoesNotPassThroughGreenwich() {
        val geometry = listOf(GeoPoint(0.0, 179.999), GeoPoint(0.0, -179.999))
        assertTrue(RouteGeometry.distanceMeters(geometry[0], geometry[1]) < 250)
        assertTrue(RouteGeometry.project(GeoPoint(0.0, 180.0), geometry).distanceFromRouteMeters < 1)
        assertTrue(RouteGeometry.project(GeoPoint(0.0, 0.0), geometry).distanceFromRouteMeters > 1_000_000)
    }

    @Test fun upcomingManeuverUsesStartOfNextStepRatherThanPreviousTurn() {
        val half = route.distanceMeters / 2
        val withSteps = route.copy(steps = listOf(
            RouteStep(0, "Start", "depart", null, start, half, 300.0),
            RouteStep(1, "Middle", "turn", "left", middle, half, 300.0),
            RouteStep(2, "End", "arrive", null, end, 0.0, 0.0),
        ))
        val progress = RouteProgressEngine(withSteps).update(GpsFix(start, 5.0, 1000), 1000)
        assertEquals(1, progress.nextStep!!.index)
        val afterMiddle = GeoPoint(43.64, -79.383)
        val next = RouteProgressEngine(withSteps).update(GpsFix(afterMiddle, 5.0, 1000), 1000)
        assertEquals(2, next.nextStep!!.index)
    }
}
