package com.danemadsen.curie.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.min
import com.danemadsen.curie.ui.common.Persona
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The moon covers this fraction of the art's size; the corona fills the rest. */
private const val MOON_DIAMETER_FRACTION = 0.5f

private const val STREAMER_COUNT = 40
private const val GOLDEN_ANGLE = 2.39996f
private const val ROTATION_SECONDS = 120f
private const val BREATH_SECONDS = 6f

// How fast the glow follows the audio: it rises in about 60 ms and settles in about 300 ms

private const val ATTACK_SECONDS = 0.06f
private const val RELEASE_SECONDS = 0.3f

/** Where the "diamond ring" sparkle sits on the rim: up and to the left of the centre. */
private const val SPARKLE_ANGLE = -0.7f * PI.toFloat()

private val SPARKLE_DIRECTION = Offset(cos(SPARKLE_ANGLE), sin(SPARKLE_ANGLE))

/**
 * A total solar eclipse, centred in its bounds and as large as their shorter side: a dark moon
 * ringed by a glowing corona whose streamers drift and breathe. Size it with [modifier].
 *
 * [level] (0 to 1, the loudness of what is heard or said) is read every frame and makes the corona
 * flare, so it can follow the call's audio; it is smoothed here, so raw meter readings are fine.
 * With [assistant] the moon shows the assistant's picture, if it has one.
 */
@Composable
fun Eclipse(
    modifier: Modifier = Modifier,
    assistant: Persona? = null,
    level: () -> Float = { 0f },
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val haloColor = colors.primary
    val coronaColor = if (isDark) colors.onPrimaryContainer else colors.primary
    val moonColor = if (isDark) Color.Black else colors.inverseSurface
    val isPreview = LocalInspectionMode.current

    var timeSeconds by remember { mutableFloatStateOf(0f) }
    var glow by remember { mutableFloatStateOf(0f) }
    val currentLevel by rememberUpdatedState(level)

    if (!isPreview) {
        LaunchedEffect(Unit) {
            var previousNanos = withFrameNanos { it }
            val startNanos = previousNanos
            while (true) {
                val frameNanos = withFrameNanos { it }
                val elapsedSeconds = (frameNanos - previousNanos) / 1e9f
                previousNanos = frameNanos
                timeSeconds = (frameNanos - startNanos) / 1e9f

                val target = currentLevel().coerceIn(0f, 1f)
                val timeConstant = if (target > glow) ATTACK_SECONDS else RELEASE_SECONDS
                val step = 1f - exp(-elapsedSeconds / timeConstant)
                glow += (target - glow) * step
            }
        }
    }

    val ringPaint = remember { BlurredRingPaint() }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val artSize = min(maxWidth, maxHeight)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val moonRadius = size.minDimension * MOON_DIAMETER_FRACTION / 2f
            if (moonRadius <= 0f) {
                return@Canvas
            }

            val breath = 0.5f + 0.5f * sin(2f * PI.toFloat() * timeSeconds / BREATH_SECONDS)

            drawHalo(haloColor, moonRadius, breath, glow)
            drawStreamers(coronaColor, moonRadius, timeSeconds, glow)
            drawRing(ringPaint, coronaColor, moonRadius, breath, glow)
            drawCircle(color = moonColor, radius = moonRadius)
            drawSparkle(coronaColor, moonRadius, breath, glow)
        }

        if (assistant?.imageUri != null) {
            AssistantAvatar(assistant = assistant, size = artSize * MOON_DIAMETER_FRACTION)
        }
    }
}

/** A soft accent glow that fades out to about twice the moon's radius, wider when loud. */
private fun DrawScope.drawHalo(color: Color, moonRadius: Float, breath: Float, glow: Float) {
    val haloRadius = moonRadius * (1.9f + 0.15f * breath + 0.5f * glow)
    val alpha = (0.35f + 0.1f * breath + 0.4f * glow).coerceAtMost(1f)
    val moonEdge = moonRadius / haloRadius

    val brush = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        moonEdge to color.copy(alpha = alpha),
        (moonEdge + 1f) / 2f to color.copy(alpha = alpha * 0.25f),
        1f to Color.Transparent,
        center = center,
        radius = haloRadius,
    )
    drawCircle(brush = brush, radius = haloRadius)
}

