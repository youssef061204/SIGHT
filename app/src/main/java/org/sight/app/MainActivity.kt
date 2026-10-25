package org.sight.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.sight.core.*
import org.sight.app.navigation.NavigationController
import org.sight.app.navigation.NavigationScreen

private val Ink = Color(0xFF101716)
private val Mint = Color(0xFFADF4D2)
private val Surface = Color(0xFF1C2825)
private val Secondary = Color(0xFFBACCC4)

class MainActivity : ComponentActivity() {
    private lateinit var controller: SightController
    private lateinit var navigation: NavigationController
    private var camera: PhoneCameraSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SightController(applicationContext)
        navigation = NavigationController(applicationContext, controller::speakProviderText)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink,
                background = Ink, surface = Surface, onSurface = Color(0xFFF1F8F3),
                onBackground = Color(0xFFF1F8F3), secondary = Secondary)) {
                SightApp()
            }
        }
    }

    private fun stopCamera() { camera?.stop(); camera = null; controller.pause() }
    override fun onStop() { stopCamera(); navigation.stopGps(); super.onStop() }
    override fun onDestroy() { navigation.close(); controller.close(); super.onDestroy() }

    @Composable
    private fun SightApp() {
        var tab by remember { mutableStateOf("Assist") }
        var command by remember { mutableStateOf("") }
        var find by remember { mutableStateOf("") }
        var onboarding by remember { mutableStateOf(!getPreferences(MODE_PRIVATE).getBoolean("introduced", false)) }
        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.start() else controller.failure("Camera permission is needed for visual assistance. Enable it in Android app settings.")
        }
        val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.startListening() else controller.command("Microphone permission was declined")
        }
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) navigation.startGps()
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

        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Surface) {
                listOf("Assist", "Routes", "Memory", "Settings", "Developer").forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == name, onClick = { tab = name },
                        icon = { Text(listOf("◉", "↗", "◇", "≡", "⌘")[index], modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(name) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("SIGHT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Text("ON DEVICE", color = Mint, style = MaterialTheme.typography.labelMedium)
                }
                Text("A little more context. On your terms.", color = Secondary)

                if (tab == "Assist") {
                    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Canvas(Modifier.size(48.dp).clearAndSetSemantics {}) {
                                    drawCircle(Mint, style = Stroke(2.dp.toPx()))
                                    drawCircle(Mint, radius = size.minDimension * .15f)
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(if (controller.running) "Camera active" else "Your companion is ready", fontWeight = FontWeight.SemiBold)
                                    Text(if (controller.running) controller.scene.reliability.name.replace('_', ' ').lowercase()
                                         else "Camera off", color = Secondary)
                                    Text(if (controller.listening) "Microphone active" else "Microphone off", color = Secondary)
                                }
                            }
                            Text(controller.lastMessage, style = MaterialTheme.typography.headlineSmall)
                            Button(onClick = { if (controller.running) stopCamera() else start() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                                Text(if (controller.running) "Pause camera" else "Start camera")
                            }
                            Text("Descriptions of the camera view. Continue using your usual mobility aids.",
                                style = MaterialTheme.typography.bodyMedium, color = Secondary)
                        }
                    }
                    Text("What would help?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Explore", Modifier.weight(1f)) { controller.command("explore") }
                        Action("Read text", Modifier.weight(1f)) { controller.command("read everything") }
                    }
                    OutlinedTextField(find, { find = it }, label = { Text("Find an object") },
                        supportingText = { Text("Try chair, bottle, backpack, or another COCO class") }, modifier = Modifier.fillMaxWidth())
                    Action("Find ${find.ifBlank { "object" }}", enabled = find.isNotBlank()) { controller.command("find $find") }
                    Text("${controller.scene.goal.type.name.lowercase()} · ${controller.scene.goal.phase.name.lowercase()}", color = Mint)
                    Text(controller.scene.goal.message, color = Secondary)
                    HorizontalDivider()
                    Text("Ask SIGHT", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("Try ‘what is ahead’, ‘what changed’, ‘remember this as my desk’, or ‘where did you last see the chair’.", color = Secondary)
                    OutlinedTextField(command, { command = it }, label = { Text("Your command") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Ask", Modifier.weight(1f), command.isNotBlank()) { controller.command(command); command = "" }
                        Action(if (controller.listening) "Cancel microphone" else "Use voice", Modifier.weight(1f)) {
                            if (controller.listening) controller.voice.cancel()
                            else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.startListening()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    Text(controller.microphoneStatus, color = Secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Repeat", Modifier.weight(1f)) { controller.repeat() }
                        Action("Stop speaking and task", Modifier.weight(1f)) { controller.stopTalking() }
                    }
                    controller.scene.extractedText?.let { text ->
                        Text("Extracted text · this session", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        Text(text)
                    }
                }

                if (tab == "Memory") {
                    Text("Remember, with permission", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text("A saved landmark is a dated description of an image. It has no present location. No photos or video are saved. Encrypted saves expire after 30 days.", color = Secondary)
                    SwitchRow("Keep future explicit saves after this session", controller.persistentMemory) { controller.persistentMemory = it }
                    controller.scene.memory.forEach { memory ->
                        Text("Session: ${memory.name} · frame ${memory.evidence.frameId}")
                    }
                    if (controller.savedLandmarks.isEmpty()) Text("No persistent landmarks saved.", color = Secondary)
                    controller.savedLandmarks.forEach { item ->
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(item.description, color = Secondary)
                            Action("Recall ${item.name}") { controller.recallSaved(item) }
                            Action("Delete ${item.name}") { controller.deleteLandmark(item.name) }
                        } }
                    }
                    Action("Delete all memories") { controller.clearMemory() }
                }
                if (tab == "Routes") NavigationScreen(navigation) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                if (tab == "Settings") {
                    Text("Make room to listen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    SwitchRow("Mute SIGHT speech", controller.muted, controller::changeMuted)
                    SwitchRow("Vibrate on a verified find", controller.haptics) { controller.haptics = it }
                    var rate by remember { mutableFloatStateOf(1f) }
                    Text("Narration speed: ${"%.1f".format(rate)}")
                    Slider(rate, { rate = it; controller.speech.rate(it) }, valueRange = .65f..1.6f,
                        modifier = Modifier.semantics { contentDescription = "Narration speed" })
                    Text("Narration detail", modifier = Modifier.semantics { heading() })
                    NarrationProfile.entries.forEach { profile ->
                        Action("${profile.name.lowercase()}${if (controller.scene.narrationProfile == profile) " · selected" else ""}") {
                            controller.narrationProfile(profile)
                        }
                    }
                    Text(controller.speechStatus, color = Secondary)
                    Text("Voice input needs Android 12 or newer with an on-device recognition service. SIGHT never switches to cloud recognition.", color = Secondary)
                    Text("Baseline perception, OCR, memory, and speech run locally. No raw media is retained. Diagnostics stay in memory until you choose to export them.", color = Secondary)
                    Text("Google's text reader may send device, app, and usage diagnostics to Google. It does not send your images or recognized text. For a session with no network traffic, turn on airplane mode.", color = Secondary)
                    Text("Current limits", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("80 detector categories; no color matching, face identity, metric depth, safe-path decisions, indoor map, or crossing instructions. Accuracy can fall in low light, blur, occlusion, and unusual views.", color = Secondary)
                    Action("Review introduction") { onboarding = true }
                }
                if (tab == "Developer") {
                    Text("Evidence, in view", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text("Live phone source · image coordinates only", color = Secondary)
                }

                // Always mount the camera while active so changing tabs cannot silently stop inference.
                if (controller.running) {
                    AndroidView(factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            contentDescription = "Live rear camera preview"
                            lateinit var ownedSource: PhoneCameraSource
                            ownedSource = PhoneCameraSource(this@MainActivity, this@MainActivity, this,
                                { this@MainActivity.controller.goalType }, { this@MainActivity.controller.thermalLevel }, this@MainActivity.controller::onFrame,
                                { message -> if (camera === ownedSource) this@MainActivity.controller.failure(message) },
                                { message -> if (camera === ownedSource) this@MainActivity.controller.perceptionStatus(message) })
                            camera = ownedSource
                            tag = ownedSource
                            ownedSource.start()
                        }
                    }, modifier = Modifier.fillMaxWidth().height(if (tab == "Developer") 300.dp else 1.dp),
                        onRelease = { view ->
                            val ownedSource = view.tag as? PhoneCameraSource
                            ownedSource?.stop()
                            if (camera === ownedSource) camera = null
                        })
                }

                if (tab == "Developer") {
                    val sample = controller.timing
                    Text("Reliability: ${controller.scene.reliability}")
                    Text("Goal: ${controller.scene.goal.type} / ${controller.scene.goal.phase}")
                    Text("Thermal: ${controller.thermalLevel} · Battery: ${controller.batteryPercent}%")
                    Text("Speech queue: ${controller.speech.queueSize} (max 4 including active)")
                    sample?.let {
                        Text("Frame ${it.frameId} · conversion ${it.conversionMs} ms · detection ${it.detectionMs} ms · OCR ${it.ocrMs} ms · total ${it.totalMs} ms")
                        Text("Analyzed ${it.analyzed} · scheduler skipped ${it.skipped}. CameraX drops are not counted.")
                        Text("Freshness clock: ${it.timestampBasis}", color = Secondary)
                    }
                    Text("Image tracks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    controller.scene.entities.forEach { entity ->
                        Text("#${entity.id} ${entity.category} · ${entity.direction.phrase} · ${"%.2f".format(entity.confidence)} · ${if (entity.visible) "visible" else "remembered"} · ${entity.observations} observations")
                    }
                    SceneGraph(controller.scene)
                    Text("Decision trace", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    controller.decisions.takeLast(15).reversed().forEach { Text("${it.code}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    Text("Export includes scene text and narration. Review before sharing.", color = Secondary)
                    Action("Export diagnostic JSON") { export.launch("sight-diagnostics.json") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (onboarding) AlertDialog(onDismissRequest = {}, title = { Text("Meet SIGHT") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SIGHT describes what your rear camera detects and reads text on your phone. Point the camera while stationary to get started.")
                Text("It can miss objects and cannot assess safe travel, crossings, clearance, or distance. Keep using your usual mobility aids.")
                Text("Camera and microphone start only when you ask. Photos and video are never saved. You choose whether explicit landmarks persist.")
                Text("Text reading runs on your phone. Google's text reader may send usage diagnostics to Google, without your images or recognized text. Airplane mode permits offline use.")
            } }, confirmButton = { TextButton(onClick = {
                onboarding = false; getPreferences(MODE_PRIVATE).edit().putBoolean("introduced", true).apply()
            }) { Text("Continue") } })
    }
}

@Composable
private fun Action(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) { Text(label) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SceneGraph(scene: EngineUpdate) {
    val visible = scene.entities.filter { it.visible }.take(20)
    val description = visible.joinToString { "${it.category} ${it.direction.phrase}" }
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Surface, RoundedCornerShape(16.dp))
        .semantics { contentDescription = "Camera image scene graph. $description. Relationships use image coordinates." }) {
        val ids = visible.associateBy { it.id }
        scene.relationships.take(50).forEach { relationship ->
            val a = ids[relationship.subjectId] ?: return@forEach
            val b = ids[relationship.objectId] ?: return@forEach
            drawLine(Secondary.copy(alpha = .3f), Offset(a.box.centerX*size.width, a.box.centerY*size.height),
                Offset(b.box.centerX*size.width, b.box.centerY*size.height), 1.dp.toPx())
        }
        visible.forEach { entity ->
            drawRect(Mint.copy(alpha = .6f), Offset(entity.box.left*size.width, entity.box.top*size.height),
                Size((entity.box.right-entity.box.left)*size.width, (entity.box.bottom-entity.box.top)*size.height),
                style = Stroke(1.dp.toPx()))
            drawCircle(Mint, 7.dp.toPx(), Offset(entity.box.centerX*size.width, entity.box.centerY*size.height))
        }
    }
}
