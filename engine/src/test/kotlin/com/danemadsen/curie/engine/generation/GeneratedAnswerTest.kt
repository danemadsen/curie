package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.tokenizer.TestTokenizers
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GeneratedAnswerTest {
    private val tokenizer = TestTokenizers.fixture
    private val markup = MarkupTokens.of(tokenizer)
    private val audioOut = Lfm3Omni.AUDIO_OUT_TOKEN
    private val endOfSpeech = IntArray(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }

    private fun frame(code: Int) = IntArray(Lfm3Omni.NUM_CODEBOOKS) { code }

    private fun textSlots(text: String): List<GeneratedSlot> =
        tokenizer.encode(text).map { token -> GeneratedSlot(token, null) }

    private fun frameSlot(codes: IntArray) = GeneratedSlot(audioOut, codes)

    private fun answer(
        slots: List<GeneratedSlot>,
        finishReason: FinishReason,
        endMode: SlotMode = SlotMode.TEXT,
        textDone: Boolean = false,
        marker: Int? = null,
    ): GeneratedAnswer {
        val outcome = LoopOutcome(slots, finishReason, endMode, textDone)
        return GeneratedAnswer.of(marker, outcome, tokenizer, markup)
    }

    @Test
    fun theContentReplaysEverySlotButTheFinalImEnd() {
        val text = textSlots("Hi")
        val imEnd = GeneratedSlot(Lfm3Omni.IM_END_TOKEN, null)
        val slots = text + listOf(frameSlot(frame(4)), frameSlot(endOfSpeech), imEnd)

        val result = answer(slots, FinishReason.END_OF_TURN, marker = Lfm3Omni.AUDIO_START_TOKEN)

        val textTokens = text.map { slot -> slot.token }
        val expectedTokens = listOf(Lfm3Omni.AUDIO_START_TOKEN) + textTokens + listOf(audioOut, audioOut)
        assertEquals(expectedTokens, result.content.tokens.toList())
        assertContentEquals(frame(4) + endOfSpeech, result.content.audioCodes)
        assertEquals(1, result.audioFrames.size)
        assertContentEquals(frame(4), result.audioFrames[0])
        assertEquals("Hi", result.text)
    }

    @Test
    fun speechCutShortIsClosedWithAnEndOfSpeechFrame() {
        val slots = listOf(frameSlot(frame(1)), frameSlot(frame(2)))

        for (reason in listOf(FinishReason.MAX_SLOTS, FinishReason.CANCELLED, FinishReason.CONTEXT_FULL)) {
            val result = answer(slots, reason, endMode = SlotMode.AUDIO, marker = Lfm3Omni.AUDIO_START_TOKEN)

            val expectedTokens = listOf(Lfm3Omni.AUDIO_START_TOKEN, audioOut, audioOut, audioOut)
            assertEquals(expectedTokens, result.content.tokens.toList(), "$reason")
            assertContentEquals(frame(1) + frame(2) + endOfSpeech, result.content.audioCodes)
            assertEquals(2, result.audioFrames.size)
        }
    }

    @Test
    fun aBlockCutBeforeItsTextEndsGetsTextEndThenTheEndOfSpeech() {
        val slots = textSlots("Hi") + listOf(frameSlot(frame(3)))

        val result = answer(slots, FinishReason.MAX_SLOTS, endMode = SlotMode.INTERLEAVED, textDone = false)

        val tokens = result.content.tokens.toList()
        assertEquals(listOf(Lfm3Omni.TEXT_END_TOKEN, audioOut), tokens.takeLast(2))
        assertContentEquals(frame(3) + endOfSpeech, result.content.audioCodes)
    }

    @Test
    fun aBlockCutAfterItsTextOnlyEndsTheSpeech() {
        val slots = textSlots("Hi") + listOf(GeneratedSlot(Lfm3Omni.TEXT_END_TOKEN, null), frameSlot(frame(3)))

        val result = answer(slots, FinishReason.CANCELLED, endMode = SlotMode.INTERLEAVED, textDone = true)

        val tokens = result.content.tokens.toList()
        assertEquals(listOf(Lfm3Omni.TEXT_END_TOKEN, audioOut, audioOut), tokens.takeLast(3))
        assertEquals(1, tokens.count { token -> token == Lfm3Omni.TEXT_END_TOKEN })
    }

    @Test
    fun textCutShortIsLeftAsItIs() {
        val slots = textSlots("Hello there")

        val result = answer(slots, FinishReason.MAX_SLOTS, endMode = SlotMode.TEXT)

        assertEquals(slots.map { slot -> slot.token }, result.content.tokens.toList())
        assertEquals(0, result.content.audioCodes.size)
    }

    @Test
    fun aTurnTheModelEndsInsideABlockIsLeftAsItIs() {
        val slots = textSlots("Hi") + listOf(GeneratedSlot(Lfm3Omni.IM_END_TOKEN, null))

        val result = answer(slots, FinishReason.END_OF_TURN, endMode = SlotMode.INTERLEAVED)

        assertEquals(textSlots("Hi").map { slot -> slot.token }, result.content.tokens.toList())
    }

    @Test
    fun theTextLeavesOutThinkTagsAndToolCalls() {
        val raw = "<think></think>Let me check. <tool_call>\n<function=get_weather>\n<parameter=city>\nParis\n" +
            "</parameter>\n</function>\n</tool_call>"
        val slots = textSlots(raw) + listOf(GeneratedSlot(Lfm3Omni.IM_END_TOKEN, null))

        val result = answer(slots, FinishReason.END_OF_TURN)

        assertEquals("Let me check.", result.text)
        assertEquals(1, result.toolCalls.size)
        assertEquals("get_weather", result.toolCalls[0].name)
        assertEquals(JsonPrimitive("Paris"), result.toolCalls[0].arguments["city"])
    }

    @Test
    fun theTextStreamShowsWhatTheFinalTextShows() {
        val raw = "Hello <tool_call>\n<function=f>\n</function>\n</tool_call> world<think></think>!"
        val pieces = ArrayList<String>()
        val stream = VisibleTextStream(tokenizer, markup) { piece -> pieces.add(piece) }

        for (token in tokenizer.encode(raw)) {
            stream.push(token)
        }
        stream.finish()

        assertEquals("Hello  world!", pieces.joinToString(""))
        assertEquals("Hello  world!", answer(textSlots(raw), FinishReason.END_OF_TURN).text)
    }
}
