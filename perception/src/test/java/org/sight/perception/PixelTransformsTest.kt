package org.sight.perception

import org.junit.Assert.*
import org.junit.Test
import org.sight.core.Box

class PixelTransformsTest {
    @Test fun rgbaUsesExplicitChannelsAndSkipsRowPadding() {
        val bytes = byteArrayOf(-1,0,0,-1, 0,-1,0,-1, 9,9,9,9, 0,0,-1,-1, -1,-1,-1,-1)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1), PixelTransforms.rgba(bytes,2,2,12).argb)
    }
    @Test fun i420ChromaOrderAndVideoRangeAreKnown() {
        val red = PixelTransforms.i420(byteArrayOf(81,81,81,81,90,(-16).toByte()),2,2).argb
        red.forEach { assertTrue(((it shr 16) and 255) >= 250); assertTrue((it and 255) <= 3); assertTrue(((it shr 8) and 255) <= 3) }
        val neutral = PixelTransforms.i420(byteArrayOf(16,16,(-21).toByte(),(-21).toByte(),(-128).toByte(),(-128).toByte()),2,2).argb
        assertEquals(0xff000000.toInt(),neutral[0]); assertEquals(-1,neutral[3])
    }
    @Test fun yuvSupportsPaddedAndInterleavedPlanesWithoutSwappingUv() {
        val y=byteArrayOf(81,81,0,81,81)
        val vu=byteArrayOf((-16).toByte(),90)
        assertArrayEquals(PixelTransforms.i420(byteArrayOf(81,81,81,81,90,(-16).toByte()),2,2).argb,
            PixelTransforms.yuv420(2,2,PixelPlane(y,3),PixelPlane(vu,2,2,1),PixelPlane(vu,2,2,0)).argb)
    }
    @Test fun rotatesRectangularPixelIdentityThenMirrorsInOutputCoordinates() {
        val original=PixelFrame(3,2,intArrayOf(1,2,3,4,5,6))
        val ninety=PixelTransforms.orient(original,90)
        assertEquals(2,ninety.width); assertEquals(3,ninety.height)
        assertArrayEquals(intArrayOf(4,1,5,2,6,3),ninety.argb)
        assertArrayEquals(intArrayOf(1,4,2,5,3,6),PixelTransforms.orient(original,90,true).argb)
        assertArrayEquals(intArrayOf(6,5,4,3,2,1),PixelTransforms.orient(original,180).argb)
        assertArrayEquals(original.argb,PixelTransforms.orient(PixelTransforms.orient(original,90),270).argb)
    }
    @Test fun letterboxRemovesPaddingAndRejectsPaddingOnlyBoxes() {
        val transform=LetterboxGeometry(640,320,320,320)
        assertEquals(Box(0f,0f,1f,1f),transform.toSource(Box(0f,.25f,1f,.75f)))
        assertNull(transform.toSource(Box(0f,0f,1f,.2f)))
    }
    @Test fun malformedPlanesCannotReadBeyondTheirBuffers() {
        listOf<() -> Unit>({PixelTransforms.i420(ByteArray(5),2,2)}, {PixelTransforms.i420(ByteArray(12),3,2)},
            {PixelTransforms.rgba(ByteArray(7),2,1,8)}, {PixelTransforms.orient(PixelFrame(1,1,intArrayOf(1)),45)}).forEach {
            try { it(); fail("Invalid frame accepted") } catch (_:IllegalArgumentException) { }
        }
    }
    @Test fun qualitySeparatesFlatExposureFromDetailedPatternWithoutClaimingText() {
        val flat=ImageQuality.measure(PixelFrame(8,8,IntArray(64){0xff808080.toInt()}))
        assertTrue(flat.lowDetail); assertFalse(flat.dark); assertFalse(flat.overexposed)
        val edges=ImageQuality.measure(PixelFrame(8,8,IntArray(64){if((it/8+it%8)%2==0) -1 else 0xff000000.toInt()}))
        assertTrue(edges.detailVariance>flat.detailVariance)
        assertTrue(ImageQuality.measure(PixelFrame(8,8,IntArray(64){0xff000000.toInt()})).dark)
    }
}
