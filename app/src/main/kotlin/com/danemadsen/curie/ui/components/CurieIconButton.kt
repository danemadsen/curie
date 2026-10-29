package com.danemadsen.curie.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Maid's icon size for list and drawer actions. */
val SMALL_ICON_SIZE = 24.dp

/** Maid's icon size for headers, the composer and message controls. */
val LARGE_ICON_SIZE = 28.dp

/** The margin Maid's MaterialIconButton puts around every icon. */
private val ICON_BUTTON_MARGIN = 4.dp

/**
 * Maid's MaterialIconButton: a bare [icon] of [size] with a 4 dp margin and no background, drawn in
 * [tint], or in [disabledTint] (the outline colour) while disabled. The layout stays this small;
 * Compose still accepts touches within the 48 dp minimum touch target around it.
 */
@Composable
fun CurieIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = LARGE_ICON_SIZE,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    disabledTint: Color = MaterialTheme.colorScheme.outline,
) {
    val buttonModifier = modifier
        .padding(ICON_BUTTON_MARGIN)
        .size(size)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)

    Box(modifier = buttonModifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (enabled) tint else disabledTint,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
