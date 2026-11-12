package org.sight.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import org.sight.app.navigation.NavigationController
import org.sight.app.navigation.NavigationScreen
import org.sight.core.*
import kotlin.math.min

private val Ink = Color(0xFF101716)
private val Mint = Color(0xFFADF4D2)
private val Surface = Color(0xFF1C2825)
private val Secondary = Color(0xFFBACCC4)
private val Tabs = listOf("Assist", "Routes", "Memory", "Settings", "Developer")

class MainActivity : ComponentActivity() {
    private lateinit var controller: SightController
    private lateinit var navigation: NavigationController
    private var camera: PhoneCameraSource? = null
    private lateinit var session: SightSession
    private var requestVoice: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[SightSession::class.java]
        controller = session.controller
        navigation = session.navigation
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink,
                background = Ink, surface = Surface, onSurface = Color(0xFFF1F8F3),
                onBackground = Color(0xFFF1F8F3), secondary = Secondary, secondaryContainer = Mint,
                onSecondaryContainer = Ink)) { SightApp() }
        }
    }

    private fun stopCamera() { camera?.stop(); camera = null; controller.pause() }

    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean =
        if(this::controller.isInitialized && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) true
        else super.onKeyDown(keyCode,event)

    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        if(this::controller.isInitialized && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if(!event.isCanceled) {
                if(keyCode==KeyEvent.KEYCODE_VOLUME_UP) requestVoice?.invoke()
                else { navigation.cancelNavigation(); stopCamera(); controller.perceptionStatus("Assistance stopped. Camera, microphone and route guidance are paused.") }
            }
            return true
        }
        return super.onKeyUp(keyCode,event)
    }

    // Stop observations in the background; keep the user's task and ephemeral memory
    // across Activity recreation. The retained ViewModel never owns an Activity or camera.
    override fun onStop() { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); stopCamera(); navigation.stopGps(); super.onStop() }
    override fun onDestroy() {
        navigation.onLocationPermissionRequired=null
        session.onRequestStart=null; session.onRequestStop=null; requestVoice=null
        super.onDestroy()
    }

    @Composable
    private fun SightApp() {
        var tab by rememberSaveable { mutableStateOf("Assist") }
        var command by rememberSaveable { mutableStateOf("") }
        var find by rememberSaveable { mutableStateOf("") }
        var rate by rememberSaveable { mutableFloatStateOf(1f) }
        var onboarding by rememberSaveable { mutableStateOf(!getPreferences(MODE_PRIVATE).getBoolean("introduced", false)) }
        val assistScroll = rememberScrollState()
        val routesScroll = rememberScrollState()
        val memoryScroll = rememberScrollState()
        val settingsScroll = rememberScrollState()
        val developerScroll = rememberScrollState()
        val scroll = when (tab) {
            "Routes" -> routesScroll
            "Memory" -> memoryScroll
            "Settings" -> settingsScroll
            "Developer" -> developerScroll
            else -> assistScroll
        }
        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
            else if(!granted) controller.failure("Camera permission is needed for visual assistance. Enable it in Android app settings.")
        }
        val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.startListening()
            else if(!granted) controller.perceptionStatus("Microphone permission was declined. The labelled command field and TalkBack remain available.")
        }
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it } && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) navigation.startGps()
            else if(!granted.values.any { it }) navigation.locationPermissionDenied()
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let {
                try { contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(controller.exportDiagnostics()) } }
                catch (_: Exception) { controller.failure("Could not write the diagnostic export.") }
            }
        }
        fun start() {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) controller.start()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        fun listen() {
            if(controller.listening) controller.voice.cancel()
            else if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) controller.startListening()
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        DisposableEffect(Unit) {
            session.onRequestStart={start()}; session.onRequestStop={stopCamera()}
            navigation.onLocationPermissionRequired={locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
            requestVoice={listen()}
            onDispose { session.onRequestStart=null; session.onRequestStop=null; navigation.onLocationPermissionRequired=null; requestVoice=null }
        }
        SideEffect {
            if(controller.running || navigation.gpsActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        Scaffold(containerColor = Ink, bottomBar = { DestinationBar(tab) { tab = it } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("SIGHT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        if(controller.running || navigation.gpsActive || controller.listening) TextButton(onClick={
                            navigation.cancelNavigation(); stopCamera(); controller.perceptionStatus("Assistance stopped. Camera, microphone and route guidance are paused.")
                        },modifier=Modifier.heightIn(min=48.dp)) { Text("Stop assistance") }
                    }
                    Text("${if (controller.running) "Camera active" else "Camera off"} · ${if (controller.listening) "Voice command active" else "Microphone off"}",
                        color = if (controller.running || controller.listening) Mint else Secondary,
                        modifier = Modifier.testTag("sensor-status").semantics { liveRegion = LiveRegionMode.Polite })
                    if(controller.listening) Text(controller.microphoneStatus,color=Mint,
                        modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    if(navigation.hasRouteContext || navigation.gpsActive) Text("${if(navigation.gpsActive) "Location active" else "Location paused"} · Route ${navigation.phase.name.lowercase()}",
                        color=Mint,modifier=Modifier.testTag("location-status").semantics { liveRegion=LiveRegionMode.Polite })
                }
                // This AndroidView has one stable composition slot outside every destination.
                CameraHost(showPreview = tab == "Developer")
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp)
                    .testTag("tab-content").semantics { isTraversalGroup = true }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (tab) {
                        "Assist" -> {
                            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(28.dp)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(if (controller.running) "Observing your camera view" else "Your companion is ready", color = Mint,
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(controller.lastMessage, style = MaterialTheme.typography.headlineSmall,
                                        modifier = Modifier.testTag("assistant-message"))
                                    Button(onClick = { if (controller.running) stopCamera() else start() }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                        Text(if (controller.running) "Pause camera" else "Start camera")
                                    }
                                    Text("Continue using your usual mobility aids. Descriptions can miss objects and do not assess a safe path.", color = Secondary)
                                }
                            }
                            Heading("Walking directions")
                            Text(navigation.message,modifier=Modifier.testTag("route-status").semantics { liveRegion=LiveRegionMode.Polite })
                            if(navigation.hasRouteContext) Text("Route context retained · ${navigation.phase.name.lowercase()}",color=Mint)
                            if(navigation.currentInstruction.isNotBlank()) Text(navigation.currentInstruction)
                            OutlinedTextField(navigation.query,navigation::changeQuery,label={Text("Destination name or address")},modifier=Modifier.fillMaxWidth())
                            Action("Navigate",Modifier.fillMaxWidth(),navigation.query.isNotBlank()) { controller.command("take me to ${navigation.query}") }
                            Text("Say ‘enable route services’ to consent to sharing destination queries and route coordinates with the providers. Then say ‘take me to the library’ and choose a numbered result.",color=Secondary)
                            AdaptiveActions(
                                { modifier -> Action(if(controller.listening) "Cancel microphone" else "Speak a command",modifier) { listen() } },
                                { modifier -> Action("Stop assistance",modifier) { navigation.cancelNavigation(); stopCamera() } })
                            Text("With SIGHT open: Volume up starts one spoken command; Volume down stops camera and route guidance. These keys replace volume adjustment while this screen is open. There is no wake-word listener.",color=Secondary)
                            Heading("What would help?")
                            AdaptiveActions(
                                { modifier -> Action("Explore", modifier) { controller.command("explore") } },
                                { modifier -> Action("Read text", modifier) { controller.command("read everything") } })
                            OutlinedTextField(find, { find = it }, label = { Text("Find an object") },
                                supportingText = { Text("Try chair, bottle, backpack, or another supported category. Small or obscured objects may be missed.") }, modifier = Modifier.fillMaxWidth())
                            Action("Find ${find.ifBlank { "object" }}", Modifier.fillMaxWidth(), find.isNotBlank()) { controller.command("find $find") }
                            Text("${controller.scene.goal.type.name.lowercase()} · ${controller.scene.goal.phase.name.lowercase()}", color = Mint)
                            Text(controller.scene.goal.message, color = Secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (controller.scene.goal.candidateCount > 1) Text("${controller.scene.goal.candidateCount} image candidates; identity is uncertain.", color = Secondary)
                            HorizontalDivider()
                            Heading("Ask SIGHT")
                            Text("Try ‘what is ahead’, ‘what changed’, ‘what was on my left’, or ‘remember this as my desk’.", color = Secondary)
                            OutlinedTextField(command, { command = it }, label = { Text("Your command") }, modifier = Modifier.fillMaxWidth())
                            AdaptiveActions(
                                { modifier -> Action("Ask", modifier, command.isNotBlank()) { controller.command(command); command = "" } },
                                { modifier -> Action(if (controller.listening) "Cancel microphone" else "Use voice", modifier) {
                                    listen()
                                } })
                            Text(controller.microphoneStatus, color = Secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            AdaptiveActions(
                                { modifier -> Action("Repeat", modifier) { controller.repeat() } },
                                { modifier -> Action("Stop speaking and task", modifier) { controller.stopTalking() } })
                            controller.scene.extractedText?.let { text ->
                                Heading("Captured text · this session")
                                Text(text)
                                Text("Text belongs to its captured image, not the current camera view.", color = Secondary)
                            }
                        }
                        "Memory" -> {
                            Heading("Recent visual context")
                            Text("Recent observations are temporary image memories. Their age and visibility matter; they do not establish a present physical location.", color = Secondary)
                            val history = controller.scene.world.filter { it.confirmedHistorically }.sortedBy { it.ageMs }.take(12)
                            if (history.isEmpty()) Text("No confirmed visual context yet.", color = Secondary)
                            history.forEach { world ->
                                Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${world.entity.category} · ${world.entity.direction.phrase}", fontWeight = FontWeight.Bold)
                                        Text("${world.visibility.displayWords()} · ${ageLabel(world.ageMs)}", color = Secondary)
                                    }
                                }
                            }
                            HorizontalDivider()
                            Heading("Remember, with permission")
                            Text("A saved landmark is a dated description of an image. It has no present location. No photos or video are saved. Encrypted saves expire after 30 days.", color = Secondary)
                            SwitchRow("Keep future explicit saves after this session", controller.persistentMemory) { controller.persistentMemory = it }
                            controller.scene.memory.forEach { memory -> Text("Session: ${memory.name} · frame ${memory.evidence.frameId}") }
                            if (controller.savedLandmarks.isEmpty()) Text("No persistent landmarks saved.", color = Secondary)
                            controller.savedLandmarks.forEach { item ->
                                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(item.name, fontWeight = FontWeight.Bold)
                                    Text(item.description, color = Secondary)
                                    Action("Recall ${item.name}", Modifier.fillMaxWidth()) { controller.recallSaved(item) }
                                    Action("Delete ${item.name}", Modifier.fillMaxWidth()) { controller.deleteLandmark(item.name) }
                                } }
                            }
                            Action("Delete all memories", Modifier.fillMaxWidth()) { controller.clearMemory() }
                        }
                        "Routes" -> NavigationScreen(navigation) {
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                        "Settings" -> {
                            Heading("Make room to listen")
                            SwitchRow("Mute SIGHT speech", controller.muted, controller::changeMuted)
                            SwitchRow("Vibrate on a verified find", controller.haptics) { controller.haptics = it }
                            Text("Narration speed: ${"%.1f".format(rate)}")
                            Slider(rate, { rate = it; controller.speech.rate(it) }, valueRange = .65f..1.6f,
                                modifier = Modifier.semantics { contentDescription = "Narration speed" })
                            Heading("Narration detail")
                            NarrationProfile.entries.forEach { profile ->
                                SelectionAction(profile.name.displayWords(), controller.scene.narrationProfile == profile) { controller.narrationProfile(profile) }
                            }
                            Heading("Visual processing")
                            Text("Auto uses the fast model for exploration and detailed inspection for a search. Detailed inspection found more objects in the held-out photo evaluation, but takes longer. It still often misses small objects, bags, books, and bottles.", color = Secondary)
                            DetectorMode.entries.forEach { mode -> SelectionAction(mode.name.displayWords(), controller.detectorMode == mode) { controller.chooseDetectorMode(mode) } }
                            Text("Heat, low memory, power saving, or slow inference can temporarily select Fast. Developer shows the actual model in use.", color = Secondary)
                            Text(controller.speechStatus, color = Secondary)
                            Text("Voice input needs Android 12 or newer with an on-device recognition service. SIGHT never switches to cloud recognition.", color = Secondary)
                            Heading("Your data")
                            Text("Perception, OCR, memory, and speech run locally. No raw media is retained. Diagnostics stay in memory until you choose to export them.", color = Secondary)
                            Text("Google's text reader may send device, app, and usage diagnostics to Google. It does not send your images or recognized text. For a session with no network traffic, turn on airplane mode.", color = Secondary)
                            Heading("Current limits")
                            Text("80 detector categories; no color matching, face identity, metric depth, safe-path decisions, indoor map, or crossing instructions. Accuracy can fall in low light, blur, occlusion, and unusual views.", color = Secondary)
                            Action("Review introduction", Modifier.fillMaxWidth()) { onboarding = true }
                        }
                        "Developer" -> {
                            Heading("Evidence, in view")
                            Text("Live phone source · image coordinates only", color = Secondary)
                            Text("Reliability: ${controller.scene.reliability}")
                            Text("Goal: ${controller.scene.goal.type} / ${controller.scene.goal.phase}")
                            Text("Thermal: ${controller.thermalLevel} · Battery: ${controller.batteryPercent}%")
                            Text("Speech queue: ${controller.speech.queueSize} (max 4 including active)")
                            Text(controller.diagnosticSummary)
                            Text(controller.surfaceSummary)
                            Action(if(controller.roadDiagnostics) "Stop road diagnostics" else "Run road diagnostics",Modifier.fillMaxWidth()) {
                                controller.command(if(controller.roadDiagnostics) "stop road diagnostics" else "road diagnostics")
                            }
                            controller.latestRoadTiming?.let { Text("Road model ${it.status}: native task ${it.inferenceMs} ms; age ${SystemClock.elapsedRealtime()-it.capturedAtMs} ms") }
                            Text(controller.cameraPose?.description(SystemClock.elapsedRealtime()) ?: "Camera direction is unavailable. Image axes are not walking directions.")
                            controller.timing?.let { sample ->
                                Text("Actual model: ${sample.modelId}")
                                Text("Frame ${sample.frameId} · conversion ${sample.conversionMs} ms · detection ${sample.detectionMs} ms · total ${sample.totalMs} ms")
                                Text("Model initialization ${sample.initializationMs} ms · ${sample.width} × ${sample.height} · rotation ${sample.rotationDegrees}° · geometry ${sample.geometryEpoch}", color = Secondary)
                                Text("Analyzed ${sample.analyzed} · scheduler skipped ${sample.skipped}. CameraX drops are not counted.")
                                Text("Freshness clock: ${sample.timestampBasis}", color = Secondary)
                            }
                            controller.latestOcr?.let { ocr ->
                                Text("OCR ${ocr.status} · frame ${ocr.frameId} · ${ocr.completedAtMs - ocr.submittedAtMs} ms · ${ocr.blocks} blocks")
                                Text("OCR capture basis: ${ocr.timeBasis}. Captured text is independent of current tracking.", color = Secondary)
                            }
                            Heading("Image tracks")
                            controller.scene.entities.forEach { entity ->
                                Text("#${entity.id} ${entity.category} · ${entity.direction.phrase} · score ${"%.2f".format(entity.confidence)} · ${if (entity.visible) "visible" else "remembered"} · ${entity.observations} observations")
                                Text("${entity.association.name.displayWords()} · aggregate score ${"%.2f".format(entity.aggregateConfidence)} · last observed ${ageLabel((SystemClock.elapsedRealtime() - entity.lastSeenAtMs).coerceAtLeast(0))}", color = Secondary)
                                entity.motion?.let { motion -> Text("Image velocity ${"%.2f".format(motion.xPerSecond)}, ${"%.2f".format(motion.yPerSecond)} per second. Camera and object motion are inseparable.", color = Secondary) }
                            }
                            SceneGraph(controller.scene)
                            controller.scene.sceneChanges?.let { changes ->
                                Heading("Observation changes")
                                Text("New: ${changes.newlyObserved.joinToString { it.category }.ifBlank { "none" }}")
                                Text("No longer observed: ${changes.noLongerObserved.joinToString { it.category }.ifBlank { "none" }}")
                                Text("Image side changes: ${changes.changedImageSide.size} · identity comparison ${if (changes.identitiesComparable) "available" else "unavailable"}", color = Secondary)
                            }
                            Heading("Decision trace")
                            controller.decisions.takeLast(15).reversed().forEach { Text("${it.code}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                            Text("Export includes scene text and narration. Review before sharing.", color = Secondary)
                            Action("Export diagnostic JSON", Modifier.fillMaxWidth()) { export.launch("sight-diagnostics.json") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (onboarding) AlertDialog(onDismissRequest = {}, title = { Text("Meet SIGHT") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SIGHT describes what your rear camera detects and reads text on your phone. Point the camera while stationary to get started.")
                Text("It can miss objects and cannot assess safe travel, crossings, clearance, or distance. Keep using your usual mobility aids.")
                Text("Camera and microphone start only when you ask. Photos and video are never saved. You choose whether explicit landmarks persist.")
                Text("For spoken walking directions, explicitly enable route services, say a destination, choose a numbered match, and grant location permission. Routing data does not establish accessible or physically passable travel.")
                Text("While SIGHT is open, Volume up starts one voice command and Volume down stops assistance. A short vibration signals microphone readiness on devices with a vibrator. Android's on-device recognition is preferred; bundled offline English recognition supports Android 8 and newer. The labelled command control and TalkBack are alternatives.")
                Text("Text reading runs on your phone. Google's text reader may send usage diagnostics to Google, without your images or recognized text. Airplane mode permits offline use.")
            } }, confirmButton = { TextButton(onClick = {
                onboarding = false; getPreferences(MODE_PRIVATE).edit().putBoolean("introduced", true).apply()
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Continue") } })
    }

    @Composable
    private fun CameraHost(showPreview: Boolean) {
        val visible = controller.running && showPreview
        Box(Modifier.fillMaxWidth().height(if (visible) 220.dp else 1.dp).testTag("camera-host")) {
            if (controller.running) key(controller.cameraGeneration) {
                AndroidView(factory = { ctx ->
                    camera?.stop()
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        lateinit var ownedSource: PhoneCameraSource
                        ownedSource = PhoneCameraSource(this@MainActivity, this@MainActivity, this,
                            { this@MainActivity.controller.goalType }, { this@MainActivity.controller.thermalLevel },
                            { frame, timing -> if (camera === ownedSource) this@MainActivity.controller.onFrame(frame, timing) },
                            { message -> if (camera === ownedSource) this@MainActivity.controller.failure(message) },
                            { message -> if (camera === ownedSource) this@MainActivity.controller.perceptionStatus(message) },
                            requestedProfile = this@MainActivity.controller::requestedProfile, textReader = this@MainActivity.controller.textReader,
                            onTiming = { timing -> if (camera === ownedSource) this@MainActivity.controller.onTiming(timing) },
                            onFeedback = { message, evidence -> if (camera === ownedSource) this@MainActivity.controller.frameFeedback(message, evidence) },
                            constrained = { this@MainActivity.controller.resourceConstrained },
                            roadReader = this@MainActivity.controller.roadReader, roadEnabled = this@MainActivity.controller::roadObservationEnabled,
                            perceptionSuspended = { this@MainActivity.controller.listening })
                        camera = ownedSource
                        tag = ownedSource
                        ownedSource.start()
                    }
                }, update = { preview ->
                    preview.contentDescription = if (showPreview) "Live rear camera preview. Detection outlines use image coordinates." else null
                    preview.importantForAccessibility = if (showPreview) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
                    val ownedSource = view.tag as? PhoneCameraSource
                    ownedSource?.stop()
                    if (camera === ownedSource) camera = null
                })
                if (showPreview) DetectionOverlay(controller.scene, controller.timing)
            }
        }
    }
}

@Composable
private fun DestinationBar(selected: String, onSelect: (String) -> Unit) {
    if (LocalDensity.current.fontScale > 1.35f) {
        Surface(color = Surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp)) {
                Tabs.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { name ->
                            TextButton(onClick = { onSelect(name) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                                .semantics { this.selected = selected == name },
                                colors = ButtonDefaults.textButtonColors(contentColor = if (selected == name) Mint else Secondary)) { Text(name) }
                        }
                    }
                }
            }
        }
    } else NavigationBar(containerColor = Surface) {
        Tabs.forEachIndexed { index, name ->
            NavigationBarItem(selected = selected == name, onClick = { onSelect(name) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink, selectedTextColor = Mint,
                    indicatorColor = Mint, unselectedIconColor = Secondary, unselectedTextColor = Secondary),
                icon = { Text(listOf("A", "R", "M", "S", "D")[index], modifier = Modifier.clearAndSetSemantics {}) }, label = { Text(name) })
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })

@Composable
private fun AdaptiveActions(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalDensity.current.fontScale > 1.35f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
}

@Composable
private fun Action(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) { Text(label) }
}

@Composable
private fun SelectionAction(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { this.selected = selected },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected) Mint else Secondary)) { Text("$label${if (selected) " · selected" else ""}") }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

