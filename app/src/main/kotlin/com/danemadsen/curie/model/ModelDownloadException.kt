package com.danemadsen.curie.model

import java.io.IOException

/** Why [ModelDownloader.download] stopped. Verified files stay; a partial one stays for a resume. */
sealed class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val failure: ModelFailure

    /** Worth another try later (the network or the server may recover). */
    open val isTransient: Boolean = false

    class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        ModelDownloadException("the download needs $requiredBytes bytes but $availableBytes are free") {
        override val failure = ModelFailure.INSUFFICIENT_STORAGE
    }

    /** The connection failed on every attempt. */
    class Network(val fileName: String, cause: Throwable?) :
        ModelDownloadException("downloading $fileName failed", cause) {
        override val failure = ModelFailure.NETWORK
        override val isTransient = true
    }

    /** An HTTP error status; 408, 429 and 5xx are transient (only thrown once retries ran out). */
    class Http(val fileName: String, val code: Int) :
        ModelDownloadException("downloading $fileName failed with HTTP $code") {
        override val failure = ModelFailure.SERVER
        override val isTransient = code == 408 || code == 429 || code >= 500
    }

    /** A response that cannot be the pinned file (another size). */
    class UnexpectedResponse(val fileName: String, detail: String) :
        ModelDownloadException("unexpected response for $fileName: $detail") {
        override val failure = ModelFailure.SERVER
    }

    /** The downloaded bytes are not the pinned file; the partial file was deleted. */
    class Corrupt(val fileName: String, detail: String) :
        ModelDownloadException("$fileName is corrupt: $detail") {
        override val failure = ModelFailure.CORRUPT_DOWNLOAD
    }

    class Storage(cause: IOException) : ModelDownloadException("cannot write the model files", cause) {
        override val failure = ModelFailure.STORAGE
    }
}
