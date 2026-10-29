package com.danemadsen.curie.engine.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * JSON as Python's json module writes and reads it, because that is what the model saw: the chat
 * template's tojson is json.dumps(ensure_ascii=False) (separators ", " and ": ", key order kept,
 * non-ASCII raw, floats as repr), and the model card parses tool call values with json.loads.
 *
 * Elements map to Python values as json.loads makes them: a number literal without a fraction or
 * exponent is an int, any other number (and NaN, Infinity, -Infinity) a float.
 */
object PythonJson {
    /** json.loads rejected the text (Python's json.JSONDecodeError). */
    class DecodeException(message: String, val position: Int) :
        IllegalArgumentException("$message at position $position")

    private const val MAX_DEPTH = 512
    private const val MAX_SIGNIFICANT_DIGITS = 17
    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
    private val FLOAT = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?")

    /** json.dumps([element], ensure_ascii=False). */
    fun dumps(element: JsonElement): String {
        val out = StringBuilder()
        appendJson(element, out)
        return out.toString()
    }

    /**
     * The chat template's render_value macro: strings raw, booleans and null as JSON, objects and
     * arrays through tojson, numbers as Python's str() prints them.
     */
    fun renderValue(element: JsonElement): String = when (element) {
        is JsonObject, is JsonArray -> dumps(element)
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) element.content else pythonStr(element)
    }

    /**
     * json.loads([text]): strict JSON plus NaN, Infinity and -Infinity, whitespace around it allowed.
     * A repeated object key keeps its first position and its last value, like a Python dict.
     *
     * @throws DecodeException where json.loads raises json.JSONDecodeError
     */
    fun loads(text: String): JsonElement {
        val parser = Parser(text)
        return parser.parseDocument()
    }

    /** repr() of a Python float: the shortest digits that read back as [value], laid out as Python does. */
    internal fun floatRepr(value: Double): String {
        if (value.isNaN()) {
            return "nan"
        }
        if (value.isInfinite()) {
            return if (value > 0) "inf" else "-inf"
        }

        val sign = if (value.toRawBits() < 0) "-" else ""
        if (value == 0.0) {
            return "${sign}0.0"
        }

        val shortest = shortestDecimal(abs(value))
        val decimal = shortest.stripTrailingZeros()
        val digits = decimal.unscaledValue().toString()
        // value = 0.<digits> x 10^point
        val point = digits.length - decimal.scale()
        val body = if (point <= -4 || point > 16) {
            scientific(digits, point - 1)
        } else {
            positional(digits, point)
        }
        return sign + body
    }

    // === Writing ===

    private fun appendJson(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> appendObject(element, out)
            is JsonArray -> appendArray(element, out)
            is JsonNull -> out.append("null")
            is JsonPrimitive -> appendPrimitive(element, out)
        }
    }

    private fun appendPrimitive(primitive: JsonPrimitive, out: StringBuilder) {
        if (primitive.isString) {
            appendString(primitive.content, out)
        } else {
            out.append(jsonLiteral(primitive))
        }
    }

    private fun appendObject(element: JsonObject, out: StringBuilder) {
        out.append('{')
        var first = true
        for ((key, value) in element) {
            if (!first) {
                out.append(", ")
            }
            first = false
            appendString(key, out)
            out.append(": ")
            appendJson(value, out)
        }
        out.append('}')
    }

    private fun appendArray(element: JsonArray, out: StringBuilder) {
        out.append('[')
        for ((index, item) in element.withIndex()) {
            if (index > 0) {
                out.append(", ")
            }
            appendJson(item, out)
        }
        out.append(']')
    }

    /** A string as json.dumps(ensure_ascii=False) writes it: only quotes, backslashes and controls escaped. */
    private fun appendString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') appendUnicodeEscape(char, out) else out.append(char)
            }
        }
        out.append('"')
    }

    private fun appendUnicodeEscape(char: Char, out: StringBuilder) {
        val hex = char.code.toString(16)
        out.append("\\u")
        out.append(hex.padStart(4, '0'))
    }

    /** A non-string scalar as json.dumps writes it. */
    private fun jsonLiteral(primitive: JsonPrimitive): String = when (kindOf(primitive)) {
        LiteralKind.BOOLEAN -> primitive.content
        LiteralKind.INTEGER -> BigInteger(primitive.content).toString()
        LiteralKind.FLOAT -> jsonFloat(primitive.content.toDouble())
    }

    /** A non-string scalar as Python's str() prints the value json.loads makes of it. */
    private fun pythonStr(primitive: JsonPrimitive): String = when (kindOf(primitive)) {
        LiteralKind.BOOLEAN -> primitive.content
        LiteralKind.INTEGER -> BigInteger(primitive.content).toString()
        LiteralKind.FLOAT -> floatRepr(primitive.content.toDouble())
    }

    private fun jsonFloat(value: Double): String = when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> floatRepr(value)
    }

    private enum class LiteralKind { BOOLEAN, INTEGER, FLOAT }

    private fun kindOf(primitive: JsonPrimitive): LiteralKind {
        val content = primitive.content
        return when {
            content == "true" || content == "false" -> LiteralKind.BOOLEAN
            INTEGER.matches(content) -> LiteralKind.INTEGER
            FLOAT.matches(content) -> LiteralKind.FLOAT
            content == "NaN" || content == "Infinity" || content == "-Infinity" -> LiteralKind.FLOAT
            else -> throw IllegalArgumentException("$content is not a JSON number, boolean or null")
        }
    }

    // === Float repr ===

    /**
     * The shortest decimal that reads back as [value] (positive, finite), the nearest one when
     * several have that length, as Python's repr (David Gay's dtoa, mode 0) picks.
     */
    private fun shortestDecimal(value: Double): BigDecimal {
        val exact = BigDecimal(value)
        for (precision in 1..MAX_SIGNIFICANT_DIGITS) {
            val nearestContext = MathContext(precision, RoundingMode.HALF_EVEN)
            val nearest = exact.round(nearestContext)
            if (readsBackAs(nearest, value)) {
                return nearest
            }
            // Just above a power of two the gap below is half the gap above, so the decimal on the
            // far side can read back when the nearest one does not
            val farSide = if (nearest < exact) RoundingMode.CEILING else RoundingMode.FLOOR
            val farSideContext = MathContext(precision, farSide)
            val other = exact.round(farSideContext)
            if (readsBackAs(other, value)) {
                return other
            }
        }
        throw IllegalStateException("$value has no round-trip decimal of $MAX_SIGNIFICANT_DIGITS digits")
    }

    // Double.parseDouble rounds correctly on every platform; Android's BigDecimal.toDouble may not
    private fun readsBackAs(decimal: BigDecimal, value: Double): Boolean =
        java.lang.Double.parseDouble(decimal.toString()) == value

    private fun positional(digits: String, point: Int): String = when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length) + ".0"
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }

    private fun scientific(digits: String, exponent: Int): String {
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val exponentSign = if (exponent < 0) "-" else "+"
        val exponentDigits = abs(exponent).toString().padStart(2, '0')
        return "${mantissa}e$exponentSign$exponentDigits"
    }

    // === Reading ===

    /** Python's C json scanner (strict mode). */
    private class Parser(private val text: String) {
        private var position = 0

        fun parseDocument(): JsonElement {
            if (text.startsWith('\uFEFF')) {
                throw DecodeException("Unexpected UTF-8 BOM", 0)
            }
            skipWhitespace()
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (position != text.length) {
                throw DecodeException("Extra data", position)
            }
            return value
        }

        private fun parseValue(depth: Int): JsonElement {
            if (depth > MAX_DEPTH) {
                throw DecodeException("Too deeply nested", position)
            }
            return when (peek()) {
                '"' -> JsonPrimitive(parseString())
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                'n' -> parseConstant("null", JsonNull)
                't' -> parseConstant("true", JsonPrimitive(true))
                'f' -> parseConstant("false", JsonPrimitive(false))
                'N' -> parseConstant("NaN", JsonPrimitive(Double.NaN))
                'I' -> parseConstant("Infinity", JsonPrimitive(Double.POSITIVE_INFINITY))
                else -> parseNumber()
            }
        }

        private fun parseConstant(word: String, value: JsonElement): JsonElement {
            if (!text.startsWith(word, position)) {
                throw DecodeException("Expecting value", position)
            }
            position += word.length
            return value
        }

        private fun parseNumber(): JsonElement {
            val start = position
            if (text.startsWith("-Infinity", position)) {
                position += "-Infinity".length
                return JsonPrimitive(Double.NEGATIVE_INFINITY)
            }
            if (peek() == '-') {
                position++
            }

            when (peek()) {
                '0' -> position++
                in '1'..'9' -> skipDigits()
                else -> throw DecodeException("Expecting value", start)
            }

            var isFloat = false
            if (peek() == '.' && isDigit(peekAt(position + 1))) {
                position++
                skipDigits()
                isFloat = true
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = parseExponent() || isFloat
            }

            val literal = text.substring(start, position)
            if (isFloat) {
                return JsonPrimitive(literal.toDouble())
            }
            return JsonPrimitive(BigInteger(literal))
        }

        /** Reads an exponent if digits follow the e (and sign); otherwise leaves it unread. */
        private fun parseExponent(): Boolean {
            val exponentStart = position
            position++
            if (peek() == '-' || peek() == '+') {
                position++
            }
            if (!isDigit(peek())) {
                position = exponentStart
                return false
            }
            skipDigits()
            return true
        }

        private fun parseString(): String {
            val start = position
            position++
            val value = StringBuilder()
            while (true) {
                val char = peek() ?: throw DecodeException("Unterminated string starting", start)
                when {
                    char == '"' -> {
                        position++
                        return value.toString()
                    }
                    char == '\\' -> appendEscape(value)
                    char < ' ' -> throw DecodeException("Invalid control character", position)
                    else -> {
                        value.append(char)
                        position++
                    }
                }
            }
        }

        private fun appendEscape(value: StringBuilder) {
            val escapeStart = position
            position++
            val code = peek() ?: throw DecodeException("Unterminated string", escapeStart)
            position++
            when (code) {
                '"' -> value.append('"')
                '\\' -> value.append('\\')
                '/' -> value.append('/')
                'b' -> value.append('\b')
                'f' -> value.append('\u000C')
                'n' -> value.append('\n')
                'r' -> value.append('\r')
                't' -> value.append('\t')
                'u' -> value.append(parseHexEscape(escapeStart))
                else -> throw DecodeException("Invalid \\escape", escapeStart)
            }
        }

        // A surrogate pair is two escapes in a row; UTF-16 strings hold them as they come
        private fun parseHexEscape(escapeStart: Int): Char {
            if (position + 4 > text.length) {
                throw DecodeException("Invalid \\uXXXX escape", escapeStart)
            }
            var code = 0
            for (index in position until position + 4) {
                val digit = hexDigit(text[index])
                if (digit < 0) {
                    throw DecodeException("Invalid \\uXXXX escape", escapeStart)
                }
                code = code * 16 + digit
            }
            position += 4
            return code.toChar()
        }

        private fun parseObject(depth: Int): JsonObject {
            position++
            val members = LinkedHashMap<String, JsonElement>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonObject(members)
            }
            while (true) {
                if (peek() != '"') {
                    throw DecodeException("Expecting property name enclosed in double quotes", position)
                }
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') {
                    throw DecodeException("Expecting ':' delimiter", position)
                }
                position++
                skipWhitespace()
                members[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    '}' -> {
                        position++
                        return JsonObject(members)
                    }
                    ',' -> {
                        position++
                        skipWhitespace()
                    }
                    else -> throw DecodeException("Expecting ',' delimiter", position)
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            position++
            val items = ArrayList<JsonElement>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonArray(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ']' -> {
                        position++
                        return JsonArray(items)
                    }
                    ',' -> {
                        position++
                        skipWhitespace()
                    }
                    else -> throw DecodeException("Expecting ',' delimiter", position)
                }
            }
        }

        private fun skipWhitespace() {
            while (true) {
                val char = peek()
                if (char != ' ' && char != '\t' && char != '\n' && char != '\r') {
                    return
                }
                position++
            }
        }

        private fun skipDigits() {
            while (isDigit(peek())) {
                position++
            }
        }

        private fun peek(): Char? = peekAt(position)

        private fun peekAt(index: Int): Char? = if (index < text.length) text[index] else null

        private fun isDigit(char: Char?): Boolean = char != null && char in '0'..'9'

        private fun hexDigit(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> -1
        }
    }
}

/** Python's str.isspace() characters: what str.strip() removes and what re's \s matches. */
internal object PythonWhitespace {
    /** The characters as the body of a regex character class. */
    const val CLASS =
        "\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

    fun isWhitespace(char: Char): Boolean = when (char.code) {
        in 0x09..0x0D, in 0x1C..0x20, 0x85, 0xA0, 0x1680 -> true
        in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Python's str.strip(). */
    fun strip(text: String): String = text.trim { char -> isWhitespace(char) }
}
