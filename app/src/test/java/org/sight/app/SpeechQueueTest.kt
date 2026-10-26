package org.sight.app

import org.junit.Assert.*
import org.junit.Test
import org.sight.core.*

class SpeechQueueTest {
    private val accept: (Announcement, Long) -> Boolean = { _, _ -> true }
    private fun event(text: String, priority: SpeechPriority = SpeechPriority.TASK) = Announcement(text, priority, dedupKey = text)

    @Test fun `task speech interrupts ambient and stale callbacks cannot finish the new utterance`() {
        val decisions = mutableListOf<SpeechDecision>()
        val queue = SpeechQueue(onDecision = decisions::add)
        queue.offer(event("ambient", SpeechPriority.AMBIENT), 0, accept)
        val ambient = queue.next(1, accept)!!
        queue.started(ambient.token, 2, accept)
        assertTrue(queue.offer(event("target found"), 3, accept).interrupted)
        val task = queue.next(4, accept)!!
        assertNotEquals(ambient.token, task.token)
        assertFalse(queue.finish(ambient.token, 5))
        assertEquals(task, queue.current)
        assertEquals(SpeechStartResult.IGNORED, queue.started(ambient.token, 6, accept))
        assertTrue(decisions.any { it.code == SpeechDecisionCode.INTERRUPTED && it.event?.text == "ambient" })
    }

    @Test fun `capacity remains three pending plus one active and fresh equal-priority work replaces oldest pending`() {
        val queue = SpeechQueue()
        queue.offer(event("active"), 0, accept)
        val active = queue.next(1, accept)!!
        repeat(4) { queue.offer(event("pending $it"), 2L + it, accept) }
        assertEquals(4, queue.size)
        assertEquals(3, queue.pendingCount)
        queue.finish(active.token, 10)
        assertEquals("pending 1", queue.next(11, accept)!!.event.text)
    }

    @Test fun `lower-priority work cannot evict task results from a full queue`() {
        val queue = SpeechQueue()
        repeat(3) { queue.offer(event("task $it"), it.toLong(), accept) }
        assertFalse(queue.offer(event("ambient", SpeechPriority.AMBIENT), 4, accept).accepted)
        assertEquals(3, queue.size)
        assertEquals("task 0", queue.next(5, accept)!!.event.text)
    }

    @Test fun `dispatch respects priority with FIFO ordering inside a priority`() {
        val queue = SpeechQueue(ambientCooldownMs = 0)
        queue.offer(event("status", SpeechPriority.STATUS), 0, accept)
        val active = queue.next(1, accept)!!
        queue.offer(event("task 1"), 2, accept)
        queue.offer(event("ambient", SpeechPriority.AMBIENT), 3, accept)
        queue.offer(event("task 2"), 4, accept) // Incoming task supersedes pending ambient.
        assertEquals(2, queue.pendingCount)
        queue.finish(active.token, 5)
        val first = queue.next(6, accept)!!
        assertEquals("task 1", first.event.text)
        queue.finish(first.token, 7)
        assertEquals("task 2", queue.next(8, accept)!!.event.text)
    }

    @Test fun `latest explicit user request supersedes older queued conversation responses`() {
        val queue = SpeechQueue()
        queue.offer(event("old response", SpeechPriority.USER_REQUEST), 0, accept)
        queue.offer(event("new response", SpeechPriority.USER_REQUEST), 1, accept)
        assertEquals(1, queue.size)
        val first = queue.next(2, accept)!!
        assertEquals("new response", first.event.text)
        assertTrue(queue.offer(event("another request", SpeechPriority.USER_REQUEST), 3, accept).interrupted)
        assertFalse(queue.finish(first.token, 4))
        assertEquals("another request", queue.next(5, accept)!!.event.text)
    }

    @Test fun `duplicates are silent while pending active and recently started but explicit repeat can bypass dedup`() {
        val decisions = mutableListOf<SpeechDecision>()
        val queue = SpeechQueue(onDecision = decisions::add)
        val result = event("chair on left")
        assertTrue(queue.offer(result, 0, accept).accepted)
        assertFalse(queue.offer(result, 1, accept).accepted)
        val first = queue.next(2, accept)!!
        assertFalse(queue.offer(result, 3, accept).accepted)
        assertEquals(SpeechStartResult.ACCEPTED, queue.started(first.token, 4, accept))
        queue.finish(first.token, 5)
        assertFalse(queue.offer(result, 6, accept).accepted)
        assertTrue(queue.offer(result.copy(priority = SpeechPriority.USER_REQUEST), 7, accept, bypassDedupe = true).accepted)
        assertEquals(3, decisions.count { it.code == SpeechDecisionCode.DUPLICATE })
    }

