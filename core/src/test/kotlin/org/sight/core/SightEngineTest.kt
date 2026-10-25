package org.sight.core

import org.junit.Assert.*
import org.junit.Test

class SightEngineTest {
    private val left = Box(0.05f, 0.2f, 0.25f, 0.6f)
    private val center = Box(0.40f, 0.2f, 0.60f, 0.6f)
    private val right = Box(0.75f, 0.2f, 0.95f, 0.6f)

    private fun frame(id: Long, at: Long, vararg detections: Detection, source: String = "phone-camera") =
        FrameObservation(Evidence(id, at, source), detections.toList())
    private fun chair(box: Box = left, confidence: Float = 0.9f) = Detection("chair", confidence, box)

    @Test fun `find requires two distinct fresh frames after command`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        assertEquals(GoalPhase.SEARCHING, engine.command("find a chair", 250).goal.phase)
        val first = engine.onFrame(frame(3, 300, chair()), 300)
        assertEquals(GoalPhase.VERIFYING, first.goal.phase)
        assertTrue(first.announcements.isEmpty())
        val duplicate = engine.onFrame(frame(3, 300, chair()), 310)
        assertEquals(GoalPhase.VERIFYING, duplicate.goal.phase)
        val second = engine.onFrame(frame(4, 400, chair()), 400)
        assertEquals(GoalPhase.COMPLETED, second.goal.phase)
        assertEquals(AnnouncementKind.FIND_RESULT, second.announcements.single().kind)
        assertTrue(engine.isAnnouncementValid(second.announcements.single(), 400))
    }

    @Test fun `one-frame false positive and category change cannot complete a find`() {
        val engine = SightEngine()
        engine.command("find chair", 0)
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200), 200)
        engine.onFrame(frame(3, 300, Detection("couch", 0.99f, left)), 300)
        assertEquals(GoalPhase.SEARCHING, engine.tick(350).goal.phase)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(4, 400, chair()), 400).goal.phase)
        assertEquals(GoalPhase.COMPLETED, engine.onFrame(frame(5, 500, chair()), 500).goal.phase)
    }

    @Test fun `low confidence observations cannot verify a target`() {
        val engine = SightEngine()
        engine.command("find a chair", 0)
        engine.onFrame(frame(1, 100, chair(confidence = 0.6f)), 100)
        val result = engine.onFrame(frame(2, 200, chair(confidence = 0.6f)), 200)
        assertEquals(GoalPhase.SEARCHING, result.goal.phase)
        assertTrue(result.announcements.isEmpty())
    }

    @Test fun `stale future and out-of-order frames are rejected without updating scene`() {
        val engine = SightEngine()
        val accepted = engine.onFrame(frame(5, 2_000, chair()), 2_000)
        val id = accepted.entities.single().id
        val stale = engine.onFrame(frame(6, 100, chair(right)), 2_001)
        assertEquals(left, stale.entities.single().box)
        assertTrue(stale.diagnostics.any { it.code == "STALE_FRAME" })
        val future = engine.onFrame(frame(7, 3_000, chair(right)), 2_002)
        assertTrue(future.diagnostics.any { it.code == "FUTURE_FRAME" })
        val oldId = engine.onFrame(frame(4, 2_003, chair(right)), 2_003)
        assertTrue(oldId.diagnostics.any { it.code == "OUT_OF_ORDER_FRAME" })
        assertEquals(id, oldId.entities.single().id)
        assertEquals(left, oldId.entities.single().box)
    }

    @Test fun `lost input invalidates speech and rejects captured work from prior lifecycle`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        val announcement = engine.onFrame(frame(2, 200, chair()), 200).announcements.single()
        assertTrue(engine.isAnnouncementValid(announcement, 200))
        assertEquals(ReliabilityState.VISUAL_INPUT_UNAVAILABLE, engine.setInputAvailable(false, 250).reliability)
        assertFalse(engine.isAnnouncementValid(announcement, 260))
        engine.setInputAvailable(true, 300)
        val late = engine.onFrame(frame(3, 240, chair()), 310)
        assertTrue(late.diagnostics.any { it.code == "LIFECYCLE_RESULT" })
        assertTrue(late.entities.none { it.visible })
        assertEquals(ReliabilityState.NEEDS_CONFIRMATION, late.reliability)
    }

    @Test fun `source changes discard image identities and find candidate evidence`() {
        val engine = SightEngine()
        engine.command("find a chair", 0)
        val old = engine.onFrame(frame(1, 100, chair()), 100).entities.single().id
        val switched = engine.onFrame(frame(1, 200, chair(), source = "glasses-mock"), 200)
        assertNotEquals(old, switched.entities.single().id)
        assertEquals(GoalPhase.VERIFYING, switched.goal.phase)
        assertEquals(GoalPhase.COMPLETED, engine.onFrame(frame(2, 300, chair(), source = "glasses-mock"), 300).goal.phase)
    }

    @Test fun `camera restart permits a fresh session counter and resets image identity`() {
        val engine = SightEngine()
        engine.onFrame(frame(100, 100, chair()), 100)
        val oldId = engine.onFrame(frame(101, 200, chair()), 200).entities.single().id
        engine.command("remember this as desk", 200)
        engine.setInputAvailable(false, 250)
        engine.setInputAvailable(true, 300)
        val restarted = engine.onFrame(frame(1, 350, chair()), 350)
        assertEquals(ReliabilityState.OPERATIONAL, restarted.reliability)
        assertTrue(restarted.entities.single().visible)
        assertNotEquals(oldId, restarted.entities.single().id)
        assertEquals(1, restarted.entities.single().consecutiveObservations)
        assertEquals("desk", restarted.memory.single().name)
    }

    @Test fun `category-gated one-to-one matching never assigns a detection twice`() {
        val engine = SightEngine()
        val initial = engine.onFrame(frame(1, 100, chair(left), chair(center)), 100)
        assertEquals(2, initial.entities.size)
        val next = engine.onFrame(frame(2, 200, chair(left)), 200)
        assertEquals(1, next.entities.count { it.visible })
        assertEquals(initial.entities.first().id, next.entities.single { it.visible }.id)
        assertEquals(0, next.entities.single { !it.visible }.consecutiveObservations)
    }

    @Test fun `image geometry reports image relationships without depth claims`() {
        val engine = SightEngine()
        val result = engine.onFrame(frame(1, 100, chair(left), Detection("bottle", 0.95f, right)), 100)
        assertEquals(1, result.relationships.size)
        assertEquals(RelationshipKind.LEFT_OF_IN_IMAGE, result.relationships.single().kind)
        assertTrue(engine.command("which is closer", 100).announcements.single().text.contains("do not have validated depth"))
    }

    @Test fun `queued directional speech expires when target moves or disappears`() {
        val engine = SightEngine(config = EngineConfig(associationIou = 0.01f))
        val wideLeft = Box(0.0f, 0.2f, 0.6f, 0.6f)
        val wideRight = Box(0.4f, 0.2f, 1.0f, 0.6f)
        engine.onFrame(frame(1, 100, chair(wideLeft)), 100)
        val announcement = engine.onFrame(frame(2, 200, chair(wideLeft)), 200).announcements.single()
        engine.onFrame(frame(3, 300, chair(wideRight)), 300)
        assertFalse(engine.isAnnouncementValid(announcement, 300))
        engine.onFrame(frame(4, 400), 400)
        assertFalse(engine.isAnnouncementValid(announcement, 400))
        assertFalse(engine.isAnnouncementValid(announcement, 2_000))
    }

    @Test fun `narration waits for persistence and suppresses duplicates`() {
        val engine = SightEngine(config = EngineConfig(narrationIntervalMs = 0))
        assertTrue(engine.onFrame(frame(1, 100, chair()), 100).announcements.isEmpty())
        assertEquals(1, engine.onFrame(frame(2, 200, chair()), 200).announcements.size)
        repeat(10) { index ->
            val time = 300L + index * 100
            assertTrue(engine.onFrame(frame(3L + index, time, chair()), time).announcements.isEmpty())
        }
    }

    @Test fun `missing observations become invisible then expire`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        val stale = engine.tick(1_601)
        assertFalse(stale.entities.single().visible)
        assertEquals(ReliabilityState.NEEDS_CONFIRMATION, stale.reliability)
        assertTrue(stale.relationships.isEmpty())
        assertTrue(engine.tick(5_101).entities.isEmpty())
    }

    @Test fun `history tracks traces and memories are bounded`() {
        val config = EngineConfig(maxTrackHistory = 3, maxTracks = 2, maxDetections = 2, maxMemories = 2)
        val engine = SightEngine(config = config)
        repeat(20) { i -> engine.onFrame(frame(i.toLong(), i * 100L, chair(), Detection("bottle", 0.8f, right)), i * 100L) }
        val result = engine.tick(1_900)
        assertTrue(result.entities.size <= 2)
        assertTrue(result.entities.all { it.history.size <= 3 })
        repeat(5) { engine.command("remember this as scene $it", 1_900) }
        assertEquals(listOf("scene 3", "scene 4"), engine.tick(1_900).memory.map { it.name })
        repeat(60) { engine.onFrame(frame(1, 100), 1_900) }
        assertTrue(engine.tick(1_900).diagnostics.size <= 30)
    }

    @Test fun `unsupported classes and visual attributes never enter successful search`() {
        val engine = SightEngine()
        assertEquals(GoalPhase.UNSUPPORTED, engine.command("find my keys", 0).goal.phase)
        val colored = engine.command("find my blue backpack", 1)
        assertEquals(GoalPhase.UNSUPPORTED, colored.goal.phase)
        assertTrue(colored.announcements.single().text.contains("cannot verify 'blue'"))
        assertEquals(GoalPhase.SEARCHING, engine.command("find my backpack", 2).goal.phase)
        assertEquals("couch", engine.command("find a sofa", 3).goal.target)
    }

    @Test fun `timeouts abstain without asserting absence and cancel-resume resets verification`() {
        val engine = SightEngine(config = EngineConfig(findTimeoutMs = 1_000))
        engine.command("find chair", 0)
        engine.onFrame(frame(1, 100, chair()), 100)
        assertEquals(GoalPhase.CANCELLED, engine.command("cancel", 200).goal.phase)
        assertEquals(GoalPhase.SEARCHING, engine.command("resume", 300).goal.phase)
        assertEquals(GoalPhase.VERIFYING, engine.onFrame(frame(2, 400, chair()), 400).goal.phase)
        val timeout = engine.tick(1_300)
        assertEquals(GoalPhase.TIMED_OUT, timeout.goal.phase)
        assertTrue(timeout.announcements.single().text.contains("does not establish it is absent"))
    }

    @Test fun `named memories are historical scene labels and never current locations`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        val saved = engine.command("remember this entrance", 200)
        assertEquals("entrance", saved.memory.single().name)
        assertEquals("chair", saved.memory.single().entities.single().category)
        engine.tick(9_000)
        val recall = engine.command("where was the entrance", 9_000).announcements.single()
        assertEquals(AnnouncementKind.MEMORY, recall.kind)
        assertTrue(recall.text.contains("current location is unknown"))
        assertTrue(recall.text.contains("8 seconds ago"))
        assertTrue(engine.command("forget entrance", 9_000).memory.isEmpty())
    }

    @Test fun `memory requires actual recent evidence`() {
        val engine = SightEngine()
        assertTrue(engine.command("remember this as home", 0).memory.isEmpty())
        engine.onFrame(frame(1, 100, chair()), 100)
        engine.onFrame(frame(2, 200, chair()), 200)
        assertTrue(engine.command("remember this as home", 2_000).memory.isEmpty())
    }

    @Test fun `reading uses fresh OCR and preserves verbatim extracted evidence`() {
        val engine = SightEngine()
        engine.command("read", 0)
        val text = "EXIT LEFT\nSTAIRS CLOSED"
        val result = engine.onFrame(FrameObservation(Evidence(1, 100), emptyList(), listOf(TextObservation(text, center))), 100)
        assertEquals(GoalPhase.COMPLETED, result.goal.phase)
        assertEquals(text, result.extractedText)
        assertEquals("Recognized text: $text", result.announcements.single().text)
        assertFalse(result.announcements.single().text.contains("go left", ignoreCase = true))
        assertTrue(engine.command("what was that sign", 1_100).announcements.single().text.startsWith("Text recognized 1 seconds ago"))
    }

    @Test fun `price reading labels pattern matches and missing dates abstain`() {
        val engine = SightEngine()
        engine.command("just tell me the price", 0)
        val price = engine.onFrame(FrameObservation(Evidence(1, 100), emptyList(), listOf(TextObservation("BREAD $3.99", center))), 100)
        assertTrue(price.announcements.single().text.contains("Price-like text: $3.99"))
        engine.command("find the expiration date", 150)
        val date = engine.onFrame(FrameObservation(Evidence(2, 200), emptyList(), listOf(TextObservation("BREAD $3.99", center))), 200)
        assertTrue(date.announcements.single().text.contains("could not identify a labelled expiration date"))
        assertEquals(GoalType.READ, date.goal.type)
    }

    @Test fun `clock regression cannot change accepted state`() {
        val engine = SightEngine()
        engine.onFrame(frame(1, 200, chair()), 200)
        val result = engine.onFrame(frame(2, 100, chair(right)), 100)
        assertEquals(left, result.entities.single().box)
        assertTrue(result.diagnostics.any { it.code == "CLOCK_REGRESSION" })
    }

    @Test fun `receipt-time evidence explicitly reduces capability`() {
        val engine = SightEngine()
        val result = engine.onFrame(FrameObservation(Evidence(1, 100, timeBasis = TimeBasis.RECEIPT), listOf(chair())), 100)
        assertEquals(ReliabilityState.REDUCED_CAPABILITY, result.reliability)
        assertTrue(result.diagnostics.any { it.code == "RECEIPT_TIME_ONLY" })
    }

    @Test fun `placeholder detector labels cannot become spoken observations`() {
        val engine = SightEngine()
        val result = engine.onFrame(frame(1, 100, Detection("???", 0.99f, left)), 100)
        assertTrue(result.entities.isEmpty())
        assertTrue(result.announcements.isEmpty())
        assertTrue(result.diagnostics.any { it.code == "UNSUPPORTED_DETECTION_LABEL" })
    }

    @Test fun `unchanged scene remains quiet across long continuous observation`() {
        val engine = SightEngine(config = EngineConfig(narrationIntervalMs = 0))
        var count = 0
        repeat(200) { index ->
            count += engine.onFrame(frame(index.toLong(), index * 400L, chair()), index * 400L).announcements.size
        }
        assertEquals(1, count)
    }

    @Test fun `narration profiles limit utterance content without changing perception`() {
        val engine = SightEngine()
        engine.setNarrationProfile(NarrationProfile.CONCISE, 0)
        val detections = arrayOf(chair(left), Detection("bottle", 0.95f, center), Detection("backpack", 0.8f, right))
        engine.onFrame(frame(1, 100, *detections), 100)
        val result = engine.onFrame(frame(2, 200, *detections), 200)
        assertEquals(3, result.entities.count { it.visible })
        assertEquals(1, result.announcements.single().trackIds.size)
        assertEquals(NarrationProfile.CONCISE, result.narrationProfile)
    }

    @Test fun `numbered OCR recall distinguishes blocks from verified paragraphs`() {
        val engine = SightEngine()
        engine.command("read", 0)
        engine.onFrame(FrameObservation(Evidence(1, 100), emptyList(), listOf(TextObservation("FIRST BLOCK", left), TextObservation("SECOND BLOCK", right))), 100)
        val recalled = engine.command("read the second paragraph again", 200).announcements.single()
        assertTrue(recalled.text.contains("SECOND BLOCK"))
        assertTrue(recalled.text.contains("paragraph boundaries are unverified"))
        val missing = engine.command("read the third paragraph again", 300).announcements.single()
        assertTrue(missing.text.contains("do not have recognized text block 3"))
    }

    @Test(expected = IllegalArgumentException::class) fun `invalid image bounds fail at boundary`() { Box(0.8f, 0.1f, 0.2f, 0.9f) }
    @Test(expected = IllegalArgumentException::class) fun `NaN confidence fails at boundary`() { chair(confidence = Float.NaN) }
}
