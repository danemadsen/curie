package com.danemadsen.curie.ui.call

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.format.formatCallDuration
import com.danemadsen.curie.ui.theme.DarkCurieTheme
import kotlinx.coroutines.flow.collectLatest

private const val CAPTION_LINES = 3

/** The eclipse and the heading sit a little above the middle: 60 % of their spare room is above. */
private val ART_ALIGNMENT = BiasAlignment.Vertical(0.2f)

/**
 * A phone call with the assistant, always in the dark variant of the app's colours (the phone's
 * Material You palette, when it has one and the user allows it): the solar eclipse whose
 * corona moves with the phase and flares with [audioLevel], the assistant's name, the call time
 * and what is happening, live captions of the user's last words and the answer being spoken, and
 * mute, end-call and speaker buttons. The buttons always fit: the eclipse shrinks on short
 * screens, and a screen wider than tall (landscape) shows the eclipse beside the captions and
 * buttons.
 *
 * [audioLevel] (0 to 1, the input level while listening and the output level while speaking) is
 * read every frame while drawing, so back it with a State or a cheap getter: it does not
 * recompose the screen.
 */
@Composable
fun CallScreen(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    modifier: Modifier = Modifier,
    audioLevel: () -> Float = { 0f },
) {
    DarkCurieTheme {
        val screenModifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)

        BoxWithConstraints(modifier = screenModifier) {
            if (maxWidth > maxHeight) {
                WideCallLayout(state = state, onAction = onAction, audioLevel = audioLevel)
            } else {
                TallCallLayout(state = state, onAction = onAction, audioLevel = audioLevel)
            }
        }
    }
}

/** One column: the eclipse and the heading, then the captions, then the buttons at the bottom. */
@Composable
private fun TallCallLayout(state: CallUiState, onAction: (CallAction) -> Unit, audioLevel: () -> Float) {
    // Laid out before the weighted part, the buttons always get their full size; within that
    // part the captions come first and the eclipse shrinks into what is left
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            val artModifier = Modifier
                .weight(1f)
                .fillMaxWidth()

            CallArt(state = state, audioLevel = audioLevel, modifier = artModifier)
            CallCaptions(state = state, modifier = Modifier.padding(top = 16.dp))
        }

        CallControls(state = state, onAction = onAction, modifier = Modifier.padding(vertical = 32.dp))
    }
}

/** Two panes side by side: the eclipse and the heading, and the captions above the buttons. */
@Composable
private fun WideCallLayout(state: CallUiState, onAction: (CallAction) -> Unit, audioLevel: () -> Float) {
    Row(modifier = Modifier.fillMaxSize()) {
        val paneModifier = Modifier
            .weight(1f)
            .fillMaxHeight()

        CallArt(state = state, audioLevel = audioLevel, modifier = paneModifier)

        Spacer(modifier = Modifier.width(24.dp))

        // The buttons get their full size first; the captions take what is left, up to theirs
        Column(
            modifier = paneModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CallCaptions(state = state, modifier = Modifier.weight(1f, fill = false))
            CallControls(state = state, onAction = onAction, modifier = Modifier.padding(top = 32.dp))
        }
    }
}

/**
 * The eclipse above the assistant's name, the call time and the phase, a little above the middle
 * of the space they are given. The eclipse is the largest square that fits, and the first to give
 * way when the screen is short.
 */
@Composable
private fun CallArt(state: CallUiState, audioLevel: () -> Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, ART_ALIGNMENT),
    ) {
        val eclipseModifier = Modifier
            .weight(1f, fill = false)
            .aspectRatio(1f)

        SolarEclipse(mood = eclipseMood(state), level = audioLevel, modifier = eclipseModifier)
        CallHeading(state = state)
    }
}

/** How the eclipse moves in each phase of the call. */
internal fun eclipseMood(state: CallUiState): EclipseMood = when (state.phase) {
    CallPhase.CONNECTING, CallPhase.ENDED -> EclipseMood.IDLE
    CallPhase.LISTENING -> if (state.isUserSpeaking) EclipseMood.USER_SPEAKING else EclipseMood.LISTENING
    CallPhase.THINKING -> EclipseMood.THINKING
    CallPhase.SPEAKING -> EclipseMood.SPEAKING
}

