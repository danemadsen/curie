package com.danemadsen.curie.engine.tokenizer

/**
 * The character classes of the tokenizers library's regexes (Oniguruma for the pre-tokenizer, the
 * Rust regex crate for added tokens), defined explicitly: java.util.regex's \s and \w and those of
 * Android's ICU-based java.util.regex differ from them and from each other.
 */
internal object UnicodeText {
    /**
     * Unicode White_Space, which \s means in both Oniguruma and the Rust regex crate, as the body of
     * a java.util.regex character class.
     */
    const val WHITESPACE_CLASS =
        "\\t\\n\\x0B\\f\\r\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D

    /** Unicode White_Space: the characters of [WHITESPACE_CLASS]. */
    fun isWhitespace(char: Char): Boolean = when (char.code) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** The Rust regex crate's \w: Alphabetic, marks, decimal digits, connector punctuation, joiners. */
    fun isWordCharacter(codePoint: Int): Boolean {
        if (Character.isAlphabetic(codePoint)) {
            return true
        }
        if (codePoint == ZERO_WIDTH_NON_JOINER || codePoint == ZERO_WIDTH_JOINER) {
            return true
        }
        return when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER,
            Character.CONNECTOR_PUNCTUATION,
            -> true
            else -> false
        }
    }
}
