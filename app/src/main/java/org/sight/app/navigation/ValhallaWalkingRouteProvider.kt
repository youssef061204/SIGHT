package org.sight.app.navigation

import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Optional mapped-mobility detail service. No silent fallback or present-accessibility assertion. */
class ValhallaWalkingRouteProvider(
    private val networkAllowed: () -> Boolean,
    private val transport: JsonTransport = BoundedHttpsTransport(),
) : WalkingRouteService {
    private val searchProvider = PublicWalkingRouteProvider(networkAllowed, transport)
    override suspend fun search(query: String, bias: GeoPoint?): List<Destination> = searchProvider.search(query,bias)
    override suspend fun route(origin: GeoPoint, destination: Destination): WalkingRoute {
        consent()
        val direct = RouteGeometry.distanceMeters(origin,destination.point)
        if (direct !in 10.0..50_000.0) throw RouteProviderException(RouteFailure.INVALID_REQUEST,"Walking destinations must be 10 metres to 50 kilometres away.")
        val url = routeUrl(origin,destination.point)
        val body = transport.get(url)
        currentCoroutineContext().ensureActive(); consent()
        return withContext(Dispatchers.Default) {
            parseRoute(body,destination,url,System.currentTimeMillis()).also {
                if(RouteGeometry.distanceMeters(it.geometry.first(),origin)>100)
                    throw RouteProviderException(RouteFailure.INVALID_RESPONSE,"Mapped route begins too far from the requested start.")
            }
        }
    }
    private fun consent() {
        if (!networkAllowed()) throw RouteProviderException(RouteFailure.CONSENT_REQUIRED,"Enable public route services before sharing route coordinates.")
    }
    companion object {
        const val ENDPOINT = "https://valhalla1.openstreetmap.de/route"
        const val DOCUMENTATION = "https://valhalla.github.io/valhalla/api/route/api-reference/"
        fun routeUrl(origin: GeoPoint, destination: GeoPoint): String {
            val payload = JSONObject().put("locations",JSONArray(listOf(
                JSONObject().put("lat",origin.latitude).put("lon",origin.longitude),
                JSONObject().put("lat",destination.latitude).put("lon",destination.longitude))))
                .put("costing","pedestrian").put("costing_options",JSONObject().put("pedestrian",JSONObject().put("type","blind")))
                .put("language","en-US").put("units","kilometers").put("directions_type","instructions")
            return ENDPOINT+"?json="+URLEncoder.encode(payload.toString(),Charsets.UTF_8.name())
        }

        fun parseRoute(body: String, destination: Destination, sourceUrl: String, retrievedAtEpochMs: Long): WalkingRoute = try {
            val root=JSONObject(body)
            if (!root.has("trip")) throw RouteProviderException(RouteFailure.NO_ROUTE,"Optional mapped-details provider did not return a pedestrian route.")
            val trip=root.getJSONObject("trip")
            require(trip.getInt("status")==0 && trip.getString("units")=="kilometers") { "Unsupported route status or distance units" }
            val legs=trip.getJSONArray("legs")
            require(legs.length() in 1..20)
            val geometry=mutableListOf<GeoPoint>()
            val steps=mutableListOf<RouteStep>()
            for (index in 0 until legs.length()) {
                val leg=legs.getJSONObject(index)
                val shape=decodePolyline6(leg.getString("shape"))
                require(geometry.size+shape.size<=50_000)
                geometry += if(geometry.isNotEmpty() && geometry.last()==shape.first()) shape.drop(1) else shape
                val maneuvers=leg.getJSONArray("maneuvers")
                require(maneuvers.length() in 1..1_000)
                for(maneuverIndex in 0 until maneuvers.length()) {
                    require(steps.size<1_000)
                    val maneuver=maneuvers.getJSONObject(maneuverIndex)
                    require(maneuver.getString("travel_mode")=="pedestrian") { "Non-pedestrian maneuver" }
                    val begin=maneuver.getInt("begin_shape_index")
                    val end=maneuver.getInt("end_shape_index")
                    require(begin in shape.indices && end in begin until shape.size)
                    val nativeType=maneuver.getInt("type")
                    val names=maneuver.optJSONArray("street_names")
                    val road=if(names!=null && names.length()>0) RouteSpeech.label(names.getString(0)) else ""
                    val (type,modifier)=maneuverKind(nativeType)
                    val mapped=features(nativeType).toMutableSet()
                    for((field,feature) in listOf("gate" to MappedRouteFeature.GATE,"rough" to MappedRouteFeature.ROUGH_SURFACE,"ferry" to MappedRouteFeature.FERRY)) {
                        if(maneuver.has(field)) {
                            val flag=maneuver.get(field)
                            require(flag is Boolean) { "Invalid mapped feature flag" }
                            if(flag) mapped+=feature
                        }
                    }
                    steps += RouteStep(steps.size,road,type,modifier,shape[begin],
                        number(maneuver.getDouble("length"))*1_000,number(maneuver.getDouble("time")),mapped)
                }
            }
            RouteGeometry.project(geometry.first(),geometry)
            require(RouteGeometry.distanceMeters(geometry.last(),destination.point)<=100) { "Route endpoint is too far from selected map point" }
            val summary=trip.getJSONObject("summary")
            val metres=number(summary.getDouble("length"))*1_000
            val seconds=number(summary.getDouble("time"))
            require(metres in 1.0..150_000.0 && seconds in .01..172_800.0)
            WalkingRoute(UUID.randomUUID().toString(),"FOSSGIS Valhalla pedestrian (mapped details requested)",sourceUrl,retrievedAtEpochMs,
                geometry,steps,metres,seconds,destination,mappedDetailsRequested=true)
        } catch(known:RouteProviderException) { throw known }
        catch(error:Exception) { throw RouteProviderException(RouteFailure.INVALID_RESPONSE,"Optional mapped-details response is incomplete or unsupported.",error) }

        private fun number(value:Double):Double { require(value.isFinite() && value>=0);return value }

        /** Native maneuver IDs from the upstream API reference; not inferred from arbitrary road names. */
        internal fun features(type:Int):Set<MappedRouteFeature> = when(type) {
            39 -> setOf(MappedRouteFeature.ELEVATOR)
            40 -> setOf(MappedRouteFeature.STAIRS)
            41 -> setOf(MappedRouteFeature.ESCALATOR)
            42 -> setOf(MappedRouteFeature.BUILDING_ENTRANCE)
            43 -> setOf(MappedRouteFeature.BUILDING_EXIT)
            else -> emptySet()
        }
        private fun maneuverKind(type:Int):Pair<String,String?> = when(type) {
            1,2,3 -> "depart" to null
            4,5,6 -> "arrive" to null
            9 -> "turn" to "slight right"
            10,18,20,23,37 -> "turn" to "right"
            11 -> "turn" to "sharp right"
            12,13 -> "turn" to "uturn"
            14 -> "turn" to "sharp left"
            15,19,21,24,38 -> "turn" to "left"
            16 -> "turn" to "slight left"
            26 -> "roundabout" to null
            27 -> "exit roundabout" to null
            else -> "continue" to null
        }

        /** The native Valhalla format is latitude/longitude, precision1e6. */
        fun decodePolyline6(encoded:String):List<GeoPoint> {
            require(encoded.length in 2..500_000)
            var index=0
            var latitude=0L
            var longitude=0L
            fun component():Long {
                var result=0L
                var shift=0
                var value:Int
                do {
                    require(index<encoded.length && shift<=35)
                    value=encoded[index++].code-63
                    require(value in 0..63)
                    result=result or ((value and 31).toLong() shl shift)
                    shift+=5
                } while(value>=32)
                return if(result and 1L != 0L) (result ushr 1).inv() else result ushr 1
            }
            val points=mutableListOf<GeoPoint>()
            while(index<encoded.length) {
                require(points.size<50_000)
                latitude+=component();longitude+=component()
                points+=GeoPoint(latitude/1_000_000.0,longitude/1_000_000.0)
            }
            require(points.size>=2)
            return points
        }
    }
}
