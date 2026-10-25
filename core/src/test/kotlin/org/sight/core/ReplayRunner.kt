package org.sight.core

import java.io.File

/**
 * Repeats/perturbations are supplied by the fixture; this runner does not execute a detector or
 * establish tracking accuracy. It executes the production Kotlin policies without reimplementing them.
 */
object ReplayRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "ReplayRunner input.tsv output.jsonl" }
        val engine = SightEngine()
        val output = File(args[1])
        output.parentFile?.mkdirs()
        var frames = 0
        var rejected = 0
        var naiveMentions = 0
        var coreUtterances = 0
        var coreMentions = 0
        var findCompletions = 0
        var previousPhase: GoalPhase? = null
        output.bufferedWriter().use { writer ->
            fun record(event: String, nowMs: Long, update: EngineUpdate, observation: FrameObservation? = null) {
                val naive = observation?.detections?.count { it.confidence >= 0.5f } ?: 0
                val live = update.announcements.filter { it.kind in setOf(AnnouncementKind.LIVE_SCENE, AnnouncementKind.FIND_RESULT, AnnouncementKind.OCR) }
                val rejectedCodes = observation?.let { frame -> update.diagnostics.filter { it.evidence == frame.evidence && it.code in setOf("INPUT_UNAVAILABLE", "LIFECYCLE_RESULT", "FUTURE_FRAME", "STALE_FRAME", "OUT_OF_ORDER_FRAME") }.map { it.code }.distinct() }.orEmpty()
                if (observation != null) frames++
                if (rejectedCodes.isNotEmpty()) rejected++
                naiveMentions += naive
                coreUtterances += live.size
                coreMentions += live.sumOf { it.trackIds.size }
                if (update.goal.type == GoalType.FIND && update.goal.phase == GoalPhase.COMPLETED && previousPhase != GoalPhase.COMPLETED) findCompletions++
                previousPhase = update.goal.phase
                writer.appendLine("{" +
                    "\"type\":\"event\",\"event\":${json(event)},\"nowMs\":$nowMs," +
                    "\"frameId\":${observation?.evidence?.frameId ?: "null"},\"naiveObjectMentions\":$naive," +
                    "\"coreLiveUtterances\":${live.size},\"coreObjectMentions\":${live.sumOf { it.trackIds.size }}," +
                    "\"goal\":${json(update.goal.type.name)},\"phase\":${json(update.goal.phase.name)}," +
                    "\"reliability\":${json(update.reliability.name)},\"visibleTrackIds\":[${update.entities.filter { it.visible }.joinToString { it.id.toString() }}]," +
                    "\"rejectedCodes\":[${rejectedCodes.joinToString { json(it) }}]," +
                    "\"announcements\":[${update.announcements.joinToString { "{\"kind\":${json(it.kind.name)},\"text\":${json(it.text)},\"speechAdmitted\":${engine.isAnnouncementValid(it, nowMs)}}" }}]}")
            }
            var pendingEvidence: Evidence? = null
            var pendingNow = 0L
            val pendingDetections = mutableListOf<Detection>()
            fun flush() {
                val evidence = pendingEvidence ?: return
                val frame = FrameObservation(evidence, pendingDetections.toList())
                record("FRAME", pendingNow, engine.onFrame(frame, pendingNow), frame)
                pendingEvidence = null
                pendingDetections.clear()
            }
            File(args[0]).forEachLine { raw ->
                if (raw.isBlank() || raw.startsWith("#")) return@forEachLine
                val cells = raw.split('\t')
                when (cells.first()) {
                    "frameId" -> Unit
                    "INPUT" -> {
                        flush()
                        require(cells.size == 3)
                        val now = cells[1].toLong()
                        record("INPUT", now, engine.setInputAvailable(cells[2].toBooleanStrict(), now))
                    }
                    "COMMAND" -> {
                        flush()
                        require(cells.size >= 3)
                        val now = cells[1].toLong()
                        record("COMMAND", now, engine.command(cells.drop(2).joinToString("\t"), now))
                    }
                    "TICK" -> {
                        flush()
                        val now = cells[1].toLong()
                        record("TICK", now, engine.tick(now))
                    }
                    else -> {
                        require(cells.size >= 5) { "Expected frameId capturedAtMs nowMs sourceId category confidence left top right bottom" }
                        val evidence = Evidence(cells[0].toLong(), cells[1].toLong(), cells[3])
                        val now = cells[2].toLong()
                        if (pendingEvidence != evidence || pendingNow != now) flush()
                        pendingEvidence = evidence
                        pendingNow = now
                        if (cells[4].isNotBlank()) {
                            require(cells.size == 10)
                            pendingDetections += Detection(cells[4], cells[5].toFloat(), Box(cells[6].toFloat(), cells[7].toFloat(), cells[8].toFloat(), cells[9].toFloat()))
                        }
                    }
                }
            }
            flush()
            writer.appendLine("{\"type\":\"summary\",\"frames\":$frames,\"rejectedFrames\":$rejected,\"naiveObjectMentions\":$naiveMentions,\"coreLiveUtterances\":$coreUtterances,\"coreObjectMentions\":$coreMentions,\"findCompletions\":$findCompletions,\"note\":\"Naive baseline emits each confidence>=0.5 detection without persistence, novelty or freshness checks. Core utterances may group objects; compare object mentions separately. Repeated-photo fixtures cannot establish temporal tracking accuracy or real camera performance.\"}")
        }
        println("Replay written to ${output.absolutePath}")
    }

    private fun json(value: String): String = "\"" + buildString {
        for (character in value) append(when (character) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (character.code < 32) "\\u%04x".format(character.code) else character.toString()
        })
    } + "\""
}
