package org.sight.core

import java.util.concurrent.atomic.AtomicReference

enum class ThermalLevel { NORMAL, WARM, HOT, CRITICAL }
enum class Workload { DETECTION, OCR }
data class InferencePlan(val runDetection: Boolean, val runOcr: Boolean)
data class SchedulingConfig(val exploreIntervalMs: Long = 250, val findIntervalMs: Long = 150,
    val readDetectionIntervalMs: Long = 250, val ocrIntervalMs: Long = 1_000) {
    init { require(listOf(exploreIntervalMs, findIntervalMs, readDetectionIntervalMs, ocrIntervalMs).all { it in 1..60_000 }) }
}

/** Admission control, not an inference queue. Device adapters separately bound in-flight work to one. */
class AdaptiveScheduler(private val config: SchedulingConfig = SchedulingConfig()) {
    private var lastDetectionMs: Long? = null
    private var lastOcrMs: Long? = null

    @Synchronized
    fun shouldAnalyze(nowMs: Long, mode: Workload, thermal: ThermalLevel = ThermalLevel.NORMAL): Boolean {
        return admit(nowMs, mode, thermal, if (mode == Workload.DETECTION) config.exploreIntervalMs else config.ocrIntervalMs)
    }

    private fun admit(nowMs: Long, mode: Workload, thermal: ThermalLevel, baseIntervalMs: Long): Boolean {
        require(nowMs >= 0)
        if (thermal == ThermalLevel.CRITICAL) return false
        val multiplier = when (thermal) { ThermalLevel.NORMAL -> 1; ThermalLevel.WARM -> 2; ThermalLevel.HOT -> 4; ThermalLevel.CRITICAL -> 8 }
        val previous = if (mode == Workload.DETECTION) lastDetectionMs else lastOcrMs
        val interval = baseIntervalMs * multiplier
        if (previous != null && nowMs - previous < interval) return false
        if (mode == Workload.DETECTION) lastDetectionMs = nowMs else lastOcrMs = nowMs
        return true
    }

    @Synchronized
    fun plan(nowMs: Long, goal: GoalType, thermal: ThermalLevel = ThermalLevel.NORMAL): InferencePlan {
        val interval = when (goal) { GoalType.FIND -> config.findIntervalMs; GoalType.READ -> config.readDetectionIntervalMs; else -> config.exploreIntervalMs }
        return InferencePlan(admit(nowMs, Workload.DETECTION, thermal, interval),
            goal == GoalType.READ && admit(nowMs, Workload.OCR, thermal, config.ocrIntervalMs))
    }

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
