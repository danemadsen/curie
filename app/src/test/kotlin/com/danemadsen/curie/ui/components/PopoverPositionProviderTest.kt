package com.danemadsen.curie.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

private val SCREEN = IntSize(1000, 2000)

/** A status bar of 100 px and a navigation bar of 50 px. */
private val MARGINS = ScreenMargins(left = 0, top = 100, right = 0, bottom = 50)

private const val EDGE_PADDING_PX = 20
private const val GAP_PX = 10

private val POPUP = IntSize(300, 200)

/** 200 x 100 px in the middle of the screen. */
private val ANCHOR = IntRect(left = 400, top = 800, right = 600, bottom = 900)

/**
 * Maid's popover placement (components/views/popover-view.tsx): the popover opens [GAP_PX] beside
 * the anchor, aligned to its start, centre or end, moved by the offset, and is then clamped into
 * the safe area minus [EDGE_PADDING_PX], the minimum winning when it does not fit. Start and end
 * follow the layout direction.
 */
class PopoverPositionProviderTest {
    @Test
    fun opensBelowTheAnchorCentredByDefault() {
        val position = place(PopoverPosition.BOTTOM, PopoverAlign.CENTER)

        assertEquals(IntOffset(350, 910), position)
    }

    @Test
    fun opensAboveTheAnchorAtItsStart() {
        val position = place(PopoverPosition.TOP, PopoverAlign.START)

        assertEquals(IntOffset(400, 590), position)
    }

    @Test
    fun alignsToTheEndOfTheAnchor() {
        val position = place(PopoverPosition.BOTTOM, PopoverAlign.END)

        assertEquals(IntOffset(300, 910), position)
    }

    @Test
    fun startAndEndAreMirroredRightToLeft() {
        val atStart = place(PopoverPosition.TOP, PopoverAlign.START, layoutDirection = LayoutDirection.Rtl)
        val atEnd = place(PopoverPosition.BOTTOM, PopoverAlign.END, layoutDirection = LayoutDirection.Rtl)

        assertEquals(IntOffset(300, 590), atStart)
        assertEquals(IntOffset(400, 910), atEnd)
    }

    @Test
    fun opensBesideTheAnchorAlignedVertically() {
        val rightAtStart = place(PopoverPosition.RIGHT, PopoverAlign.START)
        val rightCentred = place(PopoverPosition.RIGHT, PopoverAlign.CENTER)
        val leftAtEnd = place(PopoverPosition.LEFT, PopoverAlign.END)

        assertEquals(IntOffset(610, 800), rightAtStart)
        assertEquals(IntOffset(610, 750), rightCentred)
        assertEquals(IntOffset(90, 700), leftAtEnd)
    }

    @Test
    fun theOffsetMovesThePopoverAfterAligning() {
        val position = place(PopoverPosition.BOTTOM, PopoverAlign.START, offset = IntOffset(-30, 40))

        assertEquals(IntOffset(370, 950), position)
    }

    @Test
    fun staysInsideTheSafeArea() {
        val nearRightEdge = IntRect(left = 900, top = 800, right = 1000, bottom = 900)
        val belowStatusBar = IntRect(left = 400, top = 150, right = 600, bottom = 250)
        val aboveNavigationBar = IntRect(left = 400, top = 1800, right = 600, bottom = 1900)

        val right = place(PopoverPosition.BOTTOM, PopoverAlign.START, anchor = nearRightEdge)
        val top = place(PopoverPosition.TOP, PopoverAlign.START, anchor = belowStatusBar)
        val bottom = place(PopoverPosition.BOTTOM, PopoverAlign.START, anchor = aboveNavigationBar)

        // 1000 - 300 - 20, 100 + 20 and 2000 - 50 - 200 - 20
        assertEquals(IntOffset(680, 910), right)
        assertEquals(IntOffset(400, 120), top)
        assertEquals(IntOffset(400, 1730), bottom)
    }

    @Test
    fun theSafeAreaStartWinsWhenThePopoverDoesNotFit() {
        val position = place(PopoverPosition.BOTTOM, PopoverAlign.CENTER, popup = IntSize(1200, 200))

        assertEquals(IntOffset(20, 910), position)
    }

    @Test
    fun usesTheWindowSizeUntilTheScreenIsLaidOut() {
        val nearRightEdge = IntRect(left = 700, top = 800, right = 800, bottom = 900)
        val provider = PopoverPositionProvider(
            screenSize = { IntSize.Zero },
            position = PopoverPosition.BOTTOM,
            align = PopoverAlign.START,
            offset = IntOffset.Zero,
            screenMargins = MARGINS,
            edgePadding = EDGE_PADDING_PX,
            gap = GAP_PX,
        )

        val windowSize = IntSize(800, 1600)
        val position = provider.calculatePosition(nearRightEdge, windowSize, LayoutDirection.Ltr, POPUP)

        // 800 - 300 - 20
        assertEquals(IntOffset(480, 910), position)
    }

    private fun place(
        position: PopoverPosition,
        align: PopoverAlign,
        anchor: IntRect = ANCHOR,
        popup: IntSize = POPUP,
        offset: IntOffset = IntOffset.Zero,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): IntOffset {
        val provider = PopoverPositionProvider(
            screenSize = { SCREEN },
            position = position,
            align = align,
            offset = offset,
            screenMargins = MARGINS,
            edgePadding = EDGE_PADDING_PX,
            gap = GAP_PX,
        )

        return provider.calculatePosition(anchor, SCREEN, layoutDirection, popup)
    }
}
