package org.sight.app.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteVoiceActionTest {
    @Test fun destinationSearchPreservesUserPlaceAndRequiresExplicitProviderSelection() {
        assertEquals(RouteVoiceAction.Search("Union Station", false), RouteVoiceAction.parse("  Navigate TO Union   Station "))
        assertEquals(RouteVoiceAction.Search("King Street", true), RouteVoiceAction.parse("starting place King Street"))
        assertNull(RouteVoiceAction.parse("navigate to "))
        assertNull(RouteVoiceAction.parse("find a chair"))
        assertNull(RouteVoiceAction.parse("enable gps"))
    }
    @Test fun routeCommandsCannotBecomeVisualPerceptionRequests() {
        assertEquals(RouteVoiceAction.Overview, RouteVoiceAction.parse("route overview"))
        assertEquals(RouteVoiceAction.PauseGps, RouteVoiceAction.parse("stop gps"))
        assertEquals(RouteVoiceAction.Recalculate, RouteVoiceAction.parse("recalculate route"))
        assertNull(RouteVoiceAction.parse("safe to cross"))
    }
}
