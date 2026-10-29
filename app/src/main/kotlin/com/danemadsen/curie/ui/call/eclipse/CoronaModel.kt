package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the corona's light is scaled at the moment, as multiples of its resting values (all 1 at rest).
 *
 * @property inner the inner corona's brightness
 * @property streamers the helmet streamers' brightness
 * @property reach how far the helmet streamers reach
 * @property exposure the brightness of all the corona's light, as a camera's exposure would scale it
 */
internal data class CoronaGains(
    val inner: Float = 1f,
    val streamers: Float = 1f,
    val reach: Float = 1f,
    val exposure: Float = 1f,
)

/**
 * The corona's light at one point, split into what the [CoronaGains] scale. Working them out takes all the
 * noise lookups, so the fallback renderer works them out once per pixel and derives several layers from them.
 *
 * @property height the height above the limb
 * @property streamerField how strongly the helmet streamers rise here, from 0 to 1, before they taper
 * @property strands the filaments' brightness factor, around 1
 * @property inner the textured inner corona, before its gain
 * @property plumesAndBackground the polar plumes and the background corona, before the filaments
 */
internal data class CoronaParts(
    val height: Float,
    val streamerField: Float,
    val strands: Float,
    val inner: Float,
    val plumesAndBackground: Float,
)

/**
 * The eclipse's light model on the CPU, for the fallback renderer and for tests. Every function mirrors the
 * AGSL function of the same name in [EclipseShaderSource]; positions are in lunar radii, light is linear.
 * Only the Baily's bead, which moves, is left to the fallback's own drawing.
 */
internal object CoronaModel {
    /**
     * All the corona's light at the angle [theta] and [height] above the limb, before colour: the inner
     * corona, the helmet streamers, the polar plumes and a faint background, all threaded with filaments.
     */
    fun coronaLight(
        theta: Float,
        height: Float,
        phases: CoronaPhases,
        flow: FilamentFlow,
        gains: CoronaGains,
    ): Float {
        val parts = coronaParts(theta, height, phases, flow)
        return lightOf(parts, gains)
    }

    /** The parts of the corona's light at [theta] and [height] that do not depend on the gains. */
    fun coronaParts(theta: Float, height: Float, phases: CoronaPhases, flow: FilamentFlow): CoronaParts {
        // Domain warp in polar coordinates: a bend that changes along the radius, so the rays curve gently.
        val rise = height / (1f + height)
        val bendWave1 = sin(CoronaLook.BEND_ORDER_1 * theta + phases.bend1 + CoronaLook.BEND_TWIST_1 * height)
        val bendWave2 = sin(CoronaLook.BEND_ORDER_2 * theta + phases.bend2 + CoronaLook.BEND_TWIST_2 * height)
        val bend = CoronaLook.BEND_WEIGHT_1 * bendWave1 + CoronaLook.BEND_WEIGHT_2 * bendWave2
        val equatorAngle = theta - CoronaLook.EQUATOR_TILT + CoronaLook.BEND * bend * rise

        // Superradial expansion: higher up, the texture comes from a footpoint nearer the pole.
        val footpoint = equatorAngle + CoronaLook.FAN * rise * sin(2f * equatorAngle)
        val turn = footpoint / TAU
        val latitude = abs(sin(equatorAngle))

        val field = streamerField(footpoint, equatorAngle, phases.streamers)
        val plumes = plumeLight(turn, latitude, height, flow)
        val strands = filamentTexture(turn, height, latitude, field, flow)

        // Inner (K) corona: an intense thin ring on the limb with a steep bloom, unevenly bright round the limb.
        val unevenWave1 = sin(CoronaLook.UNEVENNESS_ORDER_1 * theta + phases.unevenness1)
        val unevenWave2 = sin(CoronaLook.UNEVENNESS_ORDER_2 * theta + phases.unevenness2)
        val unevenness1 = CoronaLook.UNEVENNESS_WEIGHT_1 * unevenWave1
        val unevenness2 = CoronaLook.UNEVENNESS_WEIGHT_2 * unevenWave2
        val unevenness = 1f + unevenness1 + unevenness2
        val innerTexture = 1f + (strands - 1f) * CoronaLook.INNER_TEXTURE
        val inner = innerCoronaLight(height) * unevenness * innerTexture
        val background = backgroundLight(height) * unevenness

        return CoronaParts(
            height = height,
            streamerField = field,
            strands = strands,
            inner = inner,
            plumesAndBackground = CoronaLook.PLUME_GAIN * plumes + background,
        )
    }

