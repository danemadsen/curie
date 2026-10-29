package com.danemadsen.curie.data

import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * A "data:<mime type>;base64,<payload>" URI: how the chat export carries an attachment. The
 * payload is decoded while it is read, so an attachment is never held a second time in memory.
 */
internal class DataUri private constructor(
    val mimeType: String,
    private val uri: String,
    private val payloadStart: Int,
) {
    /** The decoded content. Reading fails with an IOException where the payload is not base64. */
    fun openContent(): InputStream {
        val payload = AsciiInputStream(uri, payloadStart)
        val decoder = Base64.getDecoder()
        return decoder.wrap(payload)
    }

    companion object {
        private const val PREFIX = "data:"
        private const val BASE64_MARKER = ";base64"

        fun encode(mimeType: String, content: ByteArray): String {
            val encoder = Base64.getEncoder()
            val payload = encoder.encodeToString(content)
            return "$PREFIX$mimeType$BASE64_MARKER,$payload"
        }

        /** The URI's parts, or null when [uri] is not a base64 data URI. */
        fun parse(uri: String): DataUri? {
            if (!uri.startsWith(PREFIX)) {
                return null
            }
            val comma = uri.indexOf(',')
            if (comma < 0) {
                return null
            }

            val header = uri.substring(PREFIX.length, comma)
            if (!header.endsWith(BASE64_MARKER)) {
                return null
            }
            val mimeType = header.removeSuffix(BASE64_MARKER)
            return DataUri(mimeType, uri, payloadStart = comma + 1)
        }
    }

    /**
     * The characters of [text] from [start] on, one byte each. Base64 is ASCII, so any other
     * character fails the read instead of being cut down to a byte that might pass for base64.
     */
    private class AsciiInputStream(private val text: String, start: Int) : InputStream() {
        private var position = start

        override fun read(): Int {
            if (position >= text.length) {
                return -1
            }
            val byte = asciiAt(position)
            position += 1
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) {
                throw IndexOutOfBoundsException("offset $offset and length $length in ${buffer.size} bytes")
            }
            if (length == 0) {
                return 0
            }
            if (position >= text.length) {
                return -1
            }

            val count = minOf(length, text.length - position)
            for (index in 0 until count) {
                buffer[offset + index] = asciiAt(position + index).toByte()
            }
            position += count
            return count
        }

        private fun asciiAt(index: Int): Int {
            val code = text[index].code
            if (code > MAX_ASCII) {
                throw IOException("character $code at $index is not base64")
            }
            return code
        }

        private companion object {
            const val MAX_ASCII = 0x7F
        }
    }
}
