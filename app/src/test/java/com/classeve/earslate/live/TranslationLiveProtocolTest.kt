package com.classeve.earslate.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLiveProtocolTest {
    @Test
    fun resamples16kPcmTo24kWithoutChangingDuration() {
        val input = ByteArray(3200) // 100 ms, mono PCM16 at 16 kHz
        val output = Pcm16Resampler.from16kTo24k(input)
        assertEquals(4800, output.size)
    }

    @Test
    fun openAiParserNormalizesAudioAndTranscriptEvents() {
        val protocol = TranslationLiveProtocols.forProvider(
            com.classeve.earslate.session.TranslationProvider.OPENAI,
        )
        val caption = protocol.parse("""{"type":"session.output_transcript.delta","delta":"Hola"}""")
        assertEquals(LiveEvent.CaptionDelta("Hola"), caption.single())
        val ready = protocol.parse("""{"type":"session.updated"}""")
        assertTrue(ready.single() is LiveEvent.SetupComplete)
    }
}