/**
 * Fine rays that slowly turn, each with its own width, angle jitter and rhythm, so together they
 * read as the corona's streamers rather than a drawn sun.
 */
private fun DrawScope.drawStreamers(color: Color, moonRadius: Float, timeSeconds: Float, glow: Float) {
    val rotation = 2f * PI.toFloat() * timeSeconds / ROTATION_SECONDS
    val spacing = 2f * PI.toFloat() / STREAMER_COUNT

    for (index in 0 until STREAMER_COUNT) {
        // Golden-angle phases spread the rays' rhythms evenly without visible repetition
        val phase = index * GOLDEN_ANGLE
        val speed = 0.3f + (index % 7) * 0.08f
        val wave = 0.5f + 0.5f * sin(timeSeconds * speed + phase)
        val flicker = 0.5f + 0.5f * sin(timeSeconds * speed * 2.3f + phase * 1.7f)

        val length = moonRadius * (0.15f + 0.4f * wave * flicker + 0.5f * glow)
        val jitter = spacing * 0.3f * sin(phase * 3f)
        val angle = rotation + index * spacing + jitter
        val strokeWidth = moonRadius * (0.015f + 0.02f * (0.5f + 0.5f * sin(phase * 5f)))
        val alpha = (0.15f + 0.3f * wave + 0.35f * glow).coerceAtMost(1f)

        val direction = Offset(cos(angle), sin(angle))
        val start = center + direction * (moonRadius * 0.97f)
        val end = center + direction * (moonRadius + length)
        val brush = Brush.linearGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            start = start,
            end = end,
        )

        drawLine(brush = brush, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

/** The bright, blurred inner corona hugging the moon's edge. */
private fun DrawScope.drawRing(
    paint: BlurredRingPaint,
    color: Color,
    moonRadius: Float,
    breath: Float,
    glow: Float,
) {
    val alpha = (0.55f + 0.15f * breath + 0.3f * glow).coerceAtMost(1f)
    val ringColor = color.copy(alpha = alpha)
    val strokeWidth = moonRadius * (0.07f + 0.03f * glow)
    val blurRadius = moonRadius * 0.1f
    val nativePaint = paint.configure(ringColor, strokeWidth, blurRadius)

    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawCircle(center.x, center.y, moonRadius * 1.02f, nativePaint)
    }
}

/** A stroke paint with a blur, rebuilt only when the blur radius changes (the art is resized). */
private class BlurredRingPaint {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var blurRadius = 0f

    init {
        paint.style = android.graphics.Paint.Style.STROKE
    }

    fun configure(color: Color, strokeWidth: Float, blurRadius: Float): android.graphics.Paint {
        if (blurRadius != this.blurRadius) {
            this.blurRadius = blurRadius
            paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        paint.color = color.toArgb()
        paint.strokeWidth = strokeWidth
        return paint
    }
}

/** The "diamond ring": a point of light on the rim that twinkles with the corona. */
private fun DrawScope.drawSparkle(color: Color, moonRadius: Float, breath: Float, glow: Float) {
    val position = center + SPARKLE_DIRECTION * (moonRadius * 0.98f)
    val radius = moonRadius * (0.12f + 0.04f * breath + 0.1f * glow)
    val alpha = (0.6f + 0.2f * breath + 0.2f * glow).coerceAtMost(1f)

    val brush = Brush.radialGradient(
        0f to Color.White.copy(alpha = alpha),
        0.25f to color.copy(alpha = alpha * 0.8f),
        1f to Color.Transparent,
        center = position,
        radius = radius,
    )
    drawCircle(brush = brush, radius = radius, center = position)
}
