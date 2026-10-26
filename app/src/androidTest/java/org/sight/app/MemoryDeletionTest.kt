package org.sight.app

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.core.*

/** Synthetic metadata only; tests actual encrypted store and controller command routing, not vision. */
@RunWith(AndroidJUnit4::class)
class MemoryDeletionTest {
    @Test fun spokenDeletionAliasesRemoveEncryptedAndSessionRecords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val preferences = context.getSharedPreferences("landmarks", Context.MODE_PRIVATE)
        val originalEncryptedRecord = preferences.getString("data", null)
        lateinit var controller: SightController
        var controllerReady = false
        var frameId = 0L
        val source = "synthetic-privacy-regression"
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
        fun freshSyntheticFrame(text: String? = null) {
            onMain {
                val at = SystemClock.elapsedRealtime()
                val evidence = Evidence(++frameId, at, source, modelId = "synthetic-labelled-box-not-inference", geometryEpoch = 1)
                val frame = FrameObservation(evidence, listOf(Detection("chair", 0.9f, Box(0.1f, 0.2f, 0.3f, 0.6f))),
                    text?.let { listOf(TextObservation(it, Box(0.1f, 0.2f, 0.3f, 0.6f))) } ?: emptyList())
                controller.onFrame(frame, StageTiming(frameId, at, 0, 0, 0, 0, 0, frameId, 0,
                    "synthetic monotonic fixture", evidence.modelId, completedAtMs = at, capturedAtMs = at,
                    width = 640, height = 480, geometryEpoch = 1))
            }
            SystemClock.sleep(5) // Distinct captured milliseconds; no detector or camera is invoked.
        }
        try {
            onMain {
                preferences.edit().clear().commit()
                controller = SightController(context)
                controllerReady = true
                controller.changeMuted(true)
                controller.persistentMemory = true
                controller.start()
            }
            val aliases = listOf("forget all", "delete all memories", "forget everything", "delete memories", "clear memory", "DELETE   MEMORIES?!")
            aliases.forEachIndexed { index, alias ->
                freshSyntheticFrame(); freshSyntheticFrame()
                val name = "privacy fixture $index"
                onMain {
                    controller.command("remember this as $name")
                    assertTrue(controller.savedLandmarks.any { it.name == name })
                    assertTrue(LandmarkStore(context).load().any { it.name == name })
                    controller.command(alias)
                    assertTrue("Persistent memory remains after $alias", LandmarkStore(context).load().isEmpty())
                    assertTrue("Cached persistent memory remains after $alias", controller.savedLandmarks.isEmpty())
                    assertTrue("Session memory remains after $alias", controller.scene.memory.isEmpty())
                }
            }
            freshSyntheticFrame(); freshSyntheticFrame()
            onMain {
                controller.command("remember this as privacy named scene")
                assertTrue(LandmarkStore(context).load().any { it.name == "privacy named scene" })
                controller.command("FORGET PRIVACY NAMED SCENE")
                assertTrue(LandmarkStore(context).load().isEmpty())
                assertTrue(controller.scene.memory.isEmpty())
                assertTrue(controller.savedLandmarks.isEmpty())
            }
            val privateText = "PRIVATE OCR RETENTION FIXTURE"
            onMain { controller.command("read") }
            freshSyntheticFrame(privateText)
            onMain {
                assertEquals(privateText, controller.scene.extractedText)
                assertTrue("Fixture must exercise an old text-bearing diagnostic", controller.exportDiagnostics().contains(privateText))
                controller.command("forget everything")
                assertNull(controller.scene.extractedText)
                assertNull(controller.scene.textEvidence)
                assertFalse("Old text remains in exported speech/decision history", controller.exportDiagnostics().contains(privateText))
                controller.command("repeat text")
                assertFalse(controller.lastMessage.contains(privateText))
                assertTrue(controller.lastMessage.contains("No recognized text"))
            }
        } finally {
            onMain {
                if (controllerReady) controller.close()
                // Restore the pre-test encrypted payload; do not export or decode it into a fixture.
                preferences.edit().clear().also { editor -> originalEncryptedRecord?.let { editor.putString("data", it) } }.commit()
            }
        }
    }
}
