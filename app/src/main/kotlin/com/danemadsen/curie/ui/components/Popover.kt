package com.danemadsen.curie.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.min

/** Which side of the anchor the popover opens on. */
enum class PopoverPosition {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

/** How the popover lines up with the anchor along the anchor's side: start, centre or end. */
enum class PopoverAlign {
    START,
    CENTER,
    END,
}

/** Maid's popover: 8 dp corners on the surface variant. */
private val POPOVER_SHAPE = RoundedCornerShape(8.dp)

private val EDGE_PADDING = 8.dp
private val ANCHOR_GAP = 6.dp
private const val FADE_MILLIS = 200

/**
 * Maid's Popover (components/views/popover-view.tsx): a [width]-wide surface-variant card that
 * opens next to its anchor (the layout this is placed in) on the [position] side, aligned by
 * [align], moved by [offset] and kept 8 dp inside the screen's safe area. It fades in and out;
 * a tap outside or the back button calls [onDismissRequest].
 */
@Composable
fun Popover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    position: PopoverPosition = PopoverPosition.BOTTOM,
    align: PopoverAlign = PopoverAlign.CENTER,
    offset: DpOffset = DpOffset.Zero,
    shape: Shape = POPOVER_SHAPE,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded

    if (!visibility.currentState && !visibility.targetState) {
        return
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val rootView = LocalView.current.rootView
    val safeArea = WindowInsets.safeDrawing

    val screenMargins = ScreenMargins(
        left = safeArea.getLeft(density, layoutDirection),
        top = safeArea.getTop(density),
        right = safeArea.getRight(density, layoutDirection),
        bottom = safeArea.getBottom(density),
    )

    val positionProvider = with(density) {
        val offsetPx = IntOffset(offset.x.roundToPx(), offset.y.roundToPx())

        PopoverPositionProvider(
            screenSize = { IntSize(rootView.width, rootView.height) },
            position = position,
            align = align,
            offset = offsetPx,
            screenMargins = screenMargins,
            edgePadding = EDGE_PADDING.roundToPx(),
            gap = ANCHOR_GAP.roundToPx(),
        )
    }

    val properties = PopupProperties(focusable = true, clippingEnabled = false)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(FADE_MILLIS)),
            exit = fadeOut(tween(FADE_MILLIS)),
        ) {
            val cardModifier = Modifier
                .width(width)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .padding(vertical = 6.dp)

            Column(modifier = cardModifier, content = content)
        }
    }
}

/** A popover entry: 16 sp text padded like Maid's popover links. */
@Composable
fun PopoverItem(text: String, onClick: () -> Unit) {
    val itemModifier = Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp)

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = itemModifier,
    )
}

/** The system bars and cutouts around the screen's safe area, in pixels. */
internal data class ScreenMargins(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Maid's popover placement. The popup's coordinates are those of the edge-to-edge window, whose
 * root view spans the whole screen: [screenSize] is that view's size (zero until it is laid out,
 * when the popup's window size stands in). The safe-area margins keep the card off the system bars.
 */
internal class PopoverPositionProvider(
    private val screenSize: () -> IntSize,
    private val position: PopoverPosition,
    private val align: PopoverAlign,
    private val offset: IntOffset,
    private val screenMargins: ScreenMargins,
    private val edgePadding: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val popupWidth = popupContentSize.width
        val popupHeight = popupContentSize.height
        val isRtl = layoutDirection == LayoutDirection.Rtl

        val leftAtStart = if (isRtl) anchorBounds.right - popupWidth else anchorBounds.left
        val leftAtEnd = if (isRtl) anchorBounds.left else anchorBounds.right - popupWidth
        val alignedLeft = when (align) {
            PopoverAlign.START -> leftAtStart
            PopoverAlign.CENTER -> anchorBounds.left + (anchorBounds.width - popupWidth) / 2
            PopoverAlign.END -> leftAtEnd
        }
        val alignedTop = when (align) {
            PopoverAlign.START -> anchorBounds.top
            PopoverAlign.CENTER -> anchorBounds.top + (anchorBounds.height - popupHeight) / 2
            PopoverAlign.END -> anchorBounds.bottom - popupHeight
        }

        val left = when (position) {
            PopoverPosition.TOP, PopoverPosition.BOTTOM -> alignedLeft
            PopoverPosition.RIGHT -> anchorBounds.right + gap
            PopoverPosition.LEFT -> anchorBounds.left - popupWidth - gap
        }
        val top = when (position) {
            PopoverPosition.BOTTOM -> anchorBounds.bottom + gap
            PopoverPosition.TOP -> anchorBounds.top - popupHeight - gap
            PopoverPosition.LEFT, PopoverPosition.RIGHT -> alignedTop
        }

        val rootSize = screenSize()
        val screenWidth = rootSize.width.takeIf { width -> width > 0 } ?: windowSize.width
        val screenHeight = rootSize.height.takeIf { height -> height > 0 } ?: windowSize.height
        val clampedLeft = clamp(
            value = left + offset.x,
            minimum = screenMargins.left + edgePadding,
            maximum = screenWidth - screenMargins.right - popupWidth - edgePadding,
        )
        val clampedTop = clamp(
            value = top + offset.y,
            minimum = screenMargins.top + edgePadding,
            maximum = screenHeight - screenMargins.bottom - popupHeight - edgePadding,
        )

        return IntOffset(clampedLeft, clampedTop)
    }

    /** Maid's clamp: the minimum wins when the popover does not fit. */
    private fun clamp(value: Int, minimum: Int, maximum: Int): Int = max(minimum, min(value, maximum))
}
