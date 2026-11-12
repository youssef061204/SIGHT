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
    var routeActive: () -> Boolean = { false }
    var onVisualTaskFinished: () -> Unit = {}
    var onFreshNavigationCueRequested: () -> Unit = {}
    var onClearSavedDestinations: () -> Boolean = { true }
    var walkingAwareness by mutableStateOf(false); private set
    var roadDiagnostics by mutableStateOf(false); private set
    var surfaceSummary by mutableStateOf("Road-scene model has not observed a frame."); private set
    var latestRoadTiming by mutableStateOf<RoadTiming?>(null); private set
    var cameraPose by mutableStateOf<CameraPose?>(null); private set
    private val roadSamples=ArrayDeque<RoadTiming>()
    // Held-out presence precision passed, but FP/image .025 exceeded the sealed .02 gate.
    // Keep segmentation visible in diagnostics; no automatic surface narration is admitted.
    private val environment = EnvironmentalAwareness()
    private val orientation = CameraOrientation(context) { cameraPose = it }
    private var visualSubtask = false
    private var landmarkSearch:TextLandmarkSearch?=null
    private var deferredNavigationCue:org.sight.app.navigation.NavigationCue?=null
    private var lastNavigationCue:org.sight.app.navigation.NavigationCue?=null
    var navigationSpeechRevision=0L; private set
    val roadReader = AsyncRoadReader(context) { observation, sample ->
        latestRoadTiming = sample
        if(roadSamples.size>=120) roadSamples.removeFirst()
        roadSamples.addLast(sample)
        if (observation != null && running && observation.evidence.sourceId == activeSource &&
            observation.evidence.geometryEpoch == activeGeometryEpoch) {
            surfaceSummary = observation.regions.joinToString { "${it.label}: ${"%.1f".format(it.coverage*100)}% image, mean model score ${"%.2f".format(it.score)}" }
                .ifBlank { "No supported surface region exceeded the model mask threshold. This is not evidence of an unobstructed area." }
            environment.onSurfaces(observation,now(),!listening && (walkingAwareness || routeActive())).forEach(::offer)
        }
    }
    @Volatile private var inspectUntilMs = 0L
    @Volatile var resourceConstrained = false; private set
    private var activeSource: String? = null
    private var activeGeometryEpoch: Long? = null
    private var lastRuntimeSampleMs = 0L
    private var pssKb = 0L
    private val ocrSamples = ArrayDeque<OcrTiming>()
    private val speechDecisions = ArrayDeque<JSONObject>()
    val textReader = AsyncTextReader(context) { evidence, text, sample ->
        scope.launch {
            if (ocrSamples.size >= 300) ocrSamples.removeFirst()
            ocrSamples.addLast(sample); latestOcr = sample
            if (running && evidence.sourceId == activeSource) {
                if (sample.status == "failed") ocrFailure(evidence)
                else {
                    val requested=landmarkSearch
                    val matching=requested?.matching(evidence,text,now()) ?: text
                    accept(engine.onTextObservation(evidence, matching, now()))
                    if(requested!=null && matching.isNotEmpty()) landmarkSearch=null
                }
            }
        }
    }
    private var seenMemoryIds = emptySet<Long>()
    private val stageSamples = ArrayDeque<StageTiming>()
    private val speechSamples = ArrayDeque<JSONObject>()
    private val landmarkStore = LandmarkStore(context)
    val speech = SpeechOutput(context, ::isAnnouncementValid, { event, at ->
        if(event.kind==AnnouncementKind.ENVIRONMENT) running && environment.isSupported(event,at,ongoing=true)
        else if (event.kind==AnnouncementKind.NAVIGATION) isAnnouncementValid(event,at)
        // OCR is a fixed captured-text reading, admitted while fresh. Live image claims must stay supported.
        else if (event.kind !in setOf(AnnouncementKind.LIVE_SCENE, AnnouncementKind.FIND_RESULT)) true
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
        }, { event ->
            if (visualSubtask && event.kind in setOf(AnnouncementKind.FIND_RESULT,AnnouncementKind.OCR)) {
                visualSubtask=false
                val stopped=engine.command("cancel",now())
                scene=stopped.copy(announcements=emptyList()); goalType=PerceptionPolicy.operationalGoal(scene.goal)
                onVisualTaskFinished()
            }
        })
    val voice = OfflineVoiceInput(context, ::command) { active, state ->
        listening = active; microphoneStatus = state
        if(active && state.startsWith("Listening ")) signalMicrophoneReady()
        if(!active && Regex("permission|no clear|could not|unavailable|timed out|not enough|busy",RegexOption.IGNORE_CASE).containsMatchIn(state)) {
            lastMessage=state
            speech.offer(Announcement(state,SpeechPriority.USER_REQUEST,kind=AnnouncementKind.STATUS,validUntilMs=now()+40_000))
        }
        if(!active && deferredNavigationCue!=null) scope.launch {
            delay(150)
            val cue=deferredNavigationCue; deferredNavigationCue=null
            if(cue!=null && !listening && routeActive()) {
                if(cue.capturedElapsedMs?.let { now()-it>(cue.maximumAgeMs ?: 10_000) }==true) onFreshNavigationCueRequested()
                else speakNavigationCue(cue)
            }
        }
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
    private fun isAnnouncementValid(event:Announcement,at:Long):Boolean {
        if (!SpeechSafety.allows(event,at)) return false
        return when(event.kind) {
            AnnouncementKind.NAVIGATION -> event.contextId==navigationSpeechRevision.toString() && (event.validUntilMs?.let { at<=it } ?: true)
            AnnouncementKind.ENVIRONMENT -> running && environment.isSupported(event,at)
            else -> engine.isAnnouncementValid(event,at)
        }
    }
    private fun offer(event:Announcement) {
        if (!listening && !(routeActive() && event.priority==SpeechPriority.AMBIENT)) speech.offer(event)
    }
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
        activeSource = null; activeGeometryEpoch = null; stageSamples.clear(); timing = null; cameraGeneration++
        environment.reset(); orientation.start()
        running = true; accept(engine.setInputAvailable(true, now()))
        lastMessage = "Camera starting. Waiting for fresh visual evidence."
    }
    fun pause() {
        orientation.stop(); environment.reset(); cameraPose=null
        running = false; activeSource = null; activeGeometryEpoch = null; voice.cancel(); speech.stop()
        accept(engine.setInputAvailable(false, now()))
        lastMessage = "Camera paused. Visual observations are unavailable."
    }
    fun failure(message: String) {
        orientation.stop(); environment.reset()
        running = false; activeSource = null; activeGeometryEpoch = null; speech.stop(); accept(engine.setInputAvailable(false, now())); lastMessage = message
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
        activeGeometryEpoch = sample.geometryEpoch
        if (stageSamples.size >= 300) stageSamples.removeFirst()
        stageSamples.addLast(sample)
    }
    fun frameFeedback(message: String, evidence: Evidence) {
        if (!running || evidence.sourceId != activeSource || now() - evidence.capturedAtMs !in 0..1_500) return
        lastMessage = message
        if (!listening) speech.offer(Announcement(message, SpeechPriority.TASK, evidence,
            AnnouncementKind.UNCERTAINTY, evidence.capturedAtMs + 1_500, dedupKey = "frame-feedback:$message"))
    }
    private fun ocrFailure(evidence: Evidence) {
        if (!PerceptionPolicy.canReportOcrFailure(scene.goal, running, activeSource, activeGeometryEpoch, evidence, now())) return
        lastMessage = "The text reader could not finish that captured view. Try reading again."
        if (!listening) speech.offer(Announcement(lastMessage, SpeechPriority.TASK, kind = AnnouncementKind.STATUS,
            dedupKey = "ocr-failure:${scene.goal.startedAtMs}"))
    }
    fun chooseDetectorMode(mode: DetectorMode) { detectorMode = mode }
    fun requestedProfile(): DetectorProfile = PerceptionPolicy.detectorProfile(detectorMode, goalType, inspectUntilMs, now())
    private fun accept(update: EngineUpdate) {
        scene = update; goalType = PerceptionPolicy.operationalGoal(update.goal)
        decisions = update.diagnostics
        update.announcements.forEach { event ->
            lastMessage = event.text
            offer(event)
        }
        environment.onObjects(update,now(),!listening && (walkingAwareness || routeActive())).forEach(::offer)
        if (persistentMemory) update.memory.filter { it.id !in seenMemoryIds }.forEach { memory ->
            try { landmarkStore.save(memory); savedLandmarks = landmarkStore.load() }
            catch (_: Exception) { lastMessage = "Landmark was saved for this session; encrypted storage failed." }
        }
        seenMemoryIds = update.memory.map { it.id }.toSet()
    }
    fun command(text: String) {
        val value = text.trim().replace(Regex("(?i)^sight[,: ]+"),"").trim().trimEnd('.','?','!').take(300)
        if (value.isEmpty()) return
        speech.stop()
        when (val deletion = PerceptionPolicy.memoryDeletion(value)) {
            MemoryDeletion.All -> { clearMemory(); return }
            is MemoryDeletion.Named -> { deleteLandmark(deletion.name); return }
            null -> Unit
        }
        when (value.lowercase()) {
            "mute", "quiet" -> { changeMuted(true); return }
            "unmute" -> { changeMuted(false); return }
            "repeat" -> { repeat(); return }
            "pause camera", "stop camera" -> { pause(); return }
        }
        if (externalCommand(value)) { deferredNavigationCue=null; return }
        TextLandmarkSearch.fromCommand(value,now())?.let { search ->
            landmarkSearch=search; visualSubtask=routeActive()
            accept(engine.command("read everything",now()))
            perceptionStatus("I do not have a validated ${search.target} object detector. I can look for relevant sign text and quote it; that will not verify the physical landmark or its location.")
            return
        }
        if(value.lowercase() !in setOf("repeat","repeat text","read that again")) landmarkSearch=null
        when(value.lowercase()) {
            "walking awareness", "observe intersection", "intersection mode" -> {
                walkingAwareness=true
                perceptionStatus("Traffic-object observation enabled. Road-surface descriptions are unavailable. Signal states and crossing conditions remain unknown."); return
            }
            "road diagnostics", "inspect road model" -> {
                roadDiagnostics=true
                perceptionStatus("Road-model diagnostics enabled. Predictions are shown in Developer; automatic surface descriptions remain disabled because the false-event test limit was exceeded."); return
            }
            "stop road diagnostics" -> { roadDiagnostics=false; perceptionStatus("Road-model diagnostics stopped."); return }
            "stop awareness" -> { walkingAwareness=false; perceptionStatus("Optional road observation stopped."); return }
            "camera alignment", "check camera", "where is the camera pointing" -> {
                perceptionStatus(cameraPose?.description(now()) ?: "Camera orientation is unavailable. Image directions do not establish walking direction."); return
            }
            "read the sign", "read that street sign", "read the street sign" -> { command("read everything"); return }
            "what's around me", "what is around me" -> { command("explore"); return }
            "where's my water bottle", "where is my water bottle", "find my water bottle", "find a water bottle" -> { command("find a bottle"); return }
        }
        if (routeActive() && (value.startsWith("find ",true) || value.startsWith("look for ",true) || value.startsWith("read",true))) visualSubtask=true
        if (value.lowercase() in setOf("inspect", "inspect scene", "look carefully")) {
            inspectUntilMs = now() + 6_000
            accept(engine.command("inspect again", now())); return
        }
        accept(engine.command(value, now()))
    }
    fun changeMuted(value: Boolean) { muted = value; speech.muted = value }
    fun startListening() {
        if(speech.currentKind==AnnouncementKind.NAVIGATION) deferredNavigationCue=lastNavigationCue
        speech.stop(); voice.start()
    }
    fun repeat() {
        if (!speech.repeat()) {
            lastMessage = "That spoken observation has expired. Ask for a fresh view, or say 'repeat text' to hear the saved OCR transcript."
            speech.offer(Announcement(lastMessage, SpeechPriority.USER_REQUEST))
        }
    }
    fun stopTalking() { deferredNavigationCue=null; lastNavigationCue=null; speech.stop(); voice.cancel(); accept(engine.command("cancel", now())) }
    fun clearMemory() {
        landmarkSearch=null
        val destinationsCleared=onClearSavedDestinations()
        speech.clearHistory()
        speechSamples.clear(); speechDecisions.clear()
        val storageCleared = try { landmarkStore.clear(); savedLandmarks = landmarkStore.load(); true }
            catch (_: Exception) { false }
        accept(engine.clearMemory(now()))
        lastMessage = when {
            storageCleared && destinationsCleared -> "Saved landmarks, saved destinations and session visual memories deleted."
            !storageCleared -> "Session visual memories deleted, but encrypted saved landmarks could not be deleted. Try again in Memory."
            else -> "Landmarks and session visual memories deleted, but saved destinations could not be deleted. Try deleting destinations again."
        }
        speech.offer(Announcement(lastMessage, SpeechPriority.USER_REQUEST, kind = AnnouncementKind.STATUS))
    }
    fun narrationProfile(profile: NarrationProfile) { accept(engine.setNarrationProfile(profile, now())) }
    fun speakProviderText(text: String) {
        lastMessage = text
        speech.offer(Announcement(text, SpeechPriority.NAVIGATION, kind = AnnouncementKind.NAVIGATION,validUntilMs=now()+40_000,
            contextId=navigationSpeechRevision.toString()))
    }
    fun speakNavigationCue(cue:org.sight.app.navigation.NavigationCue) {
        lastNavigationCue=cue
        if(listening){ deferredNavigationCue=cue; return }
        val at=now()
        if(cue.capturedElapsedMs?.let { it>at || at-it>(cue.maximumAgeMs ?: 10_000) }==true) return
        lastMessage=cue.text
        val deadline=cue.capturedElapsedMs?.let { it+(cue.maximumAgeMs ?: 10_000) } ?: at+120_000
        speech.offer(Announcement(cue.text,SpeechPriority.NAVIGATION,kind=AnnouncementKind.NAVIGATION,validUntilMs=deadline,
            contextId=navigationSpeechRevision.toString()))
    }
    fun invalidateNavigationSpeech() {
        navigationSpeechRevision++; deferredNavigationCue=null; lastNavigationCue=null
        speech.tick(now())
    }
    fun roadObservationEnabled() = roadDiagnostics && !listening && !resourceConstrained && thermalLevel<ThermalLevel.HOT
    fun deleteLandmark(name: String) {
        val hadSession = scene.memory.any { it.name.equals(name, ignoreCase = true) }
        var hadSaved = false
        var storageDeleted = true
        try {
            val storedName = landmarkStore.load().firstOrNull { it.name.equals(name, ignoreCase = true) }?.name
            hadSaved = storedName != null
            if (storedName != null) landmarkStore.delete(storedName)
            savedLandmarks = landmarkStore.load()
        } catch (_: Exception) {
            storageDeleted = false
        }
        accept(engine.command("forget $name", now()))
        lastMessage = when {
            !storageDeleted -> "Session recall for $name was cleared, but its encrypted saved record could not be deleted and may still be present."
            hadSaved || hadSession -> "Deleted the saved and session memory named $name."
            else -> "There is no saved or session memory named $name."
        }
        speech.offer(Announcement(lastMessage, SpeechPriority.USER_REQUEST, kind = AnnouncementKind.STATUS))
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
    @Suppress("DEPRECATION")
    private fun signalMicrophoneReady() {
        val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if(vibrator.hasVibrator()) vibrator.vibrate(android.os.VibrationEffect.createOneShot(25,android.os.VibrationEffect.DEFAULT_AMPLITUDE))
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
        .put("road",JSONArray(roadSamples.map { sample -> JSONObject().put("frameId",sample.frameId)
            .put("capturedAtMs",sample.capturedAtMs).put("completedAtMs",sample.completedAtMs)
            .put("nativeTaskMs",sample.inferenceMs).put("receiptOrCaptureToResultMs",sample.completedAtMs-sample.capturedAtMs)
            .put("status",sample.status) }))
        .put("decisions", JSONArray(decisions.map { JSONObject().put("code", it.code).put("message", it.message)
            .put("frameId", it.evidence?.frameId) }))
        .toString(2)

    override fun close() { scope.cancel(); orientation.close(); roadReader.close(); textReader.close(); voice.close(); speech.close() }
}
