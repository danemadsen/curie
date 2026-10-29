package com.danemadsen.curie.data

import com.danemadsen.curie.engine.Lfm3Omni
import java.io.File
import kotlin.math.floor

/** What a media file holds, as [Attachment] reports it; facts that do not apply are 0. */
internal data class MediaFacts(
    val kind: AttachmentKind,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val frameCount: Int = 0,
    val fps: Double = 0.0,
    val sampleCount16k: Int = 0,
)

/** Identifies a media file by its content. */
internal fun interface MediaProbe {
    /** The facts of [file], or null when it is not an image, video or audio file Curie can read. */
    fun probe(file: File, mimeTypeHint: String?): MediaFacts?
}

/** What MediaMetadataRetriever reported; null where a key was missing or unreadable. */
internal data class ContainerMetadata(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val mimeType: String?,

    /** The longest track's duration: a soundtrack can run past the picture. */
    val durationMs: Long?,

    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int?,

    /** The first video track's frame count. */
    val frameCount: Int?,

    val captureFrameRate: Double?,
)

/** What MediaExtractor reported about the first audio and the first video track. */
internal data class TrackMetadata(
    val audioDurationUs: Long?,
    val videoDurationUs: Long?,
    val videoFrameRate: Double?,
)

/** Samples of a stretch of audio at the model's 16 kHz input rate, rounded down. */
internal fun samplesAt16k(durationUs: Long): Int {
    if (durationUs <= 0) {
        return 0
    }

    // Whole seconds and the rest apart, so no duration can overflow the multiplication
    val rate = Lfm3Omni.INPUT_SAMPLE_RATE.toLong()
    val seconds = durationUs / MICROSECONDS_PER_SECOND
    val remainderUs = durationUs % MICROSECONDS_PER_SECOND
    val samples = seconds * rate + remainderUs * rate / MICROSECONDS_PER_SECOND
    return samples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private const val MICROSECONDS_PER_SECOND = 1_000_000L

/**
 * A video's facts. The frame rate is the frame count over the video track's duration when the
 * container counts its frames: the stream's average rate, which frame indices map to and which
 * the reference processor reads (the capture rate of a slow-motion clip is not). Without a frame
 * count it falls back to the track's nominal rate, then the capture rate, and estimates the
 * count. The container's duration, the longest track's, stands in for a track without one.
 */
internal fun videoFacts(container: ContainerMetadata, tracks: TrackMetadata, mimeType: String): MediaFacts? {
    val durationMs = container.durationMs ?: return null
    val storedWidth = container.width ?: return null
    val storedHeight = container.height ?: return null
    if (durationMs <= 0 || storedWidth <= 0 || storedHeight <= 0) {
        return null
    }

    val videoDurationUs = tracks.videoDurationUs?.takeIf { durationUs -> durationUs > 0 }
    val videoSeconds = if (videoDurationUs != null) videoDurationUs / 1_000_000.0 else durationMs / 1000.0
    val countedFrames = container.frameCount ?: 0
    val fps: Double
    val frameCount: Int
    if (countedFrames > 0) {
        frameCount = countedFrames
        fps = countedFrames / videoSeconds
    } else {
        fps = tracks.videoFrameRate ?: container.captureFrameRate ?: return null
        frameCount = floor(videoSeconds * fps).toInt()
    }
    if (frameCount <= 0 || fps <= 0.0 || !fps.isFinite()) {
        return null
    }

    val quarterTurn = isQuarterTurn(container.rotationDegrees ?: 0)
    val width = if (quarterTurn) storedHeight else storedWidth
    val height = if (quarterTurn) storedWidth else storedHeight

    val soundtrackSamples = if (container.hasAudio) {
        val soundtrackUs = tracks.audioDurationUs ?: (durationMs * 1000)
        samplesAt16k(soundtrackUs)
    } else {
        0
    }

    return MediaFacts(
        kind = AttachmentKind.VIDEO,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        frameCount = frameCount,
        fps = fps,
        sampleCount16k = soundtrackSamples,
    )
}

/** An audio clip's facts: the audio track's duration (microseconds) is preferred to the container's. */
internal fun audioFacts(container: ContainerMetadata, tracks: TrackMetadata, mimeType: String): MediaFacts? {
    val containerUs = container.durationMs?.let { durationMs -> durationMs * 1000 }
    val durationUs = tracks.audioDurationUs ?: containerUs ?: return null
    if (durationUs <= 0) {
        return null
    }

    return MediaFacts(
        kind = AttachmentKind.AUDIO,
        mimeType = mimeType,
        durationMs = durationUs / 1000,
        sampleCount16k = samplesAt16k(durationUs),
    )
}

internal fun isQuarterTurn(rotationDegrees: Int): Boolean {
    val normalized = Math.floorMod(rotationDegrees, 360)
    return normalized == 90 || normalized == 270
}
