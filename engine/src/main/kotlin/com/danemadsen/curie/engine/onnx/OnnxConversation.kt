package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoraAdapter
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.CacheMemory
import com.danemadsen.curie.engine.ContextOverflowException
import com.danemadsen.curie.engine.Conversation
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GeneratedAnswer
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationLoop
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.generation.GenerationStart
import com.danemadsen.curie.engine.generation.LoopOutcome
import com.danemadsen.curie.engine.generation.MaskGit
import com.danemadsen.curie.engine.generation.MaskGitOrders
import com.danemadsen.curie.engine.generation.MarkupTokens
import com.danemadsen.curie.engine.generation.SlotDecoder
import com.danemadsen.curie.engine.generation.SlotListener
import com.danemadsen.curie.engine.generation.SlotMode
import com.danemadsen.curie.engine.generation.TextSampler
import com.danemadsen.curie.engine.generation.VisibleTextStream
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.MediaInput
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.SlotSource
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A conversation's KV cache on the shared decoder session, with the slots it holds (token ids and
 * their sources) and the snapshots it can rewind to.
 *
 * A prompt reuses the longest prefix of the cache it can: the cache is kept whole when it is a
 * prefix of the prompt (a new turn appends only), or rewinds to the latest snapshot within the
 * common prefix (an edited message, a regenerated answer). Snapshots are taken at the prompt's
 * checkpoints that a prefill crosses and at the end of every prefill ([CachePlan], [SnapshotStore]).
 *
 * One [generate], [prefill] or [reset] runs at a time: a second call waits for the first. [cancel]
 * stops every generate and prefill called before it, the running one at its next step and the
 * waiting ones before they start (a stop pressed while a message waits for the warm-up prefill
 * stops both); calls made after it run normally. A cancelled or failed run leaves the cache
 * consistent: it holds exactly the slots fed before. [cancel] may come from any thread, [close]
 * from any thread but the running call's listener: it stops the calls as [cancel] does, waits for
 * the running one to return, then frees the native memory.
 */
