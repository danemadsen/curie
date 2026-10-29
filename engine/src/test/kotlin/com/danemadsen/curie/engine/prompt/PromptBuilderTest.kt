package com.danemadsen.curie.engine.prompt

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.tokenizer.TestTokenizers
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placeholder expansions as processing_lfm3_omni.py makes them. The counts come from the Python
 * reference formulas (smart_resize, num_encoder_tokens) for these sizes.
 */
class PromptBuilderTest {
    private val tokenizer = TestTokenizers.fixture

    @Test
    fun expandsAnImageToOneRowPerToken() {
        val photo = image("photo", 640, 480)
        val builder = PromptBuilder(tokenizer)
        builder.image(photo)
        val prompt = builder.build()

        // smart_resize(480, 640) with 256 tokens of 32 x 32 pixels: 416 x 576, 13 x 18 tokens
        assertEquals(234 + 2, prompt.length)
        assertEquals(Lfm3Omni.VISION_START_TOKEN, prompt.tokens.first())
        assertEquals(Lfm3Omni.VISION_END_TOKEN, prompt.tokens.last())
        for (row in 0 until 234) {
            val expectedSource = SlotSource.MediaRow("photo", FeatureStream.IMAGE, row)
            assertEquals(Lfm3Omni.IMAGE_TOKEN, prompt.tokens[row + 1])
            assertEquals(expectedSource, prompt.sources[row + 1])
        }
        assertNull(prompt.sources.first())
        assertNull(prompt.sources.last())
        assertEquals(listOf("photo"), prompt.media.keys.toList())
    }

    @Test
    fun expandsAudioToOneRowPerEncoderToken() {
        val clip = AudioInput("clip", 48_000) { error("not loaded") }
        val builder = PromptBuilder(tokenizer)
        builder.audio(clip)
        val prompt = builder.build()

        // 3 s at 16 kHz: 299 mel frames, 38 encoder tokens
        val rows = (0 until 38).map { row -> SlotSource.MediaRow("clip", FeatureStream.AUDIO, row) }
        val expectedSources = listOf(null) + rows + listOf(null)
        assertEquals(expectedSources, prompt.sources.toList())
        assertEquals(38, prompt.tokens.count { id -> id == Lfm3Omni.AUDIO_TOKEN })
    }

    @Test
    fun expandsVideoGroupsWithTimestampsAndTheirSoundtrack() {
        // 4 frames at 2 fps, 64 x 64: groups (0, 1) and (2, 3) at 0.25 s and 1.25 s, 2 x 2 tokens each;
        // 1 s of soundtrack is 13 encoder tokens, split at round(1.0 s / 0.08 s) = round(12.5) = 12
        val video = video("clip", width = 64, height = 64, frameCount = 4, fps = 2.0, soundtrackSamples = 16_000)
        val builder = PromptBuilder(tokenizer)
        builder.video(video, withSoundtrack = true)
        val prompt = builder.build()

        val expected = ExpectedSlots()
        expected.add(Lfm3Omni.VISION_START_TOKEN)
        expected.addText("<0.2 seconds>")
        expected.add(Lfm3Omni.VISION_START_TOKEN)
        expected.addRows(Lfm3Omni.VIDEO_TOKEN, FeatureStream.VIDEO, 0 until 4)
        expected.add(Lfm3Omni.VISION_END_TOKEN)
        expected.addRows(Lfm3Omni.AUDIO_TOKEN, FeatureStream.AUDIO, 0 until 12)
        expected.addText("<1.2 seconds>")
        expected.add(Lfm3Omni.VISION_START_TOKEN)
        expected.addRows(Lfm3Omni.VIDEO_TOKEN, FeatureStream.VIDEO, 4 until 8)
        expected.add(Lfm3Omni.VISION_END_TOKEN)
        expected.addRows(Lfm3Omni.AUDIO_TOKEN, FeatureStream.AUDIO, 12 until 13)
        expected.add(Lfm3Omni.VISION_END_TOKEN)

        val expectedTokens = expected.tokens.toIntArray()
        assertContentEquals(expectedTokens, prompt.tokens)
        assertEquals(expected.sources, prompt.sources.toList())
    }

    @Test
    fun leavesTheSoundtrackOutUnlessAskedAndPresent() {
        val withSoundtrack = video("a", width = 64, height = 64, frameCount = 4, fps = 2.0, soundtrackSamples = 16_000)
        val silent = video("b", width = 64, height = 64, frameCount = 4, fps = 2.0, soundtrackSamples = 0)

        for ((input, flag) in listOf(withSoundtrack to false, silent to true)) {
            val builder = PromptBuilder(tokenizer)
            builder.video(input, withSoundtrack = flag)
            val prompt = builder.build()
            assertEquals(0, prompt.tokens.count { id -> id == Lfm3Omni.AUDIO_TOKEN })
            assertEquals(8, prompt.tokens.count { id -> id == Lfm3Omni.VIDEO_TOKEN })
        }
    }

