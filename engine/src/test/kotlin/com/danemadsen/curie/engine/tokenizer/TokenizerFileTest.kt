package com.danemadsen.curie.engine.tokenizer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * tokenizer.json reading and the BPE merge order, on small synthetic files: the 256 byte tokens
 * (ids 0 to 255 in byte order) plus the tokens a test names (ids from 256 on).
 */
class TokenizerFileTest {
    @Test
    fun mergesLowestRankFirstAndLeftmostAmongEqualRanks() {
        val json = tokenizerJson(
            extraTokens = listOf("aa", "ab", "aab"),
            merges = listOf("a" to "a", "a" to "b", "aa" to "b"),
        )
        val tokenizer = load(json)
        val aa = id(json, "aa")
        val aab = id(json, "aab")
        val aaThenA = intArrayOf(aa, byte('a'))

        assertContentEquals(intArrayOf(aa, aa), tokenizer.encode("aaaa"))
        assertContentEquals(aaThenA, tokenizer.encode("aaa"))
        // (a, a) outranks (a, b): "aab" merges to aa + b, then to aab
        assertContentEquals(intArrayOf(aab), tokenizer.encode("aab"))
    }

    @Test
    fun aLowerRankWinsOverAPairFurtherLeft() {
        val json = tokenizerJson(
            extraTokens = listOf("ab", "bc"),
            merges = listOf("b" to "c", "a" to "b"),
        )
        val tokenizer = load(json)
        val bc = id(json, "bc")
        val aThenBc = intArrayOf(byte('a'), bc)

        assertContentEquals(aThenBc, tokenizer.encode("abc"))
    }

    @Test
    fun ignoreMergesKeepsAWholeVocabularyWord() {
        val merges = listOf("a" to "a")
        val withShortcut = tokenizerJson(extraTokens = listOf("aa", "aaa"), merges = merges, ignoreMerges = true)
        val withoutShortcut = tokenizerJson(extraTokens = listOf("aa", "aaa"), merges = merges, ignoreMerges = false)
        val wholeWord = intArrayOf(id(withShortcut, "aaa"))
        val mergedPairThenByte = intArrayOf(id(withoutShortcut, "aa"), byte('a'))

        assertContentEquals(wholeWord, load(withShortcut).encode("aaa"))
        assertContentEquals(mergedPairThenByte, load(withoutShortcut).encode("aaa"))
    }

    @Test
    fun readsLegacyMergeStrings() {
        val pairs = tokenizerJson(
            extraTokens = listOf("he", "hel", "Ġw"),
            merges = listOf("h" to "e", "he" to "l", "Ġ" to "w"),
        )
        val legacyMerges = buildJsonArray {
            add(JsonPrimitive("#version: 0.2"))
            add(JsonPrimitive("h e"))
            add(JsonPrimitive("he l"))
            add(JsonPrimitive("Ġ w"))
        }
        val legacy = withModelField(pairs, "merges", legacyMerges)
        val pairsTokenizer = load(pairs)
        val legacyTokenizer = load(legacy)

        val text = "help we hello world"
        assertContentEquals(pairsTokenizer.encode(text), legacyTokenizer.encode(text))
        val helThenP = intArrayOf(id(pairs, "hel"), byte('p'))
        assertContentEquals(helThenP, legacyTokenizer.encode("help"))
    }

    @Test
    fun readsPrettyPrintedFiles() {
        // tokenizers writes each merge over four lines, as here
        val json = tokenizerJson(extraTokens = listOf("ab"), merges = listOf("a" to "b"))
        val prettyJson = Json { prettyPrint = true }
        val text = prettyJson.encodeToString(JsonObject.serializer(), json)
        val tokenizer = BpeTokenizer.load(text.byteInputStream())
        val ab = id(json, "ab")

        assertTrue(text.contains("[\n"), text)
        assertContentEquals(intArrayOf(ab, ab), tokenizer.encode("abab"))
    }

