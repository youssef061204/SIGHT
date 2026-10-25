package org.sight.meta

/** Receiver-clock checks cannot establish the capture age of a remote device's frame. */
class MetaFrameGate(private val maxQueueAgeNs: Long = 750_000_000) {
    init { require(maxQueueAgeNs > 0) }
    private var active = false
    private var lastPtsUs = -1L
    private var lastReceiptNs = -1L

    @Synchronized fun begin() {
        active = false
        lastPtsUs = -1
        lastReceiptNs = -1
    }

    @Synchronized fun setStreaming(value: Boolean) { active = value }

    @Synchronized fun accept(ptsUs: Long, receivedNs: Long, nowNs: Long): FrameDecision {
        if (!active) return FrameDecision.SOURCE_INACTIVE
        if (ptsUs < 0 || receivedNs < 0 || nowNs < receivedNs) return FrameDecision.INVALID_CLOCK
        if (nowNs - receivedNs > maxQueueAgeNs) return FrameDecision.STALE_IN_LOCAL_QUEUE
        if (ptsUs <= lastPtsUs || receivedNs < lastReceiptNs) return FrameDecision.OUT_OF_ORDER
        lastPtsUs = ptsUs
        lastReceiptNs = receivedNs
        return FrameDecision.ACCEPT_RECEIPT_ONLY
    }
}

enum class FrameDecision {
    ACCEPT_RECEIPT_ONLY, SOURCE_INACTIVE, INVALID_CLOCK, STALE_IN_LOCAL_QUEUE, OUT_OF_ORDER,
}
