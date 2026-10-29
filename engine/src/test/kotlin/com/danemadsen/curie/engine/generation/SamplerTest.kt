package com.danemadsen.curie.engine.generation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplerTest {
    private val greedy = SamplingSettings(temperature = 0f, topK = 0, repetitionPenalty = 1f)
    private val noHistory = IntArray(0)
    private val nothingSuppressed = IntArray(0)

    @Test
    fun greedyPicksTheFirstLargestScore() {
        val sampler = Sampler(5)
        val scores = floatArrayOf(1f, 3f, 2f, 3f, -1f)

        val id = sampler.sample(scores, greedy, noHistory, 0, nothingSuppressed, SplittableRandom(1))

        assertEquals(1, id)
    }

    @Test
    fun suppressedIdsAreNeverPicked() {
        val sampler = Sampler(4)
        val scores = floatArrayOf(0f, 9f, 8f, 1f)
        val suppressed = intArrayOf(1, 2)

        val id = sampler.sample(scores, greedy, noHistory, 0, suppressed, SplittableRandom(1))

        assertEquals(3, id)
        assertEquals(Float.NEGATIVE_INFINITY, scores[1])
        assertEquals(Float.NEGATIVE_INFINITY, scores[2])
    }

    @Test
    fun repetitionPenaltyDividesPositiveAndMultipliesNegativeScoresOncePerId() {
        val sampler = Sampler(4)
        val scores = floatArrayOf(2.4f, -1.5f, 3f, 0.5f)
        val settings = greedy.copy(repetitionPenalty = 1.2f)
        // id 0 three times and id 1 twice: each is penalized once, as transformers' gather/scatter does
        val history = intArrayOf(0, 1, 0, 0, 1)

        sampler.sample(scores, settings, history, history.size, nothingSuppressed, SplittableRandom(1))

        assertEquals(2.4f / 1.2f, scores[0])
        assertEquals(-1.5f * 1.2f, scores[1])
        assertEquals(3f, scores[2])
        assertEquals(0.5f, scores[3])
    }

    @Test
    fun repetitionPenaltyCanChangeTheGreedyPick() {
        val sampler = Sampler(3)
        val scores = floatArrayOf(1.0f, 1.1f, 0.2f)
        val settings = greedy.copy(repetitionPenalty = 1.5f)
        val history = intArrayOf(1)

        val id = sampler.sample(scores, settings, history, history.size, nothingSuppressed, SplittableRandom(1))

        assertEquals(0, id)
    }

    @Test
    fun onlyTheGivenPartOfTheHistoryCounts() {
        val sampler = Sampler(3)
        val scores = floatArrayOf(1f, 2f, 0f)
        val settings = greedy.copy(repetitionPenalty = 4f)
        val history = intArrayOf(0, 1)

        sampler.sample(scores, settings, history, 1, nothingSuppressed, SplittableRandom(1))

        assertEquals(0.25f, scores[0])
        assertEquals(2f, scores[1])
    }

    @Test
    fun topKKeepsTheKLargestScoresAndTheirTies() {
        val sampler = Sampler(6)
        val scores = floatArrayOf(0.5f, 3f, 2f, 2f, 1f, -4f)
        val settings = SamplingSettings(temperature = 1f, topK = 2, repetitionPenalty = 1f)

        sampler.sample(scores, settings, noHistory, 0, nothingSuppressed, SplittableRandom(7))

        // The second largest value is 2: both of its ids stay, everything below goes
        val dropped = Float.NEGATIVE_INFINITY
        val expected = floatArrayOf(dropped, 3f, 2f, 2f, dropped, dropped)
        assertEquals(expected.toList(), scores.toList())
    }

    @Test
    fun samplingDrawsOnlyFromTheTopK() {
        val sampler = Sampler(8)
        val settings = SamplingSettings(temperature = 1f, topK = 3, repetitionPenalty = 1f)
        val random = SplittableRandom(42)
        val drawn = HashSet<Int>()

        repeat(500) {
            val scores = floatArrayOf(0f, 1f, 5f, 4.5f, 0.2f, 4f, 3f, -2f)
            drawn.add(sampler.sample(scores, settings, noHistory, 0, nothingSuppressed, random))
        }

        assertEquals(setOf(2, 3, 5), drawn)
    }

    @Test
    fun samplingFollowsTheSoftmax() {
        val sampler = Sampler(2)
        val settings = SamplingSettings(temperature = 1f, topK = 0, repetitionPenalty = 1f)
        val random = SplittableRandom(3)
        var firstCount = 0
        val draws = 20_000

        repeat(draws) {
            // softmax(ln 3, 0) = (0.75, 0.25)
            val scores = floatArrayOf(kotlin.math.ln(3f), 0f)
            if (sampler.sample(scores, settings, noHistory, 0, nothingSuppressed, random) == 0) {
                firstCount++
            }
        }

        val share = firstCount.toDouble() / draws
        assertTrue(share in 0.73..0.77, "share of the first id: $share")
    }

    @Test
    fun aLowTemperatureSharpensTheDistribution() {
        val sampler = Sampler(2)
        val settings = SamplingSettings(temperature = 0.05f, topK = 0, repetitionPenalty = 1f)
        val random = SplittableRandom(5)
        var firstCount = 0

        repeat(1_000) {
            val scores = floatArrayOf(1f, 0f)
            if (sampler.sample(scores, settings, noHistory, 0, nothingSuppressed, random) == 0) {
                firstCount++
            }
        }

        // exp(-1 / 0.05) is about 2e-9: the second id is practically never drawn
        assertEquals(1_000, firstCount)
    }

    @Test
    fun theSameSeedDrawsTheSameIds() {
        val settings = SamplingSettings(temperature = 0.8f, topK = 0, repetitionPenalty = 1f)

        fun draws(seed: Long): List<Int> {
            val sampler = Sampler(16)
            val random = SplittableRandom(seed)
            return List(50) {
                val scores = FloatArray(16) { id -> (id % 5).toFloat() * 0.3f }
                sampler.sample(scores, settings, noHistory, 0, nothingSuppressed, random)
            }
        }

        assertEquals(draws(11), draws(11))
        assertTrue(draws(11) != draws(12), "different seeds should draw differently")
    }

    @Test
    fun suppressedIdsAreNeverDrawn() {
        val sampler = Sampler(3)
        val settings = SamplingSettings(temperature = 1f, topK = 0, repetitionPenalty = 1f)
        val random = SplittableRandom(9)
        val suppressed = intArrayOf(0)

        repeat(200) {
            val scores = floatArrayOf(10f, 0f, 0f)
            val id = sampler.sample(scores, settings, noHistory, 0, suppressed, random)
            assertTrue(id != 0)
        }
    }

    @Test
    fun rejectsScoresOfTheWrongSize() {
        val sampler = Sampler(3)

        assertThrows<IllegalArgumentException> {
            sampler.sample(FloatArray(4), greedy, noHistory, 0, nothingSuppressed, SplittableRandom(1))
        }
    }

    @Test
    fun rejectsSettingsThatCannotSample() {
        assertThrows<IllegalArgumentException> { greedy.copy(temperature = Float.NaN) }
        assertThrows<IllegalArgumentException> { greedy.copy(temperature = Float.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { greedy.copy(topK = -1) }
        assertThrows<IllegalArgumentException> { greedy.copy(repetitionPenalty = 0f) }
        assertThrows<IllegalArgumentException> { greedy.copy(repetitionPenalty = Float.NaN) }
    }

    @Test
    fun rejectsANegativeRepetitionWindow() {
        val sampling = TextSampling(repetitionWindow = -1)

        assertThrows<IllegalArgumentException> { RecentTokens(-1) }
        assertThrows<IllegalArgumentException> { TextSampler(sampling, nothingSuppressed, SplittableRandom(1)) }
    }

    @Test
    fun recentTokensKeepTheLastWindow() {
        val recent = RecentTokens(3)
        for (id in 1..5) {
            recent.add(id)
        }
        val destination = IntArray(3)

        val count = recent.copyInto(destination)

        assertEquals(3, count)
        assertEquals(setOf(3, 4, 5), destination.toSet())
    }

    @Test
    fun anEmptyWindowKeepsNothing() {
        val recent = RecentTokens(0)
        recent.add(7)

        assertEquals(0, recent.size)
        assertEquals(0, recent.copyInto(IntArray(0)))
    }
}
