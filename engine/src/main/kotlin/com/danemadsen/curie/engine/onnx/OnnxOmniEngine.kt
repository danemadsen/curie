package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OrtLoraAdapter
import ai.onnxruntime.OrtSession
import com.danemadsen.curie.engine.Conversation
import com.danemadsen.curie.engine.EngineOptions
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.ModelFiles
import com.danemadsen.curie.engine.OmniEngine
import com.danemadsen.curie.engine.SpeechDecoder
import com.danemadsen.curie.engine.generation.MarkupTokens
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import java.io.File
import java.io.FileNotFoundException

/** LFM3-Omni-2B on ONNX Runtime: the [OmniEngine] of a model folder. */
object OnnxOmniEngine {
    /**
     * Loads the model from [directory], which holds every [ModelFiles] file flat: the downloads
     * and the bundled patched graphs (and, with [EngineOptions.useLora], their LoRA adapters).
     * Takes seconds and about 3 GB of memory: call it once, off the main thread.
     *
     * Threading: one [com.danemadsen.curie.engine.Conversation.generate] or prefill runs at a time
     * per conversation, and since all conversations share the engine's sessions and thread pool,
     * the caller serialises generations across conversations too. Cancel may come from any thread;
     * close from any thread but a running generation's listener: closing stops what runs and waits
     * for it.
     *
     * @throws FileNotFoundException naming every missing file
     */
    fun load(directory: File, options: EngineOptions = EngineOptions()): OmniEngine {
        val files = ModelFolder(directory, options.useLora)
        files.requireComplete()
        val runtime = OrtRuntime.acquire(options.threads)
        val tokenizer = BpeTokenizer.load(files.file(ModelFiles.TOKENIZER))
        val markup = MarkupTokens.of(tokenizer)

        val opened = ArrayList<AutoCloseable>()

        fun openSession(name: String, quietGraph: Boolean = false): OrtSession {
            val file = files.file(name)
            val session = runtime.createSession(file, quietGraph)
            opened.add(session)
            return session
        }

        fun openAdapter(name: String): OrtLoraAdapter {
            val file = files.file(name)
            val adapter = OrtLoraAdapter.create(file.path)
            opened.add(adapter)
            return adapter
        }

        try {
            val decoder = openSession(ModelFiles.DECODER, quietGraph = true)
            val depth = openSession(ModelFiles.DEPTH_DECODER, quietGraph = true)
            val vision = openSession(ModelFiles.VISION_ENCODER)
            val audio = openSession(ModelFiles.AUDIO_ENCODER)
            val codec = openSession(ModelFiles.CODEC_DECODER)
            var decoderAdapter: OrtLoraAdapter? = null
            var depthAdapter: OrtLoraAdapter? = null
            if (options.useLora) {
                decoderAdapter = openAdapter(ModelFiles.DECODER_ADAPTER)
                depthAdapter = openAdapter(ModelFiles.DEPTH_DECODER_ADAPTER)
            }

            val visionEncoder = VisionEncoder(runtime.environment, vision)
            val audioEncoder = AudioEncoder(runtime.environment, audio)
            val mediaFeatures = MediaFeatures(visionEncoder, audioEncoder)
            val resources = EngineResources(
                runtime = runtime,
                guard = SessionGuard(),
                decoderSession = decoder,
                depthSession = depth,
                decoderAdapter = decoderAdapter,
                depthAdapter = depthAdapter,
                tokenizer = tokenizer,
                markup = markup,
                mediaFeatures = mediaFeatures,
                cacheMemory = options.cacheMemory,
            )
            val sessions = listOf(decoder, depth, vision, audio, codec)
            val adapters = listOfNotNull(decoderAdapter, depthAdapter)
            return OnnxEngine(resources, codec, sessions, adapters)
        } catch (error: Throwable) {
            for (resource in opened.asReversed()) {
                resource.close()
            }
            throw error
        }
    }
}

/** The files of a model folder, as [OnnxOmniEngine.load] needs them. */
internal class ModelFolder(private val directory: File, useLora: Boolean) {
    private val required: List<String> = buildList {
        for (download in ModelFiles.DOWNLOADS) {
            add(download.fileName)
        }
        add(ModelFiles.DECODER)
        add(ModelFiles.DEPTH_DECODER)
        if (useLora) {
            add(ModelFiles.DECODER_ADAPTER)
            add(ModelFiles.DEPTH_DECODER_ADAPTER)
        }
    }

    fun file(name: String): File = File(directory, name)

    /** @throws FileNotFoundException naming every missing file */
    fun requireComplete() {
        val missing = required.filter { name -> !file(name).isFile }
        if (missing.isNotEmpty()) {
            throw FileNotFoundException("${directory.path} is missing ${missing.joinToString(", ")}")
        }
    }
}

/**
 * The loaded engine. Conversations share its sessions, each with its own cache; run one
 * generation at a time across all of them (the sessions' thread pool is shared, and so is the
 * media feature cache).
 *
 * Every run on the sessions (a conversation's, a speech decoder's) happens inside the
 * [SessionGuard]: [close] cancels the conversations, waits for the runs in flight to return, then
 * closes every conversation still open, the sessions and the LoRA adapters, in that order. The
 * generation in flight returns [com.danemadsen.curie.engine.generation.FinishReason.CANCELLED];
 * the calls that had not started by then or come later (a conversation's generate, prefill or
 * reset, [newConversation], [newSpeechDecoder], a speech decoder's push or finish) throw
 * [IllegalStateException]; cancelling and closing again do nothing.
 */
internal class OnnxEngine(
    /** The shared sessions and caches (the tests reach the encoders through it). */
    internal val resources: EngineResources,
    private val codecSession: OrtSession,
    private val sessions: List<OrtSession>,
    private val adapters: List<OrtLoraAdapter>,
) : OmniEngine {
    private val conversations = LinkedHashSet<OnnxConversation>()

    override fun newConversation(capacity: Int): Conversation {
        require(capacity > 0) { "the capacity must be positive, got $capacity" }
        return resources.guard.whileOpen {
            val conversation = OnnxConversation(resources, capacity, ::forget)
            synchronized(conversations) {
                conversations.add(conversation)
            }
            conversation
        }
    }

    override fun newSpeechDecoder(): SpeechDecoder =
        newSpeechDecoder(StreamingSpeechDecoder.DEFAULT_CHUNK_FRAMES, Lfm3Omni.CODEC_CONTEXT_FRAMES)

    /** A speech decoder with other chunks or context (the tests measure them). */
    internal fun newSpeechDecoder(chunkFrames: Int, contextFrames: Int): StreamingSpeechDecoder =
        resources.guard.whileOpen {
            val environment = resources.runtime.environment
            StreamingSpeechDecoder(environment, codecSession, resources.guard, chunkFrames, contextFrames)
        }

    override fun close() {
        resources.guard.close(stopRuns = ::cancelConversations, release = ::release)
    }

    private fun openConversations(): List<OnnxConversation> =
        synchronized(conversations) {
            conversations.toList()
        }

    private fun cancelConversations() {
        for (conversation in openConversations()) {
            conversation.cancel()
        }
    }

    /** Frees everything once no run is left: the conversations first, the adapters last. */
    private fun release() {
        for (conversation in openConversations()) {
            conversation.close()
        }
        resources.mediaFeatures.clear()
        for (session in sessions) {
            session.close()
        }
        for (adapter in adapters) {
            adapter.close()
        }
    }

    private fun forget(conversation: OnnxConversation) {
        synchronized(conversations) {
            conversations.remove(conversation)
        }
    }
}