    /** The corona's light made of [parts], scaled by [gains]. */
    fun lightOf(parts: CoronaParts, gains: CoronaGains): Float {
        val streamerProfile = streamerLight(parts.streamerField, parts.height, gains.reach)
        val streamers = CoronaLook.STREAMER_GAIN * gains.streamers * streamerProfile
        val outer = (streamers + parts.plumesAndBackground) * parts.strands
        val inner = parts.inner * gains.inner
        return (inner + outer) * gains.exposure
    }

    /** The inner corona's ring and bloom at [height], before unevenness and gain. */
    fun innerCoronaLight(height: Float): Float {
        val ring = CoronaLook.RING_GAIN * exp(-height / CoronaLook.RING_WIDTH)
        val bloomBase = 1f + height / CoronaLook.BLOOM_SCALE
        val bloom = CoronaLook.BLOOM_GAIN * bloomBase.pow(-CoronaLook.BLOOM_FALLOFF)
        return ring + bloom
    }

    /**
     * Where the helmet streamers rise, from 0 to 1: harmonics place a few broad bases round the circle and the
     * equator envelope keeps them at low latitudes. It reaches 1 only at the crest of the strongest streamer.
     */
    fun streamerField(footpoint: Float, equatorAngle: Float, phases: Quad): Float {
        val orders = CoronaLook.STREAMER_ORDERS
        val weights = CoronaLook.STREAMER_WEIGHTS
        val wave1 = weights.x * sin(orders.x * footpoint + phases.x)
        val wave2 = weights.y * sin(orders.y * footpoint + phases.y)
        val wave3 = weights.z * sin(orders.z * footpoint + phases.z)
        val wave4 = weights.w * sin(orders.w * footpoint + phases.w)
        val harmonics = CoronaLook.STREAMER_HARMONICS_MEAN + wave1 + wave2 + wave3 + wave4

        val equator = abs(cos(equatorAngle)).pow(CoronaLook.EQUATOR_POWER)
        val fieldRange = CoronaLook.STREAMER_FIELD_HIGH - CoronaLook.STREAMER_FIELD_LOW
        return saturate((equator * harmonics - CoronaLook.STREAMER_FIELD_LOW) / fieldRange)
    }

    /**
     * A helmet streamer's light at [height] where its [field] is as given: raising the field to a power that
     * grows with height narrows each streamer from a broad base into a long thin wing, and a longer [reach]
     * stretches the whole streamer outwards.
     */
    fun streamerLight(field: Float, height: Float, reach: Float): Float {
        val reachHeight = height / reach
        val taper = 1f + CoronaLook.STREAMER_TAPER * reachHeight
        val profileBase = 1f + reachHeight / CoronaLook.STREAMER_SCALE
        val profile = profileBase.pow(-CoronaLook.STREAMER_FALLOFF)
        return field.pow(taper) * profile
    }

    /** Polar plumes: short, thin, near-radial rays at high [latitude] (0 at the equator, 1 at the poles). */
    fun plumeLight(turn: Float, latitude: Float, height: Float, flow: FilamentFlow): Float {
        val pole = latitude.pow(CoronaLook.POLE_POWER)
        val rays = CoronaNoise.valueNoise(
            x = turn * CoronaLook.PLUME_CELLS,
            y = height * CoronaLook.PLUME_RADIAL - flow.plumes,
            periodX = CoronaLook.PLUME_CELLS,
            periodY = CoronaLook.FLOW_PERIOD,
            seed = CoronaLook.PLUME_SEED,
        )
        val profileBase = 1f + height / CoronaLook.PLUME_SCALE
        val profile = profileBase.pow(-CoronaLook.PLUME_FALLOFF)
        return pole * rays * rays * profile
    }

