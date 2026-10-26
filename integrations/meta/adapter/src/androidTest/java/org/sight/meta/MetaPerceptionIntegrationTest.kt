package org.sight.meta

import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.core.AnnouncementKind
import org.sight.core.GoalPhase
import org.sight.core.SightEngine
import org.sight.core.TimeBasis
import org.sight.perception.FramePixels

/** Official MDK -> SDK HEVC decode -> real shared RGB model -> actual production goal engine. */
@RunWith(AndroidJUnit4::class)
class MetaPerceptionIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val kit = MockDeviceKit.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before fun grants() {
        listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.CAMERA").forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
            }
        }
    }

    @After fun cleanup() { kit.disable(); scope.cancel() }

    @Test fun officialMockI420ColorChartConvertsCorrectColorsAndOrientation() = runBlocking {
        ActivityScenario.launch(MockHostActivity::class.java).use {
            val glasses = feed("mock-color-chart-hevc.mp4")
            val adapter = MetaDatCameraAdapter(context, scope, true, 60_000)
            try {
                adapter.start()
                val frame = nextFrame(adapter, "color-chart first frame")
                MetaFrameCalibration.mockFixture().requireCompatible(frame)
                val bitmap = FramePixels.i420(frame.bytes, frame.width, frame.height)
                try {
                    val colors = listOf(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                        bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                        bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                        bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
                    assertColor(colors[0], 255, 0, 0)
                    assertColor(colors[1], 0, 255, 0)
                    assertColor(colors[2], 0, 0, 255)
                    assertColor(colors[3], 255, 255, 255)
                    File(context.cacheDir, "meta-color-chart-proof.json").writeText(JSONObject()
                        .put("source", frame.source).put("width", frame.width).put("height", frame.height)
                        .put("rgb", JSONArray(colors.map { listOf(Color.red(it), Color.green(it), Color.blue(it)) }))
                        .put("layout", "packed-I420").put("rotationDegrees", 0)
                        .put("captureClockSynchronized", false).toString(2))
                } finally { bitmap.recycle() }
            } finally { adapter.stop(); kit.unpairDevice(glasses) }
        }
    }

    @Test fun officialMockPhotographCompletesFindThroughSharedDetectorAndCore() = runBlocking {
        ActivityScenario.launch(MockHostActivity::class.java).use {
            val glasses = feed("mock-coco-person-hevc.mp4")
            val adapter = MetaDatCameraAdapter(context, scope, true, 60_000)
            val engine = SightEngine()
            val perception = MetaSightPerception(context, adapter, engine, MetaFrameCalibration.mockFixture())
            val samples = JSONArray()
            var findCompleted = false
            var failureDetail: String? = null
            try {
                perception.start()
                engine.command("find a person", SystemClock.elapsedRealtime())
                val completion = withTimeout(90_000) {
                    var completed: MetaInferenceResult? = null
                    while (completed == null) {
                        val result = perception.processLatest()
                        if (result != null) {
                            samples.put(JSONObject().put("frameId", result.frame.evidence.frameId)
                                .put("modelId", result.modelId)
                                .put("devicePresentationTimeUs", result.devicePresentationTimeUs)
                                .put("receiptElapsedNs", result.receivedElapsedNs)
                                .put("receiptToResultMs", result.receiptToResultMs)
                                .put("conversionMs", result.conversionMs).put("inferenceMs", result.inferenceMs)
                                .put("goalPhase", result.update.goal.phase.name)
                                .put("sourceState", adapter.status.value.state.name)
                                .put("reliability", result.update.reliability.name)
                                .put("diagnostics", JSONArray(result.update.diagnostics.map { it.code }))
                                .put("entities", JSONArray(result.update.entities.map { entity ->
                                    JSONObject().put("id", entity.id).put("category", entity.category)
                                        .put("confidence", entity.confidence).put("visible", entity.visible)
                                        .put("consecutiveObservations", entity.consecutiveObservations)
                                }))
                                .put("detections", JSONArray(result.frame.detections.map { detection ->
                                    JSONObject().put("category", detection.category).put("confidence", detection.confidence)
                                })))
                            if (result.update.goal.phase == GoalPhase.COMPLETED) completed = result
                            check(result.update.goal.phase !in setOf(GoalPhase.TIMED_OUT, GoalPhase.UNSUPPORTED, GoalPhase.CANCELLED)) {
                                "Photo Find ended as ${result.update.goal.phase}; ${result.update.diagnostics.map { it.code }}"
                            }
                        }
                        requireSourceActive(adapter, "photo inference")
                        if (completed == null) delay(150)
                    }
                    completed
                }
                assertTrue(completion.frame.detections.any { it.category == "person" })
                assertEquals(TimeBasis.RECEIPT, completion.frame.evidence.timeBasis)
                assertEquals("meta-dat-mock", completion.frame.evidence.sourceId)
                assertFalse(completion.captureClockSynchronized)
                assertTrue(completion.update.announcements.any {
                    it.kind == AnnouncementKind.FIND_RESULT && it.evidence?.sourceId == "meta-dat-mock"
                })
                assertTrue(adapter.capabilities.value.cameraAttached)
                findCompleted = true
                perception.stop()
                assertNull(perception.processLatest())
                assertFalse(engine.tick(SystemClock.elapsedRealtime()).entities.any { it.visible })
            } catch (failure: Throwable) {
                failureDetail = failure.toString()
                throw failure
            } finally {
                File(context.cacheDir, "meta-real-pipeline-proof.json").writeText(JSONObject()
                    .put("source", "official-DAT-1.0.1-MDK-file-feed")
                    .put("fixture", "COCO-350148-repeated-photograph-HEVC")
                    .put("samples", samples).put("findCompleted", findCompleted)
                    .put("failure", failureDetail ?: JSONObject.NULL)
                    .put("lastSourceStatus", adapter.status.value.toString())
                    .put("captureClockSynchronized", false).put("physicalGlassesTested", false).toString(2))
                perception.close(); kit.unpairDevice(glasses)
            }
        }
    }

    @Test fun permissionRevocationClearsFramesAndExplicitReconnectCreatesFreshSession() = runBlocking {
        ActivityScenario.launch(MockHostActivity::class.java).use {
            val glasses = feed("mock-test-pattern-hevc.mp4")
            val adapter = MetaDatCameraAdapter(context, scope, true, 60_000)
            try {
                adapter.start()
                val first = nextFrame(adapter, "permission test initial frame")
                kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
                withTimeout(20_000) { adapter.status.first { it.state == MetaSourceState.CAMERA_PERMISSION_REQUIRED } }
                assertNull(adapter.takeLatest())
                assertTrue(adapter.generation > first.streamGeneration)
                kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
                withTimeout(10_000) {
                    while (Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() != PermissionStatus.Granted) delay(100)
                }
                adapter.reconnect()
                val recovered = nextFrame(adapter, "permission test reconnected frame")
                assertTrue(recovered.streamGeneration > first.streamGeneration)
                assertEquals("meta-dat-mock", recovered.source)
            } finally { adapter.stop(); kit.unpairDevice(glasses) }
        }
    }

    private suspend fun feed(asset: String): MockGlasses {
        kit.enable()
        withTimeout(10_000) { Wearables.registrationState.first { it == RegistrationState.REGISTERED } }
        kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
        val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        val file = File(context.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        glasses.services.camera.setCameraFeed(Uri.fromFile(file))
        glasses.powerOn(); glasses.unfold(); glasses.don()
        // Pair/power operations schedule SDK publication. The production adapter correctly refuses
        // to start if its permission query runs before this actual device becomes available.
        withTimeout(10_000) { Wearables.devices.first { glasses.deviceIdentifier in it } }
        withTimeout(10_000) {
            while (Wearables.devicesMetadata[glasses.deviceIdentifier]?.value?.linkState != LinkState.CONNECTED) delay(50)
        }
        withTimeout(10_000) {
            while (Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() != PermissionStatus.Granted) delay(50)
        }
        return glasses
    }

    private suspend fun nextFrame(adapter: MetaDatCameraAdapter, stage: String): MetaVideoPacket {
        try {
            return withTimeout(70_000) {
                var packet = adapter.takeLatest()
                while (packet == null) {
                    requireSourceActive(adapter, stage)
                    delay(50)
                    packet = adapter.takeLatest()
                }
                packet
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$stage: timed out; ${adapter.status.value}; capabilities=${adapter.capabilities.value}", timeout)
        }
    }

    private fun requireSourceActive(adapter: MetaDatCameraAdapter, stage: String) {
        check(adapter.status.value.state in setOf(MetaSourceState.STARTING, MetaSourceState.WAITING_FOR_FRAMES,
            MetaSourceState.RAW_FRAMES_AVAILABLE)) { "$stage: ${adapter.status.value}; capabilities=${adapter.capabilities.value}" }
    }

    private fun assertColor(pixel: Int, red: Int, green: Int, blue: Int) {
        val rgb = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        listOf(red, green, blue).zip(rgb).forEach { (expected, actual) ->
            assertTrue("Expected approximate RGB $red,$green,$blue; got $rgb", kotlin.math.abs(expected - actual) <= 35)
        }
    }
}
