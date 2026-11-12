package org.sight.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One requested utterance; preferred Android on-device service, bundled Vosk fallback, no network recognizer. */
class OfflineVoiceInput(context:Context,private val onText:(String)->Unit,
                        private val onState:(Boolean,String)->Unit):AutoCloseable {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),ThreadPoolExecutor.DiscardOldestPolicy())
    private var platform:SpeechRecognizer?=null
    private var platformGeneration:Long?=null
    @Volatile private var recorder:AudioRecord?=null
    @Volatile private var closed=false

    fun start() {
        if(closed) return
        cancelCurrent()
        val id=generation.incrementAndGet()
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            onState(false,"Microphone permission is required. Use the microphone permission control or enter a command with TalkBack."); return
        }
        val available=Build.VERSION.SDK_INT>=31 && try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } catch(_:Exception){false}
        if(available) startPlatform(id) else startBundled(id)
    }
    private fun current(id:Long)=!closed && id==generation.get()
    private fun state(id:Long,active:Boolean,text:String){ main.post { if(current(id)) onState(active,text) } }
    private fun deliver(id:Long,text:String?,message:String) {
        if(!current(id)) return
        generation.incrementAndGet()
        platformGeneration=null
        platform?.cancel(); platform?.destroy(); platform=null
        onState(false,message)
        text?.takeIf { it.isNotBlank() && it.length<=300 }?.let(onText)
    }
    private fun startPlatform(id:Long) {
        if(Build.VERSION.SDK_INT<31) { startBundled(id); return }
        try {
            platform=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            platformGeneration=id
            platform!!.apply {
                setRecognitionListener(object:RecognitionListener {
                    override fun onReadyForSpeech(params:Bundle?){ if(current(id) && platformGeneration==id) onState(true,"Listening on device. Say one command.") }
                    override fun onBeginningOfSpeech()=Unit
                    override fun onRmsChanged(rmsdB:Float)=Unit
                    override fun onBufferReceived(buffer:ByteArray?)=Unit
                    override fun onEndOfSpeech(){ if(current(id) && platformGeneration==id) onState(true,"Processing on-device command") }
                    override fun onError(error:Int){
                        if(!current(id) || platformGeneration!=id) return
                        if(error in setOf(SpeechRecognizer.ERROR_SERVER,SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                                SpeechRecognizer.ERROR_CLIENT,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)) {
                            // Invalidate this backend before destroy can issue another error callback.
                            platformGeneration=null
                            platform?.destroy(); platform=null; startBundled(id)
                        } else deliver(id,null,when(error){
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was denied. Grant it or enter a command with TalkBack."
                            SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No clear command was recognized. Press the command control and try again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Stop the other recorder and try again."
                            else -> "On-device recognition could not finish. Try again or enter a command with TalkBack."
                        })
                    }
                    override fun onResults(results:Bundle?){
                        val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        if(platformGeneration==id) deliver(id,text,if(text==null) "No clear command was recognized. Try again." else "Voice command received on device")
                    }
                    override fun onPartialResults(partialResults:Bundle?)=Unit
                    override fun onEvent(eventType:Int,params:Bundle?)=Unit
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
                })
            }
            onState(true,"Starting on-device microphone")
            main.postDelayed({ if(current(id) && platformGeneration==id) deliver(id,null,"Listening timed out. Press the command control to try again.") },15_000)
        } catch(_:Exception){ platformGeneration=null; platform?.destroy(); platform=null; startBundled(id) }
    }
    private fun startBundled(id:Long) {
        if(!current(id)) return
        onState(true,"Preparing bundled offline English recognition")
        executor.execute {
            var audio:AudioRecord?=null
            var outcome:Pair<String?,String>?=null
            try {
                if(!current(id)) return@execute
                LocalVoiceDecoder(context){!current(id)}.use { decoder ->
                    if(!current(id)) return@execute
                    val minimum=AudioRecord.getMinBufferSize(16_000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum>0) { "Microphone format unavailable" }
                    audio=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum,6_400)).build()
                    check(audio!!.state==AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                    recorder=audio
                    if(!current(id)) return@execute
                    decoder.recognizer(16_000).use { recognizer ->
                        audio!!.startRecording()
                        check(audio!!.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
                        state(id,true,"Listening with bundled offline English recognition. Say one command.")
                        val deadline=SystemClock.elapsedRealtime()+15_000
                        val buffer=ShortArray(3_200)
                        var text:String?=null
                        while(current(id) && SystemClock.elapsedRealtime()<deadline) {
                            val count=audio!!.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                            if(count<0) error("Microphone read failed")
                            if(count==0) continue
                            if(recognizer.acceptWaveForm(buffer,count)) {
                                text=VoiceTranscript.finalText(recognizer.result)
                                if(text!=null) break
                            }
                        }
                        if(current(id) && text==null) text=VoiceTranscript.finalText(recognizer.finalResult)
                        if(current(id)) outcome=text to if(text==null) "No clear command was recognized. Press the command control to try again." else "Voice command received with bundled offline recognition"
                    }
                }
            } catch(_:CancellationException){ /* Explicit cancellation never dispatches an old command. */ }
            catch(_:SecurityException){ outcome=null to "Microphone permission was revoked. Grant it or enter a command with TalkBack." }
            catch(_:OutOfMemoryError){ outcome=null to "Not enough memory for offline voice. Close other apps or enter a command with TalkBack." }
            catch(_:LinkageError){ outcome=null to "Bundled voice is unavailable on this architecture. Enter a command with TalkBack." }
            catch(_:Exception){ outcome=null to "Offline voice could not start or finish. Check the microphone and try again, or enter a command with TalkBack." }
            finally {
                try { audio?.stop() } catch(_:Exception) { }
                audio?.release()
                if(recorder===audio) recorder=null
            }
            // Sensor state and a recognized command are delivered only after microphone release.
            outcome?.let { completed -> main.post { deliver(id,completed.first,completed.second) } }
        }
    }
    private fun cancelCurrent(){
        generation.incrementAndGet()
        platformGeneration=null
        platform?.cancel(); platform?.destroy(); platform=null
        try { recorder?.stop() } catch(_:Exception) { }
        executor.queue.clear()
    }
    fun cancel(){ cancelCurrent(); if(!closed) onState(false,"Microphone stopped") }
    override fun close(){ if(closed)return; closed=true; cancelCurrent(); executor.shutdown() }
}
