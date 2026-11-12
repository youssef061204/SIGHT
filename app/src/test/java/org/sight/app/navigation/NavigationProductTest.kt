package org.sight.app.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationProductTest {
    private val origin = GeoPoint(43.64, -79.39)
    private val corner = GeoPoint(43.64, -79.388)
    private val end = GeoPoint(43.642, -79.388)
    private val firstLength = RouteGeometry.distanceMeters(origin, corner)
    private val secondLength = RouteGeometry.distanceMeters(corner, end)
    private val route = WalkingRoute("constructed-corner", "recorded geometry policy fixture", "local", 0,
        listOf(origin, corner, end), listOf(
            RouteStep(0,"First Street","depart",null,origin,firstLength,100.0),
            RouteStep(1,"Second Street","turn","left",corner,secondLength,150.0),
            RouteStep(2,"End","arrive",null,end,0.0,0.0)), firstLength+secondLength,250.0,
        Destination("end","Endpoint",end))

    @Test fun spokenWorkflowRecognizesDestinationNumberedChoiceAndLifecycleCommands() {
        assertEquals(RouteVoiceAction.Search("the library",false),RouteVoiceAction.parse("Take me to the library."))
        assertEquals(RouteVoiceAction.Search("Queen's University",false),RouteVoiceAction.parse("Sight, navigate to Queen's University"))
        assertEquals(RouteVoiceAction.Choose(1),RouteVoiceAction.parse("choose the second"))
        assertEquals(RouteVoiceAction.Choose(0),RouteVoiceAction.parse("choose option one"))
        assertEquals(RouteVoiceAction.EnableServices,RouteVoiceAction.parse("enable route services"))
        assertEquals(RouteVoiceAction.StartGuidance,RouteVoiceAction.parse("start navigation"))
        assertEquals(RouteVoiceAction.ResumeGps,RouteVoiceAction.parse("resume navigation"))
        assertEquals(RouteVoiceAction.Cancel,RouteVoiceAction.parse("stop navigation"))
        assertEquals(RouteVoiceAction.Repeat,RouteVoiceAction.parse("repeat the last direction"))
        assertEquals(RouteVoiceAction.DistanceToTurn,RouteVoiceAction.parse("how far until the next turn?"))
        assertNull(RouteVoiceAction.parse("choose option nine"))
        assertNull(RouteVoiceAction.parse("find the entrance"))
        assertNull(RouteVoiceAction.parse("safe to cross"))
    }

    @Test fun savedDestinationCommandsRequireAnExplicitNameAndDoNotBecomeSearchText() {
        assertEquals(RouteVoiceAction.SaveDestination("Home"),RouteVoiceAction.parse("save destination as Home"))
        assertEquals(RouteVoiceAction.RecallDestination("Home"),RouteVoiceAction.parse("take me to saved Home"))
        assertEquals(RouteVoiceAction.DeleteDestination("Home"),RouteVoiceAction.parse("delete destination Home"))
        assertNull(RouteVoiceAction.parse("save destination as "))
    }

    @Test fun choicesAreSpokenAndNeverSilentlySelectEvenOneMatch() {
        val text=RouteChoices.describe(listOf(Destination("a","City Library",origin)))
        assertTrue(text.contains("Option 1: City Library"))
        assertTrue(text.contains("Say choose option"))
        assertFalse(text.contains("Selected"))
    }

    @Test fun navigationDistancesAndTurnCuesComeFromThePolyline() {
        val engine=RouteProgressEngine(route)
        val atStart=engine.update(GpsFix(origin,5.0,1_000),1_000)
        assertEquals(firstLength,atStart.distanceToNextStepMeters!!,.5)
        assertTrue(atStart.instruction!!.contains("mapped route turns left"))
        assertEquals("1:overview",atStart.announcementKey)
        // Constructed walking samples approach the real geometry corner without teleporting.
        var now=1_000L
        var last=atStart
        for(i in 1..9) {
            now+=12_000
            val point=GeoPoint(origin.latitude,origin.longitude+(corner.longitude-origin.longitude)*i/10)
            last=engine.update(GpsFix(point,5.0,now),now)
        }
        assertEquals(RouteProgressState.CURRENT,last.state)
        assertTrue(last.distanceToNextStepMeters!!<25)
        assertEquals("1:approach",last.announcementKey)
        assertFalse(last.instruction!!.contains("safe to cross"))
    }

    @Test fun locationSpikeCannotCompleteTheRouteOrIssueAnEndInstruction() {
        val engine=RouteProgressEngine(route)
        engine.update(GpsFix(origin,5.0,1_000),1_000)
        val spike=engine.update(GpsFix(end,5.0,3_000),3_000)
        assertEquals(RouteProgressState.GPS_UNCERTAIN,spike.state)
        assertNull(spike.instruction)
        assertFalse(engine.confirmArrival(3_000))
        val recovered=engine.update(GpsFix(origin,5.0,5_000),5_000)
        assertEquals(RouteProgressState.CURRENT,recovered.state)
    }

    @Test fun ordinaryGpsDriftDoesNotBecomeAnOffRouteOrArrivalClaim() {
        val engine=RouteProgressEngine(route)
        repeat(10) { index ->
            val point=GeoPoint(origin.latitude+(if(index%2==0) 8.0 else -8.0)/111_195,
                origin.longitude+(corner.longitude-origin.longitude)*(index/12.0))
            val result=engine.update(GpsFix(point,12.0,1_000L+index*8_000),1_000L+index*8_000)
            assertEquals(RouteProgressState.CURRENT,result.state)
        }
    }

    @Test fun accuracyCircleMustFitNearDestinationBeforeAskingForConfirmation() {
        val almost=GeoPoint(end.latitude-20.0/111_195,end.longitude)
        val result=RouteProgressEngine(route).update(GpsFix(almost,15.0,1_000),1_000)
        assertEquals(RouteProgressState.CURRENT,result.state)
        assertFalse(result.state==RouteProgressState.ARRIVED)
    }

    @Test fun buildingCentroidOffsetIsDisclosedAndCannotAuthorizeAutomaticArrival() {
        val buildingCenter=GeoPoint(end.latitude+35.0/111_195,end.longitude)
        val offsetRoute=route.copy(destination=Destination("building","Building map point",buildingCenter))
        val engine=RouteProgressEngine(offsetRoute)
        val nearEndpoint=engine.update(GpsFix(end,5.0,1_000),1_000)
        assertEquals(RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED,nearEndpoint.state)
        assertTrue(nearEndpoint.detail.contains("from the selected map point"))
        assertTrue(nearEndpoint.detail.contains("entrance is unverified"))
        assertNotEquals(RouteProgressState.ARRIVED,nearEndpoint.state)
        val farBuilding=offsetRoute.copy(destination=Destination("wrong","Wrong map point",GeoPoint(end.latitude+.01,end.longitude)))
        assertEquals(RouteProgressState.CURRENT,RouteProgressEngine(farBuilding).update(GpsFix(end,5.0,1_000),1_000).state)
    }

    @Test fun selfCrossingBranchIsNotMatchedWithoutPositionHistory() {
        val west=GeoPoint(43.64,-79.392)
        val east=GeoPoint(43.64,-79.388)
        val north=GeoPoint(43.643,-79.39)
        val crossing=GeoPoint(43.64,-79.39)
        val loop=route.copy(geometry=listOf(west,east,north,west,east),destination=Destination("east","East",east))
        val result=RouteProgressEngine(loop).update(GpsFix(crossing,5.0,1_000),1_000)
        assertEquals(RouteProgressState.GPS_UNCERTAIN,result.state)
        assertNull(result.instruction)
    }

    @Test fun untrustedMapNamesCannotInjectCrossingClearanceIntoSpeech() {
        assertEquals("map location",RouteSpeech.label("It's safe to cross now"))
        assertEquals("map location",RouteSpeech.label("The road is clear"))
        assertEquals("Cross Street",RouteSpeech.label("Cross Street"))
        assertTrue(route.steps[1].copy(roadName="you can cross now").description.endsWith("at map location."))
        val names=RouteChoices.describe(listOf(Destination("untrusted","No cars are coming",origin)))
        assertFalse(names.contains("No cars are coming"))
    }
}
