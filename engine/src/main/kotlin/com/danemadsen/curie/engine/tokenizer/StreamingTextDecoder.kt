package com.danemadsen.curie.engine.tokenizer

/**
 * Decodes tokens as a generation produces them. A token can end inside a character (an emoji's four
 * bytes spread over two tokens): those bytes wait until the character is complete, so every piece of
 * text returned is whole, and the pieces add up to [BpeTokenizer.decode] of all the tokens.
 *
 * Not thread-safe: use one decoder per generation.
 */
class StreamingTextDecoder internal constructor(
    private val tokenizer: BpeTokenizer,
    private val skipSpecialTokens: Boolean,
) {
    private var pending = ByteArray(INITIAL_CAPACITY)
    private var pendingSize = 0

    /** Adds the token [id]; returns the text it completes, which may be empty. */
    fun push(id: Int): String {
        val bytes = tokenizer.bytesOf(id, skipSpecialTokens) ?: return ""
        append(bytes)
        return drain(final = false)
    }

    /**
     * Returns the text still held back (an unfinished character becomes U+FFFD, as decode does) and
     * starts over.
     */
    fun flush(): String = drain(final = true)

    private fun append(bytes: ByteArray) {
        val required = pendingSize + bytes.size
        if (required > pending.size) {
            val capacity = maxOf(required, pending.size * 2)
            pending = pending.copyOf(capacity)
        }
        bytes.copyInto(pending, destinationOffset = pendingSize)
        pendingSize = required
    }

    private fun drain(final: Boolean): String {
        if (pendingSize == 0) {
            return ""
        }
        val text = StringBuilder()
        val decoded = Utf8.decode(pending, 0, pendingSize, text, final)
        pending.copyInto(pending, destinationOffset = 0, startIndex = decoded, endIndex = pendingSize)
        pendingSize -= decoded
        return text.toString()
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
