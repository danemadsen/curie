package com.danemadsen.curie.model

import com.danemadsen.curie.engine.ModelFiles
import com.danemadsen.curie.engine.RemoteFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads [files] into [directory] (flat) and verifies each against its pinned size and SHA-256.
 * Plain Kotlin on OkHttp, so it runs in JVM tests.
 *
 * - Resumable: bytes go to "<name>.part"; a later run hashes the part again and asks for the rest
 *   with a Range request. A 206 must start where the part ends; a 200 restarts the file.
 * - The hash grows with every chunk written; a matching part is synced and renamed atomically,
 *   and recorded ([VerificationCache]) so later runs skip it without hashing.
 * - Free space is checked, and made (Android clears other apps' caches, see [StorageSpace]),
 *   before anything is fetched. A connection that fails is retried with backoff; [MAX_ATTEMPTS]
 *   attempts in a row without progress fail the download.
 * - Cancelling the coroutine stops it promptly: the in-flight call is cancelled as well.
 *
 * Redirects (Hugging Face sends the bytes from a CDN) are followed by OkHttp; no credentials are
 * involved. One download runs per process at a time ([lock]).
 */
class ModelDownloader internal constructor(
    private val client: OkHttpClient,
    private val directory: File,
    private val files: List<RemoteFile>,
    private val storage: StorageSpace,
    private val urlOf: (RemoteFile) -> String,
    private val retryDelayMs: Long,

    /** Opens a ".part" file to append to (tests make writes fail). */
    private val openPart: (File) -> OutputStream,
) {
    constructor(
        client: OkHttpClient,
        directory: File,
        files: List<RemoteFile> = ModelFiles.DOWNLOADS,
        storage: StorageSpace = FileStorageSpace,
        urlOf: (RemoteFile) -> String = { file -> file.url },
        retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    ) : this(
        client = client,
        directory = directory,
        files = files,
        storage = storage,
        urlOf = urlOf,
        retryDelayMs = retryDelayMs,
        openPart = { part -> FileOutputStream(part, true) },
    )

    private val totalBytes: Long = files.sumOf { file -> file.size }

    /**
     * Brings every file to the directory, reporting progress on the downloading coroutine (the
     * callback should be cheap: it runs for every 64 KiB). Throws [ModelDownloadException].
     */
    suspend fun download(onProgress: suspend (DownloadProgress) -> Unit = {}) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw ModelDownloadException.Storage(IOException("cannot create $directory"))
                }

                val cache = VerificationCache.load(directory)
                checkFreeSpace(cache)

                val progress = ProgressTracker(totalBytes, onProgress)
                for (file in files) {
                    fetchFile(file, cache, progress)
                }
            }
        }
    }

    private suspend fun fetchFile(file: RemoteFile, cache: VerificationCache, progress: ProgressTracker) {
        val target = targetOf(file)
        if (cache.isVerified(file, target)) {
            progress.finished(file)
            return
        }

        // A file without a record (the record was lost): verify it before fetching it again
        if (target.exists()) {
            progress.verifying(file)
            val matches = target.length() == file.size && sha256Of(target) == file.sha256
            if (matches) {
                cache.markVerified(file, target)
                progress.finished(file)
                return
            }
            onDisk { Files.delete(target.toPath()) }
        }

        downloadFile(file, target, progress)
        cache.markVerified(file, target)
        progress.finished(file)
    }

    private suspend fun downloadFile(file: RemoteFile, target: File, progress: ProgressTracker) {
        val part = PartFile(partOf(file))
        if (part.file.exists()) {
            progress.verifying(file)
            onDisk { part.resume(file.size) }
        }

        var attemptsWithoutProgress = 0
        while (part.length < file.size) {
            val lengthBefore = part.length
            val failure = try {
                fetchRemainder(file, part, progress)
                null
            } catch (error: IOException) {
                // Cancellation surfaces as a "Canceled" IOException from the call: never retry it
                currentCoroutineContext().ensureActive()
                onDisk { part.cutToLength() }
                error
            }

            if (part.length > lengthBefore) {
                attemptsWithoutProgress = 0
                continue
            }
            attemptsWithoutProgress += 1
            if (attemptsWithoutProgress >= MAX_ATTEMPTS) {
                throw exhausted(file, failure)
            }
            delay(retryDelayMs shl (attemptsWithoutProgress - 1))
        }

        finish(file, part, target)
    }

    /** Requests the bytes after the part and appends them; network trouble is an IOException. */
    private suspend fun fetchRemainder(file: RemoteFile, part: PartFile, progress: ProgressTracker) {
        val builder = Request.Builder()
        builder.url(urlOf(file))
        // OkHttp would otherwise ask for gzip, which hides the length and breaks byte ranges
        builder.header("Accept-Encoding", "identity")
        if (part.length > 0) {
            builder.header("Range", "bytes=${part.length}-")
        }
        val request = builder.build()

        executeCancellable(request) { response ->
            when (response.code) {
                HTTP_PARTIAL_CONTENT -> checkContentRange(file, part, response)
                HTTP_OK -> {
                    checkContentLength(file, response)
                    // The server ignored the range: the body is the whole file
                    onDisk { part.restart() }
                }
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    onDisk { part.restart() }
                    throw RetryableStatusException(response.code)
                }
                else -> throw statusFailure(file, response.code)
            }
            appendBody(file, part, response.body, progress)
        }
    }

    private fun checkContentRange(file: RemoteFile, part: PartFile, response: Response) {
        val header = response.header("Content-Range")
        val range = header?.let { value -> ContentRange.parse(value) }
        if (range?.total != null && range.total != file.size) {
            throw ModelDownloadException.UnexpectedResponse(file.fileName, "size ${range.total}, expected ${file.size}")
        }
        if (range == null || range.start != part.length || range.end < range.start) {
            // Other bytes than asked for: start the file over rather than trust this server's ranges
            onDisk { part.restart() }
            throw InvalidRangeException("asked for bytes from ${part.length}, got \"$header\"")
        }
    }

    private fun checkContentLength(file: RemoteFile, response: Response) {
        val length = response.body.contentLength()
        if (length >= 0 && length != file.size) {
            throw ModelDownloadException.UnexpectedResponse(file.fileName, "length $length, expected ${file.size}")
        }
    }

    private suspend fun appendBody(file: RemoteFile, part: PartFile, body: ResponseBody, progress: ProgressTracker) {
        val input = body.byteStream()
        val buffer = ByteArray(BUFFER_BYTES)
        val output = onDisk { openPart(part.file) }

        output.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }

                if (part.length + count > file.size) {
                    part.file.delete()
                    throw ModelDownloadException.Corrupt(file.fileName, "the server sent more than ${file.size} bytes")
                }

                try {
                    stream.write(buffer, 0, count)
                } catch (error: IOException) {
                    part.cutToLengthQuietly()
                    throw storageFailure(error)
                }
                part.appended(buffer, count)
                progress.downloading(file, part.length)
            }
        }
    }

    private fun finish(file: RemoteFile, part: PartFile, target: File) {
        val length = part.file.length()
        val sha256 = part.sha256()
        if (length != file.size || sha256 != file.sha256) {
            part.file.delete()
            throw ModelDownloadException.Corrupt(file.fileName, "$length bytes with SHA-256 $sha256")
        }

        onDisk {
            FileOutputStream(part.file, true).use { output -> output.fd.sync() }
            Files.move(
                part.file.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun checkFreeSpace(cache: VerificationCache) {
        val missingBytes = files.sumOf { file -> missingBytesOf(file, cache) }
        if (missingBytes == 0L) {
            return
        }

        val requiredBytes = missingBytes + SPACE_MARGIN_BYTES
        // A volume that cannot tell its free space gets the benefit of the doubt: writes still classify
        val available = allocatableBytesOrNull() ?: return
        if (available < requiredBytes) {
            throw ModelDownloadException.InsufficientStorage(requiredBytes, available)
        }
        try {
            storage.allocate(directory, requiredBytes)
        } catch (error: IOException) {
            throw ModelDownloadException.InsufficientStorage(requiredBytes, available)
        }
    }

    private fun missingBytesOf(file: RemoteFile, cache: VerificationCache): Long {
        val target = targetOf(file)
        if (cache.isVerified(file, target) || target.length() == file.size) {
            return 0
        }
        val partLength = partOf(file).length()
        return if (partLength <= file.size) file.size - partLength else file.size
    }

    private suspend fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        onDisk { hashInto(file, digest) }
        return digest.digest().toHex()
    }

    /**
     * Runs [request] and hands the response to [block]. A blocked read ignores coroutine
     * cancellation, so a cancelled coroutine cancels the call, which ends the read.
     */
    private suspend fun <T> executeCancellable(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // A call that already completed ignores this
                call.cancel()
            }
        }

        try {
            call.execute().use { response -> block(response) }
        } finally {
            canceller.cancel()
        }
    }

    private fun statusFailure(file: RemoteFile, code: Int): Exception {
        val transient = code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code >= 500
        if (transient) {
            return RetryableStatusException(code)
        }
        return ModelDownloadException.Http(file.fileName, code)
    }

    private fun exhausted(file: RemoteFile, failure: IOException?): ModelDownloadException {
        if (failure is RetryableStatusException) {
            return ModelDownloadException.Http(file.fileName, failure.code)
        }
        return ModelDownloadException.Network(file.fileName, failure)
    }

    /** A disk operation: its IOException is a storage failure, never a retried network error. */
    private inline fun <T> onDisk(operation: () -> T): T {
        try {
            return operation()
        } catch (error: IOException) {
            throw storageFailure(error)
        }
    }

    private fun storageFailure(error: IOException): ModelDownloadException {
        val requiredBytes = totalBytes - bytesOnDisk(directory, files) + SPACE_MARGIN_BYTES
        val available = allocatableBytesOrNull()
        if (available != null && available < requiredBytes) {
            return ModelDownloadException.InsufficientStorage(requiredBytes, available)
        }
        return ModelDownloadException.Storage(error)
    }

    private fun allocatableBytesOrNull(): Long? {
        return try {
            storage.allocatableBytes(directory)
        } catch (error: IOException) {
            null
        }
    }

    private fun targetOf(file: RemoteFile): File = targetOf(directory, file)

    private fun partOf(file: RemoteFile): File = partOf(directory, file)

    /** Reports progress over all files: the finished ones plus the current one's bytes. */
    private class ProgressTracker(
        private val totalBytes: Long,
        private val listener: suspend (DownloadProgress) -> Unit,
    ) {
        private var finishedBytes = 0L

        suspend fun verifying(file: RemoteFile) {
            val progress = DownloadProgress(DownloadPhase.VERIFYING, finishedBytes, totalBytes, file.fileName)
            listener(progress)
        }

        suspend fun downloading(file: RemoteFile, fileBytes: Long) {
            val doneBytes = finishedBytes + fileBytes
            val progress = DownloadProgress(DownloadPhase.DOWNLOADING, doneBytes, totalBytes, file.fileName)
            listener(progress)
        }

        fun finished(file: RemoteFile) {
            finishedBytes += file.size
        }
    }

    /** A ".part" file and the SHA-256 of exactly its first [length] bytes. */
    private class PartFile(val file: File) {
        private val digest = MessageDigest.getInstance(SHA_256)

        var length = 0L
            private set

        /** Takes up what an earlier run left: hashes it, or deletes a part longer than the file. */
        suspend fun resume(expectedSize: Long) {
            val existing = file.length()
            if (existing > expectedSize) {
                Files.delete(file.toPath())
                return
            }
            hashInto(file, digest)
            length = existing
        }

        fun restart() {
            truncate(0)
            digest.reset()
            length = 0
        }

        /** Call once [count] bytes of [buffer] are written at the end of the file. */
        fun appended(buffer: ByteArray, count: Int) {
            digest.update(buffer, 0, count)
            length += count
        }

        /** Cuts bytes a failed write may have left beyond [length]. */
        fun cutToLength() {
            if (file.length() > length) {
                truncate(length)
            }
        }

        fun cutToLengthQuietly() {
            try {
                cutToLength()
            } catch (error: IOException) {
                // A later resume hashes the extra bytes, and the hash check rejects the file
            }
        }

        /** The hash of the bytes appended so far; call once, at the end. */
        fun sha256(): String = digest.digest().toHex()

        private fun truncate(size: Long) {
            RandomAccessFile(file, "rw").use { access -> access.setLength(size) }
        }
    }

    /** A Content-Range header: "bytes <start>-<end>/<total or *>". */
    private class ContentRange(val start: Long, val end: Long, val total: Long?) {
        companion object {
            private val PATTERN = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

            fun parse(header: String): ContentRange? {
                val match = PATTERN.matchEntire(header.trim()) ?: return null
                val (start, end, total) = match.destructured
                return ContentRange(start.toLong(), end.toLong(), total.toLongOrNull())
            }
        }
    }

    /** A status worth retrying (408, 416, 429, 5xx). */
    private class RetryableStatusException(val code: Int) : IOException("HTTP $code")

    private class InvalidRangeException(message: String) : IOException(message)

    companion object {
        const val PART_SUFFIX = ".part"

        /** Attempts in a row that move no byte before a file fails. */
        const val MAX_ATTEMPTS = 3

        /** Room for the bundled LoRA assets (68 MB) and for the system to breathe. */
        const val SPACE_MARGIN_BYTES = 128L * 1024 * 1024

        private const val DEFAULT_RETRY_DELAY_MS = 2_000L
        private const val BUFFER_BYTES = 64 * 1024
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val SHA_256 = "SHA-256"

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * Held for a whole download. A replaced worker may still be unwinding when its successor
         * starts, and deleting the model must wait for a cancelled download to let go of the files.
         */
        internal val lock = Mutex()

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** Every file is present and verified. Reads the verification record; hashes nothing. */
        fun isComplete(directory: File, files: List<RemoteFile> = ModelFiles.DOWNLOADS): Boolean {
            val cache = VerificationCache.load(directory)
            return files.all { file -> cache.isVerified(file, targetOf(directory, file)) }
        }

        /** Bytes of the download already on disk: verified files and partial ones. */
        fun bytesOnDisk(directory: File, files: List<RemoteFile> = ModelFiles.DOWNLOADS): Long {
            val cache = VerificationCache.load(directory)
            var bytes = 0L
            for (file in files) {
                if (cache.isVerified(file, targetOf(directory, file))) {
                    bytes += file.size
                } else {
                    val partLength = partOf(directory, file).length()
                    bytes += partLength.coerceAtMost(file.size)
                }
            }
            return bytes
        }

        private fun targetOf(directory: File, file: RemoteFile): File = File(directory, file.fileName)

        private fun partOf(directory: File, file: RemoteFile): File = File(directory, file.fileName + PART_SUFFIX)

        private suspend fun hashInto(file: File, digest: MessageDigest) {
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    digest.update(buffer, 0, count)
                }
            }
        }

        private fun ByteArray.toHex(): String {
            val builder = StringBuilder(size * 2)
            for (byte in this) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX_DIGITS[value ushr 4])
                builder.append(HEX_DIGITS[value and 0x0F])
            }
            return builder.toString()
        }
    }
}
