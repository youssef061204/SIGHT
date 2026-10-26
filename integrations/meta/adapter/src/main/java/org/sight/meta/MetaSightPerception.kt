package org.sight.meta

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sight.core.EngineUpdate
import org.sight.core.Evidence
import org.sight.core.FrameObservation
import org.sight.core.SightEngine
import org.sight.core.TimeBasis
import org.sight.perception.DetectorProfile
import org.sight.perception.FramePixels
import org.sight.perception.MediaPipeDetector

data class MetaInferenceResult(
    val frame: FrameObservation,
    val update: EngineUpdate,
    val devicePresentationTimeUs: Long,
    val receivedElapsedNs: Long,
    val conversionMs: Double,
    val inferenceMs: Double,
    val receiptToResultMs: Double,
    val modelId: String,
    val captureClockSynchronized: Boolean = false,
)

/**
 * A real shared detector and production core, not a mock receiver. The caller chooses a calibrated
 * source and consumes processLatest() from its lifecycle loop. At most one inference is in flight;
 * the transport retains only its latest packet. No images or recordings are persisted here.
 */
class MetaSightPerception(
    context: Context,
    private val adapter: MetaDatCameraAdapter,
    private val engine: SightEngine,
    private val calibration: MetaFrameCalibration,
    profile: DetectorProfile = DetectorProfile.FAST,
) {
    private val serial = Mutex()
    private val detector = lazy { MediaPipeDetector(context.applicationContext, profile) }
    private var frameId = 0L
    private var closed = false
    private var lastGeneration: Long? = null

    suspend fun start() {
        withContext(Dispatchers.Default) { serial.withLock { check(!closed); detector.value } }
        engine.setInputAvailable(true, SystemClock.elapsedRealtime())
        adapter.start()
        if (adapter.status.value.state !in activeStates) invalidateInput()
    }

    /** Also used after a terminal disconnect; adapter.start() always creates new SDK handles. */
    suspend fun reconnect() = start()

    suspend fun processLatest(): MetaInferenceResult? = withContext(Dispatchers.Default) {
        serial.withLock {
            check(!closed)
            if (adapter.status.value.state !in activeStates) {
                invalidateInput()
                return@withLock null
            }
            val model = detector.value
            val packet = adapter.takeLatest() ?: run {
                engine.tick(SystemClock.elapsedRealtime()) // expire visibility even if the stream stalls
                return@withLock null
            }
            try { calibration.requireCompatible(packet) }
            catch (failure: IllegalArgumentException) { invalidateInput(); throw failure }
            val startedNs = SystemClock.elapsedRealtimeNanos()
            if (lastGeneration != packet.streamGeneration) {
                // A new source epoch cannot inherit image identities. Its first queued frame may
                // precede this cutoff and is intentionally rejected by the core freshness gate.
                val resetMs = SystemClock.elapsedRealtime()
                engine.setInputAvailable(false, resetMs)
                engine.setInputAvailable(true, resetMs)
                lastGeneration = packet.streamGeneration
            } else engine.setInputAvailable(true, startedNs / 1_000_000)
            val bitmap = FramePixels.i420(packet.bytes, packet.width, packet.height,
                calibration.rotationDegrees, calibration.mirrored)
            val convertedNs = SystemClock.elapsedRealtimeNanos()
            val detections = try { model.detect(bitmap, packet.receivedElapsedNs / 1_000_000) }
                catch (failure: Exception) { invalidateInput(); throw failure }
                finally { bitmap.recycle() }
            val finishedNs = SystemClock.elapsedRealtimeNanos()
            val receiptMs = packet.receivedElapsedNs / 1_000_000
            val evidence = Evidence(++frameId, receiptMs, packet.source, TimeBasis.RECEIPT,
                modelId = model.modelId, geometryEpoch = packet.streamGeneration)
            val frame = FrameObservation(evidence, detections)
            // Admission and SDK lifecycle mutations share Main, so stop cannot change the source
            // between this generation check and core admission. Inference itself stays off Main.
            val update = withContext(Dispatchers.Main.immediate) {
                if (packet.streamGeneration != adapter.generation || adapter.status.value.state != MetaSourceState.RAW_FRAMES_AVAILABLE) {
                    invalidateInput()
                    null
                } else engine.onFrame(frame, SystemClock.elapsedRealtime())
            } ?: return@withLock null
            val admittedNs = SystemClock.elapsedRealtimeNanos()
            MetaInferenceResult(frame, update, packet.presentationTimeUs, packet.receivedElapsedNs,
                (convertedNs - startedNs) / 1_000_000.0, (finishedNs - convertedNs) / 1_000_000.0,
                (admittedNs - packet.receivedElapsedNs) / 1_000_000.0, model.modelId)
        }
    }

    suspend fun stop() {
        adapter.stop() // generation invalidation precedes waiting for any inference
        invalidateInput()
    }

    suspend fun close() {
        stop()
        withContext(Dispatchers.Default) {
            serial.withLock {
                if (!closed && detector.isInitialized()) detector.value.close()
                closed = true
            }
        }
    }

    private fun invalidateInput() { engine.setInputAvailable(false, SystemClock.elapsedRealtime()) }

    private companion object {
        val activeStates = setOf(MetaSourceState.STARTING, MetaSourceState.WAITING_FOR_FRAMES, MetaSourceState.RAW_FRAMES_AVAILABLE)
    }
}
