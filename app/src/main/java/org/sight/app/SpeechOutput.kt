package org.sight.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.sight.core.Announcement
import org.sight.core.SpeechPriority

/** All admission and dispatch happens on main, including a final evidence check at speech start. */
class SpeechOutput(context: Context, private val valid: (Announcement, Long) -> Boolean,
                   private val stillSupported: (Announcement, Long) -> Boolean,
                   private val onStatus: (String) -> Unit,
                   private val onDispatch: (Announcement, Long) -> Unit,
                   private val onStart: (Announcement, Long) -> Unit = { _, _ -> },
                   private val onDecision: (SpeechDecision) -> Unit = {}) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = SpeechQueue(onDecision = onDecision)
    private var ready = false
    private var closed = false
    var muted = false
        set(value) { field = value; if (value) stop() }
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status -> handler.post {
            if (closed) return@post
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@post
                val voice = engine.voices?.firstOrNull {
                    it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
                }
                if (voice == null) {
                    onStatus("No offline English voice is installed. Install a local voice in Android text-to-speech settings.")
                } else {
                    if (engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                        onStatus("The installed offline voice could not start. Check Android text-to-speech settings.")
                        return@post
                    }
                    engine.setSpeechRate(1f)
                    engine.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    ready = true
                    onStatus("Offline speech ready")
                    pump()
                }
            } else onStatus("Speech is unavailable. Check Android text-to-speech settings.")
        } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val callbackAt = SystemClock.elapsedRealtime()
                handler.post {
                if (closed) return@post
                val token = utteranceId?.toLongOrNull() ?: return@post
                val item = queue.current?.takeIf { it.token == token } ?: return@post
                val now = SystemClock.elapsedRealtime()
                when (queue.started(token, now, valid)) {
                    SpeechStartResult.ACCEPTED -> onStart(item.event, callbackAt)
                    SpeechStartResult.REJECTED -> { tts?.stop(); pump() }
                    SpeechStartResult.IGNORED -> Unit
                }
            } }
            override fun onDone(utteranceId: String?) = finish(utteranceId, false)
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) = finish(utteranceId, true)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, true)
            private fun finish(id: String?, error: Boolean) { handler.post {
                if (closed) return@post
                val token = id?.toLongOrNull() ?: return@post
                if (queue.finish(token, SystemClock.elapsedRealtime(), error)) {
                    if (error) onStatus("Speech output failed")
                    pump()
                }
            } }
        })
    }

    fun offer(event: Announcement) {
        offer(event, bypassDedupe = false)
    }

    private fun offer(event: Announcement, bypassDedupe: Boolean) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (muted) { queue.rejectedMuted(event, now); return }
        val result = queue.offer(event, now, valid, bypassDedupe)
        if (result.interrupted) tts?.stop()
        pump()
    }

    private fun pump() {
        if (closed || !ready || muted || queue.current != null) return
        while (queue.pendingCount > 0) {
            val now = SystemClock.elapsedRealtime()
            val next = queue.next(now, valid) ?: return
            val result = tts?.speak(next.event.text, TextToSpeech.QUEUE_FLUSH, null, next.token.toString()) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                queue.finish(next.token, now, error = true)
                onStatus("Speech output failed")
            } else { onDispatch(next.event, now); return }
        }
    }

    fun tick(now: Long) {
        if (closed) return
        if (queue.tick(now, valid, stillSupported)) tts?.stop()
        pump()
    }
    fun repeat(): Boolean {
        val event = queue.lastStarted ?: return false
        if (closed || muted) return false
        if (!valid(event, SystemClock.elapsedRealtime())) return false
        offer(event.copy(priority = SpeechPriority.USER_REQUEST), bypassDedupe = true)
        return true
    }
    fun rate(value: Float) { tts?.setSpeechRate(value.coerceIn(0.65f, 1.6f)) }
    fun stop() { queue.stop(SystemClock.elapsedRealtime()); tts?.stop() }
    val queueSize: Int get() = queue.size
    override fun close() {
        if (closed) return
        closed = true
        stop(); tts?.shutdown(); tts = null; ready = false
    }
}
