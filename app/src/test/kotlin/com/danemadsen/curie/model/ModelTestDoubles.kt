package com.danemadsen.curie.model

import com.danemadsen.curie.engine.RemoteFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** A pinned download of [content], as ModelFiles lists the real ones. */
internal fun remoteFileOf(path: String, content: ByteArray): RemoteFile {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(content)
    val hex = hash.joinToString("") { byte -> "%02x".format(byte) }
    return RemoteFile(path, content.size.toLong(), hex)
}

/** Reports [bytes] as allocatable (tests change it as the disk fills) and records what is allocated. */
internal class FakeStorage(bytes: Long, private val canAllocate: Boolean = true) : StorageSpace {
    @Volatile
    var bytes: Long = bytes

    val allocations = CopyOnWriteArrayList<Long>()

    override fun allocatableBytes(directory: File): Long = bytes

    override fun allocate(directory: File, bytes: Long) {
        if (!canAllocate) {
            throw IOException("cannot clear $bytes bytes")
        }
        allocations.add(bytes)
    }
}

/**
 * Appends to [file] like a disk with room for [capacity] more bytes: the write that does not fit
 * stores what fits, calls [onFull] and fails.
 */
internal class FullDiskOutputStream(
    file: File,
    private val capacity: Long,
    private val onFull: () -> Unit,
) : OutputStream() {
    private val output = FileOutputStream(file, true)
    private var written = 0L

    override fun write(byte: Int) {
        val single = byteArrayOf(byte.toByte())
        write(single, 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val room = (capacity - written).coerceIn(0L, length.toLong()).toInt()
        output.write(buffer, offset, room)
        written += room
        if (room < length) {
            onFull()
            throw IOException("No space left on device")
        }
    }

    override fun close() {
        output.close()
    }
}
