package org.sight.meta

import android.content.Context
import android.os.SystemClock
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.WearablesError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MetaSourceState {
    STOPPED, REGISTRATION_REQUIRED, CAMERA_PERMISSION_REQUIRED, DEVICE_UNAVAILABLE, STARTING,
    WAITING_FOR_FRAMES, RAW_FRAMES_AVAILABLE, PAUSED, FAILED,
}

data class MetaSourceStatus(val state: MetaSourceState, val detail: String? = null)

/**
 * Owned copy of the SDK's uncompressed buffer; its YUV layout/rotation must be validated
 * before adapting it to the phone application's FrameSource. It is NOT an RGB image.
 * presentationTimeUs belongs to the stream; receivedElapsedNs belongs to the handset.
 */
data class MetaVideoPacket(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val presentationTimeUs: Long,
    val receivedElapsedNs: Long,
    val source: String,
    val captureClockSynchronized: Boolean = false,
)

/**
 * Optional DAT 1.0.1 transport scaffolding, deliberately outside the phone build.
 * start() requests a session; only observed frames establish RAW_FRAMES_AVAILABLE.
 * Registration/permission UI belongs to the host Activity. No automatic pairing.
 */
class MetaDatCameraAdapter(
    context: Context,
    private val scope: CoroutineScope,
    private val simulated: Boolean,
    private val firstFrameTimeoutMs: Long = 20_000,
) {
    init { require(firstFrameTimeoutMs in 1_000..120_000) }
    private val appContext = context.applicationContext
    private val mutableStatus = MutableStateFlow(MetaSourceStatus(MetaSourceState.STOPPED))
    val status: StateFlow<MetaSourceStatus> = mutableStatus.asStateFlow()
    private val frames = Channel<MetaVideoPacket>(1, BufferOverflow.DROP_OLDEST)
    private val gate = MetaFrameGate()
    private var worker: Job? = null

    /** Must run from a lifecycle-owned coroutine. Cancellation propagates. */
    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        stop()
        val initialized = Wearables.initialize(appContext)
        if (initialized.isFailure && initialized.errorOrNull() != WearablesError.ALREADY_INITIALIZED) {
            mutableStatus.value = MetaSourceStatus(MetaSourceState.FAILED,
                initialized.errorOrNull()?.description ?: "DAT initialization failed")
            return@withContext
        }
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            mutableStatus.value = MetaSourceStatus(MetaSourceState.REGISTRATION_REQUIRED)
            return@withContext
        }
        val permission = Wearables.checkPermissionStatus(Permission.CAMERA)
        if (permission.isFailure) {
            val error = permission.errorOrNull()
            val unavailable = error == PermissionError.NO_DEVICE || error == PermissionError.NO_DEVICE_WITH_CONNECTION
            mutableStatus.value = MetaSourceStatus(if (unavailable) MetaSourceState.DEVICE_UNAVAILABLE else MetaSourceState.FAILED,
                error?.description ?: "Permission check failed")
            return@withContext
        }
        if (permission.getOrNull() != PermissionStatus.Granted) {
            mutableStatus.value = MetaSourceStatus(MetaSourceState.CAMERA_PERMISSION_REQUIRED)
            return@withContext
        }
        gate.begin()
        mutableStatus.value = MetaSourceStatus(MetaSourceState.STARTING)
        worker = scope.launch(Dispatchers.Main.immediate) {
            var session: DeviceSession? = null
            var camera: Camera? = null
            try {
              coroutineScope {
                val sessionResult = Wearables.createSession(AutoDeviceSelector())
                session = sessionResult.getOrNull()
                    ?: error(sessionResult.errorOrNull()?.description ?: "No eligible DAT device")
                val currentSession = session
                var sessionReady = false
                var streamReady = false
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentSession.errors.collect { failure ->
                        // This single compatibility warning is explicitly nonblocking in DAT 1.0.1.
                        if (failure != DeviceSessionError.DWA_OUT_OF_STU_RANGE) error(failure.description)
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentSession.state.collect { state ->
                        sessionReady = state == DeviceSessionState.STARTED
                        gate.setStreaming(sessionReady && streamReady)
                        if (!sessionReady) drainFrames()
                        when (state) {
                            DeviceSessionState.STARTED -> if (camera == null) {
                                val result = currentSession.addCamera(StreamConfiguration(
                                    videoQuality = VideoQuality.MEDIUM,
                                    frameRate = 7,
                                    compressVideo = false,
                                ))
                                val attached = result.getOrNull()
                                    ?: error(result.errorOrNull()?.description ?: "Cannot attach camera")
                                camera = attached
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    attached.stream.errorStream.collect { failure ->
                                        error(failure.description)
                                    }
                                }
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    attached.stream.state.collect { streamState ->
                                        streamReady = streamState == StreamState.STREAMING
                                        gate.setStreaming(sessionReady && streamReady)
                                        if (!sessionReady || !streamReady) drainFrames()
                                        mutableStatus.value = MetaSourceStatus(if (!sessionReady) {
                                            MetaSourceState.PAUSED
                                        } else when (streamState) {
                                            StreamState.STREAMING -> MetaSourceState.WAITING_FOR_FRAMES
                                            StreamState.PAUSED -> MetaSourceState.PAUSED
                                            StreamState.CLOSED -> MetaSourceState.STOPPED
                                            else -> MetaSourceState.STARTING
                                        })
                                    }
                                }
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    attached.stream.videoStream.collect { frame ->
                                        // Own the bytes; SDK memory must not outlive the callback.
                                        if (frame.isCompressed || frame.isCodecConfig) {
                                            error("Unexpected compressed frame; no decoder configured")
                                        }
                                        val buffer = frame.buffer.duplicate()
                                        if (frame.width !in 1..1920 || frame.height !in 1..1920 ||
                                            !buffer.hasRemaining() || buffer.remaining() > 8_000_000) {
                                            error("Invalid DAT frame")
                                        }
                                        val receivedNs = SystemClock.elapsedRealtimeNanos()
                                        if (gate.accept(frame.presentationTimeUs, receivedNs, receivedNs)
                                            == FrameDecision.ACCEPT_RECEIPT_ONLY) {
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            frames.trySend(MetaVideoPacket(bytes, frame.width, frame.height,
                                                frame.presentationTimeUs, receivedNs,
                                                if (simulated) "meta-dat-mock" else "meta-dat-glasses"))
                                            mutableStatus.value = MetaSourceStatus(MetaSourceState.RAW_FRAMES_AVAILABLE)
                                        }
                                    }
                                }
                                attached.stream.start().onFailure { failure, _ -> error(failure.description) }
                            }
                            DeviceSessionState.PAUSED -> {
                                gate.setStreaming(false)
                                drainFrames()
                                mutableStatus.value = MetaSourceStatus(MetaSourceState.PAUSED)
                            }
                            DeviceSessionState.STOPPED -> error("DAT session ended; create a new session to reconnect")
                            else -> Unit
                        }
                    }
                }
                launch {
                    delay(firstFrameTimeoutMs)
                    if (mutableStatus.value.state in setOf(MetaSourceState.STARTING, MetaSourceState.WAITING_FOR_FRAMES)) {
                        error("No first frame within ${firstFrameTimeoutMs / 1_000} seconds")
                    }
                }
                currentSession.start()
                awaitCancellation()
              }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableStatus.value = MetaSourceStatus(MetaSourceState.FAILED,
                    failure.message ?: failure.javaClass.simpleName)
            } finally {
                gate.setStreaming(false)
                drainFrames()
                camera?.stop()
                session?.stop()
            }
        }
    }

    /** A receiver-age check, deliberately NOT a capture-age assertion. */
    fun takeLatest(maxQueueAgeNs: Long = 750_000_000): MetaVideoPacket? {
        require(maxQueueAgeNs > 0)
        if (mutableStatus.value.state != MetaSourceState.RAW_FRAMES_AVAILABLE) return null
        val frame = frames.tryReceive().getOrNull() ?: return null
        val ageNs = SystemClock.elapsedRealtimeNanos() - frame.receivedElapsedNs
        return frame.takeIf { ageNs in 0..maxQueueAgeNs }
    }

    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        worker?.cancelAndJoin()
        worker = null
        gate.setStreaming(false)
        drainFrames()
        mutableStatus.value = MetaSourceStatus(MetaSourceState.STOPPED)
    }

    private fun drainFrames() { while (frames.tryReceive().isSuccess) { /* bounded queue */ } }
}
