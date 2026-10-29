package com.danemadsen.curie.assistant.wakeword

import java.text.Normalizer
import java.util.Locale

/** "Hey Curie" (or a variant speech recognition wrote instead) found in a transcript. */
internal data class WakeWordMatch(
    /** Phonetic similarity of the name to "Curie", 0 to 1. */
    val score: Double,

    /** The position of the name among the transcript's words: the same hit keeps it across partial results. */
    val nameIndex: Int,
)

/**
 * Finds "Hey Curie" in speech recognition results, which rarely spell it right: a greeting ("hey",
 * "hi", "ok", "hay"...) followed by a word that sounds like Curie ("curry", "kiri", "query", "kerry",
 * "courier"...). The name is compared by a phonetic key and a weighted edit distance in which vowels
 * cost less, because recognisers confuse vowels far more than consonants.
 *
 * "a" (a frequent mishearing of "hey") only counts at the very start and before Curie itself, so
 * "I'd like a curry" never triggers. A greeting glued to the name ("heycurie") only counts with a
 * known spelling of the name, since many words begin like a greeting ("hickory").
 */
internal object WakeWordMatcher {
    /** Normalised phonetic similarity a name needs after a greeting. */
    const val THRESHOLD = 0.80

    /** After "a", "he" or "uh" only near-exact names count. */
    private const val WEAK_PREFIX_THRESHOLD = 0.90

    private val STRONG_PREFIXES = setOf("hey", "hay", "hei", "heh", "hi", "hiya", "ey", "eh", "ay", "okay", "ok")
    private val WEAK_PREFIXES = setOf("a", "he", "uh")

    /** Greetings recognisers glue to the name: "heycurie". */
    private val MERGED_PREFIXES = listOf("hey", "hay", "hi", "ok")

    private val WEAK_PREFIX_NAMES = setOf("curie", "currie", "kuri", "cury", "curi")

    /** Spellings recognisers commonly produce for "Curie", accepted as they are. */
    private val KNOWN_VARIANTS = setOf(
        "curie", "currie", "cury", "curi", "curry", "kuri", "kiri", "kyrie", "query", "kerry", "courier",
        "cory", "corey",
    )

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_LETTERS = Regex("[^a-z]+")
    private val SOFT_C = Regex("c(?=[eiy])")
    private val TARGET_KEY = phoneticKey("curie")

    /** The best match in [transcript], or null when it does not contain the wake phrase. */
    fun findMatch(transcript: String): WakeWordMatch? {
        val words = words(transcript)
        var bestMatch: WakeWordMatch? = null
        for (index in words.indices) {
            val candidate = bestMatchAt(words, index) ?: continue
            val currentBest = bestMatch
            if (currentBest == null || candidate.score > currentBest.score) {
                bestMatch = candidate
            }
        }
        return bestMatch
    }

    /** The best match among a result's hypotheses. */
    fun findMatch(hypotheses: List<String>): WakeWordMatch? {
        val matches = hypotheses.mapNotNull { hypothesis -> findMatch(hypothesis) }
        return matches.maxByOrNull { match -> match.score }
    }

    private fun bestMatchAt(words: List<String>, index: Int): WakeWordMatch? {
        val word = words[index]
        val matches = mutableListOf<WakeWordMatch>()

        val isStrongPrefix = word in STRONG_PREFIXES
        val isWeakPrefix = word in WEAK_PREFIXES
        if (isStrongPrefix || isWeakPrefix) {
            val nameIndex = index + 1
            if (nameIndex < words.size) {
                val name = words[nameIndex]
                addIfAccepted(matches, name, nameIndex, isWeakPrefix, prefixIndex = index)
            }

            // "hey cu rie": recognisers sometimes split the name into short pieces.
            val canJoinSplitName = nameIndex + 1 < words.size && words[nameIndex].length <= 3
            if (canJoinSplitName) {
                val joinedName = words[nameIndex] + words[nameIndex + 1]
                addIfAccepted(matches, joinedName, nameIndex, isWeakPrefix, prefixIndex = index)
            }
        }

        // Only exact spellings: a phonetic match would accept ordinary words ("hi" + "ckory").
        for (prefix in MERGED_PREFIXES) {
            val name = word.removePrefix(prefix)
            val isMergedName = word.startsWith(prefix) && name in KNOWN_VARIANTS
            if (isMergedName) {
                addIfAccepted(matches, name, index, isWeakPrefix = false, prefixIndex = index)
            }
        }

        return matches.maxByOrNull { match -> match.score }
    }

