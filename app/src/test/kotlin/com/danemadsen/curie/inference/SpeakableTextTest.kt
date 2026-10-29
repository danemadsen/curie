package com.danemadsen.curie.inference

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakableTextTest {
    @Test
    fun markdownIsReadAsPlainText() {
        val markdown = """
            ### How to care for it
            - **Bright**, indirect light
            - Water when the [soil](https://example.com) is dry
            
            > The holes appear as it *matures*.
            
            ```kotlin
            val code = "is not read"
            ```
            Check `soil moisture` weekly.
        """.trimIndent()

        val text = SpeakableText.plainText(markdown)

        val expected = """
            How to care for it
            Bright, indirect light
            Water when the soil is dry
            
            The holes appear as it matures.
            
            Check soil moisture weekly.
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun tablesReadRowByRow() {
        val markdown = "| City | Weather |\n|---|---|\n| Hobart | Rain |"

        assertEquals("City, Weather\nHobart, Rain", SpeakableText.plainText(markdown))
    }

    @Test
    fun longTextIsSplitAtSentenceEnds() {
        val sentence = "This is a sentence of about forty chars. "
        val text = sentence.repeat(30)

        val chunks = SpeakableText.chunks(text, maxChars = 200)

        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 200)
            assertTrue(chunk.endsWith("."))
        }
        assertEquals(text.trim(), chunks.joinToString(" "))
    }

    @Test
    fun aSentenceLongerThanAChunkIsCutBetweenWords() {
        val words = List(100) { index -> "word$index" }
        val text = words.joinToString(" ")

        val chunks = SpeakableText.chunks(text, maxChars = 120)

        for (chunk in chunks) {
            assertTrue(chunk.length <= 120)
        }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun nothingToSayGivesNoChunks() {
        assertTrue(SpeakableText.chunks("```\ncode only\n```").isEmpty())
        assertTrue(SpeakableText.chunks("   ").isEmpty())
    }
}
