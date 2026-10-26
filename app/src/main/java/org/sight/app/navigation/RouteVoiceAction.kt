package org.sight.app.navigation

/** Parsing never grants consent, chooses an ambiguous map result, or starts GPS. */
sealed interface RouteVoiceAction {
    data class Search(val query: String, val start: Boolean) : RouteVoiceAction
    data object Overview : RouteVoiceAction
    data object PauseGps : RouteVoiceAction
    data object Recalculate : RouteVoiceAction
    companion object {
        fun parse(command: String): RouteVoiceAction? {
            val normalized = command.trim().replace(Regex("\\s+"), " ")
            return when (normalized.lowercase()) {
                "route overview", "read route", "rehearse route" -> Overview
                "pause gps", "stop gps", "pause route" -> PauseGps
                "recalculate route", "reroute" -> Recalculate
                else -> listOf("navigate to " to false, "destination " to false, "starting place " to true)
                    .firstOrNull { normalized.startsWith(it.first, ignoreCase = true) }
                    ?.let { (prefix, start) -> normalized.drop(prefix.length).take(200).trim()
                        .takeIf { it.isNotEmpty() }?.let { Search(it, start) } }
            }
        }
    }
}
