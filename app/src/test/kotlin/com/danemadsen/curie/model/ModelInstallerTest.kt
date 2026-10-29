package com.danemadsen.curie.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Installs the app's real bundled assets (the two small graphs; the adapters work the same way). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "model")
    private val fileNames = listOf("decoder_q4.lora.onnx", "depth_decoder.lora.onnx")

    /** Unit tests run in the module directory. */
    private val sources = File("src/main/assets/lora")

    private fun installer(stamp: String) = ModelInstaller(context.assets, directory, stamp, fileNames)

    private fun assertInstalled() {
        for (fileName in fileNames) {
            val installed = File(directory, fileName)
            assertFalse(Files.isSymbolicLink(installed.toPath()), "$fileName is a symlink")
            assertContentEquals(File(sources, fileName).readBytes(), installed.readBytes(), fileName)
        }
        val leftovers = directory.listFiles().orEmpty().filter { file -> file.name.endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "left $leftovers")
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
    }

    @Test
    fun copiesTheBundledAssetsAsRealFiles() = runTest {
        val installer = installer("7:1000")
        assertFalse(installer.isInstalled())

        installer.install()

        assertInstalled()
        assertTrue(installer.isInstalled())
        assertEquals("7:1000", File(directory, ModelInstaller.STAMP_FILE_NAME).readText())
    }

    @Test
    fun anUpToDateInstallCopiesNothing() = runTest {
        installer("7:1000").install()
        val installed = File(directory, fileNames.first())
        installed.setLastModified(1_000_000)

        installer("7:1000").install()

        assertEquals(1_000_000, installed.lastModified())
    }

    @Test
    fun anUpdatedAppCopiesAgainEvenWhenTheSizesMatch() = runTest {
        installer("7:1000").install()
        val installed = File(directory, fileNames.first())
        installed.writeBytes(ByteArray(installed.length().toInt()))

        val updated = installer("8:2000")
        assertFalse(updated.isInstalled())
        updated.install()

        assertInstalled()
        assertTrue(updated.isInstalled())
    }

    @Test
    fun aFileOfAnotherSizeIsCopiedAgainUnderTheSameStamp() = runTest {
        installer("7:1000").install()
        val installed = File(directory, fileNames.last())
        installed.writeBytes(ByteArray(10))

        val installer = installer("7:1000")
        assertFalse(installer.isInstalled())
        installer.install()

        assertInstalled()
    }

    @Test
    fun aMissingFileOrASymlinkIsReplacedByARealFile() = runTest {
        installer("7:1000").install()
        File(directory, fileNames.first()).delete()

        val elsewhere = File(context.filesDir, "elsewhere.onnx")
        File(sources, fileNames.last()).copyTo(elsewhere)
        val linked = File(directory, fileNames.last())
        linked.delete()
        Files.createSymbolicLink(linked.toPath(), elsewhere.toPath())

        val installer = installer("7:1000")
        assertFalse(installer.isInstalled())
        installer.install()

        assertInstalled()
        assertTrue(elsewhere.exists())
    }
}
