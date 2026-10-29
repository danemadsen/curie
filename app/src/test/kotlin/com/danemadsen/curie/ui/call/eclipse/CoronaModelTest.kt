package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class CoronaModelTest {
    private val phases = CoronaShape.phasesAt(driftSeconds = 0.0)
    private val flow = CoronaShape.flowAt(shimmerSeconds = 0.0)
    private val restingGains = CoronaGains()

    @Test
    fun innerCoronaFallsOffSteeplyFromTheLimb() {
        val atLimb = CoronaModel.innerCoronaLight(0f)
        val heights = listOf(0f, 0.02f, 0.05f, 0.1f, 0.2f, 0.5f, 1f)
        val profile = heights.map { height -> CoronaModel.innerCoronaLight(height) }

        for (index in 1 until profile.size) {
            assertTrue("not falling at ${heights[index]}: $profile", profile[index] < profile[index - 1])
        }
        assertTrue(CoronaModel.innerCoronaLight(0.1f) < 0.25f * atLimb)
        assertTrue(CoronaModel.innerCoronaLight(0.5f) < 0.01f * atLimb)
    }

    @Test
    fun coronaDimsWithHeightAllRoundTheLimb() {
        val heights = listOf(0f, 0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f)
        val averages = heights.map { height -> averageLight(height, fromAngle = 0f, toAngle = TAU) }

        for (index in 1 until averages.size) {
            assertTrue("not dimming at ${heights[index]}: $averages", averages[index] < averages[index - 1])
        }
        assertTrue(averages.last() < 0.02f * averages.first())
    }

    @Test
    fun streamersGatherAtTheTiltedEquator() {
        val equatorLight = equatorLight(height = 1f)
        val poleLight = poleLight(height = 1f)

        assertTrue("equator $equatorLight, poles $poleLight", equatorLight > 3f * poleLight)
    }

    /** The bow-tie of a solar-minimum corona: the poles fade into the sky long before the streamers do. */
    @Test
    fun polesFadeFarSoonerThanTheStreamers() {
        val poleOutline = outlineHeight { height -> poleLight(height) }
        val equatorOutline = outlineHeight { height -> equatorLight(height) }

        assertTrue("poles end at $poleOutline, streamers at $equatorOutline", equatorOutline > poleOutline + 0.4f)
    }

    /**
     * A clipped (flat) crest would never narrow with height and would show as a hard-edged bar, so however the
     * streamers drift, the strongest one peaks at a single angle.
     */
    @Test
    fun theStrongestStreamerAlwaysPeaksAtASingleAngle() {
        val samples = 3600
        for (driftSeconds in listOf(0.0, 20.0, 45.0, 90.0, 150.0, 240.0, 330.0)) {
            val streamerPhases = CoronaShape.phasesAt(driftSeconds).streamers
            val fields = (0 until samples).map { step ->
                val angle = step * TAU / samples
                CoronaModel.streamerField(angle, angle, streamerPhases)
            }
            val strongest = fields.max()
            val nearCrest = fields.count { field -> field >= 0.999f * strongest }

            assertTrue("drift $driftSeconds s: the strongest streamer reaches $strongest", strongest > 0.4f)
            assertTrue("drift $driftSeconds s: $nearCrest angles share the crest", nearCrest <= 20)
        }
    }

    @Test
    fun streamersNarrowWithHeight() {
        val lowWidth = streamerWidth(height = 0.2f)
        val highWidth = streamerWidth(height = 1.2f)

        assertTrue("width ${lowWidth}° at 0.2, ${highWidth}° at 1.2", highWidth < 0.6f * lowWidth)
    }

    @Test
    fun streamersReachFurtherAndShineBrighterWithTheirGains() {
        val resting = equatorLight(1.2f, restingGains)
        val stretched = equatorLight(1.2f, CoronaGains(reach = 1.45f))
        val brightened = equatorLight(1.2f, CoronaGains(streamers = 1.5f))

        assertTrue(stretched > 1.2f * resting)
        assertTrue(brightened > 1.2f * resting)
    }

    @Test
    fun exposureScalesAllTheCoronasLight() {
        for (step in 0 until 36) {
            val theta = step * TAU / 36f
            for (height in listOf(0f, 0.3f, 1.2f)) {
                val resting = CoronaModel.coronaLight(theta, height, phases, flow, restingGains)
                val exposed = CoronaModel.coronaLight(theta, height, phases, flow, CoronaGains(exposure = 1.3f))

                assertEquals(1.3f * resting, exposed, 1e-4f * resting + 1e-7f)
            }
        }
    }

    @Test
    fun partsAndGainsGiveTheSameLightAsTheWholeModel() {
        val gains = CoronaGains(inner = 1.2f, streamers = 1.4f, reach = 1.3f, exposure = 0.9f)
        for (step in 0 until 72) {
            val theta = step * TAU / 72f
            val parts = CoronaModel.coronaParts(theta, 0.4f, phases, flow)
            val wholeModel = CoronaModel.coronaLight(theta, 0.4f, phases, flow, gains)
            val fromParts = CoronaModel.lightOf(parts, gains)

            assertEquals(wholeModel, fromParts, 0f)
        }
    }

    /** Helmet streamers are smooth fans and polar plumes crisp rays, so the filaments are stronger at the poles. */
    @Test
    fun filamentsAreCrisperAtThePolesThanInTheStreamers() {
        val equatorContrast = filamentContrast(latitude = 0f, streamerField = 1f)
        val poleContrast = filamentContrast(latitude = 1f, streamerField = 0f)

        assertTrue("equator $equatorContrast, poles $poleContrast", poleContrast > 1.5f * equatorContrast)
    }

    /** Some sectors are fibrous and others smooth, unlike a uniform procedural texture. */
    @Test
    fun filamentsClumpRoundTheCircle() {
        val sectors = 16
        val contrasts = (0 until sectors).map { sector ->
            val fromTurn = sector / sectors.toFloat()
            filamentContrast(latitude = 0.5f, streamerField = 0f, fromTurn = fromTurn, turnSpan = 1f / sectors)
        }

        assertTrue("sector contrasts $contrasts", contrasts.max() > 1.8f * contrasts.min())
    }

    /** Screen angles come from atan2, which jumps from π to −π at nine o'clock: no seam may show there. */
    @Test
    fun coronaIsSeamlessAllRoundTheCircle() {
        for (step in 0 until 360) {
            val theta = step * TAU / 360f
            for (height in listOf(0f, 0.05f, 0.4f, 1.5f)) {
                val light = CoronaModel.coronaLight(theta, height, phases, flow, restingGains)
                val oneTurnBack = CoronaModel.coronaLight(theta - TAU, height, phases, flow, restingGains)

                assertEquals("theta $theta, height $height", light, oneTurnBack, 1e-3f * light + 1e-6f)
            }
        }
    }

    @Test
    fun coronaLightIsFiniteAndNeverNegative() {
        for (step in 0 until 720) {
            val theta = step * TAU / 720f - PI.toFloat()
            for (heightStep in 0 until 30) {
                val height = heightStep * 0.1f
                val light = CoronaModel.coronaLight(theta, height, phases, flow, restingGains)

                assertTrue("light($theta, $height) = $light", light.isFinite() && light >= 0f)
            }
        }
    }

    @Test
    fun toneMappingStartsAtBlackRisesAndNeverClips() {
        assertEquals(0f, CoronaModel.toDisplay(0f), 0f)

        var previous = 0f
        for (step in 1..200) {
            val display = CoronaModel.toDisplay(step * 0.05f)
            assertTrue(display > previous && display < 1f)
            previous = display
        }
    }

    /** The fade must be complete on the circle inscribed in the bounds, or their edge would show. */
    @Test
    fun boundsFadeKeepsTheCentreAndEndsOnTheInscribedCircle() {
        assertEquals(1f, CoronaModel.boundsFade(radiusFraction = 0.3f), 0f)
        assertEquals(0f, CoronaModel.boundsFade(radiusFraction = 1f), 0f)
        assertEquals(0f, CoronaModel.boundsFade(radiusFraction = 1.4f), 0f)

        var previous = 1f
        for (step in 0..100) {
            val radiusFraction = step / 100f
            val fade = CoronaModel.boundsFade(radiusFraction)

            assertTrue("fade rises at $radiusFraction", fade <= previous)
            previous = fade
        }
    }

    @Test
    fun skyIsDarkestAtTheBoundsAndGlowsNearTheCorona() {
        assertEquals(1f, CoronaModel.skyVignette(0.2f), 0f)
        assertEquals(0f, CoronaModel.skyVignette(1f), 0f)
        assertTrue(CoronaModel.skyGlow(0f) > CoronaModel.skyGlow(1f))
    }

    @Test
    fun starsAreFewFaintAndFarOut() {
        // A coarse scale (30 pixels per lunar radius) makes each star wide enough to sample reliably.
        val moonRadiusPx = 30f
        assertEquals(0f, CoronaModel.starLight(1.5f, 0f, moonRadiusPx, twinklePhase = 0f), 0f)

        var litCells = 0
        var farCells = 0
        val cell = CoronaLook.STAR_CELL
        for (column in -10 until 10) {
            for (row in -10 until 10) {
                val cellX = column * cell
                val cellY = row * cell
                val brightest = brightestInCell(cellX, cellY, cell, moonRadiusPx)
                assertTrue(brightest <= CoronaLook.STAR_BRIGHTNESS)

                val centreX = cellX + cell / 2f
                val centreY = cellY + cell / 2f
                if (centreX * centreX + centreY * centreY > 2.5f * 2.5f) {
                    farCells += 1
                    if (brightest > 0.01f) {
                        litCells += 1
                    }
                }
            }
        }

        assertTrue("$litCells of $farCells far cells hold a star", litCells in 1..farCells / 4)
    }

    /** Beyond the glint's reach its light must be negligible, or the shader's early return would show an edge. */
    @Test
    fun theGlintFadesOutWithinItsReach() {
        val reach = CoronaLook.GLINT_REACH
        val nearlyThere = reach - 0.01f
        val glintLight = CoronaModel.glareLight(nearlyThere) + 3f * CoronaModel.spikeLight(nearlyThere)
        val windowed = glintLight * CoronaModel.glintWindow(nearlyThere)

        assertTrue("glint light $windowed just inside its reach", windowed < 1e-5f)
        assertEquals(0f, CoronaModel.glintWindow(reach), 0f)
        assertEquals(1f, CoronaModel.glintWindow(0.1f), 0f)
    }

    @Test
    fun aBeadIsABrilliantSpotLongerAlongTheLimbThanAcrossIt() {
        val centre = CoronaModel.beadSpotLight(radial = 0f, tangential = 0f)
        val across = CoronaModel.beadSpotLight(radial = 0.02f, tangential = 0f)
        val along = CoronaModel.beadSpotLight(radial = 0f, tangential = 0.02f)

        assertTrue("a bead's centre is only $centre", centre > 20f)
        assertTrue("across $across, along $along", along > 5f * across)
    }

    private fun brightestInCell(cellX: Float, cellY: Float, cell: Float, moonRadiusPx: Float): Float {
        var brightest = 0f
        val samples = 40
        for (column in 0 until samples) {
            for (row in 0 until samples) {
                val x = cellX + cell * (column + 0.5f) / samples
                val y = cellY + cell * (row + 0.5f) / samples
                brightest = maxOf(brightest, CoronaModel.starLight(x, y, moonRadiusPx, twinklePhase = 0f))
            }
        }
        return brightest
    }

    /** The mean light within 0.4 radians of the tilted equator, on both sides of the Sun. */
    private fun equatorLight(height: Float, gains: CoronaGains = restingGains): Float {
        val equator = CoronaLook.EQUATOR_TILT
        val pi = PI.toFloat()
        val east = averageLight(height, equator - 0.4f, equator + 0.4f, gains)
        val west = averageLight(height, equator + pi - 0.4f, equator + pi + 0.4f, gains)
        return (east + west) / 2f
    }

    /** The mean light within 0.4 radians of the poles. */
    private fun poleLight(height: Float): Float {
        val north = CoronaLook.EQUATOR_TILT + PI.toFloat() / 2f
        val south = CoronaLook.EQUATOR_TILT - PI.toFloat() / 2f
        val northLight = averageLight(height, north - 0.4f, north + 0.4f)
        val southLight = averageLight(height, south - 0.4f, south + 0.4f)
        return (northLight + southLight) / 2f
    }

    /** The height where [lightAt] drops below a faint level, stepping out from the limb. */
    private fun outlineHeight(lightAt: (Float) -> Float): Float {
        val faint = 0.002f
        var height = 0f
        while (height < 3f && lightAt(height) >= faint) {
            height += 0.02f
        }
        return height
    }

    /** The angular width (degrees) over which the strongest streamer is at least half as bright as its crest. */
    private fun streamerWidth(height: Float): Float {
        val samples = 1440
        val lights = (0 until samples).map { step ->
            val theta = step * TAU / samples
            CoronaModel.streamerLight(CoronaModel.streamerField(theta, theta, phases.streamers), height, reach = 1f)
        }
        val crest = lights.max()
        val crestIndex = lights.indexOf(crest)

        var width = 1
        var left = crestIndex - 1
        while (lights[Math.floorMod(left, samples)] >= crest / 2f) {
            width += 1
            left -= 1
        }
        var right = crestIndex + 1
        while (lights[Math.floorMod(right, samples)] >= crest / 2f) {
            width += 1
            right += 1
        }
        return width * 360f / samples
    }

    /** The filaments' root-mean-square deviation from 1 at [latitude], over a range of turns and heights. */
    private fun filamentContrast(
        latitude: Float,
        streamerField: Float,
        fromTurn: Float = 0f,
        turnSpan: Float = 1f,
    ): Float {
        var sumOfSquares = 0f
        var count = 0
        for (step in 0 until 400) {
            val turn = fromTurn + turnSpan * (step + 0.5f) / 400f
            for (height in listOf(0.4f, 0.8f, 1.2f)) {
                val strands = CoronaModel.filamentTexture(turn, height, latitude, streamerField, flow)
                val deviation = strands - 1f
                sumOfSquares += deviation * deviation
                count += 1
            }
        }
        return sqrt(sumOfSquares / count)
    }

    private fun averageLight(
        height: Float,
        fromAngle: Float,
        toAngle: Float,
        gains: CoronaGains = restingGains,
    ): Float {
        val samples = 400
        var total = 0f
        for (step in 0 until samples) {
            val theta = fromAngle + (toAngle - fromAngle) * (step + 0.5f) / samples
            total += CoronaModel.coronaLight(theta, height, phases, flow, gains)
        }
        return total / samples
    }
}
