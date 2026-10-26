package org.sight.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.core.*
import org.sight.perception.DetectorProfile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android inference on a licensed fixed photograph; this is not live-camera throughput. */
@RunWith(AndroidJUnit4::class)
class InspectionRuntimeTest {
    @Test fun bothBundledProfilesRunAndReportWarmAndroidLatencyWithoutRecyclingSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("coco_350148.jpg").use(BitmapFactory::decodeStream)
        val results = JSONArray()
        for (profile in DetectorProfile.entries) {
            val init = SystemClock.elapsedRealtime()
            MediaPipeDetector(instrumentation.targetContext,profile).use { detector ->
                val initializationMs = SystemClock.elapsedRealtime()-init
                val warmups = 3
                repeat(warmups) { detector.detect(bitmap,SystemClock.elapsedRealtime()) }
                val times = mutableListOf<Long>()
                var detections = emptyList<Detection>()
                repeat(30) {
                    val start = SystemClock.elapsedRealtime()
                    detections = detector.detect(bitmap,start)
                    times += SystemClock.elapsedRealtime()-start
                    assertFalse(bitmap.isRecycled)
                }
                assertTrue("Known person must remain detectable in ${profile.name}",detections.any { it.category == "person" && it.confidence >= .65f })
                val sorted = times.sorted()
                results.put(JSONObject().put("modelId",detector.modelId).put("profile",profile.name)
                    .put("initializationMs",initializationMs).put("warmups",warmups).put("samples",JSONArray(times))
                    .put("p50Ms",sorted[14]).put("p95Ms",sorted[28]).put("p99Ms",JSONObject.NULL)
                    .put("p99Reason","30 samples cannot support a useful tail estimate")
                    .put("processPssKb",Debug.getPss()).put("detections",detections.size))
            }
        }
        bitmap.recycle()
        File(instrumentation.targetContext.cacheDir,"inspection-runtime-v3.json").writeText(JSONObject()
            .put("scope","Android emulator or device fixed-photo MediaPipe inference; not camera FPS or phone battery")
            .put("androidApi",Build.VERSION.SDK_INT).put("hardware",Build.HARDWARE).put("model",Build.MODEL)
            .put("profiles",results).toString(2))
    }
    @Test fun asynchronousTextKeepsItsCaptureEvidenceWhileObjectInferenceContinues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val textBitmap = Bitmap.createBitmap(1000,300,Bitmap.Config.ARGB_8888)
        Canvas(textBitmap).apply { drawColor(Color.WHITE); drawText("TOTAL $12.99",30f,140f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=80f }) }
        val source = "async-native-fixture"
        val captured = SystemClock.elapsedRealtime()
        val evidence = Evidence(1,captured,source,TimeBasis.CAPTURE,"mlkit-latin-16.0.1",1)
        val done = CountDownLatch(1)
        val output = AtomicReference<Pair<Evidence,List<TextObservation>>>()
        AsyncTextReader(context) { actual,text,_ -> output.set(actual to text); done.countDown() }.use { reader ->
            assertTrue(reader.submit(textBitmap,evidence))
            // The reader copied the input. Its native task has no ownership over this caller frame.
            textBitmap.recycle()
            val photo = instrumentation.context.assets.open("coco_350148.jpg").use(BitmapFactory::decodeStream)
            MediaPipeDetector(context).use { detector ->
                repeat(2) { assertTrue(detector.detect(photo,SystemClock.elapsedRealtime()).any { it.category=="person" }) }
            }
            photo.recycle()
            assertTrue("Bundled OCR should finish independently",done.await(45,TimeUnit.SECONDS))
            assertEquals(evidence,output.get().first)
            assertTrue(output.get().second.any { it.text.contains("12.99") })
        }
    }
}
