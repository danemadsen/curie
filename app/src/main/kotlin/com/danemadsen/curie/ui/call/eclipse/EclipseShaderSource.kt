package com.danemadsen.curie.ui.call.eclipse

import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.sin

/**
 * The AGSL source of the eclipse shader. Its constants are written in from [CoronaLook], and its functions
 * mirror [CoronaNoise] and [CoronaModel] line for line, so the GPU and CPU renderers share one model.
 *
 * Per pixel it costs five value-noise lookups (plumes, three filament octaves and the clumping), about a dozen
 * sines and a few powers and exponentials; the Moon and everything beyond the fade return early, and the
 * Baily's beads cost nothing unless Curie is thinking, and then only near them.
 */
internal object EclipseShaderSource {
    /** The bounds in pixels. */
    const val SIZE = "uSize"

    /** [CoronaPhases.streamers]. */
    const val STREAMER_PHASES = "uStreamerPhases"

    /** [CoronaPhases.bend1], [CoronaPhases.bend2], [CoronaPhases.unevenness1], [CoronaPhases.unevenness2]. */
    const val SHAPE_PHASES = "uShapePhases"

    /** [FilamentFlow]: plumes, fine, finer and finest filaments. */
    const val FLOW = "uFlow"

    /** [CoronaGains]: inner corona, streamer brightness, streamer reach and exposure. */
    const val GAINS = "uGains"

    /** The Baily's beads: the main bead's angle on the limb and the glint's strength. */
    const val GLINT = "uGlint"

    /** [BeadFlicker]: each bead's shimmering brightness factor. */
    const val BEAD_FLICKER = "uBeadFlicker"

    /** How far the stars' twinkle has run, in radians. */
    const val TWINKLE = "uTwinkle"

    val UNIFORM_NAMES = listOf(SIZE, STREAMER_PHASES, SHAPE_PHASES, FLOW, GAINS, GLINT, BEAD_FLICKER, TWINKLE)

    val SOURCE: String = buildSource()

