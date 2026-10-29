package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.SlotSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The prefix and snapshot bookkeeping of a conversation's cache, without ONNX Runtime. */
class CachePlanTest {
    private fun sequence(vararg tokens: Int): SlotSequence {
        val sources = arrayOfNulls<SlotSource>(tokens.size)
        return SlotSequence(tokens, sources, tokens.size)
    }

    private fun snapshot(position: Int, withOutputs: Boolean = false): CacheSnapshot {
        val snapshot = CacheSnapshot(position, FloatArray(1))
        if (withOutputs) {
            snapshot.outputs = DecoderOutputs(FloatArray(1), FloatArray(1))
        }
        return snapshot
    }

    private fun store(vararg snapshots: CacheSnapshot): SnapshotStore {
        val store = SnapshotStore()
        for (snapshot in snapshots) {
            store.add(snapshot)
        }
        return store
    }

    @Test
    fun commonPrefixComparesTokensAndSources() {
        val image = SlotSource.MediaRow("photo", FeatureStream.IMAGE, 0)
        val otherImage = SlotSource.MediaRow("other", FeatureStream.IMAGE, 0)
        val first = SlotSequence(intArrayOf(1, 2, 3, 4), arrayOf(null, image, null, null), 4)
        val sameTokensOtherMedia = SlotSequence(intArrayOf(1, 2, 3, 4), arrayOf(null, otherImage, null, null), 4)
        val sameImage = SlotSource.MediaRow("photo", FeatureStream.IMAGE, 0)
        val sameMedia = SlotSequence(intArrayOf(1, 2, 3, 9), arrayOf(null, sameImage, null, null), 4)

        assertEquals(1, first.commonPrefix(sameTokensOtherMedia))
        assertEquals(3, first.commonPrefix(sameMedia))
    }

    @Test
    fun replayedFramesMatchByTheirCodes() {
        val codes = SlotSource.AudioOutCodes(IntArray(12) { 5 })
        val sameCodes = SlotSource.AudioOutCodes(IntArray(12) { 5 })
        val otherCodes = SlotSource.AudioOutCodes(IntArray(12) { 6 })
        val first = SlotSequence(intArrayOf(7, 7), arrayOf(codes, codes), 2)
        val second = SlotSequence(intArrayOf(7, 7), arrayOf(sameCodes, otherCodes), 2)

        assertEquals(1, first.commonPrefix(second))
    }

    @Test
    fun onlyTheGivenLengthCounts() {
        val cached = SlotSequence(intArrayOf(1, 2, 3, 4), arrayOfNulls(4), 2)

        assertEquals(2, cached.commonPrefix(sequence(1, 2, 3, 4)))
    }

    @Test
    fun aCacheThatIsAPrefixOfThePromptIsKept() {
        val plan = CachePlan.of(sequence(1, 2, 3), outputsValid = false, store(), sequence(1, 2, 3, 4, 5))

        assertIs<CachePlan.Append>(plan)
        assertEquals(3, plan.feedFrom)
    }

    @Test
    fun theSamePromptAgainFeedsNothingWhileTheOutputsAreValid() {
        val plan = CachePlan.of(sequence(1, 2, 3), outputsValid = true, store(), sequence(1, 2, 3))

        assertIs<CachePlan.Append>(plan)
        assertEquals(3, plan.feedFrom)
    }

    @Test
    fun theSamePromptWithoutValidOutputsRefeedsFromAnEarlierSnapshot() {
        val snapshots = store(snapshot(1), snapshot(3))

        val plan = CachePlan.of(sequence(1, 2, 3), outputsValid = false, snapshots, sequence(1, 2, 3))

        assertIs<CachePlan.Rewind>(plan)
        assertEquals(1, plan.feedFrom)
    }

    @Test
    fun aRegeneratedAnswerRewindsToTheEndOfThePromptWithItsOutputs() {
        val promptEnd = snapshot(3, withOutputs = true)
        val snapshots = store(snapshot(1), promptEnd)
        // The cache holds the prompt and the answer
        val cached = sequence(1, 2, 3, 10, 11, 12)

        val plan = CachePlan.of(cached, outputsValid = true, snapshots, sequence(1, 2, 3))

        assertIs<CachePlan.Rewind>(plan)
        assertSame(promptEnd, plan.snapshot)
        assertEquals(3, plan.feedFrom)
    }

    @Test
    fun anEditedMessageRewindsToTheLatestSnapshotBeforeTheEdit() {
        val snapshots = store(snapshot(2), snapshot(4), snapshot(7))
        val cached = sequence(1, 2, 3, 4, 5, 6, 7, 8)
        val edited = sequence(1, 2, 3, 4, 5, 9, 9, 9, 9)

        val plan = CachePlan.of(cached, outputsValid = true, snapshots, edited)

        assertIs<CachePlan.Rewind>(plan)
        assertEquals(4, plan.feedFrom)
    }

