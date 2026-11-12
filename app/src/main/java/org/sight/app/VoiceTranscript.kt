package org.sight.app

import org.json.JSONObject

/** Only complete ASR results become commands. Partial hypotheses never execute. */
object VoiceTranscript {
    fun finalText(json:String):String? {
        if(json.length>20_000) return null
        return try {
            val value=JSONObject(json).opt("text") as? String ?: return null
            if(!Charsets.UTF_8.newEncoder().canEncode(value)) return null
            value.replace(Regex("[\\p{Cntrl}]")," ").replace(Regex("\\s+")," ").trim()
                .takeIf { it.isNotEmpty() && it.length<=300 && !it.contains("[unk]",ignoreCase=true) }
        } catch(_:Exception){ null }
    }
}
