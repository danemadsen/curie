package com.danemadsen.curie.tools

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Scores how well a spoken name ("you tube", "Zoe") matches a label ("YouTube", "Zoë Smith").
 * Comparison ignores case, accents, punctuation and spacing; whole-word and word-prefix matches beat
 * loose substring and spelling matches, so "john" prefers "John Smith" over "Johnny".
 */
internal object NameMatching {
    const val EXACT = 1.0
    const val EXACT_WITHOUT_SPACES = 0.98
    const val ALL_WORDS = 0.9
    const val LABEL_PREFIX = 0.88
    const val WORD_PREFIXES = 0.85
    const val SUBSTRING = 0.7

    /** Spelling similarity (0..1) a pair needs before it counts at all. */
    const val MIN_SPELLING_SIMILARITY = 0.75

    /** Spelling matches score below every structural match. */
    private const val SPELLING_WEIGHT = 0.65

    /** The shortest spoken name allowed to match the start or middle of a longer label. */
    private const val MIN_PARTIAL_LENGTH = 3

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, accents removed, every run of punctuation or space turned into one space. */
    fun normalize(text: String): String {
        val lowerCase = text.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowerCase, Normalizer.Form.NFD)
        val withoutMarks = COMBINING_MARKS.replace(decomposed, "")
        val withoutApostrophes = withoutMarks.replace("'", "").replace("’", "")
        val spaced = NON_ALPHANUMERIC.replace(withoutApostrophes, " ")
        return spaced.trim()
    }

    fun words(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            return emptyList()
        }
        return normalized.split(' ')
    }

    /** 0 when [spoken] and [label] are unrelated, up to [EXACT] when they are the same name. */
    fun score(spoken: String, label: String): Double {
        val spokenWords = words(spoken)
        val labelWords = words(label)
        if (spokenWords.isEmpty() || labelWords.isEmpty()) {
            return 0.0
        }

        if (spokenWords == labelWords) {
            return EXACT
        }

        val spokenCompact = spokenWords.joinToString("")
        val labelCompact = labelWords.joinToString("")
        if (spokenCompact == labelCompact) {
            return EXACT_WITHOUT_SPACES
        }

        val structuralScore = structuralScore(spokenWords, labelWords, spokenCompact, labelCompact)
        if (structuralScore > 0.0) {
            return structuralScore
        }

        val similarity = spellingSimilarity(spokenCompact, labelCompact)
        if (similarity < MIN_SPELLING_SIMILARITY) {
            return 0.0
        }
        return similarity * SPELLING_WEIGHT
    }

    private fun structuralScore(
        spokenWords: List<String>,
        labelWords: List<String>,
        spokenCompact: String,
        labelCompact: String,
    ): Double {
        if (matchesWordsInOrder(spokenWords, labelWords, allowPrefix = false)) {
            return ALL_WORDS
        }

        val isLongEnough = spokenCompact.length >= MIN_PARTIAL_LENGTH
        if (!isLongEnough) {
            return 0.0
        }

        if (labelCompact.startsWith(spokenCompact)) {
            return LABEL_PREFIX
        }
        if (matchesWordsInOrder(spokenWords, labelWords, allowPrefix = true)) {
            return WORD_PREFIXES
        }
        if (labelCompact.contains(spokenCompact)) {
            return SUBSTRING
        }
        return 0.0
    }

    /** Whether every spoken word matches a later label word, in order (whole words or word prefixes). */
    private fun matchesWordsInOrder(spokenWords: List<String>, labelWords: List<String>, allowPrefix: Boolean): Boolean {
        var labelIndex = 0
        for (spokenWord in spokenWords) {
            var matched = false
            while (labelIndex < labelWords.size && !matched) {
                val labelWord = labelWords[labelIndex]
                matched = labelWord == spokenWord || (allowPrefix && labelWord.startsWith(spokenWord))
                labelIndex += 1
            }
            if (!matched) {
                return false
            }
        }
        return true
    }

    /**
     * How alike two names are spelt, ignoring spaces, without [score]'s cut-off: for ranking "did you
     * mean" suggestions, never for picking what to open.
     */
    fun looseSimilarity(spoken: String, label: String): Double {
        val spokenCompact = words(spoken).joinToString("")
        val labelCompact = words(label).joinToString("")
        if (spokenCompact.isEmpty() || labelCompact.isEmpty()) {
            return 0.0
        }
        return spellingSimilarity(spokenCompact, labelCompact)
    }

    /** 1 minus the edit distance relative to the longer text. */
    fun spellingSimilarity(first: String, second: String): Double {
        val longerLength = max(first.length, second.length)
        if (longerLength == 0) {
            return 1.0
        }
        val distance = editDistance(first, second)
        return 1.0 - distance.toDouble() / longerLength
    }

    private fun editDistance(first: String, second: String): Int {
        var previousRow = IntArray(second.length + 1) { column -> column }
        var currentRow = IntArray(second.length + 1)

        for (row in 1..first.length) {
            currentRow[0] = row
            for (column in 1..second.length) {
                val substitutionCost = if (first[row - 1] == second[column - 1]) 0 else 1
                val deletion = previousRow[column] + 1
                val insertion = currentRow[column - 1] + 1
                val substitution = previousRow[column - 1] + substitutionCost
                currentRow[column] = minOf(deletion, insertion, substitution)
            }
            val finishedRow = previousRow
            previousRow = currentRow
            currentRow = finishedRow
        }
        return previousRow[second.length]
    }
}
