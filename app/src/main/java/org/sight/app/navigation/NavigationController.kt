package org.sight.app.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Optional foreground-only routing. User consent precedes any network request or location use. */
class NavigationController(private val context: Context, private val say: (String) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var consent by mutableStateOf(false); private set
    var originResults by mutableStateOf<List<Destination>>(emptyList()); private set
    var destinationResults by mutableStateOf<List<Destination>>(emptyList()); private set
    var origin by mutableStateOf<Destination?>(null); private set
    var destination by mutableStateOf<Destination?>(null); private set
    var route by mutableStateOf<WalkingRoute?>(null); private set
    var progress by mutableStateOf<RouteProgress?>(null); private set
    var message by mutableStateOf("Enable route services to search public map data."); private set
    var busy by mutableStateOf(false); private set
    var gpsActive by mutableStateOf(false); private set
    private val provider = PublicWalkingRouteProvider(networkAllowed = { consent })
    private var operation: Job? = null
    private var operationVersion = 0L
    private var progressEngine: RouteProgressEngine? = null
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var lastSpokenStep: Int? = null
    private var lastProgressState: RouteProgressState? = null
    private var latestFix: GpsFix? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!gpsActive || !consent) return
            val fix = GpsFix(GeoPoint(location.latitude, location.longitude),
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                location.elapsedRealtimeNanos / 1_000_000)
            latestFix = fix
            updateProgress(fix)
        }
        override fun onProviderDisabled(provider: String) {
            message = "GPS is unavailable. Route progress is uncertain."
            progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = message)
            say(message)
        }
        @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    init { scope.launch {
        while (isActive) {
            if (gpsActive) latestFix?.let { fix ->
                if (SystemClock.elapsedRealtime() - fix.capturedElapsedMs > 10_000) {
                    progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = "GPS fix expired. Waiting for a fresh location.")
                    if (lastProgressState != RouteProgressState.GPS_UNCERTAIN) say(progress!!.detail)
                    lastProgressState = RouteProgressState.GPS_UNCERTAIN
                }
            }
            delay(1_000)
        }
    } }

    fun changeConsent(value: Boolean) {
        consent = value
        if (!value) { operation?.cancel(); busy = false; stopGps(); message = "Route services off. Local camera assistance remains available." }
        else message = "Route services enabled for this session. Choose a start and a destination."
    }
    fun search(query: String, start: Boolean) {
        if (!consent) { message = "Enable route services first."; return }
        runOperation {
            val results = provider.search(query.trim())
            if (start) originResults = results else destinationResults = results
            message = if (results.isEmpty()) "No matching place returned. Try a more specific place name." else "Choose one of ${results.size} map results."
        }
    }
    fun select(item: Destination, start: Boolean) {
        if (start) origin = item else destination = item
        invalidateRoute()
    }
    fun requestRoute(fromGps: Boolean = false) {
        val selectedDestination = destination ?: run { message = "Select a destination first."; return }
        val fix = latestFix
        val start = if (fromGps) {
            if (fix == null || SystemClock.elapsedRealtime()-fix.capturedElapsedMs !in 0..10_000 || fix.accuracyMeters > 25) {
                message = "A fresh GPS fix with accuracy of 25 meters or better is required to recalculate."; return
            }
            fix.point
        } else origin?.point ?: run { message = "Select a starting place first."; return }
        runOperation {
            stopGps()
            val received = provider.route(start, selectedDestination)
            route = received
            progressEngine = RouteProgressEngine(received)
            progress = null; lastSpokenStep = null; lastProgressState = null
            message = "Provider route loaded. Rehearse it before enabling GPS progress."
        }
    }
    private fun runOperation(block: suspend () -> Unit) {
        operation?.cancel()
        val version = ++operationVersion
        operation = scope.launch {
            busy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RouteProviderException) { message = "Route service: ${error.message}" }
            catch (_: Exception) { message = "Route request failed. Local camera assistance remains available." }
            finally { if (operationVersion == version) busy = false }
        }
    }
    fun rehearse() { route?.let { say(it.rehearsal()) } }
    fun startGps() {
        if (!consent || route == null) { message = "Load a provider route before starting GPS progress."; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            message = "Location permission is required for GPS progress."; return
        }
        try {
            gpsActive = true
            latestFix = null
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000, 1f, listener, android.os.Looper.getMainLooper())
            message = "Foreground GPS active. Waiting for a sufficiently accurate fix."
        } catch (_: Exception) { gpsActive = false; message = "GPS could not start. Check Android location settings." }
    }
    fun stopGps() {
        locationManager.removeUpdates(listener)
        gpsActive = false
        latestFix = null
        progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = "GPS progress paused.")
    }
    private fun updateProgress(fix: GpsFix) {
        val update = progressEngine?.update(fix, SystemClock.elapsedRealtime()) ?: return
        progress = update
        if (update.state != lastProgressState && update.state != RouteProgressState.CURRENT) say(update.detail)
        if (update.state == RouteProgressState.CURRENT && update.nextStep?.index != lastSpokenStep) {
            update.nextStep?.let { say(it.description); lastSpokenStep = it.index }
        }
        lastProgressState = update.state
    }
    fun confirmArrival() {
        if (progressEngine?.confirmArrival(SystemClock.elapsedRealtime()) == true) {
            progress = RouteProgress(RouteProgressState.ARRIVED, detail = "Arrival confirmed by you.")
            say("Arrival confirmed by you.")
            locationManager.removeUpdates(listener); gpsActive = false
        } else message = "Arrival needs a fresh accurate fix near the provider destination and your confirmation."
    }
    private fun invalidateRoute() { operation?.cancel(); stopGps(); route = null; progressEngine = null; message = "Selection changed. Request a new route." }
    override fun close() { stopGps(); operation?.cancel(); scope.cancel() }
}
