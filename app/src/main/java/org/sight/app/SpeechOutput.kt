package org.sight.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.sight.core.Announcement
import org.sight.core.SpeechPriority
import org.sight.core.SpeechSafety

/** All admission and dispatch happens on main, including a final evidence check at speech start. */
class SpeechOutput(context: Context, private val valid: (Announcement, Long) -> Boolean,
                   private val stillSupported: (Announcement, Long) -> Boolean,
                   private val onStatus: (String) -> Unit,
                   private val onDispatch: (Announcement, Long) -> Unit,
                   private val onStart: (Announcement, Long) -> Unit = { _, _ -> },
                   private val onDecision: (SpeechDecision) -> Unit = {},
                   private val onCompleted: (Announcement) -> Unit = {}) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var focused=false
    private var retryFocusAtMs=0L
    private val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if(!closed) {
                if(change==AudioManager.AUDIOFOCUS_GAIN){ focused=true; retryFocusAtMs=0; pump() }
                else if(change<0) {
                    val resume=queue.current?.event?.takeIf { change!=AudioManager.AUDIOFOCUS_LOSS && it.priority==SpeechPriority.NAVIGATION }
                    focused=false; retryFocusAtMs=SystemClock.elapsedRealtime()+5_000
                    queue.stop(SystemClock.elapsedRealtime()); tts?.stop()
                    if(resume!=null) queue.offer(resume,SystemClock.elapsedRealtime(),::admitted,bypassDedupe=true)
                    onStatus("Audio interrupted. Route cues must still be fresh before speech resumes.")
                }
            }
        },handler).build()
    private val queue = SpeechQueue(onDecision = onDecision)
    private var ready = false
    private var closed = false
    var muted = false
        set(value) { field = value; if (value) stop() }
    private var tts: TextToSpeech? = null
    private fun admitted(event: Announcement, at: Long) = SpeechSafety.allows(event, at) && valid(event, at)

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
                    engine.setAudioAttributes(attributes)
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
                when (queue.started(token, now, ::admitted)) {
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
                val completed = queue.current?.takeIf { it.token == token }?.event
                if (queue.finish(token, SystemClock.elapsedRealtime(), error)) {
                    if (error) onStatus("Speech output failed")
                    else if (completed != null) onCompleted(completed)
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
        if(!SpeechSafety.timely(event,now)) return
        val safe = if (!SpeechSafety.prohibitsClaim(event)) event else Announcement(
            "I cannot provide crossing authorization or unverified physical clearance. Use your established mobility practices.",
            SpeechPriority.STATUS, kind = org.sight.core.AnnouncementKind.STATUS)
        val result = queue.offer(safe, now, ::admitted, bypassDedupe)
        if (result.interrupted) tts?.stop()
        pump()
    }

    private fun pump() {
        if (closed || !ready || muted || queue.current != null) return
        if(queue.pendingCount==0){ abandonFocus(); return }
        if(!focused) {
            val at=SystemClock.elapsedRealtime()
            if(at<retryFocusAtMs) return
            focused=audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if(!focused){ retryFocusAtMs=at+5_000; onStatus("Audio output is unavailable while another activity has priority. Old directions will not be replayed."); return }
        }
        while (queue.pendingCount > 0) {
            val now = SystemClock.elapsedRealtime()
            val next = queue.next(now, ::admitted) ?: return
            val result = tts?.speak(next.event.text, TextToSpeech.QUEUE_FLUSH, null, next.token.toString()) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                queue.finish(next.token, now, error = true)
                onStatus("Speech output failed")
            } else { onDispatch(next.event, now); return }
        }
    }

    fun tick(now: Long) {
        if (closed) return
        if (queue.tick(now, ::admitted) { event, at -> SpeechSafety.allows(event.copy(validUntilMs=null),at) && stillSupported(event,at) }) tts?.stop()
        pump()
    }
    fun repeat(): Boolean {
        val event = queue.lastStarted ?: return false
        if (closed || muted) return false
        if (!admitted(event, SystemClock.elapsedRealtime())) return false
        offer(event.copy(priority = SpeechPriority.USER_REQUEST), bypassDedupe = true)
        return true
    }
    fun rate(value: Float) { tts?.setSpeechRate(value.coerceIn(0.65f, 1.6f)) }
    private fun abandonFocus(){ if(focused){ focused=false; audioManager.abandonAudioFocusRequest(focusRequest) } }
    fun stop() { queue.stop(SystemClock.elapsedRealtime()); tts?.stop(); abandonFocus() }
    fun clearHistory() { queue.clearHistory(SystemClock.elapsedRealtime()); tts?.stop(); abandonFocus() }
    val queueSize: Int get() = queue.size
    val currentKind:org.sight.core.AnnouncementKind? get()=queue.current?.event?.kind
    override fun close() {
        if (closed) return
        closed = true
        stop(); tts?.shutdown(); tts = null; ready = false
    }
}
