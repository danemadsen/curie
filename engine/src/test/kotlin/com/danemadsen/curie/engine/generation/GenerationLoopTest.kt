package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.Test
import java.util.SplittableRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The slot loop against liquidonnx's generate_ids, with a scripted decoder (each text slot's
 * logits peak at the next scripted token) and a scripted depth decoder (each frame's logits peak at
 * the next scripted frame's codes), both sampled greedily.
 */
class GenerationLoopTest {
    private val imEnd = Lfm3Omni.IM_END_TOKEN
    private val textEnd = Lfm3Omni.TEXT_END_TOKEN
    private val audioStart = Lfm3Omni.AUDIO_START_TOKEN
    private val mixedStart = Lfm3Omni.MIXED_START_TOKEN
    private val audioOut = Lfm3Omni.AUDIO_OUT_TOKEN
    private val endOfSpeech = IntArray(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }

    private class ScriptedDecoder(
        textTokens: List<Int>,
        private val room: Int = Int.MAX_VALUE,
        private val terminatedFeed: Int = -1,
    ) : SlotDecoder {
        private val script = ArrayDeque(textTokens)
        val fed = ArrayList<GeneratedSlot>()

        override val hasRoom: Boolean
            get() = fed.size < room

        override fun copyLogits(destination: FloatArray) {
            destination.fill(0f)
            destination[script.removeFirst()] = 1f
        }

        override fun copyHidden(destination: FloatArray) {
            destination.fill(0f)
        }

        override fun feed(token: Int, codes: IntArray?): Boolean {
            if (fed.size == terminatedFeed) {
                return false
            }
            fed.add(GeneratedSlot(token, codes?.copyOf()))
            return true
        }
    }

    private class ScriptedDepth(frames: List<IntArray>) : DepthLogits {
        private val script = ArrayDeque(frames)
        private var frame = IntArray(Lfm3Omni.NUM_CODEBOOKS)

        override fun compute(hidden: FloatArray, codes: IntArray, masked: BooleanArray, logits: FloatArray) {
            if (masked.all { isMasked -> isMasked }) {
                frame = script.removeFirst()
            }
            logits.fill(0f)
            for (codebook in 0 until Lfm3Omni.NUM_CODEBOOKS) {
                logits[codebook * Lfm3Omni.CODEBOOK_SIZE + frame[codebook]] = 1f
            }
        }
    }

    private class RecordingListener : SlotListener {
        val events = ArrayList<String>()

        override fun onToken(token: Int) {
            events.add("token $token")
        }

        override fun onAudioFrame(codes: IntArray) {
            events.add("frame ${codes[0]}")
        }

        override fun onAudioEnd() {
            events.add("end of speech")
        }
    }

    private class Run(
        val outcome: LoopOutcome,
        val decoder: ScriptedDecoder,
        val listener: RecordingListener,
    )

    private fun frame(code: Int) = IntArray(Lfm3Omni.NUM_CODEBOOKS) { code }

    private fun run(
        startMode: SlotMode,
        maxSlots: Int,
        text: List<Int>,
        frames: List<IntArray> = emptyList(),
        decoder: ScriptedDecoder = ScriptedDecoder(text),
        cancelAfterSlots: Int = Int.MAX_VALUE,
    ): Run {
        val random = SplittableRandom(1)
        val textSampler = TextSampler(TextSampling(), IntArray(0), random)
        val audioSampling = AudioSampling(temperature = 0f, repetitionPenalty = 1f)
        val maskGit = MaskGit(ScriptedDepth(frames), MaskGitOrders.IDENTITY, audioSampling, random)
        val listener = RecordingListener()
        var checks = 0
        val loop = GenerationLoop(decoder, textSampler, maskGit, listener) {
            checks++
            checks >= cancelAfterSlots
        }
        val outcome = loop.run(startMode, maxSlots)
        return Run(outcome, decoder, listener)
    }

    private fun tokensOf(slots: List<GeneratedSlot>): List<Int> = slots.map { slot -> slot.token }

    @Test
    fun textEndsWithImEndWhichIsNotFed() {
        val result = run(SlotMode.TEXT, 10, listOf(5, 6, imEnd))

        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
        assertEquals(listOf(5, 6, imEnd), tokensOf(result.outcome.slots))
        assertEquals(listOf(5, 6), tokensOf(result.decoder.fed))
        assertEquals(listOf("token 5", "token 6", "token $imEnd"), result.listener.events)
    }

    @Test
    fun textEndOutsideAnInterleavedBlockEndsTheTurn() {
        val result = run(SlotMode.TEXT, 10, listOf(5, textEnd, 6))

        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
        assertEquals(listOf(5, textEnd), tokensOf(result.outcome.slots))
        assertEquals(listOf(5), tokensOf(result.decoder.fed))
    }

    @Test
    fun audioStartSpeaksUntilTheEndOfSpeechFrame() {
        val frames = listOf(frame(7), frame(8), endOfSpeech)
        val result = run(SlotMode.TEXT, 10, listOf(audioStart, imEnd), frames)

        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
        assertEquals(listOf(audioStart, audioOut, audioOut, audioOut, imEnd), tokensOf(result.outcome.slots))
        assertEquals(SlotMode.TEXT, result.outcome.endMode)

        // Every frame is fed with its codes, the end-of-speech frame too
        val fed = result.decoder.fed
        assertEquals(4, fed.size)
        assertNull(fed[0].codes)
        assertContentEquals(frame(7), fed[1].codes)
        assertContentEquals(frame(8), fed[2].codes)
        assertContentEquals(endOfSpeech, fed[3].codes)

        val expectedEvents = listOf("token $audioStart", "frame 7", "frame 8", "end of speech", "token $imEnd")
        assertEquals(expectedEvents, result.listener.events)
    }

    @Test
    fun speechModeStartsWithFrames() {
        val frames = listOf(frame(1), endOfSpeech)
        val result = run(SlotMode.AUDIO, 10, listOf(imEnd), frames)

        assertEquals(listOf(audioOut, audioOut, imEnd), tokensOf(result.outcome.slots))
        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
    }

    @Test
    fun interleavedBlocksAlternateSixTokensAndTwelveFrames() {
        val text = listOf(1, 2, 3, 4, 5, 6, 7, 8, textEnd, imEnd)
        val frames = List(12) { index -> frame(100 + index) } + listOf(frame(200), frame(201), frame(202), endOfSpeech)
        val result = run(SlotMode.INTERLEAVED, 100, text, frames)

        val expected = listOf(1, 2, 3, 4, 5, 6) +
            List(12) { audioOut } +
            listOf(7, 8, textEnd) +
            List(4) { audioOut } +
            listOf(imEnd)
        assertEquals(expected, tokensOf(result.outcome.slots))
        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
        assertEquals(SlotMode.TEXT, result.outcome.endMode)
        assertEquals(expected.size - 1, result.decoder.fed.size)
    }

    @Test
    fun textEndInsideABlockKeepsSpeakingUntilTheEndOfSpeech() {
        // The text ends early: the block speaks on, past its 12 frames, until the end-of-speech frame
        val text = listOf(1, textEnd, imEnd)
        val frames = List(14) { index -> frame(index) } + listOf(endOfSpeech)
        val result = run(SlotMode.INTERLEAVED, 100, text, frames)

        val expected = listOf(1, textEnd) + List(15) { audioOut } + listOf(imEnd)
        assertEquals(expected, tokensOf(result.outcome.slots))
    }

    @Test
    fun modeMarkersInsideABlockArePlainText() {
        val text = listOf(audioStart, mixedStart, 3, 4, 5, 6, imEnd)
        val frames = List(12) { index -> frame(index) }
        val result = run(SlotMode.INTERLEAVED, 100, text, frames)

        // Neither marker switched the mode or restarted the count: frames follow the sixth token
        val expected = listOf(audioStart, mixedStart, 3, 4, 5, 6) + List(12) { audioOut } + listOf(imEnd)
        assertEquals(expected, tokensOf(result.outcome.slots))
    }

    @Test
    fun imEndInsideABlockEndsTheTurn() {
        val result = run(SlotMode.INTERLEAVED, 100, listOf(1, imEnd))

        assertEquals(FinishReason.END_OF_TURN, result.outcome.finishReason)
        assertEquals(SlotMode.INTERLEAVED, result.outcome.endMode)
        assertFalse(result.outcome.textDone)
    }

    @Test
    fun mixedStartOpensABlockWhoseCountStartsAfterIt() {
        val text = listOf(mixedStart, 1, 2, 3, 4, 5, 6, textEnd, imEnd)
        val frames = List(12) { index -> frame(index) } + listOf(endOfSpeech)
        val result = run(SlotMode.TEXT, 100, text, frames)

        val expected = listOf(mixedStart, 1, 2, 3, 4, 5, 6) +
            List(12) { audioOut } +
            listOf(textEnd, audioOut, imEnd)
        assertEquals(expected, tokensOf(result.outcome.slots))
    }

    @Test
    fun anEndOfSpeechFrameInsideABlockResumesPlainText() {
        val text = listOf(1, 2, 3, 4, 5, 6, 7, 8, imEnd)
        val frames = listOf(frame(1), endOfSpeech)
        val result = run(SlotMode.INTERLEAVED, 100, text, frames)

        // After the end of speech the mode is TEXT: no more frames, whatever the count says
        val expected = listOf(1, 2, 3, 4, 5, 6, audioOut, audioOut, 7, 8, imEnd)
        assertEquals(expected, tokensOf(result.outcome.slots))
    }

    @Test
    fun theLastSlotAllowedIsSampledButNotFed() {
        val result = run(SlotMode.TEXT, 3, listOf(1, 2, 3, 4))

        assertEquals(FinishReason.MAX_SLOTS, result.outcome.finishReason)
        assertEquals(listOf(1, 2, 3), tokensOf(result.outcome.slots))
        assertEquals(listOf(1, 2), tokensOf(result.decoder.fed))
    }

    @Test
    fun noSlotsAllowedSamplesNothing() {
        val result = run(SlotMode.AUDIO, 0, emptyList())

        assertTrue(result.outcome.slots.isEmpty())
        assertEquals(FinishReason.MAX_SLOTS, result.outcome.finishReason)
        assertEquals(SlotMode.AUDIO, result.outcome.endMode)
    }

    @Test
    fun cancelStopsBetweenSlots() {
        val result = run(SlotMode.TEXT, 10, listOf(1, 2, 3, 4), cancelAfterSlots = 2)

        assertEquals(FinishReason.CANCELLED, result.outcome.finishReason)
        assertEquals(listOf(1, 2), tokensOf(result.outcome.slots))
        assertEquals(listOf(1), tokensOf(result.decoder.fed))
    }

    @Test
    fun aTerminatedFeedCancels() {
        val text = listOf(1, 2, 3)
        val decoder = ScriptedDecoder(text, terminatedFeed = 1)
        val result = run(SlotMode.TEXT, 10, text, decoder = decoder)

        assertEquals(FinishReason.CANCELLED, result.outcome.finishReason)
        assertEquals(listOf(1, 2), tokensOf(result.outcome.slots))
        assertEquals(listOf(1), tokensOf(result.decoder.fed))
    }

    @Test
    fun aFullCacheStops() {
        val text = listOf(1, 2, 3)
        val decoder = ScriptedDecoder(text, room = 1)
        val result = run(SlotMode.TEXT, 10, text, decoder = decoder)

        assertEquals(FinishReason.CONTEXT_FULL, result.outcome.finishReason)
        assertEquals(listOf(1, 2), tokensOf(result.outcome.slots))
    }

    @Test
    fun aCutBlockReportsItsState() {
        val text = listOf(1, 2, 3, 4, 5, 6)
        val frames = listOf(frame(1), frame(2))
        val result = run(SlotMode.INTERLEAVED, 8, text, frames)

        assertEquals(FinishReason.MAX_SLOTS, result.outcome.finishReason)
        assertEquals(SlotMode.INTERLEAVED, result.outcome.endMode)
        assertFalse(result.outcome.textDone)
    }
}