    private fun buildSource(): String = """
// Constants, from CoronaLook.
const float TAU = ${glsl(TAU)};
const float ROW_STRIDE = ${glsl(CoronaNoise.ROW_STRIDE)};
const float MOON_RADIUS_FRACTION = ${glsl(CoronaLook.MOON_RADIUS_FRACTION)};
const float EQUATOR_TILT = ${glsl(CoronaLook.EQUATOR_TILT)};
const float RADIAL_FADE_START = ${glsl(CoronaLook.RADIAL_FADE_START)};
const float RADIAL_FADE_END = ${glsl(CoronaLook.RADIAL_FADE_END)};
const float BEND = ${glsl(CoronaLook.BEND)};
const float2 BEND_ORDERS = ${float2(CoronaLook.BEND_ORDER_1, CoronaLook.BEND_ORDER_2)};
const float2 BEND_WEIGHTS = ${float2(CoronaLook.BEND_WEIGHT_1, CoronaLook.BEND_WEIGHT_2)};
const float2 BEND_TWISTS = ${float2(CoronaLook.BEND_TWIST_1, CoronaLook.BEND_TWIST_2)};
const float FAN = ${glsl(CoronaLook.FAN)};
const float4 STREAMER_ORDERS = ${float4(CoronaLook.STREAMER_ORDERS)};
const float4 STREAMER_WEIGHTS = ${float4(CoronaLook.STREAMER_WEIGHTS)};
const float STREAMER_HARMONICS_MEAN = ${glsl(CoronaLook.STREAMER_HARMONICS_MEAN)};
const float EQUATOR_POWER = ${glsl(CoronaLook.EQUATOR_POWER)};
const float STREAMER_FIELD_LOW = ${glsl(CoronaLook.STREAMER_FIELD_LOW)};
const float STREAMER_FIELD_HIGH = ${glsl(CoronaLook.STREAMER_FIELD_HIGH)};
const float STREAMER_TAPER = ${glsl(CoronaLook.STREAMER_TAPER)};
const float STREAMER_SCALE = ${glsl(CoronaLook.STREAMER_SCALE)};
const float STREAMER_FALLOFF = ${glsl(CoronaLook.STREAMER_FALLOFF)};
const float STREAMER_GAIN = ${glsl(CoronaLook.STREAMER_GAIN)};
const float POLE_POWER = ${glsl(CoronaLook.POLE_POWER)};
const float PLUME_CELLS = ${glsl(CoronaLook.PLUME_CELLS)};
const float PLUME_RADIAL = ${glsl(CoronaLook.PLUME_RADIAL)};
const float PLUME_SCALE = ${glsl(CoronaLook.PLUME_SCALE)};
const float PLUME_FALLOFF = ${glsl(CoronaLook.PLUME_FALLOFF)};
const float PLUME_GAIN = ${glsl(CoronaLook.PLUME_GAIN)};
const float3 FILAMENT_CELLS = ${float3(CoronaLook.FINE_CELLS, CoronaLook.FINER_CELLS, CoronaLook.FINEST_CELLS)};
const float3 FILAMENT_RADIAL = ${float3(CoronaLook.FINE_RADIAL, CoronaLook.FINER_RADIAL, CoronaLook.FINEST_RADIAL)};
const float3 FILAMENT_WEIGHTS = ${float3(CoronaLook.FINE_WEIGHT, CoronaLook.FINER_WEIGHT, CoronaLook.FINEST_WEIGHT)};
const float3 FILAMENT_SEEDS = ${float3(CoronaLook.FINE_SEED, CoronaLook.FINER_SEED, CoronaLook.FINEST_SEED)};
const float FILAMENT_CONTRAST = ${glsl(CoronaLook.FILAMENT_CONTRAST)};
const float FILAMENT_BASE_SHARE = ${glsl(CoronaLook.FILAMENT_BASE_SHARE)};
const float FILAMENT_RAMP_HEIGHT = ${glsl(CoronaLook.FILAMENT_RAMP_HEIGHT)};
const float EQUATOR_TEXTURE = ${glsl(CoronaLook.EQUATOR_TEXTURE)};
const float POLE_TEXTURE = ${glsl(CoronaLook.POLE_TEXTURE)};
const float CLUMP_CELLS = ${glsl(CoronaLook.CLUMP_CELLS)};
const float CLUMP_LOW = ${glsl(CoronaLook.CLUMP_LOW)};
const float CLUMP_HIGH = ${glsl(CoronaLook.CLUMP_HIGH)};
const float RIDGE_SHARE = ${glsl(CoronaLook.RIDGE_SHARE)};
const float RIDGE_SHARPNESS = ${glsl(CoronaLook.RIDGE_SHARPNESS)};
const float RIDGE_STREAMER_DAMPING = ${glsl(CoronaLook.RIDGE_STREAMER_DAMPING)};
const float INNER_TEXTURE = ${glsl(CoronaLook.INNER_TEXTURE)};
const float FLOW_PERIOD = ${glsl(CoronaLook.FLOW_PERIOD)};
const float PLUME_SEED = ${glsl(CoronaLook.PLUME_SEED)};
const float CLUMP_SEED = ${glsl(CoronaLook.CLUMP_SEED)};
const float RING_WIDTH = ${glsl(CoronaLook.RING_WIDTH)};
const float RING_GAIN = ${glsl(CoronaLook.RING_GAIN)};
const float BLOOM_SCALE = ${glsl(CoronaLook.BLOOM_SCALE)};
const float BLOOM_FALLOFF = ${glsl(CoronaLook.BLOOM_FALLOFF)};
const float BLOOM_GAIN = ${glsl(CoronaLook.BLOOM_GAIN)};
const float BACKGROUND_SCALE = ${glsl(CoronaLook.BACKGROUND_SCALE)};
const float BACKGROUND_FALLOFF = ${glsl(CoronaLook.BACKGROUND_FALLOFF)};
const float BACKGROUND_GAIN = ${glsl(CoronaLook.BACKGROUND_GAIN)};
const float2 UNEVENNESS_ORDERS = ${float2(CoronaLook.UNEVENNESS_ORDER_1, CoronaLook.UNEVENNESS_ORDER_2)};
const float2 UNEVENNESS_WEIGHTS = ${float2(CoronaLook.UNEVENNESS_WEIGHT_1, CoronaLook.UNEVENNESS_WEIGHT_2)};
const float3 INNER_TINT = ${float3(CoronaLook.INNER_TINT)};
const float3 OUTER_TINT = ${float3(CoronaLook.OUTER_TINT)};
const float TINT_BLEND_HEIGHT = ${glsl(CoronaLook.TINT_BLEND_HEIGHT)};
const float3 SKY = ${float3(CoronaLook.SKY)};
const float SKY_VIGNETTE_INNER = ${glsl(CoronaLook.SKY_VIGNETTE_INNER)};
const float3 SKY_GLOW = ${float3(CoronaLook.SKY_GLOW)};
const float SKY_GLOW_SCALE = ${glsl(CoronaLook.SKY_GLOW_SCALE)};
const float DISPLAY_GAMMA = ${glsl(CoronaLook.DISPLAY_GAMMA)};
const float STAR_CELL = ${glsl(CoronaLook.STAR_CELL)};
const float STAR_DENSITY = ${glsl(CoronaLook.STAR_DENSITY)};
const float STAR_MIN_RADIUS = ${glsl(CoronaLook.STAR_MIN_RADIUS)};
const float STAR_BRIGHTNESS = ${glsl(CoronaLook.STAR_BRIGHTNESS)};
const float STAR_SIZE_PX = ${glsl(CoronaLook.STAR_SIZE_PX)};
const float3 STAR_TINT = ${float3(CoronaLook.STAR_TINT)};
const float TWINKLE_DEPTH = ${glsl(CoronaLook.TWINKLE_DEPTH)};
const float STAR_COLUMN_SALT = ${glsl(CoronaLook.STAR_COLUMN_SALT)};
const float STAR_ROW_SALT = ${glsl(CoronaLook.STAR_ROW_SALT)};
const float STAR_CELL_OFFSET = ${glsl(CoronaLook.STAR_CELL_OFFSET)};
const float STAR_X_SALT = ${glsl(CoronaLook.STAR_X_SALT)};
const float STAR_Y_SALT = ${glsl(CoronaLook.STAR_Y_SALT)};
const float STAR_BRIGHTNESS_SALT = ${glsl(CoronaLook.STAR_BRIGHTNESS_SALT)};
const float STAR_PACE_SALT = ${glsl(CoronaLook.STAR_PACE_SALT)};
const float STAR_MARGIN = ${glsl(CoronaLook.STAR_MARGIN)};
const float STAR_PACES = ${glsl(CoronaLook.STAR_PACES)};
const float BEAD_OFFSET_2 = ${glsl(CoronaLook.BEAD_OFFSET_2)};
const float BEAD_OFFSET_3 = ${glsl(CoronaLook.BEAD_OFFSET_3)};
const float BEAD_WEIGHT_2 = ${glsl(CoronaLook.BEAD_WEIGHT_2)};
const float BEAD_WEIGHT_3 = ${glsl(CoronaLook.BEAD_WEIGHT_3)};
const float BEAD_RADIAL_SIZE = ${glsl(CoronaLook.BEAD_RADIAL_SIZE)};
const float BEAD_TANGENTIAL_SIZE = ${glsl(CoronaLook.BEAD_TANGENTIAL_SIZE)};
const float BEAD_GAIN = ${glsl(CoronaLook.BEAD_GAIN)};
const float BEAD_LIFT = ${glsl(CoronaLook.BEAD_LIFT)};
const float GLARE_TIGHT_GAIN = ${glsl(CoronaLook.GLARE_TIGHT_GAIN)};
const float GLARE_TIGHT_SCALE = ${glsl(CoronaLook.GLARE_TIGHT_SCALE)};
const float GLARE_WIDE_GAIN = ${glsl(CoronaLook.GLARE_WIDE_GAIN)};
const float GLARE_WIDE_SCALE = ${glsl(CoronaLook.GLARE_WIDE_SCALE)};
const float SPIKE_WIDTH_PX = ${glsl(CoronaLook.SPIKE_WIDTH_PX)};
const float SPIKE_GAIN = ${glsl(CoronaLook.SPIKE_GAIN)};
const float SPIKE_LENGTH = ${glsl(CoronaLook.SPIKE_LENGTH)};
const float2 SPIKE_NORMAL_1 = ${spikeNormal(line = 0)};
const float2 SPIKE_NORMAL_2 = ${spikeNormal(line = 1)};
const float2 SPIKE_NORMAL_3 = ${spikeNormal(line = 2)};
const float GLINT_FADE_START = ${glsl(CoronaLook.GLINT_FADE_START)};
const float GLINT_REACH = ${glsl(CoronaLook.GLINT_REACH)};
const float3 BEAD_TINT = ${float3(CoronaLook.BEAD_TINT)};
const float DITHER_AMPLITUDE = ${glsl(CoronaLook.DITHER_AMPLITUDE)};

uniform float2 uSize;
uniform float4 uStreamerPhases;
uniform float4 uShapePhases;
uniform float4 uFlow;
uniform float4 uGains;
uniform float2 uGlint;
uniform float3 uBeadFlicker;
uniform float uTwinkle;

// A pseudo-random value in [0, 1) (Dave Hoskins' "hash without sine"); see CoronaNoise.hash.
float hash11(float n) {
    n = fract(n * 0.1031);
    n *= n + 33.33;
    n *= n + n;
    return fract(n);
}

// Smooth value noise in [0, 1], repeating every period.x cells along x and period.y cells along y.
float valueNoise(float2 p, float2 period, float seed) {
    float2 cell = floor(p);
    float2 f = p - cell;
    float2 weight = f * f * (3.0 - 2.0 * f);
    float2 lowCell = mod(cell, period);
    float2 highCell = mod(cell + 1.0, period);
    float lowerRow = lowCell.y * ROW_STRIDE + seed;
    float upperRow = highCell.y * ROW_STRIDE + seed;
    float lower = mix(hash11(lowCell.x + lowerRow), hash11(highCell.x + lowerRow), weight.x);
    float upper = mix(hash11(lowCell.x + upperRow), hash11(highCell.x + upperRow), weight.x);
    return mix(lower, upper, weight.y);
}

// Smooth value noise in [0, 1] along one axis, repeating every period cells; see CoronaNoise.valueNoise1D.
float valueNoise1(float x, float period, float seed) {
    float cell = floor(x);
    float f = x - cell;
    float weight = f * f * (3.0 - 2.0 * f);
    float left = hash11(mod(cell, period) + seed);
    float right = hash11(mod(cell + 1.0, period) + seed);
    return mix(left, right, weight);
}

// Where the helmet streamers rise, from 0 to 1: harmonics place a few broad bases round the circle, the equator
// envelope keeps them at low latitudes, and only the crest of the strongest streamer reaches 1.
float streamerField(float footpoint, float equatorAngle) {
    float4 waves = sin(STREAMER_ORDERS * footpoint + uStreamerPhases);
    float harmonics = STREAMER_HARMONICS_MEAN + dot(STREAMER_WEIGHTS, waves);
    float equator = pow(abs(cos(equatorAngle)), EQUATOR_POWER);
    float fieldRange = STREAMER_FIELD_HIGH - STREAMER_FIELD_LOW;
    return clamp((equator * harmonics - STREAMER_FIELD_LOW) / fieldRange, 0.0, 1.0);
}

// A helmet streamer's light: raising the field to a power that grows with height narrows each streamer from a
// broad base into a long thin wing, and a longer reach stretches it outwards.
float streamerLight(float field, float height, float reach) {
    float reachHeight = height / reach;
    float taper = 1.0 + STREAMER_TAPER * reachHeight;
    float profile = pow(1.0 + reachHeight / STREAMER_SCALE, -STREAMER_FALLOFF);
    return pow(field, taper) * profile;
}

// Polar plumes: short, thin, near-radial rays at high latitudes.
float plumeLight(float turn, float latitude, float height) {
    float pole = pow(latitude, POLE_POWER);
    float2 cell = float2(turn * PLUME_CELLS, height * PLUME_RADIAL - uFlow.x);
    float rays = valueNoise(cell, float2(PLUME_CELLS, FLOW_PERIOD), PLUME_SEED);
    float profile = pow(1.0 + height / PLUME_SCALE, -PLUME_FALLOFF);
    return pole * rays * rays * profile;
}

// Fine radial filaments: noise stretched along the rays (many cells round the circle, few along the radius),
// flowing slowly outwards, with thin bright strands from ridged noise. They are crisper towards the poles,
// smoother inside streamers, and clumped round the circle. Returns a brightness factor around 1.
float filamentTexture(float turn, float height, float latitude, float field) {
    float3 around = turn * FILAMENT_CELLS;
    float3 along = height * FILAMENT_RADIAL - uFlow.yzw;
    float fine = valueNoise(float2(around.x, along.x), float2(FILAMENT_CELLS.x, FLOW_PERIOD), FILAMENT_SEEDS.x);
    float finer = valueNoise(float2(around.y, along.y), float2(FILAMENT_CELLS.y, FLOW_PERIOD), FILAMENT_SEEDS.y);
    float finest = valueNoise(float2(around.z, along.z), float2(FILAMENT_CELLS.z, FLOW_PERIOD), FILAMENT_SEEDS.z);
    float even = dot(FILAMENT_WEIGHTS, float3(fine, finer, finest)) * 2.0 - 1.0;
    float ridges = pow(1.0 - abs(2.0 * finer - 1.0), RIDGE_SHARPNESS) * 2.0 - 1.0;
    float ridgeShare = RIDGE_SHARE * (1.0 - RIDGE_STREAMER_DAMPING * field);
    float filaments = mix(even, ridges, ridgeShare);

    float heightShare = mix(FILAMENT_BASE_SHARE, 1.0, smoothstep(0.0, FILAMENT_RAMP_HEIGHT, height));
    float latitudeShare = mix(EQUATOR_TEXTURE, POLE_TEXTURE, latitude);
    float clumps = mix(CLUMP_LOW, CLUMP_HIGH, valueNoise1(turn * CLUMP_CELLS, CLUMP_CELLS, CLUMP_SEED));
    float strength = FILAMENT_CONTRAST * heightShare * latitudeShare * clumps;
    return 1.0 + filaments * strength;
}

// The inner (K) corona: an intense thin ring on the limb with a steep bloom.
float innerCoronaLight(float height) {
    float ring = RING_GAIN * exp(-height / RING_WIDTH);
    float bloom = BLOOM_GAIN * pow(1.0 + height / BLOOM_SCALE, -BLOOM_FALLOFF);
    return ring + bloom;
}

// The pearly, near-isotropic background corona, which falls off faster than the streamers.
float backgroundLight(float height) {
    return BACKGROUND_GAIN * pow(1.0 + height / BACKGROUND_SCALE, -BACKGROUND_FALLOFF);
}

// All the corona's light at one point, before colour.
float coronaLight(float theta, float height) {
    // Domain warp in polar coordinates: a bend that changes along the radius, so the rays curve gently.
    float rise = height / (1.0 + height);
    float2 bendWaves = sin(BEND_ORDERS * theta + uShapePhases.xy + BEND_TWISTS * height);
    float equatorAngle = theta - EQUATOR_TILT + BEND * dot(BEND_WEIGHTS, bendWaves) * rise;

    // Superradial expansion: higher up, the texture comes from a footpoint nearer the pole.
    float footpoint = equatorAngle + FAN * rise * sin(2.0 * equatorAngle);
    float turn = footpoint / TAU;
    float latitude = abs(sin(equatorAngle));

    float field = streamerField(footpoint, equatorAngle);
    float plumes = plumeLight(turn, latitude, height);
    float strands = filamentTexture(turn, height, latitude, field);

    // The inner corona and the background are unevenly bright round the limb; the inner one is less textured.
    float2 unevenWaves = sin(UNEVENNESS_ORDERS * theta + uShapePhases.zw);
    float unevenness = 1.0 + dot(UNEVENNESS_WEIGHTS, unevenWaves);
    float innerTexture = 1.0 + (strands - 1.0) * INNER_TEXTURE;
    float inner = innerCoronaLight(height) * unevenness * innerTexture;
    float background = backgroundLight(height) * unevenness;
    float plumesAndBackground = PLUME_GAIN * plumes + background;

    // The gains (uGains: inner corona, streamer brightness, streamer reach, exposure).
    float streamers = STREAMER_GAIN * uGains.y * streamerLight(field, height, uGains.z);
    float outer = (streamers + plumesAndBackground) * strands;
    return (inner * uGains.x + outer) * uGains.w;
}

// The twilight sky: deep blue-black, a little brighter near the corona.
float3 skyLight(float radiusFraction, float height) {
    float vignette = 1.0 - smoothstep(SKY_VIGNETTE_INNER, 1.0, radiusFraction);
    float glow = exp(-height / SKY_GLOW_SCALE);
    return SKY * vignette + SKY_GLOW * glow;
}

// A few faint stars far out: at most one per grid cell, each with its own brightness and twinkle.
float starLight(float2 p, float r, float moonRadiusPx) {
    if (r < STAR_MIN_RADIUS) {
        return 0.0;
    }
    float2 cell = floor(p / STAR_CELL);
    float pick = hash11(cell.x * STAR_COLUMN_SALT + cell.y * STAR_ROW_SALT + STAR_CELL_OFFSET);
    if (pick < 1.0 - STAR_DENSITY) {
        return 0.0;
    }
    float2 jitter = float2(hash11(pick * STAR_X_SALT), hash11(pick * STAR_Y_SALT));
    float2 star = (cell + STAR_MARGIN + (1.0 - 2.0 * STAR_MARGIN) * jitter) * STAR_CELL;
    float distancePx = length(p - star) * moonRadiusPx;
    float glow = exp(-distancePx * distancePx / (STAR_SIZE_PX * STAR_SIZE_PX));
    float brightness = STAR_BRIGHTNESS * hash11(pick * STAR_BRIGHTNESS_SALT);
    float pace = 1.0 + floor(STAR_PACES * hash11(pick * STAR_PACE_SALT));
    float twinkle = 1.0 - TWINKLE_DEPTH * (0.5 + 0.5 * sin(pace * uTwinkle + TAU * pick));
    return glow * brightness * twinkle;
}

// One Baily's bead: a brilliant spot just outside the limb at the given angle, elongated along the limb.
float beadSpotLight(float2 p, float angle) {
    float2 outward = float2(cos(angle), sin(angle));
    float radial = dot(p, outward) - 1.0 - BEAD_LIFT;
    float tangential = dot(p, float2(-outward.y, outward.x));
    float across = radial / BEAD_RADIAL_SIZE;
    float along = tangential / BEAD_TANGENTIAL_SIZE;
    return BEAD_GAIN * exp(-(across * across + along * along));
}

// Baily's beads: sunlight through lunar valleys, a short string of brilliant points on the limb, each
// shimmering on its own, with glare and diffraction spikes round the main one (uGlint: angle, strength).
float bailysBeads(float2 p, float moonRadiusPx) {
    float2 mainBead = float2(cos(uGlint.x), sin(uGlint.x)) * (1.0 + BEAD_LIFT);
    float2 offset = p - mainBead;
    float distance = length(offset);
    if (distance >= GLINT_REACH) {
        return 0.0;
    }

    float mainSpot = uBeadFlicker.x * beadSpotLight(p, uGlint.x);
    float secondSpot = BEAD_WEIGHT_2 * uBeadFlicker.y * beadSpotLight(p, uGlint.x + BEAD_OFFSET_2);
    float thirdSpot = BEAD_WEIGHT_3 * uBeadFlicker.z * beadSpotLight(p, uGlint.x + BEAD_OFFSET_3);
    float tightGlare = GLARE_TIGHT_GAIN * exp(-distance / GLARE_TIGHT_SCALE);
    float wideGlare = GLARE_WIDE_GAIN * exp(-distance / GLARE_WIDE_SCALE);

    // Across each spike line the light is a Gaussian measured in pixels, so the spikes stay crisp at any size.
    float3 acrossLines = float3(dot(offset, SPIKE_NORMAL_1), dot(offset, SPIKE_NORMAL_2), dot(offset, SPIKE_NORMAL_3));
    float3 acrossPx = acrossLines * (moonRadiusPx / SPIKE_WIDTH_PX);
    float3 lineProfiles = exp(-acrossPx * acrossPx);
    float spikes = SPIKE_GAIN * exp(-distance / SPIKE_LENGTH) * (lineProfiles.x + lineProfiles.y + lineProfiles.z);

    float window = 1.0 - smoothstep(GLINT_FADE_START, GLINT_REACH, distance);
    float halo = (tightGlare + wideGlare + spikes) * uBeadFlicker.x;
    return (mainSpot + secondSpot + thirdSpot + halo) * uGlint.y * window;
}

half4 main(float2 fragCoord) {
    float side = min(uSize.x, uSize.y);
    float moonRadiusPx = side * MOON_RADIUS_FRACTION;
    float2 offset = fragCoord - 0.5 * uSize;
    // In lunar radii from the centre, y up, so angles run anticlockwise from three o'clock.
    float2 p = float2(offset.x, -offset.y) / moonRadiusPx;
    float r = length(p);

    // The Moon: an exact black disk with a one-pixel antialiased limb.
    float clearOfMoon = clamp((r - 1.0) * moonRadiusPx + 0.5, 0.0, 1.0);
    if (clearOfMoon <= 0.0) {
        return half4(0.0, 0.0, 0.0, 1.0);
    }

    // Everything fades out within the circle inscribed in the bounds, so no edge of them ever shows.
    float radiusFraction = r * moonRadiusPx / (0.5 * side);
    float fade = 1.0 - smoothstep(RADIAL_FADE_START, RADIAL_FADE_END, radiusFraction);
    if (fade <= 0.0) {
        return half4(0.0);
    }

    float height = max(r - 1.0, 0.0);
    float theta = atan(p.y, p.x);

    // Linear light: the tinted corona, the sky, the stars and (while thinking) the Baily's beads.
    float3 tint = mix(INNER_TINT, OUTER_TINT, smoothstep(0.0, TINT_BLEND_HEIGHT, height));
    float3 light = tint * coronaLight(theta, height) + skyLight(radiusFraction, height);
    light += STAR_TINT * starLight(p, r, moonRadiusPx);
    if (uGlint.y > 0.0) {
        light += BEAD_TINT * bailysBeads(p, moonRadiusPx);
    }

    // Tone mapping like film: bright light saturates softly instead of clipping. Then encode for display.
    half3 display = half3(pow(1.0 - exp(-light), float3(1.0 / DISPLAY_GAMMA)));
    display *= half(clearOfMoon * fade);

    // Interleaved gradient noise dithers away banding in the dark gradients (but leaves black black).
    half brightness = max(display.r, max(display.g, display.b));
    half dither = half(fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715)))) - 0.5);
    display = max(display + dither * half(DITHER_AMPLITUDE) * clamp(brightness * 255.0, 0.0, 1.0), half3(0.0));

    // Light over transparency (premultiplied): opaque where the Moon is, elsewhere just enough to hold the light.
    half moonCover = half(1.0 - clearOfMoon);
    half alpha = clamp(moonCover + max(display.r, max(display.g, display.b)), 0.0, 1.0);
    return half4(display, alpha);
}
"""

    /** [value] as an AGSL float literal: plain decimal digits, never an exponent. */
    private fun glsl(value: Float): String {
        val decimal = BigDecimal(value.toString())
        val plain = decimal.toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }

    private fun float2(x: Float, y: Float): String = "float2(${glsl(x)}, ${glsl(y)})"

    private fun float3(x: Float, y: Float, z: Float): String = "float3(${glsl(x)}, ${glsl(y)}, ${glsl(z)})"

    private fun float3(rgb: Rgb): String = float3(rgb.red, rgb.green, rgb.blue)

    private fun float4(quad: Quad): String {
        val components = listOf(quad.x, quad.y, quad.z, quad.w)
        val literals = components.joinToString(separator = ", ") { component -> glsl(component) }
        return "float4($literals)"
    }

    /** The unit normal of spike [line], so that its dot product with an offset is the distance across the line. */
    private fun spikeNormal(line: Int): String {
        val direction = CoronaLook.SPIKE_DIRECTIONS[line]
        return float2(sin(direction), -cos(direction))
    }
}
