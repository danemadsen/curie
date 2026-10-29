package com.danemadsen.curie.engine.tokenizer

import com.danemadsen.curie.engine.Lfm3Omni
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Hugging Face tokenizers' own output (see tools/golden/generate-text-golden.py) against BpeTokenizer,
 * on the minimized fixture and, when available, the real tokenizer.json.
 */
class BpeTokenizerGoldenTest {
    private val encodeCases = Goldens.cases("encode-cases.json")
    private val decodeCases = Goldens.cases("decode-cases.json")
    private val tokenIdCases = Goldens.cases("token-id-cases.json")
    private val literalPlaceholderCases = Goldens.cases("literal-placeholder-cases.json")
    private val literalSpecialCases = Goldens.cases("literal-special-cases.json")

    @TestFactory
    fun encodesLikeTokenizers(): List<DynamicNode> = forEachTokenizer { load ->
        encodeCases.map { case ->
            val text = case.getValue("text").jsonPrimitive.content
            DynamicTest.dynamicTest(describe(text)) {
                val expected = Goldens.ids(case["ids"])
                val actual = load().encode(text)
                assertContentEquals(expected, actual, "ids of ${describe(text)}")
            }
        }
    }

    @TestFactory
    fun decodesLikeTokenizers(): List<DynamicNode> = forEachTokenizer { load ->
        val roundTrips = encodeCases.map { case ->
            val text = case.getValue("text").jsonPrimitive.content
            DynamicTest.dynamicTest("round trip ${describe(text)}") {
                val tokenizer = load()
                val ids = Goldens.ids(case["ids"])
                val withoutSpecial = case.getValue("decoded").jsonPrimitive.content
                val withSpecial = case.getValue("decoded_with_special").jsonPrimitive.content
                assertEquals(withoutSpecial, tokenizer.decode(ids))
                assertEquals(withSpecial, tokenizer.decode(ids, skipSpecialTokens = false))
            }
        }
        val idSequences = decodeCases.mapIndexed { index, case ->
            DynamicTest.dynamicTest("decode case $index") {
                val ids = Goldens.ids(case["ids"])
                val skipSpecial = case.getValue("skip_special").jsonPrimitive.boolean
                val expected = case.getValue("text").jsonPrimitive.content
                assertEquals(expected, load().decode(ids, skipSpecial), "decode of ${ids.toList()}")
            }
        }
        roundTrips + idSequences
    }

    @TestFactory
    fun streamingDecodeAddsUpToDecode(): List<DynamicNode> = forEachTokenizer { load ->
        val encodedIds = encodeCases.map { case -> Goldens.ids(case["ids"]) }
        val decodedIds = decodeCases.map { case -> Goldens.ids(case["ids"]) }
        val allCases = encodedIds + decodedIds
        allCases.mapIndexed { index, ids ->
            DynamicTest.dynamicTest("stream $index") {
                val tokenizer = load()
                for (skipSpecial in listOf(true, false)) {
                    val decoded = tokenizer.decode(ids, skipSpecial)
                    val decoder = tokenizer.newStreamingDecoder(skipSpecial)
                    val streamed = StringBuilder()
                    for (id in ids) {
                        val piece = decoder.push(id)
                        assertWholeCharacters(piece, decoded)
                        streamed.append(piece)
                    }
                    streamed.append(decoder.flush())
                    assertEquals(decoded, streamed.toString())
                    assertEquals("", decoder.flush(), "flush starts over")
                }
            }
        }
    }

    @TestFactory
    fun findsTokenIdsLikeTokenizers(): List<DynamicNode> = forEachTokenizer { load ->
        tokenIdCases.map { case ->
            val token = case.getValue("token").jsonPrimitive.content
            DynamicTest.dynamicTest(describe(token)) {
                val idElement = case.getValue("id")
                val expected = if (idElement is JsonNull) null else idElement.jsonPrimitive.int
                assertEquals(expected, load().tokenId(token))
            }
        }
    }

    /** encode_special_tokens with only the media placeholders special. */
    @TestFactory
    fun keepsLiteralPlaceholdersAsText(): List<DynamicNode> = forEachTokenizer { load ->
        literalPlaceholderCases.map { case ->
            val text = case.getValue("text").jsonPrimitive.content
            DynamicTest.dynamicTest(describe(text)) {
                val placeholders = Lfm3Omni.PLACEHOLDER_TOKENS.toSet()
                val literal = LiteralText(tokenIds = placeholders)
                val actual = load().encode(text, literal)
                assertContentEquals(Goldens.ids(case["ids"]), actual)
            }
        }
    }

    /** encode_special_tokens: every special token stays text, <think> and the tool tags still match. */
    @TestFactory
    fun keepsSpecialTokensAsTextLikeTokenizers(): List<DynamicNode> = forEachTokenizer { load ->
        literalSpecialCases.map { case ->
            val text = case.getValue("text").jsonPrimitive.content
            DynamicTest.dynamicTest(describe(text)) {
                val literal = LiteralText(specialTokenRanges = listOf(text.indices))
                val actual = load().encode(text, literal)
                assertContentEquals(Goldens.ids(case["ids"]), actual)
            }
        }
    }

    private fun forEachTokenizer(tests: (load: () -> BpeTokenizer) -> List<DynamicNode>): List<DynamicNode> =
        TestTokenizers.sources.map { (name, load) ->
            val nodes = tests(load)
            DynamicContainer.dynamicContainer(name, nodes)
        }

    /**
     * Streamed pieces hold whole characters: the decoder holds partial UTF-8 back, so a piece has a
     * replacement character only where the whole decoded text has one.
     */
    private fun assertWholeCharacters(piece: String, decoded: String) {
        if (piece.isEmpty()) {
            return
        }
        val endsInsidePair = Character.isHighSurrogate(piece.last())
        assertFalse(endsInsidePair, "piece ends inside a surrogate pair")
        if (!decoded.contains(Utf8.REPLACEMENT)) {
            assertFalse(piece.contains(Utf8.REPLACEMENT), "piece \"$piece\" holds a partial character")
        }
    }

    private fun describe(text: String): String {
        val escaped = buildString {
            for (char in text.take(60)) {
                if (char < ' ' || char.isSurrogate()) {
                    append("\\u%04x".format(char.code))
                } else {
                    append(char)
                }
            }
        }
        return "\"$escaped\""
    }
}
