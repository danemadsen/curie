package com.danemadsen.curie.ui.call.eclipse

import com.danemadsen.curie.ui.call.EclipseMood
import kotlin.math.PI
import kotlin.math.sin

/**
 * Everything one frame of the eclipse needs, for either renderer.
 *
 * @property driftSeconds the corona's shape clock, see [CoronaShape.phasesAt]
 * @property shimmerSeconds the filaments' flow clock, see [CoronaShape.flowAt]
 * @property gains how the corona's light is scaled: its parts' brightness, the streamers' reach and the exposure
 * @property glintAngle where the Baily's bead is on the limb, in radians anticlockwise from three o'clock
 * @property glintStrength how bright the bead is, from 0 (hidden) to 1
 * @property twinklePhase how far the stars' twinkle has run, in radians within [0, 2π)
 */
internal data class EclipseFrame(
    val driftSeconds: Double,
    val shimmerSeconds: Double,
    val gains: CoronaGains,
    val glintAngle: Float,
    val glintStrength: Float,
    val twinklePhase: Float,
)

/**
 * The eclipse's animation, advanced once per display frame: the clocks that morph the corona and flow its
 * filaments, the smoothed audio level, the slow breathing, the glint's journey round the limb and the
 * cross-fade from one mood's [EclipseStyle] to the next. The clocks run at the style's rates, so slowing
 * down or speeding up never makes the corona jump.
 */
internal class EclipseMotion(initialMood: EclipseMood) {
    private val smoother = LevelSmoother(LEVEL_ATTACK_SECONDS, LEVEL_RELEASE_SECONDS)
    private var mood = initialMood
    private var style = EclipseStyle.forMood(initialMood)
    private var fadeFrom = style
    private var fadeStartNanos = 0L
    private var lastFrameNanos: Long? = null
    private var driftSeconds = 0.0
    private var shimmerSeconds = 0.0
    private var breathPhase = 0.0
    private var glintAngle = GLINT_START_ANGLE

    /** Whether the eclipse is in a calm style, which moves so slowly that a lower frame rate looks the same. */
    val isCalm: Boolean
        get() = style.isCalm

    /**
     * Moves the animation on to [frameTimeNanos] in [mood] with the raw audio [level] (0 to 1). Unless
     * [motionAllowed] (the system's "Remove animations" setting is off), the corona holds still: its clocks,
     * its breathing and the glint stop, and only the reaction to the level and the moods' cross-fades remain.
     */
    fun advance(frameTimeNanos: Long, mood: EclipseMood, level: Float, motionAllowed: Boolean = true): EclipseFrame {
        val elapsedSeconds = elapsedSince(frameTimeNanos)
        updateStyle(frameTimeNanos, mood)

        val clockSeconds = if (motionAllowed) elapsedSeconds else 0.0
        driftSeconds += clockSeconds * style.driftRate
        shimmerSeconds += clockSeconds * style.shimmerRate
        breathPhase = (breathPhase + clockSeconds * BREATH_SPEED).mod(FULL_TURN)
        glintAngle = (glintAngle - clockSeconds * GLINT_SPEED).mod(FULL_TURN)

        val smoothedLevel = smoother.update(level, elapsedSeconds.toFloat())
        val breath = sin(breathPhase).toFloat() * style.breathingDepth
        return frameFor(style, breath, smoothedLevel, driftSeconds, shimmerSeconds, glintAngle)
    }

    private fun elapsedSince(frameTimeNanos: Long): Double {
        val previousNanos = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previousNanos == null) {
            return 0.0
        }

