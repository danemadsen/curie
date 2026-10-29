package com.danemadsen.curie.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R

/** One choice of a [Dropdown]. */
data class DropdownItem<T>(val label: String, val value: T)

private val MENU_MIN_WIDTH = 180.dp
private val MENU_MAX_HEIGHT = 400.dp
private val MENU_SHAPE = RoundedCornerShape(12.dp)
private val MENU_GAP = 2.dp

/**
 * Maid's Dropdown: the selected label (14 sp, at most 200 dp) and a caret that opens the choices
 * below it, at least 180 dp wide on a surface-variant card with 12 dp corners.
 */
@Composable
fun <T> Dropdown(
    items: List<DropdownItem<T>>,
    selectedValue: T,
    onValueChange: (T) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val anchorWidth = with(density) { anchorWidthPx.toDp() }
    val menuWidth = maxOf(MENU_MIN_WIDTH, anchorWidth)

    val selectedItem = items.firstOrNull { item -> item.value == selectedValue } ?: items.firstOrNull()
    val caretIcon = if (expanded) R.drawable.tabler_caret_up_filled else R.drawable.tabler_caret_down_filled

    val anchorModifier = modifier.onSizeChanged { size -> anchorWidthPx = size.width }
    val rowModifier = Modifier
        .clickable(role = Role.DropdownList) { expanded = true }
        .semantics {
            this.contentDescription = contentDescription
            stateDescription = selectedItem?.label.orEmpty()
        }

    // The box is the popover's anchor: the label and the caret
    Box(modifier = anchorModifier) {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            if (selectedItem != null) {
                Text(
                    text = selectedItem.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
            Icon(
                painter = painterResource(caretIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .size(22.dp),
            )
        }

        Popover(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = menuWidth,
            position = PopoverPosition.BOTTOM,
            offset = DpOffset(0.dp, MENU_GAP),
            shape = MENU_SHAPE,
        ) {
            val listModifier = Modifier
                .heightIn(max = MENU_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())

            Column(modifier = listModifier) {
                for (item in items) {
                    DropdownMenuItem(
                        label = item.label,
                        onClick = {
                            onValueChange(item.value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownMenuItem(label: String, onClick: () -> Unit) {
    val itemModifier = Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp)

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = itemModifier,
    )
}
