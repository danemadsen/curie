package com.danemadsen.curie.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Copies picked, captured and recorded media into app-private storage
 * (filesDir/attachments/<id>.<ext>) and works out the facts the engine needs from the content
 * itself. Files are deleted through [ChatRepository], which knows which messages still use them.
 */
class AttachmentStore internal constructor(
    private val context: Context,
    val directory: File,
    private val limits: AttachmentLimits,
    private val probe: MediaProbe,
) {
    constructor(context: Context, limits: AttachmentLimits = AttachmentLimits()) : this(
        context = context.applicationContext,
        directory = File(context.filesDir, DIRECTORY_NAME),
        limits = limits,
        probe = AndroidMediaProbe(),
    )

    private val captureDirectory = File(context.cacheDir, CAPTURE_DIRECTORY_NAME)
    private val captureAuthority = context.packageName + CAPTURE_AUTHORITY_SUFFIX

    /**
     * A new, empty file for the camera or a recorder to write a [kind] capture into, shared as a
     * content Uri (FileProvider authority "<applicationId>.files"). [import] of this Uri consumes
     * the file; captures never imported (a cancelled camera, an import that failed and was not
     * retried) are cleared after a day.
     */
    suspend fun createCaptureUri(kind: AttachmentKind): Uri = withContext(Dispatchers.IO) {
        captureDirectory.mkdirs()
        val cutoff = System.currentTimeMillis() - STALE_CAPTURE_AGE_MS
        deleteFilesModifiedBefore(captureDirectory, cutoff)

        val extension = when (kind) {
            AttachmentKind.IMAGE -> "jpg"
            AttachmentKind.VIDEO -> "mp4"
            AttachmentKind.AUDIO -> "m4a"
        }
        val capture = File(captureDirectory, "${UUID.randomUUID()}.$extension")
        if (!capture.createNewFile()) {
            throw IOException("cannot create $capture")
        }
        FileProvider.getUriForFile(context, captureAuthority, capture)
    }

    /**
     * Copies the media at [uri] into storage. [mimeTypeHint] (or the provider's type) only orders
     * the probes: the content decides the kind and the type. Throws [AttachmentImportException].
     *
     * A capture Uri ([createCaptureUri]) is consumed once it is imported or its content is
     * refused. After a failure that may pass (a full device, a read error, cancellation) it is
     * kept, so the same Uri can be imported again.
     */
    suspend fun import(uri: Uri, mimeTypeHint: String? = null): Attachment = withContext(Dispatchers.IO) {
        val attachment = try {
            importFrom(uri, mimeTypeHint)
        } catch (error: AttachmentImportException) {
            if (refusesContent(error)) {
                deleteIfCapture(uri)
            }
            throw error
        }

        deleteIfCapture(uri)
        attachment
    }

    /** Copies [input] into storage; [import] and the chat import share this path. */
    internal suspend fun importStream(input: InputStream, mimeTypeHint: String?): Attachment =
        withContext(Dispatchers.IO) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw AttachmentImportException.StorageFailed(IOException("cannot create $directory"))
            }

            val id = UUID.randomUUID().toString()
            val temporary = File(directory, id + TEMPORARY_SUFFIX)
            try {
                val size = copyWithLimit(input, temporary)
                val facts = probe.probe(temporary, mimeTypeHint)
                    ?: throw AttachmentImportException.Unsupported(mimeTypeHint)
                checkLimits(facts, size)

                val extension = extensionFor(facts.mimeType)
                val stored = File(directory, "$id.$extension")
                if (!temporary.renameTo(stored)) {
                    throw AttachmentImportException.StorageFailed(IOException("cannot rename $temporary"))
                }
                toAttachment(id, stored, facts)
            } finally {
                // Already gone after a successful rename
                temporary.delete()
            }
        }

    /** True when [attachment]'s file lives in [directory] (an attachment this store made). */
    internal fun contains(attachment: Attachment): Boolean {
        val file = File(attachment.path)
        return file.parent == directory.path
    }

    /** Deletes a stored file by name; a name that could leave [directory] is ignored. */
    internal fun deleteFile(fileName: String) {
        deleteFileNamed(directory, fileName)
    }

    /** Deletes files no message names that are older than [modifiedBefore]; returns the count. */
    internal fun deleteUnreferencedFiles(referencedFileNames: Set<String>, modifiedBefore: Long): Int {
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            val unreferenced = file.name !in referencedFileNames
            if (unreferenced && file.lastModified() < modifiedBefore && file.delete()) {
                deleted += 1
            }
        }
        return deleted
    }

    private suspend fun importFrom(uri: Uri, mimeTypeHint: String?): Attachment {
        val declaredSize = declaredSizeOf(uri)
        if (declaredSize != null && declaredSize > limits.maxImportBytes) {
            throw AttachmentImportException.TooLarge(null, declaredSize, limits.maxImportBytes)
        }

        val resolver = context.contentResolver
        val hint = mimeTypeHint ?: resolver.getType(uri)
        val input = openInput(resolver, uri)
        return input.use { stream -> importStream(stream, hint) }
    }

    /** The content itself was refused: importing it again fails the same way. */
    private fun refusesContent(error: AttachmentImportException): Boolean = when (error) {
        is AttachmentImportException.Unsupported,
        is AttachmentImportException.TooLarge,
        is AttachmentImportException.TooLong,
        -> true
        is AttachmentImportException.Unreadable,
        is AttachmentImportException.StorageFailed,
        -> false
    }

    private fun declaredSizeOf(uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return null
            return File(path).length()
        }

        val projection = arrayOf(OpenableColumns.SIZE)
        val cursor = try {
            context.contentResolver.query(uri, projection, null, null, null)
        } catch (error: RuntimeException) {
            // The provider cannot say; the copy enforces the limit anyway
            null
        }
        return cursor?.use { rows ->
            val hasSize = rows.moveToFirst() && !rows.isNull(0)
            if (hasSize) rows.getLong(0) else null
        }
    }

    private fun openInput(resolver: ContentResolver, uri: Uri): InputStream {
        val input = try {
            resolver.openInputStream(uri)
        } catch (error: IOException) {
            throw AttachmentImportException.Unreadable(error)
        } catch (error: SecurityException) {
            throw AttachmentImportException.Unreadable(error)
        }
        return input ?: throw AttachmentImportException.Unreadable(IOException("$uri has no content"))
    }

    private suspend fun copyWithLimit(input: InputStream, target: File): Long {
        val limit = limits.maxImportBytes
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L

        val output = try {
            FileOutputStream(target)
        } catch (error: IOException) {
            throw AttachmentImportException.StorageFailed(error)
        }
        output.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()

                val count = try {
                    input.read(buffer)
                } catch (error: IOException) {
                    throw AttachmentImportException.Unreadable(error)
                }
                if (count < 0) {
                    break
                }

                copied += count
                if (copied > limit) {
                    throw AttachmentImportException.TooLarge(null, copied, limit)
                }

                try {
                    stream.write(buffer, 0, count)
                } catch (error: IOException) {
                    throw AttachmentImportException.StorageFailed(error)
                }
            }
        }
        return copied
    }

    private fun checkLimits(facts: MediaFacts, sizeBytes: Long) {
        val maxBytes = when (facts.kind) {
            AttachmentKind.IMAGE -> limits.maxImageBytes
            AttachmentKind.VIDEO -> limits.maxVideoBytes
            AttachmentKind.AUDIO -> limits.maxAudioBytes
        }
        if (sizeBytes > maxBytes) {
            throw AttachmentImportException.TooLarge(facts.kind, sizeBytes, maxBytes)
        }

        val maxDurationMs = when (facts.kind) {
            AttachmentKind.IMAGE -> return
            AttachmentKind.VIDEO -> limits.maxVideoDurationMs
            AttachmentKind.AUDIO -> limits.maxAudioDurationMs
        }
        if (facts.durationMs > maxDurationMs) {
            throw AttachmentImportException.TooLong(facts.kind, facts.durationMs, maxDurationMs)
        }
    }

    private fun deleteIfCapture(uri: Uri) {
        if (uri.authority != captureAuthority) {
            return
        }
        val name = uri.lastPathSegment ?: return
        deleteFileNamed(captureDirectory, name)
    }

    private fun deleteFilesModifiedBefore(directory: File, cutoff: Long) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun toAttachment(id: String, file: File, facts: MediaFacts) = Attachment(
        id = id,
        kind = facts.kind,
        path = file.path,
        mimeType = facts.mimeType,
        width = facts.width,
        height = facts.height,
        durationMs = facts.durationMs,
        frameCount = facts.frameCount,
        fps = facts.fps,
        sampleCount16k = facts.sampleCount16k,
    )

    private companion object {
        const val DIRECTORY_NAME = "attachments"
        const val CAPTURE_DIRECTORY_NAME = "captures"
        const val CAPTURE_AUTHORITY_SUFFIX = ".files"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val STALE_CAPTURE_AGE_MS = 24L * 60 * 60 * 1000

        /** The platform's extension for [mimeType], else a plain subtype ("x-wav" gives "wav"). */
        fun extensionFor(mimeType: String): String {
            val known = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!known.isNullOrEmpty()) {
                return known
            }

            val subtype = mimeType.substringAfter('/')
            val candidate = subtype.lowercase().removePrefix("x-")
            val hasPlainLength = candidate.length in 1..8
            val hasPlainCharacters = candidate.all { character -> character in 'a'..'z' || character in '0'..'9' }
            return if (hasPlainLength && hasPlainCharacters) candidate else "bin"
        }
    }
}
