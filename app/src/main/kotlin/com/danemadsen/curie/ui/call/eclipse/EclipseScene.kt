package com.danemadsen.curie.ui.call.eclipse

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import com.danemadsen.curie.ui.call.EclipseMood

/**
 * The eclipse, drawn by the AGSL shader when [useShader] is set and the device supports it (Android 13+),
 * otherwise by the bitmap fallback. On a light page ([onLightBackground]) it is drawn as its negative
 * (see [drawAsNegative]). Previews show one still frame.
 */
@Composable
internal fun EclipseScene(
    mood: EclipseMood,
    level: () -> Float,
    useShader: Boolean,
    onLightBackground: Boolean,
    modifier: Modifier = Modifier,
) {
    val isPreview = LocalInspectionMode.current
    val frame = rememberEclipseFrame(mood = mood, level = level, animate = !isPreview)
    val shader = remember(useShader, isPreview) {
        val wantsShader = useShader && !isPreview
        if (wantsShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            EclipseShader.compileOrNull()
        } else {
            null
        }
    }

    // A layer of its own, so redrawing every frame does not re-record the screen around it.
    val pageModifier = if (onLightBackground) modifier.drawAsNegative() else modifier
    val sceneModifier = pageModifier.graphicsLayer()

    if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderEclipse(shader = shader, frame = frame, modifier = sceneModifier)
    } else {
        FallbackEclipse(frame = frame, renderSynchronously = isPreview, modifier = sceneModifier)
    }
}

/**
 * The current [EclipseFrame], advanced on display frames while [animate] is set (a still frame in previews).
 * [mood] and [level] are read on each frame, so changing them never restarts the animation. Calm frames are
 * published at about 30 a second ([FramePacer]); with the system's animations turned off the corona holds
 * still and only reacts to the voice.
 */
@Composable
private fun rememberEclipseFrame(mood: EclipseMood, level: () -> Float, animate: Boolean): State<EclipseFrame> {
    // Live, the level is smoothed up from silence; a still frame shows the level as given.
    val frame = remember {
        val firstLevel = if (animate) 0f else level()
        mutableStateOf(EclipseMotion.stillFrame(mood, firstLevel))
    }
    val currentMood by rememberUpdatedState(mood)
    val currentLevel by rememberUpdatedState(level)

    LaunchedEffect(animate) {
        if (!animate) {
            return@LaunchedEffect
        }

        // Compose keeps this in step with the system's animator duration scale, which is 0 for "Remove animations".
        val durationScale = coroutineContext[MotionDurationScale]
        val motion = EclipseMotion(initialMood = currentMood)
        val pacer = FramePacer()
        while (true) {
            withFrameNanos { frameTimeNanos ->
                val motionAllowed = durationScale == null || durationScale.scaleFactor > 0f
                val nextFrame = motion.advance(frameTimeNanos, currentMood, currentLevel(), motionAllowed)
                if (pacer.shouldDraw(frameTimeNanos, motion.isCalm)) {
                    frame.value = nextFrame
                }
            }
        }
    }

    return frame
}
