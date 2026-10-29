package com.danemadsen.curie.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File

/**
 * Decides by content, so tests choose the facts: a file reading "IMAGE", "VIDEO <ms>" or
 * "AUDIO <ms>" is that media (the number is its duration); anything else is unsupported.
 */
internal class FakeMediaProbe : MediaProbe {
    override fun probe(file: File, mimeTypeHint: String?): MediaFacts? {
        val words = file.readText().split(' ')
        val durationMs = words.getOrNull(1)?.toLongOrNull() ?: 1_000L
        return when (words.first()) {
            "IMAGE" -> MediaFacts(kind = AttachmentKind.IMAGE, mimeType = "image/png", width = 640, height = 480)
            "VIDEO" -> MediaFacts(
                kind = AttachmentKind.VIDEO,
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationMs = durationMs,
                frameCount = (durationMs * 30 / 1000).toInt(),
                fps = 30.0,
                sampleCount16k = samplesAt16k(durationMs * 1000),
            )
            "AUDIO" -> MediaFacts(
                kind = AttachmentKind.AUDIO,
                mimeType = "audio/mp4",
                durationMs = durationMs,
                sampleCount16k = samplesAt16k(durationMs * 1000),
            )
            else -> null
        }
    }
}

/** An in-memory database with a repository and stores over the Robolectric app's files. */
internal class DataTestFixture(limits: AttachmentLimits = AttachmentLimits()) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val database: CurieDatabase = Room.inMemoryDatabaseBuilder(context, CurieDatabase::class.java).build()
    val attachmentStore = AttachmentStore(
        context = context,
        directory = File(context.filesDir, "attachments"),
        limits = limits,
        probe = FakeMediaProbe(),
    )
    val speechDirectory = File(context.filesDir, "speech")

    /** The repository's clock; tests move it to age files. */
    var now: Long = System.currentTimeMillis()

    val repository = ChatRepository(
        database = database,
        attachmentStore = attachmentStore,
        speechDirectory = speechDirectory,
        clock = { now },
    )

    private val sources = File(context.cacheDir, "sources")

    /** A file:// Uri of a new file holding [content]. */
    fun sourceUri(content: String): Uri {
        sources.mkdirs()
        val file = File.createTempFile("source", ".bin", sources)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    suspend fun importAttachment(content: String): Attachment {
        val source = sourceUri(content)
        return attachmentStore.import(source)
    }

    fun close() {
        database.close()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
    }
}
