package com.danemadsen.curie.engine.tokenizer

import kotlin.math.max

/** An entry of tokenizer.json's added_tokens: a token matched in raw text before pre-tokenization. */
internal class AddedToken(
    val id: Int,
    val content: String,
    val special: Boolean,
    val singleWord: Boolean,
    val leftStrip: Boolean,
    val rightStrip: Boolean,
    val normalized: Boolean,
)

/** A stretch of raw text: an added token, or text for the pre-tokenizer and BPE. */
internal sealed interface TextSegment {
    class Token(val id: Int) : TextSegment

    /** [text] starts at character [offset] of the text that was split. */
    class Text(val text: String, val offset: Int) : TextSegment
}

/**
 * The added-token matches that stay text, as tokenizers' encode_special_tokens keeps special
 * tokens (transformers' split_special_tokens): such a match is skipped whole, and the search goes
 * on after it. Matches of [tokenIds] stay text anywhere; matches of special tokens stay text where
 * they overlap one of [specialTokenRanges], which are character ranges of the text being split.
 */
internal class LiteralText(
    private val tokenIds: Set<Int> = emptySet(),
    private val specialTokenRanges: List<IntRange> = emptyList(),
) {
    /** Whether the match of [token] over characters [start, end) stays text. */
    fun keepsAsText(token: AddedToken, start: Int, end: Int): Boolean {
        if (token.id in tokenIds) {
            return true
        }
        if (!token.special) {
            return false
        }
        return specialTokenRanges.any { range -> !range.isEmpty() && start <= range.last && range.first < end }
    }

    companion object {
        /** Every added token matches: tokenizers' plain encode. */
        val NONE = LiteralText()
    }
}

/**
 * Finds the added tokens in raw text as tokenizers' AddedVocabulary does: the leftmost match wins,
 * the longest among those starting there; first among the tokens with normalized = false over the
 * whole text, then among the normalized ones in the text between (without a normalizer both match
 * the raw text). single_word, lstrip and rstrip work as in AddedVocabulary.find_matches, with the
 * Rust regex crate's \w and \s.
 */
internal class AddedVocabulary(tokens: List<AddedToken>) {
    private val rawTokens: Candidates
    private val normalizedTokens: Candidates

    init {
        val (normalized, raw) = tokens.partition { token -> token.normalized }
        rawTokens = Candidates(raw)
        normalizedTokens = Candidates(normalized)
    }

    /** Splits [text]; the matches [literal] picks stay text. */
    fun split(text: String, literal: LiteralText): List<TextSegment> {
        val firstPass = splitWith(text, 0, rawTokens, literal)
        if (normalizedTokens.isEmpty) {
            return firstPass
        }

        val segments = ArrayList<TextSegment>()
        for (segment in firstPass) {
            if (segment is TextSegment.Text) {
                val parts = splitWith(segment.text, segment.offset, normalizedTokens, literal)
                segments.addAll(parts)
            } else {
                segments.add(segment)
            }
        }
        return segments
    }

    /** Splits [text], which starts at character [offset] of the text [split] was given. */
    private fun splitWith(text: String, offset: Int, candidates: Candidates, literal: LiteralText): List<TextSegment> {
        val segments = ArrayList<TextSegment>()
        // Where the previous segment ended, and where the search goes on: after the previous match
        // as found, even when it stayed text, single_word discarded it or rstrip extended it
        var segmentEnd = 0
        var searchFrom = 0
        while (true) {
            val match = candidates.find(text, searchFrom) ?: break
            val token = match.token
            var start = match.start
            var stop = match.start + token.content.length
            searchFrom = stop

            if (literal.keepsAsText(token, offset + start, offset + stop)) {
                continue
            }
            if (token.singleWord && !standsAlone(text, start, stop)) {
                continue
            }
            if (token.leftStrip) {
                start = max(whitespaceRunStart(text, start), segmentEnd)
            }
            if (token.rightStrip) {
                stop = whitespaceRunEnd(text, stop)
            }

            if (segmentEnd < start) {
                val before = text.substring(segmentEnd, start)
                segments.add(TextSegment.Text(before, offset + segmentEnd))
            }
            segments.add(TextSegment.Token(token.id))
            segmentEnd = stop
        }
        if (segmentEnd < text.length) {
            val rest = text.substring(segmentEnd)
            segments.add(TextSegment.Text(rest, offset + segmentEnd))
        }
        return segments
    }

    /** No word character touches the match on either side. */
    private fun standsAlone(text: String, start: Int, stop: Int): Boolean {
        val wordBefore = start > 0 && UnicodeText.isWordCharacter(text.codePointBefore(start))
        val wordAfter = stop < text.length && UnicodeText.isWordCharacter(text.codePointAt(stop))
        return !wordBefore && !wordAfter
    }

    private fun whitespaceRunStart(text: String, end: Int): Int {
        var start = end
        while (start > 0 && UnicodeText.isWhitespace(text[start - 1])) {
            start--
        }
        return start
    }

    private fun whitespaceRunEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && UnicodeText.isWhitespace(text[end])) {
            end++
        }
        return end
    }

    private class Match(val token: AddedToken, val start: Int)

    /** Tokens by their first character, longest first. */
    private class Candidates(tokens: List<AddedToken>) {
        private val byFirstChar: Map<Char, List<AddedToken>> = groupByFirstChar(tokens)

        val isEmpty: Boolean
            get() = byFirstChar.isEmpty()

        /** The leftmost-longest match at or after [from]. */
        fun find(text: String, from: Int): Match? {
            for (start in from until text.length) {
                val tokens = byFirstChar[text[start]] ?: continue
                for (token in tokens) {
                    if (text.startsWith(token.content, start)) {
                        return Match(token, start)
                    }
                }
            }
            return null
        }

        private companion object {
            fun groupByFirstChar(tokens: List<AddedToken>): Map<Char, List<AddedToken>> {
                val nonEmpty = tokens.filter { token -> token.content.isNotEmpty() }
                val grouped = nonEmpty.groupBy { token -> token.content[0] }
                return grouped.mapValues { (_, group) -> group.sortedByDescending { token -> token.content.length } }
            }
        }
    }
}