    @Test fun `ambient cooldown suppresses unnecessary new narration without suppressing task evidence`() {
        val decisions = mutableListOf<SpeechDecision>()
        val queue = SpeechQueue(onDecision = decisions::add)
        queue.offer(event("first ambient", SpeechPriority.AMBIENT), 0, accept)
        val first = queue.next(1, accept)!!
        queue.started(first.token, 2, accept)
        queue.finish(first.token, 3)
        assertFalse(queue.offer(event("second ambient", SpeechPriority.AMBIENT), 100, accept).accepted)
        assertTrue(queue.offer(event("important target"), 101, accept).accepted)
        assertEquals(1, decisions.count { it.code == SpeechDecisionCode.COOLDOWN })
    }

    @Test fun `expired work cannot enter dispatch or actual TTS start and rejected work does not block following speech`() {
        val queue = SpeechQueue()
        val freshUntil100: (Announcement, Long) -> Boolean = { _, at -> at <= 100 }
        assertFalse(queue.offer(event("already old"), 101, freshUntil100).accepted)
        queue.offer(event("waiting while engine initializes"), 0, freshUntil100)
        assertNull(queue.next(101, freshUntil100))
        queue.offer(event("delayed start"), 110, accept)
        val delayed = queue.next(111, accept)!!
        assertEquals(SpeechStartResult.REJECTED, queue.started(delayed.token, 112) { _, _ -> false })
        assertNull(queue.current)
        queue.offer(event("new result"), 113, accept)
        assertEquals("new result", queue.next(114, accept)!!.event.text)
    }

    @Test fun `real core grounding change between dispatch and TTS callback rejects contradicted directional speech`() {
        val engine = SightEngine(config = EngineConfig(associationIou = 0.01f))
        val left = Detection("chair", 0.9f, Box(0f, 0.2f, 0.6f, 0.6f))
        engine.onFrame(FrameObservation(Evidence(1, 100), listOf(left)), 100)
        val narration = engine.onFrame(FrameObservation(Evidence(2, 200), listOf(left)), 200).announcements.single()
        val queue = SpeechQueue()
        queue.offer(narration, 200, engine::isAnnouncementValid)
        val dispatched = queue.next(200, engine::isAnnouncementValid)!!
        engine.onFrame(FrameObservation(Evidence(3, 300), listOf(left.copy(box = Box(0.4f, 0.2f, 1f, 0.6f)))), 300)
        assertEquals(SpeechStartResult.REJECTED, queue.started(dispatched.token, 300, engine::isAnnouncementValid))
        assertNull(queue.current)
    }

    @Test fun `ongoing live statement interrupts on lost support while admitted fixed OCR can finish`() {
        val queue = SpeechQueue()
        queue.offer(event("live target"), 0, accept)
        val live = queue.next(1, accept)!!
        queue.started(live.token, 2, accept)
        assertTrue(queue.tick(3, accept) { _, _ -> false })
        assertNull(queue.current)
        val snapshot = Announcement("Captured text: EXIT", SpeechPriority.USER_REQUEST, Evidence(1, 10), AnnouncementKind.OCR)
        queue.offer(snapshot, 10, accept)
        val read = queue.next(11, accept)!!
        queue.started(read.token, 12, accept)
        assertFalse(queue.tick(20_000, { _, _ -> false }) { candidate, _ -> candidate.kind == AnnouncementKind.OCR })
        assertEquals(read.token, queue.current!!.token)
    }

    @Test fun `before actual start even an OCR snapshot must pass full freshness rather than continuation policy`() {
        val queue = SpeechQueue()
        val snapshot = Announcement("Captured text", SpeechPriority.USER_REQUEST, Evidence(1, 100), AnnouncementKind.OCR)
        queue.offer(snapshot, 100, accept)
        queue.next(101, accept)
        assertTrue(queue.tick(9_000, { _, _ -> false }) { _, _ -> true })
        assertNull(queue.current)
    }

    @Test fun `duplicate and obsolete start callbacks cannot create extra start events or replace active speech`() {
        val decisions = mutableListOf<SpeechDecision>()
        val queue = SpeechQueue(onDecision = decisions::add)
        queue.offer(event("one"), 0, accept)
        val one = queue.next(1, accept)!!
        assertEquals(SpeechStartResult.ACCEPTED, queue.started(one.token, 2, accept))
        assertEquals(SpeechStartResult.IGNORED, queue.started(one.token, 3, accept))
        queue.stop(4)
        queue.offer(event("two"), 5, accept)
        val two = queue.next(6, accept)!!
        assertEquals(SpeechStartResult.IGNORED, queue.started(one.token, 7, accept))
        assertEquals(two, queue.current)
        assertEquals(1, decisions.count { it.code == SpeechDecisionCode.STARTED })
    }

    @Test fun `invalid high-priority input cannot interrupt a currently supported task`() {
        val queue = SpeechQueue()
        queue.offer(event("supported task"), 0, accept)
        val current = queue.next(1, accept)!!
        val denied = queue.offer(event("stale user response", SpeechPriority.USER_REQUEST), 2, { _, _ -> false })
        assertFalse(denied.accepted)
        assertFalse(denied.interrupted)
        assertEquals(current, queue.current)
    }
}
