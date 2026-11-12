package org.sight.core
import org.junit.Assert.*
import org.junit.Test

class TextLandmarkSearchTest {
    private val box=Box(.1f,.1f,.8f,.5f)
    @Test fun matchingSignTextDoesNotInventVisualObjectSupport() {
        val request=TextLandmarkSearch.fromCommand("find the elevator",1_000)!!
        val blocks=listOf(TextObservation("Elevators to the left",box),TextObservation("Office reception",box))
        assertEquals(listOf(blocks.first()),request.matching(Evidence(1,1_100),blocks,1_200))
        assertTrue(request.matching(Evidence(2,999),blocks,1_200).isEmpty())
        assertTrue(request.matching(Evidence(3,1_100),blocks,9_101).isEmpty())
        assertTrue(request.matching(Evidence(4,1_300),blocks,1_200).isEmpty())
    }
    @Test fun busStopRequiresSpecificTextAndSupportedObjectSearchStaysSeparate() {
        val search=TextLandmarkSearch.fromCommand("locate a bus stop",0)!!
        assertTrue(search.matching(Evidence(1,100),listOf(TextObservation("STOP",box)),200).isEmpty())
        assertEquals(1,search.matching(Evidence(2,200),listOf(TextObservation("BUS STOP",box)),300).size)
        assertNull(TextLandmarkSearch.fromCommand("find a chair",0))
    }
}
