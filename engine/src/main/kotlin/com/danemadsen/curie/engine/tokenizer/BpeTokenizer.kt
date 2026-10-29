package com.danemadsen.curie.engine.tokenizer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The LFM3 tokenizer: Hugging Face tokenizers' behaviour on the model's tokenizer.json, token for
 * token. Added tokens (special ones like <|im_start|> and others like <think>) are matched in the raw
 * text first; the text between is split by the pre-tokenizer regex and each piece is byte-pair
 * encoded. Decoding maps the tokens back to bytes and the bytes to UTF-8 text.
 *
 * Thread-safe. [load] parses the 18 MB file in one streaming pass, allocating about 80 MB on the way
 * and keeping under 20 MB. On an M-series Mac the first load in a fresh JVM takes about 0.25 s
 * (0.1 s once the JIT has compiled the reader), so expect a second or so on a phone: load once, off
 * the main thread, and share the instance.
 */
class BpeTokenizer internal constructor(data: TokenizerData) {
    private val addedVocabulary = AddedVocabulary(data.addedTokens)
    private val model = BpeModel(data.vocabularyIndex, data.merges, data.ignoreMerges, data.byteTokenIds)
    private val vocabularyIndex = data.vocabularyIndex
    private val addedIds: Map<String, Int> = data.addedTokens.associate { token -> token.content to token.id }

    /** The bytes each id decodes to; an added token's content wins over a vocabulary entry. */
    private val decodedBytes: Array<ByteArray?>

    /** Ids whose token is a special added token (skipped when decoding with skipSpecialTokens). */
    private val special: BooleanArray

    init {
        val idLimit = data.addedTokens.maxOfOrNull { token -> token.id + 1 } ?: 0
        val size = maxOf(data.vocabulary.size, idLimit)
        decodedBytes = data.vocabulary.copyOf(size)
        special = BooleanArray(size)

        for (token in data.addedTokens) {
            // The byte-level decoder reads a token written in its alphabet as bytes, any other as UTF-8
            val bytes = ByteLevel.bytesOf(token.content) ?: token.content.toByteArray(Charsets.UTF_8)
            decodedBytes[token.id] = bytes
        }

        // tokenizers skips a token whose text is a special token's: the added id, and a vocabulary
        // entry spelled the same
        for (token in data.addedTokens) {
            if (!token.special) {
                continue
            }
            special[token.id] = true
            val vocabularyId = vocabularyIdOf(token.content)
            if (vocabularyId != null) {
                special[vocabularyId] = true
            }
        }
    }

    /** The token ids of [text], exactly as tokenizers' encode gives them (no tokens are added). */
    fun encode(text: String): IntArray = encode(text, LiteralText.NONE)

    /**
     * Like [encode], except that the added-token matches [literal] picks stay text and are
     * tokenized like the text around them.
     */
    internal fun encode(text: String, literal: LiteralText): IntArray {
        val ids = ArrayList<Int>()
        val segments = addedVocabulary.split(text, literal)
        for (segment in segments) {
            when (segment) {
                is TextSegment.Token -> ids.add(segment.id)
                is TextSegment.Text -> appendTextIds(segment.text, ids)
            }
        }
        return ids.toIntArray()
    }

    /**
     * The id of [token] as tokenizers' token_to_id finds it: an added token's content, or a
     * vocabulary entry as written in tokenizer.json (byte-level, so " the" is "Ġthe").
     */
    fun tokenId(token: String): Int? = addedIds[token] ?: vocabularyIdOf(token)

    /**
     * The text of [ids], as tokenizers' decode gives it: unknown ids are skipped, as are special
     * tokens when [skipSpecialTokens], and bytes that are not valid UTF-8 become U+FFFD.
     */
    fun decode(ids: IntArray, skipSpecialTokens: Boolean = true): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            val tokenBytes = bytesOf(id, skipSpecialTokens) ?: continue
            bytes.write(tokenBytes)
        }

        val allBytes = bytes.toByteArray()
        val text = StringBuilder(allBytes.size)
        Utf8.decode(allBytes, 0, allBytes.size, text, final = true)
        return text.toString()
    }

    /** Whether [id] is a special token (control tokens like <|im_end|>; not <think> or <tool_call>). */
    fun isSpecial(id: Int): Boolean = id in special.indices && special[id]

    /** A decoder for tokens that arrive one at a time; see [StreamingTextDecoder]. */
    fun newStreamingDecoder(skipSpecialTokens: Boolean = true): StreamingTextDecoder =
        StreamingTextDecoder(this, skipSpecialTokens)

    /** The bytes [id] decodes to, or null for an unknown id or a skipped special token. */
    internal fun bytesOf(id: Int, skipSpecialTokens: Boolean): ByteArray? {
        if (id !in decodedBytes.indices) {
            return null
        }
        if (skipSpecialTokens && special[id]) {
            return null
        }
        return decodedBytes[id]
    }

    private fun appendTextIds(text: String, ids: MutableList<Int>) {
        val pieces = PreTokenizer.split(text)
        for (piece in pieces) {
            val pieceIds = model.encode(piece)
            for (id in pieceIds) {
                ids.add(id)
            }
        }
    }

    private fun vocabularyIdOf(token: String): Int? {
        val bytes = ByteLevel.bytesOf(token) ?: return null
        val id = vocabularyIndex.find(bytes)
        return if (id == TokenIndex.NOT_FOUND) null else id
    }

    companion object {
        /**
         * Reads a tokenizer.json file.
         *
         * @throws IllegalArgumentException if it is not JSON, or not a tokenizer this class implements
         */
        fun load(file: File): BpeTokenizer = file.inputStream().use { stream -> load(stream) }

        /**
         * Reads tokenizer.json from [stream], which stays open.
         *
         * @throws IllegalArgumentException if it is not JSON, or not a tokenizer this class implements
         */
        fun load(stream: InputStream): BpeTokenizer {
            val data = TokenizerFile.read(stream)
            return BpeTokenizer(data)
        }
    }
}