internal class OnnxConversation(
    private val engine: EngineResources,
    override val capacity: Int,
    private val onClose: (OnnxConversation) -> Unit,
) : Conversation {
    private val runOptions: ConversationRunOptions
    private val depth: DepthDecoder
    private val decoder: DecoderSession
    private val cachedTokens = IntArray(capacity)
    private val cachedSources = arrayOfNulls<SlotSource>(capacity)
    private val snapshots = SnapshotStore()

    /** Held by the running [generate], [prefill] or [reset]: [close] waits for it. */
    private val runLock = ReentrantLock()

    /** How many times [cancel] or [close] was called: a call stops once it grows past [cancelsWhenCalled]. */
    private val cancelCount = AtomicLong()

    /** [cancelCount] when the running call was made, before it waited for the run lock; only that call uses it. */
    private var cancelsWhenCalled = 0L

    @Volatile
    private var closed = false

    /** Whether [cancel] or [close] was called after the running call was made. */
    private val cancelRequested: Boolean
        get() = cancelCount.get() != cancelsWhenCalled

    init {
        val environment = engine.runtime.environment
        val created = ArrayList<AutoCloseable>()
        try {
            runOptions = ConversationRunOptions(engine.decoderAdapter, engine.depthAdapter)
            created.add(runOptions)
            depth = DepthDecoder(environment, engine.depthSession, runOptions.depth)
            created.add(depth)
            decoder = DecoderSession(environment, engine.decoderSession, capacity, engine.cacheMemory)
        } catch (error: Throwable) {
            for (resource in created.asReversed()) {
                resource.close()
            }
            throw error
        }
    }

    override val cachedLength: Int
        get() = decoder.length

    override fun generate(prompt: Prompt, request: GenerationRequest, listener: GenerationListener): GenerationResult {
        val random = request.seed?.let { seed -> SplittableRandom(seed) } ?: SplittableRandom()
        val orders = MaskGitOrders.random(random.split())
        return generate(prompt, request, listener, random, orders)
    }

    /** [generate] with the sampling [random] and the MaskGIT [orders] given (the golden tests). */
    internal fun generate(
        prompt: Prompt,
        request: GenerationRequest,
        listener: GenerationListener,
        random: SplittableRandom,
        orders: MaskGitOrders,
    ): GenerationResult {
        require(prompt.length > 0) { "the prompt is empty" }
        require(request.maxNewSlots >= 0) { "maxNewSlots must not be negative, got ${request.maxNewSlots}" }
        val lastPosition = prompt.length - 1
        val start = GenerationStart.of(request.mode, prompt.tokens[lastPosition], prompt.sources[lastPosition])
        val target = fedSequence(prompt, start.marker)
        checkCapacity(target.length + request.maxNewSlots)
        PromptChecks.validate(prompt)
        // The samplers check their settings: an invalid request fails before it waits or touches the cache
        val suppressed = if (request.allowToolCalls) {
            start.suppressed
        } else {
            start.suppressed + engine.markup.toolCallStart
        }
        val textSampler = TextSampler(request.textSampling, suppressed, random)
        val maskGit = MaskGit(depth, orders, request.audioSampling, random)

        return whileRunning {
            val prefilled = bringCacheTo(target, prompt, listener)
            val outcome = if (prefilled) {
                runSlots(start.mode, request.maxNewSlots, textSampler, maskGit, listener)
            } else {
                LoopOutcome(emptyList(), FinishReason.CANCELLED, start.mode, textDone = false)
            }

            val answer = GeneratedAnswer.of(start.marker, outcome, engine.tokenizer, engine.markup)
            GenerationResult(answer.content, answer.text, answer.toolCalls, answer.audioFrames, outcome.finishReason)
        }
    }

    override fun prefill(prompt: Prompt, listener: GenerationListener): Boolean {
        require(prompt.length > 0) { "the prompt is empty" }
        checkCapacity(prompt.length)
        PromptChecks.validate(prompt)
        val target = SlotSequence(prompt.tokens, prompt.sources, prompt.length)

        return whileRunning {
            bringCacheTo(target, prompt, listener)
        }
    }

    override fun cancel() {
        synchronized(runOptions) {
            if (closed) {
                return
            }
            cancelCount.incrementAndGet()
            runOptions.terminate()
        }
    }

    override fun reset() {
        whileRunning {
            decoder.reset()
            snapshots.clear()
        }
    }

    /**
     * Stops the calls made before it (as [cancel] does), waits for the running one to return, then
     * frees the cache. The calls still waiting then throw [IllegalStateException].
     *
     * @throws IllegalStateException when called from the running call's listener, which would wait for itself
     */
    override fun close() {
        check(!runLock.isHeldByCurrentThread) {
            "close the conversation after its generate or prefill returns, not from its listener"
        }
        synchronized(runOptions) {
            if (closed) {
                return
            }
            closed = true
            cancelCount.incrementAndGet()
            runOptions.terminate()
        }
        runLock.withLock {
            runOptions.close()
            depth.close()
            decoder.close()
        }
        onClose(this)
    }

    /**
     * Runs [block] as the conversation's one run: on the engine's open sessions, holding the run
     * lock that [close] waits for. A [cancel] from the moment of this call on stops it, even one
     * that comes while it waits for the run before it.
     */
    private fun <T> whileRunning(block: () -> T): T {
        check(!runLock.isHeldByCurrentThread) { "one run at a time: not from the running call's listener" }
        val cancelsAtCall = cancelCount.get()
        return engine.guard.whileOpen {
            runLock.withLock {
                beginRun(cancelsAtCall)
                block()
            }
        }
    }

    /** Starts the run of a call made when [cancelCount] was [cancelsAtCall]; renews terminated run options. */
    private fun beginRun(cancelsAtCall: Long) {
        synchronized(runOptions) {
            check(!closed) { "the conversation is closed" }
            // The engine started closing while this call waited: refused, as the calls after it are
            engine.guard.checkOpen()
            runOptions.renewIfTerminated()
            cancelsWhenCalled = cancelsAtCall
        }
    }

    private fun checkCapacity(required: Int) {
        if (required > capacity) {
            throw ContextOverflowException(required, capacity)
        }
    }

    /** The prompt, then the mode's [marker] if there is one. */
    private fun fedSequence(prompt: Prompt, marker: Int?): SlotSequence {
        if (marker == null) {
            return SlotSequence(prompt.tokens, prompt.sources, prompt.length)
        }
        val tokens = prompt.tokens + marker
        val sources = prompt.sources.copyOf(prompt.length + 1)
        return SlotSequence(tokens, sources, tokens.size)
    }

    /**
     * Brings the cache to [target], whose positions up to the prompt's end come from [prompt]:
     * keeps or rewinds ([CachePlan]), encodes the media of the positions to feed, and feeds them in
     * chunks, taking snapshots at checkpoints and at the end. Returns false when cancelled.
     */
    private fun bringCacheTo(target: SlotSequence, prompt: Prompt, listener: GenerationListener): Boolean {
        // Cancelled while it waited for the run before it: the cache stays as that run left it
        if (cancelRequested) {
            return false
        }

        val cached = SlotSequence(cachedTokens, cachedSources, decoder.length)
        val plan = CachePlan.of(cached, decoder.outputsValid, snapshots, target)
        if (plan is CachePlan.Rewind) {
            rewind(plan.snapshot)
        }
        val from = plan.feedFrom
        val to = target.length
        if (from == to) {
            return true
        }

        val mediaToFeed = PromptChecks.mediaStreams(target, from, to, prompt.media)
        var encodeRows = 0
        for ((input, stream) in mediaToFeed) {
            if (!engine.mediaFeatures.isCached(input, stream)) {
                encodeRows += MediaFeatures.expectedRows(input, stream)
            }
        }
        val progress = PrefillProgress(listener, total = encodeRows + (to - from))

        val features = HashMap<FeatureKey, FloatArray>()
        for ((input, stream) in mediaToFeed) {
            if (cancelRequested) {
                return false
            }
            val encoded = encodeOrCancel(input, stream, progress) ?: return false
            features[FeatureKey(input.key, stream)] = encoded
        }

        for (chunk in PrefillChunks.of(from, to, prompt.checkpoints)) {
            if (cancelRequested) {
                return false
            }
            val feed = chunkFeed(target, chunk, features)
            if (!runDecoder(feed)) {
                return false
            }
            target.tokens.copyInto(cachedTokens, chunk.start, chunk.start, chunk.end)
            target.sources.copyInto(cachedSources, chunk.start, chunk.start, chunk.end)
            progress.advance(chunk.size)

            val isEnd = chunk.end == to
            if (chunk.endsAtCheckpoint || isEnd) {
                val snapshot = decoder.snapshot(withOutputs = isEnd)
                snapshots.add(snapshot)
            }
        }
        return true
    }

    private fun rewind(snapshot: CacheSnapshot?) {
        if (snapshot != null) {
            decoder.restore(snapshot)
        } else {
            decoder.reset()
        }
        snapshots.dropAfter(decoder.length)
    }

    private fun encodeOrCancel(input: MediaInput, stream: FeatureStream, progress: PrefillProgress): FloatArray? =
        try {
            engine.mediaFeatures.features(input, stream, runOptions.encoder) { rows -> progress.advance(rows) }
        } catch (error: OrtException) {
            if (!cancelRequested) {
                throw error
            }
            null
        }

    /** Runs [feed]; returns false when a cancel terminated the run (the cache is unchanged then). */
    private fun runDecoder(feed: DecoderFeed): Boolean =
        try {
            decoder.run(feed, runOptions.decoder)
            true
        } catch (error: OrtException) {
            if (!cancelRequested) {
                throw error
            }
            false
        }

    /** The decoder input of [chunk]: its tokens and, in position order, its placeholders' rows. */
    private fun chunkFeed(
        target: SlotSequence,
        chunk: PrefillChunk,
        features: Map<FeatureKey, FloatArray>,
    ): DecoderFeed {
        val image = FeatureRows()
        val video = FeatureRows()
        val audio = FeatureRows()
        val codes = ArrayList<Int>()
        for (position in chunk.start until chunk.end) {
            when (val source = target.sources[position]) {
                is SlotSource.MediaRow -> {
                    val key = FeatureKey(source.mediaKey, source.stream)
                    val rows = checkNotNull(features[key]) { "no features for ${source.mediaKey}" }
                    val stream = when (source.stream) {
                        FeatureStream.IMAGE -> image
                        FeatureStream.VIDEO -> video
                        FeatureStream.AUDIO -> audio
                    }
                    stream.add(rows, source.row)
                }
                is SlotSource.AudioOutCodes -> codes.addAll(source.codes.toList())
                null -> Unit
            }
        }
        val tokens = target.tokens.copyOfRange(chunk.start, chunk.end)
        return DecoderFeed(tokens, image.toArray(), video.toArray(), audio.toArray(), codes.toIntArray())
    }

    private fun runSlots(
        startMode: SlotMode,
        maxSlots: Int,
        textSampler: TextSampler,
        maskGit: MaskGit,
        listener: GenerationListener,
    ): LoopOutcome {
        val textStream = VisibleTextStream(engine.tokenizer, engine.markup, listener::onText)
        val slotListener = object : SlotListener {
            override fun onToken(token: Int) = textStream.push(token)

            override fun onAudioFrame(codes: IntArray) = listener.onAudioFrame(codes)

            override fun onAudioEnd() = listener.onAudioEnd()
        }

        val loop = GenerationLoop(CacheSlots(), textSampler, maskGit, slotListener) { cancelRequested }
        val outcome = loop.run(startMode, maxSlots)
        textStream.finish()
        return outcome
    }

    /** The cache as the generation loop feeds it, slot by slot. */
    private inner class CacheSlots : SlotDecoder {
        override val hasRoom: Boolean
            get() = decoder.hasRoom

        override fun copyLogits(destination: FloatArray) = decoder.copyLogits(destination)

        override fun copyHidden(destination: FloatArray) = decoder.copyHidden(destination)

        override fun feed(token: Int, codes: IntArray?): Boolean {
            val position = decoder.length
            val frameCodes = codes ?: DecoderFeed.NO_CODES
            val tokens = intArrayOf(token)
            val feed = DecoderFeed(tokens, audioOutCodes = frameCodes)
            if (!runDecoder(feed)) {
                return false
            }
            cachedTokens[position] = token
            cachedSources[position] = codes?.let { frame -> SlotSource.AudioOutCodes(frame.copyOf()) }
            return true
        }
    }
}

