package org.sight.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Exercises CameraX -> real native inference -> core -> diagnostic UI on a device/emulator. */
class CameraFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun continuousCameraProducesFreshFramesAndRecoversAfterPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("pm grant org.sight.app android.permission.CAMERA")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        rule.waitForIdle()
        if (rule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Start camera").performScrollTo().performClick()
        rule.onNodeWithText("Developer").performClick()
        rule.waitUntil(60_000) { frameNumber() >= 2 }
        rule.waitUntil(30_000) { freshSceneAccepted() }
        val first = frameNumber()
        rule.waitUntil(30_000) { frameNumber() > first }
        assertTrue("Actual detector frame updates reached the UI", frameNumber() > first)
        // Capture real Android stage/counter evidence, with enough warm frames for P95.
        rule.waitUntil(90_000) {
            var enough = false
            rule.activityRule.scenario.onActivity { activity ->
                enough = (ViewModelProvider(activity)[SightSession::class.java].controller.timing?.analyzed ?: 0) >= 30
            }
            enough
        }
        var generation = -1L
        rule.activityRule.scenario.onActivity { activity ->
            val controller = ViewModelProvider(activity)[SightSession::class.java].controller
            generation = controller.cameraGeneration
            File(activity.cacheDir,"camera-runtime-v3.json").writeText(controller.exportDiagnostics())
        }
        // Switching destinations must not recreate the preview source or restart its model.
        listOf("Memory", "Settings", "Routes", "Developer").forEach { destination ->
            rule.onNodeWithText(destination).performClick()
            rule.activityRule.scenario.onActivity { activity ->
                val controller = ViewModelProvider(activity)[SightSession::class.java].controller
                assertTrue(controller.running)
                org.junit.Assert.assertEquals(generation,controller.cameraGeneration)
            }
        }

        rule.onNodeWithText("Assist").performClick()
        rule.onNodeWithText("Pause camera").performScrollTo().performClick()
        rule.onNodeWithText("Start camera").assertExists()
        rule.onNodeWithText("Start camera").performScrollTo().performClick()
        rule.onNodeWithText("Developer").performClick()
        // Session IDs may restart. Observe a change and continued updates rather than asserting old track identity.
        val afterRestart = frameNumber()
        rule.waitUntil(60_000) { frameNumber() != afterRestart && frameNumber() >= 2 }
        val next = frameNumber()
        rule.waitUntil(30_000) { frameNumber() > next }
        rule.waitUntil(30_000) { freshSceneAccepted() }
        rule.onNodeWithText("Assist").performClick()
        rule.onNodeWithText("Pause camera").performScrollTo().performClick()
    }

    private fun frameNumber(): Long = rule.onAllNodesWithText("Frame ", substring = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .mapNotNull { Regex("Frame (\\d+)").find(it.text)?.groupValues?.get(1)?.toLongOrNull() }
        .maxOrNull() ?: -1

    private fun freshSceneAccepted(): Boolean =
        rule.onAllNodesWithText("Reliability: OPERATIONAL").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Reliability: REDUCED_CAPABILITY").fetchSemanticsNodes().isNotEmpty()
}
