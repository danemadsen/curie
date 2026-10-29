package com.danemadsen.curie.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsTest {
    private fun arguments(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ToolArguments {
        val json = buildJsonObject(build)
        return ToolArguments(json)
    }

    private fun assertInvalid(expectedMessage: String, read: () -> Unit) {
        val exception = assertThrows(InvalidToolArgumentException::class.java) { read() }
        assertEquals(expectedMessage, exception.message)
    }

    @Test
    fun stringsAreTrimmedAndNumbersAcceptedAsText() {
        val parsed = arguments {
            put("name", "  YouTube ")
            put("number", 5551234)
        }

        assertEquals("YouTube", parsed.requiredString("name"))
        assertEquals("5551234", parsed.requiredString("number"))
    }

    @Test
    fun missingBlankAndNullStringsAreAbsent() {
        val parsed = arguments {
            put("blank", "   ")
            put("nothing", JsonNull)
        }

        assertNull(parsed.optionalString("blank"))
        assertNull(parsed.optionalString("nothing"))
        assertNull(parsed.optionalString("missing"))
        assertInvalid("\"missing\" is required.") { parsed.requiredString("missing") }
        assertInvalid("\"blank\" is required.") { parsed.requiredString("blank") }
    }

    @Test
    fun objectsAndListsAreNotText() {
        val parsed = arguments {
            putJsonObject("object") { put("a", 1) }
            putJsonArray("list") { add(JsonPrimitive("a")) }
        }

        assertInvalid("\"object\" must be a single value, not a list or an object.") {
            parsed.requiredString("object")
        }
        assertInvalid("\"list\" must be a single value, not a list or an object.") {
            parsed.requiredString("list")
        }
    }

    @Test
    fun wholeNumbersComeAsNumbersQuotedNumbersOrWholeDecimals() {
        val parsed = arguments {
            put("plain", 7)
            put("quoted", "8")
            put("decimal", 9.0)
        }

        assertEquals(7, parsed.requiredInt("plain", 0..23))
        assertEquals(8, parsed.requiredInt("quoted", 0..23))
        assertEquals(9, parsed.requiredInt("decimal", 0..23))
    }

    @Test
    fun numbersOutsideTheRangeOrNotWholeAreRejected() {
        val parsed = arguments {
            put("hour", 24)
            put("fraction", 7.5)
            put("words", "seven")
            put("huge", "99999999999999999999")
        }

        assertInvalid("\"hour\" must be between 0 and 23.") { parsed.requiredInt("hour", 0..23) }
        assertInvalid("\"fraction\" must be a whole number.") { parsed.requiredInt("fraction", 0..23) }
        assertInvalid("\"words\" must be a whole number.") { parsed.requiredInt("words", 0..23) }
        assertInvalid("\"huge\" must be a whole number.") { parsed.requiredInt("huge", 0..23) }
        assertInvalid("\"missing\" is required.") { parsed.requiredInt("missing", 0..23) }
    }

    @Test
    fun booleansAcceptOnOffAndYesNo() {
        val parsed = arguments {
            put("json", true)
            put("on", "on")
            put("off", "Off")
            put("yes", "yes")
            put("no", "no")
            put("maybe", "maybe")
        }

        assertTrue(parsed.requiredBoolean("json"))
        assertTrue(parsed.requiredBoolean("on"))
        assertFalse(parsed.requiredBoolean("off"))
        assertTrue(parsed.requiredBoolean("yes"))
        assertFalse(parsed.requiredBoolean("no"))
        assertInvalid("\"maybe\" must be true or false.") { parsed.requiredBoolean("maybe") }
        assertInvalid("\"missing\" is required.") { parsed.requiredBoolean("missing") }
    }

    @Test
    fun choicesAreCaseInsensitiveAndValidated() {
        val parsed = arguments {
            put("mode", "Walking")
            put("bad", "flying")
        }
        val modes = listOf("driving", "walking")

        assertEquals("walking", parsed.requiredChoice("mode", modes))
        assertNull(parsed.optionalChoice("missing", modes))
        assertInvalid("\"bad\" must be one of: driving, walking.") { parsed.optionalChoice("bad", modes) }
    }

    @Test
    fun listsComeAsArraysOrSeparatedText() {
        val parsed = arguments {
            put("days", JsonArray(listOf(JsonPrimitive("monday"), JsonPrimitive(" friday "))))
            put("text", "a@example.com; b@example.com, ")
            putJsonArray("mixed") {
                add(JsonPrimitive("x"))
                add(buildJsonObject { put("y", 1) })
            }
        }

        assertEquals(listOf("monday", "friday"), parsed.optionalStringList("days"))
        assertEquals(listOf("a@example.com", "b@example.com"), parsed.optionalStringList("text"))
        assertTrue(parsed.optionalStringList("missing").isEmpty())
        assertInvalid("\"mixed\" must be a list of texts.") { parsed.optionalStringList("mixed") }
    }

    @Test
    fun anEmptyObjectHasNoArguments() {
        val parsed = ToolArguments(JsonObject(emptyMap()))

        assertNull(parsed.optionalString("anything"))
        assertTrue(parsed.optionalStringList("anything").isEmpty())
    }
}
