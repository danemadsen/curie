package com.danemadsen.curie.engine.generation

import com.danemadsen.curie.engine.Lfm3Omni
import java.util.SplittableRandom

/** What the next slot is: a text token, a speech frame, or one of an interleaved answer's. */
internal enum class SlotMode {
    TEXT,
    AUDIO,
    INTERLEAVED,
}

/** The decoder as the generation loop drives it, after the prompt is in its cache. */
internal interface SlotDecoder {
    /** Whether the cache can take one more slot. */
    val hasRoom: Boolean

    /** Copies the logits that follow the last fed position ([Lfm3Omni.VOCAB_SIZE] values). */
    fun copyLogits(destination: FloatArray)

    /** Copies the hidden state of the last fed position ([Lfm3Omni.HIDDEN_SIZE] values). */
    fun copyHidden(destination: FloatArray)

    /**
     * Feeds one slot: a text token, or <|audio_out|> with its frame's [codes]. Returns false when
     * the run was cancelled, leaving the cache as it was.
     */
    fun feed(token: Int, codes: IntArray?): Boolean
}

/** The slots of a generation as they are sampled. */
internal interface SlotListener {
    fun onToken(token: Int) {}

    /** A speech frame other than the end-of-speech frame. */
    fun onAudioFrame(codes: IntArray) {}

    /** The end-of-speech frame. */
    fun onAudioEnd() {}
}

/** A sampled slot: a text token, or <|audio_out|> with its frame's 12 [codes]. */
internal class GeneratedSlot(val token: Int, val codes: IntArray?) {
    val isEndOfSpeech: Boolean
        get() = codes != null && codes[0] == Lfm3Omni.AUDIO_EOS_CODE
}

/**
 * How the slot loop ended: every sampled slot (all fed but the last), why it stopped, and the mode
 * machine's state after the last slot (which decides the slots that close a cut answer).
 */
internal class LoopOutcome(
    val slots: List<GeneratedSlot>,
    val finishReason: FinishReason,
    val endMode: SlotMode,
    val textDone: Boolean,
)

/**
 * Samples text tokens from the decoder's logits: [Sampler] with the text settings and history.
 *
 * @throws IllegalArgumentException when [sampling] cannot sample (see [SamplingSettings], [RecentTokens])
 */
internal class TextSampler(
    sampling: TextSampling,
    private val suppressed: IntArray,
    private val random: SplittableRandom,
) {
    private val settings = SamplingSettings.of(sampling)
    private val sampler = Sampler(Lfm3Omni.VOCAB_SIZE)
    private val scores = FloatArray(Lfm3Omni.VOCAB_SIZE)
    private val history = RecentTokens(sampling.repetitionWindow)
    private val historyIds = IntArray(history.capacity)

    fun next(decoder: SlotDecoder): Int {
        decoder.copyLogits(scores)
        val historySize = history.copyInto(historyIds)
        val token = sampler.sample(scores, settings, historyIds, historySize, suppressed, random)
        history.add(token)
        return token
    }
}

/**
 * The slot loop of liquidonnx's generate_ids (lfm3_audio/session.py), after the prompt:
 *
 * - AUDIO, or INTERLEAVED while speaking: a speech frame ([MaskGit]). An end-of-speech frame
 *   switches to TEXT; every frame is fed back as <|audio_out|> with its codes.
 * - otherwise a text token. <|text_end|> inside an interleaved block ends its text (the speech goes
 *   on until an end-of-speech frame); anywhere else it ends the turn, as <|im_end|> does
 *   everywhere. Outside a block <|audio_start|> switches to AUDIO and <|mixed_start|> opens an
 *   interleaved block; inside one these markers are plain text.
 * - an interleaved block alternates [Lfm3Omni.INTERLEAVED_TEXT_SLOTS] text slots and
 *   [Lfm3Omni.INTERLEAVED_AUDIO_SLOTS] frames until its text is done.
 *
 * The last slot is never fed: nothing needs its hidden state. Between slots the loop stops when
 * [isCancelled] says so or the cache is full.
 */
internal class GenerationLoop(
    private val decoder: SlotDecoder,
    private val textSampler: TextSampler,
    private val maskGit: MaskGit,
    private val listener: SlotListener,
    private val isCancelled: () -> Boolean,
) {
    private val hidden = FloatArray(Lfm3Omni.HIDDEN_SIZE)

    fun run(startMode: SlotMode, maxSlots: Int): LoopOutcome {
        require(maxSlots >= 0) { "maxSlots must not be negative, got $maxSlots" }
        val slots = ArrayList<GeneratedSlot>()
        var mode = startMode
        var slotsLeft = Lfm3Omni.INTERLEAVED_TEXT_SLOTS
        var speaking = false
        var textDone = false
        var finishReason = FinishReason.MAX_SLOTS

        for (step in 0 until maxSlots) {
            val interleaving = mode == SlotMode.INTERLEAVED
            var finished = false
            val slot: GeneratedSlot
            if (mode == SlotMode.AUDIO || (interleaving && speaking)) {
                decoder.copyHidden(hidden)
                val frame = maskGit.sampleFrame(hidden)
                slot = GeneratedSlot(Lfm3Omni.AUDIO_OUT_TOKEN, frame)
                if (slot.isEndOfSpeech) {
                    mode = SlotMode.TEXT
                    listener.onAudioEnd()
                } else {
                    listener.onAudioFrame(frame)
                }
            } else {
                val token = textSampler.next(decoder)
                slot = GeneratedSlot(token, null)
                listener.onToken(token)
                when {
                    token == Lfm3Omni.TEXT_END_TOKEN && interleaving -> {
                        textDone = true
                        speaking = true
                    }
                    token == Lfm3Omni.TEXT_END_TOKEN || token == Lfm3Omni.IM_END_TOKEN -> finished = true
                    // Inside an interleaved block the mode markers are plain text
                    interleaving -> Unit
                    token == Lfm3Omni.AUDIO_START_TOKEN -> mode = SlotMode.AUDIO
                    token == Lfm3Omni.MIXED_START_TOKEN -> {
                        mode = SlotMode.INTERLEAVED
                        slotsLeft = Lfm3Omni.INTERLEAVED_TEXT_SLOTS
                        speaking = false
                        textDone = false
                    }
                }
            }
            slots.add(slot)

            if (interleaving && !textDone) {
                slotsLeft--
                if (slotsLeft == 0) {
                    speaking = !speaking
                    slotsLeft = if (speaking) Lfm3Omni.INTERLEAVED_AUDIO_SLOTS else Lfm3Omni.INTERLEAVED_TEXT_SLOTS
                }
            }

            // The last slot needs no hidden state: it is not fed
            if (finished) {
                finishReason = FinishReason.END_OF_TURN
                break
            }
            if (step == maxSlots - 1) {
                finishReason = FinishReason.MAX_SLOTS
                break
            }
            if (isCancelled()) {
                finishReason = FinishReason.CANCELLED
                break
            }
            if (!decoder.hasRoom) {
                finishReason = FinishReason.CONTEXT_FULL
                break
            }
            if (!decoder.feed(slot.token, slot.codes)) {
                finishReason = FinishReason.CANCELLED
                break
            }
        }
        return LoopOutcome(slots, finishReason, mode, textDone)
    }
}
