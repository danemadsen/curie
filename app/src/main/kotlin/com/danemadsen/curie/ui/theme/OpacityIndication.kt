package com.danemadsen.curie.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/** React Native's Easing.inOut(Easing.quad). */
private val QUAD_IN_OUT = Easing { fraction ->
    if (fraction < 0.5f) {
        2f * fraction * fraction
    } else {
        val remaining = -2f * fraction + 2f
        1f - remaining * remaining / 2f
    }
}

/** How long TouchableOpacity takes to fade back in after the finger lifts. */
private const val RELEASE_DURATION_MILLIS = 250

/**
 * The press feedback of React Native's TouchableOpacity, which Maid uses for every touchable: the
 * content drops to [pressedAlpha] the moment it is touched and fades back in when released.
 * [CurieTheme] provides [DEFAULT] as the LocalIndication, so plain `clickable`s behave like Maid's.
 */
class OpacityIndication(private val pressedAlpha: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        OpacityIndicationNode(interactionSource, pressedAlpha)

    override fun equals(other: Any?): Boolean =
        other is OpacityIndication && other.pressedAlpha == pressedAlpha

    override fun hashCode(): Int = pressedAlpha.hashCode()

    companion object {
        /** TouchableOpacity's default activeOpacity. */
        val DEFAULT = OpacityIndication(pressedAlpha = 0.2f)
    }
}

private class OpacityIndicationNode(
    private val interactionSource: InteractionSource,
    private val pressedAlpha: Float,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> coroutineScope.launch { alpha.snapTo(pressedAlpha) }
                    is PressInteraction.Release -> fadeIn()
                    is PressInteraction.Cancel -> fadeIn()
                }
            }
        }
    }

    private fun fadeIn() {
        val fadeSpec = tween<Float>(durationMillis = RELEASE_DURATION_MILLIS, easing = QUAD_IN_OUT)
        coroutineScope.launch { alpha.animateTo(1f, fadeSpec) }
    }

    override fun ContentDrawScope.draw() {
        val contentAlpha = alpha.value
        if (contentAlpha >= 1f) {
            drawContent()
            return
        }

        val layerPaint = Paint()
        layerPaint.alpha = contentAlpha
        val bounds = Rect(Offset.Zero, size)

        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, layerPaint)
            drawContent()
            canvas.restore()
        }
    }
}
