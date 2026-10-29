package com.danemadsen.curie.engine.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-tokenizer regex against Oniguruma's, as tokenizers runs it: the pieces of every golden
 * text, and the character classes code point by code point.
 */
class PreTokenizerTest {
    private val classes: JsonObject = unicodeClasses()

    @TestFactory
    fun splitsLikeTokenizers(): List<DynamicTest> = Goldens.cases("encode-cases.json").mapIndexed { index, case ->
        DynamicTest.dynamicTest("case $index") {
            val text = case.getValue("text").jsonPrimitive.content
            val pieceArray = case.getValue("pieces").jsonArray
            val expected = pieceArray.map { piece -> piece.jsonPrimitive.content }
            assertEquals(expected, PreTokenizer.split(text), "pieces of case $index")
        }
    }

    @Test
    fun whitespaceIsOnigurumas() {
        val expected = codePoints("whitespace")
        val whitespaceClass = Pattern.compile("[${UnicodeText.WHITESPACE_CLASS}]")
        for (codePoint in allCodePoints()) {
            val text = String(Character.toChars(codePoint))
            val inClass = whitespaceClass.matcher(text).matches()
            val name = "U+%04X".format(codePoint)
            assertEquals(codePoint in expected, inClass, "$name in the whitespace class")
            if (codePoint <= Char.MAX_VALUE.code) {
                val isWhitespace = UnicodeText.isWhitespace(codePoint.toChar())
                assertEquals(codePoint in expected, isWhitespace, "$name isWhitespace")
            }
        }
    }

    @Test
    fun lettersAreOnigurumasWhereThisJvmKnowsTheCharacter() {
        val letters = codePoints("letter")
        assertMatchesOniguruma("\\p{L}", letters)
    }

    @Test
    fun numbersAreOnigurumasWhereThisJvmKnowsTheCharacter() {
        val numbers = codePoints("number")
        assertMatchesOniguruma("\\p{N}", numbers)
    }

    @Test
    fun contractionsFoldCaseLikeOniguruma() {
        // The golden lists, per alternative of (?i:'s|'t|'re|'ve|'m|'ll|'d), what each letter matches
        val goldenAlternatives = classes.getValue("contractions").jsonObject
        val javaAlternatives = PreTokenizer.CONTRACTIONS.split("|")
        val alternativeNames = goldenAlternatives.keys.toList()
        assertEquals(listOf("s", "t", "re", "ve", "m", "ll", "d"), alternativeNames)
        assertEquals(goldenAlternatives.size, javaAlternatives.size)

        for ((index, entry) in goldenAlternatives.entries.withIndex()) {
            val (alternative, letterSets) = entry
            val matcher = Pattern.compile(javaAlternatives[index]).matcher("")
            for ((position, letterSet) in letterSets.jsonArray.withIndex()) {
                val expected = letterSet.jsonArray.mapTo(HashSet()) { codePoint -> codePoint.jsonPrimitive.int }
                val before = "'" + alternative.substring(0, position)
                val after = alternative.substring(position + 1)
                for (codePoint in allCodePoints()) {
                    val letter = String(Character.toChars(codePoint))
                    val text = before + letter + after
                    val matches = matcher.reset(text).matches()
                    val message = "'$alternative letter $position: U+%04X".format(codePoint)
                    assertEquals(codePoint in expected, matches, message)
                }
            }
        }
    }

    @Test
    fun splitKeepsEveryCharacter() {
        val text = "It's   a\u00A0test,\n\n  with 12345 numbers\u3000and 😀 emoji!  "
        val pieces = PreTokenizer.split(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.none { piece -> piece.isEmpty() })
    }

    /**
     * Java's class and Oniguruma's agree on every code point this JVM assigns; characters newer than
     * the JVM's Unicode version are unassigned here, and are reported rather than failed.
     */
    private fun assertMatchesOniguruma(regex: String, expected: Set<Int>) {
        val pattern = Pattern.compile(regex)
        val matcher = pattern.matcher("")
        var newerThanThisJvm = 0
        for (codePoint in allCodePoints()) {
            val text = String(Character.toChars(codePoint))
            val inJavaClass = matcher.reset(text).matches()
            if (inJavaClass == codePoint in expected) {
                continue
            }
            val unassignedHere = Character.getType(codePoint) == Character.UNASSIGNED.toInt()
            assertTrue(unassignedHere, "$regex differs from Oniguruma at U+%04X".format(codePoint))
            newerThanThisJvm++
        }
        val javaVersion = System.getProperty("java.version")
        println("$regex: $newerThanThisJvm code points differ, all unassigned in Java $javaVersion")
    }

    private fun allCodePoints(): Sequence<Int> =
        (0..Character.MAX_CODE_POINT).asSequence().filter { codePoint -> codePoint !in 0xD800..0xDFFF }

    private fun unicodeClasses(): JsonObject {
        val text = Goldens.text("unicode-classes.json")
        val root = Json.parseToJsonElement(text)
        return root.jsonObject
    }

    /** The code points of the golden class [name], which lists them as [first, last] ranges. */
    private fun codePoints(name: String): Set<Int> {
        val ranges = classes.getValue(name).jsonArray
        val codePoints = HashSet<Int>()
        for (range in ranges) {
            val bounds = range.jsonArray
            val first = bounds[0].jsonPrimitive.int
            val last = bounds[1].jsonPrimitive.int
            for (codePoint in first..last) {
                codePoints.add(codePoint)
            }
        }
        return codePoints
    }
}