    @Test
    fun rejectsMalformedMerges() {
        val valid = tokenizerJson(extraTokens = listOf("ab"), merges = listOf("a" to "b"))
        load(valid)

        val pair = stringArray("a", "b")
        val triple = stringArray("a", "b", "c")
        val single = stringArray("a")
        val numbers = buildJsonArray {
            add(1)
            add(2)
        }
        val line = JsonPrimitive("a b")
        val malformed = listOf(
            "a pair of three tokens" to listOf(pair, triple),
            "a pair of one token" to listOf(pair, single),
            "a pair of numbers" to listOf(pair, numbers),
            "a first pair of numbers" to listOf(numbers),
            "a line among pairs" to listOf(pair, line),
            "a pair among lines" to listOf(line, pair),
            "a line of three tokens" to listOf(JsonPrimitive("a b c")),
            "a number" to listOf(JsonPrimitive(7)),
        )
        for ((reason, entries) in malformed) {
            val merges = JsonArray(entries)
            val json = withModelField(valid, "merges", merges)
            assertFailsWith<IllegalArgumentException>(reason) { load(json) }
        }
    }

    @Test
    fun numbersAddedTokensLikeTokenizers() {
        // An added token the vocabulary holds keeps its id; the others count on from the vocabulary size
        val json = tokenizerJson(
            extraTokens = listOf("<x>"),
            addedTokens = listOf(256 to "<x>", 257 to "<y>", 258 to "<z>"),
        )
        val tokenizer = load(json)

        assertEquals(256, tokenizer.tokenId("<x>"))
        assertEquals(258, tokenizer.tokenId("<z>"))
        assertContentEquals(intArrayOf(257, 256), tokenizer.encode("<y><x>"))

        val misnumbered = tokenizerJson(addedTokens = listOf(300 to "<y>"))
        val error = assertFailsWith<IllegalArgumentException> { load(misnumbered) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("numbers it 256"), message)
    }

    @Test
    fun rejectsSettingsItDoesNotImplement() {
        val valid = tokenizerJson()
        load(valid)

        val normalizer = buildJsonObject { put("type", "NFC") }
        val truncation = buildJsonObject { put("max_length", 8) }
        val otherRegex = splitPreTokenizer("\\s+")
        val preTokenizer = valid.getValue("pre_tokenizer").toString()
        val prefixSpaceText = preTokenizer.replace("\"add_prefix_space\":false", "\"add_prefix_space\":true")
        val prefixSpace = Json.parseToJsonElement(prefixSpaceText)
        val templateProcessor = buildJsonObject { put("type", "TemplateProcessing") }
        val unknownPair = listOf("x" to "y")
        val unknownMerges = mergesJson(unknownPair)
        val rejected = listOf(
            "a normalizer" to withField(valid, "normalizer", normalizer),
            "truncation" to withField(valid, "truncation", truncation),
            "another split regex" to withField(valid, "pre_tokenizer", otherRegex),
            "a prefix space" to withField(valid, "pre_tokenizer", prefixSpace),
            "no decoder" to withField(valid, "decoder", JsonNull),
            "a template post-processor" to withField(valid, "post_processor", templateProcessor),
            "WordPiece" to withModelField(valid, "type", JsonPrimitive("WordPiece")),
            "dropout" to withModelField(valid, "dropout", JsonPrimitive(0.1)),
            "a subword prefix" to withModelField(valid, "continuing_subword_prefix", JsonPrimitive("##")),
            "a merge outside the vocabulary" to withModelField(valid, "merges", unknownMerges),
        )
        for ((reason, json) in rejected) {
            assertFailsWith<IllegalArgumentException>(reason) { load(json) }
        }
    }

    @Test
    fun rejectsVocabulariesItCannotRead() {
        val valid = tokenizerJson()
        val vocabulary = valid.getValue("model").jsonObject.getValue("vocab").jsonObject

        val missingByte = JsonObject(vocabulary - "a")
        val withoutByte = withModelField(valid, "vocab", missingByte)
        assertFailsWith<IllegalArgumentException> { load(withoutByte) }

        val notByteLevel = withField(vocabulary, "a b", JsonPrimitive(256))
        val withSpace = withModelField(valid, "vocab", notByteLevel)
        assertFailsWith<IllegalArgumentException> { load(withSpace) }

        val sharedId = withField(vocabulary, "ab", JsonPrimitive(1))
        val withSharedId = withModelField(valid, "vocab", sharedId)
        assertFailsWith<IllegalArgumentException> { load(withSharedId) }

        // The merges stream into ids, so they must come after the vocabulary, as tokenizers writes them
        val model = valid.getValue("model").jsonObject
        val noMerges = JsonArray(emptyList())
        val mergesFirst = buildJsonObject {
            put("type", "BPE")
            put("merges", noMerges)
            put("vocab", model.getValue("vocab"))
        }
        val withMergesFirst = withField(valid, "model", mergesFirst)
        assertFailsWith<IllegalArgumentException> { load(withMergesFirst) }
    }

