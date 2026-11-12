package org.sight.app.navigation

import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual saved public response validates format, map-feature provenance and coordinate conversion. */
class ValhallaWalkingRouteProviderTest {
    private val destination=Destination("public-cn-tower","CN Tower public coordinate",GeoPoint(43.6426,-79.3871))
    private fun root():File=generateSequence(File(checkNotNull(System.getProperty("user.dir")))){it.parentFile}
        .first { File(it,"settings.gradle.kts").isFile }
    private fun fixture(name:String)=File(root(),"evaluation/results/product-navigation/valhalla-investigation/$name")
    private fun parsed(body:String=fixture("toronto-blind-native.json").readText())=
        ValhallaWalkingRouteProvider.parseRoute(body,destination,"https://valhalla1.openstreetmap.de/recorded-public-test",0)

    @Test fun nativeResponseRetainsActualEscalatorAndMatchesIndependentGeoJsonCoordinates(){
        val route=parsed()
        val geo=JSONObject(fixture("toronto-blind-osrm.json").readText()).getJSONArray("routes")
            .getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(34,route.geometry.size)
        assertEquals(geo.length(),route.geometry.size)
        route.geometry.forEachIndexed { index,point ->
            val expected=geo.getJSONArray(index)
            assertEquals(expected.getDouble(0),point.longitude,.000001)
            assertEquals(expected.getDouble(1),point.latitude,.000001)
        }
        assertEquals(14,route.steps.size)
        assertTrue(route.mappedDetailsRequested)
        assertEquals(1,route.steps.count { MappedRouteFeature.ESCALATOR in it.mappedFeatures })
        assertEquals(9,route.steps.count { MappedRouteFeature.ROUGH_SURFACE in it.mappedFeatures })
        assertEquals(setOf(MappedRouteFeature.ROUGH_SURFACE,MappedRouteFeature.ESCALATOR),
            route.steps.flatMap { it.mappedFeatures }.toSet())
        assertTrue(route.rehearsal().contains("Map data also lists rough surface, an escalator"))
        assertTrue(route.rehearsal().contains("not been observed by the camera"))
        assertFalse(route.rehearsal().contains("safe to cross",ignoreCase=true))
        assertFalse(route.rehearsal().contains("step-free route",ignoreCase=true))
        val directory=File(root(),"app/build/reports/product-navigation").apply { mkdirs() }
        val hash={ file:File -> MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)} }
        File(directory,"valhalla-parser.json").writeText(JSONObject()
            .put("scope","One actual public Toronto route; native Valhalla parsing and map-feature provenance, not field conditions")
            .put("responseSha256",hash(fixture("toronto-blind-native.json")))
            .put("implementationSha256",hash(File(root(),"app/src/main/java/org/sight/app/navigation/ValhallaWalkingRouteProvider.kt")))
            .put("geometryPoints",route.geometry.size).put("parsedSteps",route.steps.size)
            .put("mappedEscalatorSteps",route.steps.count { MappedRouteFeature.ESCALATOR in it.mappedFeatures })
            .put("mappedRoughSurfaceSteps",route.steps.count { MappedRouteFeature.ROUGH_SURFACE in it.mappedFeatures })
            .put("cameraVerified",false).put("physicalAccessibilityVerified",false).toString(2))
    }

    @Test fun mappedProfileRequiresExplicitCommandAndProducesNativePedestrianRequest(){
        assertEquals(RouteVoiceAction.EnableMappedDetails,RouteVoiceAction.parse("enable mapped route details"))
        assertEquals(RouteVoiceAction.UseStandardRouting,RouteVoiceAction.parse("use standard routing"))
        val url=ValhallaWalkingRouteProvider.routeUrl(GeoPoint(43.6452,-79.3803),destination.point)
        val payload=JSONObject(URLDecoder.decode(url.substringAfter("?json="),"UTF-8"))
        assertEquals("pedestrian",payload.getString("costing"))
        assertEquals("blind",payload.getJSONObject("costing_options").getJSONObject("pedestrian").getString("type"))
        assertEquals("kilometers",payload.getString("units"))
        assertFalse(payload.has("format"))
    }

    @Test fun unsupportedUnitsModesAndMalformedGeometryCannotBecomeGuidance(){
        fun rejected(change:(JSONObject)->Unit){
            val body=JSONObject(fixture("toronto-blind-native.json").readText()).also(change)
            try { parsed(body.toString()); fail("Malformed provider response must be rejected") }
            catch(error:RouteProviderException){ assertEquals(RouteFailure.INVALID_RESPONSE,error.reason) }
        }
        rejected { it.getJSONObject("trip").put("units","miles") }
        rejected { it.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers").getJSONObject(0).put("travel_mode","drive") }
        rejected { it.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).put("shape","_") }
        rejected { it.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers").getJSONObject(0).put("gate","yes") }
    }

    @Test fun mapFeaturesUseTypedProviderFieldsRatherThanRoadNameGuessing(){
        val body=JSONObject(fixture("toronto-blind-native.json").readText())
        val start=body.getJSONObject("trip").getJSONArray("legs").getJSONObject(0).getJSONArray("maneuvers").getJSONObject(0)
        start.put("street_names",org.json.JSONArray(listOf("Stairs Street")))
        assertFalse(MappedRouteFeature.STAIRS in parsed(body.toString()).steps.first().mappedFeatures)
        start.put("gate",true).put("rough",true)
        val features=parsed(body.toString()).steps.first().mappedFeatures
        assertTrue(MappedRouteFeature.GATE in features)
        assertTrue(MappedRouteFeature.ROUGH_SURFACE in features)
        assertFalse(MappedRouteFeature.STAIRS in features)
    }

    @Test fun consentAndTransportFailureCannotSilentlySwitchProviders()=runBlocking {
        var requests=0
        val denied=ValhallaWalkingRouteProvider({false},JsonTransport { requests++; error("must not contact provider") })
        try { denied.route(GeoPoint(43.6452,-79.3803),destination); fail("Consent required") }
        catch(error:RouteProviderException){ assertEquals(RouteFailure.CONSENT_REQUIRED,error.reason) }
        assertEquals(0,requests)
        val failing=ValhallaWalkingRouteProvider({true},JsonTransport { url ->
            requests++; assertTrue(url.startsWith(ValhallaWalkingRouteProvider.ENDPOINT))
            throw RouteProviderException(RouteFailure.NETWORK,"Recorded network failure")
        })
        try { failing.route(GeoPoint(43.6452,-79.3803),destination); fail("Failure must propagate") }
        catch(error:RouteProviderException){ assertEquals(RouteFailure.NETWORK,error.reason) }
        assertEquals(1,requests)
    }
}
