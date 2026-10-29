package com.danemadsen.curie.inference

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.MediaLimits
import com.danemadsen.curie.engine.media.RgbImage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pictures decoded the way the engine needs them: the platform's real decoder (native graphics),
 * EXIF orientation applied, and exactly the size the prompt's placeholders were counted for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoadingTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A JPEG of [width] x [height], blue with a red band along its top edge. */
    private fun writePhoto(name: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width, height / 4)
        graphics.dispose()

        val file = folder.newFile(name)
        ImageIO.write(image, "jpg", file)
        return file
    }

    private fun redAt(image: RgbImage, x: Int, y: Int): Int = channelAt(image, x, y, channel = 0)

    private fun blueAt(image: RgbImage, x: Int, y: Int): Int = channelAt(image, x, y, channel = 2)

    private fun channelAt(image: RgbImage, x: Int, y: Int, channel: Int): Int {
        val index = (y * image.width + x) * 3 + channel
        return image.pixels[index].toInt() and 0xFF
    }

    @Test
    fun aLargeSidewaysPhotoComesBackUprightAtExactlyThePromptsSize() {
        // Stored landscape; the camera says to turn it a quarter clockwise, so it is a portrait
        val file = writePhoto("sideways.jpg", width = 2_560, height = 1_712)
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()
        val uprightWidth = 1_712
        val uprightHeight = 2_560
        val target = MediaGeometry.imageResizeTarget(uprightWidth, uprightHeight, MediaLimits.MAX_IMAGE_TOKENS)

        // Big enough to be decoded reduced, which is the path that must land on the target itself
        val sampleSize = ImageLoading.sampleSize(2_560, 1_712, target.height * 2, target.width * 2)
        assertTrue(sampleSize > 1)

        val image = ImageLoading.load(file, uprightWidth, uprightHeight)

        assertEquals(target.width, image.width)
        assertEquals(target.height, image.height)

        // Turned clockwise, the band along the stored top edge runs down the right-hand side
        val middle = image.height / 2
        assertTrue(redAt(image, image.width - 2, middle) > 200)
        assertTrue(blueAt(image, image.width - 2, middle) < 60)
        assertTrue(blueAt(image, 1, middle) > 200)
        assertTrue(redAt(image, 1, middle) < 60)
    }

    @Test
    fun aSmallPhotoIsLeftAtItsOwnSizeForTheEngineToResize() {
        val file = writePhoto("small.jpg", width = 300, height = 200)

        val image = ImageLoading.load(file, uprightWidth = 300, uprightHeight = 200)

        assertEquals(300, image.width)
        assertEquals(200, image.height)
        assertTrue(redAt(image, 150, 10) > 200)
        assertTrue(blueAt(image, 150, 190) > 200)
    }
}
