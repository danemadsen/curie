package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.RgbImage
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.MediaInput
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.SlotSource
import com.danemadsen.curie.engine.prompt.VideoInput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** The checks a prompt passes before it touches a cache, without ONNX Runtime. */
class PromptChecksTest {
    private val image = ImageInput("photo", 512, 512) { RgbImage(1, 1, ByteArray(3)) }
    private val clip = AudioInput("clip", 16_000) { FloatArray(16_000) }
    private val video = VideoInput(
        key = "video",
        width = 320,
        height = 240,
        frameCount = 20,
        fps = 10.0,
        soundtrackSamples = 32_000,
        loadFrames = { indices -> List(indices.size) { RgbImage(320, 240, ByteArray(320 * 240 * 3)) } },
        loadSoundtrack = { FloatArray(32_000) },
    )
    private val silentVideo = VideoInput("silent", 320, 240, 20, 10.0, 0, { emptyList() }, { FloatArray(0) })

    private fun prompt(vararg slots: Pair<Int, SlotSource?>, media: List<MediaInput> = listOf(image, clip)): Prompt {
        val tokens = IntArray(slots.size) { index -> slots[index].first }
        val sources = Array(slots.size) { index -> slots[index].second }
        val mediaByKey = media.associateBy { input -> input.key }
        return Prompt(tokens, sources, intArrayOf(slots.size), mediaByKey)
    }

    private fun imageRow(row: Int) = Lfm3Omni.IMAGE_TOKEN to SlotSource.MediaRow("photo", FeatureStream.IMAGE, row)

    private fun frame(code: Int) = Lfm3Omni.AUDIO_OUT_TOKEN to SlotSource.AudioOutCodes(IntArray(12) { code })

    @Test
    fun aWellFormedPromptPasses() {
        val audioRow = Lfm3Omni.AUDIO_TOKEN to SlotSource.MediaRow("clip", FeatureStream.AUDIO, 0)
        val valid = prompt(1 to null, imageRow(0), imageRow(1), audioRow, frame(Lfm3Omni.AUDIO_EOS_CODE), 2 to null)

        assertDoesNotThrow { PromptChecks.validate(valid) }
    }

    @Test
    fun placeholdersNeedTheirSources() {
        val noSource = prompt(Lfm3Omni.IMAGE_TOKEN to null)
        val wrongStream = prompt(Lfm3Omni.IMAGE_TOKEN to SlotSource.MediaRow("clip", FeatureStream.AUDIO, 0))
        val unknownMedia = prompt(Lfm3Omni.IMAGE_TOKEN to SlotSource.MediaRow("other", FeatureStream.IMAGE, 0))
        val noCodes = prompt(Lfm3Omni.AUDIO_OUT_TOKEN to null)

        for (invalid in listOf(noSource, wrongStream, unknownMedia, noCodes)) {
            assertThrows<IllegalArgumentException> { PromptChecks.validate(invalid) }
        }
    }

    @Test
    fun rowsMustBeWithinTheMediasRows() {
        val rows = MediaGeometry.imageGrid(512, 512, 256).tokens

        assertDoesNotThrow { PromptChecks.validate(prompt(imageRow(rows - 1))) }
        assertThrows<IllegalArgumentException> { PromptChecks.validate(prompt(imageRow(rows))) }
    }

    @Test
    fun framesNeedTwelveCodesInTheCodebook() {
        val short = prompt(Lfm3Omni.AUDIO_OUT_TOKEN to SlotSource.AudioOutCodes(IntArray(11)))
        val outOfRange = prompt(frame(Lfm3Omni.CODEBOOK_SIZE))
        val negative = prompt(frame(-1))

        for (invalid in listOf(short, outOfRange, negative)) {
            assertThrows<IllegalArgumentException> { PromptChecks.validate(invalid) }
        }
    }

    @Test
    fun plainTokensCarryNoSource() {
        val invalid = prompt(5 to SlotSource.MediaRow("photo", FeatureStream.IMAGE, 0))

        assertThrows<IllegalArgumentException> { PromptChecks.validate(invalid) }
    }

    @Test
    fun mediaStreamsComeInTheOrderTheyFirstAppear() {
        val audioRow = Lfm3Omni.AUDIO_TOKEN to SlotSource.MediaRow("clip", FeatureStream.AUDIO, 0)
        val full = prompt(1 to null, audioRow, imageRow(0), imageRow(1), audioRow)
        val target = SlotSequence(full.tokens, full.sources, full.length)

        val all = PromptChecks.mediaStreams(target, 0, full.length, full.media)
        val tail = PromptChecks.mediaStreams(target, 2, 4, full.media)

        val allKeys = all.map { (input, stream) -> input.key to stream }
        val tailKeys = tail.map { (input, stream) -> input.key to stream }
        assertEquals(listOf("clip" to FeatureStream.AUDIO, "photo" to FeatureStream.IMAGE), allKeys)
        assertEquals(listOf("photo" to FeatureStream.IMAGE), tailKeys)
    }

    @Test
    fun expectedRowsFollowThePromptBuildersGeometry() {
        val frameIndices = MediaGeometry.videoFrameIndices(20, 10.0, 32)
        val size = MediaGeometry.videoResizeTarget(320, 240, frameIndices.size, 1024)
        val groupTokens = (size.width / 32) * (size.height / 32)
        val groups = MediaGeometry.videoGroupCount(frameIndices.size)

        val imageTokens = MediaGeometry.imageGrid(512, 512, 256).tokens
        assertEquals(imageTokens, MediaFeatures.expectedRows(image, FeatureStream.IMAGE))
        assertEquals(MediaGeometry.audioTokenCount(16_000), MediaFeatures.expectedRows(clip, FeatureStream.AUDIO))
        assertEquals(groups * groupTokens, MediaFeatures.expectedRows(video, FeatureStream.VIDEO))
        assertEquals(MediaGeometry.audioTokenCount(32_000), MediaFeatures.expectedRows(video, FeatureStream.AUDIO))
        assertThrows<IllegalArgumentException> { MediaFeatures.expectedRows(silentVideo, FeatureStream.AUDIO) }
        assertThrows<IllegalArgumentException> { MediaFeatures.expectedRows(image, FeatureStream.AUDIO) }
    }
}
