package org.sight.core

import org.junit.Assert.*
import org.junit.Test

class EngineToolsTest {
    private val box = Box(0.1f, 0.2f, 0.3f, 0.6f)

    @Test fun `tool access shares actual scene and rejects stale live facts`() {
        val engine = SightEngine()
        val tools = EngineTools(engine)
        assertEquals(ToolStatus.NO_EVIDENCE, tools.observeScene(0).status)
        engine.onFrame(FrameObservation(Evidence(1, 100), listOf(Detection("chair", 0.9f, box))), 100)
        val scene = tools.observeScene(100)
        assertEquals(ToolStatus.SUCCESS, scene.status)
        assertEquals("chair", scene.data!!.entities.single().category)
        val id = scene.data.entities.single().id
        assertEquals(ToolStatus.SUCCESS, tools.trackTarget(id, 100).status)
        assertEquals(ToolStatus.NO_EVIDENCE, tools.observeScene(1_601).status)
        assertEquals(ToolStatus.STALE, tools.trackTarget(id, 1_601).status)
        engine.setInputAvailable(false, 1_700)
        assertEquals(ToolStatus.UNAVAILABLE, tools.detectObjects(1_700).status)
    }

    @Test fun `no location depth or route provider can produce a success`() {
        val tools = EngineTools(SightEngine())
        listOf(tools.getLocation(0), tools.measureDepth(0), tools.requestRoute(0)).forEach {
            assertEquals(ToolStatus.UNAVAILABLE, it.status)
            assertNull(it.data)
            assertNull(it.confidence)
            assertTrue(it.errors.isNotEmpty())
        }
    }

    @Test fun `requesting text is pending and historical OCR cannot become fresh tool text`() {
        val engine = SightEngine()
        val tools = EngineTools(engine)
        assertEquals(ToolStatus.NEEDS_CONFIRMATION, tools.requestReadText(0).status)
        assertEquals(ToolStatus.NO_EVIDENCE, tools.readText(0).status)
        engine.onFrame(FrameObservation(Evidence(1, 100), emptyList(), listOf(TextObservation("EXIT", box))), 100)
        assertEquals("EXIT", tools.readText(100).data!!.originalText)
        val stale = tools.readText(1_601)
        assertEquals(ToolStatus.STALE, stale.status)
        assertNull(stale.data)
    }

    @Test fun `tool memory remains historical after live evidence expires`() {
        val engine = SightEngine()
        val tools = EngineTools(engine)
        assertEquals(ToolStatus.NO_EVIDENCE, tools.saveLandmark("desk", 0).status)
        repeat(2) { index -> engine.onFrame(FrameObservation(Evidence(index.toLong(), index * 100L), listOf(Detection("chair", 0.9f, box))), index * 100L) }
        val saved = tools.saveLandmark("desk", 100)
        assertEquals(ToolStatus.SUCCESS, saved.status)
        val memory = tools.queryVisualMemory("desk", 10_100)
        assertEquals(ToolStatus.SUCCESS, memory.status)
        assertEquals(10_000L, memory.data!!.ageMs)
        assertFalse(memory.data.presentLocationKnown)
        assertNull(memory.confidence)
    }

    @Test fun `receipt-clock tools require confirmation rather than silently succeeding`() {
        val engine = SightEngine()
        val tools = EngineTools(engine)
        engine.onFrame(FrameObservation(Evidence(1, 100, timeBasis = TimeBasis.RECEIPT), emptyList()), 100)
        val result = tools.observeScene(100)
        assertEquals(ToolStatus.NEEDS_CONFIRMATION, result.status)
        assertEquals("EXPOSURE_CLOCK_UNVERIFIED", result.errors.single().code)
    }
}
