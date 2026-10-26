package org.sight.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TemporalMetricTest {
    @Test fun `global identity matching chooses one prediction per truth and preserves the best complete assignment`() {
        val counts = mapOf(("A" to 1L) to 4, ("A" to 2L) to 1, ("B" to 1L) to 3, ("B" to 2L) to 4)
        assertEquals(8, TemporalEvaluation.maximumIdentityMatches(counts))
        assertEquals(4, TemporalEvaluation.maximumIdentityMatches(mapOf(("A" to 1L) to 4, ("B" to 1L) to 3)))
        assertEquals(0, TemporalEvaluation.maximumIdentityMatches(emptyMap()))
    }

    @Test fun `fragmenting a labelled identity cannot claim all fragments as the same matched identity`() {
        assertEquals(3, TemporalEvaluation.maximumIdentityMatches(mapOf(("A" to 1L) to 3, ("A" to 2L) to 2, ("A" to 3L) to 1)))
        assertEquals(5, TemporalEvaluation.maximumIdentityMatches(mapOf(("A" to 1L) to 3, ("B" to 2L) to 2)))
    }
}
