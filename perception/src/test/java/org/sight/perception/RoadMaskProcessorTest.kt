package org.sight.perception

import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test
import org.sight.core.Box
import org.sight.core.PedestrianSurface

class RoadMaskProcessorTest {
    @Test fun knownArgbPixelsBecomeUnnormalizedBgrAndLeaveSourceIntact() {
        val source = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x12345678)
        val copy = source.copyOf()
        val out = FloatBuffer.allocate(12)
        RoadMaskProcessor.putBgr(source, out)
        assertArrayEquals(floatArrayOf(0f,0f,255f, 0f,255f,0f, 255f,0f,0f, 120f,86f,52f),out.array(),0f)
        assertArrayEquals(copy,source)
    }

    @Test fun disconnectedRegionsStaySeparateAndCoverageUsesWholeOutputGrid() {
        // Two road components on opposite sides, one curb and one marking. Background separates them.
        val out = logits(6, 2, intArrayOf(1,1,0,2,0,1, 1,0,0,2,3,0))
        val regions = RoadMaskProcessor.regions(out,6,2,minimumCoverage=0f)
        assertEquals(4,regions.size)
        val road = regions.filter { it.label == PedestrianSurface.ROAD }
        assertEquals(2,road.size)
        assertEquals(Box(0f,0f,2f/6,1f),road[0].box)
        assertEquals(.25f,road[0].coverage,1e-6f)
        assertEquals(Box(5f/6,0f,1f,.5f),road[1].box)
        assertTrue(regions.any { it.label == PedestrianSurface.ROAD_MARKING })
        assertFalse(regions.any { it.label == PedestrianSurface.CROSSWALK || it.label == PedestrianSurface.SIDEWALK })
        assertEquals(.97f,road[0].score,1e-6f)
    }

    @Test fun diagonalPixelsAreNotConnectedAndSmallComponentsAreFiltered() {
        val out = logits(2,2,intArrayOf(1,0,0,1))
        assertEquals(2,RoadMaskProcessor.regions(out,2,2,minimumCoverage=.2f).size)
        assertTrue(RoadMaskProcessor.regions(out,2,2,minimumCoverage=.3f).isEmpty())
    }

    @Test fun invalidOrLowPosteriorPixelsCannotCreateSurfaceEvidence() {
        val out = logits(4,1,intArrayOf(1,1,1,1))
        out.put(0,Float.NaN)
        out.put(4,-.1f)
        out.put(8,.9f) // Sum no longer resembles softmax.
        out.put(12,.1f); out.put(13,.8f); out.put(14,.05f); out.put(15,.05f)
        assertTrue(RoadMaskProcessor.regions(out,4,1,minimumCoverage=0f).isEmpty())
    }

    @Test fun regionCountIsBoundedWithoutJoiningUnrelatedComponents() {
        val out = logits(9,1,intArrayOf(1,0,1,0,1,0,1,0,1))
        assertEquals(3,RoadMaskProcessor.regions(out,9,1,minimumCoverage=0f,maximumRegions=3).size)
    }

    private fun logits(width: Int,height: Int,classes:IntArray): FloatBuffer {
        require(classes.size==width*height)
        val scores=FloatArray(classes.size*4) { .01f }
        classes.forEachIndexed { pixel,category -> scores[pixel*4+category]=.97f }
        return FloatBuffer.wrap(scores)
    }
}
