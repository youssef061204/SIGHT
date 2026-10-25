package org.sight.core

import java.util.concurrent.atomic.AtomicReference

enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }
enum class Workload { DETECTION, OCR }
data class InferencePlan(val runDetection: Boolean, val runOcr: Boolean)

/** Admission control, not an inference queue. Device adapters separately bound in-flight work to one. */
class AdaptiveScheduler {
    private var lastDetectionMs: Long? = null
    private var lastOcrMs: Long? = null

    @Synchronized
    fun shouldAnalyze(nowMs: Long, mode: Workload, thermal: ThermalLevel = ThermalLevel.NORMAL): Boolean {
        require(nowMs >= 0)
        if (thermal == ThermalLevel.CRITICAL) return false
        val multiplier = when (thermal) { ThermalLevel.NORMAL -> 1; ThermalLevel.WARM -> 2; ThermalLevel.HOT -> 4; ThermalLevel.CRITICAL -> 8 }
        val previous = if (mode == Workload.DETECTION) lastDetectionMs else lastOcrMs
        val interval = (if (mode == Workload.DETECTION) 250L else 1_000L) * multiplier
        if (previous != null && nowMs - previous < interval) return false
        if (mode == Workload.DETECTION) lastDetectionMs = nowMs else lastOcrMs = nowMs
        return true
    }

    @Synchronized
    fun plan(nowMs: Long, goal: GoalType, thermal: ThermalLevel = ThermalLevel.NORMAL): InferencePlan =
        InferencePlan(shouldAnalyze(nowMs, Workload.DETECTION, thermal), goal == GoalType.READ && shouldAnalyze(nowMs, Workload.OCR, thermal))

    @Synchronized
    fun reset() { lastDetectionMs = null; lastOcrMs = null }
}

/** Capacity one: producers replace pending work; ownership of dropped resources is explicit. */
class LatestFrameSlot<T : Any>(private val onDrop: (T) -> Unit = {}) : AutoCloseable {
    private val value = AtomicReference<T?>(null)
    private var closed = false

    @Synchronized
    fun offer(frame: T) {
        if (closed) { onDrop(frame); return }
        value.getAndSet(frame)?.let(onDrop)
    }
    @Synchronized fun poll(): T? = value.getAndSet(null)
    @Synchronized override fun close() {
        closed = true
        value.getAndSet(null)?.let(onDrop)
    }
}
