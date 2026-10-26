package org.sight.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.sight.core.*
import java.text.DateFormat
import java.util.Date
import org.sight.perception.DetectorProfile

enum class DetectorMode { AUTO, FAST, INSPECT }

class SightController(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = SightEngine()
    var scene by mutableStateOf(engine.setInputAvailable(false, now())); private set
    var running by mutableStateOf(false); private set
    var lastMessage by mutableStateOf("Ready when you are. Start the camera to explore."); private set
    var speechStatus by mutableStateOf("Starting local speech"); private set
    var microphoneStatus by mutableStateOf("Microphone off"); private set
    var listening by mutableStateOf(false); private set
    var timing by mutableStateOf<StageTiming?>(null); private set
    var decisions by mutableStateOf<List<DecisionTrace>>(emptyList()); private set
    var savedLandmarks by mutableStateOf<List<SavedLandmark>>(emptyList()); private set
    var persistentMemory by mutableStateOf(false)
    var haptics by mutableStateOf(false)
    var muted by mutableStateOf(false); private set
    var cameraGeneration by mutableStateOf(0L); private set
    var detectorMode by mutableStateOf(DetectorMode.AUTO); private set
    var latestOcr by mutableStateOf<OcrTiming?>(null); private set
    var diagnosticSummary by mutableStateOf("Start the camera to collect runtime samples."); private set
    var externalCommand: (String) -> Boolean = { false }
    @Volatile private var inspectUntilMs = 0L
    @Volatile var resourceConstrained = false; private set
    private var activeSource: String? = null
    private var lastRuntimeSampleMs = 0L
    private var pssKb = 0L
    private val ocrSamples = ArrayDeque<OcrTiming>()
    private val speechDecisions = ArrayDeque<JSONObject>()
    val textReader = AsyncTextReader(context) { evidence, text, sample ->
        scope.launch {
            if (ocrSamples.size >= 300) ocrSamples.removeFirst()
            ocrSamples.addLast(sample); latestOcr = sample
            if (running && evidence.sourceId == activeSource) {
                if (sample.status == "failed") frameFeedback("The text reader could not finish. Try reading again.", evidence)
                else accept(engine.onTextObservation(evidence, text, now()))
            }
        }
    }
    private var seenMemoryIds = emptySet<Long>()
    private val stageSamples = ArrayDeque<StageTiming>()
    private val speechSamples = ArrayDeque<JSONObject>()
    private val landmarkStore = LandmarkStore(context)
    val speech = SpeechOutput(context, engine::isAnnouncementValid, { event, at ->
        // OCR is a fixed captured-text reading, admitted while fresh. Live image claims must stay supported.
        if (event.kind !in setOf(AnnouncementKind.LIVE_SCENE, AnnouncementKind.FIND_RESULT)) true
        else running && event.evidence?.let { evidence -> scene.liveEvidence?.let { live ->
            evidence.sourceId == live.sourceId && evidence.modelId == live.modelId && evidence.geometryEpoch == live.geometryEpoch
        } } == true && event.grounding.all { grounding -> scene.entities.any {
            it.id == grounding.trackId && it.visible && it.category == grounding.category &&
                it.direction == grounding.direction && at-it.lastSeenAtMs in 0..1_500 &&
                it.confidence >= .65f && it.aggregateConfidence >= .65f
        } } && event.requiredRelationships.all { required -> scene.relationships.any {
            it.subjectId == required.subjectId && it.objectId == required.objectId && it.kind == required.kind
        } }
    },
        { speechStatus = it }, { event, dispatchAt ->
            if (speechSamples.size == 200) speechSamples.removeFirst()
            speechSamples.addLast(JSONObject().put("text", event.text).put("kind", event.kind.name)
                .put("ttsRequestAtMs", dispatchAt).put("frameId", event.evidence?.frameId)
                .put("observationToTtsRequestMs", event.evidence?.let { dispatchAt-it.capturedAtMs })
                .put("timeBasis", event.evidence?.timeBasis?.name))
            if (haptics && event.kind == AnnouncementKind.FIND_RESULT) vibrate()
        }, { event, startedAt ->
            speechSamples.lastOrNull { it.optLong("frameId", -1) == (event.evidence?.frameId ?: -2) && it.optString("text") == event.text }
                ?.put("ttsEngineStartAtMs", startedAt)
                ?.put("observationToTtsEngineStartMs", event.evidence?.let { startedAt - it.capturedAtMs })
        }, { decision ->
            if (speechDecisions.size >= 200) speechDecisions.removeFirst()
            speechDecisions.addLast(JSONObject().put("code", decision.code).put("atMs", decision.atMs)
                .put("text", decision.event?.text))
        })
    val voice = OfflineVoiceInput(context, ::command) { active, state ->
        listening = active; microphoneStatus = state
    }
    @Volatile var goalType = GoalType.EXPLORE; private set
    @Volatile var thermalLevel = ThermalLevel.NORMAL; private set

    init {
        try { savedLandmarks = landmarkStore.load() }
        catch (_: Exception) { lastMessage = "Saved landmark storage could not be opened. You can delete it in Memory." }
        scope.launch {
            while (isActive) {
                thermalLevel = deviceThermal()
                resourceConstrained = context.getSystemService(PowerManager::class.java).isPowerSaveMode ||
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                        .let { manager -> android.app.ActivityManager.MemoryInfo().also(manager::getMemoryInfo).lowMemory }
                if (running) accept(engine.tick(now()))
                if (now() - lastRuntimeSampleMs >= 5_000) {
                    lastRuntimeSampleMs = now(); pssKb = android.os.Debug.getPss(); updateRuntimeSummary()
                }
                speech.tick(now())
                delay(300)
            }
        }
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun deviceThermal(): ThermalLevel {
        val power = context.getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
        return when {
            status >= 4 -> ThermalLevel.CRITICAL
            status >= 3 -> ThermalLevel.HOT
            status >= 1 || power.isPowerSaveMode -> ThermalLevel.WARM
            else -> ThermalLevel.NORMAL
        }
    }
    val batteryPercent: Int get() = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    fun start() {
        if (running) return
        activeSource = null; stageSamples.clear(); timing = null; cameraGeneration++
        running = true; accept(engine.setInputAvailable(true, now()))
        lastMessage = "Camera starting. Waiting for fresh visual evidence."
    }
    fun pause() {
        running = false; activeSource = null; voice.cancel(); speech.stop()
        accept(engine.setInputAvailable(false, now()))
        lastMessage = "Camera paused. Visual observations are unavailable."
    }
    fun failure(message: String) {
        running = false; speech.stop(); accept(engine.setInputAvailable(false, now())); lastMessage = message
    }
    fun perceptionStatus(message: String) {
        lastMessage = message
        speech.offer(Announcement(message, SpeechPriority.TASK, kind = AnnouncementKind.STATUS))
    }
    fun onFrame(frame: FrameObservation, sample: StageTiming) {
        if (!running) return
        activeSource = frame.evidence.sourceId
        onTiming(sample)
        accept(engine.onFrame(frame, now()))
        if (lastMessage == "Camera starting. Waiting for fresh visual evidence." && scene.liveEvidence == frame.evidence)
            lastMessage = "Camera is receiving fresh frames. Choose Explore, Find, or Read text."
    }
    fun onTiming(sample: StageTiming) {
        if (!running) return
        timing = sample
        if (stageSamples.size >= 300) stageSamples.removeFirst()
        stageSamples.addLast(sample)
    }
    fun frameFeedback(message: String, evidence: Evidence) {
        if (!running || evidence.sourceId != activeSource || now() - evidence.capturedAtMs !in 0..1_500) return
        lastMessage = message
        if (!listening) speech.offer(Announcement(message, SpeechPriority.TASK, evidence,
            AnnouncementKind.UNCERTAINTY, evidence.capturedAtMs + 1_500, dedupKey = "frame-feedback:$message"))
    }
    fun chooseDetectorMode(mode: DetectorMode) { detectorMode = mode }
    fun requestedProfile(): DetectorProfile = when {
        detectorMode == DetectorMode.FAST -> DetectorProfile.FAST
        detectorMode == DetectorMode.INSPECT -> DetectorProfile.INSPECT
        goalType == GoalType.FIND || now() < inspectUntilMs -> DetectorProfile.INSPECT
        else -> DetectorProfile.FAST
    }
    private fun accept(update: EngineUpdate) {
        scene = update; goalType = update.goal.type
        decisions = update.diagnostics
        update.announcements.forEach { event ->
            lastMessage = event.text
            if (!listening) speech.offer(event)
        }
        if (persistentMemory) update.memory.filter { it.id !in seenMemoryIds }.forEach { memory ->
            try { landmarkStore.save(memory); savedLandmarks = landmarkStore.load() }
            catch (_: Exception) { lastMessage = "Landmark was saved for this session; encrypted storage failed." }
        }
        seenMemoryIds = update.memory.map { it.id }.toSet()
    }
    fun command(text: String) {
        val value = text.trim().take(300)
        if (value.isEmpty()) return
        speech.stop()
        when (value.lowercase()) {
            "mute", "quiet" -> { changeMuted(true); return }
            "unmute" -> { changeMuted(false); return }
            "repeat" -> { repeat(); return }
            "pause camera", "stop camera" -> { pause(); return }
            "forget all", "delete all memories" -> { clearMemory(); return }
        }
        if (externalCommand(value)) return
        if (value.lowercase() in setOf("inspect", "inspect scene", "look carefully")) {
            inspectUntilMs = now() + 6_000
            accept(engine.command("inspect again", now())); return
        }
        accept(engine.command(value, now()))
    }
    fun changeMuted(value: Boolean) { muted = value; speech.muted = value }
    fun startListening() { speech.stop(); voice.start() }
    fun repeat() {
        if (!speech.repeat()) {
            lastMessage = "That spoken observation has expired. Ask for a fresh view, or say 'repeat text' to hear the saved OCR transcript."
            speech.offer(Announcement(lastMessage, SpeechPriority.USER_REQUEST))
        }
    }
    fun stopTalking() { speech.stop(); voice.cancel(); accept(engine.command("cancel", now())) }
    fun clearMemory() {
        landmarkStore.clear(); savedLandmarks = emptyList(); accept(engine.clearMemory(now()))
        lastMessage = "Saved landmarks deleted."
    }
    fun narrationProfile(profile: NarrationProfile) { accept(engine.setNarrationProfile(profile, now())) }
    fun speakProviderText(text: String) {
        lastMessage = text
        speech.offer(Announcement(text, SpeechPriority.USER_REQUEST, kind = AnnouncementKind.STATUS))
    }
    fun deleteLandmark(name: String) {
        try { landmarkStore.delete(name); savedLandmarks = landmarkStore.load(); accept(engine.command("forget $name", now())) }
        catch (_: Exception) { lastMessage = "Could not delete this saved landmark." }
    }
    fun recallSaved(item: SavedLandmark) {
        val timestamp = DateFormat.getDateTimeInstance().format(Date(item.savedAtEpochMs))
        val text = "${item.name}, saved $timestamp from ${item.source}. ${item.description}. " +
            (item.observedText?.let { "Saved text: $it. " } ?: "") +
            "This is a historical image observation. I cannot confirm its current location."
        lastMessage = text
        speech.offer(Announcement(text, SpeechPriority.USER_REQUEST, kind = AnnouncementKind.MEMORY))
    }
    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 50, 70, 50), -1))
    }
    private fun updateRuntimeSummary() {
        val samples = stageSamples.filter { it.detectionMs > 0 }
        fun percentile(fraction: Double): String {
            if (samples.isEmpty()) return "unavailable"
            val sorted = samples.map { it.detectionMs }.sorted()
            return "${sorted[(kotlin.math.ceil(fraction * sorted.size).toInt() - 1).coerceIn(sorted.indices)]} ms"
        }
        val first = stageSamples.firstOrNull(); val last = stageSamples.lastOrNull()
        val seconds = if (first != null && last != null) (last.completedAtMs - first.completedAtMs) / 1000.0 else 0.0
        fun rate(delta: Long): String = if (seconds >= 1 && delta >= 0) "%.1f".format(delta / seconds) else "collecting"
        diagnosticSummary = "Camera callbacks ${rate((last?.cameraFrames ?: 0)-(first?.cameraFrames ?: 0))}/s; " +
            "analyzer ${rate((last?.receivedFrames ?: 0)-(first?.receivedFrames ?: 0))}/s; " +
            "inference ${rate((last?.analyzed ?: 0)-(first?.analyzed ?: 0))}/s.\n" +
            "Detection n=${samples.size}, P50 ${percentile(.50)}, P95 ${percentile(.95)}, " +
            "P99 ${if (samples.size >= 200) percentile(.99) else "needs 200 samples"}.\n" +
            "Process PSS ${"%.1f".format(pssKb / 1024.0)} MiB; managed heap " +
            "${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1_048_576} MiB.\n" +
            "TTS engine callbacks are measured; audible speaker onset is unavailable. CameraX drops are unknown."
    }
    fun exportDiagnostics(): String = JSONObject()
        .put("schema", "sight.diagnostics.v3")
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("source", activeSource ?: "unavailable")
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("androidApi", Build.VERSION.SDK_INT)
        .put("model", timing?.modelId ?: "unavailable")
        .put("detectorMode", detectorMode.name).put("processPssKb", pssKb)
        .put("runtimeSummary", diagnosticSummary)
        .put("audibleOnsetMeasured", false)
        .put("timestampBasis", timing?.timestampBasis ?: "no observations yet")
        .put("thermalLevel", thermalLevel.name)
        .put("stages", JSONArray(stageSamples.map { sample -> JSONObject()
            .put("frameId", sample.frameId).put("receivedAtMs", sample.receivedAtMs)
            .put("capturedAtMs", sample.capturedAtMs).put("completedAtMs", sample.completedAtMs)
            .put("captureToCompletionMs", sample.capturedAtMs?.let { sample.completedAtMs - it })
            .put("model", sample.modelId).put("initializationMs", sample.initializationMs)
            .put("cameraFrames", sample.cameraFrames).put("analyzerFrames", sample.receivedFrames)
            .put("width", sample.width).put("height", sample.height).put("rotation", sample.rotationDegrees)
            .put("geometryEpoch", sample.geometryEpoch)
            .put("sensorTimestampNs", sample.sensorTimestampNs)
            .put("conversionMs", sample.conversionMs).put("detectionMs", sample.detectionMs)
            .put("ocrMs", sample.ocrMs).put("totalMs", sample.totalMs)
            .put("analyzed", sample.analyzed).put("skipped", sample.skipped) }))
        .put("speech", JSONArray(speechSamples.toList()))
        .put("speechDecisions", JSONArray(speechDecisions.toList()))
        .put("ocr", JSONArray(ocrSamples.map { sample -> JSONObject()
            .put("frameId", sample.frameId).put("submittedAtMs", sample.submittedAtMs)
            .put("completedAtMs", sample.completedAtMs).put("capturedAtMs", sample.capturedAtMs)
            .put("timeBasis", sample.timeBasis).put("nativeTaskMs", sample.completedAtMs-sample.submittedAtMs)
            .put("blocks", sample.blocks).put("status", sample.status) }))
        .put("decisions", JSONArray(decisions.map { JSONObject().put("code", it.code).put("message", it.message)
            .put("frameId", it.evidence?.frameId) }))
        .toString(2)

    override fun close() { scope.cancel(); textReader.close(); voice.close(); speech.close() }
}
