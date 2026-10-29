package com.danemadsen.curie.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Int arrays as BLOBs of little-endian int32 values. */
internal object IntArrayBlob {
    fun encode(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Int.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asIntBuffer().put(values)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): IntArray {
        require(bytes.size % Int.SIZE_BYTES == 0) { "a BLOB of int32 values cannot be ${bytes.size} bytes long" }

        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val ints = buffer.asIntBuffer()

        val values = IntArray(ints.remaining())
        ints.get(values)
        return values
    }
}
