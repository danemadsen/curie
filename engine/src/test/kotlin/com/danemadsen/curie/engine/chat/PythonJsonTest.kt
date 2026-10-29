package com.danemadsen.curie.engine.chat

import com.danemadsen.curie.engine.tokenizer.Goldens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PythonJson against Python's json module and the chat template's render_value (goldens). */
class PythonJsonTest {
    @TestFactory
    fun dumpsAndRendersLikePython(): List<DynamicTest> {
        val cases = Goldens.cases("python-json-cases.json")
        return cases.mapIndexed { index, case -> dumpsCase(index, case) }
    }

    private fun dumpsCase(index: Int, case: JsonObject): DynamicTest {
        val expectedDumps = case.getValue("dumps").jsonPrimitive.content
        val expectedRender = case.getValue("render").jsonPrimitive.content
        val asciiText = case.getValue("text").jsonPrimitive.content
        val name = "case $index: $expectedDumps".take(80)
        return DynamicTest.dynamicTest(name) {
            val value = case.getValue("value")
            assertEquals(expectedDumps, PythonJson.dumps(value), "json.dumps")
            assertEquals(expectedRender, PythonJson.renderValue(value), "render_value")

            // json.loads of the ASCII-escaped text, then json.dumps, is the same value again
            assertEquals(expectedDumps, reencoded(asciiText), "json.dumps(json.loads(text))")
        }
    }

    @Test
    fun floatReprIsPythons() {
        val text = Goldens.text("float-repr-cases.txt")
        val lines = text.lines().filter { line -> line.isNotBlank() }
        assertTrue(lines.size > 7_000)
        for (line in lines) {
            val (bits, expected) = line.split(' ')
            val rawBits = java.lang.Long.parseUnsignedLong(bits, 16)
            val value = Double.fromBits(rawBits)
            assertEquals(expected, PythonJson.floatRepr(value), "repr of $bits")
        }
    }

    @Test
    fun readsNumbersAsPythonTypes() {
        assertEquals("100000.0", reencoded("1e5"))
        assertEquals("0", reencoded("-0"))
        assertEquals("-0.0", reencoded("-0.0"))
        val bigInteger = "123456789012345678901234567890"
        assertEquals(bigInteger, reencoded(bigInteger))
        assertEquals("[NaN, Infinity, -Infinity]", reencoded(" [NaN,Infinity,-Infinity] "))

        val overflow = PythonJson.loads("1e400")
        assertEquals("inf", PythonJson.renderValue(overflow))
    }

    @Test
    fun keepsTheFirstPositionAndLastValueOfARepeatedKey() {
        assertEquals("{\"a\": 3, \"b\": 2}", reencoded("{\"a\": 1, \"b\": 2, \"a\": 3}"))
    }

    @Test
    fun rejectsWhatJsonLoadsRejects() {
        val invalid = listOf(
            "", " ", "abc", "01", "1.", ".5", "-", "+1", "1e", "1e+", "[1,]", "[1 2]", "{\"a\":1,}", "{'a': 1}",
            "\"tab\tinside\"", "\"\\uZZZZ\"", "\"\\x\"", "\"unterminated", "\uFEFF1", "nan", "inf", "1_000", "0x10",
            "true false", "[", "{\"a\" 1}", "nul",
        )
        for (text in invalid) {
            assertFailsWith<PythonJson.DecodeException>("json.loads(\"$text\")") { PythonJson.loads(text) }
        }
    }

    @Test
    fun writesKotlinBuiltNumbersAsPythonWould() {
        val items = listOf(JsonPrimitive(true), JsonPrimitive("\u00e9\n"))
        val fields = mapOf(
            "double" to JsonPrimitive(1.0e20),
            "long" to JsonPrimitive(Long.MIN_VALUE),
            "float" to JsonPrimitive(0.5f),
            "nothing" to JsonNull,
            "list" to JsonArray(items),
        )
        val built = JsonObject(fields)

        val expected = "{\"double\": 1e+20, \"long\": -9223372036854775808, \"float\": 0.5, \"nothing\": null, " +
            "\"list\": [true, \"\u00e9\\n\"]}"
        assertEquals(expected, PythonJson.dumps(built))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun refusesLiteralsPythonCannotHold() {
        val kotlinFloat = JsonUnquotedLiteral("1.0f")
        val bareWord = JsonUnquotedLiteral("abc")

        assertFailsWith<IllegalArgumentException> { PythonJson.dumps(kotlinFloat) }
        assertFailsWith<IllegalArgumentException> { PythonJson.renderValue(bareWord) }
    }

    /** What json.dumps(json.loads([text])) gives. */
    private fun reencoded(text: String): String {
        val value = PythonJson.loads(text)
        return PythonJson.dumps(value)
    }
}
