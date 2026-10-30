package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.prompt.SlotSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerationStartTest {
    private val plainToken = 1_234
    private val speechFrame = SlotSource.AudioOutCodes(IntArray(Lfm3Omni.NUM_CODEBOOKS) { 7 })
    private val endOfSpeechCodes = IntArray(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }
    private val endOfSpeechFrame = SlotSource.AudioOutCodes(endOfSpeechCodes)

    @Test
    fun textAppendsNothingAndSuppressesTheModeMarkers() {
        val start = GenerationStart.of(GenerationMode.TEXT, plainToken, null)

        assertNull(start.marker)
        assertEquals(SlotMode.TEXT, start.mode)
        val suppressed = start.suppressed.toSet()
        val markers = setOf(Lfm3Omni.MIXED_START_TOKEN, Lfm3Omni.AUDIO_START_TOKEN)
        assertEquals(Lfm3Omni.PLACEHOLDER_TOKENS.toSet() + markers, suppressed)
    }

    @Test
    fun speechAppendsAudioStartUnlessThePromptEndsInSpeech() {
        val afterText = GenerationStart.of(GenerationMode.SPEECH, plainToken, null)
        val afterAudioStart = GenerationStart.of(GenerationMode.SPEECH, Lfm3Omni.AUDIO_START_TOKEN, null)
        val afterFrame = GenerationStart.of(GenerationMode.SPEECH, Lfm3Omni.AUDIO_OUT_TOKEN, speechFrame)

        assertEquals(Lfm3Omni.AUDIO_START_TOKEN, afterText.marker)
        assertNull(afterAudioStart.marker)
        assertNull(afterFrame.marker)
        for (start in listOf(afterText, afterAudioStart, afterFrame)) {
            assertEquals(SlotMode.AUDIO, start.mode)
            assertEquals(Lfm3Omni.PLACEHOLDER_TOKENS.toSet(), start.suppressed.toSet())
        }
    }

    @Test
    fun interleavedAppendsMixedStartUnlessThePromptEndsWithIt() {
        val afterText = GenerationStart.of(GenerationMode.INTERLEAVED, plainToken, null)
        val afterMarker = GenerationStart.of(GenerationMode.INTERLEAVED, Lfm3Omni.MIXED_START_TOKEN, null)
        // <|audio_start|> is not an interleaved marker: <|mixed_start|> still follows it
        val afterAudioStart = GenerationStart.of(GenerationMode.INTERLEAVED, Lfm3Omni.AUDIO_START_TOKEN, null)

        assertEquals(Lfm3Omni.MIXED_START_TOKEN, afterText.marker)
        assertNull(afterMarker.marker)
        assertEquals(Lfm3Omni.MIXED_START_TOKEN, afterAudioStart.marker)
        assertEquals(SlotMode.INTERLEAVED, afterText.mode)
    }

    @Test
    fun spokenStartsAsTextToReadTheFirstTokenAndThenSpeaksAsInterleaved() {
        val start = GenerationStart.of(GenerationMode.SPOKEN, plainToken, null)

        // Nothing is appended and the markers are suppressed, exactly as for text
        val text = GenerationStart.of(GenerationMode.TEXT, plainToken, null)
        assertNull(start.marker)
        assertEquals(SlotMode.TEXT, start.mode)
        assertEquals(text.suppressed.toSet(), start.suppressed.toSet())

        // A spoken answer that does not open with a tool call carries on as an interleaved one
        val speech = GenerationStart.SPOKEN_SPEECH
        assertEquals(Lfm3Omni.MIXED_START_TOKEN, speech.marker)
        assertEquals(SlotMode.INTERLEAVED, speech.mode)
        assertEquals(Lfm3Omni.PLACEHOLDER_TOKENS.toSet(), speech.suppressed.toSet())
    }

    @Test
    fun autoReadsTheModeFromTheLastToken() {
        val cases = mapOf(
            Pair(plainToken, null) to SlotMode.TEXT,
            Pair(Lfm3Omni.AUDIO_START_TOKEN, null) to SlotMode.AUDIO,
            Pair(Lfm3Omni.AUDIO_OUT_TOKEN, speechFrame) to SlotMode.AUDIO,
            Pair(Lfm3Omni.MIXED_START_TOKEN, null) to SlotMode.INTERLEAVED,
            Pair(Lfm3Omni.TEXT_END_TOKEN, null) to SlotMode.TEXT,
        )
        for ((last, expected) in cases) {
            val start = GenerationStart.of(GenerationMode.AUTO, last.first, last.second)
            assertNull(start.marker)
            assertEquals(expected, start.mode, "after token ${last.first}")
            assertEquals(Lfm3Omni.PLACEHOLDER_TOKENS.toSet(), start.suppressed.toSet())
        }
    }

    @Test
    fun autoResumesTextAfterAReplayedEndOfSpeechFrame() {
        val start = GenerationStart.of(GenerationMode.AUTO, Lfm3Omni.AUDIO_OUT_TOKEN, endOfSpeechFrame)

        assertEquals(SlotMode.TEXT, start.mode)
    }

    @Test
    fun aFrameWithAnyEndOfSpeechCodeCountsAsTheEnd() {
        val codes = IntArray(Lfm3Omni.NUM_CODEBOOKS) { 3 }
        codes[5] = Lfm3Omni.AUDIO_EOS_CODE
        val source = SlotSource.AudioOutCodes(codes)

        val start = GenerationStart.of(GenerationMode.AUTO, Lfm3Omni.AUDIO_OUT_TOKEN, source)

        assertTrue(start.mode == SlotMode.TEXT)
    }
}
