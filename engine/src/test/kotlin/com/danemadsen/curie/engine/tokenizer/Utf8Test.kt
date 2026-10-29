package com.danemadsen.curie.engine.tokenizer

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

/** Lossy UTF-8 decoding; the expected strings are Python's bytes.decode("utf-8", "replace"). */
class Utf8Test {
    @Test
    fun replacesEachMaximalInvalidSubpartOnce() {
        val cases = mapOf(
            listOf(0xE2, 0x82) to "\uFFFD",
            listOf(0xE2, 0x82, 0x41) to "\uFFFDA",
            listOf(0xF0, 0x80, 0x80, 0x80) to "\uFFFD\uFFFD\uFFFD\uFFFD",
            listOf(0xED, 0xA0, 0x80) to "\uFFFD\uFFFD\uFFFD",
            listOf(0xC0, 0xAF) to "\uFFFD\uFFFD",
            listOf(0xF4, 0x90, 0x80, 0x80) to "\uFFFD\uFFFD\uFFFD\uFFFD",
            listOf(0xE0, 0x80, 0x80) to "\uFFFD\uFFFD\uFFFD",
            listOf(0xF0, 0x9F, 0x98) to "\uFFFD",
            listOf(0xF0, 0x9F, 0x41) to "\uFFFDA",
            listOf(0x80, 0xBF) to "\uFFFD\uFFFD",
            listOf(0xF8, 0x88, 0x80, 0x80, 0x80) to "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD",
            listOf(0xE2, 0x82, 0xAC) to "€",
            listOf(0xF0, 0x9F, 0x98, 0x80) to "😀",
        )
        for ((input, expected) in cases) {
            val bytes = ByteArray(input.size) { index -> input[index].toByte() }
            assertEquals(expected, decodeAll(bytes), "decoding $input")
        }
    }

    @Test
    fun holdsBackOnlyACompletablePrefix() {
        val emoji = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())
        for (cut in 1 until emoji.size) {
            val out = StringBuilder()
            val stop = Utf8.decode(emoji, 0, cut, out, final = false)
            assertEquals(0, stop, "the first $cut bytes wait")
            assertEquals("", out.toString())
        }

        val textThenPrefix = byteArrayOf(0x41, 0xE2.toByte(), 0x82.toByte())
        val out = StringBuilder()
        assertEquals(1, Utf8.decode(textThenPrefix, 0, 3, out, final = false))
        assertEquals("A", out.toString())

        val brokenPrefix = byteArrayOf(0xE2.toByte(), 0x41)
        val brokenOut = StringBuilder()
        assertEquals(2, Utf8.decode(brokenPrefix, 0, 2, brokenOut, final = false))
        assertEquals("\uFFFDA", brokenOut.toString())
    }

    @Test
    fun decodesValidTextLikeTheJdk() {
        val text = "plain ASCII, café, 中文, 😀, \u0000 and \uFFFF"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, decodeAll(bytes))
    }

    /**
     * ED takes only 80..9F next (no surrogates): "ED BF" is two maximal subparts, as Rust and
     * Python decode it. The JDK's decoder (17) makes it one, so it cannot serve as the reference;
     * the golden decode cases hold tokenizers' results for random byte sequences instead.
     */
    @Test
    fun treatsASurrogateLeadAsItsOwnSubpart() {
        val surrogateStart = byteArrayOf(0xED.toByte(), 0xBF.toByte(), 0x41)
        assertEquals("\uFFFD\uFFFDA", decodeAll(surrogateStart))
    }

    @Test
    fun chunkedDecodingEqualsWholeDecoding() {
        val random = Random(7)
        repeat(2_000) {
            val length = random.nextInt(0, 24)
            val bytes = ByteArray(length) { pickByte(random) }
            val whole = decodeAll(bytes)

            val chunked = StringBuilder()
            var start = 0
            var end = 0
            while (end < bytes.size) {
                end = minOf(bytes.size, end + random.nextInt(1, 4))
                start = Utf8.decode(bytes, start, end, chunked, final = false)
            }
            Utf8.decode(bytes, start, bytes.size, chunked, final = true)
            assertEquals(whole, chunked.toString(), "chunks of ${bytes.toList()}")
        }
    }

    private fun decodeAll(bytes: ByteArray): String {
        val out = StringBuilder()
        Utf8.decode(bytes, 0, bytes.size, out, final = true)
        return out.toString()
    }

    /** Bytes that make many lead, continuation and invalid combinations. */
    private fun pickByte(random: Random): Byte {
        val interesting = intArrayOf(
            0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC2,
            0xDF, 0xE0, 0xE2, 0xED, 0xEF, 0xF0, 0xF4, 0xF5, 0xFF,
        )
        return interesting[random.nextInt(interesting.size)].toByte()
    }
}
