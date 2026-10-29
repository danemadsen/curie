package com.danemadsen.curie.engine.tokenizer

/**
 * UTF-8 decoding that replaces each maximal ill-formed subsequence with one U+FFFD (Unicode 3.9,
 * "U+FFFD substitution of maximal subparts"), as Rust's String::from_utf8_lossy does in the
 * tokenizers decoder. Written out so the text is the same on every JVM and Android version.
 */
internal object Utf8 {
    const val REPLACEMENT = '\uFFFD'

    /**
     * Decodes bytes[from, to) into [out] and returns the index decoding stopped at: [to], or, when
     * [final] is false, the start of a trailing sequence that later bytes could still complete.
     */
    fun decode(bytes: ByteArray, from: Int, to: Int, out: StringBuilder, final: Boolean): Int {
        var position = from
        while (position < to) {
            val lead = bytes[position].toInt() and 0xFF
            if (lead < 0x80) {
                out.append(lead.toChar())
                position++
                continue
            }

            val length = sequenceLength(lead)
            if (length == 0) {
                out.append(REPLACEMENT)
                position++
                continue
            }

            var matched = 1
            while (matched < length && position + matched < to && fits(lead, matched, bytes[position + matched])) {
                matched++
            }

            if (matched == length) {
                appendCodePoint(bytes, position, length, out)
                position += length
            } else if (position + matched == to && !final) {
                // A valid prefix cut off by the end: the next bytes may complete it
                break
            } else {
                out.append(REPLACEMENT)
                position += matched
            }
        }
        return position
    }

    /** The length of the sequence [lead] starts, or 0 for a byte that never starts one. */
    private fun sequenceLength(lead: Int): Int = when (lead) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

    /** Whether [byte] can be byte [index] (1-based after the lead) of a well-formed sequence. */
    private fun fits(lead: Int, index: Int, byte: Byte): Boolean {
        val value = byte.toInt() and 0xFF
        if (index > 1) {
            return value in 0x80..0xBF
        }
        return when (lead) {
            0xE0 -> value in 0xA0..0xBF // no overlong forms
            0xED -> value in 0x80..0x9F // no surrogates
            0xF0 -> value in 0x90..0xBF // no overlong forms
            0xF4 -> value in 0x80..0x8F // nothing past U+10FFFF
            else -> value in 0x80..0xBF
        }
    }

    private fun appendCodePoint(bytes: ByteArray, start: Int, length: Int, out: StringBuilder) {
        val leadPayloadMask = 0xFF shr (length + 1)
        var codePoint = bytes[start].toInt() and leadPayloadMask
        for (index in 1 until length) {
            val payload = bytes[start + index].toInt() and 0x3F
            codePoint = (codePoint shl 6) or payload
        }
        out.appendCodePoint(codePoint)
    }
}
