package com.danemadsen.curie.ui.call.eclipse

import com.danemadsen.curie.ui.call.EclipseMood

/**
 * How the eclipse behaves in one mood. Moods cross-fade by interpolating these numbers ([lerp]), so nothing
 * ever jumps.
 *
 * @property breathingDepth how much the corona breathes with the slow resting rhythm, from 0 to 1
 * @property innerLevelGain how much the audio level brightens the inner corona (the user's voice)
 * @property streamerLevelGain how much the audio level lengthens and brightens the streamers (Curie's voice)
 * @property glintStrength how bright the Baily's bead travelling round the limb is, from 0 to 1
 * @property driftRate how fast the corona's shape morphs, relative to normal
 * @property shimmerRate how fast the filaments flow and shimmer, relative to normal
 */
internal data class EclipseStyle(
    val breathingDepth: Float,
    val innerLevelGain: Float,
    val streamerLevelGain: Float,
    val glintStrength: Float,
    val driftRate: Float,
    val shimmerRate: Float,
) {
    /**
     * Whether nothing in this style moves quickly: no glint travels round the limb and the corona ignores the
     * voice, so only the slow drift and breathing remain.
     */
    val isCalm: Boolean
        get() = glintStrength == 0f && innerLevelGain == 0f && streamerLevelGain == 0f

    /** This style moved [fraction] of the way towards [target]. */
    fun lerp(target: EclipseStyle, fraction: Float): EclipseStyle = EclipseStyle(
        breathingDepth = mix(breathingDepth, target.breathingDepth, fraction),
        innerLevelGain = mix(innerLevelGain, target.innerLevelGain, fraction),
        streamerLevelGain = mix(streamerLevelGain, target.streamerLevelGain, fraction),
        glintStrength = mix(glintStrength, target.glintStrength, fraction),
        driftRate = mix(driftRate, target.driftRate, fraction),
        shimmerRate = mix(shimmerRate, target.shimmerRate, fraction),
    )

    companion object {
        /** Calm, slowly breathing. */
        private val LISTENING = EclipseStyle(
            breathingDepth = 1f,
            innerLevelGain = 0f,
            streamerLevelGain = 0f,
            glintStrength = 0f,
            driftRate = 1f,
            shimmerRate = 1f,
        )

        /** The inner corona brightens with the user's voice. */
        private val USER_SPEAKING = EclipseStyle(
            breathingDepth = 0.4f,
            innerLevelGain = 0.5f,
            streamerLevelGain = 0f,
            glintStrength = 0f,
            driftRate = 1f,
            shimmerRate = 1.3f,
        )

        /** A Baily's bead glints its way round the limb while the filaments shimmer. */
        private val THINKING = EclipseStyle(
            breathingDepth = 0.3f,
            innerLevelGain = 0f,
            streamerLevelGain = 0f,
            glintStrength = 1f,
            driftRate = 1.3f,
            shimmerRate = 1.8f,
        )

        /** The streamers stretch and brighten with Curie's voice, as if the corona breathed with her speech. */
        private val SPEAKING = EclipseStyle(
            breathingDepth = 0.3f,
            innerLevelGain = 0.1f,
            streamerLevelGain = 1f,
            glintStrength = 0f,
            driftRate = 1.2f,
            shimmerRate = 1.3f,
        )

        /** Still and calm: no breathing or reactions, and the corona barely changes. */
        private val IDLE = EclipseStyle(
            breathingDepth = 0f,
            innerLevelGain = 0f,
            streamerLevelGain = 0f,
            glintStrength = 0f,
            driftRate = 0.15f,
            shimmerRate = 0.15f,
        )

        fun forMood(mood: EclipseMood): EclipseStyle = when (mood) {
            EclipseMood.LISTENING -> LISTENING
            EclipseMood.USER_SPEAKING -> USER_SPEAKING
            EclipseMood.THINKING -> THINKING
            EclipseMood.SPEAKING -> SPEAKING
            EclipseMood.IDLE -> IDLE
        }
    }
}
