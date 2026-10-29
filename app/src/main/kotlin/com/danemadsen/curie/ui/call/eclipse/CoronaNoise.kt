package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.floor

/**
 * The hash and value noise behind the corona's fine texture. The AGSL shader has the same functions
 * (`hash11`, `valueNoise`, `valueNoise1` in [EclipseShaderSource]) with the same constants, and both use
 * 32-bit floats.
 */
internal object CoronaNoise {
    /**
     * Lattice rows are folded into one hash input as `column + row * ROW_STRIDE`. The stride is larger than
     * every period used along x, so no two cells of one pattern share an input.
     */
    const val ROW_STRIDE = 1009f

    /** A pseudo-random value in [0, 1) for [input] (Dave Hoskins' "hash without sine"). */
    fun hash(input: Float): Float {
        var value = fract(input * 0.1031f)
        value *= value + 33.33f
        value *= value + value
        return fract(value)
    }

    /**
     * Smooth value noise in [0, 1]. It repeats every [periodX] cells along x and every [periodY] cells along
     * y (whole numbers), so an angle can run once round the circle and a clock can wrap without a seam.
     * [seed] picks an independent pattern.
     */
    fun valueNoise(x: Float, y: Float, periodX: Float, periodY: Float, seed: Float): Float {
        val cellX = floor(x)
        val cellY = floor(y)
        val weightX = smoothCurve(x - cellX)
        val weightY = smoothCurve(y - cellY)

        val leftColumn = floorMod(cellX, periodX)
        val rightColumn = floorMod(cellX + 1f, periodX)
        val lowerRow = floorMod(cellY, periodY) * ROW_STRIDE + seed
        val upperRow = floorMod(cellY + 1f, periodY) * ROW_STRIDE + seed

        val lowerLeft = hash(leftColumn + lowerRow)
        val lowerRight = hash(rightColumn + lowerRow)
        val upperLeft = hash(leftColumn + upperRow)
        val upperRight = hash(rightColumn + upperRow)

        val lower = mix(lowerLeft, lowerRight, weightX)
        val upper = mix(upperLeft, upperRight, weightX)
        return mix(lower, upper, weightY)
    }

    /** Smooth value noise in [0, 1] along one axis, repeating every [period] cells (a whole number). */
    fun valueNoise1D(x: Float, period: Float, seed: Float): Float {
        val cell = floor(x)
        val weight = smoothCurve(x - cell)

        val left = hash(floorMod(cell, period) + seed)
        val right = hash(floorMod(cell + 1f, period) + seed)
        return mix(left, right, weight)
    }
}
