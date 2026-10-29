package com.danemadsen.curie.data

/** Why [ChatTransfer.importChat] rejected a file. Nothing is imported when it is thrown. */
sealed class ChatImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not JSON, or not a chat export. */
    class Malformed(cause: Throwable) : ChatImportException("not a Curie chat export", cause)

    /** A chat export of another format or of a newer version of the app. */
    class Unsupported(val format: String, val version: Int) :
        ChatImportException("unsupported chat export: $format version $version")

    /**
     * Too large to read into memory: the file, or one value in it, passed [limitBytes]. Also
     * thrown when the app's memory runs short while reading a file within the limits.
     */
    class TooLarge(val limitBytes: Long) :
        ChatImportException("the chat export is too large to import (limit: $limitBytes bytes)")

    /** The file could not be read (a revoked permission, a provider that failed). */
    class Unreadable(cause: Throwable) : ChatImportException("cannot read the chat export", cause)

    /** App storage could not take the attachments (usually: the device is full). */
    class StorageFailed(cause: Throwable) : ChatImportException("cannot store the chat's attachments", cause)
}

/** What [ChatTransfer.exportChat] wrote: [omittedAttachments] were listed without their data. */
data class ChatExportResult(val omittedAttachments: Int)

/**
 * The imported chat. [skippedAttachments] could not be imported (left out of the export, or not
 * media Curie can read); [skippedToolCalls] were damaged (not a name with JSON arguments).
 */
data class ChatImportResult(
    val chat: Chat,
    val skippedAttachments: Int,
    val skippedToolCalls: Int,
)
