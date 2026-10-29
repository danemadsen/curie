package com.danemadsen.curie.ui.chat

import androidx.compose.runtime.Immutable
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona

/**
 * Everything the chat screen shows. The prompt text is not part of it: the composer edits a
 * TextFieldState passed next to this state (see [ChatScreen]).
 */
@Immutable
data class ChatUiState(
    val modelStatus: ModelStatus,
    val user: Persona,
    val assistant: Persona,

    /** The drawer's chats, newest first. */
    val chats: List<ChatSummary> = emptyList(),

    val activeChatId: String? = null,

    /**
     * The active chat's current branch, oldest first: the user's and the assistant's messages
     * (the system prompt and tool results are not shown; tool calls show as [MessageUi.toolActions]).
     */
    val messages: List<MessageUi> = emptyList(),

    /**
     * An answer is being generated (from sending until it ends). The message controls are disabled
     * meanwhile and the composer's button stops the generation.
     */
    val isGenerating: Boolean = false,

    /** The message being edited in place; the other messages' controls are disabled meanwhile. */
    val editingMessageId: String? = null,

    /** The assistant message being read aloud, if any. */
    val speakingMessageId: String? = null,

    /** The audio clip that is playing or paused, if any. */
    val audioPlayback: AudioPlayback? = null,

    val composer: ComposerUiState = ComposerUiState(),
)

/** A chat in the drawer. A blank [title] shows as "New Chat". */
@Immutable
data class ChatSummary(val id: String, val title: String)

enum class MessageRole {
    USER,
    ASSISTANT,
}

/** A message of the current branch. */
@Immutable
data class MessageUi(
    val id: String,
    val role: MessageRole,

    /** Markdown; streams in while the answer is generated. */
    val text: String,

    /** What the user sent with the text: pictures, videos and voice messages. */
    val attachments: List<AttachmentUi> = emptyList(),

    /** What the assistant did through its tools while answering, in order. */
    val toolActions: List<ToolActionUi> = emptyList(),

    /** The assistant's spoken answer, once there is speech to play back. */
    val spokenReply: AudioClipUi? = null,

    /** This message's position among the alternatives at its place in the chat (0-based). */
    val siblingIndex: Int = 0,

    val siblingCount: Int = 1,

    /** Why producing this answer failed; regenerating it tries again. */
    val error: String? = null,

    /**
     * A later round of an answer, after its tools ran: it carries on the message above it and shows
     * without an author or controls of its own (the answer's first message has them for all of it).
     */
    val isContinuation: Boolean = false,
)

/** A media item of a message or of the composer. */
@Immutable
sealed interface AttachmentUi {
    val id: String

    /** A picture of [width] by [height] pixels (upright); 0 when unknown. */
    data class Image(
        override val id: String,
        val uri: String,
        val width: Int = 0,
        val height: Int = 0,
    ) : AttachmentUi

    /** A video; its first frame is shown as the thumbnail. */
    data class Video(
        override val id: String,
        val uri: String,
        val durationMillis: Long,
        val width: Int = 0,
        val height: Int = 0,
    ) : AttachmentUi

    /** A voice message the user recorded. */
    data class Audio(val clip: AudioClipUi) : AttachmentUi {
        override val id: String
            get() = clip.id
    }
}

/** A playable audio clip: a voice message or a spoken answer. */
@Immutable
data class AudioClipUi(
    val id: String,
    val durationMillis: Long,

    /** Loudness from 0 to 1 over the clip, any number of points; empty draws a neutral shape. */
    val waveform: List<Float> = emptyList(),
)

/** The clip that is playing or paused, and where. */
@Immutable
data class AudioPlayback(
    val clipId: String,
    val isPlaying: Boolean,
    val positionMillis: Long,
)

/** Something the assistant did through a tool, summarised for the chat ("Opened YouTube"). */
@Immutable
data class ToolActionUi(
    val id: String,
    val icon: ToolIcon,
    val summary: String,

    /** False when the action failed; the chip is then drawn in the error colour. */
    val succeeded: Boolean = true,

    /** A web search or page Curie did not open by itself: tapping the chip opens it. */
    val isLink: Boolean = false,
)

/** The pictogram of a [ToolActionUi]. */
enum class ToolIcon {
    APP,
    ALARM,
    TIMER,
    CALENDAR,
    CALL,
    MESSAGE,
    SEARCH,
    WEB,
    MAP,
    MUSIC,
    FLASHLIGHT,
    CAMERA,
    NOTE,
    SETTINGS,
    OTHER,
}

/** The composer below the messages. */
@Immutable
data class ComposerUiState(
    /** Media to send with the next message, shown as thumbnails above the input. */
    val attachments: List<AttachmentUi> = emptyList(),

    /** Non-null while a voice message is being recorded. */
    val recording: RecordingUi? = null,
)

/** A voice message being recorded. */
@Immutable
data class RecordingUi(val elapsedMillis: Long)

/** Where a new attachment comes from. */
enum class MediaSource {
    PICK_PHOTO,
    PICK_VIDEO,
    TAKE_PHOTO,
    RECORD_VIDEO,
}
