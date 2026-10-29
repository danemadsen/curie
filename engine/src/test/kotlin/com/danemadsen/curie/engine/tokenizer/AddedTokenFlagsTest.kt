package com.danemadsen.curie.engine.tokenizer

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * lstrip, rstrip, single_word and normalized: off for every added token of the real file, so the
 * flags fixture sets them on a few tokens and adds two that overlap others ("<tool" and "<|im"),
 * with tokenizers' encodings of texts that exercise them, plainly and with the special tokens kept
 * as text (encode_special_tokens).
 */
class AddedTokenFlagsTest {
    @TestFactory
    fun honoursFlagsLikeTokenizers(): List<DynamicTest> = Goldens.cases("flag-cases.json").map { case ->
        val text = case.getValue("text").jsonPrimitive.content
        DynamicTest.dynamicTest(text.replace("\n", "\\n")) {
            val tokenizer = TestTokenizers.flagsFixture
            val allSpecialTokensLiteral = LiteralText(specialTokenRanges = listOf(text.indices))

            val ids = tokenizer.encode(text)
            val literalIds = tokenizer.encode(text, allSpecialTokensLiteral)
            assertContentEquals(Goldens.ids(case["ids"]), ids, "ids of \"$text\"")
            assertContentEquals(Goldens.ids(case["literal_ids"]), literalIds, "literal ids of \"$text\"")
        }
    }

    @Test
    fun singleWordDiscardsMatchesInsideWords() {
        val tokens = listOf(addedToken(id = 1, content = "<x>", singleWord = true))
        val vocabulary = AddedVocabulary(tokens)

        val insideWord = vocabulary.split("a<x>b", LiteralText.NONE)
        val alone = vocabulary.split("a <x> b", LiteralText.NONE)
        assertEquals(listOf("T:a<x>b"), describe(insideWord))
        assertEquals(listOf("T:a ", "1", "T: b"), describe(alone))
    }

    @Test
    fun stripsTakeTheWhitespaceAroundTheToken() {
        val tokens = listOf(addedToken(id = 1, content = "<x>", leftStrip = true, rightStrip = true))
        val vocabulary = AddedVocabulary(tokens)

        val segments = vocabulary.split("a 　<x>\n\tb", LiteralText.NONE)
        assertEquals(listOf("T:a", "1", "T:b"), describe(segments))
    }

    @Test
    fun rawTokensMatchBeforeNormalizedOnes() {
        val tokens = listOf(
            addedToken(id = 1, content = "<tool_call>", normalized = true),
            addedToken(id = 2, content = "<tool"),
        )
        val vocabulary = AddedVocabulary(tokens)

        val segments = vocabulary.split("<tool_call>", LiteralText.NONE)
        assertEquals(listOf("2", "T:_call>"), describe(segments))
    }

    @Test
    fun aMatchKeptAsTextStaysTextWhole() {
        // As with encode_special_tokens, the "<x" inside a "<x>" kept as text is not matched
        val tokens = listOf(addedToken(id = 1, content = "<x>"), addedToken(id = 2, content = "<x"))
        val vocabulary = AddedVocabulary(tokens)
        val literalX = LiteralText(tokenIds = setOf(1))

        val whole = vocabulary.split("<x>", literalX)
        val followed = vocabulary.split("<x><xy", literalX)
        assertEquals(listOf("T:<x>"), describe(whole))
        assertEquals(listOf("T:<x>", "2", "T:y"), describe(followed))
    }

    @Test
    fun specialTokensStayTextWhereTheyOverlapALiteralRange() {
        val tokens = listOf(
            addedToken(id = 1, content = "<s>"),
            addedToken(id = 2, content = "<t>", special = false),
        )
        val vocabulary = AddedVocabulary(tokens)
        // "<s>" at 0, "a" at 3, "<s>" at 4, "b" at 7, "<t>" at 8: the range takes the second "<s>",
        // the "b" and the first character of the "<t>", which is not special
        val text = "<s>a<s>b<t>"
        val literal = LiteralText(specialTokenRanges = listOf(4 until 9))
        val segments = vocabulary.split(text, literal)
        assertEquals(listOf("1", "T:a<s>b", "2"), describe(segments))

        // A special token that only starts inside the range stays text too
        val straddling = LiteralText(specialTokenRanges = listOf(0 until 1))
        val straddled = vocabulary.split("<s>a", straddling)
        assertEquals(listOf("T:<s>a"), describe(straddled))
    }

    @Test
    fun literalRangesCountFromTheStartOfTheWholeText() {
        // The normalized "<n>" is found in a second pass over the text after "<r>"
        val tokens = listOf(
            addedToken(id = 1, content = "<r>"),
            addedToken(id = 2, content = "<n>", normalized = true),
        )
        val vocabulary = AddedVocabulary(tokens)
        // "<r>" at 0, "ab" at 3, "<n>" at 5, "c" at 8, "<n>" at 9: only the second "<n>" is literal
        val literal = LiteralText(specialTokenRanges = listOf(9 until 12))

        val segments = vocabulary.split("<r>ab<n>c<n>", literal)
        assertEquals(listOf("1", "T:ab", "2", "T:c<n>"), describe(segments))
    }

    private fun describe(segments: List<TextSegment>): List<String> = segments.map { segment ->
        when (segment) {
            is TextSegment.Token -> segment.id.toString()
            is TextSegment.Text -> "T:" + segment.text
        }
    }

    private fun addedToken(
        id: Int,
        content: String,
        special: Boolean = true,
        singleWord: Boolean = false,
        leftStrip: Boolean = false,
        rightStrip: Boolean = false,
        normalized: Boolean = false,
    ) = AddedToken(id, content, special, singleWord, leftStrip, rightStrip, normalized)
}
