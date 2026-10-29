package com.danemadsen.curie.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaFactsTest {
    private fun container(
        hasVideo: Boolean = true,
        hasAudio: Boolean = false,
        durationMs: Long? = 10_000,
        width: Int? = 1920,
        height: Int? = 1080,
        rotationDegrees: Int? = 0,
        frameCount: Int? = 300,
        captureFrameRate: Double? = null,
    ) = ContainerMetadata(
        hasVideo = hasVideo,
        hasAudio = hasAudio,
        mimeType = "video/mp4",
        durationMs = durationMs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        frameCount = frameCount,
        captureFrameRate = captureFrameRate,
    )

    private val noTracks = TrackMetadata(audioDurationUs = null, videoDurationUs = null, videoFrameRate = null)

    @Test
    fun theFrameRateIsTheFrameCountOverTheDuration() {
        val slowMotion = container(frameCount = 300, captureFrameRate = 240.0)

        val facts = videoFacts(slowMotion, noTracks, "video/mp4")

        assertNotNull(facts)
        assertEquals(AttachmentKind.VIDEO, facts.kind)
        assertEquals(300, facts.frameCount)
        assertEquals(30.0, facts.fps)
        assertEquals(10_000, facts.durationMs)
        assertEquals(0, facts.sampleCount16k)
    }

    @Test
    fun theFrameRateIsTheVideoTracksNotALongerSoundtracks() {
        // The container reports its longest track: here the soundtrack, half a second past the picture
        val clip = container(hasAudio = true, durationMs = 10_500, frameCount = 300)
        val tracks = TrackMetadata(audioDurationUs = 10_500_000, videoDurationUs = 10_000_000, videoFrameRate = 30.0)
        val uncounted = container(hasAudio = true, durationMs = 10_500, frameCount = null)

        val facts = videoFacts(clip, tracks, "video/mp4")
        val estimated = videoFacts(uncounted, tracks, "video/mp4")

        assertEquals(30.0, facts?.fps)
        assertEquals(300, facts?.frameCount)
        assertEquals(10_500, facts?.durationMs)
        assertEquals(168_000, facts?.sampleCount16k)
        assertEquals(300, estimated?.frameCount)
    }

    @Test
    fun withoutAFrameCountTheTrackRateThenTheCaptureRateEstimateIt() {
        val uncounted = container(frameCount = null, captureFrameRate = 24.0)
        val trackRate = TrackMetadata(audioDurationUs = null, videoDurationUs = null, videoFrameRate = 29.97)

        val fromTrack = videoFacts(uncounted, trackRate, "video/mp4")
        val fromCapture = videoFacts(uncounted, noTracks, "video/mp4")
        val unknown = videoFacts(container(frameCount = 0, captureFrameRate = null), noTracks, "video/mp4")

        assertEquals(29.97, fromTrack?.fps)
        assertEquals(299, fromTrack?.frameCount)
        assertEquals(24.0, fromCapture?.fps)
        assertEquals(240, fromCapture?.frameCount)
        assertNull(unknown)
    }

    @Test
    fun aQuarterTurnSwapsTheVideoSides() {
        val portrait = videoFacts(container(rotationDegrees = 90), noTracks, "video/mp4")
        val upsideDown = videoFacts(container(rotationDegrees = 180), noTracks, "video/mp4")

        assertEquals(1080 to 1920, portrait?.let { facts -> facts.width to facts.height })
        assertEquals(1920 to 1080, upsideDown?.let { facts -> facts.width to facts.height })
        assertTrue(isQuarterTurn(-90))
        assertTrue(isQuarterTurn(450))
        assertFalse(isQuarterTurn(360))
    }

    @Test
    fun theSoundtrackIsCountedAt16kHzFromTheAudioTrack() {
        val withSound = container(hasAudio = true)
        val audioTrack = TrackMetadata(audioDurationUs = 9_500_000, videoDurationUs = null, videoFrameRate = null)

        val fromTrack = videoFacts(withSound, audioTrack, "video/mp4")
        val fromContainer = videoFacts(withSound, noTracks, "video/mp4")

        assertEquals(152_000, fromTrack?.sampleCount16k)
        assertEquals(160_000, fromContainer?.sampleCount16k)
    }

    @Test
    fun unusableVideoMetadataIsRejected() {
        assertNull(videoFacts(container(durationMs = null), noTracks, "video/mp4"))
        assertNull(videoFacts(container(durationMs = 0), noTracks, "video/mp4"))
        assertNull(videoFacts(container(width = 0), noTracks, "video/mp4"))
        assertNull(videoFacts(container(height = null), noTracks, "video/mp4"))
    }

    @Test
    fun audioPrefersTheTracksMicrosecondDuration() {
        val clip = container(hasVideo = false, hasAudio = true, durationMs = 3_000)
        val track = TrackMetadata(audioDurationUs = 2_999_937, videoDurationUs = null, videoFrameRate = null)

        val fromTrack = audioFacts(clip, track, "audio/mpeg")
        val fromContainer = audioFacts(clip, noTracks, "audio/mpeg")
        val empty = audioFacts(container(hasVideo = false, durationMs = null), noTracks, "audio/mpeg")

        assertEquals(AttachmentKind.AUDIO, fromTrack?.kind)
        assertEquals(2_999, fromTrack?.durationMs)
        assertEquals(47_998, fromTrack?.sampleCount16k)
        assertEquals(48_000, fromContainer?.sampleCount16k)
        assertNull(empty)
    }

    @Test
    fun sampleCountsRoundDownAndStayInRange() {
        assertEquals(0, samplesAt16k(62))
        assertEquals(1, samplesAt16k(63))
        assertEquals(16_000, samplesAt16k(1_000_000))
        assertEquals(16_000 * 600 + 15, samplesAt16k(600_000_999))
        assertEquals(Int.MAX_VALUE, samplesAt16k(Long.MAX_VALUE))
        assertEquals(0, samplesAt16k(-5))
    }
}
