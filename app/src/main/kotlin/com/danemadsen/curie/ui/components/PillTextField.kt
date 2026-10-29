package com.danemadsen.curie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val FIELD_SHAPE = RoundedCornerShape(30.dp)

/** Maid keeps a focused input this far above the keyboard (KeyboardAwareScrollView's bottomOffset). */
private val KEYBOARD_GAP = 16.dp

/** The keyboard has settled once its height has not changed for this long. */
private const val KEYBOARD_SETTLE_MILLIS = 100L

/**
 * Maid's text input: 16 sp text on a fully rounded surface-variant pill, with the placeholder in
 * the text colour as Maid draws it. While it is focused, the scrolling container keeps the whole
 * pill 16 dp above the keyboard, as Maid's settings page does.
 *
 * The field keeps its own text while it is edited and hands it to [onValueCommit] when editing
 * ends (the keyboard's Done, losing focus, or leaving the screen), so an asynchronously updated
 * [value] never fights the user's typing. New [value]s are taken over while the field is not focused.
 */
@Composable
fun PillTextField(
    value: String,
    onValueCommit: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val fieldState = rememberTextFieldState(initialText = value)
    var isFocused by remember { mutableStateOf(false) }
    var lastCommittedText by remember { mutableStateOf(value) }
    val commitCallback by rememberUpdatedState(onValueCommit)
    val focusManager = LocalFocusManager.current

    // Done, then the focus loss it causes, must not hand over the same text twice
    fun commit() {
        val text = fieldState.text.toString()
        if (text != lastCommittedText) {
            lastCommittedText = text
            commitCallback(text)
        }
    }

    LaunchedEffect(value) {
        lastCommittedText = value
        val isShowingValue = fieldState.text.toString() == value
        if (!isFocused && !isShowingValue) {
            fieldState.setTextAndPlaceCursorAtEnd(value)
        }
    }

    DisposableEffect(fieldState) {
        onDispose { commit() }
    }

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val keyboardInsets = WindowInsets.ime
    val density = LocalDensity.current

    // Compose scrolls only the cursor's line above the keyboard. Like Maid's KeyboardAwareScrollView,
    // once the keyboard has settled the whole pill and a gap below it are brought above it too.
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            return@LaunchedEffect
        }

        val keyboardHeights = snapshotFlow { keyboardInsets.getBottom(density) }
        keyboardHeights.collectLatest { keyboardHeight ->
            if (keyboardHeight > 0) {
                delay(KEYBOARD_SETTLE_MILLIS)

                val gap = with(density) { KEYBOARD_GAP.toPx() }
                val pillAndGap = Rect(0f, 0f, fieldSize.width.toFloat(), fieldSize.height + gap)
                bringIntoViewRequester.bringIntoView(pillAndGap)
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    val keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Done,
    )
    val onKeyboardAction = KeyboardActionHandler {
        commit()
        focusManager.clearFocus()
    }

    // The pill belongs to the decoration rather than the modifier, so the whole pill is the text
    // field: a tap anywhere on it focuses the field
    val decorator = TextFieldDecorator { innerTextField ->
        val pillModifier = Modifier
            .background(colors.surfaceVariant, FIELD_SHAPE)
            .padding(horizontal = 16.dp, vertical = 12.dp)

        Box(modifier = pillModifier) {
            if (fieldState.text.isEmpty()) {
                Text(text = placeholder, style = textStyle)
            }
            innerTextField()
        }
    }

    val fieldModifier = modifier
        .bringIntoViewRequester(bringIntoViewRequester)
        .onSizeChanged { size -> fieldSize = size }
        .onFocusChanged { focusState ->
            if (isFocused && !focusState.isFocused) {
                commit()
            }
            isFocused = focusState.isFocused
        }

    BasicTextField(
        state = fieldState,
        modifier = fieldModifier,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(colors.primary),
        decorator = decorator,
    )
}