private fun String.displayWords() = lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
private fun VisibilityStatus.displayWords() = name.displayWords()
private fun ageLabel(ageMs: Long) = if (ageMs < 1_000) "less than a second ago" else "${ageMs / 1_000} seconds ago"

@Composable
private fun DetectionOverlay(scene: EngineUpdate, sample: StageTiming?) {
    if (sample == null || sample.width <= 0 || sample.height <= 0) return
    val visible = scene.entities.filter { it.visible && it.evidence.geometryEpoch == sample.geometryEpoch && it.evidence.modelId == sample.modelId }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val scale = min(size.width / sample.width, size.height / sample.height)
        val imageWidth = sample.width * scale
        val imageHeight = sample.height * scale
        val origin = Offset((size.width - imageWidth) / 2, (size.height - imageHeight) / 2)
        visible.forEach { entity ->
            drawRect(Mint, origin + Offset(entity.box.left * imageWidth, entity.box.top * imageHeight),
                Size((entity.box.right - entity.box.left) * imageWidth, (entity.box.bottom - entity.box.top) * imageHeight), style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun SceneGraph(scene: EngineUpdate) {
    val visible = scene.entities.filter { it.visible }.take(20)
    val description = visible.joinToString { "${it.category} ${it.direction.phrase}" }
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Surface, RoundedCornerShape(16.dp))
        .semantics { contentDescription = "Camera image scene graph. ${description.ifBlank { "No current image tracks" }}. Relationships use image coordinates." }) {
        val ids = visible.associateBy { it.id }
        scene.relationships.take(50).forEach { relationship ->
            val a = ids[relationship.subjectId] ?: return@forEach
            val b = ids[relationship.objectId] ?: return@forEach
            drawLine(Secondary.copy(alpha = .3f), Offset(a.box.centerX * size.width, a.box.centerY * size.height),
                Offset(b.box.centerX * size.width, b.box.centerY * size.height), 1.dp.toPx())
        }
        visible.forEach { entity ->
            drawRect(Mint.copy(alpha = .6f), Offset(entity.box.left * size.width, entity.box.top * size.height),
                Size((entity.box.right - entity.box.left) * size.width, (entity.box.bottom - entity.box.top) * size.height), style = Stroke(1.dp.toPx()))
            drawCircle(Mint, 7.dp.toPx(), Offset(entity.box.centerX * size.width, entity.box.centerY * size.height))
        }
    }
}
