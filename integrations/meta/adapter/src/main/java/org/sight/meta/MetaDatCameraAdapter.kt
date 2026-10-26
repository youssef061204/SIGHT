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
import com.meta.wearable.dat.core.types.LinkState
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
import java.util.concurrent.atomic.AtomicLong

enum class MetaSourceState {
    STOPPED, REGISTRATION_REQUIRED, CAMERA_PERMISSION_REQUIRED, DEVICE_UNAVAILABLE, STARTING,
    WAITING_FOR_FRAMES, RAW_FRAMES_AVAILABLE, PAUSED, DISCONNECTED, FAILED,
}

data class MetaSourceStatus(val state: MetaSourceState, val detail: String? = null, val errorCode: String? = null)

/** SDK-observed metadata. Camera attachment does not establish a calibrated RGB image or audio route. */
data class MetaDeviceCapabilities(
    val deviceName: String? = null,
    val deviceType: String? = null,
    val linkState: String? = null,
    val compatibility: String? = null,
    val thermalLevel: String? = null,
    val batteryPercent: Int? = null,
    val displayCapable: Boolean = false,
    val cameraAttached: Boolean = false,
    val microphoneRequested: Boolean = false,
)

private class MetaSourceFailure(val sourceState: MetaSourceState, val code: String, message: String) : Exception(message)

