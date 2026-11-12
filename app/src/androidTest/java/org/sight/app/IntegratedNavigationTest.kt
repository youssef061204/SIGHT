package org.sight.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.sight.app.navigation.*
import org.sight.core.GoalType
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual Activity/CameraX/task runtime; recorded public HTTP and constructed GPS are labelled. */
class IntegratedNavigationTest {
    private val requests=AtomicInteger()
    @get:Rule(order=0) val recordedProvider=object:ExternalResource(){
        override fun before(){
            val assets=InstrumentationRegistry.getInstrumentation().context.assets
            SightSession.testRouteServiceFactory={ PublicWalkingRouteProvider({true},JsonTransport { url ->
                requests.incrementAndGet()
                assets.open(if("photon.komoot.io" in url) "navigation/toronto-destination-search.json" else "navigation/toronto-route.json")
                    .bufferedReader().use { it.readText() }
            }) }
        }
        override fun after(){ SightSession.testRouteServiceFactory=null }
    }
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()

    @Test fun spokenCommandRoutingCameraAndVisualSubtaskShareOneRetainedRoute(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String){ instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        } }
        shell("pm grant org.sight.app android.permission.CAMERA")
        shell("pm grant org.sight.app android.permission.ACCESS_COARSE_LOCATION")
        shell("pm grant org.sight.app android.permission.ACCESS_FINE_LOCATION")
        shell("settings put secure location_mode 3")
        if(rule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Continue").performClick()
        fun session(block:(SightSession)->Unit){ rule.activityRule.scenario.onActivity { block(ViewModelProvider(it)[SightSession::class.java]) } }
        fun command(text:String)=session { it.controller.command(text) }
        command("SIGHT, take me to CN Tower Toronto")
        assertEquals("No provider request before explicit consent",0,requests.get())
        command("enable route services")
        command("take me to CN Tower Toronto")
        rule.waitUntil(20_000){ var ready=false; session { ready=it.navigation.destinationResults.isNotEmpty() }; ready }
        session { assertNull("No automatic first-result selection",it.navigation.destination) }
        command("choose first")
        rule.waitUntil(15_000){ var ready=false; session { ready=it.navigation.gpsActive }; ready }
        val routeBody=instrumentation.context.assets.open("navigation/toronto-route.json").bufferedReader().use { it.readText() }
        lateinit var capturedRoute:WalkingRoute
        session {
            capturedRoute=PublicWalkingRouteProvider.parseRoute(routeBody,it.navigation.destination!!,"https://routing.openstreetmap.de/recorded-test",0)
            it.navigation.acceptLocationFix(GpsFix(capturedRoute.geometry.first(),5.0,SystemClock.elapsedRealtime()))
        }
        rule.waitUntil(20_000){ var ready=false; session { ready=it.navigation.guidanceActive && it.navigation.route!=null }; ready }
        rule.onNodeWithText("Start camera").performScrollTo().performClick()
        rule.waitUntil(60_000){ var ready=false; session { ready=(it.controller.timing?.analyzed ?: 0)>=3 }; ready }
        var frames=0L
        session { frames=it.controller.timing!!.analyzed; assertTrue(it.navigation.guidanceActive) }
        command("road diagnostics")
        rule.waitUntil(90_000){ var ready=false; session { ready=it.controller.latestRoadTiming?.status=="completed" }; ready }
        command("find the entrance")
        session {
            assertEquals("Text-grounded fallback does not invent an entrance detector",GoalType.READ,it.controller.scene.goal.type)
            assertTrue("Route remains active during visual subtask",it.navigation.guidanceActive)
            assertTrue(it.controller.running)
        }
        command("cancel")
        command("repeat direction")
        session { assertTrue(it.navigation.hasRouteContext); assertTrue(it.navigation.guidanceActive) }
        rule.activityRule.scenario.recreate()
        session { assertTrue(it.navigation.hasRouteContext); assertFalse(it.navigation.gpsActive); assertFalse(it.controller.running) }
        shell("input keyevent KEYCODE_VOLUME_DOWN")
        rule.waitForIdle()
        session {
            assertFalse(it.navigation.hasRouteContext); assertFalse(it.navigation.gpsActive)
            assertFalse(it.controller.running); assertFalse(it.controller.listening)
            File(instrumentation.targetContext.cacheDir,"integrated-navigation-proof.json").writeText(JSONObject()
                .put("scope","Actual Android Activity, CameraX, provider parsing, command/task and lifecycle integration; recorded HTTP, constructed GPS, transcript input after recognition")
                .put("recordedHttpRequests",requests.get()).put("liveCameraDetectorFrames",frames)
                .put("explicitConsent",true).put("explicitDestinationChoice",true)
                .put("routePreservedDuringTextSearch",true).put("backgroundStoppedSensors",true)
                .put("actualCameraReachedRoadModel",true)
                .put("allAssistanceStopped",true).put("physicalGpsOrMicrophone",false).toString(2))
        }
    }
}