    /**
     * Fine radial filaments: noise stretched along the rays, flowing slowly outwards, with thin bright strands
     * from ridged noise. They are crisper towards the poles, smoother inside streamers ([streamerField]), and
     * clumped, so some sectors are fibrous and others smooth. Returns a brightness factor around 1.
     */
    fun filamentTexture(
        turn: Float,
        height: Float,
        latitude: Float,
        streamerField: Float,
        flow: FilamentFlow,
    ): Float {
        val fine = filamentNoise(turn, height, FilamentOctave.FINE, flow.fine)
        val finer = filamentNoise(turn, height, FilamentOctave.FINER, flow.finer)
        val finest = filamentNoise(turn, height, FilamentOctave.FINEST, flow.finest)

        val fineShare = CoronaLook.FINE_WEIGHT * fine
        val finerShare = CoronaLook.FINER_WEIGHT * finer
        val finestShare = CoronaLook.FINEST_WEIGHT * finest
        val even = (fineShare + finerShare + finestShare) * 2f - 1f
        val ridges = (1f - abs(2f * finer - 1f)).pow(CoronaLook.RIDGE_SHARPNESS) * 2f - 1f
        val ridgeShare = CoronaLook.RIDGE_SHARE * (1f - CoronaLook.RIDGE_STREAMER_DAMPING * streamerField)
        val filaments = mix(even, ridges, ridgeShare)

        val ramp = smoothStep(0f, CoronaLook.FILAMENT_RAMP_HEIGHT, height)
        val heightShare = mix(CoronaLook.FILAMENT_BASE_SHARE, 1f, ramp)
        val latitudeShare = mix(CoronaLook.EQUATOR_TEXTURE, CoronaLook.POLE_TEXTURE, latitude)
        val clumpNoise = CoronaNoise.valueNoise1D(
            x = turn * CoronaLook.CLUMP_CELLS,
            period = CoronaLook.CLUMP_CELLS,
            seed = CoronaLook.CLUMP_SEED,
        )
        val clumps = mix(CoronaLook.CLUMP_LOW, CoronaLook.CLUMP_HIGH, clumpNoise)
        val strength = CoronaLook.FILAMENT_CONTRAST * heightShare * latitudeShare * clumps
        return 1f + filaments * strength
    }

    private enum class FilamentOctave(val cells: Float, val radial: Float, val seed: Float) {
        FINE(CoronaLook.FINE_CELLS, CoronaLook.FINE_RADIAL, CoronaLook.FINE_SEED),
        FINER(CoronaLook.FINER_CELLS, CoronaLook.FINER_RADIAL, CoronaLook.FINER_SEED),
        FINEST(CoronaLook.FINEST_CELLS, CoronaLook.FINEST_RADIAL, CoronaLook.FINEST_SEED),
    }

    private fun filamentNoise(turn: Float, height: Float, octave: FilamentOctave, flow: Float): Float =
        CoronaNoise.valueNoise(
            x = turn * octave.cells,
            y = height * octave.radial - flow,
            periodX = octave.cells,
            periodY = CoronaLook.FLOW_PERIOD,
            seed = octave.seed,
        )

    /** The pearly, near-isotropic background corona, which falls off faster than the streamers. */
    fun backgroundLight(height: Float): Float {
        val base = 1f + height / CoronaLook.BACKGROUND_SCALE
        return CoronaLook.BACKGROUND_GAIN * base.pow(-CoronaLook.BACKGROUND_FALLOFF)
    }

    /** How much of the outer (pearly) tint the corona has at [height], from 0 (bluish white) to 1. */
    fun outerTintShare(height: Float): Float = smoothStep(0f, CoronaLook.TINT_BLEND_HEIGHT, height)

    /** The sky's strength at [radiusFraction] of half the smaller side: full near the Sun, 0 at the bounds. */
    fun skyVignette(radiusFraction: Float): Float =
        1f - smoothStep(CoronaLook.SKY_VIGNETTE_INNER, 1f, radiusFraction)

    /** Scattered light near the corona, multiplying [CoronaLook.SKY_GLOW]. */
    fun skyGlow(height: Float): Float = exp(-height / CoronaLook.SKY_GLOW_SCALE)

