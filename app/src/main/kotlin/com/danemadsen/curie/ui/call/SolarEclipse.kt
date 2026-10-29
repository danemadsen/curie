package com.danemadsen.curie.ui.call

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.danemadsen.curie.ui.call.eclipse.EclipseScene

/** What is happening in the call, which sets how the eclipse moves. */
enum class EclipseMood {
    /** Waiting for the user to speak: calm, slow breathing. */
    LISTENING,

    /** The user is speaking: the inner corona brightens with their voice. */
    USER_SPEAKING,

    /** Curie is thinking: a Baily's bead glints its way round the Moon's edge. */
    THINKING,

    /** Curie is speaking: the streamers stretch and brighten with her voice. */
    SPEAKING,

    /** Nothing is happening yet (connecting): still and calm. */
    IDLE,
}

/**
 * A photographic total solar eclipse, the centrepiece of the call screen: a black Moon ringed by the bright
 * inner corona, long streamers and fine filaments that slowly morph, and a deep twilight sky. It fills its
 * bounds (meant to be a square about as wide as the screen), centred, and fades out before reaching their
 * edges, so it blends into any dark background. It is purely decorative.
 *
 * [level] is the audio level from 0 to 1 (the user's voice while they speak, Curie's while she speaks). It is
 * read on every frame while drawing, so back it with something cheap to read such as a volatile field; it
 * never recomposes, and it is smoothed here, so raw meter readings are fine. Changing [mood] cross-fades
 * to the new behaviour over 400 ms.
 *
 * On Android 13 and later an AGSL shader renders the eclipse; while it is calm (listening or idle) it redraws
 * about 30 times a second, otherwise on every frame. On earlier versions, or if the shader does not compile, a
 * still corona is rendered once in the background (a soft placeholder shows meanwhile) and animated cheaply.
 * With the system's "Remove animations" setting on, the corona holds still and only reacts to [level].
 */
@Composable
fun SolarEclipse(mood: EclipseMood, level: () -> Float, modifier: Modifier = Modifier) {
    EclipseScene(mood = mood, level = level, useShader = true, modifier = modifier)
}

/** Previews show each mood with the voice at a typical level. */
private const val PREVIEW_LEVEL = 0.6f

internal class EclipseMoodPreviewProvider : PreviewParameterProvider<EclipseMood> {
    override val values: Sequence<EclipseMood> = EclipseMood.entries.asSequence()
}

@Preview(widthDp = 360, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SolarEclipsePreview(@PreviewParameter(EclipseMoodPreviewProvider::class) mood: EclipseMood) {
    SolarEclipse(mood = mood, level = { PREVIEW_LEVEL }, modifier = Modifier.fillMaxSize())
}
