package org.sight.core

/** Recognizes words on a captured image, never the physical object or an entrance's location. */
class TextLandmarkSearch(val target:String,val startedAtMs:Long,private val patterns:List<Regex>) {
    fun matching(evidence:Evidence,blocks:List<TextObservation>,nowMs:Long):List<TextObservation> {
        if(evidence.capturedAtMs<startedAtMs || nowMs-evidence.capturedAtMs !in 0..8_000) return emptyList()
        return blocks.filter { block -> patterns.any { it.containsMatchIn(block.text) } }
    }
    companion object {
        fun fromCommand(command:String,atMs:Long):TextLandmarkSearch? {
            val target=Regex("^(?:find|locate|look for) (?:the |an? |my )?(.+)$",RegexOption.IGNORE_CASE)
                .matchEntire(command.trim())?.groupValues?.get(1)?.lowercase() ?: return null
            val words=when(target) {
                "door", "doors" -> listOf("\\bdoor\\b","\\bentrance\\b","\\bexit\\b")
                "entrance", "entry", "entrances" -> listOf("\\bentrance\\b","\\bentry\\b")
                "elevator", "elevators", "lift" -> listOf("\\belevators?\\b","\\blifts?\\b")
                "bus stop", "bus stop sign" -> listOf("\\bbus\\s+stop\\b","\\bstop\\s+(?:no\\.?|number|#)\\s*\\d+")
                else -> return null
            }
            return TextLandmarkSearch(target,atMs,words.map { Regex(it,RegexOption.IGNORE_CASE) })
        }
    }
}
