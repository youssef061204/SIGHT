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
                   private val onDispatch: (Announcement, Long) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Announcement>()
    private var ready = false
    private var current: Announcement? = null
    private var last: Announcement? = null
    private var generation = 0L
    var muted = false
        set(value) { field = value; if (value) stop() }
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status -> handler.post {
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
            override fun onStart(utteranceId: String?) { handler.post {
                if (utteranceId == generation.toString() && current?.let { !valid(it, SystemClock.elapsedRealtime()) } == true) stop()
            } }
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) = finish(utteranceId)
            private fun finish(id: String?) { handler.post {
                if (id == generation.toString()) { current = null; pump() }
            } }
        })
    }

    fun offer(event: Announcement) {
        if (muted || !valid(event, SystemClock.elapsedRealtime())) return
        if (event.priority >= SpeechPriority.USER_REQUEST) {
            stop()
        }
        pending.removeAll { it.dedupKey == event.dedupKey }
        if (pending.size == 3) pending.removeFirst()
        pending.addLast(event)
        pump()
    }

    private fun pump() {
        if (!ready || muted || current != null) return
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val now = SystemClock.elapsedRealtime()
            if (!valid(next, now)) continue
            current = next
            last = next
            generation++
            val result = tts?.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, generation.toString())
            if (result == TextToSpeech.ERROR) { current = null; onStatus("Speech output failed") }
            else onDispatch(next, now)
            break
        }
    }

    fun tick(now: Long) {
        if (current?.let { !stillSupported(it, now) } == true) { stop(); return }
        pending.removeAll { !valid(it, now) }
        pump()
    }
    fun repeat(): Boolean {
        val event = last ?: return false
        if (!valid(event, SystemClock.elapsedRealtime())) return false
        offer(event)
        return true
    }
    fun rate(value: Float) { tts?.setSpeechRate(value.coerceIn(0.65f, 1.6f)) }
    fun stop() { generation++; pending.clear(); current = null; tts?.stop() }
    val queueSize: Int get() = pending.size + if (current != null) 1 else 0
    override fun close() { stop(); tts?.shutdown(); tts = null; ready = false }
}
