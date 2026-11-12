package org.sight.app.navigation

/** Deterministic spoken commands never grant Android permissions or silently resolve ambiguity. */
sealed interface RouteVoiceAction {
    data class Search(val query: String, val start: Boolean) : RouteVoiceAction
    data class Choose(val index: Int) : RouteVoiceAction
    data class SaveDestination(val name: String) : RouteVoiceAction
    data class RecallDestination(val name: String) : RouteVoiceAction
    data class DeleteDestination(val name: String) : RouteVoiceAction
    data object EnableServices : RouteVoiceAction
    data object DisableServices : RouteVoiceAction
    data object EnableMappedDetails : RouteVoiceAction
    data object UseStandardRouting : RouteVoiceAction
    data object Overview : RouteVoiceAction
    data object PauseGps : RouteVoiceAction
    data object ResumeGps : RouteVoiceAction
    data object StartGuidance : RouteVoiceAction
    data object Cancel : RouteVoiceAction
    data object Recalculate : RouteVoiceAction
    data object Repeat : RouteVoiceAction
    data object DistanceToTurn : RouteVoiceAction
    data object ConfirmArrival : RouteVoiceAction
    data object ListDestinations : RouteVoiceAction
    companion object {
        fun parse(command: String): RouteVoiceAction? {
            val normalized = command.trim().replace(Regex("\\s+"), " ").replace(Regex("(?i)^sight[, :]\\s*"), "")
            val lower = normalized.lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?')
            return when (lower) {
                "enable route services", "enable public route services", "enable navigation services", "allow route services" -> EnableServices
                "disable route services", "disable navigation services" -> DisableServices
                "enable mapped route details", "use mapped route details", "use accessibility informed routing" -> EnableMappedDetails
                "use standard routing", "disable mapped route details", "use ordinary walking route" -> UseStandardRouting
                "route overview", "read route", "rehearse route", "navigation overview" -> Overview
                "pause gps", "stop gps", "pause route", "pause navigation" -> PauseGps
                "resume gps", "resume route", "resume navigation", "continue navigation" -> ResumeGps
                "start navigation", "begin navigation", "start guidance" -> StartGuidance
                "stop navigation", "cancel navigation", "cancel route", "stop route" -> Cancel
                "recalculate route", "reroute", "recalculate navigation" -> Recalculate
                "repeat direction", "repeat last direction", "repeat the last direction", "repeat navigation", "repeat route instruction" -> Repeat
                "how far until the next turn", "distance to next turn", "how far to the next turn" -> DistanceToTurn
                "confirm arrival", "i have arrived", "i confirm this is the destination" -> ConfirmArrival
                "list saved destinations", "saved destinations" -> ListDestinations
                else -> {
                    val choose = Regex("^(?:choose|select|option|choose option|select option) (?:the )?(first|second|third|fourth|fifth|one|two|three|four|five|[1-5])(?: option| result)?$").matchEntire(lower)
                    if (choose != null) {
                        val value = choose.groupValues[1]
                        val index = when(value) { "first", "one" -> 0; "second", "two" -> 1; "third", "three" -> 2; "fourth", "four" -> 3; "fifth", "five" -> 4; else -> value.toInt()-1 }
                        Choose(index)
                    } else {
                        val saved = listOf("save destination as " to 0, "remember destination as " to 0,
                            "navigate to saved " to 1, "take me to saved " to 1, "delete destination " to 2,
                            "forget destination " to 2).firstOrNull { lower.startsWith(it.first) }
                        if (saved != null) {
                            val name = normalized.drop(saved.first.length).trim().trimEnd('.', '!', '?').take(60)
                            if (name.isBlank()) null else when(saved.second) { 0 -> SaveDestination(name); 1 -> RecallDestination(name); else -> DeleteDestination(name) }
                        } else listOf("take me to " to false, "navigate to " to false, "navigation to " to false,
                            "destination " to false, "starting place " to true)
                            .firstOrNull { lower.startsWith(it.first) }
                            ?.let { (prefix, start) -> normalized.drop(prefix.length).trim().trimEnd('.', '!', '?')
                                .takeIf { it.length in 2..160 }?.let { Search(it, start) } }
                    }
                }
            }
        }
    }
}
