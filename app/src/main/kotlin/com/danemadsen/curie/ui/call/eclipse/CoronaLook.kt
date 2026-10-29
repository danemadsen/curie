package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.PI

/** A colour as three channels; linear light or display values, as each use says. */
internal data class Rgb(val red: Float, val green: Float, val blue: Float)

/** A four-component constant, written to the shader as a `float4`. */
internal data class Quad(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun sum(): Float = x + y + z + w
}

/**
 * Every number that shapes the eclipse. The AGSL shader is generated from these ([EclipseShaderSource]) and
 * the CPU fallback ([CoronaModel]) reads them directly, so the two renderers cannot drift apart.
 *
 * Distances are in lunar radii (the Moon's edge is at 1) and "height" is the distance above the limb.
 * Intensities are linear light before tone mapping; angles are radians anticlockwise from three o'clock.
 * The look follows photographs of the 2017 and 2024 eclipses: a solar-minimum corona whose helmet streamers
 * spread from broad bases along a tilted equator into long tapering wings, with short plumes at the poles.
 */
internal object CoronaLook {
    /** The Moon's radius as a fraction of the smaller side of the bounds, which leaves 2.5 lunar radii of room. */
    const val MOON_RADIUS_FRACTION = 0.2f

    /** The solar equator, where the helmet streamers gather, rises 20° from horizontal. */
    const val EQUATOR_TILT = 0.34906585f

    /**
     * Everything fades out between these fractions of half the smaller side. The corona itself has mostly faded
     * before the fade starts; it ends on the circle inscribed in the bounds, so no edge of them ever shows.
     */
    const val RADIAL_FADE_START = 0.76f
    const val RADIAL_FADE_END = 1f

    // Polar domain warps: the corona's rays are not straight spokes.

    /** How far (radians) the large-scale bend can swing a ray at great height. */
    const val BEND = 0.1f

    /** The bend's two harmonics: their orders round the circle, weights and twist along the radius. */
    const val BEND_ORDER_1 = 2f
    const val BEND_ORDER_2 = 5f
    const val BEND_WEIGHT_1 = 0.6f
    const val BEND_WEIGHT_2 = 0.4f
    const val BEND_TWIST_1 = 1.3f
    const val BEND_TWIST_2 = -0.9f

    /** Superradial expansion: how far (radians) field lines fan away from the poles with height. */
    const val FAN = 0.18f

    // Helmet streamers: broad bases near the equator that taper into a few long wings.

    /** Where the streamers sit round the circle: four harmonics of these orders and weights around a mean. */
    val STREAMER_ORDERS = Quad(2f, 3f, 5f, 9f)
    val STREAMER_WEIGHTS = Quad(0.22f, 0.18f, 0.14f, 0.1f)
    const val STREAMER_HARMONICS_MEAN = 0.5f

    /** The equator envelope `|cos(latitude)|^power` that keeps the streamers at low latitudes. */
    const val EQUATOR_POWER = 2f

    /**
     * The harmonics times the equator envelope are mapped to streamer strength linearly from [STREAMER_FIELD_LOW]
     * to their largest possible value, so the strength reaches 1 only at the very crest of a streamer. A lower top
     * would clip the crest flat, and a flat crest never narrows with height: it shows as a hard-edged bar.
     */
    const val STREAMER_FIELD_LOW = 0.1f
    val STREAMER_FIELD_HIGH = STREAMER_HARMONICS_MEAN + STREAMER_WEIGHTS.sum()

    /** Streamers narrow with height: their strength is raised to `1 + taper * height`. */
    const val STREAMER_TAPER = 5f

    /** Radial profile of a streamer: `(1 + height / scale) ^ -falloff`. */
    const val STREAMER_SCALE = 0.45f
    const val STREAMER_FALLOFF = 1.5f
    const val STREAMER_GAIN = 6f

    // Polar plumes: short, thin rays at high latitudes.

    /** The polar envelope `|sin(latitude)|^power`. */
    const val POLE_POWER = 6f

    /** Plume noise cells round the circle and along one lunar radius. */
    const val PLUME_CELLS = 64f
    const val PLUME_RADIAL = 0.6f
    const val PLUME_SCALE = 0.12f
    const val PLUME_FALLOFF = 3.5f
    const val PLUME_GAIN = 1.2f

    // Fine radial filaments over the whole corona: three octaves of value noise stretched along the radius.

    /** Noise cells round the circle for the fine, finer and finest filaments. */
    const val FINE_CELLS = 150f
    const val FINER_CELLS = 330f
    const val FINEST_CELLS = 720f