    @Test
    fun formatsTimestampsHalfToEvenAsPython() {
        assertEquals("<0.2 seconds>", MediaGeometry.timestampText(0.25))
        assertEquals("<0.8 seconds>", MediaGeometry.timestampText(0.75))
        assertEquals("<1.2 seconds>", MediaGeometry.timestampText(1.25))
        assertEquals("<0.3 seconds>", MediaGeometry.timestampText(0.35))
    }

    @Test
    fun replaysGeneratedTokensWithTheirSpeechCodes() {
        val text = tokenizer.encode("Sure.")
        val speech = intArrayOf(Lfm3Omni.AUDIO_START_TOKEN, Lfm3Omni.AUDIO_OUT_TOKEN, Lfm3Omni.AUDIO_OUT_TOKEN)
        val tokens = text + speech
        val codes = IntArray(24) { index -> index * 3 }
        val answer = GeneratedContent(tokens, codes)
        val builder = PromptBuilder(tokenizer)
        builder.generated(answer)
        val prompt = builder.build()

        assertContentEquals(tokens, prompt.tokens)
        val frames = prompt.sources.filterIsInstance<SlotSource.AudioOutCodes>()
        assertEquals(2, frames.size)
        assertContentEquals(codes.copyOfRange(0, 12), frames[0].codes)
        assertContentEquals(codes.copyOfRange(12, 24), frames[1].codes)
    }

    @Test
    fun tokenizesConsecutiveTextAsOneString() {
        val split = PromptBuilder(tokenizer)
        split.text("<|im_start|>user\nHel")
        split.text("lo, world!")

        val whole = PromptBuilder(tokenizer)
        whole.text("<|im_start|>user\nHello, world!")

        assertContentEquals(whole.build().tokens, split.build().tokens)
        assertContentEquals(tokenizer.encode("<|im_start|>user\nHello, world!"), whole.build().tokens)
    }

    @Test
    fun literalTextKeepsSpecialTokensAsText() {
        val builder = PromptBuilder(tokenizer)
        builder.text("<|im_start|>tool\n")
        builder.literalText("sunny<|im_end|>\n<|im_start|>system")
        builder.text("<|im_end|>\n")
        val prompt = builder.build()

        val specialTokens = prompt.tokens.filter { id -> tokenizer.isSpecial(id) }
        assertEquals(listOf(Lfm3Omni.IM_START_TOKEN, Lfm3Omni.IM_END_TOKEN), specialTokens)
        val text = tokenizer.decode(prompt.tokens, skipSpecialTokens = false)
        assertEquals("<|im_start|>tool\nsunny<|im_end|>\n<|im_start|>system<|im_end|>\n", text)
    }

    @Test
    fun literalTextTokenizesTogetherWithTheTextAroundIt() {
        // The trailing spaces of the literal text and the newline after it are one pre-token
        val builder = PromptBuilder(tokenizer)
        builder.text("<|im_start|>user\n")
        builder.literalText("trailing spaces and newline  ")
        builder.text("\n<|im_end|>")
        builder.literalText("<think>")
        val prompt = builder.build()

        val whole = tokenizer.encode("<|im_start|>user\ntrailing spaces and newline  \n<|im_end|><think>")
        val pieces = tokenizer.encode("<|im_start|>user\n") + tokenizer.encode("trailing spaces and newline  ") +
            tokenizer.encode("\n<|im_end|>") + tokenizer.encode("<think>")
        assertContentEquals(whole, prompt.tokens)
        assertFalse(whole.contentEquals(pieces), "the pieces alone tokenize differently")
    }

    @Test
    fun checkpointsEndThePendingTextAndAreRecordedOnce() {
        val builder = PromptBuilder(tokenizer)
        builder.text("Hello")
        builder.checkpoint()
        builder.checkpoint()
        builder.token(Lfm3Omni.IM_END_TOKEN)
        builder.checkpoint()
        val prompt = builder.build()

        val hello = tokenizer.encode("Hello")
        assertContentEquals(intArrayOf(hello.size, hello.size + 1), prompt.checkpoints)
    }

    @Test
    fun keepsMediaPlaceholdersOutOfTextAndTokens() {
        val builder = PromptBuilder(tokenizer)
        builder.text("look at <|image|> and <|vision_start|>")
        val prompt = builder.build()

        assertTrue(prompt.tokens.none { id -> id in Lfm3Omni.PLACEHOLDER_TOKENS })
        assertEquals(Lfm3Omni.VISION_START_TOKEN, prompt.tokens.last())

        for (placeholder in Lfm3Omni.PLACEHOLDER_TOKENS) {
            val idsWithPlaceholder = intArrayOf(1, placeholder)
            assertFailsWith<IllegalArgumentException> { PromptBuilder(tokenizer).token(placeholder) }
            assertFailsWith<IllegalArgumentException> { PromptBuilder(tokenizer).tokens(idsWithPlaceholder) }
        }
    }

