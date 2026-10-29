package com.danemadsen.curie.model

import android.content.res.AssetManager
import com.danemadsen.curie.engine.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies the LoRA assets the APK ships (assets/lora: the patched decoder graphs and their
 * adapters) into the model directory, next to the downloaded weights they reference. ONNX Runtime
 * resolves symlinks and rejects external data outside a graph's real folder, so these must be real
 * files there.
 *
 * A file is copied when it is missing, its size differs from the asset's, or the app was installed
 * or updated since the last copy ([installStamp] differs from the one recorded): an updated LoRA
 * can have exactly the old size. Each copy goes to a temporary file that is synced and renamed.
 */
class ModelInstaller(
    private val assets: AssetManager,
    private val directory: File,
    private val installStamp: String,
    private val fileNames: List<String> = ModelFiles.BUNDLED,
) {
    private val stampFile = File(directory, STAMP_FILE_NAME)

    /** Copies what is out of date; quick when nothing is. Throws IOException. */
    suspend fun install() = withContext(Dispatchers.IO) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("cannot create $directory")
        }

        val stampChanged = readStamp() != installStamp
        for (fileName in fileNames) {
            val target = File(directory, fileName)
            if (stampChanged || !isInPlace(fileName, target)) {
                copyAsset(fileName, target)
            }
        }

        if (stampChanged) {
            writeStamp()
        }
    }

    /** Every asset is in place for this install of the app; reads no asset content. */
    fun isInstalled(): Boolean {
        if (readStamp() != installStamp) {
            return false
        }
        return fileNames.all { fileName -> isInPlace(fileName, File(directory, fileName)) }
    }

    private fun isInPlace(fileName: String, target: File): Boolean {
        val path = target.toPath()
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            return false
        }
        val assetSize = assetSizeOf(fileName) ?: return true
        return target.length() == assetSize
    }

    /** The asset's size, or null when it is stored compressed (the stamp still catches updates). */
    private fun assetSizeOf(fileName: String): Long? {
        return try {
            assets.openFd(assetPathOf(fileName)).use { descriptor -> descriptor.length }
        } catch (error: FileNotFoundException) {
            null
        }
    }

    private suspend fun copyAsset(fileName: String, target: File) {
        val temporary = File(directory, fileName + TEMPORARY_SUFFIX)
        try {
            val buffer = ByteArray(BUFFER_BYTES)
            assets.open(assetPathOf(fileName)).use { input ->
                FileOutputStream(temporary).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            // Replaces a stale file or a symlink itself, never a symlink's target
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun readStamp(): String? {
        return try {
            stampFile.readText()
        } catch (error: IOException) {
            null
        }
    }

    private fun writeStamp() {
        val temporary = File(directory, STAMP_FILE_NAME + TEMPORARY_SUFFIX)
        FileOutputStream(temporary).use { output ->
            output.write(installStamp.toByteArray())
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            stampFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun assetPathOf(fileName: String): String = "$ASSET_DIRECTORY/$fileName"

    companion object {
        const val ASSET_DIRECTORY = "lora"
        const val STAMP_FILE_NAME = ".bundled-install"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val BUFFER_BYTES = 256 * 1024
    }
}
