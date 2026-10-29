package com.danemadsen.curie.engine.tokenizer

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/** What [BpeTokenizer] needs from tokenizer.json, in compact form. */
internal class TokenizerData(
    /** Each vocabulary token's raw bytes, by id (null where the vocabulary skips an id). */
    val vocabulary: Array<ByteArray?>,

    val vocabularyIndex: TokenIndex,
    val merges: MergeTable,
    val ignoreMerges: Boolean,

    /** The token of each single byte. */
    val byteTokenIds: IntArray,

    val addedTokens: List<AddedToken>,
)

/**
 * Reads tokenizer.json in one streaming pass: the vocabulary goes straight into byte arrays and the
 * merges into a [MergeTable], so the 18 MB file never exists as a JSON tree.
 *
 * Only the tokenizer [BpeTokenizer] implements is accepted (LFM3's: no normalizer, the
 * [PreTokenizer] split regex then byte-level mapping, byte-level BPE, the byte-level decoder); any
 * other setting is an error rather than a silently different tokenization.
 */
internal object TokenizerFile {
    private const val BYTE_COUNT = 256

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    fun read(stream: InputStream): TokenizerData = json.decodeFromStream(FileReader, stream)

    /** An added_tokens entry; tokenizers requires every field. */
    @Serializable
    private class AddedTokenEntry(
        val id: Int,
        val content: String,

        @SerialName("single_word")
        val singleWord: Boolean,

        val lstrip: Boolean,
        val rstrip: Boolean,
        val normalized: Boolean,
        val special: Boolean,
    )

    /** The model's vocab: tokens by id, how many entries it lists, and the lookup by bytes. */
    private class Vocabulary(val tokens: Array<ByteArray?>, val entryCount: Int) {
        val index = TokenIndex(tokens)
    }

    private class Model(
        val vocabulary: Vocabulary,
        val merges: MergeTable,
        val settings: Map<String, JsonElement>,
    )

    private object FileReader : DeserializationStrategy<TokenizerData> {
        private val settingNames = listOf(
            "truncation",
            "padding",
            "normalizer",
            "pre_tokenizer",
            "post_processor",
            "decoder",
        )
        private val addedTokensSerializer = ListSerializer(AddedTokenEntry.serializer())

        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Tokenizer") {
            for (name in settingNames) {
                element(name, JsonElement.serializer().descriptor, isOptional = true)
            }
            element("added_tokens", addedTokensSerializer.descriptor, isOptional = true)
            element("model", ModelReader.descriptor)
        }

        override fun deserialize(decoder: Decoder): TokenizerData {
            val settings = HashMap<String, JsonElement>()
            var addedTokens: List<AddedTokenEntry> = emptyList()
            var model: Model? = null

            val structure = decoder.beginStructure(descriptor)
            while (true) {
                val index = structure.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) {
                    break
                }
                when (val name = descriptor.getElementName(index)) {
                    "added_tokens" -> {
                        addedTokens = structure.decodeSerializableElement(descriptor, index, addedTokensSerializer)
                    }
                    "model" -> model = structure.decodeSerializableElement(descriptor, index, ModelReader)
                    else -> {
                        val setting = structure.decodeSerializableElement(descriptor, index, JsonElement.serializer())
                        settings[name] = setting
                    }
                }
            }
            structure.endStructure(descriptor)

