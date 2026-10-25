package org.sight.core

import org.junit.Assert.*
import org.junit.Test

class AdaptiveSchedulerTest {
    @Test fun `scheduler rates limit detection and expensive OCR separately`() {
        val scheduler = AdaptiveScheduler()
        assertEquals(InferencePlan(true, false), scheduler.plan(0, GoalType.EXPLORE))
        assertEquals(InferencePlan(false, true), scheduler.plan(100, GoalType.READ))
        assertEquals(InferencePlan(false, false), scheduler.plan(200, GoalType.READ))
        assertEquals(InferencePlan(true, false), scheduler.plan(250, GoalType.READ))
        assertEquals(InferencePlan(true, true), scheduler.plan(1_100, GoalType.READ))
    }

    @Test fun `thermal throttling reduces load and critical admits no inference`() {
        val scheduler = AdaptiveScheduler()
        assertTrue(scheduler.shouldAnalyze(0, Workload.DETECTION))
        assertFalse(scheduler.shouldAnalyze(250, Workload.DETECTION, ThermalLevel.HOT))
        assertTrue(scheduler.shouldAnalyze(1_000, Workload.DETECTION, ThermalLevel.HOT))
        assertEquals(InferencePlan(false, false), scheduler.plan(10_000, GoalType.READ, ThermalLevel.CRITICAL))
        assertTrue(scheduler.shouldAnalyze(10_000, Workload.DETECTION))
    }

    @Test fun `latest-frame slot drops superseded frames and closes resources exactly once`() {
        val dropped = mutableListOf<Int>()
        val slot = LatestFrameSlot<Int> { dropped += it }
        slot.offer(1)
        slot.offer(2)
        slot.offer(3)
        assertEquals(listOf(1, 2), dropped)
        assertEquals(3, slot.poll())
        assertNull(slot.poll())
        slot.offer(4)
        slot.close()
        slot.close()
        slot.offer(5)
        assertEquals(listOf(1, 2, 4, 5), dropped)
        assertNull(slot.poll())
    }
}
