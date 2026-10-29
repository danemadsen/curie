package com.danemadsen.curie.engine

import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.prompt.Prompt
import java.nio.ByteBuffer

/**
 * LFM3-Omni-2B on ONNX Runtime with the Curie voice LoRA. Every method blocks: call them from a
 * background thread, one generation at a time.
 *
 * Closing cancels the running generations, waits for them and for any speech decoding in flight,
 * then frees the model. The generation in flight returns FinishReason.CANCELLED; calls that had not
 * started by then or come later (a conversation's generate, prefill or reset, newConversation,
 * newSpeechDecoder, a speech decoder's push or finish) throw IllegalStateException; cancel and
 * close again do nothing. Do not close it from a generation's listener.
 */
interface OmniEngine : AutoCloseable {
    /**
     * A KV cache of [capacity] positions. Conversations are independent: each holds its own
     * cache, so a short-lived one (text to speech) does not evict a chat's.
     */
    fun newConversation(capacity: Int): Conversation

    /** A streaming speech decoder: codes in, 24 kHz samples out. */
    fun newSpeechDecoder(): SpeechDecoder
}

data class EngineOptions(
    /**
     * Threads of the shared intra-op pool; 0 picks min(4, available processors), the performance
     * cores of most phones.
     */
    val threads: Int = 0,

    /** Applies the bundled Curie voice LoRA. */
    val useLora: Boolean = true,

    /**
     * Where conversations keep their KV caches, most of their memory (44 KB a position). The
     * default's direct buffers are Java heap on Android, which cannot hold them there.
     */
    val cacheMemory: CacheMemory = CacheMemory.DIRECT,
)

/** Allocates the native memory a conversation's KV caches live in, which ONNX Runtime writes in place. */
fun interface CacheMemory {
    /** [bytes] of zeroed memory, from any thread; the conversation closes the block when it closes. */
    fun allocate(bytes: Int): MemoryBlock

    companion object {
        /** ByteBuffer.allocateDirect: the garbage collector frees it. */
        val DIRECT = CacheMemory { bytes ->
            val buffer = ByteBuffer.allocateDirect(bytes)
            MemoryBlock.collected(buffer)
        }
    }
}

/** A direct buffer over native memory that stays valid until [close]. */
interface MemoryBlock : AutoCloseable {
    val buffer: ByteBuffer

    companion object {
        /** A block the garbage collector frees: closing it does nothing. */
        fun collected(buffer: ByteBuffer): MemoryBlock = object : MemoryBlock {
            override val buffer: ByteBuffer = buffer

            override fun close() = Unit
        }
    }
}

/** The prompt (plus the answer) would not fit the conversation's cache. */
class ContextOverflowException(val required: Int, val capacity: Int) :
    IllegalArgumentException("$required positions exceed the context capacity of $capacity")

/**
 * One generate, prefill or reset runs at a time: a second call waits for the first. close() stops
 * the calls made before it as cancel() does, waits for the running one to return, then frees the
 * cache (calls still waiting then throw IllegalStateException). cancel() may come from any thread;
 * close() from any thread but the running call's listener (there it throws IllegalStateException).
 */
interface Conversation : AutoCloseable {
    val capacity: Int

    /** Positions the cache holds. */
    val cachedLength: Int

    /**
     * Brings the cache to [prompt], keeping the longest cached prefix it can rewind to, then
     * generates the answer. Throws [ContextOverflowException] when the prompt plus
     * [GenerationRequest.maxNewSlots] exceeds [capacity].
     */
    fun generate(prompt: Prompt, request: GenerationRequest, listener: GenerationListener): GenerationResult

    /**
     * Brings the cache to [prompt] without generating, e.g. to warm up the system prompt while the
     * user types. Returns false when [cancel] stopped it first.
     */
    fun prefill(prompt: Prompt, listener: GenerationListener): Boolean

    /**
     * From any thread: stops every [generate] and [prefill] called before it, the running one at its
     * next step and those still waiting to run before they start (they return CANCELLED, or false);
     * a call made after it runs normally.
     */
    fun cancel()

    /** Forgets the cache. */
    fun reset()
}

/**
 * The codec decoder and inverse STFT, streamed: decoding the frames in chunks yields the samples
 * the whole utterance would (the codec is causal; see [Lfm3Omni.CODEC_CONTEXT_FRAMES]).
 */
interface SpeechDecoder {
    /** Adds a speech frame (12 codes below the end-of-speech code); returns the samples it completes. */
    fun push(codes: IntArray): FloatArray

    /** Decodes what is pending; returns the remaining samples. The decoder can start a new utterance. */
    fun finish(): FloatArray
}