/** The shared parts of the engine that conversations run on. */
internal class EngineResources(
    val runtime: OrtRuntime,
    /** Every run on the sessions happens inside it, so that closing the engine waits for them. */
    val guard: SessionGuard,
    val decoderSession: OrtSession,
    val depthSession: OrtSession,
    val decoderAdapter: OrtLoraAdapter?,
    val depthAdapter: OrtLoraAdapter?,
    val tokenizer: BpeTokenizer,
    val markup: MarkupTokens,
    val mediaFeatures: MediaFeatures,
    val cacheMemory: CacheMemory,
)

private data class FeatureKey(val mediaKey: String, val stream: FeatureStream)

/** Feature rows gathered in position order for one decoder input. */
private class FeatureRows {
    private val sources = ArrayList<FloatArray>()
    private val rows = ArrayList<Int>()

    fun add(features: FloatArray, row: Int) {
        sources.add(features)
        rows.add(row)
    }

    fun toArray(): FloatArray {
        if (rows.isEmpty()) {
            return DecoderFeed.NO_FEATURES
        }
        val values = FloatArray(rows.size * Lfm3Omni.HIDDEN_SIZE)
        for (index in rows.indices) {
            val start = rows[index] * Lfm3Omni.HIDDEN_SIZE
            sources[index].copyInto(values, index * Lfm3Omni.HIDDEN_SIZE, start, start + Lfm3Omni.HIDDEN_SIZE)
        }
        return values
    }
}