/** The assistant's name, the call time and the phase ("Listening…"). */
@Composable
private fun CallHeading(state: CallUiState) {
    val colors = MaterialTheme.colorScheme
    val statusText = when {
        state.isMuted && state.phase == CallPhase.LISTENING -> stringResource(R.string.call_status_muted)
        else -> stringResource(phaseLabel(state.phase))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.assistant.name,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Text(
            text = formatCallDuration(state.elapsedSeconds),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun phaseLabel(phase: CallPhase): Int = when (phase) {
    CallPhase.CONNECTING -> R.string.call_status_connecting
    CallPhase.LISTENING -> R.string.call_status_listening
    CallPhase.THINKING -> R.string.call_status_thinking
    CallPhase.SPEAKING -> R.string.call_status_speaking
    CallPhase.ENDED -> R.string.call_status_ended
}

/** The live captions: the user's last words, and below them the answer being spoken. */
@Composable
private fun CallCaptions(state: CallUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScrollingCaption(
            text = state.userCaption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScrollingCaption(
            text = state.assistantCaption,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Up to three lines of [text], centred. Longer text scrolls up as it grows, so the newest words
 * stay visible, and the lines scrolled away fade out at the top.
 */
@Composable
private fun ScrollingCaption(text: String, style: TextStyle, color: Color) {
    val scrollState = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val captionHeight = remember(style, density) {
        val sample = List(CAPTION_LINES) { "Ag" }.joinToString("\n")
        val layout = textMeasurer.measure(sample, style)
        with(density) { layout.size.height.toDp() }
    }

    // Follow the text as it grows; a newer length cancels the scroll to the older one
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collectLatest { bottom -> scrollState.animateScrollTo(bottom) }
    }

    val captionModifier = Modifier
        .fillMaxWidth()
        .height(captionHeight)
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            if (scrollState.value > 0) {
                val fade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black,
                )
                drawRect(brush = fade, blendMode = BlendMode.DstIn)
            }
        }
        .verticalScroll(scrollState, enabled = false)

    Box(modifier = captionModifier) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Mute, end call and speaker, like a phone's in-call buttons. */
@Composable
private fun CallControls(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        val muteIcon = if (state.isMuted) R.drawable.tabler_microphone_off else R.drawable.tabler_microphone
        CallToggleButton(
            icon = muteIcon,
            label = stringResource(R.string.call_mute),
            checked = state.isMuted,
            onToggle = { onAction(CallAction.ToggleMute) },
        )
        EndCallButton(onClick = { onAction(CallAction.EndCall) })
        CallToggleButton(
            icon = R.drawable.tabler_volume,
            label = stringResource(R.string.call_speaker),
            checked = state.isSpeakerOn,
            onToggle = { onAction(CallAction.ToggleSpeaker) },
        )
    }
}

/** A round on/off button with its [label] below; when on, it is filled with the primary colour. */
@Composable
private fun CallToggleButton(
    @DrawableRes icon: Int,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (checked) colors.primary else colors.surfaceVariant
    val iconTint = if (checked) colors.onPrimary else colors.onSurfaceVariant
    val toggleModifier = Modifier.toggleable(
        value = checked,
        role = Role.Switch,
        onValueChange = { onToggle() },
    )

    Column(modifier = toggleModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(icon = icon, size = 64.dp, background = background, tint = iconTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The destructive action, in the error colour like the app's other danger buttons. */
@Composable
private fun EndCallButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val buttonModifier = Modifier.clickable(role = Role.Button, onClick = onClick)

    Column(modifier = buttonModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(
            icon = R.drawable.tabler_phone_off,
            size = 72.dp,
            background = colors.error,
            tint = colors.onError,
        )
        Text(
            text = stringResource(R.string.call_end),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A decorative circle with an icon at 40 % of its size; the surrounding button carries the label. */
@Composable
private fun RoundIcon(@DrawableRes icon: Int, size: Dp, background: Color, tint: Color) {
    val circleModifier = Modifier
        .size(size)
        .background(background, CircleShape)

    Box(modifier = circleModifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.4f),
        )
    }
}
