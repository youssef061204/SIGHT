package org.sight.core

import org.junit.Assert.*
import org.junit.Test

class TemporalEngineTest {
    private val left = Box(0.05f, 0.2f, 0.25f, 0.6f)
    private val right = Box(0.75f, 0.2f, 0.95f, 0.6f)
    private fun chair(box: Box = left, confidence: Float = 0.9f) = Detection("chair", confidence, box)
    private fun frame(id: Long, at: Long, vararg detections: Detection, model: String = "fixture-detector", epoch: Long = 0) =
        FrameObservation(Evidence(id, at, modelId = model, geometryEpoch = epoch), detections.toList())

    @Test fun `bounded prediction reconnects translated observations across a detector miss without inventing visibility`() {
        val improved = SightEngine()
        val baseline = SightEngine(config = EngineConfig(useMotionPrediction = false))
        fun moved(x: Float) = chair(Box(x, 0.2f, x + 0.12f, 0.5f))
        var original = 0L
        listOf(0.05f, 0.11f, 0.17f).forEachIndexed { index, x ->
            val f = frame(index + 1L, (index + 1) * 100L, moved(x))
            val result = improved.onFrame(f, f.evidence.capturedAtMs)
            baseline.onFrame(f, f.evidence.capturedAtMs)
            if (index == 0) original = result.entities.single().id
        }
        val missed = improved.onFrame(frame(4, 400), 400)
        baseline.onFrame(frame(4, 400), 400)
        assertTrue(missed.entities.none { it.visible })
        assertEquals(VisibilityStatus.RECENTLY_OBSERVED, missed.world.single().visibility)
        assertTrue(missed.relationships.isEmpty())
        val recovered = improved.onFrame(frame(5, 500, moved(0.29f)), 500)
        val fragmented = baseline.onFrame(frame(5, 500, moved(0.29f)), 500)
        assertEquals(original, recovered.entities.single { it.visible }.id)
        assertNotEquals(original, fragmented.entities.single { it.visible }.id)
        assertEquals(ImageAssociation.MOTION_PREDICTED, recovered.entities.single { it.visible }.association)
        assertEquals(1, recovered.entities.single { it.visible }.consecutiveObservations)
        assertEquals(0.29f, recovered.entities.single { it.visible }.box.left, 0.0001f)
    }

    @Test fun `aggregation describes model scores and cannot turn a low latest confidence into confirmation`() {
        val engine = SightEngine()
        engine.command("find chair", 0)
        engine.onFrame(frame(1, 100, chair()), 100)
        val dipped = engine.onFrame(frame(2, 200, chair(confidence = 0.6f)), 200)
        assertEquals(0.75f, dipped.entities.single().aggregateConfidence, 0.0001f)
        assertEquals(GoalPhase.SEARCHING, dipped.goal.phase)
        assertTrue(dipped.announcements.isEmpty())
    }

