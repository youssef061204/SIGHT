package org.sight.core

import org.junit.Assert.*
import org.junit.Test

class ProductSafetyTest {
    @Test fun crossingClearanceIsBlockedRegardlessOfOrigin() {
        listOf("It's safe to cross", "You can cross now", "The road is clear", "No cars are coming", "Walk into the intersection").forEach { text ->
            AnnouncementKind.entries.forEach { kind -> assertFalse("$kind: $text",SpeechSafety.allows(Announcement(text,SpeechPriority.STATUS,kind=kind),1_000)) }
        }
        assertTrue(SpeechSafety.allows(Announcement("I cannot assess crossing safety. The mapped route turns left in approximately 80 meters.",SpeechPriority.NAVIGATION,kind=AnnouncementKind.NAVIGATION),1_000))
    }
    @Test fun visualDistancesAndExpiredProviderCuesCannotPassFinalAdmission() {
        assertFalse(SpeechSafety.allows(Announcement("A car is 3 meters away",SpeechPriority.ENVIRONMENT,kind=AnnouncementKind.ENVIRONMENT),1_000))
        assertFalse(SpeechSafety.allows(Announcement("Turn ahead",SpeechPriority.NAVIGATION,kind=AnnouncementKind.NAVIGATION,validUntilMs=999),1_000))
        assertFalse("Expired input is a silent freshness rejection, not an unsafe crossing claim",SpeechSafety.prohibitsClaim(
            Announcement("Turn ahead",SpeechPriority.NAVIGATION,kind=AnnouncementKind.NAVIGATION,validUntilMs=999)))
    }
    @Test fun surfaceEvidenceNeedsIndependentFramesAndNeverImpliesCrosswalkOrClearance() {
        val awareness=EnvironmentalAwareness(setOf(PedestrianSurface.ROAD))
        fun observation(id:Long, at:Long)=SurfaceObservation(Evidence(id,at,"camera/1",modelId="road-model"),
            listOf(SurfaceRegion(PedestrianSurface.ROAD,.95f,.4f,Box(.1f,.4f,.9f,1f))))
        assertTrue(awareness.onSurfaces(observation(1,1_000),1_100,true).isEmpty())
        val event=awareness.onSurfaces(observation(2,2_000),2_100,true).single()
        assertTrue(awareness.isSupported(event,2_200))
        assertFalse(event.text.contains("crosswalk"))
        assertFalse(awareness.isSupported(event,3_501))
        assertTrue(awareness.onSurfaces(observation(3,3_000),3_100,true).isEmpty())
        assertTrue("An admitted sentence may continue only with newer supported evidence",awareness.isSupported(event,3_600,ongoing=true))
        assertFalse(awareness.isSupported(event,4_501,ongoing=true))
        awareness.reset(); assertFalse(awareness.isSupported(event,2_200))
    }
    @Test fun unsupportedSurfaceClassesAndUnstableObjectsAreSilent() {
        val awareness=EnvironmentalAwareness(setOf(PedestrianSurface.ROAD))
        val marked=SurfaceObservation(Evidence(1,1_000,modelId="marking"),listOf(SurfaceRegion(PedestrianSurface.ROAD_MARKING,.99f,.5f,Box(0f,0f,1f,1f))))
        awareness.onSurfaces(marked,1_100,true)
        assertTrue(awareness.onSurfaces(marked.copy(evidence=marked.evidence.copy(frameId=2,capturedAtMs=2_000)),2_100,true).isEmpty())
    }
    @Test fun trafficNeedsStableEvidenceAndLosesSupportWhenItsImageDirectionChanges() {
        val engine=SightEngine()
        val awareness=EnvironmentalAwareness()
        fun frame(id:Long,at:Long,box:Box)=engine.onFrame(FrameObservation(Evidence(id,at),
            listOf(Detection("car",.9f,box))),at+50)
        val left=Box(.2f,.2f,.45f,.7f)
        assertTrue(awareness.onObjects(frame(1,1_000,left),1_050,true).isEmpty())
        val event=awareness.onObjects(frame(2,1_400,left),1_450,true).single()
        assertTrue(awareness.isSupported(event,1_500))
        assertTrue(event.text.contains("Movement is unverified"))
        awareness.onObjects(frame(3,1_800,Box(.3f,.2f,.55f,.7f)),1_850,true)
        assertFalse("Previous left-image claim cannot continue after current direction changes",awareness.isSupported(event,1_900,ongoing=true))
    }
}
