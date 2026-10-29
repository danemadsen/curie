package com.danemadsen.curie.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.danemadsen.curie.data.Attachment
import com.danemadsen.curie.engine.media.ImageResizer
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.MediaLimits
import com.danemadsen.curie.engine.media.RgbImage
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.VideoInput
import java.io.File
import java.io.IOException

/** Makes the engine's media inputs of stored attachments; the media key is the attachment's id. */
interface MediaInputFactory {
    fun image(attachment: Attachment): ImageInput

    fun video(attachment: Attachment): VideoInput

    fun audio(attachment: Attachment): AudioInput
}

/**
 * Media inputs decoded with the platform: images with BitmapFactory (EXIF orientation applied),
 * video frames with MediaMetadataRetriever, audio and soundtracks with MediaExtractor and
 * MediaCodec ([AudioDecoding]). Nothing is decoded until the engine asks for it, on its thread.
 */
class AndroidMediaInputs : MediaInputFactory {
    override fun image(attachment: Attachment): ImageInput {
        val file = File(attachment.path)
        return ImageInput(attachment.id, attachment.width, attachment.height) {
            ImageLoading.load(file, attachment.width, attachment.height)
        }
    }

    override fun video(attachment: Attachment): VideoInput {
        val file = File(attachment.path)
        return VideoInput(
            key = attachment.id,
            width = attachment.width,
            height = attachment.height,
            frameCount = attachment.frameCount,
            fps = attachment.fps,
            soundtrackSamples = attachment.sampleCount16k,
            loadFrames = { indices -> VideoFrames.load(file, attachment, indices) },
            loadSoundtrack = { AudioDecoding.decode16kMono(file, attachment.sampleCount16k) },
        )
    }

    override fun audio(attachment: Attachment): AudioInput {
        val file = File(attachment.path)
        return AudioInput(attachment.id, attachment.sampleCount16k) {
            AudioDecoding.decode16kMono(file, attachment.sampleCount16k)
        }
    }
}

/** Still images, decoded no larger than needed. */
internal object ImageLoading {
    /** Decoded images keep at least this many times the resize target per side, so it still downsamples well. */
    private const val OVERSAMPLING = 2

    /**
     * The image in [file] upright, for an attachment of [uprightWidth] x [uprightHeight]. Decoded
     * at full size it is returned as is (the engine resizes it as the reference processor does);
     * decoded smaller (a large photo), it is resized here to the exact target the prompt was built
     * for, which the engine then keeps.
     */
    fun load(file: File, uprightWidth: Int, uprightHeight: Int): RgbImage {
        val target = MediaGeometry.imageResizeTarget(uprightWidth, uprightHeight, MediaLimits.MAX_IMAGE_TOKENS)
        val orientation = exifOrientation(file)
        val isQuarterTurn = isQuarterTurn(orientation)

        // The stored image is sideways when the EXIF orientation turns it a quarter
        val storedWidth = if (isQuarterTurn) uprightHeight else uprightWidth
        val storedHeight = if (isQuarterTurn) uprightWidth else uprightHeight
        val storedTargetWidth = if (isQuarterTurn) target.height else target.width
        val storedTargetHeight = if (isQuarterTurn) target.width else target.height
        val sampleSize = sampleSize(
            width = storedWidth,
            height = storedHeight,
            minimumWidth = storedTargetWidth * OVERSAMPLING,
            minimumHeight = storedTargetHeight * OVERSAMPLING,
        )

        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("cannot decode ${file.name}")
        val upright = applyOrientation(decoded, orientation)
        val image = BitmapPixels.toRgb(upright)
        upright.recycle()

        val isFullSize = image.width == uprightWidth && image.height == uprightHeight
        if (isFullSize) {
            return image
        }
        return ImageResizer.resize(image, target.width, target.height)
    }

    /** The largest power of two that keeps the decoded image at least [minimumWidth] x [minimumHeight]. */
    fun sampleSize(width: Int, height: Int, minimumWidth: Int, minimumHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= minimumWidth && height / (sampleSize * 2) >= minimumHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun isQuarterTurn(orientation: Int): Boolean = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
        -> true
        else -> false
    }

