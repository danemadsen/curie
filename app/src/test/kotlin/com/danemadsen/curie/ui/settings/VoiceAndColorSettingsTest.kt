package com.danemadsen.curie.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.data.SpeakReplies
import com.danemadsen.curie.ui.preview.PreviewData
import com.danemadsen.curie.ui.theme.CurieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice and colour settings: "Read replies aloud" shows the stored choice and hands a new one
 * over, and "Match system colors" hides the accent slider it replaces.
 */
@RunWith(AndroidJUnit4::class)
class VoiceAndColorSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<SettingsAction>()

    @Test
    fun choosingAlwaysReadsEveryReplyAloud() {
        composeRule.setContent {
            CurieTheme {
                SettingsScreen(state = PreviewData.SETTINGS, onAction = { action -> actions.add(action) })
            }
        }

        val dropdown = composeRule.onNodeWithContentDescription("Read replies aloud")
        dropdown.performScrollTo()
        composeRule.onNodeWithText("To voice messages").assertIsDisplayed()
        dropdown.performClick()
        composeRule.onNodeWithText("Always").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(SettingsAction.SetReadAloud(ReadAloud.ALWAYS)), actions)
        }
    }

    @Test
    fun theAccentSliderShowsOnlyWithoutSystemColors() {
        var state by mutableStateOf(PreviewData.SETTINGS.copy(useSystemColors = true))
        composeRule.setContent {
            CurieTheme {
                SettingsScreen(state = state, onAction = { action -> actions.add(action) })
            }
        }

        // System colours replace the accent colour, so its slider would do nothing
        composeRule.onNodeWithContentDescription("Accent Color").assertDoesNotExist()
        val systemColors = composeRule.onNodeWithText("Match system colors")
        systemColors.performScrollTo()
        systemColors.performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsAction.SetUseSystemColors(false)), actions)
            state = state.copy(useSystemColors = false)
        }

        composeRule.onNodeWithContentDescription("Accent Color").assertExists()
    }

    @Test
    fun everyChoiceMapsToItsStoredPreference() {
        for (readAloud in ReadAloud.entries) {
            val stored = SettingsViewModel.speakRepliesOf(readAloud)
            assertEquals(readAloud, SettingsViewModel.readAloudOf(stored))
        }
        assertEquals(SpeakReplies.VOICE_MESSAGES, SettingsViewModel.speakRepliesOf(ReadAloud.VOICE_MESSAGES))
    }
}
