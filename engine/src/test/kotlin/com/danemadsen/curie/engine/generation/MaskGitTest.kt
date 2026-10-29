package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.SplittableRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskGitTest {
    private val greedy = AudioSampling(temperature = 0f, topK = 0, repetitionPenalty = 1f, repetitionWindow = 64)

    /** A depth decoder whose logits favour [favourite] (codebook -> code) and that records its inputs. */
    private class FakeDepth(var favourite: (codebook: Int) -> Int) : DepthLogits {
        val codesSeen = ArrayList<IntArray>()
        val maskedSeen = ArrayList<BooleanArray>()

        override fun compute(hidden: FloatArray, codes: IntArray, masked: BooleanArray, logits: FloatArray) {
            codesSeen.add(codes.copyOf())
            maskedSeen.add(masked.copyOf())
            logits.fill(0f)
            for (codebook in 0 until Lfm3Omni.NUM_CODEBOOKS) {
                val code = favourite(codebook)
                logits[codebook * Lfm3Omni.CODEBOOK_SIZE + code] = 10f
                // A runner-up, for the repetition penalty to promote
                val runnerUp = (code + 1) % Lfm3Omni.AUDIO_EOS_CODE
                logits[codebook * Lfm3Omni.CODEBOOK_SIZE + runnerUp] = 9f
            }
        }
    }

    private val hidden = FloatArray(Lfm3Omni.HIDDEN_SIZE)

    @Test
    fun revealsTheCodebooksInOrderInScheduledPasses() {
        val depth = FakeDepth { codebook -> 100 + codebook }
        val order = intArrayOf(11, 3, 7, 0, 5, 1, 9, 2, 10, 4, 6, 8)
        val maskGit = MaskGit(depth, { order.copyOf() }, greedy, SplittableRandom(1))

        val frame = maskGit.sampleFrame(hidden)

        assertContentEquals(IntArray(12) { codebook -> 100 + codebook }, frame)
        assertEquals(Lfm3Omni.MASKGIT_SCHEDULE.size, depth.maskedSeen.size)

        // Pass 1 sees everything masked and no codes; each later pass sees the earlier picks revealed
        var revealed = emptySet<Int>()
        var passStart = 0
        for ((pass, passSize) in Lfm3Omni.MASKGIT_SCHEDULE.withIndex()) {
            val masked = depth.maskedSeen[pass]
            val codes = depth.codesSeen[pass]
            for (codebook in 0 until Lfm3Omni.NUM_CODEBOOKS) {
                val isRevealed = codebook in revealed
                assertEquals(!isRevealed, masked[codebook], "pass $pass codebook $codebook masked")
                val expectedCode = if (isRevealed) 100 + codebook else 0
                assertEquals(expectedCode, codes[codebook], "pass $pass codebook $codebook code")
            }
            revealed = revealed + order.copyOfRange(passStart, passStart + passSize).toSet()
            passStart += passSize
        }
    }

    @Test
    fun anEndOfSpeechCodeEndsTheWholeFrame() {
        val depth = FakeDepth { codebook -> if (codebook == 6) Lfm3Omni.AUDIO_EOS_CODE else 5 }
        val maskGit = MaskGit(depth, MaskGitOrders.IDENTITY, greedy, SplittableRandom(1))

        val frame = maskGit.sampleFrame(hidden)

        assertContentEquals(IntArray(12) { Lfm3Omni.AUDIO_EOS_CODE }, frame)
    }

    @Test
    fun penalizesEachCodebooksRecentCodes() {
        val depth = FakeDepth { codebook -> 40 + codebook }
        // Strong enough to push 10 below the runner-up's 9
        val sampling = greedy.copy(repetitionPenalty = 1.2f)
        val maskGit = MaskGit(depth, MaskGitOrders.IDENTITY, sampling, SplittableRandom(1))

        val first = maskGit.sampleFrame(hidden)
        val second = maskGit.sampleFrame(hidden)

        assertContentEquals(IntArray(12) { codebook -> 40 + codebook }, first)
        // The favourites were just used: their scores drop to 10 / 1.2 < 9, the runners-up win
        assertContentEquals(IntArray(12) { codebook -> 41 + codebook }, second)
    }

    @Test
    fun endOfSpeechFramesStayOutOfTheHistory() {
        var ending = true
        val depth = FakeDepth { codebook -> if (ending && codebook == 0) Lfm3Omni.AUDIO_EOS_CODE else 40 }
        val sampling = greedy.copy(repetitionPenalty = 1.2f)
        val maskGit = MaskGit(depth, MaskGitOrders.IDENTITY, sampling, SplittableRandom(1))

        maskGit.sampleFrame(hidden)
        ending = false
        val frame = maskGit.sampleFrame(hidden)

        // Nothing penalized code 40: the end-of-speech frame was not remembered
        assertContentEquals(IntArray(12) { 40 }, frame)
    }

    @Test
    fun rejectsAnOrderThatIsNotAPermutation() {
        val depth = FakeDepth { 0 }
        val repeated = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10)
        val maskGit = MaskGit(depth, { repeated }, greedy, SplittableRandom(1))

        assertThrows<IllegalArgumentException> { maskGit.sampleFrame(hidden) }
    }

    @Test
    fun rejectsANegativeRepetitionWindow() {
        val sampling = greedy.copy(repetitionWindow = -1)

        assertThrows<IllegalArgumentException> { RecentFrames(-1) }
        assertThrows<IllegalArgumentException> {
            MaskGit(FakeDepth { 0 }, MaskGitOrders.IDENTITY, sampling, SplittableRandom(1))
        }
    }

    @Test
    fun randomOrdersArePermutationsReproducibleFromTheSeed() {
        val first = MaskGitOrders.random(SplittableRandom(99))
        val second = MaskGitOrders.random(SplittableRandom(99))
        val distinctOrders = HashSet<List<Int>>()

        repeat(100) {
            val order = first.next()
            assertContentEquals(order, second.next())
            assertEquals((0 until 12).toList(), order.sorted())
            distinctOrders.add(order.toList())
        }

        assertTrue(distinctOrders.size > 90, "orders should vary: ${distinctOrders.size} distinct")
    }

    @Test
    fun recentFramesKeepEachCodebooksCodes() {
        val frames = RecentFrames(2)
        frames.add(IntArray(12) { codebook -> codebook })
        frames.add(IntArray(12) { codebook -> 100 + codebook })
        frames.add(IntArray(12) { codebook -> 200 + codebook })
        val destination = IntArray(2)

        val count = frames.copyCodebook(5, destination)

        assertEquals(2, count)
        assertEquals(setOf(105, 205), destination.toSet())
    }
}
