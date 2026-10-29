package com.danemadsen.curie.engine.tokenizer

/**
 * GPT-2's byte-level alphabet (bytes_to_unicode): each byte has a printable stand-in character, so
 * tokenizer.json can write tokens of raw bytes as strings. Printable bytes stand for themselves; the
 * 68 others (controls, space, DEL, no-break space, soft hyphen) map to U+0100 onwards in byte order,
 * which turns the space into 'Ġ' and the newline into 'Ċ'.
 */
internal object ByteLevel {
    const val NOT_IN_ALPHABET = -1

    private const val BYTE_COUNT = 256
    private const val FIRST_SHIFTED_CHAR = 0x100

    /** The byte each alphabet character stands for, indexed by character code. */
    private val byteByChar: IntArray

    init {
        var nextShifted = FIRST_SHIFTED_CHAR
        val charByByte = IntArray(BYTE_COUNT)
        for (byte in 0 until BYTE_COUNT) {
            if (standsForItself(byte)) {
                charByByte[byte] = byte
            } else {
                charByByte[byte] = nextShifted
                nextShifted++
            }
        }

        byteByChar = IntArray(nextShifted) { NOT_IN_ALPHABET }
        for (byte in 0 until BYTE_COUNT) {
            byteByChar[charByByte[byte]] = byte
        }
    }

    /** The byte [char] stands for, or [NOT_IN_ALPHABET]. */
    fun byteOf(char: Char): Int {
        val code = char.code
        if (code >= byteByChar.size) {
            return NOT_IN_ALPHABET
        }
        return byteByChar[code]
    }

    /** The raw bytes of a byte-level [token], or null if it holds a character outside the alphabet. */
    fun bytesOf(token: String): ByteArray? {
        val bytes = ByteArray(token.length)
        return if (writeBytes(token, bytes, 0)) bytes else null
    }

    /**
     * Writes the raw bytes of a byte-level [token] (one per character) into [destination] from
     * [offset]. Returns false, with part of them written, if it holds a character outside the
     * alphabet.
     */
    fun writeBytes(token: String, destination: ByteArray, offset: Int): Boolean {
        for (index in token.indices) {
            val byte = byteOf(token[index])
            if (byte == NOT_IN_ALPHABET) {
                return false
            }
            destination[offset + index] = byte.toByte()
        }
        return true
    }

    private fun standsForItself(byte: Int): Boolean =
        byte in 0x21..0x7E || byte in 0xA1..0xAC || byte in 0xAE..0xFF
}
