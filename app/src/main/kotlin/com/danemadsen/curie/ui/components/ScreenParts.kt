package com.danemadsen.curie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R

private val DIVIDER_SHAPE = RoundedCornerShape(1.dp)

/** Maid's divider: a 2 dp rounded bar in the outline colour, 18 dp in from the sides. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    val dividerModifier = modifier
        .padding(horizontal = 18.dp, vertical = 12.dp)
        .fillMaxWidth()
        .height(2.dp)
        .background(MaterialTheme.colorScheme.outline, DIVIDER_SHAPE)

    Box(modifier = dividerModifier)
}

/**
 * Maid's DefaultHeader: a back arrow and a bold 20 sp [title] on the surface. Edge to edge, it
 * draws behind the status bar and pads itself below it.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBarInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    val headerModifier = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .windowInsetsPadding(statusBarInsets)
        .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)

    Row(modifier = headerModifier, verticalAlignment = Alignment.CenterVertically) {
        CurieIconButton(
            icon = R.drawable.tabler_arrow_left,
            contentDescription = stringResource(R.string.common_back),
            onClick = onBack,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}
