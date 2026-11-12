package org.sight.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.sight.core.Evidence
import org.sight.core.SurfaceObservation
import org.sight.perception.RoadSegmenter
import java.util.concurrent.Executors

data class RoadTiming(val frameId:Long,val capturedAtMs:Long,val completedAtMs:Long,val inferenceMs:Long,val status:String)

/** One owned copy and native task. New frames are dropped while busy; no unbounded queue. */
class AsyncRoadReader(context:Context, private val result:(SurfaceObservation?,RoadTiming)->Unit):AutoCloseable {
    private val application=context.applicationContext
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var closed=false
    @Volatile var inFlight=false; private set
    private var segmenter:RoadSegmenter?=null
    private var lastAcceptedAtMs=-10_000L

    @Synchronized fun submit(bitmap:Bitmap,evidence:Evidence):Boolean {
        val at=SystemClock.elapsedRealtime()
        if(closed || inFlight || at-lastAcceptedAtMs<2_500) return false
        val copy=try { bitmap.copy(Bitmap.Config.ARGB_8888,false) ?: return false } catch(_:OutOfMemoryError){ return false }
        inFlight=true; lastAcceptedAtMs=at
        executor.execute {
            val started=SystemClock.elapsedRealtime()
            var observation:SurfaceObservation?=null
            var status="completed"
            try {
                val model=segmenter ?: RoadSegmenter(application).also { segmenter=it }
                observation=model.segment(copy,evidence)
            } catch(_:Exception){ status="failed" } catch(_:LinkageError){ status="unavailable" } catch(_:OutOfMemoryError){ status="low-memory" }
            finally { copy.recycle(); inFlight=false }
            val completed=SystemClock.elapsedRealtime()
            val timing=RoadTiming(evidence.frameId,evidence.capturedAtMs,completed,completed-started,status)
            main.post { synchronized(this) { if(!closed) result(observation,timing) } }
        }
        return true
    }
    @Synchronized override fun close() {
        if(closed) return
        closed=true
        executor.execute { segmenter?.close(); segmenter=null }
        executor.shutdown()
    }
}
