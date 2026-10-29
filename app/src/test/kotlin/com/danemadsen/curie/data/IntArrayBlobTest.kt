package com.danemadsen.curie.data

import com.danemadsen.curie.data.db.IntArrayBlob
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class IntArrayBlobTest {
    @Test
    fun storesLittleEndianInt32Values() {
        val bytes = IntArrayBlob.encode(intArrayOf(1, 0x01020304, -1))

        val expected = byteArrayOf(
            1, 0, 0, 0,
            4, 3, 2, 1,
            -1, -1, -1, -1,
        )
        assertContentEquals(expected, bytes)
    }

    @Test
    fun decodesWhatItEncodes() {
        val values = intArrayOf(0, 125_032, Int.MIN_VALUE, Int.MAX_VALUE, -7)

        val decoded = IntArrayBlob.decode(IntArrayBlob.encode(values))

        assertContentEquals(values, decoded)
        assertContentEquals(IntArray(0), IntArrayBlob.decode(ByteArray(0)))
    }

    @Test
    fun rejectsABlobThatIsNotWholeValues() {
        assertFailsWith<IllegalArgumentException> { IntArrayBlob.decode(ByteArray(5)) }
    }
}
