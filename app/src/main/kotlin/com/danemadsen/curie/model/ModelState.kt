package com.danemadsen.curie.model

/** Why the model could not be downloaded or installed. */
enum class ModelFailure {
    /** Not enough free storage; [ModelState.Failed.requiredBytes] says how much is needed. */
    INSUFFICIENT_STORAGE,

    /** The connection kept failing (after retries). */
    NETWORK,

    /** The server refused or answered with something other than the pinned file. */
    SERVER,

    /** A downloaded file did not match its pinned size or SHA-256 (it was deleted). */
    CORRUPT_DOWNLOAD,

    /** Writing to app storage failed for a reason other than space. */
    STORAGE,

    /** Copying the bundled LoRA assets next to the downloads failed. */
    INSTALL,

    /** The download worker failed without saying why (a bug). */
    UNKNOWN,
}

/** Where the model stands, derived from the files on disk and the download work. */
sealed interface ModelState {
    data object NotDownloaded : ModelState

    /**
     * Downloading [fileName] (null between files). [isQueued]: the work waits for its network
     * constraint (Wi-Fi only) or a retry; the bytes are what is already on disk.
     */
    data class Downloading(
        val doneBytes: Long,
        val totalBytes: Long,
        val fileName: String?,
        val isQueued: Boolean = false,
    ) : ModelState

    /** Hashing files already on disk (a resumed download, or a file without a verification record). */
    data object Verifying : ModelState

    /** Every download is present and verified; [ModelRepository.ensureReady] installs the rest. */
    data object Ready : ModelState

    data class Failed(
        val reason: ModelFailure,
        val requiredBytes: Long = 0,
    ) : ModelState
}
