package org.sight.app

import android.content.Context
import org.vosk.Recognizer
import java.util.concurrent.CancellationException

/** Shared real ASR backend for microphone capture and recorded-audio integration checks. */
class LocalVoiceDecoder(context:Context,private val cancelled:()->Boolean={false}):AutoCloseable {
    private val model=BundledVoiceModel.open(context,cancelled)
    private var closed=false
    private fun ensureOpen(){ check(!closed) { "Voice decoder is closed" }; if(cancelled()) throw CancellationException("Voice request cancelled") }
    /** The caller closes the recognizer before closing this model owner. */
    @Synchronized fun recognizer(sampleRate:Int):Recognizer {
        ensureOpen()
        require(sampleRate in 8_000..48_000)
        return Recognizer(model,sampleRate.toFloat())
    }
    @Synchronized fun decode(samples:ShortArray,sampleRate:Int):String {
        ensureOpen()
        require(samples.size<=sampleRate*30) { "Recorded-audio check is limited to 30 seconds" }
        val phrases=mutableListOf<String>()
        recognizer(sampleRate).use { recognizer ->
            var offset=0
            while(offset<samples.size) {
                ensureOpen()
                val end=minOf(offset+4_000,samples.size)
                val chunk=samples.copyOfRange(offset,end)
                if(recognizer.acceptWaveForm(chunk,chunk.size)) VoiceTranscript.finalText(recognizer.result)?.let(phrases::add)
                offset=end
            }
            ensureOpen()
            VoiceTranscript.finalText(recognizer.finalResult)?.let(phrases::add)
        }
        return phrases.joinToString(" ")
    }
    @Synchronized override fun close(){ if(!closed){ closed=true; model.close() } }
}