    /** The matrix that turns a stored image of [orientation] upright; null when it already is. */
    fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return null
        }
        return matrix
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = orientationMatrix(orientation) ?: return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun exifOrientation(file: File): Int {
        return try {
            val exif = ExifInterface(file)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (error: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}

/** Sampled frames of a video, all at one size no larger than needed. */
internal object VideoFrames {
    /** Frames keep at least this many times the resize target per side. */
    private const val OVERSAMPLING = 2

    fun load(file: File, attachment: Attachment, indices: IntArray): List<RgbImage> {
        val sampledFrames = MediaGeometry.videoFrameIndices(attachment.frameCount, attachment.fps, MediaLimits.MAX_VIDEO_FRAMES)
        val target = MediaGeometry.videoResizeTarget(
            width = attachment.width,
            height = attachment.height,
            sampledFrames = sampledFrames.size,
            maxTokens = MediaLimits.MAX_VIDEO_TOKENS,
        )
        val frameWidth = minOf(attachment.width, target.width * OVERSAMPLING)
        val frameHeight = minOf(attachment.height, target.height * OVERSAMPLING)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotationDegrees = rotation?.toIntOrNull() ?: 0
            val countText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val knowsFrameCount = (countText?.toIntOrNull() ?: 0) > 0

            val frames = ArrayList<RgbImage>(indices.size)
            for (index in indices) {
                val frame = frameAt(retriever, index, attachment.fps, knowsFrameCount)
                    ?: throw IOException("cannot decode frame $index of ${file.name}")
                val upright = uprightFrame(frame, attachment.width, attachment.height, rotationDegrees)
                val scaled = BitmapPixels.downscale(upright, frameWidth, frameHeight)
                val image = BitmapPixels.toRgb(scaled)
                scaled.recycle()
                frames.add(image)
            }
            return frames
        } catch (error: RuntimeException) {
            throw IOException("cannot read the frames of ${file.name}", error)
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, index: Int, fps: Double, knowsFrameCount: Boolean): Bitmap? {
        if (knowsFrameCount) {
            val frame = try {
                retriever.getFrameAtIndex(index)
            } catch (error: IllegalArgumentException) {
                null
            } catch (error: IllegalStateException) {
                null
            }
            if (frame != null) {
                return frame
            }
        }

        // Without a frame count the index is a time: frame i of a constant-rate clip shows at i / fps
        val timeUs = (index / fps * MICROSECONDS_PER_SECOND).toLong()
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    /**
     * The retriever applies the video's rotation on the phones checked; a frame that still comes
     * back sideways (its orientation disagrees with the upright size) is turned here.
     */
    private fun uprightFrame(frame: Bitmap, uprightWidth: Int, uprightHeight: Int, rotationDegrees: Int): Bitmap {
        val isUprightLandscape = uprightWidth > uprightHeight
        val isFrameLandscape = frame.width > frame.height
        val isSquare = uprightWidth == uprightHeight || frame.width == frame.height
        if (isSquare || isUprightLandscape == isFrameLandscape || rotationDegrees % 180 == 0) {
            return frame
        }

        val matrix = Matrix()
        matrix.setRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        if (rotated !== frame) {
            frame.recycle()
        }
        return rotated
    }

    private const val MICROSECONDS_PER_SECOND = 1_000_000.0
}

/** Bitmap pixels as the engine's RGB images. */
internal object BitmapPixels {
    /** [bitmap] scaled to [width] x [height] (if it is larger), halving at most at each step so it stays smooth. */
    fun downscale(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        var current = bitmap
        while (current.width > width || current.height > height) {
            val nextWidth = maxOf(width, current.width / 2)
            val nextHeight = maxOf(height, current.height / 2)
            val scaled = current.scale(nextWidth, nextHeight, filter = true)
            if (current !== bitmap && current !== scaled) {
                current.recycle()
            }
            current = scaled
        }
        return current
    }

    fun toRgb(bitmap: Bitmap): RgbImage {
        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = argbBitmap.width
        val height = argbBitmap.height
        val row = IntArray(width)
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) {
            argbBitmap.getPixels(row, 0, width, 0, y, width, 1)
            argbToRgb(row, pixels, y * width * 3)
        }
        if (argbBitmap !== bitmap) {
            argbBitmap.recycle()
        }
        return RgbImage(width, height, pixels)
    }

    /**
     * Writes the RGB bytes of [argb] pixels (non-premultiplied, as Bitmap.getPixels returns them)
     * into [rgb] from [offset]. Translucent pixels are composited over white. The reference
     * processor's PIL convert("RGB") drops alpha instead, which shows a transparent PNG's hidden
     * colours (often black); white matches what the user saw in the chat.
     */
    fun argbToRgb(argb: IntArray, rgb: ByteArray, offset: Int) {
        var target = offset
        for (pixel in argb) {
            val alpha = pixel ushr 24
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            rgb[target] = overWhite(red, alpha).toByte()
            rgb[target + 1] = overWhite(green, alpha).toByte()
            rgb[target + 2] = overWhite(blue, alpha).toByte()
            target += 3
        }
    }

    internal fun overWhite(channel: Int, alpha: Int): Int {
        if (alpha == 0xFF) {
            return channel
        }
        val blended = channel * alpha + 0xFF * (0xFF - alpha)
        return (blended + 127) / 0xFF
    }
}
