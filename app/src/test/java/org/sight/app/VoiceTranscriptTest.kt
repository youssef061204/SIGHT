package org.sight.app

import org.junit.Assert.*
import org.junit.Test

class VoiceTranscriptTest {
    @Test fun onlyCompleteNonemptyBoundedUtterancesBecomeCommands(){
        assertEquals("find a chair",VoiceTranscript.finalText("""{"text":"  find\n a chair  "}"""))
        assertNull(VoiceTranscript.finalText("""{"partial":"stop navigation"}"""))
        assertNull(VoiceTranscript.finalText("""{"text":""}"""))
        assertNull(VoiceTranscript.finalText("""{"text":42}"""))
        assertNull(VoiceTranscript.finalText("not json"))
        assertNull(VoiceTranscript.finalText("""{"text":"[unk]"}"""))
        assertNull(VoiceTranscript.finalText("""{"text":"find [UNK]"}"""))
        assertNull(VoiceTranscript.finalText("""{"text":"\ud800"}"""))
        assertEquals("navigate to café",VoiceTranscript.finalText("""{"text":"navigate to café"}"""))
        assertNull(VoiceTranscript.finalText("""{"text":"${"a".repeat(301)}"}"""))
    }
}
