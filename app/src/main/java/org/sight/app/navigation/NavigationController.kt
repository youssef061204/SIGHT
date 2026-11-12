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

/** Foreground walking guidance, with independent visual tasks owned by the host. */
class NavigationController(
    private val context: Context,
    private val say: (String) -> Unit,
    routeService: WalkingRouteService? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var consent by mutableStateOf(false); private set
    var query by mutableStateOf(""); private set
    var providerPreference by mutableStateOf(RouteProviderPreference.STANDARD); private set
    var originResults by mutableStateOf<List<Destination>>(emptyList()); private set
    var destinationResults by mutableStateOf<List<Destination>>(emptyList()); private set
    var origin by mutableStateOf<Destination?>(null); private set
    var destination by mutableStateOf<Destination?>(null); private set
    var route by mutableStateOf<WalkingRoute?>(null); private set
    var progress by mutableStateOf<RouteProgress?>(null); private set
    var message by mutableStateOf("Route services are off. Say enable route services to use public map search and routing."); private set
    var busy by mutableStateOf(false); private set
    var gpsActive by mutableStateOf(false); private set
    var phase by mutableStateOf(NavigationPhase.IDLE); private set
    var currentInstruction by mutableStateOf(""); private set
    var currentInstructionCapturedAtMs by mutableStateOf<Long?>(null); private set
    var savedDestinations by mutableStateOf<List<SavedDestination>>(emptyList()); private set
    var onLocationPermissionRequired: (() -> Unit)? = null
    var onNavigationCue: ((NavigationCue) -> Unit)? = null
    /** Host invalidates only navigation speech; visual/OCR tasks retain their own evidence. */
    var onNavigationInvalidated: (() -> Unit)? = null
    private var guidanceRequested by mutableStateOf(false)
    val guidanceActive: Boolean get() = guidanceRequested && route != null && gpsActive && phase != NavigationPhase.ARRIVED
    val hasRouteContext: Boolean get() = route != null
    private val standardProvider: WalkingRouteService = routeService ?: PublicWalkingRouteProvider(networkAllowed = { consent })
    private val mappedProvider: WalkingRouteService = routeService ?: ValhallaWalkingRouteProvider(networkAllowed = { consent })
    private val provider: WalkingRouteService get() = if (providerPreference == RouteProviderPreference.MAPPED_DETAILS) mappedProvider else standardProvider
    private val destinationStore = SavedDestinationStore(context)
    private var operation: Job? = null
    private var operationVersion = 0L
    private var progressEngine: RouteProgressEngine? = null
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var lastProgressState: RouteProgressState? = null
    private var latestFix: GpsFix? = null
    private var gpsStartedAtMs = 0L
    private var pendingRouteFromGps = false
    private var spokenChoiceStart = false
    private var spokenChoicesPending = false
    private var routeAfterSelection = false
    private var lastRerouteAtMs = -60_000L
    private val announcedKeys = linkedSetOf<String>()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!gpsActive || !consent) return
            acceptLocationFix(GpsFix(GeoPoint(location.latitude, location.longitude),
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                location.elapsedRealtimeNanos / 1_000_000,
                if (location.hasSpeed()) location.speed.toDouble() else null,
                if (location.hasBearing()) location.bearing.toDouble() else null,
                if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null))
        }
        override fun onProviderDisabled(provider: String) {
            if (!gpsActive || !consent) return
            val text = "GPS is unavailable. Route progress is uncertain. Check Android location settings."
            progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = text)
            clearCurrentInstruction()
            lastProgressState = RouteProgressState.GPS_UNCERTAIN
            announce(text)
        }
        @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    init {
        try { savedDestinations = destinationStore.load() }
        catch (_: Exception) { message = "Saved destinations could not be opened. They can be deleted without reading them." }
        scope.launch {
            while (isActive) {
                if (gpsActive && SystemClock.elapsedRealtime() - (latestFix?.capturedElapsedMs ?: gpsStartedAtMs) > 10_000) {
                    val text = if (latestFix == null) "No GPS fix received. Check Android location settings and open sky. Route progress is uncertain."
                        else "GPS fix expired. Waiting for a fresh location."
                    progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = text)
                    clearCurrentInstruction(currentInstruction.isNotBlank() || lastProgressState != RouteProgressState.GPS_UNCERTAIN)
                    if (lastProgressState != RouteProgressState.GPS_UNCERTAIN) announce(text)
                    lastProgressState = RouteProgressState.GPS_UNCERTAIN
                }
                delay(1_000)
            }
        }
    }

    fun changeConsent(value: Boolean) {
        consent = value
        if (!value) {
            cancelNavigation(announce = false)
            message = "Route services off. Local camera assistance remains available."
        } else message = "Route services enabled for this session. Searches and route coordinates go to public map providers. Say take me to followed by a place."
    }

    fun changeQuery(value: String) { query = value.take(160) }
    fun changeProviderPreference(value: RouteProviderPreference) {
        if (providerPreference == value) return
        clearActiveRoute()
        providerPreference = value
        phase = NavigationPhase.IDLE
        announce(if (value == RouteProviderPreference.MAPPED_DETAILS)
            "Mapped route details selected. Valhalla's pedestrian profile requests additional map instructions; it does not guarantee step-free paths, complete sidewalk data or present accessibility. Any previous guidance is paused. Say start navigation after choosing a destination."
        else "Standard walking routing selected. Additional mapped-mobility details are not requested. Any previous guidance is paused. Say start navigation after choosing a destination.")
    }

    fun search(query: String, start: Boolean) = search(query, start, spoken = false)
    private fun search(query: String, start: Boolean, spoken: Boolean) {
        if (!consent) { announce("Route services are off. Say enable route services before sending place names and location coordinates to public map providers."); return }
        val bias = latestFix?.takeIf(::freshAccurate)?.point
        if (!start) {
            this.query = query.take(160)
            clearActiveRoute()
            destination = null; destinationResults = emptyList()
        } else {
            // A starting-place search is route planning, not permission to continue old guidance.
            stopGps()
            originResults = emptyList()
        }
        spokenChoicesPending = false
        routeAfterSelection = spoken && !start
        phase = NavigationPhase.SEARCHING
        runOperation {
            val results = provider.search(query.trim(), bias)
            currentCoroutineContext().ensureActive()
            if (start) originResults = results else destinationResults = results
            if (results.isEmpty()) {
                phase = NavigationPhase.IDLE
                announce("No matching place returned. Add a town, street or address and try again.")
            } else if (spoken) {
                spokenChoiceStart = start
                spokenChoicesPending = true
                phase = NavigationPhase.CHOOSING_DESTINATION
                announce(RouteChoices.describe(results), NavigationCueKind.CHOICES)
            } else {
                phase = NavigationPhase.CHOOSING_DESTINATION
                message = "Choose one of ${results.size} map results. You can say choose option one, two, three, four or five."
                spokenChoiceStart = start; spokenChoicesPending = true
            }
        }
    }

    fun select(item: Destination, start: Boolean) {
        if (!consent) { announce("Enable route services first."); return }
        val results = if (start) originResults else destinationResults
        if (results.none { it.id == item.id }) { announce("That selection is no longer available. Search again."); return }
        operation?.cancel()
        clearActiveRoute()
        if (start) origin = item else destination = item
        spokenChoicesPending = false
        phase = NavigationPhase.IDLE
        message = "Selected ${RouteSpeech.label(item.label)}."
        if (!start && routeAfterSelection) {
            routeAfterSelection = false
            announce(message)
            beginNavigation()
        }
    }

    private fun choose(index: Int) {
        if (!spokenChoicesPending) { announce("There is no destination choice pending. Say take me to followed by a place."); return }
        val results = if (spokenChoiceStart) originResults else destinationResults
        val selected = results.getOrNull(index) ?: run { announce("That option is not in the returned results. ${RouteChoices.describe(results)}", NavigationCueKind.CHOICES); return }
        select(selected, spokenChoiceStart)
        if (spokenChoiceStart) announce("Starting place selected. Now choose a destination.")
        else if (!guidanceRequested) announce("Destination selected. Say start navigation to use current GPS, or choose a starting place for route rehearsal.")
    }

    fun requestRoute(fromGps: Boolean = false) {
        val selected = destination ?: run { announce("Select a destination first."); return }
        val fix = latestFix
        val start = if (fromGps) {
            if (fix == null || !freshAccurate(fix)) { announce("A fresh GPS fix with accuracy of 25 metres or better is required. Say start navigation to acquire location."); return }
            fix.point
        } else origin?.point ?: run { announce("Choose a starting place, or say start navigation to use current GPS."); return }
        loadRoute(start, selected, startGuidance = fromGps && guidanceRequested)
    }

    private fun loadRoute(start: GeoPoint, selected: Destination, startGuidance: Boolean) {
        clearCurrentInstruction()
        progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = "Requesting a new walking route. Turn directions are paused.")
        phase = NavigationPhase.ROUTING
        runOperation {
            val received = provider.route(start, selected)
            currentCoroutineContext().ensureActive()
            route = received; progressEngine = RouteProgressEngine(received)
            progress = null; lastProgressState = null; announcedKeys.clear()
            clearCurrentInstruction()
            guidanceRequested = startGuidance
            phase = if (startGuidance && gpsActive) NavigationPhase.GUIDING else NavigationPhase.READY
            announce("Walking route loaded to ${RouteSpeech.label(selected.label)}. Approximately ${RouteSpeech.approximateMeters(received.distanceMeters)} metres, about ${(received.durationSeconds / 60).toInt().coerceAtLeast(1)} minutes. Sidewalk access and current crossing conditions are unverified.", NavigationCueKind.OVERVIEW)
            if (startGuidance) {
                if (!gpsActive) startGps() else latestFix?.takeIf(::freshAccurate)?.let(::updateProgress)
            } else message = "Route ready for rehearsal. Say start navigation to use fresh GPS progress."
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        operation?.cancel()
        val version = ++operationVersion
        operation = scope.launch {
            busy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: RouteProviderException) {
                if (operationVersion != version || !isActive) return@launch
                phase = if (route != null && gpsActive && guidanceRequested) NavigationPhase.GUIDING else NavigationPhase.IDLE
                announce("Route service: ${error.message}" + if (providerPreference == RouteProviderPreference.MAPPED_DETAILS)
                    " No provider fallback was performed. Say use standard routing if you want to try an ordinary walking route." else "")
            }
            catch (_: Exception) {
                if (operationVersion != version || !isActive) return@launch
                phase = if (route != null && gpsActive && guidanceRequested) NavigationPhase.GUIDING else NavigationPhase.IDLE
                announce("Route request failed. Check connectivity and retry. Local camera assistance remains available.")
            }
            finally { if (operationVersion == version) busy = false }
        }
    }

    fun rehearse() {
        route?.let { announce(it.rehearsal(), NavigationCueKind.OVERVIEW) }
            ?: announce("Choose a destination and load a walking route first.")
    }

    fun handleVoiceCommand(command: String): Boolean {
        when (val action = RouteVoiceAction.parse(command) ?: return false) {
            is RouteVoiceAction.Search -> search(action.query, action.start, spoken = true)
            is RouteVoiceAction.Choose -> choose(action.index)
            RouteVoiceAction.EnableServices -> { changeConsent(true); announce(message) }
            RouteVoiceAction.DisableServices -> { changeConsent(false); announce(message) }
            RouteVoiceAction.EnableMappedDetails -> changeProviderPreference(RouteProviderPreference.MAPPED_DETAILS)
            RouteVoiceAction.UseStandardRouting -> changeProviderPreference(RouteProviderPreference.STANDARD)
            RouteVoiceAction.Overview -> rehearse()
            RouteVoiceAction.PauseGps -> { stopGps(); announce("Navigation paused. The route is retained. Say resume navigation when ready.") }
            RouteVoiceAction.ResumeGps -> resumeNavigation()
            RouteVoiceAction.StartGuidance -> beginNavigation()
            RouteVoiceAction.Cancel -> cancelNavigation()
            RouteVoiceAction.Recalculate -> { guidanceRequested = true; requestRoute(fromGps = true) }
            RouteVoiceAction.Repeat -> repeatInstruction()
            RouteVoiceAction.DistanceToTurn -> distanceToNextTurn()
            RouteVoiceAction.ConfirmArrival -> confirmArrival()
            is RouteVoiceAction.SaveDestination -> saveDestination(action.name)
            is RouteVoiceAction.RecallDestination -> recallDestination(action.name)
            is RouteVoiceAction.DeleteDestination -> deleteSavedDestination(action.name)
            RouteVoiceAction.ListDestinations -> announce(if (savedDestinations.isEmpty()) "No destinations saved." else
                "Saved destinations: ${savedDestinations.joinToString { RouteSpeech.label(it.name) }}. Say take me to saved followed by a name.")
        }
        return true
    }

    fun beginNavigation() {
        if (!consent) { announce("Say enable route services before using public map routing."); return }
        if (destination == null) { announce("Say take me to followed by a place, then choose the matching result."); return }
        guidanceRequested = true
        if (route == null) pendingRouteFromGps = true
        startGps()
    }
    fun resumeNavigation() {
        if (route == null && destination == null) { announce("There is no route to resume. Say take me to followed by a place."); return }
        beginNavigation()
    }
    fun startGps() {
        if (!consent || (route == null && !pendingRouteFromGps)) { announce("Choose a destination before starting GPS guidance."); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            clearCurrentInstruction()
            removeLocationUpdates(); gpsActive = false; latestFix = null
            phase = NavigationPhase.ACQUIRING_LOCATION
            announce("Android location permission is required. Use the permission dialog to allow location, or cancel navigation.")
            onLocationPermissionRequired?.invoke()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            clearCurrentInstruction()
            removeLocationUpdates(); gpsActive = false; latestFix = null
            phase = NavigationPhase.PAUSED
            announce("GPS is disabled in Android location settings. Turn location on, then say resume navigation."); return
        }
        if (gpsActive) return
        try {
            clearCurrentInstruction()
            gpsActive = true; gpsStartedAtMs = SystemClock.elapsedRealtime(); latestFix = null
            guidanceRequested = true
            phase = if (pendingRouteFromGps) NavigationPhase.ACQUIRING_LOCATION else NavigationPhase.GUIDING
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000, 1f, listener, android.os.Looper.getMainLooper())
            announce("GPS active while SIGHT is open. Waiting for an accurate location. Camera direction does not establish your walking direction.")
        } catch (_: Exception) { gpsActive = false; phase = NavigationPhase.PAUSED; announce("GPS could not start. Check Android location settings and permissions.") }
    }
    fun locationPermissionDenied() {
        clearCurrentInstruction()
        removeLocationUpdates(); gpsActive = false; latestFix = null
        pendingRouteFromGps = false; phase = NavigationPhase.PAUSED
        announce("Location permission was declined. Navigation is paused. Local camera assistance remains available.")
    }
    fun stopGps() {
        // A late routing response must not restart GPS after Activity backgrounding or pause.
        operation?.cancel(); operationVersion++; busy = false
        removeLocationUpdates()
        gpsActive = false; latestFix = null; pendingRouteFromGps = false
        clearCurrentInstruction()
        if (route != null || guidanceRequested || phase !in setOf(NavigationPhase.IDLE, NavigationPhase.ARRIVED)) phase = NavigationPhase.PAUSED
        progress = RouteProgress(RouteProgressState.GPS_UNCERTAIN, detail = "GPS progress paused. Say resume navigation to acquire a new fix.")
    }
    private fun freshAccurate(fix: GpsFix): Boolean =
        SystemClock.elapsedRealtime() - fix.capturedElapsedMs in 0..10_000 &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0.01..25.0

    /** Shared by actual LocationListener and recorded-location native integration replay. */
    internal fun acceptLocationFix(fix: GpsFix) {
        if (!gpsActive || !consent) return
        if (latestFix?.let { fix.capturedElapsedMs <= it.capturedElapsedMs } == true) return
        latestFix = fix
        if (pendingRouteFromGps && freshAccurate(fix) && !busy) {
            pendingRouteFromGps = false
            val selected = destination ?: return
            loadRoute(fix.point, selected, startGuidance = true)
        } else updateProgress(fix)
    }
    private fun updateProgress(fix: GpsFix) {
        if (!guidanceRequested || phase == NavigationPhase.ROUTING) return
        val update = progressEngine?.update(fix, SystemClock.elapsedRealtime()) ?: return
        progress = update
        if (update.state == RouteProgressState.CURRENT || update.state == RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED) {
            currentInstruction = update.instruction ?: update.detail
            currentInstructionCapturedAtMs = fix.capturedElapsedMs
            val key = update.announcementKey
            if (key != null && announcedKeys.add(key)) {
                if (announcedKeys.size > 500) announcedKeys.remove(announcedKeys.first())
                announce(currentInstruction, if (update.state == RouteProgressState.CURRENT) NavigationCueKind.TURN else NavigationCueKind.ARRIVAL,
                    fix.capturedElapsedMs, 10_000)
            }
        } else {
            clearCurrentInstruction(currentInstruction.isNotBlank() || update.state != lastProgressState)
            if (update.state != lastProgressState) announce(update.detail)
        }
        lastProgressState = update.state
        if (update.state == RouteProgressState.OFF_ROUTE && !busy &&
            SystemClock.elapsedRealtime() - lastRerouteAtMs >= 30_000 && freshAccurate(fix)) {
            lastRerouteAtMs = SystemClock.elapsedRealtime()
            announce("GPS repeatedly disagrees with the route. Requesting a new walking route; no physical detour is being assessed.")
            destination?.let { loadRoute(fix.point, it, startGuidance = true) }
        }
    }
    fun repeatInstruction() {
        val fix = latestFix
        if (!gpsActive || fix == null || !freshAccurate(fix) || progress?.state !in setOf(RouteProgressState.CURRENT, RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED)) {
            announce("Current directions need a fresh, accurate GPS position. Say route overview for the saved map instructions."); return
        }
        val text = currentInstruction.ifBlank { progress?.instruction ?: "Waiting for the next route instruction." }
        announce(text, NavigationCueKind.TURN, currentInstructionCapturedAtMs ?: fix.capturedElapsedMs, 10_000)
    }
    fun distanceToNextTurn() {
        val fix = latestFix
        val distance = progress?.distanceToNextStepMeters
        if (fix == null || !freshAccurate(fix) || progress?.state != RouteProgressState.CURRENT || distance == null) {
            announce("Distance to the next turn is uncertain. A fresh accurate location and mapped maneuver are needed."); return
        }
        announce("The next mapped maneuver is approximately ${RouteSpeech.approximateMeters(distance, fix.accuracyMeters)} metres along the route. GPS accuracy is about ${RouteSpeech.approximateMeters(fix.accuracyMeters)} metres.",
            NavigationCueKind.TURN, fix.capturedElapsedMs, 10_000)
    }
    fun confirmArrival() {
        if (gpsActive && progress?.state == RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED &&
            latestFix?.let(::freshAccurate) == true && progressEngine?.confirmArrival(SystemClock.elapsedRealtime()) == true) {
            clearCurrentInstruction()
            removeLocationUpdates(); gpsActive = false; guidanceRequested = false
            phase = NavigationPhase.ARRIVED
            progress = RouteProgress(RouteProgressState.ARRIVED, detail = "Destination confirmed by you.")
            announce("Destination confirmed by you. Navigation stopped.", NavigationCueKind.ARRIVAL)
        } else announce("Arrival needs a fresh accurate fix near the mapped endpoint and your confirmation of the actual place.")
    }
    fun cancelNavigation(announce: Boolean = true) {
        clearActiveRoute(); destination = null; origin = null; destinationResults = emptyList(); originResults = emptyList()
        spokenChoicesPending = false; routeAfterSelection = false; phase = NavigationPhase.IDLE
        message = "Navigation stopped. Camera assistance is controlled separately."
        if (announce) this.announce(message)
    }
    private fun clearActiveRoute() {
        operation?.cancel(); operationVersion++; busy = false
        removeLocationUpdates(); gpsActive = false; latestFix = null
        pendingRouteFromGps = false; guidanceRequested = false; route = null; progressEngine = null; progress = null
        clearCurrentInstruction(); lastProgressState = null; announcedKeys.clear()
    }
    fun saveDestination(name: String) {
        val selected = destination ?: run { announce("Choose a destination before saving it."); return }
        try {
            savedDestinations = destinationStore.save(name, selected)
            announce("Saved destination ${RouteSpeech.label(name)} encrypted on this phone for up to 30 days. You can say take me to saved ${RouteSpeech.label(name)}, or delete destination ${RouteSpeech.label(name)}.")
        } catch (_: Exception) { announce("Destination could not be saved securely. No save is confirmed.") }
    }
    fun recallDestination(name: String) {
        val saved = savedDestinations.firstOrNull { it.name.equals(name, true) }
            ?: run { announce("No destination is saved under that name."); return }
        if (!consent) { announce("Enable route services before routing to a saved destination."); return }
        clearActiveRoute(); destination = saved.destination
        announce("Selected saved destination ${RouteSpeech.label(saved.name)}.")
        beginNavigation()
    }
    fun deleteSavedDestination(name: String) {
        try { savedDestinations = destinationStore.delete(name); announce("Saved destination deleted if present.") }
        catch (_: Exception) { announce("Destination deletion failed. Try again in Routes.") }
    }
    fun clearSavedDestinations(): Boolean = try { destinationStore.clear(); savedDestinations = emptyList(); true }
        catch (_: Exception) { false }
    private fun announce(text: String, kind: NavigationCueKind = NavigationCueKind.STATUS,
                         capturedAt: Long? = null, maxAge: Long? = null) {
        message = text
        val cue = NavigationCue(text, kind, capturedAt, maxAge)
        onNavigationCue?.invoke(cue) ?: say(text)
    }
    private fun clearCurrentInstruction(invalidate: Boolean = true) {
        currentInstruction = ""; currentInstructionCapturedAtMs = null
        if (invalidate) onNavigationInvalidated?.invoke()
    }
    private fun removeLocationUpdates() {
        try { locationManager.removeUpdates(listener) } catch (_: SecurityException) { /* Revocation already prevents new fixes. */ }
    }
    override fun close() {
        stopGps(); operation?.cancel()
        onLocationPermissionRequired = null; onNavigationCue = null; onNavigationInvalidated = null
        scope.cancel()
    }
}

/** Numbered choices remain explicit even for a single geocoder result. */
object RouteChoices {
    fun describe(results: List<Destination>): String = if (results.isEmpty()) "No matching places returned." else
        results.take(5).mapIndexed { index, item -> "Option ${index + 1}: ${RouteSpeech.label(item.label)}." }.joinToString(" ") +
            " Say choose option followed by its number, or give a more specific destination."
}
