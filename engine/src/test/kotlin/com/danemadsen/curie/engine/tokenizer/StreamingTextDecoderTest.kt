package com.danemadsen.curie.engine.tokenizer

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StreamingTextDecoderTest {
    private val tokenizer = TestTokenizers.fixture

    @Test
    fun holdsBackACharacterSplitOverTokens() {
        // U+1F600 is F0 9F 98 80: one byte token each
        val emojiBytes = listOf(0xF0, 0x9F, 0x98, 0x80)
        val byteTokens = emojiBytes.map { byte -> byteToken(byte) }
        val decoder = tokenizer.newStreamingDecoder()

        assertEquals("", decoder.push(byteTokens[0]))
        assertEquals("", decoder.push(byteTokens[1]))
        assertEquals("", decoder.push(byteTokens[2]))
        assertEquals("😀", decoder.push(byteTokens[3]))
        assertEquals("", decoder.flush())
    }

    @Test
    fun flushEndsAnUnfinishedCharacterAsDecodeDoes() {
        val euroStart = byteToken(0xE2)
        val euroMiddle = byteToken(0x82)
        val letterA = byteToken(0x41)
        val decoder = tokenizer.newStreamingDecoder()
        decoder.push(euroStart)
        decoder.push(euroMiddle)

        assertEquals("\uFFFD", decoder.flush())
        assertEquals("A", decoder.push(letterA))
    }

    @Test
    fun skipsSpecialTokensButKeepsPlainAddedTokens() {
        val letterH = byteToken(0x48)
        val ids = intArrayOf(Lfm3Omni.IM_START_TOKEN, letterH, 124_901, 124_902, Lfm3Omni.IM_END_TOKEN)

        val skipping = tokenizer.newStreamingDecoder()
        val skipped = ids.joinToString("") { id -> skipping.push(id) }
        assertEquals("H<think></think>", skipped)

        val keeping = tokenizer.newStreamingDecoder(skipSpecialTokens = false)
        val kept = ids.joinToString("") { id -> keeping.push(id) }
        assertEquals("<|im_start|>H<think></think><|im_end|>", kept)
    }

    @Test
    fun ignoresUnknownIds() {
        val letterA = byteToken(0x41)
        val decoder = tokenizer.newStreamingDecoder()
        assertEquals("", decoder.push(-1))
        assertEquals("", decoder.push(127_999))
        assertEquals("A", decoder.push(letterA))
    }

    private fun byteToken(byte: Int): Int {
        val token = byteLevelChar(byte).toString()
        val id = tokenizer.tokenId(token)
        return requireNotNull(id) { "no token for byte $byte" }
    }
}
