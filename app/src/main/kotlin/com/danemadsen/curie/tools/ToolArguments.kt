package com.danemadsen.curie.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** A tool argument that is missing or malformed. Its message is relayed to the model as the "error". */
internal class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

/**
 * Typed, validated access to the arguments of one tool call.
 *
 * The model writes non-string values as JSON, but it sometimes quotes numbers or writes "on" for a
 * boolean, so every accessor accepts the reasonable spellings and rejects everything else with a
 * message the model can act on.
 */
internal class ToolArguments(private val arguments: JsonObject) {

    /** A trimmed, non-blank text value. Numbers are accepted as their text. */
    fun requiredString(name: String): String {
        val value = optionalString(name)
        if (value == null) {
            throw InvalidToolArgumentException("\"$name\" is required.")
        }
        return value
    }

    /** A trimmed text value, or null when it is absent, null or blank. */
    fun optionalString(name: String): String? {
        val primitive = primitiveOrNull(name) ?: return null
        val text = primitive.content.trim()
        if (text.isEmpty()) {
            return null
        }
        return text
    }

    fun requiredInt(name: String, range: IntRange): Int {
        val value = optionalInt(name, range)
        if (value == null) {
            throw InvalidToolArgumentException("\"$name\" is required.")
        }
        return value
    }

    /** A whole number within [range]; "7", 7 and 7.0 are all accepted. */
    fun optionalInt(name: String, range: IntRange): Int? {
        val primitive = primitiveOrNull(name) ?: return null
        if (primitive.isString && primitive.content.isBlank()) {
            return null
        }

        val number = wholeNumberOrNull(primitive)
        if (number == null) {
            throw InvalidToolArgumentException("\"$name\" must be a whole number.")
        }

        val isInRange = number >= range.first && number <= range.last
        if (!isInRange) {
            throw InvalidToolArgumentException("\"$name\" must be between ${range.first} and ${range.last}.")
        }
        return number.toInt()
    }

    /** A boolean; true/false, "on"/"off" and "yes"/"no" are accepted. */
    fun requiredBoolean(name: String): Boolean {
        val primitive = primitiveOrNull(name)
        if (primitive == null) {
            throw InvalidToolArgumentException("\"$name\" is required.")
        }

        val strictValue = primitive.booleanOrNull
        if (strictValue != null) {
            return strictValue
        }

        val spelling = primitive.content.trim().lowercase(Locale.ROOT)
        return when (spelling) {
            "on", "yes" -> true
            "off", "no" -> false
            else -> throw InvalidToolArgumentException("\"$name\" must be true or false.")
        }
    }

    /** One of [allowed] (compared case-insensitively), or null when the argument is absent. */
    fun optionalChoice(name: String, allowed: List<String>): String? {
        val value = optionalString(name) ?: return null
        val normalized = value.lowercase(Locale.ROOT)
        if (normalized !in allowed) {
            val choices = allowed.joinToString(", ")
            throw InvalidToolArgumentException("\"$name\" must be one of: $choices.")
        }
        return normalized
    }

    fun requiredChoice(name: String, allowed: List<String>): String {
        val value = optionalChoice(name, allowed)
        if (value == null) {
            throw InvalidToolArgumentException("\"$name\" is required.")
        }
        return value
    }

    /** A list of non-blank texts: a JSON array, or a single text separated by commas or semicolons. */
    fun optionalStringList(name: String): List<String> {
        val element = arguments[name] ?: return emptyList()
        if (element is JsonNull) {
            return emptyList()
        }

        val items = if (element is JsonArray) {
            element.map { item -> itemText(name, item) }
        } else {
            val text = itemText(name, element)
            text.split(',', ';')
        }

        val trimmedItems = items.map { item -> item.trim() }
        return trimmedItems.filter { item -> item.isNotEmpty() }
    }

    private fun primitiveOrNull(name: String): JsonPrimitive? {
        val element = arguments[name] ?: return null
        if (element is JsonNull) {
            return null
        }
        if (element !is JsonPrimitive) {
            throw InvalidToolArgumentException("\"$name\" must be a single value, not a list or an object.")
        }
        return element
    }

    private fun itemText(name: String, item: JsonElement): String {
        if (item !is JsonPrimitive || item is JsonNull) {
            throw InvalidToolArgumentException("\"$name\" must be a list of texts.")
        }
        return item.content
    }

    private fun wholeNumberOrNull(primitive: JsonPrimitive): Long? {
        val longValue = primitive.longOrNull
        if (longValue != null) {
            return longValue
        }

        val doubleValue = primitive.doubleOrNull ?: return null
        val isWhole = doubleValue % 1.0 == 0.0
        val fitsInLong = doubleValue >= Long.MIN_VALUE.toDouble() && doubleValue <= Long.MAX_VALUE.toDouble()
        if (!isWhole || !fitsInLong) {
            return null
        }
        return doubleValue.toLong()
    }
}
