package com.danemadsen.curie.ui.call

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.ui.preview.PreviewData
import com.danemadsen.curie.ui.theme.CurieTheme
import com.danemadsen.curie.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The labels of the call's buttons, which sit below the round icons. */
private val CONTROL_LABELS = listOf("Mute", "End call", "Speaker")

/**
 * The call can always be muted and ended: on a small phone, with large text and in landscape, the
 * buttons and their labels stay whole on the screen (the eclipse gives way instead).
 */
@RunWith(AndroidJUnit4::class)
class CallScreenLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun theButtonsFitASmallPhone() {
        showCall()

        assertControlsOnScreen()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port", fontScale = 1.3f)
    fun theButtonsFitASmallPhoneWithLargeText() {
        showCall()

        assertControlsOnScreen()
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land")
    fun theButtonsFitALandscapePhone() {
        showCall()

        assertControlsOnScreen()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun theButtonsFitASmallPhoneOnALightPage() {
        // The eclipse is drawn as its negative on a light page
        showCall(ThemeMode.LIGHT)

        assertControlsOnScreen()
    }

    private fun showCall(themeMode: ThemeMode = ThemeMode.DARK) {
        // The eclipse animates every frame for as long as it is shown: let only the layout settle
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            CurieTheme(themeMode = themeMode) {
                CallScreen(state = PreviewData.CALL, onAction = {})
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun assertControlsOnScreen() {
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

        for (label in CONTROL_LABELS) {
            // Where the control is laid out, not boundsInRoot: that is clipped to the screen
            val node = composeRule.onNodeWithText(label).fetchSemanticsNode()
            val nodeSize = node.size.toSize()
            val control = Rect(node.positionInRoot, nodeSize)
            val isWhollyOnScreen = control.left >= screen.left &&
                control.top >= screen.top &&
                control.right <= screen.right &&
                control.bottom <= screen.bottom

            assertTrue("$label at $control is not wholly on the screen $screen", isWhollyOnScreen)
        }
    }
}