    @Test
    fun rejectsMalformedJson() {
        val truncated = "{\"model\": [".byteInputStream()
        val result = runCatching { BpeTokenizer.load(truncated) }
        val error = result.exceptionOrNull()
        assertTrue(error is SerializationException || error is IllegalArgumentException, "got $error")
    }

    // === Synthetic tokenizer.json ===

    private fun load(json: JsonObject): BpeTokenizer {
        val stream = json.toString().byteInputStream()
        return BpeTokenizer.load(stream)
    }

    private fun byte(char: Char): Int = char.code

    private fun id(json: JsonObject, token: String): Int {
        val vocabulary = json.getValue("model").jsonObject.getValue("vocab").jsonObject
        val id = vocabulary.getValue(token) as JsonPrimitive
        return id.content.toInt()
    }

    private fun tokenizerJson(
        extraTokens: List<String> = emptyList(),
        merges: List<Pair<String, String>> = emptyList(),
        addedTokens: List<Pair<Int, String>> = emptyList(),
        ignoreMerges: Boolean = false,
    ): JsonObject {
        val vocabulary = buildJsonObject {
            for (byte in 0..255) {
                put(byteLevelChar(byte).toString(), byte)
            }
            for ((index, token) in extraTokens.withIndex()) {
                put(token, 256 + index)
            }
        }
        val mergeArray = mergesJson(merges)
        val model = buildJsonObject {
            put("type", "BPE")
            put("dropout", JsonNull)
            put("unk_token", JsonNull)
            put("continuing_subword_prefix", JsonNull)
            put("end_of_word_suffix", JsonNull)
            put("fuse_unk", false)
            put("byte_fallback", false)
            put("ignore_merges", ignoreMerges)
            put("vocab", vocabulary)
            put("merges", mergeArray)
        }
        val added = buildJsonArray {
            for ((id, content) in addedTokens) {
                val entry = addedTokenJson(id, content)
                add(entry)
            }
        }
        val preTokenizer = splitPreTokenizer(PreTokenizer.TOKENIZER_JSON_PATTERN)
        val byteLevel = buildJsonObject { put("type", "ByteLevel") }
        return buildJsonObject {
            put("version", "1.0")
            put("truncation", JsonNull)
            put("padding", JsonNull)
            put("added_tokens", added)
            put("normalizer", JsonNull)
            put("pre_tokenizer", preTokenizer)
            put("post_processor", byteLevel)
            put("decoder", byteLevel)
            put("model", model)
        }
    }

    private fun mergesJson(merges: List<Pair<String, String>>): JsonArray = buildJsonArray {
        for ((left, right) in merges) {
            val pair = buildJsonArray {
                add(JsonPrimitive(left))
                add(JsonPrimitive(right))
            }
            add(pair)
        }
    }

    private fun addedTokenJson(id: Int, content: String): JsonObject = buildJsonObject {
        put("id", id)
        put("content", content)
        put("single_word", false)
        put("lstrip", false)
        put("rstrip", false)
        put("normalized", false)
        put("special", true)
    }

    private fun splitPreTokenizer(regex: String): JsonObject {
        val pattern = buildJsonObject { put("Regex", regex) }
        val split = buildJsonObject {
            put("type", "Split")
            put("pattern", pattern)
            put("behavior", "Isolated")
            put("invert", false)
        }
        val byteLevel = buildJsonObject {
            put("type", "ByteLevel")
            put("add_prefix_space", false)
            put("trim_offsets", true)
            put("use_regex", false)
        }
        val steps = JsonArray(listOf(split, byteLevel))
        return buildJsonObject {
            put("type", "Sequence")
            put("pretokenizers", steps)
        }
    }

    private fun stringArray(vararg items: String): JsonArray {
        val primitives = items.map { item -> JsonPrimitive(item) }
        return JsonArray(primitives)
    }

    private fun withField(json: JsonObject, name: String, value: JsonElement): JsonObject {
        val fields = json + (name to value)
        return JsonObject(fields)
    }

    private fun withModelField(json: JsonObject, name: String, value: JsonElement): JsonObject {
        val model = json.getValue("model").jsonObject
        val changedModel = withField(model, name, value)
        return withField(json, "model", changedModel)
    }
}
