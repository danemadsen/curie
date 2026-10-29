package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.PI
import kotlin.math.floor

/*
 * The handful of GLSL built-ins the corona model needs, with the same semantics, so the CPU model reads like
 * the AGSL shader it mirrors.
 */

internal const val TAU = (2.0 * PI).toFloat()

/** The fractional part, always in [0, 1), also for negative values (GLSL `fract`). */
internal fun fract(value: Float): Float = value - floor(value)

/** The remainder with the sign of [period], so it is in [0, period) (GLSL `mod`). */
internal fun floorMod(value: Float, period: Float): Float = value - period * floor(value / period)

/** Linear interpolation from [start] at 0 to [end] at 1 (GLSL `mix`). */
internal fun mix(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

/** [value] limited to [0, 1] (GLSL `clamp(value, 0.0, 1.0)`). */
internal fun saturate(value: Float): Float = value.coerceIn(0f, 1f)

/** The cubic Hermite curve 3t² − 2t³ of a fraction already in [0, 1]. */
internal fun smoothCurve(fraction: Float): Float = fraction * fraction * (3f - 2f * fraction)

/**
 * 0 below [edge0], 1 beyond [edge1] and a smooth curve in between (GLSL `smoothstep`). As in GLSL, [edge0]
 * must be less than [edge1]: GPUs may return anything for reversed edges, so a falling curve is written as
 * `1 - smoothStep(low, high, value)` here and in the shader alike.
 */
internal fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    val fraction = saturate((value - edge0) / (edge1 - edge0))
    return smoothCurve(fraction)
}
