package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.sight.core.*
import org.sight.perception.DetectorProfile
import org.sight.perception.FramePixels
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** CameraX owns one latest pending image. One worker owns all model switches and inference. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class PhoneCameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val goal: () -> GoalType,
    private val thermal: () -> ThermalLevel,
    private val onObservation: (FrameObservation, StageTiming) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val requestedProfile: () -> DetectorProfile = { DetectorProfile.FAST },
    private val textReader: AsyncTextReader? = null,
    private val onTiming: (StageTiming) -> Unit = {},
    private val onFeedback: (String, Evidence) -> Unit = { _, _ -> },
    private val constrained: () -> Boolean = { false },
    private val roadReader: AsyncRoadReader? = null,
    private val roadEnabled: () -> Boolean = { false },
    private val perceptionSuspended: () -> Boolean = { false },
) : VisualSource {
    // Distinguish restarted cameras so an old, asynchronous OCR task cannot enter a new session.
    override val sourceId = "phone-camera/${sessions.incrementAndGet()}"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val cameraFrames = AtomicLong()
    private var provider: ProcessCameraProvider? = null
    private var detector: MediaPipeDetector? = null
    private val scheduler = AdaptiveScheduler()
    private var nextId = 0L
    private var analyzed = 0L
    private var received = 0L
    private var skipped = 0L
    private var lastTimestamp = -1L
    private var geometryKey = ""
    private var geometryEpoch = 0L
    private var feedbackAtMs = -10_000L
    private var lastPoorOcrAttemptMs = -10_000L
    private var slowRuns = 0
    private var fallbackUntilMs = 0L
    @Volatile private var realtimeClock = false

    fun start() {
        if (closed.get() || !active.compareAndSet(false, true)) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active.get()) return@addListener
            try {
                provider = future.get()
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val preview = Preview.Builder().setResolutionSelector(resolution).build().also { it.surfaceProvider = previewView.surfaceProvider }
                val builder = ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        if (active.get()) cameraFrames.incrementAndGet()
                    }
                })
                val analysis = builder.build().also { it.setAnalyzer(executor, ::analyze) }
                val camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                realtimeClock = Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            } catch (error: Exception) { fail("Camera unavailable: ${error.javaClass.simpleName}. Check camera permission and try again.") }
        }, mainExecutor)
    }
    private fun analyze(proxy: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            if (!active.get()) return
            received++
            if(perceptionSuspended()){ skipped++; return }
            val receivedAt = SystemClock.elapsedRealtime()
            val exposureClock = realtimeClock
            val actualThermal = thermal()
            val limited = constrained()
            val scheduleThermal = if (limited && actualThermal == ThermalLevel.NORMAL) ThermalLevel.WARM else actualThermal
            val plan = scheduler.plan(receivedAt, goal(), scheduleThermal)
            val runOcr = plan.runOcr && textReader?.inFlight == false
            if (!plan.runDetection && !runOcr) { skipped++; return }
            val rotation = proxy.imageInfo.rotationDegrees
            val geometry = "${proxy.width}x${proxy.height}:$rotation:false"
            if (geometryKey != geometry) { geometryKey = geometry; geometryEpoch++ }
            val basis = if (exposureClock) TimeBasis.CAPTURE else TimeBasis.RECEIPT
            val capturedAt = if (exposureClock) proxy.imageInfo.timestamp / 1_000_000 else null
            val observedAt = capturedAt ?: receivedAt
            val profile = if (limited || actualThermal >= ThermalLevel.HOT || receivedAt < fallbackUntilMs) DetectorProfile.FAST else requestedProfile()
            var initialized = 0L
            if (plan.runDetection && detector?.profile != profile) {
                val initializationStarted = SystemClock.elapsedRealtime()
                detector?.close(); detector = null
                detector = MediaPipeDetector(context.applicationContext, profile)
                initialized = SystemClock.elapsedRealtime() - initializationStarted
                slowRuns = 0
            }
            val preprocessingAt = SystemClock.elapsedRealtime()
            val plane = proxy.planes.first()
            val raw = FramePixels.rgba(plane.buffer, proxy.width, proxy.height, plane.rowStride, plane.pixelStride)
            val frame = try { FramePixels.orient(raw, rotation) } catch (e: Exception) { raw.recycle(); throw e }
            if (frame !== raw) raw.recycle()
            bitmap = frame
            val quality = if (goal() == GoalType.READ) FramePixels.quality(frame) else null
            val conversionEnd = SystemClock.elapsedRealtime()
            val inferenceAt = maxOf(receivedAt, lastTimestamp + 1)
            lastTimestamp = inferenceAt
            val detections = if (plan.runDetection) detector!!.detect(frame, inferenceAt) else emptyList()
            val detectionEnd = SystemClock.elapsedRealtime()
            val detectionMs = if (plan.runDetection) detectionEnd - conversionEnd else 0
            if (plan.runDetection) {
                analyzed++
                slowRuns = if (profile == DetectorProfile.INSPECT && detectionMs > 1_100) slowRuns + 1 else 0
                if (slowRuns >= 3) {
                    fallbackUntilMs = detectionEnd + 15_000
                    mainExecutor.execute { if (active.get()) onStatus("Detailed inspection is too slow on this device. Using the fast model temporarily.") }
                }
            }
            val id = ++nextId
            val evidence = Evidence(id, observedAt, sourceId, basis, detector?.modelId ?: profile.modelId, geometryEpoch)
            val sample = StageTiming(id, receivedAt, proxy.imageInfo.timestamp, conversionEnd - preprocessingAt,
                detectionMs, 0, detectionEnd - receivedAt, analyzed, skipped,
                if (exposureClock) "Camera2 exposure / elapsedRealtime" else "analyzer receipt (capture clock unavailable)",
                detector?.modelId ?: profile.modelId, initialized, detectionEnd, capturedAt, cameraFrames.get(), received,
                frame.width, frame.height, rotation, geometryEpoch, quality)
            mainExecutor.execute {
                if (active.get()) {
                    if (plan.runDetection) onObservation(FrameObservation(evidence, detections), sample) else onTiming(sample)
                }
            }
            if (roadEnabled() && actualThermal < ThermalLevel.HOT && !limited) roadReader?.submit(frame,evidence)
            if (runOcr) {
                if (quality?.feedback != null && detectionEnd - feedbackAtMs >= 6_000) {
                    feedbackAtMs = detectionEnd
                    mainExecutor.execute { if (active.get()) onFeedback(quality.feedback!!, evidence) }
                }
                // Prefer detailed frames, but never let an uncalibrated heuristic permanently veto
                // a real document (e.g. sparse dark text on an otherwise white page).
                if (quality != null && (quality.usableForText || detectionEnd - lastPoorOcrAttemptMs >= 3_000)) {
                    if (!quality.usableForText) lastPoorOcrAttemptMs = detectionEnd
                    textReader?.submit(frame, evidence.copy(modelId = "mlkit-latin-16.0.1"))
                }
            }
        } catch (_: OutOfMemoryError) { fail("Memory is low. Visual assistance is paused; close other apps and restart the camera.") }
        catch (error: LinkageError) { fail("Local inference is unavailable on this architecture: ${error.javaClass.simpleName}.") }
        catch (error: Exception) { fail("Local perception failed: ${error.javaClass.simpleName}. Pause and restart to retry.") }
        finally { bitmap?.recycle(); proxy.close() }
    }
    private fun fail(message: String) {
        if (active.getAndSet(false)) mainExecutor.execute { if (!closed.get()) onFailure(message) }
    }
    override fun stop() {
        if (!closed.compareAndSet(false, true)) return
        active.set(false); provider?.unbindAll()
        executor.execute { detector?.close(); detector = null }
        executor.shutdown()
    }
    private companion object { val sessions = AtomicLong() }
}
