package com.danemadsen.curie.engine.tokenizer

import java.util.regex.Pattern

/**
 * The Split pre-tokenizer of tokenizer.json (behavior Isolated): cuts text into the pieces BPE
 * encodes one at a time, with the regex [TOKENIZER_JSON_PATTERN].
 *
 * tokenizers runs that regex on Oniguruma. java.util.regex and Android's ICU-based implementation
 * backtrack through the alternatives the same way, but their \s is not Oniguruma's (Unicode
 * White_Space), and neither folds case like it ((?i:'s) also matches "'ſ"). The compiled pattern is
 * therefore the same regex with \s and the case folding spelled out. \p{L} and \p{N} come from the
 * platform's Unicode tables, which agree with Oniguruma's for every character both versions of
 * Unicode assign (checked code point by code point in the tests); a letter or digit added to Unicode
 * after the platform's version (rare scripts only) splits differently.
 */
internal object PreTokenizer {
    /** The pre-tokenizer regex tokenizer.json must hold, verbatim. */
    const val TOKENIZER_JSON_PATTERN =
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*" +
            "|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"

    /** (?i:'s|'t|'re|'ve|'m|'ll|'d) with Oniguruma's case folding, which also folds 'ſ' to 's'. */
    const val CONTRACTIONS = "'[sS\\u017F]|'[tT]|'[rR][eE]|'[vV][eE]|'[mM]|'[lL][lL]|'[dD]"

    private val PATTERN: Pattern = compilePattern()

    /** The pieces of [text] in order: each match, and any text between matches. */
    fun split(text: String): List<String> {
        val pieces = ArrayList<String>()
        val matcher = PATTERN.matcher(text)
        var end = 0
        while (matcher.find()) {
            val start = matcher.start()
            if (start > end) {
                val between = text.substring(end, start)
                pieces.add(between)
            }
            end = matcher.end()
            val match = text.substring(start, end)
            pieces.add(match)
        }
        if (end < text.length) {
            pieces.add(text.substring(end))
        }
        return pieces
    }

    private fun compilePattern(): Pattern {
        val whitespace = UnicodeText.WHITESPACE_CLASS
        val alternatives = listOf(
            CONTRACTIONS,
            "[^\\r\\n\\p{L}\\p{N}]?\\p{L}+",
            "\\p{N}{1,3}",
            " ?[^$whitespace\\p{L}\\p{N}]+[\\r\\n]*",
            "[$whitespace]*[\\r\\n]+",
            "[$whitespace]+(?![^$whitespace])",
            "[$whitespace]+",
        )
        val expression = alternatives.joinToString("|")
        return Pattern.compile(expression)
    }
}