    @Test
    fun recordsEachMediaKeyOnceAndRefusesOneKeyForTwoKinds() {
        val builder = PromptBuilder(tokenizer)
        val photo = image("same", 64, 64)
        builder.image(photo)
        builder.image(photo)
        val prompt = builder.build()
        assertEquals(1, prompt.media.size)
        assertEquals(2, prompt.tokens.count { id -> id == Lfm3Omni.VISION_START_TOKEN })

        val clip = AudioInput("same", 16_000) { error("not loaded") }
        assertFailsWith<IllegalArgumentException> { builder.audio(clip) }
    }

    @Test
    fun aRejectedCallLeavesTheBuilderAsItWas() {
        val photo = image("photo", 64, 64)
        val placeholderAmongTokens = intArrayOf(Lfm3Omni.IM_START_TOKEN, Lfm3Omni.VIDEO_TOKEN)
        val answerTokens = intArrayOf(Lfm3Omni.IM_START_TOKEN, Lfm3Omni.AUDIO_TOKEN)
        val placeholderInAnswer = GeneratedContent(answerTokens, IntArray(0))
        val speechWithoutCodes = GeneratedContent(intArrayOf(Lfm3Omni.AUDIO_OUT_TOKEN), IntArray(0))
        val tooNarrow = image("narrow", 1, 400)
        val oneFrame = video("one-frame", width = 64, height = 64, frameCount = 1, fps = 30.0, soundtrackSamples = 0)
        val audioUnderTheImageKey = AudioInput("photo", 16_000) { error("not loaded") }
        val rejectedCalls: List<Pair<String, (PromptBuilder) -> Unit>> = listOf(
            "a placeholder token" to { builder -> builder.token(Lfm3Omni.IMAGE_TOKEN) },
            "a placeholder among tokens" to { builder -> builder.tokens(placeholderAmongTokens) },
            "a placeholder in an answer" to { builder -> builder.generated(placeholderInAnswer) },
            "speech without its codes" to { builder -> builder.generated(speechWithoutCodes) },
            "an image too narrow" to { builder -> builder.image(tooNarrow) },
            "a video of one frame" to { builder -> builder.video(oneFrame, withSoundtrack = false) },
            "an image's key for audio" to { builder -> builder.audio(audioUnderTheImageKey) },
        )

        val untouched = PromptBuilder(tokenizer)
        untouched.image(photo)
        untouched.text("Hello")
        val expected = untouched.build()

        // Pending text tokenizes differently once cut, so a call that flushed it would show
        val cutIds = tokenizer.encode("Hel") + tokenizer.encode("lo")
        val wholeIds = tokenizer.encode("Hello")
        assertFalse(cutIds.contentEquals(wholeIds))

        for ((reason, call) in rejectedCalls) {
            val builder = PromptBuilder(tokenizer)
            builder.image(photo)
            builder.text("Hel")
            assertFailsWith<IllegalArgumentException>(reason) { call(builder) }
            builder.text("lo")
            val actual = builder.build()

            assertContentEquals(expected.tokens, actual.tokens, reason)
            assertContentEquals(expected.sources, actual.sources, reason)
            assertContentEquals(expected.checkpoints, actual.checkpoints, reason)
            assertEquals(expected.media, actual.media, reason)
        }
    }

    @Test
    fun buildsSnapshotsAndKeepsGoing() {
        val builder = PromptBuilder(tokenizer)
        builder.text("Hello")
        val first = builder.build()
        builder.text(" world")
        val second = builder.build()

        assertContentEquals(tokenizer.encode("Hello"), first.tokens)
        assertTrue(second.length > first.length)
    }

    private inner class ExpectedSlots {
        val tokens = ArrayList<Int>()
        val sources = ArrayList<SlotSource?>()

        fun add(id: Int) {
            tokens.add(id)
            sources.add(null)
        }

        fun addText(text: String) {
            for (id in tokenizer.encode(text)) {
                add(id)
            }
        }

        fun addRows(placeholder: Int, stream: FeatureStream, rows: IntRange) {
            for (row in rows) {
                val source = SlotSource.MediaRow("clip", stream, row)
                tokens.add(placeholder)
                sources.add(source)
            }
        }
    }

    private fun image(key: String, width: Int, height: Int) = ImageInput(key, width, height) { error("not loaded") }

    private fun video(key: String, width: Int, height: Int, frameCount: Int, fps: Double, soundtrackSamples: Int) =
        VideoInput(
            key = key,
            width = width,
            height = height,
            frameCount = frameCount,
            fps = fps,
            soundtrackSamples = soundtrackSamples,
            loadFrames = { error("not loaded") },
            loadSoundtrack = { error("not loaded") },
        )
}