    @Test
    fun withoutAUsableSnapshotTheCacheStartsOver() {
        val snapshots = store(snapshot(5))
        val cached = sequence(1, 2, 3, 4, 5, 6)

        val plan = CachePlan.of(cached, outputsValid = true, snapshots, sequence(1, 2, 9))

        assertIs<CachePlan.Rewind>(plan)
        assertNull(plan.snapshot)
        assertEquals(0, plan.feedFrom)
    }

    @Test
    fun aSnapshotAtThePromptEndWithoutOutputsIsPassedOver() {
        val snapshots = store(snapshot(2), snapshot(3))
        val cached = sequence(1, 2, 3, 10)

        val plan = CachePlan.of(cached, outputsValid = true, snapshots, sequence(1, 2, 3))

        assertIs<CachePlan.Rewind>(plan)
        assertEquals(2, plan.feedFrom)
    }

    @Test
    fun anEmptyTargetIsRejected() {
        assertThrows<IllegalArgumentException> {
            CachePlan.of(sequence(1), outputsValid = true, store(), sequence())
        }
    }

    @Test
    fun theStoreKeepsTheLatestSnapshotsUpToItsLimit() {
        val store = SnapshotStore(limit = 3)
        for (position in 1..5) {
            store.add(snapshot(position * 10))
        }

        assertEquals(listOf(30, 40, 50), store.positions)
    }

    @Test
    fun aSnapshotReplacesOneAtTheSamePosition() {
        val store = store(snapshot(10), snapshot(20))
        val replacement = snapshot(20, withOutputs = true)

        store.add(replacement)

        assertEquals(listOf(10, 20), store.positions)
        assertSame(replacement, store.latestUsable(prefix = 20, targetLength = 20))
    }

    @Test
    fun onlyTheLatestPrefillEndKeepsItsOutputs() {
        val first = snapshot(10, withOutputs = true)
        val second = snapshot(20, withOutputs = true)
        val store = store(first, second)

        assertNull(first.outputs)
        assertNotNull(second.outputs)
        assertEquals(2, store.size)
    }

    @Test
    fun rewindingDropsTheSnapshotsAfterIt() {
        val store = store(snapshot(10), snapshot(20), snapshot(30))

        store.dropAfter(20)

        assertEquals(listOf(10, 20), store.positions)
    }

    @Test
    fun snapshotsMustNotGoBackwards() {
        val store = store(snapshot(10))

        assertThrows<IllegalArgumentException> { store.add(snapshot(5)) }
    }

    @Test
    fun chunksSplitAtCheckpointsAndTheSizeLimit() {
        val chunks = PrefillChunks.of(from = 3, to = 20, checkpoints = intArrayOf(0, 3, 5, 12, 20), maxChunk = 4)

        val expected = listOf(
            PrefillChunk(3, 5, endsAtCheckpoint = true),
            PrefillChunk(5, 9, endsAtCheckpoint = false),
            PrefillChunk(9, 12, endsAtCheckpoint = true),
            PrefillChunk(12, 16, endsAtCheckpoint = false),
            PrefillChunk(16, 20, endsAtCheckpoint = true),
        )
        assertEquals(expected, chunks)
    }

    @Test
    fun nothingToFeedMakesNoChunks() {
        assertEquals(emptyList(), PrefillChunks.of(from = 7, to = 7, checkpoints = intArrayOf(7)))
    }

    @Test
    fun theMarkerAfterThePromptIsFedWithItsLastChunk() {
        // The prompt plus a mode marker: the prompt's end would leave the marker alone in a run
        val chunks = PrefillChunks.of(from = 0, to = 6, checkpoints = intArrayOf(0, 3, 5))

        assertEquals(listOf(PrefillChunk(0, 3, true), PrefillChunk(3, 6, false)), chunks)
    }

    @Test
    fun aSizeCutNeverLeavesOnePositionForTheLastRun() {
        val chunks = PrefillChunks.of(from = 0, to = 9, checkpoints = intArrayOf(), maxChunk = 4)

        val expected = listOf(
            PrefillChunk(0, 4, endsAtCheckpoint = false),
            PrefillChunk(4, 7, endsAtCheckpoint = false),
            PrefillChunk(7, 9, endsAtCheckpoint = false),
        )
        assertEquals(expected, chunks)
    }

    @Test
    fun feedingOnePositionTakesOneRun() {
        val chunks = PrefillChunks.of(from = 4, to = 5, checkpoints = intArrayOf(4, 5))

        assertEquals(listOf(PrefillChunk(4, 5, endsAtCheckpoint = true)), chunks)
    }
}
