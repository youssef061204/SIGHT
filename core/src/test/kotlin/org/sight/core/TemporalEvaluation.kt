package org.sight.core

import java.io.File
import java.time.Instant
import kotlin.math.max

/**
 * Constructed labelled boxes, not camera recordings or detector benchmarks. Every variant receives
 * exactly the same truths, scores and missing detections. Identity metrics apply only to this fixture.
 */
object TemporalEvaluation {
    data class Label(val identity: String, val category: String, val box: Box)
    data class Sample(val atMs: Long, val truth: List<Label>, val detections: List<Detection>)
    data class Scenario(val name: String, val samples: List<Sample>)
    private data class Variant(val name: String, val config: EngineConfig)
    private data class Metrics(val observations: Int, val truthObservations: Int, val matchedObservations: Int, val identitySwitches: Int,
        val stableTransitions: Int, val identityTransitions: Int, val idTruePositive: Int,
        val completions: Int, val falseCompletions: Int, val liveUtterances: Int, val staleSpeechAdmissions: Int,
        val temporalRecallCorrect: Boolean, val activePrompts: Int) {
        val idf1: Double get() = if (observations + truthObservations == 0) 0.0 else 2.0 * idTruePositive / (observations + truthObservations)
        val stability: Double get() = if (identityTransitions == 0) 1.0 else stableTransitions.toDouble() / identityTransitions
        val missedRate: Double get() = if (truthObservations == 0) 0.0 else (truthObservations - matchedObservations).toDouble() / truthObservations
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "TemporalEvaluation output-directory" }
        val output = File(args.single()).apply { mkdirs() }
        val fixtures = scenarios()
        val variants = listOf(
            Variant("overlap_association_only", EngineConfig(useMotionPrediction = false)),
            Variant("without_world_memory", EngineConfig(enableWorldMemory = false)),
            Variant("without_goal_continuation", EngineConfig(continueFindAfterMatch = false)),
            Variant("without_active_perception", EngineConfig(enableActivePerception = false)),
            Variant("full_temporal_policy", EngineConfig()),
        )
        val records = variants.map { variant -> variant to measure(variant.config, fixtures) }
        File(output, "fixture.tsv").bufferedWriter().use { writer ->
            writer.appendLine("scenario\tatMs\tidentity\tcategory\tleft\ttop\tright\tbottom\tdetectorPresent\tconfidence")
            fixtures.forEach { scenario -> scenario.samples.forEach { sample -> sample.truth.forEach { label ->
                val detection = sample.detections.firstOrNull { it.category == label.category && it.box == label.box }
                writer.appendLine(listOf(scenario.name, sample.atMs, label.identity, label.category, label.box.left,
                    label.box.top, label.box.right, label.box.bottom, detection != null, detection?.confidence ?: "").joinToString("\t"))
            } } }
        }
        File(output, "detector-observations.tsv").bufferedWriter().use { writer ->
            writer.appendLine("scenario\tatMs\tcategory\tleft\ttop\tright\tbottom\tconfidence")
            fixtures.forEach { scenario -> scenario.samples.forEach { sample ->
                if (sample.detections.isEmpty()) writer.appendLine("${scenario.name}\t${sample.atMs}\t\t\t\t\t\t")
                sample.detections.forEach { detection -> writer.appendLine(listOf(scenario.name, sample.atMs, detection.category,
                    detection.box.left, detection.box.top, detection.box.right, detection.box.bottom, detection.confidence).joinToString("\t")) }
            } }
        }
        File(output, "summary.json").writeText(buildString {
            append("{\n  \"schema\":\"sight.synthetic-temporal-ablation.v1\",\n  \"recordedAtUtc\":${json(Instant.now().toString())},\n")
            append("  \"scope\":\"Deterministic constructed labelled image boxes. No real video, camera motion estimation, physical identity, detector accuracy, mobile speed or mobility validation.\",\n")
            append("  \"identityMetric\":\"IDF1 uses maximum one-to-one global identity assignment over actual observed boxes; missed detector frames count as IDFN. Identity switches compare each labelled object across its successive detected appearances.\",\n")
            append("  \"scenarioCount\":${fixtures.size},\n  \"variants\":[\n")
            records.forEachIndexed { index, (variant, metrics) ->
                append("    {\"name\":${json(variant.name)},\"observations\":${metrics.observations},\"truthObservations\":${metrics.truthObservations},")
                append("\"identitySwitches\":${metrics.identitySwitches},\"stableTransitions\":${metrics.stableTransitions},\"identityTransitions\":${metrics.identityTransitions},")
                append("\"idf1\":${metrics.idf1},\"identityStability\":${metrics.stability},\"missedObservationRate\":${metrics.missedRate},")
                append("\"findCompletions\":${metrics.completions},\"falseCompletions\":${metrics.falseCompletions},\"liveUtterances\":${metrics.liveUtterances},\"staleSpeechAdmissions\":${metrics.staleSpeechAdmissions},")
                append("\"temporalRecallCorrect\":${metrics.temporalRecallCorrect},\"groundedActivePrompts\":${metrics.activePrompts}}${if (index == records.lastIndex) "" else ","}\n")
            }
            append("  ]\n}\n")
        })
        File(output, "report.md").writeText(buildString {
            appendLine("# Constructed temporal component ablation")
            appendLine()
            appendLine("These are deterministic, labelled synthetic image-box trajectories. They are **not** real video or detector measurements. Every variant receives the same observations, including explicitly missing detections and score dips. This evaluates policy mechanisms in the fixture domain only. The overlap-only row ablates prediction in the upgraded engine; it is not a rerun of the complete original SIGHT binary.")
            appendLine()
            appendLine("| Component configuration | ID switches | IDF1 | Stable identity transitions | Missed observations | Find completions / false | Live utterances | Stale speech admitted | Historical recall | Grounded active prompts |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---|---:|")
            records.forEach { (variant, m) -> appendLine("| ${variant.name} | ${m.identitySwitches} | ${format(m.idf1)} | ${m.stableTransitions}/${m.identityTransitions} | ${m.truthObservations - m.matchedObservations}/${m.truthObservations} | ${m.completions} / ${m.falseCompletions} | ${m.liveUtterances} | ${m.staleSpeechAdmissions} | ${m.temporalRecallCorrect} | ${m.activePrompts} |") }
            appendLine()
            appendLine("Scenarios: ${fixtures.joinToString { it.name }}. Constant image translation can represent camera or object motion; the engine cannot distinguish them. Occlusion/missed detections never create predicted visible observations. Sudden geometry changes, unrelated repeated objects and long gaps remain identity limitations. Track IDs are image hypotheses.")
            appendLine()
            appendLine("IDF1 uses a maximum global one-to-one predicted/label identity matching. Missing detector observations count as IDFN, rather than crediting the tracker for invented boxes. Recall correctness is an actual grounded historical-side response after tracker TTL expiry; the world-memory ablation should abstain. Find completions include initial matches and explicitly reverified returns, so a higher count here is not a general task-success estimate. Active prompts require the actual table evidence in a separate missing-target fixture. Zero stale admissions checks the final speech gate at a future time.")
            appendLine()
            appendLine("This is a controlled component comparison, not the requested full six-stage detector/system comparison. No detector, threshold tuning, external dataset partition or Android runtime is evaluated here. Reproduce from the repository root with `./gradlew.bat :core:temporalEvaluation \"-PtemporalOutput=evaluation/results/temporal-v3\" --max-workers=2`.")
        })
        println("Synthetic temporal ablation written to ${output.absolutePath}")
    }

    private fun measure(config: EngineConfig, scenarios: List<Scenario>): Metrics {
        var observations = 0; var truthCount = 0; var matchedCount = 0; var switches = 0; var stable = 0; var transitions = 0
        var idtp = 0; var completions = 0; var falseCompletions = 0; var utterances = 0; var staleAdmissions = 0
        for (scenario in scenarios) {
            val engine = SightEngine(config = config)
            engine.command("find chair", 0)
            val previousIds = mutableMapOf<String, Long>()
            val counts = mutableMapOf<Pair<String, Long>, Int>()
            var priorPhase = GoalPhase.SEARCHING
            scenario.samples.forEachIndexed { index, sample ->
                val update = engine.onFrame(FrameObservation(Evidence(index + 1L, sample.atMs, "constructed:${scenario.name}", modelId = "labelled-fixture-no-model"), sample.detections), sample.atMs)
                truthCount += sample.truth.size
                val live = update.entities.filter { it.visible }
                observations += live.size
                sample.truth.forEach { label ->
                    val matched = live.filter { it.category == label.category && it.box.iou(label.box) > 0.99f }.maxByOrNull { it.box.iou(label.box) }
                    if (matched != null) {
                        matchedCount++
                        val before = previousIds.put(label.identity, matched.id)
                        if (before != null) { transitions++; if (before == matched.id) stable++ else switches++ }
                        val key = label.identity to matched.id
                        counts[key] = counts.getOrDefault(key, 0) + 1
                    }
                }
                if (update.goal.phase == GoalPhase.COMPLETED && priorPhase != GoalPhase.COMPLETED) {
                    completions++
                    if (update.announcements.filter { it.kind == AnnouncementKind.FIND_RESULT }.flatMap { it.grounding }.none { grounding ->
                            live.firstOrNull { it.id == grounding.trackId }?.let { candidate -> sample.truth.any { it.category == candidate.category && it.box.iou(candidate.box) > 0.99f } } == true
                        }) falseCompletions++
                }
                priorPhase = update.goal.phase
                update.announcements.filter { it.kind in setOf(AnnouncementKind.FIND_RESULT, AnnouncementKind.LIVE_SCENE) }.forEach {
                    utterances++
                    if (engine.isAnnouncementValid(it, sample.atMs + config.maxFrameAgeMs + 1)) staleAdmissions++
                }
            }
            idtp += maximumIdentityMatches(counts)
        }
        val memory = SightEngine(config = config)
        val box = Box(0.05f, 0.2f, 0.25f, 0.6f)
        repeat(2) { index -> memory.onFrame(FrameObservation(Evidence(index + 1L, (index + 1) * 100L), listOf(Detection("chair", 0.9f, box))), (index + 1) * 100L) }
        val recall = memory.command("what was on my left", 6_000).announcements.singleOrNull()
        val recallCorrect = recall?.kind == AnnouncementKind.MEMORY && recall.text.contains("a chair") && recall.evidence?.capturedAtMs == 200L && recall.text.contains("past image observations")
        val active = SightEngine(config = config)
        active.command("find bottle", 0)
        var prompts = 0
        repeat(14) { index ->
            val at = (index + 1) * 500L
            val update = active.onFrame(FrameObservation(Evidence(index + 1L, at), listOf(Detection("dining table", 0.9f, box))), at)
            prompts += update.announcements.count { it.text.contains("inspect that area") && it.trackIds.isNotEmpty() && active.isAnnouncementValid(it, at) }
        }
        return Metrics(observations, truthCount, matchedCount, switches, stable, transitions, idtp, completions, falseCompletions, utterances, staleAdmissions, recallCorrect, prompts)
    }

    /** Exact maximum bipartite weight matching; fixtures have only one or two labelled identities. */
    internal fun maximumIdentityMatches(counts: Map<Pair<String, Long>, Int>): Int {
        val truth = counts.keys.map { it.first }.distinct()
        require(truth.size <= 12)
        var best = IntArray(1 shl truth.size) { Int.MIN_VALUE }.also { it[0] = 0 }
        counts.keys.map { it.second }.distinct().forEach { predicted ->
            val next = best.copyOf()
            best.indices.filter { best[it] >= 0 }.forEach { mask -> truth.forEachIndexed { index, identity ->
                if (mask and (1 shl index) == 0) {
                    val assigned = mask or (1 shl index)
                    next[assigned] = max(next[assigned], best[mask] + counts.getOrDefault(identity to predicted, 0))
                }
            } }
            best = next
        }
        return best.maxOrNull() ?: 0
    }

    fun scenarios(): List<Scenario> {
        fun samples(count: Int, build: (Int) -> Pair<List<Label>, List<Detection>>) = (0 until count).map { index ->
            val (truth, detections) = build(index); Sample((index + 1) * 100L, truth, detections)
        }
        fun detection(label: Label, confidence: Float = 0.9f) = Detection(label.category, confidence, label.box)
        return listOf(
            Scenario("translation_detector_misses", samples(12) { i ->
                val x = 0.05f + i * 0.045f
                val chair = Label("chair-A", "chair", Box(x, 0.2f, x + 0.12f, 0.5f))
                val bottle = Label("bottle-B", "bottle", Box(0.8f, 0.2f, 0.9f, 0.5f))
                listOf(chair, bottle) to (if (i in setOf(3, 6)) listOf(detection(bottle)) else listOf(detection(chair), detection(bottle)))
            }),
            Scenario("partial_occlusion_score_dip", samples(10) { i ->
                val x = 0.1f + i * 0.015f
                val label = Label("chair-A", "chair", Box(x, 0.2f, x + if (i in 4..5) 0.10f else 0.15f, 0.6f))
                listOf(label) to listOf(detection(label, if (i in 3..4) 0.55f else 0.9f))
            }),
            Scenario("same_category_crossing", samples(12) { i ->
                val x = 0.1f + i * 0.05f
                val y = 0.75f - i * 0.05f
                val a = Label("chair-A", "chair", Box(x, 0.2f, x + 0.12f, 0.5f))
                val b = Label("chair-B", "chair", Box(y, 0.2f, y + 0.12f, 0.5f))
                listOf(a, b) to listOf(detection(a), detection(b))
            }),
            Scenario("long_gap_new_identity", samples(18) { i ->
                val label = Label("chair-A", "chair", Box(0.1f, 0.2f, 0.3f, 0.6f))
                listOf(label) to if (i in 3..14) emptyList() else listOf(detection(label))
            }),
            Scenario("single_frame_false_positive", samples(8) { i ->
                val label = Label("bottle-A", "bottle", Box(0.7f, 0.2f, 0.8f, 0.6f))
                listOf(label) to (listOf(detection(label)) + if (i == 3) listOf(Detection("chair", 0.99f, Box(0.1f, 0.2f, 0.3f, 0.6f))) else emptyList())
            }),
        )
    }

    private fun format(value: Double) = "%.3f".format(java.util.Locale.ROOT, value)
    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
