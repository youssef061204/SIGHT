package org.sight.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** No fallback to a network recognizer: voice requires platform on-device support. */
class OfflineVoiceInput(private val context: Context, private val onText: (String) -> Unit,
                        private val onState: (Boolean, String) -> Unit) : AutoCloseable {
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onState(false, "On-device voice recognition is unavailable on this phone. Enter a command below.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onState(true, "Listening on device") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { onState(false, "Processing voice command") }
                override fun onError(error: Int) { onState(false, "Voice recognition could not finish (code $error). Try again or type a command.") }
                override fun onResults(results: Bundle?) {
                    onState(false, "Voice command received")
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        onState(true, "Starting on-device microphone")
        try {
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (error: Exception) { onState(false, "Microphone unavailable: ${error.javaClass.simpleName}") }
    }
    fun cancel() { recognizer?.cancel(); onState(false, "Microphone stopped") }
    override fun close() { recognizer?.destroy(); recognizer = null }
}
