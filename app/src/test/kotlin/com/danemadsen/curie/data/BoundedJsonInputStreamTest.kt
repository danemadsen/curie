package com.danemadsen.curie.data

import com.danemadsen.curie.data.BoundedJsonInputStream.Refusal
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class BoundedJsonInputStreamTest {
    private fun bounded(
        json: String,
        maxDocumentBytes: Long = 1_000,
        maxTokenBytes: Long = 20,
        maxDepth: Int = 3,
        chunkBytes: Int = 3,
    ): BoundedJsonInputStream {
        val bytes = json.toByteArray()
        val source = ChunkedInputStream(bytes, chunkBytes)
        return BoundedJsonInputStream(source, maxDocumentBytes, maxTokenBytes, maxDepth)
    }

    /** Reads everything; returns the refusal, or null when the whole input passed. */
    private fun refusalOf(input: BoundedJsonInputStream): Refusal? {
        try {
            input.readBytes()
        } catch (error: IOException) {
            // The refusal is recorded
        }
        return input.refusal
    }

    @Test
    fun aDocumentWithinTheLimitsPassesUnchanged() {
        val json = """{"text":"a [list] {not} \"quoted\" \\","items":[1,2,{"x":true}],"name":"Ünïcödé 😀"}"""

        val input = bounded(json, maxTokenBytes = 64)
        val read = input.readBytes()

        assertContentEquals(json.toByteArray(), read)
        assertNull(input.refusal)
        assertNull(input.sourceFailure)
    }

    @Test
    fun theDocumentMayNotPassItsLimit() {
        val padded = "{" + " ".repeat(200) + "}"

        assertEquals(Refusal.DOCUMENT_TOO_LARGE, refusalOf(bounded(padded, maxDocumentBytes = 100)))
        assertNull(refusalOf(bounded(padded, maxDocumentBytes = 202)))
    }

    @Test
    fun aStringMayNotPassTheTokenLimitWhateverItEscapes() {
        val withinLimit = """["${"a".repeat(18)}"]"""
        val tooLong = """["${"a".repeat(19)}"]"""
        // Escaped quotes do not end the string; split reads keep the escape state
        val escapedQuotes = """["${"\\\"".repeat(10)}"]"""

        assertNull(refusalOf(bounded(withinLimit)))
        assertEquals(Refusal.TOKEN_TOO_LARGE, refusalOf(bounded(tooLong)))
        assertEquals(Refusal.TOKEN_TOO_LARGE, refusalOf(bounded(escapedQuotes, chunkBytes = 1)))
    }

    @Test
    fun numbersAndOtherLiteralsAreTokensToo() {
        val longNumber = "[" + "1".repeat(21) + "]"
        val manyShortNumbers = "[" + List(50) { "1234567890" }.joinToString(",") + "]"

        assertEquals(Refusal.TOKEN_TOO_LARGE, refusalOf(bounded(longNumber)))
        assertNull(refusalOf(bounded(manyShortNumbers)))
    }

    @Test
    fun nestingMayNotPassItsDepthButBracketsInStringsDoNotCount() {
        assertNull(refusalOf(bounded("[[[1]]]")))
        assertEquals(Refusal.TOO_DEEP, refusalOf(bounded("[[[[1]]]]")))
        assertNull(refusalOf(bounded("""[["[[[[{{{{"]]""")))
    }

    @Test
    fun aFailingSourceIsRecorded() {
        val failure = IOException("provider failed")
        val source = object : InputStream() {
            override fun read(): Int = throw failure
        }
        val input = BoundedJsonInputStream(source, maxDocumentBytes = 10, maxTokenBytes = 10, maxDepth = 3)

        assertFailsWith<IOException> { input.read() }

        assertSame(failure, input.sourceFailure)
        assertNull(input.refusal)
    }

    /** Hands out at most [chunkBytes] per read, as a network stream may. */
    private class ChunkedInputStream(bytes: ByteArray, private val chunkBytes: Int) : InputStream() {
        private val source = ByteArrayInputStream(bytes)

        override fun read(): Int = source.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, chunkBytes)
            return source.read(buffer, offset, count)
        }
    }
}
