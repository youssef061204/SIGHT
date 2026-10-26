package org.sight.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetaFrameCalibrationTest {
    private val frame = MetaVideoPacket(ByteArray(12), 4, 2, 1, 100, "meta-dat-mock")

    @Test fun exactPackedFixtureRetainsIndependentClocks() {
        MetaFrameCalibration.mockFixture().requireCompatible(frame)
        assertEquals(1L, frame.presentationTimeUs)
        assertEquals(100L, frame.receivedElapsedNs)
    }

    @Test fun mockCalibrationCannotAuthorizePhysicalGlasses() {
        assertThrows(IllegalArgumentException::class.java) {
            MetaFrameCalibration.mockFixture().requireCompatible(frame.copy(source = "meta-dat-glasses"))
        }
    }

    @Test fun rejectsPaddingOddDimensionsAndInventedClockAlignment() {
        val profile = MetaFrameCalibration.mockFixture()
        listOf(frame.copy(bytes = ByteArray(16)), frame.copy(width = 3), frame.copy(captureClockSynchronized = true)).forEach {
            assertThrows(IllegalArgumentException::class.java) { profile.requireCompatible(it) }
        }
    }

    @Test fun orientationAndCalibrationRecordMustBeExplicit() {
        assertThrows(IllegalArgumentException::class.java) { MetaFrameCalibration(false, 45, false, "chart") }
        assertThrows(IllegalArgumentException::class.java) { MetaFrameCalibration(false, 0, false, "") }
    }
}
