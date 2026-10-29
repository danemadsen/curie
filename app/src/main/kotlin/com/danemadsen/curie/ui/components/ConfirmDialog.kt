package com.danemadsen.curie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.danemadsen.curie.R

private val CARD_SHAPE = RoundedCornerShape(32.dp)
private const val CARD_WIDTH_FRACTION = 0.8f

/** The black at half opacity that Maid's modals lay over the whole screen, system bars included. */
internal val MODAL_SCRIM_COLOR = Color.Black.copy(alpha = 0.5f)

/**
 * Maid's ConfirmView: a centred surface-variant card (80 % wide, 32 dp corners) with a bold 18 sp
 * [message] above two primary pill buttons, over a half-black scrim. Like Maid, tapping the scrim
 * does nothing; the back button answers [onDismiss].
 *
 * With a [title], the title takes the bold style and the [message] reads as body text, scrolling
 * when it is longer than the screen: for disclosures that explain what accepting means.
 */
@Composable
fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.common_yes),
    dismissLabel: String = stringResource(R.string.common_no),
    title: String? = null,
) {
    val properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

    Dialog(onDismissRequest = onDismiss, properties = properties) {
        RemoveWindowDim()

        val scrimModifier = Modifier
            .fillMaxSize()
            .background(MODAL_SCRIM_COLOR)

        Box(modifier = scrimModifier, contentAlignment = Alignment.Center) {
            val cardModifier = Modifier
                .fillMaxWidth(CARD_WIDTH_FRACTION)
                .background(MaterialTheme.colorScheme.surfaceVariant, CARD_SHAPE)
                .padding(32.dp)

            Column(
                modifier = cardModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                if (title == null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    TitledMessage(title = title, message = message, modifier = Modifier.weight(1f, fill = false))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(text = confirmLabel, onClick = onConfirm, style = PillButtonStyle.FILLED)
                    PillButton(text = dismissLabel, onClick = onDismiss, style = PillButtonStyle.FILLED)
                }
            }
        }
    }
}

@Composable
private fun TitledMessage(title: String, message: String, modifier: Modifier = Modifier) {
    val scrollingModifier = modifier.verticalScroll(rememberScrollState())

    Column(modifier = scrollingModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dialog windows dim what is behind them; the scrim above already does, exactly as Maid's does. */
@Composable
private fun RemoveWindowDim() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window

    SideEffect {
        dialogWindow?.setDimAmount(0f)
    }
}
