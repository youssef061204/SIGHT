package org.sight.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.perception.FramePixels
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class PixelPipelineTest {
    @Test fun androidRgbaHonorsBufferWindowChannelsAndRowPadding() {
        val buffer = ByteBuffer.allocateDirect(28)
        buffer.putInt(0x77777777) // Not part of the declared buffer window.
        buffer.put(byteArrayOf(-1,0,0,-1, 0,-1,0,-1, 99,99,99,99))
        buffer.put(byteArrayOf(0,0,-1,-1, -1,-1,-1,-1, 99,99,99,99))
        buffer.position(4)
        val bitmap = FramePixels.rgba(buffer, 2, 2, 12)
        assertEquals(4, buffer.position())
        assertEquals(Color.RED, bitmap.getPixel(0,0))
        assertEquals(Color.GREEN, bitmap.getPixel(1,0))
        assertEquals(Color.BLUE, bitmap.getPixel(0,1))
        assertEquals(Color.WHITE, bitmap.getPixel(1,1))
        bitmap.recycle()
    }
    @Test fun androidRotationAndMirroringMatchIndependentLabeledPixels() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
        val original = Bitmap.createBitmap(colors,3,2,Bitmap.Config.ARGB_8888)
        val expected = mapOf(
            0 to colors,
            90 to intArrayOf(Color.CYAN,Color.RED,Color.MAGENTA,Color.GREEN,Color.YELLOW,Color.BLUE),
            180 to colors.reversedArray(),
            270 to intArrayOf(Color.BLUE,Color.YELLOW,Color.GREEN,Color.MAGENTA,Color.RED,Color.CYAN))
        for ((degrees, pixels) in expected) for (mirror in listOf(false,true)) {
            val oriented = FramePixels.orient(original,degrees,mirror)
            val width = if (degrees % 180 == 0) 3 else 2
            val actual = IntArray(6); oriented.getPixels(actual,0,width,0,0,width,6/width)
            val golden = if (mirror) pixels.toList().chunked(width).flatMap { it.reversed() }.toIntArray() else pixels
            assertArrayEquals("rotation=$degrees mirror=$mirror",golden,actual)
            if (oriented !== original) oriented.recycle()
            assertFalse(original.isRecycled)
        }
        original.recycle()
    }
    @Test fun strictI420MatchesKnownBt601LimitedRedAndRejectsAmbiguousPadding() {
        // BT.601 limited red: Y=81 U=90 V=240. Four pixels share the U/V sample.
        val bitmap = FramePixels.i420(byteArrayOf(81,81,81,81,90,-16),2,2)
        assertTrue(Color.red(bitmap.getPixel(0,0)) >= 250)
        assertTrue(Color.green(bitmap.getPixel(0,0)) <= 3)
        assertTrue(Color.blue(bitmap.getPixel(0,0)) <= 3)
        bitmap.recycle()
        assertThrows(IllegalArgumentException::class.java) { FramePixels.i420(ByteArray(7),2,2) }
    }
}
