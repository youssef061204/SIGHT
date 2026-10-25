package org.sight.meta

import org.junit.Assert.assertEquals
import org.junit.Test

class MetaFrameGateTest {
    @Test fun refusesFramesBeforeStreamingAndAfterPause() {
        val gate = MetaFrameGate()
        gate.begin()
        assertEquals(FrameDecision.SOURCE_INACTIVE, gate.accept(10, 100, 100))
        gate.setStreaming(true)
        assertEquals(FrameDecision.ACCEPT_RECEIPT_ONLY, gate.accept(10, 100, 100))
        gate.setStreaming(false)
        assertEquals(FrameDecision.SOURCE_INACTIVE, gate.accept(11, 101, 101))
    }

    @Test fun rejectsStaleDuplicateAndRegressingFrames() {
        val gate = MetaFrameGate(100)
        gate.begin()
        gate.setStreaming(true)
        assertEquals(FrameDecision.STALE_IN_LOCAL_QUEUE, gate.accept(1, 100, 201))
        assertEquals(FrameDecision.ACCEPT_RECEIPT_ONLY, gate.accept(2, 201, 201))
        assertEquals(FrameDecision.OUT_OF_ORDER, gate.accept(2, 202, 202))
        assertEquals(FrameDecision.OUT_OF_ORDER, gate.accept(1, 203, 203))
        assertEquals(FrameDecision.OUT_OF_ORDER, gate.accept(3, 200, 200))
        assertEquals(FrameDecision.ACCEPT_RECEIPT_ONLY, gate.accept(3, 204, 204))
    }

    @Test fun invalidClockCannotPoisonNextFrame() {
        val gate = MetaFrameGate()
        gate.setStreaming(true)
        assertEquals(FrameDecision.INVALID_CLOCK, gate.accept(-1, 0, 0))
        assertEquals(FrameDecision.INVALID_CLOCK, gate.accept(0, 100, 99))
        assertEquals(FrameDecision.ACCEPT_RECEIPT_ONLY, gate.accept(0, 100, 100))
    }

    @Test fun reconnectResetsPresentationClockButPauseDoesNot() {
        val gate = MetaFrameGate()
        gate.setStreaming(true)
        gate.accept(500, 1000, 1000)
        gate.setStreaming(false)
        gate.setStreaming(true)
        assertEquals(FrameDecision.OUT_OF_ORDER, gate.accept(0, 1001, 1001))
        gate.begin()
        assertEquals(FrameDecision.SOURCE_INACTIVE, gate.accept(0, 1002, 1002))
        gate.setStreaming(true)
        assertEquals(FrameDecision.ACCEPT_RECEIPT_ONLY, gate.accept(0, 1002, 1002))
    }
}
