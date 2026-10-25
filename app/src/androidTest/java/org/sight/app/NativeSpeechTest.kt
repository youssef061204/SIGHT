package org.sight.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthesizes real local PCM; this does not claim anyone heard an emulator speaker. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechTest {
    @Test fun offlineVoiceProducesNonemptyWaveWhenInstalled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val metadata = File(context.cacheDir, "sight-speech-proof.json")
        val wave = File(context.cacheDir, "sight-speech-proof.wav")
        wave.delete()
        val initialized = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync {
            tts = TextToSpeech(context) { status -> initStatus = status; initialized.countDown() }
        }
        try {
            val ready = initialized.await(40, TimeUnit.SECONDS) && initStatus == TextToSpeech.SUCCESS
            val voice = if (ready) tts.voices?.firstOrNull {
                it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            } else null
            metadata.writeText(JSONObject().put("offlineVoiceAvailable", voice != null).put("synthesized", false)
                .put("reason", if (voice == null) "An offline English Android TTS voice is not installed" else "Synthesis pending").toString(2))
            assumeTrue("No offline English voice is installed; capability unavailable, not a synthesis pass", voice != null)
            val selected = voice ?: return
            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(selected))
            val completed = CountDownLatch(1)
            var failed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { completed.countDown() }
                @Deprecated("Platform callback") override fun onError(id: String?) { failed = true; completed.countDown() }
            })
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(
                "SIGHT is ready. Observations describe the camera view.", Bundle(), wave, "sight-native-proof"))
            assertTrue("Local synthesis did not complete", completed.await(60, TimeUnit.SECONDS))
            assertFalse("Android TTS reported an error", failed)
            val bytes = wave.readBytes()
            assertTrue(bytes.size > 44)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            var index = 12
            var pcmBytes = 0
            while (index + 8 <= bytes.size) {
                val size = ByteBuffer.wrap(bytes, index + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (size < 0 || index.toLong() + 8 + size > bytes.size) break
                if (String(bytes, index, 4, Charsets.US_ASCII) == "data") {
                    pcmBytes = size
                    assertTrue("PCM contains no signal", bytes.copyOfRange(index + 8, index + 8 + size).any { it != 0.toByte() })
                    break
                }
                index += 8 + size + size % 2
            }
            assertTrue("No PCM payload", pcmBytes > 1_000)
            metadata.writeText(JSONObject().put("offlineVoiceAvailable", true).put("synthesized", true)
                .put("voice", selected.name).put("locale", selected.locale.toLanguageTag())
                .put("networkRequired", selected.isNetworkConnectionRequired).put("waveBytes", bytes.size)
                .put("pcmBytes", pcmBytes).put("audioHeardByHuman", false).toString(2))
        } finally { tts.shutdown() }
    }
}
