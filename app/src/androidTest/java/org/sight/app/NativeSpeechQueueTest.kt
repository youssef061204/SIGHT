package org.sight.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.core.Announcement
import org.sight.core.SpeechPriority
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Tests real TTS callbacks; availability and speaker audibility are separate questions. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechQueueTest {
    @Test fun actualOfflineEngineStartsPriorityTaskAfterInterruptingAmbientSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val proof = File(context.cacheDir,"speech-queue-native-v3.json")
        proof.writeText(JSONObject().put("offlineVoiceAvailable",false).put("taskStarted",false)
            .put("reason","Native speech check pending").toString(2))
        val ready = CountDownLatch(1)
        val ambientStarted = CountDownLatch(1)
        val taskStarted = CountDownLatch(1)
        val status = AtomicReference("")
        val decisions = CopyOnWriteArrayList<SpeechDecision>()
        val callbacks = CopyOnWriteArrayList<JSONObject>()
        lateinit var output: SpeechOutput
        instrumentation.runOnMainSync {
            output = SpeechOutput(context, { _, _ -> true }, { _, _ -> true }, {
                status.set(it); ready.countDown()
            }, { event, at -> callbacks.add(JSONObject().put("kind","request").put("text",event.text).put("atMs",at)) },
                { event, at ->
                    callbacks.add(JSONObject().put("kind","engineStart").put("text",event.text).put("atMs",at))
                    if (event.priority == SpeechPriority.AMBIENT) ambientStarted.countDown() else taskStarted.countDown()
                }, { decisions += it })
        }
        try {
            val available = ready.await(40,TimeUnit.SECONDS) && status.get() == "Offline speech ready"
            proof.writeText(JSONObject().put("offlineVoiceAvailable",available).put("taskStarted",false)
                .put("status",status.get()).put("reason","Interruption has not been verified").toString(2))
            assumeTrue("A local Android voice is required to test native speech interruption",available)
            instrumentation.runOnMainSync { output.offer(Announcement(
                "This is an ambient speech queue test. ".repeat(30),SpeechPriority.AMBIENT)) }
            assertTrue(ambientStarted.await(40,TimeUnit.SECONDS))
            instrumentation.runOnMainSync { output.offer(Announcement(
                "SIGHT speech queue test. Higher priority task.",SpeechPriority.TASK)) }
            assertTrue(taskStarted.await(40,TimeUnit.SECONDS))
            assertTrue(decisions.any { it.code == SpeechDecisionCode.INTERRUPTED && it.event?.priority == SpeechPriority.AMBIENT })
            assertEquals(1,decisions.count { it.code == SpeechDecisionCode.STARTED && it.event?.priority == SpeechPriority.TASK })
            proof.writeText(JSONObject().put("offlineVoiceAvailable",true)
                .put("scope","Actual Android offline TTS callbacks and queue interruption; not audible speaker onset")
                .put("status",status.get()).put("recordedAtElapsedMs",SystemClock.elapsedRealtime())
                .put("ambientInterrupted",true).put("taskStarted",true).put("humanHeardAudio",false)
                .put("callbacks",JSONArray(callbacks)).toString(2))
        } finally { instrumentation.runOnMainSync { output.close() } }
    }
}