    private fun addIfAccepted(
        matches: MutableList<WakeWordMatch>,
        name: String,
        nameIndex: Int,
        isWeakPrefix: Boolean,
        prefixIndex: Int,
    ) {
        if (isWeakPrefix) {
            val isUtteranceStart = prefixIndex == 0
            val isExactName = name in WEAK_PREFIX_NAMES
            if (!isUtteranceStart || !isExactName) {
                return
            }
        }

        val score = nameScore(name)
        val threshold = if (isWeakPrefix) WEAK_PREFIX_THRESHOLD else THRESHOLD
        if (score < threshold) {
            return
        }
        matches.add(WakeWordMatch(score, nameIndex))
    }

    /** 1 for "Curie" and the known variants, otherwise 1 minus the phonetic distance relative to "Curie". */
    fun nameScore(name: String): Double {
        if (name in KNOWN_VARIANTS) {
            return 1.0
        }
        val key = phoneticKey(name)
        val distance = weightedDistance(key, TARGET_KEY)
        val score = 1.0 - distance / TARGET_KEY.length
        return score.coerceAtLeast(0.0)
    }

    /** Lower-case words of letters only, without accents or apostrophes ("I'd" becomes "id"). */
    fun words(text: String): List<String> {
        val lowerCase = text.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowerCase, Normalizer.Form.NFD)
        val withoutMarks = COMBINING_MARKS.replace(decomposed, "")
        val withoutApostrophes = withoutMarks.replace("'", "").replace("’", "")
        val spaced = NON_LETTERS.replace(withoutApostrophes, " ")
        val trimmed = spaced.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        return trimmed.split(' ')
    }

    /** A rough English sound spelling: "curie" and "curry" both become "kuri". */
    fun phoneticKey(word: String): String {
        var key = word
        key = key.replace("ph", "f")
        key = key.replace("qu", "k")
        key = key.replace("ck", "k")
        key = key.replace("q", "k")
        key = SOFT_C.replace(key, "s")
        key = key.replace("c", "k")
        key = key.replace("x", "ks")
        key = key.replace("ou", "u")
        key = key.replace("oo", "u")
        key = key.replace("ew", "u")
        key = key.replace("ee", "i")
        key = key.replace("ea", "i")
        key = key.replace("ie", "i")
        key = key.replace("ey", "i")
        if (key.endsWith("y")) {
            key = key.dropLast(1) + "i"
        }

        val hasSilentFinalE = key.length > 2 && key.endsWith("e") && key[key.length - 2] !in VOWELS
        if (hasSilentFinalE) {
            key = key.dropLast(1)
        }
        return withoutRepeats(key)
    }

    private fun withoutRepeats(text: String): String {
        val collapsed = StringBuilder()
        for (character in text) {
            val isRepeat = collapsed.isNotEmpty() && collapsed.last() == character
            if (!isRepeat) {
                collapsed.append(character)
            }
        }
        return collapsed.toString()
    }

    /** Edit distance where vowels are cheap to insert, delete or swap and k/g are close. */
    fun weightedDistance(source: String, target: String): Double {
        val rows = source.length + 1
        val columns = target.length + 1
        val table = Array(rows) { DoubleArray(columns) }

        for (row in 1 until rows) {
            table[row][0] = table[row - 1][0] + indelCost(source[row - 1])
        }
        for (column in 1 until columns) {
            table[0][column] = table[0][column - 1] + indelCost(target[column - 1])
        }

        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val deletion = table[row - 1][column] + indelCost(source[row - 1])
                val insertion = table[row][column - 1] + indelCost(target[column - 1])
                val substitutionCost = substitutionCost(source[row - 1], target[column - 1])
                val substitution = table[row - 1][column - 1] + substitutionCost
                table[row][column] = minOf(deletion, insertion, substitution)
            }
        }
        return table[rows - 1][columns - 1]
    }

    private fun indelCost(character: Char): Double {
        return if (character in VOWELS) 0.5 else 1.0
    }

    private fun substitutionCost(first: Char, second: Char): Double {
        if (first == second) {
            return 0.0
        }
        val areBothVowels = first in VOWELS && second in VOWELS
        if (areBothVowels) {
            return 0.4
        }
        val isVelarPair = (first == 'k' && second == 'g') || (first == 'g' && second == 'k')
        if (isVelarPair) {
            return 0.5
        }
        return 1.0
    }
}

/**
 * Partial results change as speech goes on, so a hit in a single partial is not trusted: the same hit
 * must appear in two consecutive partial results, or once in a final (segment) result.
 */
internal class PartialResultStabilizer {
    private var previousNameIndex: Int? = null

    /** Whether to act on [match]; null (no match in this result) breaks a streak. */
    fun shouldFire(match: WakeWordMatch?, isFinal: Boolean): Boolean {
        if (match == null) {
            previousNameIndex = null
            return false
        }
        if (isFinal) {
            return true
        }

        val isRepeat = previousNameIndex == match.nameIndex
        previousNameIndex = match.nameIndex
        return isRepeat
    }

    fun reset() {
        previousNameIndex = null
    }
}
