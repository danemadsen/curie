package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.generation.AudioSampling
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.MaskGitOrders
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The engine with the bundled Curie voice LoRA (each graph run with its own adapter). Prints the
 * speed: per text token, and per speech frame (a decoder step and three depth passes). Skipped
 * without CURIE_MODEL_DIR.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoraEngineTest {
    private lateinit var engine: OnnxEngine

    @BeforeAll
    fun loadEngine() {
        engine = ModelFixture.loadEngine(useLora = true)
    }

    @AfterAll
    fun closeEngine() {
        if (this::engine.isInitialized) {
            engine.close()
        }
    }

    @Test
    fun theVoiceChangesTheSpeechCodes() {
        val golden = EngineGolden.generation("speech")
        val messages = EngineGolden.messages(golden)
        val prompt = ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, engine.resources.tokenizer)
        val sampling = EngineGolden.manifest.audioSampling
        val audioSampling = AudioSampling(
            temperature = sampling.temperature,
            repetitionPenalty = sampling.repetitionPenalty,
            repetitionWindow = sampling.repetitionWindow,
        )
        val request = GenerationRequest(
            GenerationMode.SPEECH,
            maxNewSlots = golden.maxSlots,
            audioSampling = audioSampling,
        )
        val listener = RecordingListener()

        val conversation = engine.newConversation(prompt.length + 1 + golden.maxSlots) as OnnxConversation
        val result = conversation.use { open ->
            open.generate(prompt, request, listener, SplittableRandom(0), MaskGitOrders.IDENTITY)
        }

        // The same request as the base model's golden: the adapter changes the codes
        assertEquals(FinishReason.MAX_SLOTS, result.finishReason)
        assertEquals(golden.frames.size, result.audioFrames.size)
        val baseCodes = golden.frames.flatten()
        val loraCodes = result.audioFrames.flatMap { frame -> frame.toList() }
        assertNotEquals(baseCodes, loraCodes)
        for (code in loraCodes) {
            assertTrue(code in 0 until Lfm3Omni.AUDIO_EOS_CODE, "speech code $code")
        }

        // And it still speaks: the frames decode to audible samples
        val decoder = engine.newSpeechDecoder()
        val samples = ArrayList<Float>()
        for (frame in result.audioFrames) {
            samples.addAll(decoder.push(frame).toList())
        }
        samples.addAll(decoder.finish().toList())
        val peak = samples.maxOf { sample -> kotlin.math.abs(sample) }
        assertTrue(peak > 0.01f, "peak amplitude $peak")
        println("LoRA speech: ${listener.millisPerFrame()?.let { "%.1f".format(it) }} ms per frame, peak $peak")
    }

    @Test
    fun textStillAnswers() {
        val prompt = ChatTemplate.render(
            listOf(ChatMessage.user("What is the capital of France? Answer in one short sentence.")),
            emptyList(),
            addGenerationPrompt = true,
            engine.resources.tokenizer,
        )
        val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 32)
        var tokens = 0
        var firstTokenAt = 0L
        var lastTokenAt = 0L
        val listener = RecordingListener {
            val now = System.nanoTime()
            if (tokens == 0) {
                firstTokenAt = now
            }
            lastTokenAt = now
            tokens++
        }

        val result = engine.newConversation(prompt.length + 32).use { conversation ->
            conversation.generate(prompt, request, listener)
        }

        assertEquals(FinishReason.END_OF_TURN, result.finishReason)
        assertTrue("Paris" in result.text, result.text)
        if (tokens > 1) {
            val millisPerToken = (lastTokenAt - firstTokenAt) / 1e6 / (tokens - 1)
            println("LoRA text: ${"%.1f".format(millisPerToken)} ms per token: ${result.text}")
        }
    }
}
