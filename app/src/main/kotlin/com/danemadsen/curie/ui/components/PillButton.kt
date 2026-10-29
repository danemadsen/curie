package com.danemadsen.curie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val PILL_SHAPE = RoundedCornerShape(20.dp)
private val TONAL_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
private val PROMINENT_PADDING = PaddingValues(horizontal = 32.dp, vertical = 8.dp)
private const val DISABLED_ALPHA = 0.38f

/** Maid's pill buttons. */
enum class PillButtonStyle {
    /** Settings actions ("Load User Image"): primary text on the surface variant. */
    TONAL,

    /** Dialog and main actions: bold onPrimary text on the primary colour. */
    FILLED,

    /** Destructive main actions: bold onError text on the error colour. */
    DANGER,
}

/** A Maid pill button: text on a 20 dp rounded background that fades while pressed. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillButtonStyle = PillButtonStyle.TONAL,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val containerColor: Color
    val contentColor: Color
    val textStyle: TextStyle
    val contentPadding: PaddingValues
    when (style) {
        PillButtonStyle.TONAL -> {
            containerColor = colors.surfaceVariant
            contentColor = colors.primary
            textStyle = typography.bodyMedium
            contentPadding = TONAL_PADDING
        }
        PillButtonStyle.FILLED -> {
            containerColor = colors.primary
            contentColor = colors.onPrimary
            textStyle = typography.labelLarge
            contentPadding = PROMINENT_PADDING
        }
        PillButtonStyle.DANGER -> {
            containerColor = colors.error
            contentColor = colors.onError
            textStyle = typography.labelLarge
            contentPadding = PROMINENT_PADDING
        }
    }

    val buttonModifier = modifier
        .alpha(if (enabled) 1f else DISABLED_ALPHA)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .background(containerColor, PILL_SHAPE)
        .padding(contentPadding)

    Text(
        text = text,
        color = contentColor,
        style = textStyle,
        textAlign = TextAlign.Center,
        modifier = buttonModifier,
    )
}
