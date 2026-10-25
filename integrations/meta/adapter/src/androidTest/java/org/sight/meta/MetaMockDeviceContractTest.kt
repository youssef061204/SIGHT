package org.sight.meta

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import android.net.Uri
import java.io.File
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the OFFICIAL kit; requires an emulator/phone. These are not host-JVM fakes. */
@RunWith(AndroidJUnit4::class)
class MetaMockDeviceContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val kit = MockDeviceKit.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before fun grantBluetooth() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        automation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }

    @After fun cleanUp() { kit.disable(); scope.cancel() }

    @Test fun officialKitPairsAndRemovesSimulatedDevice() = runBlocking {
        kit.enable()
        val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        assertTrue(kit.pairedDevices.any { it.deviceIdentifier == glasses.deviceIdentifier })
        kit.unpairDevice(glasses)
        assertFalse(kit.pairedDevices.any { it.deviceIdentifier == glasses.deviceIdentifier })
    }

    @Test fun unregisteredAdapterDoesNotPretendToConnect() = runBlocking {
        kit.enable(MockDeviceKitConfig(initiallyRegistered = false))
        val adapter = MetaDatCameraAdapter(context, scope, simulated = true)
        adapter.start()
        assertEquals(MetaSourceState.REGISTRATION_REQUIRED, adapter.status.value.state)
        assertEquals(null, adapter.takeLatest())
        adapter.stop()
    }

    @Test fun officialPermissionDenialStopsBeforeSessionCreation() = runBlocking {
        kit.enable()
        withTimeout(5_000) { Wearables.registrationState.first { it == RegistrationState.REGISTERED } }
        val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn(); glasses.unfold(); glasses.don()
        kit.permissions.set(Permission.CAMERA, PermissionStatus.Denied)
        withTimeout(20_000) {
            while (Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() != PermissionStatus.Denied) delay(100)
        }
        val adapter = MetaDatCameraAdapter(context, scope, simulated = true)
        adapter.start()
        assertEquals("Denial detail: ${adapter.status.value.detail}", MetaSourceState.CAMERA_PERMISSION_REQUIRED, adapter.status.value.state)
        assertEquals(null, adapter.takeLatest())
        adapter.stop()
        kit.unpairDevice(glasses)
    }

    @Test fun registeredWithoutDeviceReportsDeviceUnavailable() = runBlocking {
        kit.enable()
        withTimeout(10_000) { Wearables.registrationState.first { it == RegistrationState.REGISTERED } }
        withTimeout(10_000) { Wearables.devices.first { it.isEmpty() } }
        val adapter = MetaDatCameraAdapter(context, scope, simulated = true)
        adapter.start()
        assertEquals("No-device detail: ${adapter.status.value.detail}", MetaSourceState.DEVICE_UNAVAILABLE, adapter.status.value.state)
        assertEquals(null, adapter.takeLatest())
        adapter.stop()
    }

    @Test fun officialSimulatedCameraDeliversOrderedFramesAndClearsOnStop() = runBlocking {
        ActivityScenario.launch(MockHostActivity::class.java).use {
            kit.enable()
            withTimeout(10_000) { Wearables.registrationState.first { it == RegistrationState.REGISTERED } }
            kit.permissions.set(Permission.CAMERA, PermissionStatus.Granted)
            val glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
            // A known HEVC file avoids depending on an emulator's missing HEVC camera encoder.
            val video = File(context.cacheDir, "mock-test-pattern-hevc.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("mock-test-pattern-hevc.mp4").use { input ->
                video.outputStream().use { output -> input.copyTo(output) }
            }
            glasses.services.camera.setCameraFeed(Uri.fromFile(video))
            glasses.powerOn(); glasses.unfold(); glasses.don()
            val adapter = MetaDatCameraAdapter(context, scope, simulated = true, firstFrameTimeoutMs = 60_000)
            try {
                adapter.start()
                val firstState = withTimeout(70_000) { adapter.status.first {
                    it.state == MetaSourceState.RAW_FRAMES_AVAILABLE || it.state == MetaSourceState.FAILED
                } }
                assertEquals("Official mock stream failed: ${firstState.detail}", MetaSourceState.RAW_FRAMES_AVAILABLE, firstState.state)
                val first = withTimeout(5_000) {
                    var frame = adapter.takeLatest()
                    while (frame == null) { delay(50); frame = adapter.takeLatest() }
                    frame
                }
                assertTrue(first.width > 0 && first.height > 0 && first.bytes.isNotEmpty())
                assertEquals("meta-dat-mock", first.source)
                assertFalse(first.captureClockSynchronized)
                val second = withTimeout(10_000) {
                    var frame = adapter.takeLatest()
                    while (frame == null || frame.presentationTimeUs <= first.presentationTimeUs) {
                        delay(50); frame = adapter.takeLatest()
                    }
                    frame
                }
                assertTrue(second.presentationTimeUs > first.presentationTimeUs)
                adapter.stop()
                assertEquals(MetaSourceState.STOPPED, adapter.status.value.state)
                assertEquals(null, adapter.takeLatest())
            } finally { adapter.stop(); kit.unpairDevice(glasses) }
        }
    }
}
