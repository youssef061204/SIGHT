package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.sight.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A single analyzer plus CameraX's one-frame latest buffer bounds work and raw media lifetime. */
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
) : VisualSource {
    override val sourceId = "phone-camera"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var detector: LocalDetector? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scheduler = AdaptiveScheduler()
    private var nextId = 0L
    private var analyzed = 0L
    private var skipped = 0L
    private var lastTimestamp = -1L
    private val ocrBusy = AtomicBoolean(false)
    private var nextOcrRetryMs = 0L
    @Volatile private var realtimeClock = false

    fun start() {
        if (closed.get()) return
        if (!active.compareAndSet(false, true)) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!active.get()) return@addListener
            try {
                provider = future.get()
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                val camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                realtimeClock = Camera2CameraInfo.from(camera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            } catch (error: Exception) {
                active.set(false)
                onFailure("Camera unavailable: ${error.javaClass.simpleName}. Check camera permission and try again.")
            }
        }, mainExecutor)
    }

    private fun analyze(proxy: ImageProxy) {
        var bitmap: Bitmap? = null
        try {
            if (!active.get()) return
            val receivedAt = SystemClock.elapsedRealtime()
            val useExposureClock = realtimeClock
            val plan = scheduler.plan(receivedAt, goal(), thermal())
            if (!plan.runDetection && !plan.runOcr) { skipped++; return }
            if (detector == null) detector = MediaPipeDetector(context)
            val raw = proxy.toBitmap()
            val frameBitmap = if (proxy.imageInfo.rotationDegrees == 0) raw else {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }, true).also { raw.recycle() }
            }
            bitmap = frameBitmap
            val conversionEnd = SystemClock.elapsedRealtime()
            // Exposure time is used only when Camera2 declares the elapsedRealtime clock domain.
            val observedAt = if (useExposureClock) proxy.imageInfo.timestamp / 1_000_000 else receivedAt
            val inferenceTimestamp = maxOf(receivedAt, lastTimestamp + 1)
            lastTimestamp = inferenceTimestamp
            // OCR updates include real detections: an empty detection list means observed absence.
            val detections = detector!!.detect(frameBitmap, inferenceTimestamp)
            val detectionEnd = SystemClock.elapsedRealtime()
            val text = if (plan.runOcr && receivedAt >= nextOcrRetryMs && ocrBusy.compareAndSet(false, true)) try {
                // ML Kit may outlive our timeout. Its owned copy is released only on native completion.
                val ocrBitmap = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
                val task = try { recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)) }
                catch (error: Exception) { ocrBitmap.recycle(); ocrBusy.set(false); throw error }
                task.addOnCompleteListener(mainExecutor) { ocrBitmap.recycle(); ocrBusy.set(false) }
                val result = Tasks.await(task, 1800, TimeUnit.MILLISECONDS)
                result.textBlocks.mapNotNull { block ->
                    val b = block.boundingBox ?: return@mapNotNull null
                    val box = normalizedBox(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                        frameBitmap.width, frameBitmap.height) ?: return@mapNotNull null
                    TextObservation(block.text, box)
                }
            } catch (_: Exception) {
                nextOcrRetryMs = SystemClock.elapsedRealtime() + 3_000
                mainExecutor.execute { if (active.get()) onStatus("Text recognition is warming up or unavailable. Object detection continues; hold text steady to retry.") }
                emptyList()
            } else emptyList()
            val end = SystemClock.elapsedRealtime()
            analyzed++
            val id = ++nextId
            val observation = FrameObservation(Evidence(id, observedAt, sourceId,
                if (useExposureClock) TimeBasis.CAPTURE else TimeBasis.RECEIPT), detections, text)
            val timing = StageTiming(id, receivedAt, proxy.imageInfo.timestamp, conversionEnd-receivedAt,
                detectionEnd-conversionEnd, end-detectionEnd, end-receivedAt, analyzed, skipped,
                if (useExposureClock) "Camera2 exposure / elapsedRealtime" else "analyzer receipt (exposure clock uncalibrated)")
            mainExecutor.execute { if (active.get()) onObservation(observation, timing) }
        } catch (error: Exception) {
            // A model failure invalidates visual availability; never replay the last successful result.
            val wasActive = active.getAndSet(false)
            if (wasActive) mainExecutor.execute { onFailure("Local perception failed: ${error.javaClass.simpleName}. Pause and restart to retry.") }
        } catch (error: LinkageError) {
            val wasActive = active.getAndSet(false)
            if (wasActive) mainExecutor.execute { onFailure("The local model runtime is unavailable on this device architecture. Visual assistance is paused.") }
        } finally { bitmap?.recycle(); proxy.close() }
    }

    override fun stop() {
        if (!closed.compareAndSet(false, true)) return
        active.set(false)
        provider?.unbindAll()
        // Cleanup follows in-flight inference on the same executor, avoiding closing native models mid-call.
        executor.execute { detector?.close(); detector = null; recognizer.close() }
        executor.shutdown()
    }
}
