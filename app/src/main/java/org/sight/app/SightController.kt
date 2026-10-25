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
    private var seenMemoryIds = emptySet<Long>()
    private val stageSamples = ArrayDeque<StageTiming>()
    private val speechSamples = ArrayDeque<JSONObject>()
    private val landmarkStore = LandmarkStore(context)
    val speech = SpeechOutput(context, engine::isAnnouncementValid, { event, at ->
        // OCR is a fixed captured-text reading, admitted while fresh. Live image claims must stay supported.
        if (event.kind !in setOf(AnnouncementKind.LIVE_SCENE, AnnouncementKind.FIND_RESULT)) true
        else running && event.grounding.all { grounding -> scene.entities.any {
            it.id == grounding.trackId && it.visible && it.category == grounding.category &&
                it.direction == grounding.direction && at-it.lastSeenAtMs <= 1_500
        } }
    },
        { speechStatus = it }, { event, dispatchAt ->
            if (speechSamples.size == 200) speechSamples.removeFirst()
            speechSamples.addLast(JSONObject().put("text", event.text).put("kind", event.kind.name)
                .put("ttsRequestAtMs", dispatchAt).put("frameId", event.evidence?.frameId)
                .put("observationToTtsRequestMs", event.evidence?.let { dispatchAt-it.capturedAtMs })
                .put("timeBasis", event.evidence?.timeBasis?.name))
            if (haptics && event.kind == AnnouncementKind.FIND_RESULT) vibrate()
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
                if (running) accept(engine.tick(now()))
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
    fun start() { running = true; accept(engine.setInputAvailable(true, now())) }
    fun pause() {
        running = false; voice.cancel(); speech.stop()
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
        timing = sample
        if (stageSamples.size == 300) stageSamples.removeFirst()
        stageSamples.addLast(sample)
        accept(engine.onFrame(frame, now()))
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
    fun exportDiagnostics(): String = JSONObject()
        .put("schema", "sight.diagnostics.v1")
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("source", "phone-camera")
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("androidApi", Build.VERSION.SDK_INT)
        .put("model", "efficientdet_lite0_int8/1")
        .put("timestampBasis", timing?.timestampBasis ?: "no observations yet")
        .put("thermalLevel", thermalLevel.name)
        .put("stages", JSONArray(stageSamples.map { sample -> JSONObject()
            .put("frameId", sample.frameId).put("receivedAtMs", sample.receivedAtMs)
            .put("sensorTimestampNs", sample.sensorTimestampNs)
            .put("conversionMs", sample.conversionMs).put("detectionMs", sample.detectionMs)
            .put("ocrMs", sample.ocrMs).put("totalMs", sample.totalMs)
            .put("analyzed", sample.analyzed).put("skipped", sample.skipped) }))
        .put("speech", JSONArray(speechSamples.toList()))
        .put("decisions", JSONArray(decisions.map { JSONObject().put("code", it.code).put("message", it.message)
            .put("frameId", it.evidence?.frameId) }))
        .toString(2)

    override fun close() { scope.cancel(); voice.close(); speech.close() }
}
