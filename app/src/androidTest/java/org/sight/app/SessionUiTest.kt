package org.sight.app

import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.sight.core.GoalType

/** Checks lifecycle and semantics on the actual Activity, independent of detector accuracy. */
class SessionUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun recreationRetainsTaskAndPreferencesWithoutStartingSensors() {
        dismissIntroduction()
        lateinit var before: SightSession
        rule.activityRule.scenario.onActivity { activity ->
            before = ViewModelProvider(activity)[SightSession::class.java]
            before.controller.chooseDetectorMode(DetectorMode.INSPECT)
            before.controller.changeMuted(true)
            before.controller.command("find chair")
            before.navigation.changeConsent(true)
        }
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { activity ->
            val after = ViewModelProvider(activity)[SightSession::class.java]
            assertSame("Activity recreation retains the entire ephemeral session", before, after)
            assertEquals(DetectorMode.INSPECT, after.controller.detectorMode)
            assertEquals(GoalType.FIND, after.controller.scene.goal.type)
            assertEquals("chair", after.controller.scene.goal.target)
            assertTrue(after.controller.muted)
            assertTrue(after.navigation.consent)
            assertFalse("Recreation must not start the camera", after.controller.running)
            assertFalse("Recreation must not start the microphone", after.controller.listening)
            assertFalse("Recreation must not start GPS", after.navigation.gpsActive)
        }
        rule.onNodeWithTag("sensor-status").assertTextEquals("Camera off · Microphone off")
        rule.onNodeWithText("Start camera").performScrollTo().assertIsDisplayed()
    }

    @Test fun everyDestinationExposesSensorStateAndIndependentScrollPosition() {
        dismissIntroduction()
        rule.onNodeWithText("Ask SIGHT").performScrollTo().assertIsDisplayed()
        val assistScroll = verticalScrollPosition()
        assertTrue("Assist has actually scrolled", assistScroll > 0f)
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithTag("sensor-status").assertIsDisplayed().assertTextEquals("Camera off · Microphone off")
        assertEquals("An unvisited destination begins at its own top", 0f, verticalScrollPosition())
        rule.onNodeWithText("Current limits").performScrollTo()
        assertTrue(verticalScrollPosition() > 0f)
        rule.onNodeWithText("Assist").performClick()
        assertEquals("Returning restores Assist independently", assistScroll, verticalScrollPosition())
        listOf("Routes", "Memory", "Developer").forEach { destination ->
            rule.onNodeWithText(destination).performClick()
            rule.onNodeWithTag("sensor-status").assertIsDisplayed().assertTextEquals("Camera off · Microphone off")
            rule.onNodeWithText(destination).assertIsSelected()
        }
    }

    @Test fun doubleFontScaleKeepsPrimaryActionsAndModelSelectionReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalScale = Settings.System.getFloat(instrumentation.targetContext.contentResolver, Settings.System.FONT_SCALE, 1f)
        try {
            shell("settings put system font_scale 2.0")
            rule.activityRule.scenario.recreate()
            dismissIntroduction()
            rule.onNodeWithTag("sensor-status").assertIsDisplayed()
            rule.onNodeWithText("Start camera").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
            rule.onNodeWithText("Read text").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            rule.onNodeWithText("SIGHT").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Inspect").performScrollTo().performClick()
            rule.onNodeWithText("Inspect · selected").performScrollTo().assertIsDisplayed().assertIsSelected()
            rule.activityRule.scenario.onActivity { activity ->
                assertEquals(DetectorMode.INSPECT, ViewModelProvider(activity)[SightSession::class.java].controller.detectorMode)
            }
            rule.onNodeWithText("Developer").assertIsDisplayed().performClick()
            rule.onNodeWithTag("sensor-status").assertIsDisplayed()
        } finally {
            shell("settings put system font_scale $originalScale")
            rule.activityRule.scenario.recreate()
        }
    }

    private fun verticalScrollPosition(): Float {
        rule.waitForIdle()
        return rule.onNodeWithTag("tab-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    }

    private fun dismissIntroduction() {
        rule.waitForIdle()
        if (rule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Continue").performClick()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
}
