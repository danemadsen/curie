package com.danemadsen.curie.engine.tokenizer

/**
 * Vocabulary token ids looked up by the token's bytes: an open-addressed table of ids over the
 * tokens' byte arrays, so no strings are kept (an index of 125k tokens takes 1 MB).
 */
internal class TokenIndex(private val tokenBytes: Array<ByteArray?>) {
    private val slots: IntArray
    private val mask: Int

    init {
        val tokenCount = tokenBytes.count { bytes -> bytes != null }
        val capacity = capacityFor(tokenCount)
        slots = IntArray(capacity) { EMPTY }
        mask = capacity - 1

        for (id in tokenBytes.indices) {
            val bytes = tokenBytes[id] ?: continue
            var slot = hash(bytes, 0, bytes.size) and mask
            while (slots[slot] != EMPTY) {
                slot = (slot + 1) and mask
            }
            slots[slot] = id
        }
    }

    /** The id of the token whose bytes are bytes[from, to), or [NOT_FOUND]. */
    fun find(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var slot = hash(bytes, from, to) and mask
        while (true) {
            val id = slots[slot]
            if (id == EMPTY) {
                return NOT_FOUND
            }
            val candidate = tokenBytes[id]
            if (candidate != null && sameBytes(candidate, bytes, from, to)) {
                return id
            }
            slot = (slot + 1) and mask
        }
    }

    companion object {
        const val NOT_FOUND = -1

        private const val EMPTY = -1
        private const val FNV_OFFSET_BASIS = -0x7ee3623b
        private const val FNV_PRIME = 0x01000193

        /** The smallest power of two that keeps the table at most half full. */
        private fun capacityFor(tokenCount: Int): Int {
            var capacity = 2
            while (capacity < tokenCount * 2) {
                capacity *= 2
            }
            return capacity
        }

        /** FNV-1a, with the high bits folded in for the power-of-two table. */
        private fun hash(bytes: ByteArray, from: Int, to: Int): Int {
            var hash = FNV_OFFSET_BASIS
            for (index in from until to) {
                val byte = bytes[index].toInt() and 0xFF
                hash = (hash xor byte) * FNV_PRIME
            }
            return hash xor (hash ushr 16)
        }

        // java.util.Arrays.equals over ranges needs Android API 33
        private fun sameBytes(token: ByteArray, bytes: ByteArray, from: Int, to: Int): Boolean {
            if (token.size != to - from) {
                return false
            }
            for (index in token.indices) {
                if (token[index] != bytes[from + index]) {
                    return false
                }
            }
            return true
        }
    }
}
