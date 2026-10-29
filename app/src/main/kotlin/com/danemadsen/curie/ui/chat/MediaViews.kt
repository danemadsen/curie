package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.format.formatClipDuration

/** Maid shows message pictures 80 % as wide as the message, centred... */
private const val MEDIA_WIDTH_FRACTION = 0.8f

/** ...with 8 dp corners. */
private val MEDIA_SHAPE = RoundedCornerShape(8.dp)

/** Tall pictures and videos are narrowed to keep them at most this tall. */
private val MEDIA_MAX_HEIGHT = 480.dp

/** Darkens a video's thumbnail behind its play button and duration. */
private val VIDEO_OVERLAY_COLOR = Color.Black.copy(alpha = 0.45f)

/**
 * A picture or video of a message, fitted whole (no cropping) into 80 % of the width, or less
 * for tall media, centred, with rounded corners. A video shows its first frame, a play button and
 * its length. Tapping opens it.
 */
@Composable
internal fun MessageMedia(attachment: AttachmentUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    when (attachment) {
        is AttachmentUi.Image -> FittedMedia(
            uri = attachment.uri,
            width = attachment.width,
            height = attachment.height,
            description = stringResource(R.string.message_image),
            onOpen = onOpen,
            modifier = modifier,
        )
        is AttachmentUi.Video -> FittedMedia(
            uri = attachment.uri,
            width = attachment.width,
            height = attachment.height,
            description = stringResource(R.string.message_video),
            onOpen = onOpen,
            modifier = modifier,
        ) {
            VideoBadges(durationMillis = attachment.durationMillis, playButtonSize = 48.dp)
        }
        is AttachmentUi.Audio -> Unit
    }
}

/** [width] and [height] give the media's shape; when unknown (0) it is fitted into a square. */
@Composable
private fun FittedMedia(
    uri: String,
    width: Int,
    height: Int,
    description: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    val aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 1f
    val openLabel = stringResource(R.string.message_open_media)

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val widestFit = maxWidth * MEDIA_WIDTH_FRACTION
        val tallestFit = MEDIA_MAX_HEIGHT * aspectRatio
        val mediaWidth = min(widestFit, tallestFit)

        val mediaModifier = Modifier
            .width(mediaWidth)
            .aspectRatio(aspectRatio)
            .clip(MEDIA_SHAPE)
            .clickable(onClickLabel = openLabel, role = Role.Image, onClick = onOpen)

        Box(modifier = mediaModifier) {
            AsyncImage(
                model = uri,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            overlay()
        }
    }
}

/** A thumbnail of the composer's attachments: a 100 dp rounded square. */
@Composable
internal fun AttachmentThumbnail(attachment: AttachmentUi, modifier: Modifier = Modifier) {
    val thumbnailModifier = modifier
        .size(100.dp)
        .clip(MEDIA_SHAPE)

    when (attachment) {
        is AttachmentUi.Image -> AsyncImage(
            model = attachment.uri,
            contentDescription = stringResource(R.string.message_image),
            contentScale = ContentScale.Crop,
            modifier = thumbnailModifier,
        )
        is AttachmentUi.Video -> Box(modifier = thumbnailModifier) {
            AsyncImage(
                model = attachment.uri,
                contentDescription = stringResource(R.string.message_video),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            VideoBadges(durationMillis = attachment.durationMillis, playButtonSize = 32.dp)
        }
        is AttachmentUi.Audio -> VoiceMessageThumbnail(
            durationMillis = attachment.clip.durationMillis,
            modifier = thumbnailModifier,
        )
    }
}

/** A recorded voice message waiting to be sent: a microphone and its length. */
@Composable
private fun VoiceMessageThumbnail(durationMillis: Long, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val thumbnailModifier = modifier.background(colors.surfaceVariant)

    Column(
        modifier = thumbnailModifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.tabler_microphone),
            contentDescription = stringResource(R.string.message_voice_message),
            tint = colors.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = formatClipDuration(durationMillis),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A video thumbnail's centred play button and its length in the bottom corner. */
@Composable
private fun VideoBadges(durationMillis: Long, playButtonSize: Dp) {
    Box(modifier = Modifier.fillMaxSize()) {
        val playModifier = Modifier
            .align(Alignment.Center)
            .size(playButtonSize)
            .background(VIDEO_OVERLAY_COLOR, CircleShape)

        Box(modifier = playModifier, contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.tabler_player_play_filled),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(playButtonSize / 2),
            )
        }

        val durationModifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp)
            .background(VIDEO_OVERLAY_COLOR, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)

        Text(
            text = formatClipDuration(durationMillis),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = durationModifier,
        )
    }
}
