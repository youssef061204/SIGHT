package org.sight.app

import org.junit.Assert.*
import org.junit.Test
import org.sight.core.Box

class NormalizedBoxTest {
    @Test fun clipsPartiallyVisibleObjectsWithoutInventingArea() {
        assertEquals(Box(0f, .1f, .5f, 1f), normalizedBox(-10f, 10f, 50f, 120f, 100, 100))
    }
    @Test fun rejectsInvalidAndEntirelyOutsideProposals() {
        assertNull(normalizedBox(100f, 10f, 200f, 50f, 100, 100))
        assertNull(normalizedBox(20f, 20f, 20f, 30f, 100, 100))
        assertNull(normalizedBox(Float.NaN, 0f, 10f, 10f, 100, 100))
        assertNull(normalizedBox(0f, 0f, 10f, 10f, 0, 100))
    }
}