/**
 * Owned copy of the SDK's uncompressed buffer. The pinned decoder requests I420;
 * conversion still requires a matching explicit calibration profile. It is NOT an RGB image.
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
    val streamGeneration: Long = 0,
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
    private val generationCounter = AtomicLong()
    val generation: Long get() = generationCounter.get()
    private val mutableCapabilities = MutableStateFlow(MetaDeviceCapabilities())
    val capabilities: StateFlow<MetaDeviceCapabilities> = mutableCapabilities.asStateFlow()

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
                var streamHasDelivered = false
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentSession.deviceInfo.collect { device ->
                        mutableCapabilities.value = MetaDeviceCapabilities(
                            deviceName = device.name, deviceType = device.deviceType.name,
                            linkState = device.linkState.name, compatibility = device.compatibility.name,
                            thermalLevel = device.thermalLevel.name,
                            batteryPercent = device.batteryLevel.takeIf { it in 0..100 },
                            displayCapable = device.isDisplayCapable(), cameraAttached = camera != null,
                        )
                        if (sessionReady && device.linkState == LinkState.DISCONNECTED) {
                            throw MetaSourceFailure(MetaSourceState.DISCONNECTED, "DEVICE_DISCONNECTED", "Glasses disconnected. Start a new session to reconnect.")
                        }
                    }
                }
                launch {
                    while (true) {
                        delay(2_000)
                        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                            throw MetaSourceFailure(MetaSourceState.REGISTRATION_REQUIRED, "REGISTRATION_REVOKED", "Meta registration is no longer active.")
                        }
                        val currentPermission = Wearables.checkPermissionStatus(Permission.CAMERA)
                        if (currentPermission.isFailure) {
                            val failure = currentPermission.errorOrNull()
                            val disconnected = failure == PermissionError.NO_DEVICE || failure == PermissionError.NO_DEVICE_WITH_CONNECTION
                            throw MetaSourceFailure(if (disconnected) MetaSourceState.DISCONNECTED else MetaSourceState.FAILED,
                                failure?.name ?: "PERMISSION_CHECK_FAILED", failure?.description ?: "Cannot establish current camera permission.")
                        }
                        if (currentPermission.getOrNull() == PermissionStatus.Denied) {
                            throw MetaSourceFailure(MetaSourceState.CAMERA_PERMISSION_REQUIRED, "CAMERA_REVOKED", "Glasses camera permission was revoked.")
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentSession.errors.collect { failure ->
                        // This single compatibility warning is explicitly nonblocking in DAT 1.0.1.
                        if (failure != DeviceSessionError.DWA_OUT_OF_STU_RANGE) {
                            throw MetaSourceFailure(if (failure == DeviceSessionError.DEVICE_DISCONNECTED) MetaSourceState.DISCONNECTED else MetaSourceState.FAILED,
                                failure.name, failure.description)
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentSession.state.collect { state ->
                        sessionReady = state == DeviceSessionState.STARTED
                        gate.setStreaming(sessionReady && streamReady)
                        if (!sessionReady) invalidateFrames()
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
                                mutableCapabilities.value = mutableCapabilities.value.copy(cameraAttached = true)
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    attached.stream.errorStream.collect { failure ->
                                        // DAT documents STREAM_ERROR as informational; all other failures
                                        // disable observations rather than admitting uncertain source state.
                                        if (failure == StreamError.STREAM_ERROR) {
                                            mutableStatus.value = mutableStatus.value.copy(detail = failure.description, errorCode = failure.name)
                                        } else {
                                            throw MetaSourceFailure(if (failure == StreamError.PERMISSIONS_DENIED) MetaSourceState.CAMERA_PERMISSION_REQUIRED else MetaSourceState.FAILED,
                                                failure.name, failure.description)
                                        }
                                    }
                                }
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    attached.stream.state.collect { streamState ->
                                        streamReady = streamState == StreamState.STREAMING
                                        if (streamReady) streamHasDelivered = true
                                        gate.setStreaming(sessionReady && streamReady)
                                        if (!sessionReady || !streamReady) invalidateFrames()
                                        if (streamState == StreamState.CLOSED) {
                                            throw MetaSourceFailure(MetaSourceState.DISCONNECTED, "STREAM_CLOSED", "DAT stream closed; create a new session to reconnect.")
                                        }
                                        mutableStatus.value = MetaSourceStatus(if (!sessionReady) {
                                            MetaSourceState.PAUSED
                                        } else when (streamState) {
                                            StreamState.STREAMING -> MetaSourceState.WAITING_FOR_FRAMES
                                            StreamState.PAUSED -> MetaSourceState.PAUSED
                                            StreamState.STOPPING -> MetaSourceState.STOPPED
                                            StreamState.STOPPED -> if (streamHasDelivered) MetaSourceState.STOPPED else MetaSourceState.STARTING
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
                                                if (simulated) "meta-dat-mock" else "meta-dat-glasses",
                                                streamGeneration = generation))
                                            mutableStatus.value = MetaSourceStatus(MetaSourceState.RAW_FRAMES_AVAILABLE)
                                        }
                                    }
                                }
                                attached.stream.start().onFailure { failure, _ -> error(failure.description) }
                            }
                            DeviceSessionState.PAUSED -> {
                                gate.setStreaming(false)
                                invalidateFrames()
                                mutableStatus.value = MetaSourceStatus(MetaSourceState.PAUSED)
                            }
                            DeviceSessionState.STOPPED -> throw MetaSourceFailure(MetaSourceState.DISCONNECTED, "SESSION_STOPPED", "DAT session ended; create a new session to reconnect")
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
                mutableStatus.value = MetaSourceStatus((failure as? MetaSourceFailure)?.sourceState ?: MetaSourceState.FAILED,
                    failure.message ?: failure.javaClass.simpleName, (failure as? MetaSourceFailure)?.code)
            } finally {
                gate.setStreaming(false)
                invalidateFrames()
                mutableCapabilities.value = mutableCapabilities.value.copy(cameraAttached = false)
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
        return frame.takeIf { ageNs in 0..maxQueueAgeNs && it.streamGeneration == generation }
    }

    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        // Invalidate already-taken frames before waiting for the SDK collectors to unwind.
        generationCounter.incrementAndGet()
        worker?.cancelAndJoin()
        worker = null
        gate.setStreaming(false)
        drainFrames()
        mutableStatus.value = MetaSourceStatus(MetaSourceState.STOPPED)
        mutableCapabilities.value = mutableCapabilities.value.copy(cameraAttached = false)
    }

    /** Recreates terminal SDK handles; permissions are checked again. No hidden pairing or retry loop. */
    suspend fun reconnect() = start()

    private fun drainFrames() { while (frames.tryReceive().isSuccess) { /* bounded queue */ } }
    private fun invalidateFrames() { generationCounter.incrementAndGet(); drainFrames() }
}
