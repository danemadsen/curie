package com.danemadsen.curie.data

import java.io.IOException
import java.io.InputStream

/**
 * Feeds a JSON document to kotlinx.serialization, which keeps every value it reads in memory, and
 * stops before that can exhaust an app's heap:
 * - the document may not pass [maxDocumentBytes];
 * - a token (a string, or a number or other literal) may not pass [maxTokenBytes]: the parser
 *   builds each one in a buffer that grows by doubling, so a long token costs several times its
 *   size while it is read;
 * - arrays and objects may not nest deeper than [maxDepth]: the parser skips unknown values with a
 *   stack as deep as their nesting.
 *
 * The parser wraps whatever its input throws, so the stream records why it stopped: [refusal]
 * when it refused to read on, [sourceFailure] when [source] failed.
 */
internal class BoundedJsonInputStream(
    private val source: InputStream,
    private val maxDocumentBytes: Long,
    private val maxTokenBytes: Long,
    private val maxDepth: Int,
) : InputStream() {
    enum class Refusal {
        DOCUMENT_TOO_LARGE,
        TOKEN_TOO_LARGE,
        TOO_DEEP,
    }

    /** Why the stream refused to read on, or null while it has not. */
    var refusal: Refusal? = null
        private set

    /** What [source] threw, or null while it has not failed. */
    var sourceFailure: IOException? = null
        private set

    private var documentBytes = 0L
    private var tokenBytes = 0L
    private var depth = 0
    private var inString = false
    private var escaping = false

    override fun read(): Int {
        val value = readSource { source.read() }
        if (value >= 0) {
            countDocumentBytes(1)
            inspect(value.toByte())
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = readSource { source.read(buffer, offset, length) }
        if (count > 0) {
            countDocumentBytes(count)
            for (index in offset until offset + count) {
                inspect(buffer[index])
            }
        }
        return count
    }

    private inline fun readSource(read: () -> Int): Int {
        try {
            return read()
        } catch (error: IOException) {
            sourceFailure = error
            throw error
        }
    }

    private fun countDocumentBytes(count: Int) {
        documentBytes += count
        if (documentBytes > maxDocumentBytes) {
            refuse(Refusal.DOCUMENT_TOO_LARGE)
        }
    }

    /** UTF-8 bytes of other characters are 0x80 and up, so they never pass for JSON syntax. */
    private fun inspect(byte: Byte) {
        if (inString) {
            inspectStringByte(byte)
        } else {
            inspectStructureByte(byte)
        }

        if (tokenBytes > maxTokenBytes) {
            refuse(Refusal.TOKEN_TOO_LARGE)
        }
        if (depth > maxDepth) {
            refuse(Refusal.TOO_DEEP)
        }
    }

    private fun inspectStringByte(byte: Byte) {
        tokenBytes += 1
        when {
            escaping -> escaping = false
            byte == BACKSLASH -> escaping = true
            byte == QUOTE -> inString = false
        }
    }

    private fun inspectStructureByte(byte: Byte) {
        when (byte) {
            QUOTE -> {
                inString = true
                tokenBytes = 1
            }
            OPEN_BRACE, OPEN_BRACKET -> {
                depth += 1
                tokenBytes = 0
            }
            CLOSE_BRACE, CLOSE_BRACKET -> {
                depth -= 1
                tokenBytes = 0
            }
            COLON, COMMA, SPACE, TAB, LINE_FEED, CARRIAGE_RETURN -> tokenBytes = 0
            // A number, true, false or null
            else -> tokenBytes += 1
        }
    }

    private fun refuse(reason: Refusal): Nothing {
        refusal = reason
        throw LimitExceededException(reason)
    }

    private class LimitExceededException(reason: Refusal) : IOException("the JSON input is refused: $reason")

    private companion object {
        const val QUOTE: Byte = 0x22
        const val BACKSLASH: Byte = 0x5C
        const val OPEN_BRACE: Byte = 0x7B
        const val CLOSE_BRACE: Byte = 0x7D
        const val OPEN_BRACKET: Byte = 0x5B
        const val CLOSE_BRACKET: Byte = 0x5D
        const val COLON: Byte = 0x3A
        const val COMMA: Byte = 0x2C
        const val SPACE: Byte = 0x20
        const val TAB: Byte = 0x09
        const val LINE_FEED: Byte = 0x0A
        const val CARRIAGE_RETURN: Byte = 0x0D
    }
}
