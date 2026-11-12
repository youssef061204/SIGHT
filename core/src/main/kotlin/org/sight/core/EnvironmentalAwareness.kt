package org.sight.core

enum class PedestrianSurface { ROAD, CURB, ROAD_MARKING, SIDEWALK, CROSSWALK, STAIRS, ENTRANCE }

/** Score is the model's mean posterior, not a calibrated probability of correctness. */
data class SurfaceRegion(val label: PedestrianSurface, val score: Float, val coverage: Float, val box: Box) {
    init { require(score.isFinite() && score in 0f..1f && coverage.isFinite() && coverage in 0f..1f) }
}
data class SurfaceObservation(val evidence: Evidence, val regions: List<SurfaceRegion>)

/** Descriptive scene information; contains no traversability, crossing authorization or metric depth. */
class EnvironmentalAwareness(
    private val enabledSurfaces: Set<PedestrianSurface> = emptySet(),
    private val repeatMs: Long = 20_000,
) {
    private var objects: EngineUpdate? = null
    private var surface: SurfaceObservation? = null
    private val emitted = linkedMapOf<String, Long>()
    private val admitted = linkedMapOf<String, Announcement>()
    private val categories = setOf("car", "truck", "bus", "motorcycle", "bicycle", "person", "traffic light", "bench", "fire hydrant")

    fun reset() { objects = null; surface = null; admitted.clear(); emitted.clear() }

    fun onObjects(update: EngineUpdate, nowMs: Long, enabled: Boolean): List<Announcement> {
        objects = update
        if (!enabled) return emptyList()
        val evidence = update.liveEvidence ?: return emptyList()
        if (nowMs - evidence.capturedAtMs !in 0..1_500) return emptyList()
        return update.entities.filter { it.category in categories && it.visible && it.consecutiveObservations >= 2 &&
            it.confidence >= .70f && it.aggregateConfidence >= .70f && it.box.area >= .02f && nowMs-it.lastSeenAtMs in 0..1_500 }
            .sortedByDescending { it.box.area }.take(1).mapNotNull { entity ->
                val noun = if (entity.category == "traffic light") "A traffic light is visible; its signal state is unknown"
                    else "A ${entity.category} is visible"
                admit(Announcement("$noun ${entity.direction.phrase} of the camera image. Movement is unverified.",
                    SpeechPriority.ENVIRONMENT, evidence, AnnouncementKind.ENVIRONMENT, evidence.capturedAtMs + 1_500,
                    trackIds = setOf(entity.id), dedupKey = "environment:${entity.category}:${entity.direction}",
                    grounding=listOf(Grounding(entity.id,entity.category,entity.direction))), nowMs)
            }
    }

    fun onSurfaces(observation: SurfaceObservation, nowMs: Long, enabled: Boolean): List<Announcement> {
        val previous = surface
        if (nowMs-observation.evidence.capturedAtMs !in 0..1_500 || previous?.let {
                it.evidence.sourceId == observation.evidence.sourceId && observation.evidence.capturedAtMs <= it.evidence.capturedAtMs } == true) return emptyList()
        surface = observation
        if (!enabled || previous == null || previous.evidence.sourceId != observation.evidence.sourceId ||
            previous.evidence.modelId != observation.evidence.modelId || previous.evidence.geometryEpoch != observation.evidence.geometryEpoch ||
            observation.evidence.capturedAtMs-previous.evidence.capturedAtMs !in 1..4_000) return emptyList()
        return observation.regions.filter { it.label in enabledSurfaces && reliable(it) &&
            previous.regions.any { old -> old.label == it.label && reliable(old) } }.take(1).mapNotNull { region ->
                val noun = when(region.label) {
                    PedestrianSurface.ROAD -> "Road surface"
                    PedestrianSurface.CURB -> "A curb-like boundary"
                    PedestrianSurface.ROAD_MARKING -> "Road markings"
                    PedestrianSurface.SIDEWALK -> "A sidewalk-like region"
                    PedestrianSurface.CROSSWALK -> "A marked crossing"
                    PedestrianSurface.STAIRS -> "Stairs"
                    PedestrianSurface.ENTRANCE -> "An entrance-like region"
                }
                admit(Announcement("$noun appears ${region.box.direction.phrase} of the camera image. Crossing conditions are unknown.",
                    SpeechPriority.ENVIRONMENT, observation.evidence, AnnouncementKind.ENVIRONMENT,
                    observation.evidence.capturedAtMs+1_500, dedupKey="surface:${region.label}:${region.box.direction}"), nowMs)
            }
    }

    private fun reliable(region: SurfaceRegion) = region.score >= .85f && region.coverage >= .06f
    private fun admit(event: Announcement, nowMs: Long): Announcement? {
        if (emitted[event.dedupKey]?.let { nowMs-it < repeatMs } == true || !SpeechSafety.allows(event,nowMs)) return null
        emitted[event.dedupKey]=nowMs; admitted[event.dedupKey]=event
        while(emitted.size>64) emitted.remove(emitted.keys.first())
        while(admitted.size>64) admitted.remove(admitted.keys.first())
        return event
    }

    fun isSupported(event: Announcement, nowMs: Long, ongoing:Boolean=false): Boolean {
        if (event.kind != AnnouncementKind.ENVIRONMENT || !SpeechSafety.allows(if(ongoing) event.copy(validUntilMs=null) else event,nowMs) || admitted[event.dedupKey] != event) return false
        val evidence=event.evidence ?: return false
        if(!ongoing && nowMs-evidence.capturedAtMs !in 0..1_500) return false
        if(event.trackIds.isNotEmpty()) {
            val scene=objects ?: return false
            if(scene.liveEvidence?.let { it.sourceId==evidence.sourceId && it.modelId==evidence.modelId && it.geometryEpoch==evidence.geometryEpoch } != true) return false
            return event.trackIds.all { id -> scene.entities.any { it.id==id && it.visible && it.confidence>=.70f &&
                it.aggregateConfidence>=.70f && nowMs-it.lastSeenAtMs in 0..1_500 } } &&
                event.grounding.all { claimed -> scene.entities.any { it.id==claimed.trackId && it.category==claimed.category && it.direction==claimed.direction } }
        }
        val current=surface ?: return false
        val sameSource=current.evidence.let { it.sourceId==evidence.sourceId && it.modelId==evidence.modelId && it.geometryEpoch==evidence.geometryEpoch }
        return sameSource && nowMs-current.evidence.capturedAtMs in 0..1_500 && (ongoing || current.evidence==evidence) &&
            current.regions.any { it.label in enabledSurfaces && reliable(it) && event.dedupKey=="surface:${it.label}:${it.box.direction}" }
    }
}
