package org.sight.app

import org.sight.core.Announcement
import org.sight.core.SpeechPriority

enum class SpeechDecisionCode {
    ENQUEUED, DUPLICATE, COOLDOWN, STALE, MUTED, EVICTED, SUPERSEDED, INTERRUPTED,
    DISPATCHED, STARTED, START_REJECTED, COMPLETED, OUTPUT_ERROR, STOPPED,
}
data class SpeechDecision(val code: SpeechDecisionCode, val atMs: Long, val event: Announcement? = null,
    val pendingCount: Int = 0, val active: Boolean = false)
data class SpeechItem(val token: Long, val event: Announcement, val dispatchedAtMs: Long, val startedAtMs: Long? = null)
data class SpeechOfferResult(val accepted: Boolean, val interrupted: Boolean = false)
enum class SpeechStartResult { ACCEPTED, REJECTED, IGNORED }

/** Pure queue policy. Android speech callbacks and clock readings remain in SpeechOutput. */
class SpeechQueue(
    private val capacity: Int = 3,
    private val duplicateWindowMs: Long = 4_000,
    private val ambientCooldownMs: Long = 1_500,
    private val onDecision: (SpeechDecision) -> Unit = {},
) {
    private data class Pending(val event: Announcement, val order: Long)
    private val pending = mutableListOf<Pending>()
    private val spoken = linkedMapOf<String, Long>()
    private var sequence = 0L
    private var nextToken = 0L
    private var lastAmbientStartMs: Long? = null
    var current: SpeechItem? = null
        private set
    var lastStarted: Announcement? = null
        private set
    val pendingCount: Int get() = pending.size
    val size: Int get() = pending.size + if (current != null) 1 else 0

    init { require(capacity in 1..20 && duplicateWindowMs >= 0 && ambientCooldownMs >= 0) }

    fun offer(event: Announcement, nowMs: Long, valid: (Announcement, Long) -> Boolean,
              bypassDedupe: Boolean = false): SpeechOfferResult {
        require(nowMs >= 0)
        if (!valid(event, nowMs)) { emit(SpeechDecisionCode.STALE, nowMs, event); return SpeechOfferResult(false) }
        spoken.entries.removeAll { nowMs - it.value >= duplicateWindowMs }
        if (!bypassDedupe && (current?.event?.dedupKey == event.dedupKey || pending.any { it.event.dedupKey == event.dedupKey } ||
                spoken[event.dedupKey]?.let { nowMs >= it && nowMs - it < duplicateWindowMs } == true)) {
            emit(SpeechDecisionCode.DUPLICATE, nowMs, event); return SpeechOfferResult(false)
        }
        if (!bypassDedupe && event.priority == SpeechPriority.AMBIENT &&
            lastAmbientStartMs?.let { nowMs >= it && nowMs - it < ambientCooldownMs } == true) {
            emit(SpeechDecisionCode.COOLDOWN, nowMs, event); return SpeechOfferResult(false)
        }
        val active = current
        val interrupt = active != null && (event.priority > active.event.priority ||
            event.priority >= SpeechPriority.USER_REQUEST && event.priority == active.event.priority)
        if (interrupt) {
            current = null
            emit(SpeechDecisionCode.INTERRUPTED, nowMs, active!!.event)
        }
        if (event.priority >= SpeechPriority.USER_REQUEST) {
            val removed = pending.toList(); pending.clear()
            removed.forEach { emit(SpeechDecisionCode.SUPERSEDED, nowMs, it.event) }
        } else if (event.priority == SpeechPriority.TASK) {
            val ambient = pending.filter { it.event.priority == SpeechPriority.AMBIENT }
            pending.removeAll(ambient.toSet())
            ambient.forEach { emit(SpeechDecisionCode.SUPERSEDED, nowMs, it.event) }
        }
        if (pending.size == capacity) {
            val lowest = pending.minWith(compareBy<Pending> { it.event.priority }.thenBy { it.order })
            if (event.priority < lowest.event.priority) {
                emit(SpeechDecisionCode.EVICTED, nowMs, event)
                return SpeechOfferResult(false, interrupt)
            }
            pending.remove(lowest)
            emit(SpeechDecisionCode.EVICTED, nowMs, lowest.event)
        }
        pending += Pending(event, sequence++)
        emit(SpeechDecisionCode.ENQUEUED, nowMs, event)
        return SpeechOfferResult(true, interrupt)
    }

    fun next(nowMs: Long, valid: (Announcement, Long) -> Boolean): SpeechItem? {
        if (current != null) return null
        while (pending.isNotEmpty()) {
            val next = pending.minWith(compareByDescending<Pending> { it.event.priority }.thenBy { it.order })
            pending.remove(next)
            if (!valid(next.event, nowMs)) { emit(SpeechDecisionCode.STALE, nowMs, next.event); continue }
            val item = SpeechItem(++nextToken, next.event, nowMs)
            current = item
            emit(SpeechDecisionCode.DISPATCHED, nowMs, next.event)
            return item
        }
        return null
    }

    fun started(token: Long, nowMs: Long, valid: (Announcement, Long) -> Boolean): SpeechStartResult {
        val item = current?.takeIf { it.token == token && it.startedAtMs == null } ?: return SpeechStartResult.IGNORED
        if (!valid(item.event, nowMs)) {
            current = null
            emit(SpeechDecisionCode.START_REJECTED, nowMs, item.event)
            return SpeechStartResult.REJECTED
        }
        current = item.copy(startedAtMs = nowMs)
        lastStarted = item.event
        spoken[item.event.dedupKey] = nowMs
        while (spoken.size > 256) spoken.remove(spoken.keys.first())
        if (item.event.priority == SpeechPriority.AMBIENT) lastAmbientStartMs = nowMs
        emit(SpeechDecisionCode.STARTED, nowMs, item.event)
        return SpeechStartResult.ACCEPTED
    }

    fun finish(token: Long, nowMs: Long, error: Boolean = false): Boolean {
        val item = current?.takeIf { it.token == token } ?: return false
        current = null
        emit(if (error) SpeechDecisionCode.OUTPUT_ERROR else SpeechDecisionCode.COMPLETED, nowMs, item.event)
        return true
    }

    /** Before onStart use full admission; a started captured-text reading can use its snapshot policy. */
    fun tick(nowMs: Long, valid: (Announcement, Long) -> Boolean,
             stillSupported: (Announcement, Long) -> Boolean): Boolean {
        val item = current
        val interrupt = item != null && !(if (item.startedAtMs == null) valid(item.event, nowMs) else stillSupported(item.event, nowMs))
        if (interrupt) { current = null; emit(SpeechDecisionCode.INTERRUPTED, nowMs, item!!.event) }
        val expired = pending.filter { !valid(it.event, nowMs) }
        pending.removeAll(expired.toSet())
        expired.forEach { emit(SpeechDecisionCode.STALE, nowMs, it.event) }
        return interrupt
    }

    fun stop(nowMs: Long) {
        val event = current?.event
        pending.clear(); current = null
        emit(SpeechDecisionCode.STOPPED, nowMs, event)
    }

    fun clearHistory(nowMs: Long) {
        stop(nowMs)
        lastStarted = null; spoken.clear(); lastAmbientStartMs = null
    }

    fun rejectedMuted(event: Announcement, nowMs: Long) = emit(SpeechDecisionCode.MUTED, nowMs, event)
    private fun emit(code: SpeechDecisionCode, nowMs: Long, event: Announcement?) = onDecision(SpeechDecision(code, nowMs, event, pending.size, current != null))
}
