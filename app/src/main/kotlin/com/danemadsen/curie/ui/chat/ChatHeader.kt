package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.components.CurieIconButton

/**
 * Maid's chat header without its model button: the drawer button on the left and, where Maid has its
 * menu, the call button. The middle stays empty. Edge to edge, it draws behind the status bar and pads
 * itself below it.
 */
@Composable
internal fun ChatHeader(
    assistantName: String,
    isCallEnabled: Boolean,
    onOpenDrawer: () -> Unit,
    onStartCall: () -> Unit,
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
            icon = R.drawable.tabler_menu_2,
            contentDescription = stringResource(R.string.chat_open_drawer),
            onClick = onOpenDrawer,
        )

        Spacer(modifier = Modifier.weight(1f))

        CurieIconButton(
            icon = R.drawable.tabler_phone,
            contentDescription = stringResource(R.string.chat_start_call, assistantName),
            onClick = onStartCall,
            enabled = isCallEnabled,
            modifier = Modifier.padding(4.dp),
        )
    }
}
