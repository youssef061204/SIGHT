package org.sight.app.navigation

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Executed production-engine evaluation. Location samples are constructed, not physical recordings. */
class NavigationProductReplayTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it,"settings.gradle.kts").isFile }

    @Test fun recordedPublicTripsParseThroughTheActualSearchAndFootRouteProvider() {
        val directory=File(root(),"evaluation/results/product-navigation")
        val sample=JSONObject(File(directory,"provider-sample.json").readText())
        val trips=sample.getJSONArray("trips")
        var parsedRoutes=0
        for(index in 0 until trips.length()) {
            val trip=trips.getJSONObject(index)
            if(trip.getString("status")!="returned_route") continue
            for(role in listOf("originSearch","destinationSearch")) {
                val search=trip.getJSONObject(role)
                val found=PublicWalkingRouteProvider.parseSearch(File(directory,search.getString("responseFile")).readText())
                assertEquals(search.getInt("results"),found.size)
                assertTrue(found.isNotEmpty())
            }
            val target=trip.getJSONObject("destination")
            val destination=Destination(target.getString("id"),target.getString("label"),
                GeoPoint(target.getDouble("latitude"),target.getDouble("longitude")))
            val recorded=trip.getJSONObject("route")
            val route=PublicWalkingRouteProvider.parseRoute(File(directory,recorded.getString("responseFile")).readText(),
                destination,recorded.getString("url"),0)
            assertTrue(route.sourceUrl.contains("routed-foot"))
            assertTrue(route.distanceMeters>0)
            assertTrue(route.steps.isNotEmpty())
            parsedRoutes++
        }
        assertEquals("This fixture records three successful fixed public routes",3,parsedRoutes)
    }

    @Test fun actualPublicRouteGeometryAndLabelledLocationFailuresUseProductionEngine() {
        val root=root()
        val directory=File(root,"evaluation/results/product-navigation")
        val manifest=JSONObject(File(directory,"constructed-replays.json").readText())
        val raw=File(root,manifest.getString("sourceFile")).readBytes()
        val sha=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals("Pinned public-provider route fixture changed",manifest.getString("sourceSha256"),sha)
        val selected=manifest.getJSONObject("destination")
        val destination=Destination(selected.getString("id"),selected.getString("label"),
            GeoPoint(selected.getDouble("latitude"),selected.getDouble("longitude")))
        val route=PublicWalkingRouteProvider.parseRoute(raw.toString(Charsets.UTF_8),destination,
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/public-landmark-fixture",0)
        val scenarios=manifest.getJSONArray("scenarios")
        val outputs=JSONArray()
        val failures=mutableListOf<String>()
        val forbidden=Regex("(?i)safe to cross|road is clear|path is clear|you can cross|cross now|no cars are coming|walk into the intersection")
        for(index in 0 until scenarios.length()) {
            val scenario=scenarios.getJSONObject(index)
            val engine=RouteProgressEngine(route)
            val input=scenario.getJSONArray("frames")
            val stages=JSONArray()
            val states=mutableListOf<RouteProgressState>()
            val directions=mutableSetOf<Int>()
            var unsafeClaims=0
            for(frameIndex in 0 until input.length()) {
                val frame=input.getJSONObject(frameIndex)
                val fix=GpsFix(GeoPoint(frame.getDouble("latitude"),frame.getDouble("longitude")),
                    frame.getDouble("accuracyMeters"),frame.getLong("capturedElapsedMs"))
                val result=engine.update(fix,frame.getLong("nowElapsedMs"))
                states+=result.state
                result.nextStep?.index?.let(directions::add)
                if(forbidden.containsMatchIn(result.instruction ?: result.detail)) unsafeClaims++
                stages.put(JSONObject().put("sample",frameIndex).put("state",result.state.name)
                    .put("remainingMeters",result.remainingMeters ?: JSONObject.NULL)
                    .put("nextStep",result.nextStep?.index ?: JSONObject.NULL)
                    .put("distanceToNextStepMeters",result.distanceToNextStepMeters ?: JSONObject.NULL)
                    .put("instruction",result.instruction ?: JSONObject.NULL)
                    .put("detail",result.detail))
            }
            val id=scenario.getString("id")
            val offRoute=states.count { it==RouteProgressState.OFF_ROUTE }
            val uncertain=states.count { it==RouteProgressState.GPS_UNCERTAIN }
            val arrivalSuggested=states.count { it==RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED }
            val arrived=states.count { it==RouteProgressState.ARRIVED }
            if(scenario.optBoolean("expectNoOffRoute") && offRoute>0) failures+="$id: false route disagreement"
            if(scenario.optBoolean("expectNoUncertainty") && uncertain>0) failures+="$id: unexpected uncertainty on exact route trace"
            if(scenario.optBoolean("expectNoArrival") && arrivalSuggested+arrived>0) failures+="$id: false arrival suggestion"
            if(scenario.has("expectLastState") && states.last().name!=scenario.getString("expectLastState")) failures+="$id: wrong final state"
            if(arrived>0) failures+="$id: arrival declared without user confirmation"
            if(unsafeClaims>0) failures+="$id: unsupported clearance statement"
            val turns=route.steps.filter { it.maneuverType !in setOf("depart","arrive","continue","new name","notification") }.map { it.index }.toSet()
            outputs.put(JSONObject().put("id",id).put("purpose",scenario.getString("purpose"))
                .put("samples",input.length()).put("offRouteStates",offRoute).put("uncertainStates",uncertain)
                .put("arrivalConfirmationSuggestions",arrivalSuggested).put("automaticArrivalDeclarations",arrived)
                .put("unsupportedClearanceClaims",unsafeClaims)
                .put("providerTurnIndexes",JSONArray(turns.sorted())).put("turnsObservedAsUpcoming",JSONArray(turns.intersect(directions).sorted()))
                .put("stages",stages))
        }
        val output=File(root,"app/build/reports/product-navigation/evaluation.json")
        output.parentFile.mkdirs()
        val implementation=JSONObject()
        listOf("RouteModels.kt","RouteGeometry.kt","RouteProgressEngine.kt","RouteVoiceAction.kt","PublicWalkingRouteProvider.kt").forEach { name ->
            val bytes=File(root,"app/src/main/java/org/sight/app/navigation/$name").readBytes()
            implementation.put(name,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        output.writeText(JSONObject().put("schema","sight.navigation-production-engine-replay")
            .put("scope",manifest.getString("scope"))
            .put("sourceSha256",manifest.getString("sourceSha256"))
            .put("implementationSha256",implementation)
            .put("routeProvider",route.provider).put("routeGeometryPoints",route.geometry.size)
            .put("routeSteps",route.steps.size).put("scenarios",outputs).put("failures",JSONArray(failures))
            .put("boundaries","No physical walking success, GPS accuracy distribution, route accessibility, audible latency, speech recognition or provider availability is established by these constructed traces")
            .toString(2))
        assertTrue("Navigation replay failures: $failures; report: $output",failures.isEmpty())
    }
}
