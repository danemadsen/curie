package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.SpeechDecoder
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.generation.AudioSampling
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.generation.MaskGitOrders
import com.danemadsen.curie.engine.media.LogMelSpectrogram
import com.danemadsen.curie.engine.media.VisionPreprocessor
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.Prompt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.SplittableRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine on the base model (EngineOptions(useLora = false): the patched graphs without
 * adapters) against liquidonnx's reference inference on the original graphs: the golden data of
 * tools/golden/generate-engine-golden.py. Greedy text, greedy speech codes with the repetition
 * penalty and the identity MaskGIT order: every slot must match. Skipped without CURIE_MODEL_DIR.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxGoldenTest {
    private lateinit var engine: OnnxEngine

    private val tokenizer
        get() = engine.resources.tokenizer

    private val goldenAudioSampling: AudioSampling
        get() {
            val sampling = EngineGolden.manifest.audioSampling
            return AudioSampling(
                temperature = sampling.temperature,
                topK = 0,
                repetitionPenalty = sampling.repetitionPenalty,
                repetitionWindow = sampling.repetitionWindow,
            )
        }

    @BeforeAll
    fun loadEngine() {
        engine = ModelFixture.loadEngine(useLora = false)
    }

    @AfterAll
    fun closeEngine() {
        if (this::engine.isInitialized) {
            engine.close()
        }
    }

    @Test
    fun greedyTextAnswersMatchTheReference() {
        for (name in listOf("capital", "multi-turn", "tool-call")) {
            val golden = EngineGolden.generation(name)
            val messages = EngineGolden.messages(golden)
            val tools = EngineGolden.tools(golden)
            val prompt = ChatTemplate.render(messages, tools, addGenerationPrompt = true, tokenizer)
            assertGoldenPrompt(golden, prompt)

            val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = golden.maxSlots)
            val listener = RecordingListener()
            val result = generate(prompt, request, listener)

            assertGoldenSlots(golden, result, name)
            assertEquals(result.text, listener.text.toString().trim(), "$name: streamed text")
            println("$name: ${result.text} ${result.toolCalls}")
        }

        val toolCall = generate(EngineGolden.generation("tool-call"))
        assertEquals("", toolCall.text)
        assertEquals(listOf("get_weather"), toolCall.toolCalls.map { call -> call.name })
        assertEquals("Paris", toolCall.toolCalls[0].arguments["city"]?.let { value -> value.toString().trim('"') })
    }

    @Test
    fun speechFramesMatchTheReference() {
        val golden = EngineGolden.generation("speech")
        val prompt = render(golden)
        val request = GenerationRequest(
            GenerationMode.SPEECH,
            maxNewSlots = golden.maxSlots,
            audioSampling = goldenAudioSampling,
        )
        val listener = RecordingListener()

        val result = generate(prompt, request, listener)

        assertEquals(Lfm3Omni.AUDIO_START_TOKEN, golden.marker)
        assertGoldenSlots(golden, result, "speech")
        assertEquals(golden.frames.size, listener.frames.size)
        // Cut at the slot limit inside speech: the content closes with an end-of-speech frame
        assertEquals(FinishReason.MAX_SLOTS, result.finishReason)
        val closing = result.content.audioCodes.takeLast(Lfm3Omni.NUM_CODEBOOKS)
        assertEquals(List(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }, closing)
        println("speech: ${listener.millisPerFrame()} ms per frame")
    }

    @Test
    fun aWholeSpeechAnswerMatchesTheReferenceThroughTheEndOfItsTurn() {
        val golden = EngineGolden.generation("speech-to-the-end")
        val prompt = render(golden)
        val request = GenerationRequest(
            GenerationMode.SPEECH,
            maxNewSlots = golden.maxSlots,
            audioSampling = goldenAudioSampling,
        )
        val listener = RecordingListener()

        val result = generate(prompt, request, listener)

        assertEquals(Lfm3Omni.AUDIO_START_TOKEN, golden.marker)
        assertGoldenSlots(golden, result, "speech to the end")
        assertSpeechEndedTheTurn(golden, result, listener)
    }

    @Test
    fun interleavedSlotsMatchTheReferenceThroughTheEndOfTheTurn() {
        val golden = EngineGolden.generation("interleaved")
        val prompt = render(golden)
        val request = GenerationRequest(
            GenerationMode.INTERLEAVED,
            maxNewSlots = golden.maxSlots,
            audioSampling = goldenAudioSampling,
        )
        val listener = RecordingListener()

        val result = generate(prompt, request, listener)

        assertEquals(Lfm3Omni.MIXED_START_TOKEN, golden.marker)
        assertGoldenSlots(golden, result, "interleaved")
        assertSpeechEndedTheTurn(golden, result, listener)
        println("interleaved: ${result.text} (${result.audioFrames.size} frames)")
    }

    @Test
    fun audioFeaturesAndSpeechRecognitionMatchTheReference() {
        val audio = EngineGolden.manifest.audio
        val samples = EngineGolden.wavSamples(audio.clip)
        assertEquals(audio.sampleCount, samples.size)

        // The encoder input is the reference's bit for bit
        val melFeatures = LogMelSpectrogram.compute(samples)
        assertEquals(audio.melSha256, EngineGolden.sha256(melFeatures))

        val input = AudioInput("speech", samples.size) { samples }
        val features = engine.resources.mediaFeatures.features(input, FeatureStream.AUDIO, null) {}
        assertEquals(audio.rowCount * Lfm3Omni.HIDDEN_SIZE, features.size)
        val difference = sampledRowsDifference(features, audio.rows, EngineGolden.floats(audio.expected))
        println("audio encoder: max abs difference $difference over rows ${audio.rows}")
        assertTrue(difference < 1e-3, "audio features differ by $difference")

        val golden = EngineGolden.generation("asr")
        val messages = listOf(
            ChatMessage.system("Perform ASR in English."),
            ChatMessage(ChatRole.USER, listOf(ContentPart.Audio(input))),
        )
        val prompt = ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, tokenizer)
        assertGoldenPrompt(golden, prompt)

        val request = GenerationRequest(GenerationMode.AUTO, maxNewSlots = golden.maxSlots)
        val result = generate(prompt, request, RecordingListener())

        assertGoldenSlots(golden, result, "asr")
        assertEquals(golden.text, result.text)
    }

    @Test
    fun visionFeaturesAndAnImageQuestionMatchTheReference() {
        val vision = EngineGolden.manifest.vision
        val image = EngineGolden.testImage(vision.size)

        // The encoder input is the reference's bit for bit
        val patches = VisionPreprocessor.image(image)
        assertEquals(vision.pixelValuesSha256, EngineGolden.sha256(patches.pixelValues))

        val input = ImageInput("disk", image.width, image.height) { image }
        val features = engine.resources.mediaFeatures.features(input, FeatureStream.IMAGE, null) {}
        assertEquals(vision.rowCount * Lfm3Omni.HIDDEN_SIZE, features.size)
        val difference = sampledRowsDifference(features, vision.rows, EngineGolden.floats(vision.expected))
        println("vision encoder: max abs difference $difference over rows ${vision.rows}")
        assertTrue(difference < 1e-2, "vision features differ by $difference")

        val golden = EngineGolden.generation("image-question")
        val question = checkNotNull(golden.question)
        val parts = listOf(ContentPart.Image(input), ContentPart.Text(question))
        val messages = listOf(ChatMessage(ChatRole.USER, parts))
        val prompt = ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, tokenizer)
        assertGoldenPrompt(golden, prompt)

        // Without cached features the image is encoded again, which the progress counts
        engine.resources.mediaFeatures.clear()
        val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = golden.maxSlots)
        val listener = RecordingListener()
        val result = generate(prompt, request, listener)

        assertGoldenSlots(golden, result, "image question")
        assertEquals(golden.text, result.text)
        // Encoding counts as prefill progress: the rows, then the positions
        assertEquals(vision.rowCount + prompt.length, listener.prefillTotal)
    }

    @Test
    fun streamedSpeechMatchesTheReferenceDecode() {
        val codec = EngineGolden.manifest.codec
        val expected = EngineGolden.floats(codec.expected)

        val decoder = engine.newSpeechDecoder()
        val samples = ArrayList<Float>()
        for (frame in codec.frames) {
            samples.addAll(decoder.push(frame.toIntArray()).toList())
        }
        samples.addAll(decoder.finish().toList())

        assertEquals(codec.frames.size * Lfm3Omni.FRAME_SAMPLES, samples.size)
        val difference = EngineGolden.maxAbsDifference(expected, samples.toFloatArray())

        // End-of-speech frames are not speech: the decoder refuses them
        val endOfSpeech = IntArray(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }
        assertThrows<IllegalArgumentException> { decoder.push(endOfSpeech) }
        println("codec: max abs difference $difference from the reference decode_audio")
        assertTrue(difference < 1e-4, "the streamed speech differs from decode_audio by $difference")
    }

    @Test
    fun chunkedSpeechDecodingEqualsDecodingTheWholeUtterance() {
        val frames = EngineGolden.manifest.codec.longFrames.map { frame -> frame.toIntArray() }
        val whole = decodeAll(engine.newSpeechDecoder(Int.MAX_VALUE, Lfm3Omni.CODEC_CONTEXT_FRAMES), frames)
        assertEquals(frames.size * Lfm3Omni.FRAME_SAMPLES, whole.size)

        for (chunkFrames in listOf(1, 5, 12, 25)) {
            val decoder = engine.newSpeechDecoder(chunkFrames, Lfm3Omni.CODEC_CONTEXT_FRAMES)
            val chunked = decodeAll(decoder, frames)
            val difference = EngineGolden.maxAbsDifference(whole, chunked)
            println("codec: ${frames.size} frames in chunks of $chunkFrames, max abs difference $difference")
            assertTrue(difference < 1e-4, "chunks of $chunkFrames differ by $difference")
        }

        // The measurement can tell: without left context the chunks do differ
        val withoutContext = decodeAll(engine.newSpeechDecoder(12, 0), frames)
        val contextFree = EngineGolden.maxAbsDifference(whole, withoutContext)
        println("codec: chunks of 12 without context, max abs difference $contextFree")
        assertTrue(contextFree > 1e-3, "the context makes no difference ($contextFree): the test cannot measure it")

        // A finished decoder starts a new utterance
        val reused = engine.newSpeechDecoder()
        decodeAll(reused, frames.take(7))
        val again = decodeAll(reused, frames)
        assertContentEquals(decodeAll(engine.newSpeechDecoder(), frames), again)
    }

    private fun decodeAll(decoder: SpeechDecoder, frames: List<IntArray>): FloatArray {
        val samples = ArrayList<Float>()
        for (frame in frames) {
            samples.addAll(decoder.push(frame).toList())
        }
        samples.addAll(decoder.finish().toList())
        return samples.toFloatArray()
    }

    private fun render(golden: EngineGolden.Generation): Prompt {
        val messages = EngineGolden.messages(golden)
        val prompt = ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, tokenizer)
        assertGoldenPrompt(golden, prompt)
        return prompt
    }

    /**
     * The prompt is the reference's, and so are its checkpoints, where the prefill is split into
     * runs (the reference split it the same way: see the golden script).
     */
    private fun assertGoldenPrompt(golden: EngineGolden.Generation, prompt: Prompt) {
        assertEquals(golden.prompt, prompt.tokens.toList(), "${golden.name}: prompt")
        assertEquals(golden.checkpoints, prompt.checkpoints.toList(), "${golden.name}: checkpoints")

        val fed = prompt.length + if (golden.marker != null) 1 else 0
        val chunks = PrefillChunks.of(0, fed, prompt.checkpoints)
        val bounds = chunks.map { chunk -> listOf(chunk.start, chunk.end) }
        assertEquals(golden.chunks, bounds, "${golden.name}: prefill runs")
    }

    private fun generate(golden: EngineGolden.Generation): GenerationResult {
        val prompt = ChatTemplate.render(EngineGolden.messages(golden), EngineGolden.tools(golden), true, tokenizer)
        val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = golden.maxSlots)
        return generate(prompt, request, RecordingListener())
    }

    /** A fresh conversation's answer, sampled with seed 0 and the identity MaskGIT order. */
    private fun generate(prompt: Prompt, request: GenerationRequest, listener: RecordingListener): GenerationResult {
        val capacity = prompt.length + 1 + request.maxNewSlots
        val conversation = engine.newConversation(capacity) as OnnxConversation
        return conversation.use { open ->
            open.generate(prompt, request, listener, SplittableRandom(0), MaskGitOrders.IDENTITY)
        }
    }

    /**
     * The content is the marker, then the reference's slots (frames with their codes), and the
     * generation stopped as the reference did. An answer that ended its turn must be the whole
     * content; one cut at the slot limit may be followed by the slots that close it.
     */
    private fun assertGoldenSlots(golden: EngineGolden.Generation, result: GenerationResult, name: String) {
        val expectedTokens = listOfNotNull(golden.marker) + golden.answerSlots
        val expectedCodes = golden.frames.flatten()
        var actualTokens = result.content.tokens.toList()
        var actualCodes = result.content.audioCodes.toList()
        if (golden.endedTurn) {
            assertEquals(FinishReason.END_OF_TURN, result.finishReason, "$name: finish")
        } else {
            assertEquals(FinishReason.MAX_SLOTS, result.finishReason, "$name: finish")
            actualTokens = actualTokens.take(expectedTokens.size)
            actualCodes = actualCodes.take(expectedCodes.size)
        }

        assertEquals(expectedTokens, actualTokens, "$name: slots")
        assertEquals(expectedCodes, actualCodes, "$name: frame codes")
    }

    /**
     * The reference's answer spoke until an end-of-speech frame, fed it back and ended its turn
     * with <|im_end|>; the engine reported that frame as the end of the speech, not as a frame.
     */
    private fun assertSpeechEndedTheTurn(
        golden: EngineGolden.Generation,
        result: GenerationResult,
        listener: RecordingListener,
    ) {
        val speechFrames = golden.frames.filter { frame -> Lfm3Omni.AUDIO_EOS_CODE !in frame }
        val endOfSpeechFrames = golden.frames.size - speechFrames.size
        assertTrue(golden.endedTurn, "${golden.name}: the reference answer was cut at its slot limit")
        assertEquals(1, endOfSpeechFrames, "${golden.name}: end-of-speech frames in the reference answer")

        assertEquals(1, listener.audioEnds, "${golden.name}: speech ends reported")
        val reportedFrames = listener.frames.map { frame -> frame.toList() }
        assertEquals(speechFrames, reportedFrames, "${golden.name}: speech frames reported")
        val resultFrames = result.audioFrames.map { frame -> frame.toList() }
        assertEquals(speechFrames, resultFrames, "${golden.name}: speech frames of the result")
    }

    private fun sampledRowsDifference(features: FloatArray, rows: List<Int>, expected: FloatArray): Double {
        val sampled = FloatArray(rows.size * Lfm3Omni.HIDDEN_SIZE)
        for ((index, row) in rows.withIndex()) {
            val start = row * Lfm3Omni.HIDDEN_SIZE
            features.copyInto(sampled, index * Lfm3Omni.HIDDEN_SIZE, start, start + Lfm3Omni.HIDDEN_SIZE)
        }
        return EngineGolden.maxAbsDifference(expected, sampled)
    }
}
