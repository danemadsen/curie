package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.format.formatClipDuration
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val WAVEFORM_BAR_COUNT = 28

/** Quiet stretches still show a short bar, so the waveform reads as a line of bars. */
private const val MIN_BAR_HEIGHT = 0.15f

private val CHIP_SHAPE = RoundedCornerShape(24.dp)

/**
 * The bar heights (0 to 1) drawn for a clip: [levels] resampled to [barCount] bars, each the
 * loudest level of its stretch, scaled so that the loudest bar is full height. Without levels a
 * fixed pseudo-random shape derived from [seed] stands in, so a clip always looks the same.
 */
internal fun waveformBars(levels: List<Float>, barCount: Int, seed: String): List<Float> {
    if (barCount <= 0) {
        return emptyList()
    }

    if (levels.isEmpty()) {
        val random = Random(seed.hashCode())
        return List(barCount) { MIN_BAR_HEIGHT + (1f - MIN_BAR_HEIGHT) * random.nextFloat() }
    }

    val loudestPerBar = List(barCount) { barIndex ->
        val start = barIndex * levels.size / barCount
        val end = max(start + 1, (barIndex + 1) * levels.size / barCount)
        var loudest = 0f
        for (levelIndex in start until min(end, levels.size)) {
            loudest = max(loudest, levels[levelIndex].coerceIn(0f, 1f))
        }
        loudest
    }

    val peak = loudestPerBar.max()
    if (peak <= 0f) {
        return List(barCount) { MIN_BAR_HEIGHT }
    }

    return loudestPerBar.map { loudest -> max(MIN_BAR_HEIGHT, loudest / peak) }
}

/**
 * A playable clip: a play/pause button, [label] ("Voice message", "Curie's reply"), the waveform
 * filling with the primary colour as it plays, and the clip's length (or position while active).
 * [playback] is the app's playback state when it concerns this clip, else null.
 */
@Composable
internal fun AudioClipChip(
    clip: AudioClipUi,
    label: String,
    playback: AudioPlayback?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val activePlayback = playback?.takeIf { it.clipId == clip.id }
    val isPlaying = activePlayback?.isPlaying == true
    val positionMillis = activePlayback?.positionMillis ?: 0L
    val progress = if (clip.durationMillis > 0L) {
        (positionMillis.toFloat() / clip.durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownMillis = if (activePlayback != null) positionMillis else clip.durationMillis
    val timeText = formatClipDuration(shownMillis)

    val bars = remember(clip.id, clip.waveform) {
        waveformBars(clip.waveform, WAVEFORM_BAR_COUNT, clip.id)
    }

    val toggleLabel = stringResource(if (isPlaying) R.string.audio_pause else R.string.audio_play)
    val positionDescription = stringResource(
        R.string.audio_position,
        formatClipDuration(positionMillis),
        formatClipDuration(clip.durationMillis),
    )
    val chipModifier = modifier
        .clip(CHIP_SHAPE)
        .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
        .background(colors.surfaceVariant)
        .semantics { stateDescription = positionDescription }
        .padding(start = 6.dp, top = 6.dp, end = 14.dp, bottom = 6.dp)

    Row(
        modifier = chipModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val buttonModifier = Modifier
            .size(36.dp)
            .background(colors.primary, CircleShape)
        val toggleIcon = if (isPlaying) {
            R.drawable.tabler_player_pause_filled
        } else {
            R.drawable.tabler_player_play_filled
        }

        Box(modifier = buttonModifier, contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(toggleIcon),
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Waveform(
                    bars = bars,
                    progress = progress,
                    playedColor = colors.primary,
                    unplayedColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .width(112.dp)
                        .height(20.dp),
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Rounded vertical bars; those left of [progress] (0 to 1) are drawn in [playedColor]. */
@Composable
private fun Waveform(
    bars: List<Float>,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (bars.isEmpty()) {
            return@Canvas
        }

        val slotWidth = size.width / bars.size
        val barWidth = slotWidth * 0.55f
        val playedWidth = size.width * progress

        bars.forEachIndexed { index, height ->
            val centerX = slotWidth * (index + 0.5f)
            val barHeight = max(barWidth, size.height * height)
            val top = (size.height - barHeight) / 2f + barWidth / 2f
            val bottom = (size.height + barHeight) / 2f - barWidth / 2f
            val color = if (centerX <= playedWidth) playedColor else unplayedColor

            drawLine(
                color = color,
                start = Offset(centerX, top),
                end = Offset(centerX, bottom),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
