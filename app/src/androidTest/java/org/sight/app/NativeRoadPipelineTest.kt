package org.sight.app

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.sight.core.*
import org.sight.perception.RoadSegmenter
import java.io.File
import java.security.MessageDigest

/** Real native TFLite on a credited selection photo; independent held-out accuracy is separate. */
class NativeRoadPipelineTest {
    @Test fun realRoadModelPreservesFrameOwnershipGeometryAndReportsActualLatency(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bytes=instrumentation.context.assets.open("pedestrian/road-context.jpg").use { it.readBytes() }
        val provenance=instrumentation.context.assets.open("pedestrian/provenance.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(provenance.getString("imageSha256"),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        val original=Evidence(7,SystemClock.elapsedRealtime(),"native-road-photo",TimeBasis.CAPTURE,"fixture",3)
        val times=mutableListOf<Long>()
        val initAt=SystemClock.elapsedRealtime()
        RoadSegmenter(instrumentation.targetContext).use { model ->
            val initialization=SystemClock.elapsedRealtime()-initAt
            var observation=model.segment(bitmap,original)
            repeat(2){ model.segment(bitmap,original) }
            repeat(30){
                val started=SystemClock.elapsedRealtime(); observation=model.segment(bitmap,original)
                times+=SystemClock.elapsedRealtime()-started
            }
            assertFalse(bitmap.isRecycled)
            assertEquals(original.copy(modelId=RoadSegmenter.MODEL_ID),observation.evidence)
            assertTrue(observation.regions.all { it.label in setOf(PedestrianSurface.ROAD,PedestrianSurface.CURB,PedestrianSurface.ROAD_MARKING) })
            val road=observation.regions.filter { it.label==PedestrianSurface.ROAD }.maxByOrNull { it.coverage }
            assertNotNull("Known credited road-positive selection scene must survive native preprocessing",road)
            assertEquals("Native component area agrees with independent desktop reference",.3652f,road!!.coverage,.05f)
            assertEquals(.5098f,road.box.top,.05f)
            val sorted=times.sorted()
            File(instrumentation.targetContext.cacheDir,"native-road-pipeline-proof.json").writeText(JSONObject()
                .put("scope","Fixed credited selection photograph; real TFLite Android preprocessing/inference/components; not camera FPS or held-out accuracy")
                .put("modelId",RoadSegmenter.MODEL_ID).put("initializationMs",initialization)
                .put("warmups",3).put("samplesMs",JSONArray(times)).put("p50Ms",sorted[14]).put("p95Ms",sorted[28])
                .put("p99Measured",false).put("processPssKb",Debug.getPss()).put("roadCoverage",road.coverage)
                .put("roadMeanModelPosterior",road.score).put("sourceBitmapOwnedByCaller",true)
                .put("captureProvenanceRetained",true).put("automaticRoadVoiceEnabled",false).toString(2))
        }
        bitmap.recycle()
    }
}