/** Reports prefill progress: encoded media rows and fed positions out of their sum. */
private class PrefillProgress(private val listener: GenerationListener, private val total: Int) {
    private var done = 0

    init {
        if (total > 0) {
            listener.onPrefill(0, total)
        }
    }

    fun advance(count: Int) {
        done = minOf(total, done + count)
        listener.onPrefill(done, total)
    }
}

/**
 * The per-conversation run options: the decoder's and the depth decoder's carry their LoRA
 * adapters (each graph only its own: an adapter's inputs must exist in the graph it feeds), the
 * encoders' none. [terminate] aborts the decoder and encoder runs in flight (a depth decoder pass
 * is short: the loop stops after the frame); terminated options stay terminated, so the next run
 * starts with new ones ([renewIfTerminated]).
 */
private class ConversationRunOptions(
    private val decoderAdapter: OrtLoraAdapter?,
    private val depthAdapter: OrtLoraAdapter?,
) : AutoCloseable {
    var decoder: OrtSession.RunOptions = withAdapter(decoderAdapter)
        private set
    var depth: OrtSession.RunOptions = withAdapter(depthAdapter)
        private set
    var encoder: OrtSession.RunOptions = OrtSession.RunOptions()
        private set

    private var terminated = false

    fun terminate() {
        decoder.setTerminate(true)
        encoder.setTerminate(true)
        terminated = true
    }

    fun renewIfTerminated() {
        if (!terminated) {
            return
        }
        decoder.close()
        encoder.close()
        decoder = withAdapter(decoderAdapter)
        encoder = OrtSession.RunOptions()
        terminated = false
    }

    override fun close() {
        decoder.close()
        depth.close()
        encoder.close()
    }

    private fun withAdapter(adapter: OrtLoraAdapter?): OrtSession.RunOptions {
        val options = OrtSession.RunOptions()
        if (adapter != null) {
            try {
                options.addActiveLoraAdapter(adapter)
            } catch (error: OrtException) {
                options.close()
                throw error
            }
        }
        return options
    }
}