            requireNotNull(model) { "tokenizer.json has no model" }
            checkPipeline(settings)
            return tokenizerData(model, addedTokens)
        }
    }

    private object ModelReader : DeserializationStrategy<Model> {
        private val settingNames = listOf(
            "type",
            "dropout",
            "unk_token",
            "continuing_subword_prefix",
            "end_of_word_suffix",
            "fuse_unk",
            "byte_fallback",
            "ignore_merges",
        )

        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BPE") {
            for (name in settingNames) {
                element(name, JsonElement.serializer().descriptor, isOptional = true)
            }
            element("vocab", VocabularyReader.descriptor)
            element("merges", MergesReader.DESCRIPTOR)
        }

        override fun deserialize(decoder: Decoder): Model {
            val settings = HashMap<String, JsonElement>()
            var vocabulary: Vocabulary? = null
            var merges: MergeTable? = null

            val structure = decoder.beginStructure(descriptor)
            while (true) {
                val index = structure.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) {
                    break
                }
                when (val name = descriptor.getElementName(index)) {
                    "vocab" -> vocabulary = structure.decodeSerializableElement(descriptor, index, VocabularyReader)
                    "merges" -> {
                        // tokenizers writes the vocabulary first; resolving merges as they stream needs it
                        val known = requireNotNull(vocabulary) {
                            "tokenizer.json lists the merges before the vocabulary"
                        }
                        val reader = MergesReader(known.index)
                        merges = structure.decodeSerializableElement(descriptor, index, reader)
                    }
                    else -> {
                        val setting = structure.decodeSerializableElement(descriptor, index, JsonElement.serializer())
                        settings[name] = setting
                    }
                }
            }
            structure.endStructure(descriptor)

            requireNotNull(vocabulary) { "tokenizer.json's model has no vocab" }
            requireNotNull(merges) { "tokenizer.json's model has no merges" }
            return Model(vocabulary, merges, settings)
        }
    }

    /** The vocab object, streamed into byte arrays by id. */
    private object VocabularyReader : DeserializationStrategy<Vocabulary> {
        private const val INITIAL_CAPACITY = 1 shl 17

        override val descriptor: SerialDescriptor = MapSerializer(String.serializer(), Int.serializer()).descriptor

        override fun deserialize(decoder: Decoder): Vocabulary {
            var tokens = arrayOfNulls<ByteArray>(INITIAL_CAPACITY)
            var entryCount = 0
            var idLimit = 0

            val structure = decoder.beginStructure(descriptor)
            while (true) {
                val keyIndex = structure.decodeElementIndex(descriptor)
                if (keyIndex == CompositeDecoder.DECODE_DONE) {
                    break
                }
                val token = structure.decodeStringElement(descriptor, keyIndex)
                val valueIndex = structure.decodeElementIndex(descriptor)
                val id = structure.decodeIntElement(descriptor, valueIndex)

                val bytes = requireNotNull(ByteLevel.bytesOf(token)) {
                    "vocabulary token \"$token\" is not written in the byte-level alphabet"
                }
                require(id >= 0) { "vocabulary token \"$token\" has the negative id $id" }
                if (id >= tokens.size) {
                    val capacity = maxOf(id + 1, tokens.size * 2)
                    tokens = tokens.copyOf(capacity)
                }
                require(tokens[id] == null) { "two vocabulary tokens share the id $id" }
                tokens[id] = bytes
                entryCount++
                idLimit = maxOf(idLimit, id + 1)
            }
            structure.endStructure(descriptor)

            val trimmed = tokens.copyOf(idLimit)
            return Vocabulary(trimmed, entryCount)
        }
    }

    /**
     * The merges array, streamed into a [MergeTable]. Entries are [left, right] pairs or, in the
     * legacy format, "left right" strings (tokenizers takes one format for the whole array). The
     * first entry shows which; the other 293k are read as plain strings, without building a JSON
     * element for each.
     */
    private class MergesReader(private val vocabulary: TokenIndex) : DeserializationStrategy<MergeTable> {
        override val descriptor: SerialDescriptor = DESCRIPTOR

        override fun deserialize(decoder: Decoder): MergeTable {
            val merges = MergeTableBuilder(vocabulary)
            val pairReader = MergePairReader(merges)
            var format: MergeFormat? = null

            val structure = decoder.beginStructure(descriptor)
            while (true) {
                val index = structure.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) {
                    break
                }
                when (format) {
                    null -> format = addFirstEntry(structure, index, merges)
                    MergeFormat.PAIRS -> structure.decodeSerializableElement(descriptor, index, pairReader)
                    MergeFormat.LINES -> {
                        val line = structure.decodeStringElement(descriptor, index)
                        merges.addLine(line)
                    }
                }
            }
            structure.endStructure(descriptor)
            return merges.build()
        }

        /** Adds the first entry and returns its format. */
        private fun addFirstEntry(structure: CompositeDecoder, index: Int, merges: MergeTableBuilder): MergeFormat {
            val entry = structure.decodeSerializableElement(descriptor, index, JsonElement.serializer())
            if (entry is JsonArray) {
                val left = stringValue(entry.getOrNull(0))
                val right = stringValue(entry.getOrNull(1))
                require(entry.size == 2 && left != null && right != null) { "merge $entry is not a pair of tokens" }
                merges.addPair(left, right)
                return MergeFormat.PAIRS
            }

            val line = requireNotNull(stringValue(entry)) { "merge $entry is neither a pair nor a string" }
            merges.addLine(line)
            return MergeFormat.LINES
        }

        /** [left, right] arrays, or the legacy "left right" strings. */
        private enum class MergeFormat {
            PAIRS,
            LINES,
        }

        companion object {
            val DESCRIPTOR: SerialDescriptor = ListSerializer(JsonElement.serializer()).descriptor
        }
    }

    /** A [left, right] entry of the merges array, added to [merges] as it is read. */
    private class MergePairReader(private val merges: MergeTableBuilder) : DeserializationStrategy<Unit> {
        override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

        override fun deserialize(decoder: Decoder) {
            val structure = decoder.beginStructure(descriptor)
            val left = nextToken(structure)
            val right = nextToken(structure)
            val end = structure.decodeElementIndex(descriptor)
            require(end == CompositeDecoder.DECODE_DONE) {
                "merge [\"$left\", \"$right\", ...] has more than two tokens"
            }
            structure.endStructure(descriptor)

            merges.addPair(left, right)
        }

        private fun nextToken(structure: CompositeDecoder): String {
            val index = structure.decodeElementIndex(descriptor)
            require(index != CompositeDecoder.DECODE_DONE) { "a merge has fewer than two tokens" }
            return structure.decodeStringElement(descriptor, index)
        }
    }

    /**
     * Collects merges as tokenizer.json writes them (byte-level strings) into a [MergeTable] of
     * token ids. Each merge is resolved in one reused buffer, so the 293k merges allocate nothing
     * beyond the strings read.
     */
    private class MergeTableBuilder(private val vocabulary: TokenIndex) {
        private val merges = MergeTable()

        /** The merge being added: the left token's bytes, then the right token's. */
        private var mergeBytes = ByteArray(INITIAL_MERGE_BYTES)

        fun addPair(left: String, right: String) {
            val mergedLength = left.length + right.length
            if (mergedLength > mergeBytes.size) {
                mergeBytes = ByteArray(maxOf(mergedLength, mergeBytes.size * 2))
            }
            val leftWritten = ByteLevel.writeBytes(left, mergeBytes, 0)
            val rightWritten = ByteLevel.writeBytes(right, mergeBytes, left.length)
            require(leftWritten && rightWritten) {
                "merge \"$left\" \"$right\" is not written in the byte-level alphabet"
            }

            val leftId = vocabulary.find(mergeBytes, 0, left.length)
            val rightId = vocabulary.find(mergeBytes, left.length, mergedLength)
            val mergedId = vocabulary.find(mergeBytes, 0, mergedLength)
            require(leftId != TokenIndex.NOT_FOUND) { "merge token \"$left\" is not in the vocabulary" }
            require(rightId != TokenIndex.NOT_FOUND) { "merge token \"$right\" is not in the vocabulary" }
            require(mergedId != TokenIndex.NOT_FOUND) { "merge token \"$left$right\" is not in the vocabulary" }
            merges.add(leftId, rightId, mergedId)
        }

        /** A legacy "left right" entry; "#version" lines do not count. */
        fun addLine(line: String) {
            if (line.startsWith("#version")) {
                return
            }
            val parts = line.split(' ')
            require(parts.size == 2) { "merge \"$line\" is not two tokens separated by a space" }
            addPair(parts[0], parts[1])
        }

        fun build(): MergeTable {
            merges.trimToSize()
            return merges
        }

        private companion object {
            const val INITIAL_MERGE_BYTES = 64
        }
    }

    // === Checks and assembly ===

    private fun checkPipeline(settings: Map<String, JsonElement>) {
        for (name in listOf("truncation", "padding", "normalizer")) {
            require(isAbsent(settings[name])) { "tokenizer.json sets a $name, which is not supported" }
        }

        val preTokenizer = settings["pre_tokenizer"]
        require(isSupportedPreTokenizer(preTokenizer)) {
            "tokenizer.json's pre_tokenizer is not Split(${PreTokenizer.TOKENIZER_JSON_PATTERN}) " +
                "then ByteLevel: $preTokenizer"
        }

        // The byte-level post-processor only trims offsets; it adds no tokens
        val postProcessor = settings["post_processor"]
        require(isAbsent(postProcessor) || typeOf(postProcessor) == "ByteLevel") {
            "tokenizer.json's post_processor is not supported: $postProcessor"
        }
        val decoder = settings["decoder"]
        require(typeOf(decoder) == "ByteLevel") { "tokenizer.json's decoder is not ByteLevel: $decoder" }
    }

    private fun isSupportedPreTokenizer(element: JsonElement?): Boolean {
        val sequence = element as? JsonObject ?: return false
        if (typeOf(sequence) != "Sequence") {
            return false
        }
        val steps = sequence["pretokenizers"] as? JsonArray ?: return false
        if (steps.size != 2) {
            return false
        }

        val split = steps[0] as? JsonObject ?: return false
        val pattern = split["pattern"] as? JsonObject ?: return false
        val splitMatches = typeOf(split) == "Split" &&
            stringValue(pattern["Regex"]) == PreTokenizer.TOKENIZER_JSON_PATTERN &&
            stringValue(split["behavior"]) == "Isolated" &&
            booleanValue(split["invert"]) == false

        val byteLevel = steps[1] as? JsonObject ?: return false
        val byteLevelMatches = typeOf(byteLevel) == "ByteLevel" &&
            booleanValue(byteLevel["add_prefix_space"]) == false &&
            booleanValue(byteLevel["use_regex"]) == false

        return splitMatches && byteLevelMatches
    }

    private fun tokenizerData(model: Model, entries: List<AddedTokenEntry>): TokenizerData {
        val settings = model.settings
        require(stringValue(settings["type"]) == "BPE") { "tokenizer.json's model is not BPE: ${settings["type"]}" }
        val dropout = settings["dropout"]
        require(isAbsent(dropout) || numberValue(dropout) == 0.0) { "BPE dropout is not supported: $dropout" }
        for (name in listOf("continuing_subword_prefix", "end_of_word_suffix")) {
            require(isAbsent(settings[name])) { "BPE $name is not supported" }
        }
        val ignoreMerges = booleanValue(settings["ignore_merges"]) ?: false
        val vocabulary = model.vocabulary

        // Every byte has a token, so neither unk_token nor byte_fallback ever applies
        val byteTokenIds = IntArray(BYTE_COUNT)
        for (byte in 0 until BYTE_COUNT) {
            val single = byteArrayOf(byte.toByte())
            val id = vocabulary.index.find(single)
            require(id != TokenIndex.NOT_FOUND) { "the vocabulary has no token for the byte $byte" }
            byteTokenIds[byte] = id
        }

        val addedTokens = addedTokens(entries, vocabulary)
        return TokenizerData(
            vocabulary = vocabulary.tokens,
            vocabularyIndex = vocabulary.index,
            merges = model.merges,
            ignoreMerges = ignoreMerges,
            byteTokenIds = byteTokenIds,
            addedTokens = addedTokens,
        )
    }

    /**
     * The added tokens with the ids tokenizers gives them: it ignores the file's ids, reuses the id
     * of a token the vocabulary already holds and numbers the others from the vocabulary size on.
     * The file's ids must agree, so a mismatch is an error rather than a different numbering.
     */
    private fun addedTokens(entries: List<AddedTokenEntry>, vocabulary: Vocabulary): List<AddedToken> {
        val byContent = LinkedHashMap<String, AddedToken>()
        var nextId = vocabulary.entryCount
        for (entry in entries) {
            // tokenizers skips empty tokens
            if (entry.content.isEmpty()) {
                continue
            }

            val existing = byContent[entry.content]
            val vocabularyId = vocabularyIdOf(entry.content, vocabulary.index)
            val id = existing?.id ?: vocabularyId ?: nextId
            if (existing == null && vocabularyId == null) {
                nextId++
            }
            require(id == entry.id) {
                "added token \"${entry.content}\" has the id ${entry.id} in tokenizer.json, " +
                    "but tokenizers numbers it $id"
            }

            val special = entry.special || existing?.special == true
            byContent[entry.content] = AddedToken(
                id = id,
                content = entry.content,
                special = special,
                singleWord = entry.singleWord,
                leftStrip = entry.lstrip,
                rightStrip = entry.rstrip,
                normalized = entry.normalized,
            )
        }
        return byContent.values.toList()
    }

    private fun vocabularyIdOf(content: String, index: TokenIndex): Int? {
        val bytes = ByteLevel.bytesOf(content) ?: return null
        val id = index.find(bytes)
        return if (id == TokenIndex.NOT_FOUND) null else id
    }

    private fun isAbsent(element: JsonElement?): Boolean = element == null || element is JsonNull

    private fun typeOf(element: JsonElement?): String? {
        val type = (element as? JsonObject)?.get("type")
        return stringValue(type)
    }

    private fun stringValue(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun booleanValue(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) {
            return null
        }
        return primitive.content.toBooleanStrictOrNull()
    }

    private fun numberValue(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) {
            return null
        }
        return primitive.content.toDoubleOrNull()
    }
}