    /** Noise cells along one lunar radius: few, so filaments run long and change slowly along their length. */
    const val FINE_RADIAL = 0.8f
    const val FINER_RADIAL = 1f
    const val FINEST_RADIAL = 1.6f

    const val FINE_WEIGHT = 0.5f
    const val FINER_WEIGHT = 0.3f
    const val FINEST_WEIGHT = 0.2f

    /** How strongly the filaments modulate the corona: ±this much brightness, before the factors below. */
    const val FILAMENT_CONTRAST = 0.6f

    /** Right at the limb only this share of the contrast shows; it reaches all of it at [FILAMENT_RAMP_HEIGHT]. */
    const val FILAMENT_BASE_SHARE = 0.35f
    const val FILAMENT_RAMP_HEIGHT = 0.3f

    /** Helmet streamers are smooth fans and polar plumes crisp rays: the contrast runs from equator to pole. */
    const val EQUATOR_TEXTURE = 0.55f
    const val POLE_TEXTURE = 1.2f

    /**
     * Some sectors are fibrous and others smooth: the contrast is scaled between [CLUMP_LOW] and [CLUMP_HIGH] by
     * value noise with [CLUMP_CELLS] cells round the circle.
     */
    const val CLUMP_CELLS = 8f
    const val CLUMP_LOW = 0.4f
    const val CLUMP_HIGH = 1.2f

    /**
     * Part of the texture is "ridged" noise, `(1 - |2n - 1|) ^ sharpness`, made from the finer octave: it peaks
     * in thin lines where the noise crosses one half, which reads as distinct bright strands. Inside a streamer
     * the share shrinks by up to [RIDGE_STREAMER_DAMPING] of itself, so streamers stay smooth.
     */
    const val RIDGE_SHARE = 0.3f
    const val RIDGE_SHARPNESS = 4f
    const val RIDGE_STREAMER_DAMPING = 0.8f

    /** The inner corona is smoother: only this share of the filament contrast shows there. */
    const val INNER_TEXTURE = 0.3f

    /** Every flowing noise pattern repeats after this many lattice rows, so its clock can wrap seamlessly. */
    const val FLOW_PERIOD = 16f

    /** Independent noise patterns for each layer. */
    const val PLUME_SEED = 0f
    const val FINE_SEED = 101f
    const val FINER_SEED = 202f
    const val FINEST_SEED = 303f
    const val CLUMP_SEED = 505f

    // Inner (K) corona: an intense thin ring hugging the limb with a steep bloom.

    const val RING_WIDTH = 0.03f
    const val RING_GAIN = 3f

    /** The bloom falls as `gain * (1 + height / scale) ^ -falloff`. */
    const val BLOOM_SCALE = 0.08f
    const val BLOOM_FALLOFF = 4f
    const val BLOOM_GAIN = 2f

    /** A pearly, near-isotropic background corona: `gain * (1 + height / scale) ^ -falloff`. */
    const val BACKGROUND_SCALE = 0.11f
    const val BACKGROUND_FALLOFF = 4.5f
    const val BACKGROUND_GAIN = 2f

    /** Uneven brightness round the limb: two harmonics of these orders and weights. */
    const val UNEVENNESS_ORDER_1 = 3f
    const val UNEVENNESS_ORDER_2 = 7f
    const val UNEVENNESS_WEIGHT_1 = 0.22f
    const val UNEVENNESS_WEIGHT_2 = 0.16f

    // Colour: white, bluish or the faintest warmth, never red or pink.

    /** The inner corona is a slightly bluish white; further out it turns pearly with a faint warmth. */
    val INNER_TINT = Rgb(0.88f, 0.94f, 1f)
    val OUTER_TINT = Rgb(1f, 0.96f, 0.9f)

    /** Height over which the inner tint gives way to the outer one. */
    const val TINT_BLEND_HEIGHT = 1f

    /**
     * Deep twilight blue-black (linear light), at full strength out to [SKY_VIGNETTE_INNER]. It stays dim, or its
     * round glow would hide the corona's own outline.
     */
    val SKY = Rgb(0.0002f, 0.0003f, 0.0008f)
    const val SKY_VIGNETTE_INNER = 0.35f

    /** Scattered light makes the sky a little brighter near the corona. */
    val SKY_GLOW = Rgb(0.0004f, 0.0006f, 0.0012f)
    const val SKY_GLOW_SCALE = 0.4f

