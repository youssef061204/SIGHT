package org.sight.app

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.app.navigation.RouteVoiceAction
import org.sight.core.GoalType
import org.sight.core.GoalPhase
import org.sight.core.SightEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real PCM -> bundled native ASR -> production command parsers. No recognition result is injected. */
@RunWith(AndroidJUnit4::class)
class NativeVoiceRecognitionTest {
    @Test fun bundledRecognizerDecodesDeclaredSyntheticCommandsAndRemainsSilentForSilence(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val assets=instrumentation.context.assets
        val manifest=assets.open("voice/manifest.json").bufferedReader().use { JSONObject(it.readText().removePrefix("\uFEFF")) }
        val cases=JSONArray()
        val proof=JSONObject().put("scope","Native Vosk decoding of three declared synthetic clean single-voice WAV files, then actual command parsing; no microphone or field-recognition claim")
            .put("audioSource",manifest.getString("source")).put("voice",manifest.getString("voice"))
            .put("modelAssetSha256",BundledVoiceModel.SHA256).put("androidApi",Build.VERSION.SDK_INT)
            .put("abi",Build.SUPPORTED_ABIS.first()).put("cases",cases).put("allCasesPassed",false)
            .put("microphoneTested",false).put("networkRecognizerUsed",false)
        val output=File(instrumentation.targetContext.cacheDir,"native-voice-recognition-proof.json")
        fun save(){output.writeText(proof.toString(2))}
        save()
        val started=SystemClock.elapsedRealtime()
        val decoder=LocalVoiceDecoder(instrumentation.targetContext)
        proof.put("modelPreparationMs",SystemClock.elapsedRealtime()-started)
        try {
            val fixtures=manifest.getJSONArray("fixtures")
            for(index in 0 until fixtures.length()) {
                val fixture=fixtures.getJSONObject(index)
                val bytes=assets.open("voice/"+fixture.getString("file")).use { it.readBytes() }
                val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                assertEquals("Canonical audio fixture changed",fixture.getString("sha256"),sha)
                val samples=pcm16(bytes)
                val at=SystemClock.elapsedRealtime()
                val text=decoder.decode(samples,16_000)
                val action=RouteVoiceAction.parse(text)
                val matched=when(fixture.getString("expectedIntent")) {
                    "route-search" -> action is RouteVoiceAction.Search && action.query==fixture.getString("expectedTarget") && !action.start
                    "cancel-route" -> action==RouteVoiceAction.Cancel
                    "find" -> SightEngine().command(text,1_000).goal.let { it.type==GoalType.FIND && it.phase==GoalPhase.SEARCHING && it.target==fixture.getString("expectedTarget") }
                    else -> false
                }
                cases.put(JSONObject().put("file",fixture.getString("file")).put("sha256",sha)
                    .put("referenceText",fixture.getString("referenceText")).put("recognizedText",text)
                    .put("expectedIntent",fixture.getString("expectedIntent")).put("intentMatched",matched)
                    .put("audioMs",samples.size*1_000L/16_000).put("offlineDecodeMs",SystemClock.elapsedRealtime()-at))
                save()
                assertTrue("Actual ASR did not preserve command intent: $text",matched)
            }
            val silence=decoder.decode(ShortArray(16_000),16_000)
            proof.put("oneSecondSyntheticSilenceTranscript",silence)
            assertEquals("Silence must not dispatch a command","",silence)
            proof.put("processPssKb",Debug.getPss())
        } finally { decoder.close(); decoder.close(); save() }
        try { decoder.recognizer(16_000); fail("Closed native model must not create another recognizer") }
        catch(_:IllegalStateException){ proof.put("closedDecoderRejectsReuse",true).put("allCasesPassed",true); save() }
    }

    private fun pcm16(bytes:ByteArray):ShortArray {
        assertEquals("RIFF",String(bytes,0,4,Charsets.US_ASCII))
        assertEquals("WAVE",String(bytes,8,4,Charsets.US_ASCII))
        var offset=12
        var formatValidated=false
        var samples:ShortArray?=null
        while(offset+8<=bytes.size) {
            val size=ByteBuffer.wrap(bytes,offset+4,4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size>=0 && offset.toLong()+8+size<=bytes.size)
            when(String(bytes,offset,4,Charsets.US_ASCII)) {
                "fmt " -> {
                    require(size>=16)
                    val format=ByteBuffer.wrap(bytes,offset+8,size).order(ByteOrder.LITTLE_ENDIAN)
                    require(format.short.toInt()==1 && format.short.toInt()==1 && format.int==16_000)
                    format.int; format.short
                    require(format.short.toInt()==16)
                    formatValidated=true
                }
                "data" -> {
                    require(size%2==0 && size in 2..960_000)
                    val buffer=ByteBuffer.wrap(bytes,offset+8,size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    samples=ShortArray(size/2).also { buffer.get(it) }
                }
            }
            offset+=8+size+size%2
        }
        require(formatValidated)
        return requireNotNull(samples)
    }
}
