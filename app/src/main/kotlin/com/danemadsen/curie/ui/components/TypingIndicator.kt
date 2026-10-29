package com.danemadsen.curie.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R

private const val DOT_COUNT = 3
private const val CYCLE_MILLIS = 1200
private const val DOT_DELAY_MILLIS = 160
private const val PULSE_MILLIS = 400
private const val RESTING_ALPHA = 0.3f
private const val RESTING_SCALE = 0.75f

private val DOT_SIZE = 8.dp
private val DOT_SPACING = 6.dp

/** Three dots pulsing one after another while the assistant prepares its answer. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.message_generating)
    val rowModifier = modifier
        .padding(vertical = 8.dp)
        .semantics { contentDescription = description }

    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(DOT_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until DOT_COUNT) {
            PulsingDot(delayMillis = index * DOT_DELAY_MILLIS)
        }
    }
}

@Composable
private fun PulsingDot(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "typing")
    val pulse = keyframes {
        durationMillis = CYCLE_MILLIS
        0f at delayMillis using LinearEasing
        1f at delayMillis + PULSE_MILLIS / 2 using LinearEasing
        0f at delayMillis + PULSE_MILLIS
    }
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(pulse, RepeatMode.Restart),
        label = "dot",
    )

    val color = MaterialTheme.colorScheme.secondary
    val dotModifier = Modifier
        .size(DOT_SIZE)
        .graphicsLayer {
            alpha = RESTING_ALPHA + (1f - RESTING_ALPHA) * progress
            val scale = RESTING_SCALE + (1f - RESTING_SCALE) * progress
            scaleX = scale
            scaleY = scale
        }
        .background(color, CircleShape)

    Box(modifier = dotModifier)
}