    @Test fun `category confirmation policy requires its configured post-request frame count`() {
        val engine = SightEngine(config = EngineConfig(categoryConfirmation = mapOf("chair" to ConfirmationPolicy(3, 0.8f))))
        engine.command("find chair", 0)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(1, 100, chair()), 100).goal.phase)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(2, 200, chair()), 200).goal.phase)
        val result = engine.onFrame(frame(3, 300, chair()), 300)
        assertEquals(GoalPhase.COMPLETED, result.goal.phase)
        assertTrue(result.announcements.single().text.contains("3 frames"))
    }

    @Test fun `pre-request capture can update scene but cannot verify search`() {
        val engine = SightEngine()
        engine.command("find chair", 200)
        assertEquals(GoalPhase.SEARCHING, engine.onFrame(frame(1, 100, chair()), 250).goal.phase)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(2, 300, chair()), 300).goal.phase)
        assertEquals(GoalPhase.COMPLETED, engine.onFrame(frame(3, 400, chair()), 400).goal.phase)
    }

    @Test fun `completed search reports loss once and requires fresh verification on return`() {
        val engine = SightEngine()
        engine.command("find chair", 0)
        engine.onFrame(frame(1, 100, chair()), 100)
        assertEquals(GoalPhase.COMPLETED, engine.onFrame(frame(2, 200, chair()), 200).goal.phase)
        assertTrue(engine.onFrame(frame(3, 300, chair()), 300).announcements.isEmpty())
        val lost = engine.onFrame(frame(4, 400), 400)
        assertEquals(GoalPhase.SEARCHING, lost.goal.phase)
        assertTrue(lost.goal.continuing)
        assertTrue(lost.announcements.single().text.contains("no longer reliably detected"))
        assertTrue(engine.onFrame(frame(5, 500), 500).announcements.isEmpty())
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(6, 600, chair()), 600).goal.phase)
        val returned = engine.onFrame(frame(7, 700, chair()), 700)
        assertEquals(GoalPhase.COMPLETED, returned.goal.phase)
        assertTrue(returned.announcements.single().text.contains("category candidate"))
        assertTrue(returned.diagnostics.any { it.code == "FIND_REACQUIRED" })
    }

    @Test fun `same chair means a retained image hypothesis and abstains when ambiguous or expired`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        val command = engine.command("can you find that same chair again", 250)
        assertNotNull(command.goal.identityTrackId)
        engine.onFrame(frame(3, 300, chair()), 300)
        val confirmed = engine.onFrame(frame(4, 400, chair()), 400)
        assertTrue(confirmed.announcements.single().text.contains("physical identity is unverified"))
        val expired = engine.command("find the same chair again", 6_000)
        assertTrue(expired.announcements.single().text.contains("cannot establish"))
        val ambiguous = SightEngine()
        ambiguous.onFrame(frame(1, 100, chair(), chair(right)), 100)
        assertTrue(ambiguous.command("find same chair", 100).announcements.single().text.contains("cannot establish"))
    }

    @Test fun `user rejection excludes that image track without relabelling detector evidence`() {
        val engine = SightEngine()
        engine.command("find chair", 0)
        engine.onFrame(frame(1, 100, chair(confidence = 0.95f), chair(right)), 100)
        engine.onFrame(frame(2, 200, chair(confidence = 0.95f), chair(right)), 200)
        engine.command("not that one", 250)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(3, 300, chair(confidence = 0.95f), chair(right)), 300).goal.phase)
        val accepted = engine.onFrame(frame(4, 400, chair(confidence = 0.95f), chair(right)), 400)
        assertEquals(GoalPhase.COMPLETED, accepted.goal.phase)
        assertEquals(ImageDirection.RIGHT, accepted.announcements.single().grounding.single().direction)
        assertEquals(setOf("chair"), accepted.entities.map { it.category }.toSet())
    }

    @Test fun `world distinguishes current recent and remembered then expires process metadata`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        assertEquals(VisibilityStatus.CURRENTLY_OBSERVED, engine.tick(100).world.single().visibility)
        engine.onFrame(frame(2, 200, chair()), 200)
        assertEquals(VisibilityStatus.RECENTLY_OBSERVED, engine.tick(1_701).world.single().visibility)
        val remembered = engine.tick(6_000)
        assertTrue(remembered.entities.isEmpty())
        assertEquals(VisibilityStatus.PREVIOUSLY_REMEMBERED, remembered.world.single().visibility)
        assertFalse(remembered.world.single().entity.visible)
        assertTrue(engine.command("where did you last see my chair", 6_000).announcements.single().text.contains("5 seconds ago"))
        assertTrue(engine.tick(60_201).world.isEmpty())
    }

    @Test fun `historical side uses actual earlier boxes after current box moves`() {
        val engine = SightEngine(config = EngineConfig(associationIou = 0.01f))
        val wideLeft = Box(0f, 0.2f, 0.6f, 0.6f)
        val wideRight = Box(0.4f, 0.2f, 1f, 0.6f)
        engine.onFrame(frame(1, 100, chair(wideLeft)), 100)
        engine.onFrame(frame(2, 200, chair(wideLeft)), 200)
        engine.onFrame(frame(3, 300, chair(wideRight)), 300)
        val answer = engine.command("what was on my left", 400).announcements.single()
        assertEquals(AnnouncementKind.MEMORY, answer.kind)
        assertEquals(200L, answer.evidence!!.capturedAtMs)
        assertTrue(answer.text.contains("a chair"))
        assertTrue(answer.text.contains("past image observations"))
        assertTrue(engine.isAnnouncementValid(answer, 400))
    }

    @Test fun `scene comparison labels detector differences without asserting physical departure`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        engine.onFrame(frame(3, 300), 300)
        val answer = engine.command("what changed", 300).announcements.single()
        assertTrue(answer.text.contains("no longer stably detected in the later view: chair"))
        assertTrue(answer.text.contains("missed detections"))
        assertFalse(answer.text.contains("chair left"))
    }

    @Test fun `model and geometry discontinuities reset identities and preserve only historical metadata`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        val spoken = engine.onFrame(frame(2, 200, chair()), 200).announcements.single()
        val old = spoken.trackIds.single()
        val switched = engine.onFrame(frame(1, 300, chair(), model = "new-model"), 300)
        assertNotEquals(old, switched.entities.single().id)
        assertFalse(engine.isAnnouncementValid(spoken, 300))
        assertEquals(VisibilityStatus.RECENTLY_OBSERVED, switched.world.first { it.entity.id == old }.visibility)
        assertFalse(switched.sceneChanges!!.identitiesComparable)
        val modelTrack = switched.entities.single().id
        val rotated = engine.onFrame(frame(1, 400, chair(), model = "new-model", epoch = 1), 400)
        assertNotEquals(modelTrack, rotated.entities.single().id)
        assertFalse(rotated.sceneChanges!!.identitiesComparable)
    }

    @Test fun `unsupported attributes support relations and door history abstain`() {
        val engine = SightEngine()
        assertTrue(engine.command("is there a red backpack", 0).announcements.single().text.contains("cannot verify"))
        assertTrue(engine.command("which objects are on the table", 0).announcements.single().text.contains("do not establish physical support"))
        assertTrue(engine.command("was there a door in the previous view", 0).announcements.single().text.contains("does not support"))
    }

    @Test fun `async OCR snapshot preserves original time while detector tracks remain independent`() {
        val engine = SightEngine()
        engine.command("read", 0)
        engine.onFrame(frame(1, 100, chair()), 100)
        val before = engine.onFrame(frame(2, 2_000, chair(right)), 2_000)
        val evidence = Evidence(1, 100, modelId = "mlkit-latin-16.0.1")
        val result = engine.onTextObservation(evidence, listOf(TextObservation("EXIT", left)), 2_500)
        assertEquals(before.entities, result.entities)
        assertEquals(before.liveEvidence, result.liveEvidence)
        assertEquals(100L, result.textEvidence!!.capturedAtMs)
        assertEquals(GoalPhase.COMPLETED, result.goal.phase)
        val announcement = result.announcements.single()
        assertTrue(announcement.text.startsWith("Captured text:"))
        assertTrue(engine.isAnnouncementValid(announcement, 2_500))
        assertFalse(engine.isAnnouncementValid(announcement, 8_101))
        assertFalse(engine.isAnnouncementValid(Announcement("chair", SpeechPriority.TASK, evidence, AnnouncementKind.LIVE_SCENE), 2_500))
    }

    @Test fun `async OCR rejects old read requests changed geometry expired snapshots and cancelled tasks`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.command("read", 200)
        val old = engine.onTextObservation(Evidence(1, 100, modelId = "ocr"), listOf(TextObservation("OLD", left)), 300)
        assertNull(old.extractedText)
        assertTrue(old.diagnostics.any { it.code == "OCR_PRE_GOAL" })
        engine.onFrame(frame(2, 400, chair(), epoch = 1), 400)
        val rotated = engine.onTextObservation(Evidence(2, 300, modelId = "ocr"), listOf(TextObservation("OLD", left)), 500)
        assertTrue(rotated.diagnostics.any { it.code == "OCR_SESSION_CHANGED" })
        val expired = engine.onTextObservation(Evidence(3, 500, modelId = "ocr", geometryEpoch = 1), listOf(TextObservation("OLD", left)), 8_501)
        assertTrue(expired.diagnostics.any { it.code == "OCR_TOO_OLD" })
        engine.command("cancel", 8_600)
        val cancelled = engine.onTextObservation(Evidence(4, 8_600, modelId = "ocr", geometryEpoch = 1), listOf(TextObservation("OLD", left)), 8_700)
        assertEquals(GoalPhase.CANCELLED, cancelled.goal.phase)
        assertTrue(cancelled.diagnostics.any { it.code == "OCR_GOAL_INACTIVE" })
    }

    @Test fun `reinspection does not answer from pre-request captures`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        engine.command("reinspect", 400)
        assertTrue(engine.onFrame(frame(3, 300, chair()), 450).announcements.isEmpty())
        assertEquals(1, engine.onFrame(frame(4, 500, chair()), 500).announcements.size)
    }

    @Test fun `find scheduling is faster without enabling OCR or bypassing thermal critical`() {
        val scheduler = AdaptiveScheduler()
        assertEquals(InferencePlan(true, false), scheduler.plan(0, GoalType.FIND))
        assertEquals(InferencePlan(false, false), scheduler.plan(149, GoalType.FIND))
        assertEquals(InferencePlan(true, false), scheduler.plan(150, GoalType.FIND))
        assertEquals(InferencePlan(false, false), scheduler.plan(10_000, GoalType.FIND, ThermalLevel.CRITICAL))
    }

    @Test fun `direct change query cannot resurrect an expired comparison`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        engine.onFrame(frame(3, 300), 300)
        val expired = engine.command("what changed", 60_301)
        assertNull(expired.sceneChanges)
        assertTrue(expired.announcements.single().text.contains("need two retained"))
    }

    @Test fun `clear memory prevents pre-clear tracker history from returning in the next frame`() {
        val engine = SightEngine(config = EngineConfig(associationIou = 0.01f))
        val wideLeft = Box(0f, 0.2f, 0.6f, 0.6f)
        val wideRight = Box(0.4f, 0.2f, 1f, 0.6f)
        engine.onFrame(frame(1, 100, chair(wideLeft)), 100)
        engine.onFrame(frame(2, 200, chair(wideRight)), 200)
        engine.command("clear memory", 250)
        val next = engine.onFrame(frame(3, 300, chair(wideRight)), 300)
        assertTrue(next.world.single().entity.history.all { it.evidence.capturedAtMs > 250 })
        val oldSide = engine.command("what was on my left", 350).announcements.single()
        assertEquals(AnnouncementKind.UNCERTAINTY, oldSide.kind)
        assertFalse(oldSide.text.contains("a chair"))
    }

    @Test fun `queued image-relation speech is invalidated when relation changes inside the same image side`() {
        val engine = SightEngine(config = EngineConfig(associationIou = 0.1f))
        val table = Detection("dining table", 0.9f, Box(0.3f, 0.4f, 0.7f, 0.8f))
        val overlap = Detection("bottle", 0.95f, Box(0.4f, 0.3f, 0.6f, 0.6f))
        engine.onFrame(frame(1, 100, table, overlap), 100)
        val before = engine.onFrame(frame(2, 200, table, overlap), 200)
        val answer = engine.command("which objects are on the table", 250).announcements.single()
        assertTrue(answer.text.contains("overlaps the dining-table box in the image"))
        assertTrue(answer.requiredRelationships.isNotEmpty())
        assertTrue(engine.isAnnouncementValid(answer, 250))
        val moved = engine.onFrame(frame(3, 300, table, overlap.copy(box = Box(0.4f, 0.1f, 0.6f, 0.4f))), 300)
        assertEquals(before.entities.map { it.id }, moved.entities.map { it.id })
        assertTrue(moved.entities.all { it.direction == ImageDirection.CENTER })
        assertFalse(engine.isAnnouncementValid(answer, 300))
    }

    @Test fun `unconfirmed one-frame candidates cannot become confident spoken historical answers`() {
        val engine = SightEngine()
        val candidate = engine.onFrame(frame(1, 100, chair(confidence = 0.99f)), 100)
        assertFalse(candidate.world.single().confirmedHistorically)
        engine.onFrame(frame(2, 200), 200)
        val side = engine.command("what was on my left", 300).announcements.single()
        val category = engine.command("was there a chair in the previous view", 300).announcements.single()
        val recall = engine.command("where did you last see my chair", 300).announcements.single()
        assertEquals(AnnouncementKind.UNCERTAINTY, side.kind)
        assertEquals(AnnouncementKind.UNCERTAINTY, category.kind)
        assertEquals(AnnouncementKind.UNCERTAINTY, recall.kind)
    }

    @Test fun `queued live speech is rejected after latest evidence drops below confidence policy`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        val narration = engine.onFrame(frame(2, 200, chair()), 200).announcements.single()
        assertTrue(engine.isAnnouncementValid(narration, 200))
        val uncertain = engine.onFrame(frame(3, 300, chair(confidence = 0.55f)), 300)
        assertTrue(uncertain.entities.single().visible)
        assertFalse(engine.isAnnouncementValid(narration, 300))
    }

    @Test fun `forget all clears OCR transcript blocks and legacy admission while preserving fresh object input`() {
        val engine = SightEngine()
        engine.command("read", 0)
        val privateRead = engine.onFrame(FrameObservation(Evidence(1, 100), listOf(chair()), listOf(TextObservation("PRIVATE ORIGINAL", left))), 100)
        val legacy = privateRead.announcements.single()
        assertTrue(engine.isAnnouncementValid(legacy, 100))
        engine.command("remember this as private label", 110)
        val cleared = engine.clearMemory(200)
        assertNull(cleared.extractedText); assertNull(cleared.textEvidence)
        assertEquals(listOf("MEMORY_CLEARED"), cleared.diagnostics.map { it.code })
        assertFalse(engine.isAnnouncementValid(legacy, 200))
        assertTrue(engine.command("repeat text", 210).announcements.single().text.contains("No recognized text"))
        assertTrue(engine.command("read the first block", 220).announcements.single().text.contains("do not have recognized text block"))
        val unattended = engine.onFrame(FrameObservation(Evidence(2, 300), listOf(chair()), listOf(TextObservation("PRIVATE LATE FRAME", left))), 300)
        assertNull(unattended.extractedText)
        assertTrue(unattended.entities.any { it.visible })
        engine.command("read", 400)
        val newRead = engine.onFrame(FrameObservation(Evidence(3, 500), listOf(chair()), listOf(TextObservation("NEW REQUEST", left))), 500)
        assertEquals("NEW REQUEST", newRead.extractedText)
        assertFalse(engine.isAnnouncementValid(legacy, 500))
    }

    @Test fun `memory deletion cancels pending read and strictly rejects pre-clear and same-millisecond OCR`() {
        val engine = SightEngine()
        engine.command("read", 0)
        engine.onFrame(FrameObservation(Evidence(1, 100, modelId = "detector"), emptyList()), 100)
        val cleared = engine.command("forget everything", 200)
        assertEquals(GoalPhase.CANCELLED, cleared.goal.phase)
        val sameMs = engine.onTextObservation(Evidence(2, 200, modelId = "ocr"), listOf(TextObservation("PRIVATE LATE", left)), 250)
        assertNull(sameMs.extractedText)
        assertTrue(sameMs.diagnostics.any { it.code == "OCR_MEMORY_CLEARED" })
        assertNull(engine.onTextObservation(Evidence(3, 300, modelId = "ocr"), listOf(TextObservation("UNREQUESTED", left)), 350).extractedText)
        engine.command("read", 400)
        val fresh = engine.onTextObservation(Evidence(4, 450, modelId = "ocr"), listOf(TextObservation("FRESH", left)), 500)
        assertEquals("FRESH", fresh.extractedText)
        assertTrue(engine.isAnnouncementValid(fresh.announcements.single(), 500))
        assertNull(engine.clearMemory(600).extractedText)
        assertFalse(engine.isAnnouncementValid(fresh.announcements.single(), 600))
    }
}
