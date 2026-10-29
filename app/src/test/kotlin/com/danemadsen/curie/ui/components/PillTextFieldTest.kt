package com.danemadsen.curie.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.ui.theme.CurieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings' name field hands its text over once per edit, whether the edit ends with Done (and
 * the focus loss Done causes), by moving the focus away or by leaving the screen, and takes over
 * new values only while it is not being edited.
 */
@RunWith(AndroidJUnit4::class)
class PillTextFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val commits = mutableListOf<String>()
    private var value by mutableStateOf("Ada")
    private var isShown by mutableStateOf(true)
    private lateinit var focusManager: FocusManager

    @Test
    fun doneAndTheFocusLossItCausesCommitOnce() {
        showField()

        editField("Grace")
        field().performImeAction()

        assertCommits("Grace")
    }

    @Test
    fun movingTheFocusAwayCommits() {
        showField()

        editField("Grace")
        composeRule.runOnIdle { focusManager.clearFocus() }

        assertCommits("Grace")
    }

    @Test
    fun leavingTheScreenWhileEditingCommits() {
        showField()

        editField("Grace")
        composeRule.runOnIdle { isShown = false }

        assertCommits("Grace")
    }

    @Test
    fun anUnchangedNameIsNotCommitted() {
        showField()

        field().performClick()
        field().performImeAction()

        assertCommits()
    }

    @Test
    fun aNewValueIsShownWhileNotEditing() {
        showField()

        composeRule.runOnIdle { value = "Marie" }

        field().assertTextEquals("Marie")
        assertCommits()
    }

    @Test
    fun aNewValueDoesNotReplaceTheTextBeingTyped() {
        showField()

        editField("Grace")
        composeRule.runOnIdle { value = "Marie" }

        field().assertTextEquals("Grace")
        field().performImeAction()
        assertCommits("Grace")
    }

    private fun showField() {
        composeRule.setContent {
            focusManager = LocalFocusManager.current

            CurieTheme {
                if (isShown) {
                    PillTextField(
                        value = value,
                        onValueCommit = { text -> commits.add(text) },
                        placeholder = "User Name",
                    )
                }
            }
        }
    }

    private fun field(): SemanticsNodeInteraction = composeRule.onNode(hasSetTextAction())

    private fun editField(text: String) {
        field().performClick()
        field().performTextReplacement(text)
    }

    private fun assertCommits(vararg expected: String) {
        composeRule.runOnIdle { assertEquals(expected.toList(), commits) }
    }
}
