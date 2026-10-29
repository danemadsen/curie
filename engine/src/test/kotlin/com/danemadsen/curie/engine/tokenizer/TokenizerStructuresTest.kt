package com.danemadsen.curie.engine.tokenizer

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The compact structures behind the tokenizer: the byte-level alphabet, the merge table, the token index. */
class TokenizerStructuresTest {
    @Test
    fun byteLevelAlphabetIsGpt2s() {
        for (byte in 0..255) {
            val char = byteLevelChar(byte)
            assertEquals(byte, ByteLevel.byteOf(char), "byte $byte")
        }
        assertEquals('Ġ', byteLevelChar(' '.code))
        assertEquals('Ċ', byteLevelChar('\n'.code))
        assertEquals(ByteLevel.NOT_IN_ALPHABET, ByteLevel.byteOf(' '))
        assertEquals(ByteLevel.NOT_IN_ALPHABET, ByteLevel.byteOf('中'))

        assertContentEquals(" hello".toByteArray(), ByteLevel.bytesOf("Ġhello"))
        assertNull(ByteLevel.bytesOf("two words"))
    }

    @Test
    fun byteLevelWritesTokensSideBySide() {
        val buffer = ByteArray(8)
        assertTrue(ByteLevel.writeBytes("Ġa", buffer, 0))
        assertTrue(ByteLevel.writeBytes("bĊ", buffer, 2))
        val written = buffer.copyOfRange(0, 4)
        assertContentEquals(" ab\n".toByteArray(), written)

        assertFalse(ByteLevel.writeBytes("a b", buffer, 0))
    }

    @Test
    fun mergeTableKeepsEveryPairThroughGrowth() {
        val random = Random(3)
        val pairs = LinkedHashMap<Pair<Int, Int>, Int>()
        val table = MergeTable()
        while (pairs.size < 50_000) {
            val pair = random.nextInt(130_000) to random.nextInt(130_000)
            if (pair in pairs) {
                continue
            }
            pairs[pair] = table.size
            table.add(pair.first, pair.second, mergedId = pair.first + 1)
        }

        for ((pair, rank) in pairs) {
            assertEquals(rank, table.rank(pair.first, pair.second))
            assertEquals(pair.first + 1, table.mergedId(rank))
        }
        assertEquals(MergeTable.NO_MERGE, table.rank(130_001, 5))
    }

    @Test
    fun aRepeatedMergeTakesTheLaterRank() {
        val table = MergeTable()
        table.add(1, 2, mergedId = 10)
        table.add(3, 4, mergedId = 11)
        table.add(1, 2, mergedId = 10)

        assertEquals(2, table.rank(1, 2))
        assertEquals(1, table.rank(3, 4))
        assertEquals(10, table.mergedId(2))
    }

    @Test
    fun tokenIndexFindsByteRanges() {
        val a = "a".toByteArray()
        val abc = "abc".toByteArray()
        val ete = "été".toByteArray()
        val empty = ByteArray(0)
        val tokens = arrayOf(a, null, abc, ete, empty)
        val index = TokenIndex(tokens)
        val abcInside = "xabcx".toByteArray()
        val ab = "ab".toByteArray()

        assertEquals(0, index.find(a))
        assertEquals(2, index.find(abcInside, 1, 4))
        assertEquals(3, index.find(ete))
        assertEquals(4, index.find(empty))
        assertEquals(TokenIndex.NOT_FOUND, index.find(ab))
    }
}
