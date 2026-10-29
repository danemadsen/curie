package com.danemadsen.curie.ui.call.eclipse

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * The fallback renderer's gradients, built once per size and centred on the origin: the placeholder corona,
 * the inner corona's boost, and the Baily's beads with their glare and diffraction spikes. Their profiles come
 * from [CoronaModel], in lunar radii of [moonRadius] pixels.
 */
internal class FallbackBrushes(private val moonRadius: Float) {
    /**
     * A soft gradient eclipse: at each height, the rendered corona's colour averaged all round the limb, so that
     * fading from one to the other only brings in the streamers' shape.
     */
    val placeholder = radialBrush(PLACEHOLDER_EXTENT, fadeEdge = false) { radius ->
        val height = max(radius - 1f, 0f)
        val radiusFraction = radius / PLACEHOLDER_EXTENT
        val fade = CoronaModel.boundsFade(radiusFraction)
        val average = averageCoronaDisplay(height)
        lightOverTransparency(average.red * fade, average.green * fade, average.blue * fade)
    }

    /** Extra light on the inner corona, a share of the inner corona's own. */
    val innerBoost = radialBrush(INNER_BOOST_EXTENT) { radius ->
        val height = max(radius - 1f, 0f)
        val light = CoronaModel.innerCoronaLight(height) * INNER_BOOST_LIGHT
        white(CoronaModel.toDisplay(light))
    }

    /**
     * The glare round the main bead. Screened over a corona of typical brightness at the same distance, it lifts
     * it to what the shader shows for the two lights added before tone mapping: Screen gives `d + s (1 - d)`,
     * so `s` is the rise from `d` to the target over the headroom `1 - d`.
     */
    val glare = radialBrush(CoronaLook.GLINT_REACH) { distance ->
        val glareLight = CoronaModel.glareLight(distance) * CoronaModel.glintWindow(distance)
        val corona = CoronaModel.innerCoronaLight(distance) + CoronaModel.backgroundLight(distance)
        val withoutGlare = CoronaModel.toDisplay(corona)
        val withGlare = CoronaModel.toDisplay(corona + glareLight)
        val headroom = 1f - withoutGlare
        val opacity = if (headroom > 0f) (withGlare - withoutGlare) / headroom else 1f
        white(opacity)
    }

    /**
     * One bead, round: its radial profile in every direction. Drawn stretched by [BEAD_STRETCH] along the limb,
     * it becomes the elongated spot of [CoronaModel.beadSpotLight].
     */
    val beadSpot = radialBrush(BEAD_SPOT_EXTENT) { radial ->
        val light = CoronaModel.beadSpotLight(radial = radial, tangential = 0f)
        white(CoronaModel.toDisplay(light))
    }

    /** One diffraction spike ray, from the bead at the origin along the positive x axis. */
    val spikeRay: Brush = run {
        val stops = Array(STOP_COUNT + 1) { index ->
            val fraction = index / STOP_COUNT.toFloat()
            val distance = fraction * CoronaLook.GLINT_REACH
            val light = CoronaModel.spikeLight(distance) * CoronaModel.glintWindow(distance)
            fraction to white(CoronaModel.toDisplay(light))
        }
        val end = Offset(CoronaLook.GLINT_REACH * moonRadius, 0f)
        Brush.linearGradient(*stops, start = Offset.Zero, end = end)
    }

    /**
     * A radial gradient reaching [extent] lunar radii from the origin, whose colour at each distance (in lunar
     * radii) is [colorAt]. With [fadeEdge] its outer part fades to nothing, so the circle it is drawn in never
     * shows; without, [colorAt] must already reach nothing at the extent.
     */
    private fun radialBrush(extent: Float, fadeEdge: Boolean = true, colorAt: (Float) -> Color): Brush {
        val stops = Array(STOP_COUNT + 1) { index ->
            val fraction = index / STOP_COUNT.toFloat()
            val edgeFade = if (fadeEdge) 1f - smoothStep(EDGE_FADE_START, 1f, fraction) else 1f
            val color = colorAt(fraction * extent)
            val fadedColor = color.copy(alpha = saturate(color.alpha) * edgeFade)
            fraction to fadedColor
        }
        return Brush.radialGradient(*stops, center = Offset.Zero, radius = extent * moonRadius)
    }

    private fun white(opacity: Float): Color = Color(red = 1f, green = 1f, blue = 1f, alpha = saturate(opacity))

    /** The resting corona's display colour at [height], averaged over evenly spaced angles round the limb. */
    private fun averageCoronaDisplay(height: Float): Rgb {
        val phases = CoronaShape.phasesAt(driftSeconds = 0.0)
        val flow = CoronaShape.flowAt(shimmerSeconds = 0.0)
        val restingGains = CoronaGains()
        val outerShare = CoronaModel.outerTintShare(height)
        val inner = CoronaLook.INNER_TINT
        val outer = CoronaLook.OUTER_TINT
        val redTint = mix(inner.red, outer.red, outerShare)
        val greenTint = mix(inner.green, outer.green, outerShare)
        val blueTint = mix(inner.blue, outer.blue, outerShare)

        var red = 0f
        var green = 0f
        var blue = 0f
        for (step in 0 until PLACEHOLDER_SAMPLES) {
            val theta = (step + 0.5f) * TAU / PLACEHOLDER_SAMPLES
            val light = CoronaModel.coronaLight(theta, height, phases, flow, restingGains)
            red += CoronaModel.toDisplay(light * redTint)
            green += CoronaModel.toDisplay(light * greenTint)
            blue += CoronaModel.toDisplay(light * blueTint)
        }
        return Rgb(red / PLACEHOLDER_SAMPLES, green / PLACEHOLDER_SAMPLES, blue / PLACEHOLDER_SAMPLES)
    }

    /** Display values as light over transparency: just opaque enough to hold the brightest channel. */
    private fun lightOverTransparency(red: Float, green: Float, blue: Float): Color {
        val alpha = max(red, max(green, blue))
        if (alpha <= 0f) {
            return Color.Transparent
        }
        return Color(red = red / alpha, green = green / alpha, blue = blue / alpha, alpha = saturate(alpha))
    }

    companion object {
        /** The placeholder reaches the circle inscribed in the bounds, where the corona's own fade ends. */
        const val PLACEHOLDER_EXTENT = 1f / (2f * CoronaLook.MOON_RADIUS_FRACTION)

        /** Angles averaged for each of the placeholder's stops: about two thousand model samples in all. */
        private const val PLACEHOLDER_SAMPLES = 32
        const val INNER_BOOST_EXTENT = 1.6f

        /** The bead spot's gradient reaches four of its radial sizes, where its light is negligible. */
        const val BEAD_SPOT_EXTENT = 4f * CoronaLook.BEAD_RADIAL_SIZE

        /** How much longer a bead is along the limb than across it. */
        const val BEAD_STRETCH = CoronaLook.BEAD_TANGENTIAL_SIZE / CoronaLook.BEAD_RADIAL_SIZE

        /** The inner corona's boost has this share of the inner corona's own light. */
        const val INNER_BOOST_LIGHT = 0.35f

        /** Every radial gradient fades out over the outer part of its extent, from this fraction of it. */
        private const val EDGE_FADE_START = 0.6f

        /** Enough stops that the steep profiles near the limb show no bands. */
        private const val STOP_COUNT = 64
    }
}
