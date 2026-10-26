package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.sight.core.Evidence
import org.sight.core.TextObservation
import org.sight.perception.normalizedBox
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OcrTiming(val frameId: Long, val submittedAtMs: Long, val completedAtMs: Long,
    val capturedAtMs: Long, val timeBasis: String, val blocks: Int, val status: String)

/** One in-flight native task per retained controller; object inference never waits for OCR. */
class AsyncTextReader(context: Context, private val result: (Evidence, List<TextObservation>, OcrTiming) -> Unit) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val completion = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile var startedAtMs: Long = 0; private set
    val inFlight: Boolean get() = busy.get()
    @Synchronized fun submit(bitmap: Bitmap, evidence: Evidence): Boolean {
        if (closed.get() || !busy.compareAndSet(false, true)) return false
        val copy = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        catch (e: Throwable) { busy.set(false); throw e }
        startedAtMs = SystemClock.elapsedRealtime()
        val submitted = startedAtMs
        val task = try { recognizer.process(InputImage.fromBitmap(copy, 0)) }
        catch (e: Throwable) { copy.recycle(); busy.set(false); throw e }
        task.addOnCompleteListener(completion) { completed ->
            try {
                val blocks = if (completed.isSuccessful) completed.result.textBlocks.mapNotNull { block ->
                    val bounds = block.boundingBox ?: return@mapNotNull null
                    val box = normalizedBox(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), copy.width, copy.height)
                        ?: return@mapNotNull null
                    TextObservation(block.text, box)
                } else emptyList()
                if (!closed.get()) result(evidence, blocks, OcrTiming(evidence.frameId, submitted, SystemClock.elapsedRealtime(),
                    evidence.capturedAtMs, evidence.timeBasis.name, blocks.size, if (completed.isSuccessful) "completed" else "failed"))
            } finally {
                copy.recycle()
                synchronized(this) {
                    busy.set(false)
                    if (closed.get()) { recognizer.close(); completion.shutdown() }
                }
            }
        }
        return true
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // ML Kit has no public cancel primitive. Do not recycle an image borrowed by native OCR.
        if (!busy.get()) { recognizer.close(); completion.shutdown() }
    }
}
