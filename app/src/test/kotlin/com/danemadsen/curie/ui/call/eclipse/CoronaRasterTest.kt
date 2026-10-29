package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CoronaRasterTest {
    private val size = 192
    private val phases = CoronaShape.phasesAt(driftSeconds = 0.0)
    private val flow = CoronaShape.flowAt(shimmerSeconds = 0.0)
    private val layers = renderLayers()
    private val moonRadiusPx = size * CoronaLook.MOON_RADIUS_FRACTION
    private val centre = size / 2

    @Test
    fun theEclipseIsBrightAtTheLimb() {
        val justOutsideLimb = centre + (moonRadiusPx * 1.03f).roundToInt()

        for (pixels in listOf(layers.dimEclipse, layers.brightEclipse)) {
            val limbAlpha = alphaOf(pixelAt(pixels, justOutsideLimb, centre))
            assertTrue("alpha $limbAlpha just outside the limb", limbAlpha > 200)
        }
    }

    /** Nothing reaches the bounds' edges, so they never show on any background. */
    @Test
    fun everyLayerIsTransparentOutsideTheInscribedCircle() {
        val half = size / 2f
        val allLayers = listOf(layers.dimEclipse, layers.brightEclipse, layers.loudStreamers)

        for (row in 0 until size) {
            for (column in 0 until size) {
                val offsetX = column + 0.5f - half
                val offsetY = row + 0.5f - half
                val distance = sqrt(offsetX * offsetX + offsetY * offsetY)
                if (distance >= half) {
                    for (pixels in allLayers) {
                        assertEquals("pixel ($column, $row)", 0, alphaOf(pixelAt(pixels, column, row)))
                    }
                }
            }
        }
    }

    @Test
    fun theEclipseFadesOutwardsAlongEveryAxis() {
        val radii = listOf(1.05f, 1.5f, 2.1f)

        for (direction in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val alphas = radii.map { radius ->
                val distance = (radius * moonRadiusPx).roundToInt()
                val column = centre + direction.first * distance
                val row = centre + direction.second * distance
                alphaOf(pixelAt(layers.brightEclipse, column, row))
            }
            assertTrue("direction $direction: $alphas", alphas[0] > alphas[1] && alphas[1] > alphas[2])
        }
    }

    @Test
    fun theLayersKeepTheLimbLightUnderTheMoon() {
        assertTrue(alphaOf(pixelAt(layers.dimEclipse, centre, centre)) > 240)
        assertTrue(alphaOf(pixelAt(layers.brightEclipse, centre, centre)) > 240)
    }

    /** Mixing the two renderings can only give exposures in between if the bright one is never darker. */
    @Test
    fun theBrightRenderingIsNeverDarkerThanTheDimOne() {
        for (index in layers.dimEclipse.indices) {
            val dim = brightestOverBlack(layers.dimEclipse[index])
            val bright = brightestOverBlack(layers.brightEclipse[index])

            assertTrue("pixel $index: dim $dim, bright $bright", bright >= dim - 1f)
        }
    }

    /** Curie's voice adds light to the streamers along the tilted equator, hardly any at the poles. */
    @Test
    fun theVoiceLayerLightsTheStreamersNotThePoles() {
        val equatorLight = meanAlongDirection(layers.loudStreamers, CoronaLook.EQUATOR_TILT)
        val oppositeLight = meanAlongDirection(layers.loudStreamers, CoronaLook.EQUATOR_TILT + PI_F)
        val poleAngle = CoronaLook.EQUATOR_TILT + PI_F / 2f
        val poleLight = meanAlongDirection(layers.loudStreamers, poleAngle)

        val streamerLight = (equatorLight + oppositeLight) / 2f
        assertTrue("streamers $streamerLight, pole $poleLight", streamerLight > 4f * poleLight + 1f)
    }

    @Test
    fun renderingIsDeterministicAndBandsMatchOnePass() {
        val inBands = CoronaLayers(size)
        CoronaRaster.renderRows(inBands, 0 until 77, phases, flow)
        CoronaRaster.renderRows(inBands, 77 until size, phases, flow)
        val again = renderLayers()

        assertArrayEquals(layers.dimEclipse, again.dimEclipse)
        assertArrayEquals(layers.dimEclipse, inBands.dimEclipse)
        assertArrayEquals(layers.brightEclipse, inBands.brightEclipse)
        assertArrayEquals(layers.loudStreamers, inBands.loudStreamers)
    }

    /** Shown over black, every layer is white, bluish or faintly warm: red never leads green or blue by much. */
    @Test
    fun noLayerIsEverRedOrPink() {
        val allLayers = listOf(layers.dimEclipse, layers.brightEclipse, layers.loudStreamers)

        for (pixels in allLayers) {
            for (pixel in pixels) {
                val red = levelOverBlack(pixel, RED_SHIFT)
                val green = levelOverBlack(pixel, GREEN_SHIFT)
                val blue = levelOverBlack(pixel, BLUE_SHIFT)
                val redExcess = red - minOf(green, blue)

                assertTrue("red leads by $redExcess in ${Integer.toHexString(pixel)}", redExcess <= MAX_RED_EXCESS)
            }
        }
    }

    private fun renderLayers(): CoronaLayers {
        val rendered = CoronaLayers(size)
        CoronaRaster.renderRows(rendered, 0 until size, phases, flow)
        return rendered
    }

    /** The mean brightest channel over black along the ray at [angle], from 1.2 to 2 lunar radii. */
    private fun meanAlongDirection(pixels: IntArray, angle: Float): Float {
        var total = 0f
        val samples = 20
        for (step in 0 until samples) {
            val radius = 1.2f + 0.8f * step / samples
            val column = (centre + radius * moonRadiusPx * cos(angle)).roundToInt()
            val row = (centre - radius * moonRadiusPx * sin(angle)).roundToInt()
            total += brightestOverBlack(pixelAt(pixels, column, row))
        }
        return total / samples
    }

    private fun pixelAt(pixels: IntArray, column: Int, row: Int): Int = pixels[row * size + column]

    private fun alphaOf(pixel: Int): Int = (pixel ushr 24) and 0xFF

    /** One channel of an unpremultiplied ARGB [pixel] as it shows over black, in 8-bit levels. */
    private fun levelOverBlack(pixel: Int, shift: Int): Float {
        val channel = (pixel ushr shift) and 0xFF
        return channel * alphaOf(pixel) / 255f
    }

    private fun brightestOverBlack(pixel: Int): Float {
        val red = levelOverBlack(pixel, RED_SHIFT)
        val green = levelOverBlack(pixel, GREEN_SHIFT)
        val blue = levelOverBlack(pixel, BLUE_SHIFT)
        return maxOf(red, green, blue)
    }

    private companion object {
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BLUE_SHIFT = 0
        const val PI_F = PI.toFloat()

        /** The outer corona's faint warmth makes red lead blue by about 6 levels at most; pink leads by 50+. */
        const val MAX_RED_EXCESS = 8f
    }
}
