package com.danemadsen.curie.model

enum class DownloadPhase {
    /** Hashing bytes already on disk; [DownloadProgress.doneBytes] does not move meanwhile. */
    VERIFYING,
    DOWNLOADING,
}

/** Progress over the whole download: [doneBytes] of [totalBytes], working on [fileName]. */
data class DownloadProgress(
    val phase: DownloadPhase,
    val doneBytes: Long,
    val totalBytes: Long,
    val fileName: String,
)
