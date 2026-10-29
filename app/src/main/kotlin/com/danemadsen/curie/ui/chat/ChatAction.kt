package com.danemadsen.curie.ui.chat

/**
 * What the user asks for on the chat screen. Some actions need the activity (pickers, the camera,
 * documents) and are best handled where [ChatScreen] is shown; the rest go to the state holder.
 */
sealed interface ChatAction {
    // Header

    /** The model pill was tapped: download, retry or show the model's state. */
    data object OpenModelStatus : ChatAction

    /** Start a voice call with the assistant. */
    data object StartCall : ChatAction

    // Drawer

    /** Pick chat export files (JSON) to import. */
    data object ImportChats : ChatAction

    /** Delete every chat (already confirmed by the user). */
    data object ClearChats : ChatAction

    data object NewChat : ChatAction

    data object OpenSettings : ChatAction

    data class SelectChat(val chatId: String) : ChatAction

    data class RenameChat(val chatId: String, val title: String) : ChatAction

    /** Save the chat to a file of the user's choice. */
    data class ExportChat(val chatId: String) : ChatAction

    data class DeleteChat(val chatId: String) : ChatAction

    // Messages

    /** Read an assistant message aloud. */
    data class Speak(val messageId: String) : ChatAction

    data object StopSpeaking : ChatAction

    /** Generate another answer in place of an assistant message, keeping the old one as a sibling. */
    data class Regenerate(val messageId: String) : ChatAction

    data class StartEditing(val messageId: String) : ChatAction

    /** Send [text] as a new sibling of the edited user message and answer it. */
    data class ConfirmEdit(val messageId: String, val text: String) : ChatAction

    data object CancelEdit : ChatAction

    /** Show the previous alternative at this message's place in the chat. */
    data class ShowPreviousSibling(val messageId: String) : ChatAction

    /** Show the next alternative at this message's place in the chat. */
    data class ShowNextSibling(val messageId: String) : ChatAction

    data class DeleteMessage(val messageId: String) : ChatAction

    /** Play, pause or resume an audio clip (a voice message or a spoken answer). */
    data class ToggleAudio(val clipId: String) : ChatAction

    /** Open a message's picture or video full screen. */
    data class OpenAttachment(val messageId: String, val attachmentId: String) : ChatAction

    /** Open the web search or page of a message's tool action shown as a link. */
    data class OpenToolLink(val messageId: String, val toolIndex: Int) : ChatAction

    // Composer

    /** Send the prompt text and the attachments. */
    data object Send : ChatAction

    data object StopGenerating : ChatAction

    data class AddMedia(val source: MediaSource) : ChatAction

    data class RemoveAttachment(val attachmentId: String) : ChatAction

    data object StartRecording : ChatAction

    data object CancelRecording : ChatAction

    /** Stop recording and send the voice message. */
    data object SendRecording : ChatAction
}
