package org.sight.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sight.core.*
import java.util.concurrent.TimeUnit

/** Requires an Android device. These tests are not substitutes for live camera or accessibility trials. */
@RunWith(AndroidJUnit4::class)
class NativePerceptionTest {
    @Test fun bundledDetectorFeedsActualResultsIntoFindWorkflow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("coco_350148.jpg").use(BitmapFactory::decodeStream)
        MediaPipeDetector(instrumentation.targetContext).use { model ->
            val engine = SightEngine()
            engine.command("find a person", 1_000)
            val first = model.detect(bitmap, 1_000)
            assertFalse("Detector must preserve the camera frame for OCR and its owner", bitmap.isRecycled)
            assertTrue("Known smoke fixture should detect its annotated person", first.any { it.category == "person" && it.confidence >= .65f })
            assertEquals(GoalPhase.VERIFYING, engine.onFrame(FrameObservation(Evidence(1, 1_000, "test-fixture"), first), 1_000).goal.phase)
            val second = model.detect(bitmap, 1_300)
            assertFalse(bitmap.isRecycled)
            val result = engine.onFrame(FrameObservation(Evidence(2, 1_300, "test-fixture"), second), 1_300)
            assertEquals(GoalPhase.COMPLETED, result.goal.phase)
            assertTrue(result.announcements.any { it.kind == AnnouncementKind.FIND_RESULT && it.evidence?.sourceId == "test-fixture" })
            assertFalse(engine.isAnnouncementValid(result.announcements.first(), 3_000))
        }
        bitmap.recycle()
    }

    @Test fun bundledOcrReadsGeneratedTextAndRetainsOriginal() {
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("SIGHT PRICE $12.99", 40f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 70f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { recognizer ->
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            assertTrue(text.text.contains("12.99"))
            val engine = SightEngine()
            engine.command("just tell me the price", 100)
            val result = engine.onFrame(FrameObservation(Evidence(1, 200, "generated-ocr-fixture"), emptyList(),
                listOf(TextObservation(text.text, Box(0f, 0f, 1f, 1f)))), 200)
            assertEquals(text.text, result.extractedText)
            assertTrue(result.announcements.any { it.text.contains("12.99") })
        }
        bitmap.recycle()
    }
}
