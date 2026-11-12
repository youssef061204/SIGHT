package org.sight.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
fun NavigationScreen(controller: NavigationController, requestLocation: () -> Unit) {
    var startQuery by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf("") }
    var showOtherStart by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Walking navigation", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Say take me to followed by a place or address. SIGHT reads numbered matches; say choose option one, two, three, four or five. Android location permission is separate.")
        Text("Public map services receive your search text, an optional recent location to bias search, and selected route coordinates. Photon and FOSSGIS routing services may log requests. Camera images are never sent for routing.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enable public route services for this session", Modifier.weight(1f))
            Switch(controller.consent, controller::changeConsent, modifier = Modifier.semantics { contentDescription = "Enable public route services" })
        }
        Text("Routing provider", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Request additional mapped mobility details", Modifier.weight(1f))
            Switch(controller.providerPreference == RouteProviderPreference.MAPPED_DETAILS,
                { controller.changeProviderPreference(if(it) RouteProviderPreference.MAPPED_DETAILS else RouteProviderPreference.STANDARD) },
                modifier = Modifier.semantics { contentDescription = "Request additional mapped mobility details" })
        }
        Text(if(controller.providerPreference == RouteProviderPreference.MAPPED_DETAILS)
            "FOSSGIS Valhalla pedestrian profile: additional mapped instructions requested. This is not a step-free guarantee or camera verification. No silent provider fallback."
        else "FOSSGIS OSRM foot: ordinary mapped walking route. Say enable mapped route details to request Valhalla's additional instructions.")
        Text("Route status: ${controller.phase.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.titleMedium)
        Text(controller.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (controller.currentInstruction.isNotBlank()) {
            Text(controller.currentInstruction, style = MaterialTheme.typography.titleLarge)
        }
        Text("Routes may include unsuitable paths or steps. Sidewalk access, crossing conditions and accessibility are unverified. These are mapped directions; keep using your usual mobility methods.")
        OutlinedTextField(controller.query, controller::changeQuery, label = { Text("Destination name or address") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { controller.search(controller.query, false) }, enabled = controller.consent && !controller.busy && controller.query.length in 2..160,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(if (controller.busy) "Contacting map service" else "Search destinations") }
        controller.destinationResults.forEachIndexed { index, result ->
            OutlinedButton(onClick = { controller.select(result, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                selected = controller.destination?.id == result.id
            }) {
                Text("Option ${index + 1}. ${if (controller.destination?.id == result.id) "Selected: " else ""}${result.label}")
            }
        }
        controller.destination?.let { selected ->
            Text("Selected destination: ${selected.label}")
            Button(onClick = {
                controller.beginNavigation()
                if (controller.phase == NavigationPhase.ACQUIRING_LOCATION && controller.onLocationPermissionRequired == null) requestLocation()
            }, enabled = controller.consent && !controller.busy && !controller.guidanceActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(if (controller.phase == NavigationPhase.PAUSED) "Resume spoken navigation" else "Start spoken navigation from current GPS")
            }
        }
        if (controller.phase == NavigationPhase.ACQUIRING_LOCATION && !controller.gpsActive) {
            OutlinedButton(onClick = requestLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Review Android location permission") }
        }
        if (controller.guidanceActive || controller.hasRouteContext) {
            if (controller.guidanceActive) {
                OutlinedButton(onClick = controller::stopGps, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Pause navigation") }
            }
            OutlinedButton(onClick = controller::repeatInstruction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Repeat current direction") }
            OutlinedButton(onClick = controller::distanceToNextTurn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Hear distance to next turn") }
            OutlinedButton(onClick = controller::rehearse, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Hear route overview") }
            OutlinedButton(onClick = { controller.requestRoute(fromGps = true) }, enabled = controller.gpsActive && !controller.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Recalculate from current GPS") }
        }
        if (controller.destination != null || controller.phase != NavigationPhase.IDLE) {
            Button(onClick = { controller.cancelNavigation() }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Stop navigation") }
        }
        controller.progress?.let { progress ->
            Text(progress.detail)
            progress.remainingMeters?.let { Text("Mapped route remaining: approximately ${RouteSpeech.approximateMeters(it, progress.fixAccuracyMeters ?: 0.0)} metres") }
            if (progress.state == RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED) {
                Button(onClick = controller::confirmArrival, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("I confirm this is the destination") }
            }
        }
        OutlinedButton(onClick = { showOtherStart = !showOtherStart }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (showOtherStart) "Hide route rehearsal planning" else "Plan a route from another starting place")
        }
        if (showOtherStart) {
            OutlinedTextField(startQuery, { startQuery = it }, label = { Text("Starting place for route rehearsal") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { controller.search(startQuery, true) }, enabled = controller.consent && !controller.busy && startQuery.length in 2..160,
                modifier = Modifier.heightIn(min = 56.dp)) { Text("Search starting places") }
            controller.originResults.forEachIndexed { index, result ->
                OutlinedButton(onClick = { controller.select(result, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Starting option ${index + 1}. ${if (controller.origin?.id == result.id) "Selected: " else ""}${result.label}")
                }
            }
            Button(onClick = { controller.requestRoute() }, enabled = controller.consent && !controller.busy && controller.origin != null && controller.destination != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Load walking route for rehearsal") }
        }
        controller.destination?.let {
            Text("Save this destination", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Only explicit saves store coordinates. Saves are encrypted on this phone, limited to 20 destinations, and expire after 30 days.")
            OutlinedTextField(saveName, { saveName = it.take(60) }, label = { Text("Saved destination name") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { controller.saveDestination(saveName) }, enabled = saveName.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save destination encrypted on this phone") }
        }
        if (controller.savedDestinations.isNotEmpty()) {
            Text("Saved destinations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            controller.savedDestinations.forEach { saved ->
                Text("${saved.name}: ${saved.destination.label}")
                OutlinedButton(onClick = { controller.recallDestination(saved.name) }, enabled = controller.consent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Navigate to saved ${saved.name}") }
                OutlinedButton(onClick = { controller.deleteSavedDestination(saved.name) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Delete saved destination ${saved.name}") }
            }
        }
        controller.route?.let { route ->
            Text("Mapped route steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            route.steps.forEach { Text("${it.index + 1}. ${it.description}") }
        }
        Text(if(controller.providerPreference == RouteProviderPreference.MAPPED_DETAILS)
            "© OpenStreetMap contributors. Routing: FOSSGIS Valhalla. Search: Photon / komoot."
        else PublicWalkingRouteProvider.ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
        Text("Map corrections: ${PublicWalkingRouteProvider.MAP_FIX_URL}", style = MaterialTheme.typography.bodySmall)
    }
}
