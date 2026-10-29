package com.danemadsen.curie.data

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Identifies media with the platform's own parsers: BitmapFactory (bounds only) and ExifInterface
 * for images, MediaMetadataRetriever and MediaExtractor for audio and video. The MIME type hint
 * only decides which parser runs first.
 */
internal class AndroidMediaProbe : MediaProbe {
    override fun probe(file: File, mimeTypeHint: String?): MediaFacts? {
        val hintsAudioOrVideo = mimeTypeHint != null && (isType(mimeTypeHint, VIDEO) || isType(mimeTypeHint, AUDIO))
        if (hintsAudioOrVideo) {
            return probeAudioOrVideo(file, mimeTypeHint) ?: probeImage(file)
        }
        return probeImage(file) ?: probeAudioOrVideo(file, mimeTypeHint)
    }

    private fun probeImage(file: File): MediaFacts? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, options)

        val mimeType = options.outMimeType
        if (mimeType == null || options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        val quarterTurn = isQuarterTurn(exifRotationDegrees(file))
        val width = if (quarterTurn) options.outHeight else options.outWidth
        val height = if (quarterTurn) options.outWidth else options.outHeight
        return MediaFacts(kind = AttachmentKind.IMAGE, mimeType = mimeType, width = width, height = height)
    }

    private fun exifRotationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file)
            exif.rotationDegrees
        } catch (error: IOException) {
            0
        }
    }

    private fun probeAudioOrVideo(file: File, mimeTypeHint: String?): MediaFacts? {
        val container = readContainer(file) ?: return null
        val tracks = readTracks(file)

        if (container.hasVideo) {
            val mimeType = mimeTypeOf(VIDEO, container.mimeType, mimeTypeHint)
            return videoFacts(container, tracks, mimeType)
        }
        if (container.hasAudio) {
            val mimeType = mimeTypeOf(AUDIO, container.mimeType, mimeTypeHint)
            return audioFacts(container, tracks, mimeType)
        }
        return null
    }

    private fun readContainer(file: File): ContainerMetadata? {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.path)
                readContainer(retriever)
            }
        } catch (error: RuntimeException) {
            // setDataSource throws when the platform cannot parse the file: not audio or video
            null
        }
    }

    private fun readContainer(retriever: MediaMetadataRetriever): ContainerMetadata {
        fun text(key: Int): String? = retriever.extractMetadata(key)

        return ContainerMetadata(
            hasVideo = text(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            hasAudio = text(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            mimeType = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            durationMs = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            width = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
            height = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
            rotationDegrees = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull(),
            frameCount = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull(),
            captureFrameRate = text(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull(),
        )
    }

    private fun readTracks(file: File): TrackMetadata {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return readTracks(extractor)
        } catch (error: IOException) {
            return UNKNOWN_TRACKS
        } catch (error: RuntimeException) {
            // A malformed track format (a numeric key stored as text) is no reason to reject the file
            return UNKNOWN_TRACKS
        } finally {
            extractor.release()
        }
    }

    /** The first audio and the first video track, as MediaMetadataRetriever reads them too. */
    private fun readTracks(extractor: MediaExtractor): TrackMetadata {
        var audioFormat: MediaFormat? = null
        var videoFormat: MediaFormat? = null

        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue

            if (isType(mimeType, AUDIO) && audioFormat == null) {
                audioFormat = format
            }
            if (isType(mimeType, VIDEO) && videoFormat == null) {
                videoFormat = format
            }
        }

        return TrackMetadata(
            audioDurationUs = audioFormat?.getNumber(MediaFormat.KEY_DURATION)?.toLong(),
            videoDurationUs = videoFormat?.getNumber(MediaFormat.KEY_DURATION)?.toLong(),
            videoFrameRate = videoFormat?.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble(),
        )
    }

    /**
     * The container's type when it names the [topLevel] kind found; otherwise the hint's, else the
     * container's subtype under [topLevel] (an audio-only "video/mp4" is "audio/mp4").
     */
    private fun mimeTypeOf(topLevel: String, containerType: String?, hint: String?): String {
        if (containerType != null && isType(containerType, topLevel)) {
            return containerType
        }
        if (hint != null && isType(hint, topLevel)) {
            return hint
        }

        val subtype = containerType?.substringAfter('/', missingDelimiterValue = "")
        if (subtype.isNullOrEmpty()) {
            return "$topLevel/*"
        }
        return "$topLevel/$subtype"
    }

    private fun isType(mimeType: String, topLevel: String): Boolean =
        mimeType.startsWith("$topLevel/", ignoreCase = true)

    private companion object {
        const val VIDEO = "video"
        const val AUDIO = "audio"
        val UNKNOWN_TRACKS = TrackMetadata(audioDurationUs = null, videoDurationUs = null, videoFrameRate = null)
    }
}
