package com.danemadsen.curie.model

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Free space on the volume that holds a directory. */
interface StorageSpace {
    /** Bytes that can be made available there, counting cached data the system may clear. Throws IOException. */
    fun allocatableBytes(directory: File): Long

    /** Has the system clear cached data until [bytes] are free there. Throws IOException when it cannot. */
    fun allocate(directory: File, bytes: Long)
}

/** Plain free space, for the JVM: nothing can be cleared on demand. */
object FileStorageSpace : StorageSpace {
    override fun allocatableBytes(directory: File): Long = directory.usableSpace

    override fun allocate(directory: File, bytes: Long) {
        val usable = directory.usableSpace
        if (usable < bytes) {
            throw IOException("$bytes bytes needed, $usable free")
        }
    }
}

/**
 * Android's view: other apps' caches count as free, because [allocate] has the system delete them
 * (which StatFs and File.usableSpace do not see).
 */
class AndroidStorageSpace(context: Context) : StorageSpace {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    override fun allocatableBytes(directory: File): Long {
        val volume = storageManager.getUuidForPath(directory)
        return storageManager.getAllocatableBytes(volume)
    }

    override fun allocate(directory: File, bytes: Long) {
        val volume = storageManager.getUuidForPath(directory)
        storageManager.allocateBytes(volume, bytes)
    }
}
