package org.sight.core

/** Final deterministic output boundary, also applied to OCR and provider-controlled labels. */
object SpeechSafety {
    private val clearance = listOf(
        "\\bsafe\\s+to\\s+cross\\b",
        "\\b(?:you\\s+(?:can|may|should)|please)\\s+cross\\b",
        "\\b(?:cross|crossing)\\s+now\\b",
        "\\bwalk\\s+into\\s+(?:the\\s+)?intersection\\b",
        "\\b(?:road|path|way|intersection)\\s+(?:is|looks|appears)\\s+(?:clear|safe)\\b",
        "\\bno\\s+(?:cars|vehicles|traffic|obstacles)\\s+(?:(?:are|is)\\s+)?(?:coming|approaching|ahead|present)\\b",
        "\\b(?:step|swerve|walk|move)\\s+(?:left|right|forward|backward)\\b.{0,45}\\bavoid\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val visualMetric = Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:metres?|meters?|feet|foot|cm|centimetres?)\\b", RegexOption.IGNORE_CASE)

    fun timely(event: Announcement, nowMs: Long): Boolean {
        if (nowMs < 0 || event.text.isBlank() || event.text.length > 12_000) return false
        if (event.validUntilMs?.let { nowMs > it } == true) return false
        if (event.evidence?.let { it.capturedAtMs > nowMs } == true) return false
        return true
    }

    fun prohibitsClaim(event:Announcement):Boolean {
        val text = event.text.replace('\u2019', '\'').replace(Regex("\\s+"), " ")
        if (clearance.any { it.containsMatchIn(text) }) return true
        if (event.kind in setOf(AnnouncementKind.LIVE_SCENE, AnnouncementKind.FIND_RESULT, AnnouncementKind.ENVIRONMENT) &&
            visualMetric.containsMatchIn(text)) return true
        return false
    }

    fun allows(event: Announcement, nowMs: Long) = timely(event,nowMs) && !prohibitsClaim(event)
}
