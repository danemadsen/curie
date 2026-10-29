package com.danemadsen.curie.model

import com.danemadsen.curie.engine.RemoteFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Which downloads were hashed and matched their pinned SHA-256, so a launch does not hash 2.9 GB
 * again. A record (keyed by the hash) holds while the file keeps the size and modification time
 * it had when it was verified. Kept as JSON next to the files and replaced atomically; a lost or
 * damaged record only costs a re-hash.
 */
internal class VerificationCache private constructor(
    private val recordFile: File,
    private val records: MutableMap<String, Record>,
) {
    fun isVerified(file: RemoteFile, target: File): Boolean {
        val record = records[file.sha256] ?: return false
        val recordMatches = record.fileName == file.fileName && record.size == file.size
        return recordMatches && target.length() == file.size && target.lastModified() == record.modifiedAt
    }

    fun markVerified(file: RemoteFile, target: File) {
        records[file.sha256] = Record(file.fileName, file.size, target.lastModified())
        save()
    }

    private fun save() {
        val content = Records(records.toMap())
        val text = json.encodeToString(Records.serializer(), content)
        val temporary = File(recordFile.parentFile, recordFile.name + TEMPORARY_SUFFIX)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray())
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                recordFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            // The files themselves are fine: the next run hashes them again
            temporary.delete()
        }
    }

    @Serializable
    private data class Records(val files: Map<String, Record>)

    @Serializable
    private data class Record(val fileName: String, val size: Long, val modifiedAt: Long)

    companion object {
        const val FILE_NAME = ".verified.json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(directory: File): VerificationCache {
            val recordFile = File(directory, FILE_NAME)
            val records = try {
                val text = recordFile.readText()
                val content = json.decodeFromString(Records.serializer(), text)
                content.files.toMutableMap()
            } catch (error: IOException) {
                mutableMapOf()
            } catch (error: IllegalArgumentException) {
                // A damaged record (SerializationException is an IllegalArgumentException)
                mutableMapOf()
            }
            return VerificationCache(recordFile, records)
        }
    }
}
