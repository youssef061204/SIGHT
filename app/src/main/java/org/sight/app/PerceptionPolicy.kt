package org.sight.app

import org.sight.core.Evidence
import org.sight.core.GoalPhase
import org.sight.core.GoalState
import org.sight.core.GoalType
import org.sight.perception.DetectorProfile
import java.util.Locale

enum class DetectorMode { AUTO, FAST, INSPECT }

sealed interface MemoryDeletion {
    data object All : MemoryDeletion
    data class Named(val name: String) : MemoryDeletion
}

/** Pure decisions shared by controller wiring and deterministic JVM tests. */
object PerceptionPolicy {
    fun operationalGoal(goal: GoalState): GoalType = when (goal.type) {
        GoalType.READ -> if (goal.phase == GoalPhase.SEARCHING) GoalType.READ else GoalType.IDLE
        GoalType.FIND -> if (goal.phase in setOf(GoalPhase.SEARCHING, GoalPhase.VERIFYING, GoalPhase.COMPLETED)) GoalType.FIND else GoalType.IDLE
        GoalType.EXPLORE -> if (goal.phase == GoalPhase.ACTIVE) GoalType.EXPLORE else GoalType.IDLE
        GoalType.IDLE -> GoalType.IDLE
    }

    fun detectorProfile(mode: DetectorMode, operationalGoal: GoalType, inspectUntilMs: Long, nowMs: Long): DetectorProfile = when (mode) {
        DetectorMode.FAST -> DetectorProfile.FAST
        DetectorMode.INSPECT -> DetectorProfile.INSPECT
        DetectorMode.AUTO -> if (operationalGoal == GoalType.FIND || operationalGoal != GoalType.IDLE && nowMs < inspectUntilMs)
            DetectorProfile.INSPECT else DetectorProfile.FAST
    }

    /** Native task failure is a status fact, independent of the detector's model identifier. */
    fun canReportOcrFailure(goal: GoalState, running: Boolean, activeSource: String?, geometryEpoch: Long?,
                            evidence: Evidence, nowMs: Long, maxSnapshotAgeMs: Long = 8_000): Boolean =
        running && goal.type == GoalType.READ && goal.phase == GoalPhase.SEARCHING &&
            (goal.deadlineAtMs?.let { nowMs < it } ?: true) &&
            activeSource == evidence.sourceId && geometryEpoch == evidence.geometryEpoch &&
            evidence.capturedAtMs >= goal.startedAtMs && nowMs >= evidence.capturedAtMs &&
            nowMs - evidence.capturedAtMs <= maxSnapshotAgeMs

    /** Match the core's punctuation/whitespace/case normalization before matching delete aliases. */
    fun memoryDeletion(raw: String): MemoryDeletion? {
        val command = raw.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        if (command in setOf("forget everything", "forget all", "delete memories", "clear memory", "delete all memories")) return MemoryDeletion.All
        return command.takeIf { it.startsWith("forget ") }?.removePrefix("forget ")?.trim()?.takeIf { it.isNotEmpty() }?.let { MemoryDeletion.Named(it) }
    }
}
