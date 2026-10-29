package com.danemadsen.curie.data

private const val MEGABYTE = 1024L * 1024L
private const val MINUTE_MS = 60_000L

/**
 * The largest media [AttachmentStore] accepts. These bound storage, not prompts: audio costs the
 * model 12.5 tokens a second (a video's soundtrack too), so ten minutes are 7,500 tokens, more
 * than a chat's context holds. The prompt builder must still check media against its budget.
 */
data class AttachmentLimits(
    val maxImageBytes: Long = 50 * MEGABYTE,
    val maxVideoBytes: Long = 500 * MEGABYTE,
    val maxVideoDurationMs: Long = 5 * MINUTE_MS,

    /** Ten minutes of 48 kHz stereo PCM need 110 MB; compressed audio far less. */
    val maxAudioBytes: Long = 200 * MEGABYTE,
    val maxAudioDurationMs: Long = 10 * MINUTE_MS,
) {
    /** The kind is known only after the copy, so a copy may grow to the largest limit. */
    val maxImportBytes: Long
        get() = maxOf(maxImageBytes, maxVideoBytes, maxAudioBytes)
}
