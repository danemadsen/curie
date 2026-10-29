package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.PI
import kotlin.math.sin

/**
 * The phases of the harmonics that place the streamers ([streamers], one per order in
 * [CoronaLook.STREAMER_ORDERS]), bend the rays and make the limb unevenly bright.
 */
internal data class CoronaPhases(
    val streamers: Quad,
    val bend1: Float,
    val bend2: Float,
    val unevenness1: Float,
    val unevenness2: Float,
)

/**
 * How far each flowing noise pattern has moved outwards along the rays, in lattice rows. Each value stays in
 * [0, [CoronaLook.FLOW_PERIOD]), where the patterns repeat, so the offsets wrap without a visible jump.
 */
internal data class FilamentFlow(val plumes: Float, val fine: Float, val finer: Float, val finest: Float)

/** How bright each of the three Baily's beads is at the moment, as a share of its full brightness. */
internal data class BeadFlicker(val main: Float, val second: Float, val third: Float)

/** A value that sways around [rest] by up to [swing], once every [periodSeconds]. */
private class Sway(val rest: Float, val swing: Float, val periodSeconds: Double) {
    fun at(seconds: Double): Float {
        val cycles = seconds / periodSeconds
        val wave = sin(2.0 * PI * cycles)
        return rest + swing * wave.toFloat()
    }
}

/**
 * How the corona changes with time. Every harmonic phase sways back and forth at its own unrelated pace, so
 * the streamers wander, bend and swell over a minute or two without the corona ever turning as a whole.
 * The filaments meanwhile flow slowly outwards, like the solar wind.
 */
internal object CoronaShape {
    private val STREAMER_SWAYS = listOf(
        Sway(rest = 0.3f, swing = 0.6f, periodSeconds = 97.0),
        Sway(rest = 1.9f, swing = 0.9f, periodSeconds = 71.0),
        Sway(rest = 4.1f, swing = 1.2f, periodSeconds = 59.0),
        Sway(rest = 2.6f, swing = 1.6f, periodSeconds = 43.0),
    )
    private val BEND_SWAY_1 = Sway(rest = 0.7f, swing = 1.5f, periodSeconds = 83.0)
    private val BEND_SWAY_2 = Sway(rest = 2.2f, swing = 2f, periodSeconds = 61.0)
    private val UNEVENNESS_SWAY_1 = Sway(rest = 1.1f, swing = 1f, periodSeconds = 89.0)
    private val UNEVENNESS_SWAY_2 = Sway(rest = 3.7f, swing = 1.4f, periodSeconds = 53.0)

    /** Outward flow of each pattern, in lattice rows per second of shimmer time. */
    const val PLUME_FLOW_RATE = 0.015
    const val FINE_FLOW_RATE = 0.012
    const val FINER_FLOW_RATE = 0.02
    const val FINEST_FLOW_RATE = 0.035

    /** Each Baily's bead shimmers between this share of its brightness and all of it. */
    const val BEAD_FLICKER_FLOOR = 0.7f

    /** How many times per second of shimmer time a bead's brightness changes course. */
    private const val BEAD_FLICKER_RATE = 1.5

    /** The flicker repeats after this many changes, so its clock can wrap seamlessly. */
    private const val BEAD_FLICKER_PERIOD = 64f

    /** Each bead flickers to its own pattern. */
    private const val MAIN_BEAD_SEED = 404f
    private const val SECOND_BEAD_SEED = 505f
    private const val THIRD_BEAD_SEED = 606f

    /** The corona's shape after [driftSeconds] of drift time. */
    fun phasesAt(driftSeconds: Double): CoronaPhases {
        val streamerPhases = Quad(
            x = STREAMER_SWAYS[0].at(driftSeconds),
            y = STREAMER_SWAYS[1].at(driftSeconds),
            z = STREAMER_SWAYS[2].at(driftSeconds),
            w = STREAMER_SWAYS[3].at(driftSeconds),
        )

        return CoronaPhases(
            streamers = streamerPhases,
            bend1 = BEND_SWAY_1.at(driftSeconds),
            bend2 = BEND_SWAY_2.at(driftSeconds),
            unevenness1 = UNEVENNESS_SWAY_1.at(driftSeconds),
            unevenness2 = UNEVENNESS_SWAY_2.at(driftSeconds),
        )
    }

    /** The filaments' outward flow after [shimmerSeconds] of shimmer time. */
    fun flowAt(shimmerSeconds: Double): FilamentFlow = FilamentFlow(
        plumes = flowOffset(shimmerSeconds, PLUME_FLOW_RATE),
        fine = flowOffset(shimmerSeconds, FINE_FLOW_RATE),
        finer = flowOffset(shimmerSeconds, FINER_FLOW_RATE),
        finest = flowOffset(shimmerSeconds, FINEST_FLOW_RATE),
    )

    /** The three Baily's beads' shimmering brightness factors after [shimmerSeconds], each in [0.7, 1]. */
    fun beadFlickerAt(shimmerSeconds: Double): BeadFlicker {
        val steps = (shimmerSeconds * BEAD_FLICKER_RATE).mod(BEAD_FLICKER_PERIOD.toDouble()).toFloat()
        return BeadFlicker(
            main = flicker(steps, MAIN_BEAD_SEED),
            second = flicker(steps, SECOND_BEAD_SEED),
            third = flicker(steps, THIRD_BEAD_SEED),
        )
    }

    private fun flicker(steps: Float, seed: Float): Float {
        val wave = CoronaNoise.valueNoise1D(steps, BEAD_FLICKER_PERIOD, seed)
        return mix(BEAD_FLICKER_FLOOR, 1f, wave)
    }

    private fun flowOffset(seconds: Double, rowsPerSecond: Double): Float {
        val rows = seconds * rowsPerSecond
        val period = CoronaLook.FLOW_PERIOD.toDouble()
        return rows.mod(period).toFloat()
    }
}
