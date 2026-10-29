package com.danemadsen.curie.data

/** Why [AttachmentStore] rejected a file. Nothing is left in storage when it is thrown. */
sealed class AttachmentImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not an image, video or audio file the platform can read, whatever the picker claimed. */
    class Unsupported(val mimeTypeHint: String?) :
        AttachmentImportException("unsupported media (claimed type: ${mimeTypeHint ?: "none"})")

    /**
     * Over the size limit for its [kind]; the kind is null when the file outgrew every limit while
     * it was copied, in which case [sizeBytes] is how much was read before stopping.
     */
    class TooLarge(val kind: AttachmentKind?, val sizeBytes: Long, val limitBytes: Long) :
        AttachmentImportException("${kind ?: "media"} of $sizeBytes bytes exceeds the limit of $limitBytes bytes")

    class TooLong(val kind: AttachmentKind, val durationMs: Long, val limitMs: Long) :
        AttachmentImportException("$kind of $durationMs ms exceeds the limit of $limitMs ms")

    /** The source could not be opened or read (a revoked permission, a deleted file). */
    class Unreadable(cause: Throwable) : AttachmentImportException("cannot read the media", cause)

    /** App storage could not take the copy (usually: the device is full). */
    class StorageFailed(cause: Throwable) : AttachmentImportException("cannot store the media", cause)
}