    /**
     * A faint star at ([x], [y]) if one sits in that grid cell, else 0. [moonRadiusPx] sets the stars' size in
     * pixels and [twinklePhase] how far their twinkle has run.
     */
    fun starLight(x: Float, y: Float, moonRadiusPx: Float, twinklePhase: Float): Float {
        val radius = sqrt(x * x + y * y)
        if (radius < CoronaLook.STAR_MIN_RADIUS) {
            return 0f
        }

        val cellX = floor(x / CoronaLook.STAR_CELL)
        val cellY = floor(y / CoronaLook.STAR_CELL)
        val cellInput = cellX * CoronaLook.STAR_COLUMN_SALT + cellY * CoronaLook.STAR_ROW_SALT
        val pick = CoronaNoise.hash(cellInput + CoronaLook.STAR_CELL_OFFSET)
        if (pick < 1f - CoronaLook.STAR_DENSITY) {
            return 0f
        }

        val span = 1f - 2f * CoronaLook.STAR_MARGIN
        val jitterX = CoronaNoise.hash(pick * CoronaLook.STAR_X_SALT)
        val jitterY = CoronaNoise.hash(pick * CoronaLook.STAR_Y_SALT)
        val starX = (cellX + CoronaLook.STAR_MARGIN + span * jitterX) * CoronaLook.STAR_CELL
        val starY = (cellY + CoronaLook.STAR_MARGIN + span * jitterY) * CoronaLook.STAR_CELL
        val offsetX = (x - starX) * moonRadiusPx
        val offsetY = (y - starY) * moonRadiusPx
        val distanceSquared = offsetX * offsetX + offsetY * offsetY
        val starSize = CoronaLook.STAR_SIZE_PX
        val glow = exp(-distanceSquared / (starSize * starSize))

        val brightness = CoronaLook.STAR_BRIGHTNESS * CoronaNoise.hash(pick * CoronaLook.STAR_BRIGHTNESS_SALT)
        val paceHash = CoronaNoise.hash(pick * CoronaLook.STAR_PACE_SALT)
        val pace = 1f + floor(CoronaLook.STAR_PACES * paceHash)
        val wave = 0.5f + 0.5f * sin(pace * twinklePhase + TAU * pick)
        val twinkle = 1f - CoronaLook.TWINKLE_DEPTH * wave
        return glow * brightness * twinkle
    }

    /**
     * One Baily's bead's light, before its weight and flicker, at [radial] lunar radii outwards from its centre
     * and [tangential] lunar radii along the limb.
     */
    fun beadSpotLight(radial: Float, tangential: Float): Float {
        val across = radial / CoronaLook.BEAD_RADIAL_SIZE
        val along = tangential / CoronaLook.BEAD_TANGENTIAL_SIZE
        return CoronaLook.BEAD_GAIN * exp(-(across * across + along * along))
    }

    /** The glare round the main bead at [distance] from it, before the glint's strength and fade. */
    fun glareLight(distance: Float): Float {
        val tight = CoronaLook.GLARE_TIGHT_GAIN * exp(-distance / CoronaLook.GLARE_TIGHT_SCALE)
        val wide = CoronaLook.GLARE_WIDE_GAIN * exp(-distance / CoronaLook.GLARE_WIDE_SCALE)
        return tight + wide
    }

    /** The light along the middle of a diffraction spike at [distance] from the main bead, before the fade. */
    fun spikeLight(distance: Float): Float = CoronaLook.SPIKE_GAIN * exp(-distance / CoronaLook.SPIKE_LENGTH)

    /** The fade that ends the glint at [CoronaLook.GLINT_REACH] from the main bead: 1 near it, then 0. */
    fun glintWindow(distance: Float): Float =
        1f - smoothStep(CoronaLook.GLINT_FADE_START, CoronaLook.GLINT_REACH, distance)

    /** Linear [light] tone-mapped like film (soft saturation) and encoded for display, in [0, 1). */
    fun toDisplay(light: Float): Float {
        val exposed = 1f - exp(-light)
        return exposed.pow(1f / CoronaLook.DISPLAY_GAMMA)
    }

    /**
     * The fade towards the bounds at [radiusFraction] (the distance from the centre over half the smaller side):
     * 1 near the Sun, 0 from the circle inscribed in the bounds outwards.
     */
    fun boundsFade(radiusFraction: Float): Float =
        1f - smoothStep(CoronaLook.RADIAL_FADE_START, CoronaLook.RADIAL_FADE_END, radiusFraction)
}
