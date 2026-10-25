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
    var destinationQuery by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Get to know a route", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("Optional public map services. Enabling sends your place searches to Photon and selected route coordinates to FOSSGIS OSRM. These services may keep access logs. No camera images are shared.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enable network route services for this session", Modifier.weight(1f))
            Switch(controller.consent, controller::changeConsent, modifier = Modifier.semantics { contentDescription = "Enable public route services" })
        }
        Text("Routes can include indoor concourses, stairs or unsuitable paths. Sidewalk accessibility, crossings and current conditions are unverified. Provider steps describe map data; use your usual mobility methods.")
        OutlinedTextField(startQuery, { startQuery = it }, label = { Text("Starting place") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { controller.search(startQuery, true) }, enabled = controller.consent && !controller.busy && startQuery.length >= 2,
            modifier = Modifier.heightIn(min = 56.dp)) { Text("Search starting places") }
        controller.originResults.forEach { result ->
            OutlinedButton(onClick = { controller.select(result, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("${if (controller.origin?.id == result.id) "Selected: " else ""}${result.label}")
            }
        }
        OutlinedTextField(destinationQuery, { destinationQuery = it }, label = { Text("Destination") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { controller.search(destinationQuery, false) }, enabled = controller.consent && !controller.busy && destinationQuery.length >= 2,
            modifier = Modifier.heightIn(min = 56.dp)) { Text("Search destinations") }
        controller.destinationResults.forEach { result ->
            OutlinedButton(onClick = { controller.select(result, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("${if (controller.destination?.id == result.id) "Selected: " else ""}${result.label}")
            }
        }
        Button(onClick = { controller.requestRoute() }, enabled = controller.consent && !controller.busy && controller.origin != null && controller.destination != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(if (controller.busy) "Contacting map service" else "Request walking route") }
        Text(controller.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        controller.route?.let { route ->
            Text(route.rehearsal())
            OutlinedButton(onClick = controller::rehearse, modifier = Modifier.heightIn(min = 56.dp)) { Text("Hear route rehearsal") }
            OutlinedButton(onClick = { if (controller.gpsActive) controller.stopGps() else requestLocation() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (controller.gpsActive) "Pause GPS progress" else "Enable foreground GPS progress")
            }
            controller.progress?.let { progress ->
                Text(progress.state.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.titleMedium)
                Text(progress.detail)
                progress.remainingMeters?.let { Text("Route-data estimate remaining: ${it.toInt()} meters") }
                progress.nextStep?.let { Text(it.description) }
                if (progress.state == RouteProgressState.OFF_ROUTE) {
                    OutlinedButton(onClick = { controller.requestRoute(fromGps = true) }, enabled = !controller.busy,
                        modifier = Modifier.heightIn(min = 56.dp)) { Text("Recalculate from current GPS") }
                }
                if (progress.state == RouteProgressState.ARRIVAL_CONFIRMATION_REQUIRED) {
                    Button(onClick = controller::confirmArrival, modifier = Modifier.heightIn(min = 64.dp)) { Text("I confirm this is the destination") }
                }
            }
            Text("Provider steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            route.steps.forEach { Text("${it.index + 1}. ${it.description}") }
        }
        Text(PublicWalkingRouteProvider.ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
        Text("Map corrections: ${PublicWalkingRouteProvider.MAP_FIX_URL}", style = MaterialTheme.typography.bodySmall)
    }
}
