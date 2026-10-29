package com.danemadsen.curie.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.common.Persona

/** The user's avatar: their picture, or Maid's account silhouette in the secondary colour. */
@Composable
fun UserAvatar(user: Persona, size: Dp, modifier: Modifier = Modifier) {
    PersonaAvatar(
        imageUri = user.imageUri,
        fallbackIcon = R.drawable.tabler_user_filled,
        size = size,
        modifier = modifier,
    )
}

/** The assistant's avatar: its picture, or sparkles in the secondary colour. */
@Composable
fun AssistantAvatar(assistant: Persona, size: Dp, modifier: Modifier = Modifier) {
    PersonaAvatar(
        imageUri = assistant.imageUri,
        fallbackIcon = R.drawable.tabler_sparkles_filled,
        size = size,
        modifier = modifier,
    )
}

/**
 * Maid's UserImageView / AssistantImageView: a round picture of [size], or the [fallbackIcon] when
 * there is no picture or it cannot be loaded. Decorative: the name is always shown next to it.
 */
@Composable
private fun PersonaAvatar(
    imageUri: String?,
    @DrawableRes fallbackIcon: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val fallbackModifier = modifier.size(size)

    if (imageUri == null) {
        Icon(
            painter = painterResource(fallbackIcon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = fallbackModifier,
        )
        return
    }

    val painter = rememberAsyncImagePainter(model = imageUri, contentScale = ContentScale.Crop)
    val painterState by painter.state.collectAsState()

    if (painterState is AsyncImagePainter.State.Error) {
        Icon(
            painter = painterResource(fallbackIcon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = fallbackModifier,
        )
    } else {
        val imageModifier = modifier
            .size(size)
            .clip(CircleShape)

        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier,
        )
    }
}