        val seconds = (frameTimeNanos - previousNanos) / NANOS_PER_SECOND
        return seconds.coerceIn(0.0, MAX_FRAME_SECONDS)
    }

    /** Starts a cross-fade when the mood changes, from wherever the style is now, and moves it along. */
    private fun updateStyle(frameTimeNanos: Long, newMood: EclipseMood) {
        if (newMood != mood) {
            mood = newMood
            fadeFrom = style
            fadeStartNanos = frameTimeNanos
        }

        val target = EclipseStyle.forMood(mood)
        val fadeNanos = (frameTimeNanos - fadeStartNanos).toDouble()
        val progress = (fadeNanos / MOOD_FADE_NANOS).coerceIn(0.0, 1.0).toFloat()
        style = fadeFrom.lerp(target, smoothCurve(progress))
    }

    companion object {
        /** The smoothed level rises in about 60 ms and falls back in about 300 ms. */
        const val LEVEL_ATTACK_SECONDS = 0.06f
        const val LEVEL_RELEASE_SECONDS = 0.3f

        /** Moods cross-fade over 400 ms. */
        const val MOOD_FADE_NANOS = 400_000_000.0

        /** One slow breath every six seconds. */
        const val BREATH_PERIOD_SECONDS = 6.0

        /** The Baily's bead goes once round the limb (clockwise) every twelve seconds. */
        const val GLINT_PERIOD_SECONDS = 12.0

        /** A longer pause (the app was in the background, say) counts as this much time. */
        const val MAX_FRAME_SECONDS = 0.1

        /** A full breath raises and lowers the exposure of all the corona's light by this share. */
        const val BREATH_EXPOSURE = 0.13f

        /** A full breath stretches and shrinks the streamers' reach by this share. */
        const val REACH_BREATH = 0.08f

        /** At full level and full [EclipseStyle.streamerLevelGain], the streamers brighten and reach this much more. */
        const val STREAMER_LEVEL_BRIGHTNESS = 0.6f
        const val STREAMER_LEVEL_REACH = 0.6f

        /** The streamers' gains while Curie speaks at her loudest, which the fallback renders ahead of time. */
        val LOUDEST_VOICE_GAINS = CoronaGains(
            streamers = 1f + STREAMER_LEVEL_BRIGHTNESS,
            reach = 1f + STREAMER_LEVEL_REACH,
        )

        /** The lowest and highest exposure any frame can have: a full breath out while thinking, and in. */
        const val LOWEST_EXPOSURE = (1f - BREATH_EXPOSURE) * (1f - CoronaLook.GLINT_EXPOSURE_DIP)
        const val HIGHEST_EXPOSURE = 1f + BREATH_EXPOSURE

        /** The stars twinkle through one cycle every five seconds of shimmer time. */
        private const val TWINKLE_PERIOD_SECONDS = 5.0

        /** Where the bead starts: upper left, where the eye looks first. */
        private const val GLINT_START_ANGLE = 2.2

        private const val NANOS_PER_SECOND = 1e9
        private const val FULL_TURN = 2.0 * PI
        private const val BREATH_SPEED = FULL_TURN / BREATH_PERIOD_SECONDS
        private const val GLINT_SPEED = FULL_TURN / GLINT_PERIOD_SECONDS
        private const val TWINKLE_SPEED = FULL_TURN / TWINKLE_PERIOD_SECONDS

        /** A frame that does not move, for previews: [mood]'s style at rest, with [level] taken as already smoothed. */
        fun stillFrame(mood: EclipseMood, level: Float): EclipseFrame {
            val style = EclipseStyle.forMood(mood)
            val restingLevel = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
            return frameFor(
                style = style,
                breath = 0f,
                level = restingLevel,
                driftSeconds = 0.0,
                shimmerSeconds = 0.0,
                glintAngle = GLINT_START_ANGLE,
            )
        }

        private fun frameFor(
            style: EclipseStyle,
            breath: Float,
            level: Float,
            driftSeconds: Double,
            shimmerSeconds: Double,
            glintAngle: Double,
        ): EclipseFrame {
            val streamerResponse = style.streamerLevelGain * level
            val breathExposure = 1f + BREATH_EXPOSURE * breath
            val glintExposure = 1f - CoronaLook.GLINT_EXPOSURE_DIP * style.glintStrength
            val gains = CoronaGains(
                inner = 1f + style.innerLevelGain * level,
                streamers = 1f + STREAMER_LEVEL_BRIGHTNESS * streamerResponse,
                reach = 1f + REACH_BREATH * breath + STREAMER_LEVEL_REACH * streamerResponse,
                exposure = breathExposure * glintExposure,
            )
            val twinklePhase = (shimmerSeconds * TWINKLE_SPEED).mod(FULL_TURN)

            return EclipseFrame(
                driftSeconds = driftSeconds,
                shimmerSeconds = shimmerSeconds,
                gains = gains,
                glintAngle = glintAngle.toFloat(),
                glintStrength = style.glintStrength,
                twinklePhase = twinklePhase.toFloat(),
            )
        }
    }
}
