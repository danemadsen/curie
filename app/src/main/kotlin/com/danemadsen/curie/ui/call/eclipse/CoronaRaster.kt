package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The fallback renderer's still layers, squares of [size] pixels in ARGB (not premultiplied, the format of
 * `Bitmap.createBitmap(int[], …)`), each light over transparency:
 *
 * - [dimEclipse] and [brightEclipse]: the sky, the corona and the stars at the lowest and the highest exposure
 *   a frame can have ([EclipseMotion.LOWEST_EXPOSURE], [EclipseMotion.HIGHEST_EXPOSURE]). Mixing the two gives
 *   any exposure in between while the bright inner corona stays white, as it does in the shader.
 * - [loudStreamers]: the light the helmet streamers gain while Curie speaks at her loudest
 *   ([EclipseMotion.LOUDEST_VOICE_GAINS]), to be added in proportion to her voice.
 *
 * Under the Moon the layers keep the limb's light, so smoothing them while scaling up never darkens the limb
 * (the Moon is drawn over them).
 */
internal class CoronaLayers(val size: Int) {
    val dimEclipse = IntArray(size * size)
    val brightEclipse = IntArray(size * size)
    val loudStreamers = IntArray(size * size)
}

/**
 * Renders [CoronaLayers] on the CPU from the same model as the shader. Rows are rendered in bands, so callers
 * can spread the work over threads and stop between bands.
 */
internal object CoronaRaster {
    private val RESTING_GAINS = CoronaGains()

    /** Light this faint shows as less than half an 8-bit step, so a layer holding only that stays transparent. */
    private const val INVISIBLE_LIGHT = 1e-6f

    /** Fills [rows] of every layer in [layers] with the corona shaped by [phases] and [flow]. */
    fun renderRows(layers: CoronaLayers, rows: IntRange, phases: CoronaPhases, flow: FilamentFlow) {
        val size = layers.size
        val half = size / 2f
        val moonRadiusPx = size * CoronaLook.MOON_RADIUS_FRACTION

        for (row in rows) {
            val offsetY = row + 0.5f - half
            for (column in 0 until size) {
                val index = row * size + column
                val offsetX = column + 0.5f - half
                val x = offsetX / moonRadiusPx
                val y = -offsetY / moonRadiusPx
                val radius = sqrt(x * x + y * y)

                val radiusFraction = radius * moonRadiusPx / half
                val fade = CoronaModel.boundsFade(radiusFraction)
                if (fade <= 0f) {
                    layers.dimEclipse[index] = TRANSPARENT
                    layers.brightEclipse[index] = TRANSPARENT
                    layers.loudStreamers[index] = TRANSPARENT
                    continue
                }

                val height = max(radius - 1f, 0f)
                val theta = atan2(y, x)
                val parts = CoronaModel.coronaParts(theta, height, phases, flow)
                val restingCorona = CoronaModel.lightOf(parts, RESTING_GAINS)
                val loudCorona = CoronaModel.lightOf(parts, EclipseMotion.LOUDEST_VOICE_GAINS)

                val pixel = PixelLight(
                    outerTintShare = CoronaModel.outerTintShare(height),
                    skyVignette = CoronaModel.skyVignette(radiusFraction),
                    skyGlow = CoronaModel.skyGlow(height),
                    star = CoronaModel.starLight(x, y, moonRadiusPx, twinklePhase = 0f),
                    fade = fade,
                    dither = ditherOffset(column, row),
                )
                // The exposure scales all the corona's light, so it is applied to the resting light directly.
                val dimCorona = restingCorona * EclipseMotion.LOWEST_EXPOSURE
                val brightCorona = restingCorona * EclipseMotion.HIGHEST_EXPOSURE
                val extraStreamerLight = loudCorona - restingCorona

                layers.dimEclipse[index] = pixel.eclipse(dimCorona)
                layers.brightEclipse[index] = pixel.eclipse(brightCorona)
                layers.loudStreamers[index] = pixel.coronaOnly(extraStreamerLight)
            }
        }
    }

    /** Everything about one pixel's light except the corona's, which differs from layer to layer. */
    private class PixelLight(
        val outerTintShare: Float,
        val skyVignette: Float,
        val skyGlow: Float,
        val star: Float,
        val fade: Float,
        val dither: Float,
    ) {
        /** The tinted [corona] with the sky and the star, as an ARGB pixel. */
        fun eclipse(corona: Float): Int {
            val red = channel(corona, Channel.RED, withSky = true)
            val green = channel(corona, Channel.GREEN, withSky = true)
            val blue = channel(corona, Channel.BLUE, withSky = true)
            return lightOverTransparency(red, green, blue)
        }

        /** The tinted [corona] alone, as an ARGB pixel. */
        fun coronaOnly(corona: Float): Int {
            if (corona < INVISIBLE_LIGHT) {
                return TRANSPARENT
            }

            val red = channel(corona, Channel.RED, withSky = false)
            val green = channel(corona, Channel.GREEN, withSky = false)
            val blue = channel(corona, Channel.BLUE, withSky = false)
            return lightOverTransparency(red, green, blue)
        }

        /** One display channel: linear light tone-mapped, faded towards the bounds and dithered. */
        private fun channel(corona: Float, channel: Channel, withSky: Boolean): Float {
            val inner = component(CoronaLook.INNER_TINT, channel)
            val outer = component(CoronaLook.OUTER_TINT, channel)
            val coronaLight = corona * mix(inner, outer, outerTintShare)

            val light = if (withSky) {
                val sky = component(CoronaLook.SKY, channel) * skyVignette
                val glow = component(CoronaLook.SKY_GLOW, channel) * skyGlow
                val starLight = component(CoronaLook.STAR_TINT, channel) * star
                coronaLight + sky + glow + starLight
            } else {
                coronaLight
            }
            return CoronaModel.toDisplay(light) * fade + dither
        }
    }

    private enum class Channel { RED, GREEN, BLUE }

    private fun component(rgb: Rgb, channel: Channel): Float = when (channel) {
        Channel.RED -> rgb.red
        Channel.GREEN -> rgb.green
        Channel.BLUE -> rgb.blue
    }

    /** The shader's interleaved gradient noise, scaled to ±half an 8-bit step. */
    private fun ditherOffset(column: Int, row: Int): Float {
        val pixelX = column + 0.5f
        val pixelY = row + 0.5f
        val gradient = fract(52.9829189f * fract(0.06711056f * pixelX + 0.00583715f * pixelY))
        return (gradient - 0.5f) * CoronaLook.DITHER_AMPLITUDE
    }

    /**
     * Light shown over whatever lies beneath: just opaque enough to hold its brightest channel, so its
     * premultiplied colour is the light itself, and black is transparent.
     */
    private fun lightOverTransparency(red: Float, green: Float, blue: Float): Int {
        val alpha = max(red, max(green, blue))
        if (alpha * 255f < 0.5f) {
            return TRANSPARENT
        }

        val quantizedAlpha = (alpha.coerceAtMost(1f) * 255f).roundToInt() / 255f
        return argb(quantizedAlpha, red / quantizedAlpha, green / quantizedAlpha, blue / quantizedAlpha)
    }

    private fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int {
        val alphaByte = toByte(alpha)
        val redByte = toByte(red)
        val greenByte = toByte(green)
        val blueByte = toByte(blue)
        return (alphaByte shl 24) or (redByte shl 16) or (greenByte shl 8) or blueByte
    }

    private fun toByte(value: Float): Int = (saturate(value) * 255f).roundToInt()

    private const val TRANSPARENT = 0
}