    /** Tone-mapped light `1 - e^-light` is shown with this gamma. */
    const val DISPLAY_GAMMA = 2.2f

    // A few faint stars far out.

    /** Stars sit one per cell at most, in a grid of this pitch (lunar radii). */
    const val STAR_CELL = 0.35f

    /** The share of cells that hold a star. */
    const val STAR_DENSITY = 0.12f

    /** No stars closer to the Sun than this. */
    const val STAR_MIN_RADIUS = 1.9f
    const val STAR_BRIGHTNESS = 0.6f

    /** The Gaussian radius of a star's image, in pixels. */
    const val STAR_SIZE_PX = 0.9f
    val STAR_TINT = Rgb(0.85f, 0.9f, 1f)

    /** How much a star's brightness wavers. */
    const val TWINKLE_DEPTH = 0.25f

    /**
     * A cell's random pick is the hash of `column * STAR_COLUMN_SALT + row * STAR_ROW_SALT + STAR_CELL_OFFSET`;
     * the star's position, brightness and twinkle pace are hashes of the pick times their own salts.
     */
    const val STAR_COLUMN_SALT = 7f
    const val STAR_ROW_SALT = 113f
    const val STAR_CELL_OFFSET = 0.5f
    const val STAR_X_SALT = 97f
    const val STAR_Y_SALT = 193f
    const val STAR_BRIGHTNESS_SALT = 389f
    const val STAR_PACE_SALT = 577f

    /** A star sits this far into its cell at least, so that its glow never spills into the next cell. */
    const val STAR_MARGIN = 0.2f

    /** Stars twinkle at one, two or three times the base pace. */
    const val STAR_PACES = 3f

    // Baily's bead: sunlight through lunar valleys, a brilliant glint on the limb (the "thinking" signal).

    /**
     * Three beads in a short string along the limb: the main one at the glint's angle and two fainter ones
     * these angles (radians) away, each with these weights.
     */
    const val BEAD_OFFSET_2 = -0.05f
    const val BEAD_OFFSET_3 = 0.042f
    const val BEAD_WEIGHT_2 = 0.55f
    const val BEAD_WEIGHT_3 = 0.35f

    /** Each bead is an elongated Gaussian: thin across the limb and three times as long along it. */
    const val BEAD_RADIAL_SIZE = 0.012f
    const val BEAD_TANGENTIAL_SIZE = 0.036f
    const val BEAD_GAIN = 60f

    /** The beads sit just outside the limb, so the Moon does not hide half of each. */
    const val BEAD_LIFT = 0.006f

    /** Glare round the main bead: a tight and a wide exponential halo (gain, scale in lunar radii). */
    const val GLARE_TIGHT_GAIN = 12f
    const val GLARE_TIGHT_SCALE = 0.055f
    const val GLARE_WIDE_GAIN = 0.3f
    const val GLARE_WIDE_SCALE = 0.15f

    /**
     * Diffraction spikes, as a camera's six-bladed aperture draws round a point of light: three lines through
     * the main bead, the first at [SPIKE_ANGLE] from horizontal and the others 60° on. Across a line the light
     * is a Gaussian of [SPIKE_WIDTH_PX] pixels (so it stays crisp at any size); along it, it fades with
     * [SPIKE_LENGTH]. The Moon covers the rays that point inwards.
     */
    const val SPIKE_ANGLE = 0.26f
    const val SPIKE_WIDTH_PX = 1.2f
    const val SPIKE_GAIN = 0.8f
    const val SPIKE_LENGTH = 0.16f

    /** The directions of the three spike lines, radians anticlockwise from three o'clock. */
    val SPIKE_DIRECTIONS = List(3) { line -> SPIKE_ANGLE + line * PI.toFloat() / 3f }

    /**
     * The glint adds nothing visible beyond [GLINT_REACH] from the main bead; it is faded out from
     * [GLINT_FADE_START] so that it never shows an edge, and nothing is computed for it further away.
     */
    const val GLINT_FADE_START = 0.8f
    const val GLINT_REACH = 1.4f

    /** The beads and their glare are pure white. */
    val BEAD_TINT = Rgb(1f, 1f, 1f)

    /** At full strength the glint outshines the corona, which dims by this share, as a camera would expose. */
    const val GLINT_EXPOSURE_DIP = 0.4f

    /** Dithering of the output by ±half a step of 8-bit colour, against banding in the dark gradients. */
    const val DITHER_AMPLITUDE = 1f / 255f
}
