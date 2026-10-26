package org.sight.app

import org.junit.Assert.*
import org.junit.Test
import org.sight.core.*
import org.sight.perception.DetectorProfile

class PerceptionPolicyTest {
    private val box = Box(0.1f, 0.2f, 0.3f, 0.6f)

    @Test fun `completed reading stops scheduling OCR rather than repeatedly producing rejected callbacks`() {
        val engine = SightEngine()
        val scheduler = AdaptiveScheduler()
        val searching = engine.command("read", 0)
        assertTrue(scheduler.plan(0, PerceptionPolicy.operationalGoal(searching.goal)).runOcr)
        val completed = engine.onFrame(FrameObservation(Evidence(1, 100), emptyList(), listOf(TextObservation("EXIT", box))), 100)
        assertEquals(GoalPhase.COMPLETED, completed.goal.phase)
        assertEquals(GoalType.IDLE, PerceptionPolicy.operationalGoal(completed.goal))
        assertFalse(scheduler.plan(1_100, PerceptionPolicy.operationalGoal(completed.goal)).runOcr)
    }

    @Test fun `cancelled reading stops OCR and an explicit resume can schedule it again`() {
        val engine = SightEngine()
        val scheduler = AdaptiveScheduler()
        engine.command("read", 0)
        scheduler.plan(0, GoalType.READ)
        val cancelled = engine.command("cancel", 100)
        assertEquals(GoalType.IDLE, PerceptionPolicy.operationalGoal(cancelled.goal))
        assertFalse(scheduler.plan(1_100, PerceptionPolicy.operationalGoal(cancelled.goal)).runOcr)
        val resumed = engine.command("resume", 1_200)
        assertTrue(scheduler.plan(1_200, PerceptionPolicy.operationalGoal(resumed.goal)).runOcr)
    }

    @Test fun `cancelled and timed-out searches return automatic model and cadence to fast idle policy`() {
        val engine = SightEngine(config = EngineConfig(findTimeoutMs = 1_000))
        engine.command("find chair", 0)
        val cancelled = engine.command("cancel", 100)
        val idle = PerceptionPolicy.operationalGoal(cancelled.goal)
        assertEquals(GoalType.IDLE, idle)
        assertEquals(DetectorProfile.FAST, PerceptionPolicy.detectorProfile(DetectorMode.AUTO, idle, 6_000, 100))
        val scheduler = AdaptiveScheduler()
        scheduler.plan(0, GoalType.FIND)
        assertFalse(scheduler.plan(150, idle).runDetection)
        assertTrue(scheduler.plan(250, idle).runDetection)
        engine.command("find chair", 300)
        val timeout = engine.tick(1_300)
        assertEquals(GoalPhase.TIMED_OUT, timeout.goal.phase)
        assertEquals(DetectorProfile.FAST, PerceptionPolicy.detectorProfile(DetectorMode.AUTO,
            PerceptionPolicy.operationalGoal(timeout.goal), 6_000, 1_300))
    }

    @Test fun `completed find keeps monitoring while unsupported and paused goals do not request detailed automatic inference`() {
        val engine = SightEngine()
        engine.command("find chair", 0)
        repeat(2) { index -> engine.onFrame(FrameObservation(Evidence(index + 1L, (index + 1) * 100L), listOf(Detection("chair", 0.9f, box))), (index + 1) * 100L) }
        val completed = engine.tick(200)
        assertEquals(GoalPhase.COMPLETED, completed.goal.phase)
        assertEquals(GoalType.FIND, PerceptionPolicy.operationalGoal(completed.goal))
        assertEquals(DetectorProfile.INSPECT, PerceptionPolicy.detectorProfile(DetectorMode.AUTO,
            PerceptionPolicy.operationalGoal(completed.goal), 0, 200))
        val paused = engine.command("pause", 250)
        assertEquals(GoalType.IDLE, PerceptionPolicy.operationalGoal(paused.goal))
        val unsupported = engine.command("find stairs", 300)
        assertEquals(GoalType.IDLE, PerceptionPolicy.operationalGoal(unsupported.goal))
        assertEquals(DetectorProfile.INSPECT, PerceptionPolicy.detectorProfile(DetectorMode.INSPECT, GoalType.IDLE, 0, 300))
        assertEquals(DetectorProfile.FAST, PerceptionPolicy.detectorProfile(DetectorMode.FAST, GoalType.FIND, 6_000, 300))
    }

    @Test fun `current pending OCR failure may speak status without falsely adopting detector model provenance`() {
        val engine = SightEngine()
        val searching = engine.command("read", 0)
        engine.onFrame(FrameObservation(Evidence(1, 100, "phone/1", modelId = "detector", geometryEpoch = 2), emptyList()), 100)
        val failure = Evidence(1, 100, "phone/1", modelId = "mlkit-latin-16.0.1", geometryEpoch = 2)
        assertTrue(PerceptionPolicy.canReportOcrFailure(searching.goal, true, "phone/1", 2, failure, 200))
        assertFalse(engine.isAnnouncementValid(Announcement("OCR task failed", SpeechPriority.TASK, failure, AnnouncementKind.UNCERTAINTY), 200))
        assertTrue(engine.isAnnouncementValid(Announcement("OCR task failed", SpeechPriority.TASK, kind = AnnouncementKind.STATUS), 200))
        assertTrue(PerceptionPolicy.canReportOcrFailure(searching.goal, true, "phone/1", 2, failure, 2_500))
    }

    @Test fun `OCR failure from old request session geometry future or expired capture cannot enter current task feedback`() {
        val goal = GoalState(GoalType.READ, GoalPhase.SEARCHING, startedAtMs = 200)
        val evidence = Evidence(1, 250, "phone/2", modelId = "ocr", geometryEpoch = 3)
        assertTrue(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/2", 3, evidence, 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/1", 3, evidence, 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/2", 2, evidence, 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/2", 3, evidence.copy(capturedAtMs = 100), 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/2", 3, evidence, 8_251))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, true, "phone/2", 3, evidence, 200))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal.copy(phase = GoalPhase.COMPLETED), true, "phone/2", 3, evidence, 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal, false, "phone/2", 3, evidence, 300))
        assertFalse(PerceptionPolicy.canReportOcrFailure(goal.copy(deadlineAtMs = 300), true, "phone/2", 3, evidence, 300))
    }

    @Test fun `all supported deletion aliases reach persistent deletion before named parsing`() {
        listOf("forget everything", "forget all", "delete memories", "clear memory", "delete all memories", "  DELETE   MEMORIES?! ")
            .forEach { assertEquals(it, MemoryDeletion.All, PerceptionPolicy.memoryDeletion(it)) }
        assertEquals(MemoryDeletion.Named("my desk"), PerceptionPolicy.memoryDeletion("  FORGET   MY DESK? "))
        assertEquals(MemoryDeletion.Named("alligator"), PerceptionPolicy.memoryDeletion("forget alligator"))
        assertNull(PerceptionPolicy.memoryDeletion("remember this as a desk"))
        assertNull(PerceptionPolicy.memoryDeletion("forget"))
    }
}
