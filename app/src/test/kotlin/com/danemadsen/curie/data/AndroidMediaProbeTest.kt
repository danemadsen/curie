package com.danemadsen.curie.data

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The platform readers: real image decoding (native graphics), and Robolectric's media shadows
 * standing in for the audio and video parsers.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidMediaProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "probe").apply { mkdirs() }
    private val probe = AndroidMediaProbe()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun writeImage(name: String, format: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val file = File(directory, name)
        ImageIO.write(image, format, file)
        return file
    }

    @Test
    fun readsAnImagesBoundsAndType() {
        val png = writeImage("picture.bin", "png", 40, 30)

        val facts = probe.probe(png, mimeTypeHint = "audio/mpeg")

        assertNotNull(facts)
        assertEquals(AttachmentKind.IMAGE, facts.kind)
        assertEquals("image/png", facts.mimeType)
        assertEquals(40 to 30, facts.width to facts.height)
    }

    @Test
    fun exifOrientationTurnsTheSize() {
        val jpeg = writeImage("photo.jpg", "jpg", 60, 20)
        val exif = ExifInterface(jpeg)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val facts = probe.probe(jpeg, mimeTypeHint = null)

        assertEquals("image/jpeg", facts?.mimeType)
        assertEquals(20 to 60, facts?.let { found -> found.width to found.height })
    }

    @Test
    fun readsAVideosContainerAndTracks() {
        val video = File(directory, "clip.mp4")
        video.writeText("not decoded: the shadows answer for this path")
        val path = video.path
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "video/mp4")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "2000")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "270")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "60")

        val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 2)
        audioFormat.setLong(MediaFormat.KEY_DURATION, 1_990_000)
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(path), audioFormat, ByteArray(0))

        val facts = probe.probe(video, mimeTypeHint = "video/*")

        assertNotNull(facts)
        assertEquals(AttachmentKind.VIDEO, facts.kind)
        assertEquals("video/mp4", facts.mimeType)
        assertEquals(1080 to 1920, facts.width to facts.height)
        assertEquals(60, facts.frameCount)
        assertEquals(30.0, facts.fps)
        assertEquals(31_840, facts.sampleCount16k)
    }

    @Test
    fun theFrameRateFollowsTheVideoTrackWhenTheSoundtrackRunsLonger() {
        val video = File(directory, "long-sound.mp4")
        video.writeText("not decoded: the shadows answer for this path")
        val path = video.path
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        // The longest track's duration, as the platform reports it
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "2500")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "640")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "480")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "48")

        val videoFormat = MediaFormat.createVideoFormat("video/avc", 640, 480)
        videoFormat.setLong(MediaFormat.KEY_DURATION, 2_000_000)
        val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48_000, 1)
        audioFormat.setLong(MediaFormat.KEY_DURATION, 2_500_000)
        val source = DataSource.toDataSource(path)
        ShadowMediaExtractor.addTrack(source, videoFormat, ByteArray(0))
        ShadowMediaExtractor.addTrack(source, audioFormat, ByteArray(0))

        val facts = probe.probe(video, mimeTypeHint = "video/mp4")

        assertNotNull(facts)
        assertEquals(24.0, facts.fps)
        assertEquals(48, facts.frameCount)
        assertEquals(2_500, facts.durationMs)
        assertEquals(40_000, facts.sampleCount16k)
    }

    @Test
    fun anAudioOnlyContainerIsAudio() {
        val clip = File(directory, "voice.m4a")
        clip.writeText("not decoded")
        val path = clip.path
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "video/mp4")
        ShadowMediaMetadataRetriever.addMetadata(path, MediaMetadataRetriever.METADATA_KEY_DURATION, "1500")

        val facts = probe.probe(clip, mimeTypeHint = null)

        assertEquals(AttachmentKind.AUDIO, facts?.kind)
        assertEquals("audio/mp4", facts?.mimeType)
        assertEquals(24_000, facts?.sampleCount16k)
    }

    @Test
    fun somethingElseIsNotMedia() {
        val text = File(directory, "notes.txt")
        text.writeText("just text")

        assertNull(probe.probe(text, mimeTypeHint = "image/png"))
    }
}
