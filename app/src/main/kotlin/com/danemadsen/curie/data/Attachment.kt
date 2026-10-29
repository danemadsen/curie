package com.danemadsen.curie.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The serial names are the chat export's attachment kinds. */
@Serializable
enum class AttachmentKind {
    @SerialName("image")
    IMAGE,

    @SerialName("video")
    VIDEO,

    @SerialName("audio")
    AUDIO,
}

/**
 * A media file in app-private storage ([AttachmentStore.directory]) with the facts the engine's
 * media inputs need up front. The file never changes after the import, so [id] can key the
 * engine's MediaInput. Facts that do not apply to the [kind] are 0.
 */
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val path: String,

    /** The type the content was found to have, not the one the picker claimed. */
    val mimeType: String,

    /** Upright size: EXIF orientation (images) and rotation (videos) applied. */
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val frameCount: Int,
    val fps: Double,

    /**
     * Samples of the audio resampled to 16 kHz mono: the clip (audio) or its soundtrack (video;
     * 0 when it has none). Estimated from the container's duration: a decoder must pad or trim to
     * exactly this count, because the prompt's placeholder count is derived from it.
     */
    val sampleCount16k: Int,

    /**
     * False when the file is gone: a restore from a cloud backup brings the chats back without
     * their media. Show a placeholder and leave the attachment out of prompts.
     */
    val isAvailable: Boolean = true,
) {
    val hasSoundtrack: Boolean
        get() = kind == AttachmentKind.VIDEO && sampleCount16k > 0
}
