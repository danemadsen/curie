package com.danemadsen.curie.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseTextTest {
    @Test
    fun blankLinesSeparateParagraphs() {
        val text = "MIT License\n\nCopyright (c) Microsoft\nAll rights reserved.\n"

        val paragraphs = splitParagraphs(text)

        assertEquals(listOf("MIT License", "Copyright (c) Microsoft\nAll rights reserved."), paragraphs)
    }

    @Test
    fun runsOfBlankAndWhitespaceOnlyLinesMakeOneBreak() {
        val text = "\n\nFirst\n\n  \n\t\n\nSecond\n\n\n"

        val paragraphs = splitParagraphs(text)

        assertEquals(listOf("First", "Second"), paragraphs)
    }

    @Test
    fun indentationIsKept() {
        val text = "TERMS AND CONDITIONS\n\n   1. Definitions.\n\n      \"License\" shall mean the terms"

        val paragraphs = splitParagraphs(text)

        val expected = listOf(
            "TERMS AND CONDITIONS",
            "   1. Definitions.",
            "      \"License\" shall mean the terms",
        )
        assertEquals(expected, paragraphs)
    }

    @Test
    fun windowsLineEndingsBecomeNewlines() {
        val text = "First line\r\nsecond line\r\n\r\nNext paragraph\r\n"

        val paragraphs = splitParagraphs(text)

        assertEquals(listOf("First line\nsecond line", "Next paragraph"), paragraphs)
    }

    @Test
    fun blankTextHasNoParagraphsOrBlocks() {
        assertEquals(emptyList<String>(), splitParagraphs(" \n\n \n"))
        assertEquals(emptyList<String>(), toTextBlocks(""))
    }

    @Test
    fun blocksJoinWholeParagraphsWithABlankLine() {
        val text = "One\n\nTwo\nlines\n\n\nThree"

        val blocks = toTextBlocks(text, maxChars = 100)

        assertEquals(listOf("One\n\nTwo\nlines\n\nThree"), blocks)
    }

    @Test
    fun aBlockEndsBeforeItWouldGrowTooLong() {
        // "aaaa\n\nbbbb" is 10 characters: a third paragraph would make it 16
        val text = "aaaa\n\nbbbb\n\ncccc\n\ndddd"

        val blocks = toTextBlocks(text, maxChars = 10)

        assertEquals(listOf("aaaa\n\nbbbb", "cccc\n\ndddd"), blocks)
    }

    @Test
    fun aLongParagraphIsABlockOfItsOwn() {
        val longParagraph = "x".repeat(25)
        val text = "short\n\n$longParagraph\n\nend"

        val blocks = toTextBlocks(text, maxChars = 10)

        assertEquals(listOf("short", longParagraph, "end"), blocks)
    }

    @Test
    fun blocksKeepAllTheText() {
        val paragraphs = List(50) { index -> "Paragraph $index " + "word ".repeat(index % 7) }
        val text = paragraphs.joinToString("\n\n")

        val blocks = toTextBlocks(text, maxChars = 120)

        assertEquals(text, blocks.joinToString("\n\n"))
        for (block in blocks) {
            assertTrue("a block of ${block.length} characters", block.length <= 120)
        }
    }
}
