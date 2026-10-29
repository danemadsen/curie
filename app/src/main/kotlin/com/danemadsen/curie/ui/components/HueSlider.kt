package com.danemadsen.curie.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// reanimated-color-picker's defaults, which Maid's accent picker uses

private val TRACK_THICKNESS = 25.dp
private val TRACK_CORNER_RADIUS = 5.dp
private val THUMB_SIZE = 35.dp
private const val THUMB_INNER_FRACTION = 0.75f
private const val THUMB_PRESSED_SCALE = 1.2f
private const val THUMB_SCALE_MILLIS = 100

private const val FULL_TURN = 360f

/** The inner disc's slight drop shadow (the picker's elevation 2). */
private val THUMB_SHADOW_COLOR = Color.Black.copy(alpha = 0.2f)

/** The rainbow of the track: every hue at full saturation and brightness, red to red. */
private val HUE_SPECTRUM = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)

/** A colour as hue (0 to 360 degrees), saturation and value (0 to 1). */
internal data class Hsv(val hue: Float, val saturation: Float, val value: Float)

internal fun Color.toHsv(): Hsv {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val delta = maximum - minimum

    val hue = when {
        delta == 0f -> 0f
        maximum == red -> 60f * (((green - blue) / delta).mod(6f))
        maximum == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    val saturation = if (maximum == 0f) 0f else delta / maximum

    return Hsv(hue = hue, saturation = saturation, value = maximum)
}

/** [color] with its hue replaced, keeping its saturation and brightness, as the picker's hue slider does. */
internal fun withHue(color: Color, hue: Float): Color {
    val hsv = color.toHsv()
    val wrappedHue = hue.coerceIn(0f, FULL_TURN) % FULL_TURN
    return Color.hsv(wrappedHue, hsv.saturation, hsv.value)
}

/**
 * Maid's accent colour picker (reanimated-color-picker's HueSlider): a 25 dp rainbow track with
 * 5 dp corners and a 35 dp ring thumb showing [color], which grows while held. Dragging or
 * tapping changes only the hue; [onColorChange] gets the new colour when the finger lifts, as
 * Maid commits it.
 */
@Composable
fun HueSlider(
    color: Color,
    onColorChange: (Color) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    // Each detector reports its own part of a gesture: the tap detector a press, until the finger
    // lifts or a drag takes the gesture over, and the drag detector the hue being dragged to
    var isPressed by remember { mutableStateOf(false) }
    var draggedHue by remember { mutableStateOf<Float?>(null) }
    val isHeld = isPressed || draggedHue != null

    val currentColor by rememberUpdatedState(color)
    val currentOnColorChange by rememberUpdatedState(onColorChange)

    val shownHue = draggedHue ?: color.toHsv().hue
    val shownColor = withHue(color, shownHue)
    val thumbScale by animateFloatAsState(
        targetValue = if (isHeld) THUMB_PRESSED_SCALE else 1f,
        animationSpec = tween(THUMB_SCALE_MILLIS),
        label = "thumbScale",
    )

    fun commit(hue: Float) {
        val newColor = withHue(currentColor, hue)
        currentOnColorChange(newColor)
    }

    val shownDegrees = shownHue.roundToInt()
    val hueDescription = pluralStringResource(R.plurals.settings_hue_degrees, shownDegrees, shownDegrees)
    val sliderModifier = modifier
        .fillMaxWidth()
        .height(THUMB_SIZE)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = { position -> commit(hueAt(position.x, size.width.toFloat())) },
            )
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { position -> draggedHue = hueAt(position.x, size.width.toFloat()) },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    draggedHue = hueAt(change.position.x, size.width.toFloat())
                },
                onDragEnd = {
                    draggedHue?.let { hue -> commit(hue) }
                    draggedHue = null
                },
                onDragCancel = { draggedHue = null },
            )
        }
        .semantics {
            this.contentDescription = contentDescription
            stateDescription = hueDescription
            progressBarRangeInfo = ProgressBarRangeInfo(shownHue, 0f..FULL_TURN)
            setProgress { hue ->
                commit(hue)
                true
            }
        }

    Canvas(modifier = sliderModifier) {
        drawTrack()
        drawThumb(shownHue, shownColor, thumbScale)
    }
}

private fun hueAt(x: Float, width: Float): Float {
    if (width <= 0f) {
        return 0f
    }
    return (x / width).coerceIn(0f, 1f) * FULL_TURN
}

private fun DrawScope.drawTrack() {
    val trackHeight = TRACK_THICKNESS.toPx()
    val top = (size.height - trackHeight) / 2f
    val cornerRadius = TRACK_CORNER_RADIUS.toPx()

    drawRoundRect(
        brush = Brush.horizontalGradient(HUE_SPECTRUM),
        topLeft = Offset(0f, top),
        size = Size(size.width, trackHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )
}

/**
 * The picker's ring thumb: a circle in the contrast colour of the pure hue (white on dark hues,
 * black on light ones) at half opacity with a 1 dp rim, around a disc of the chosen colour.
 */
private fun DrawScope.drawThumb(hue: Float, color: Color, scale: Float) {
    val center = Offset(size.width * hue / FULL_TURN, size.height / 2f)
    val outerRadius = THUMB_SIZE.toPx() / 2f * scale
    val innerRadius = outerRadius * THUMB_INNER_FRACTION

    val pureHue = Color.hsv(hue % FULL_TURN, 1f, 1f)
    val contrast = if (pureHue.luminance() < 0.5f) Color.White else Color.Black

    val rim = Stroke(width = 1.dp.toPx())
    val shadowCenter = center + Offset(0f, 1.dp.toPx())
    val shadowRadius = innerRadius + 1.dp.toPx()

    drawCircle(color = contrast.copy(alpha = 0.5f), radius = outerRadius, center = center)
    drawCircle(color = contrast, radius = outerRadius, center = center, style = rim)
    drawCircle(color = THUMB_SHADOW_COLOR, radius = shadowRadius, center = shadowCenter)
    drawCircle(color = color, radius = innerRadius, center = center)
}
