package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.EngineOptions
import com.danemadsen.curie.engine.ModelFiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The model of the integration tests: CURIE_MODEL_DIR (system property curie.modelDir) holds the
 * Hugging Face layout (tokenizer.json, and the graphs with their weights in onnx/), curie.loraDir
 * the bundled LoRA assets. The engine needs them flat in one folder, as the app lays them out: the fixture builds
 * that folder once per test JVM in the build's temporary directory, with hard links to the export's
 * files (ONNX Runtime rejects symlinked external data) and copies of the LoRA assets.
 *
 * Without CURIE_MODEL_DIR the tests that need the model are skipped.
 */
internal object ModelFixture {
    private val modelDir: File? by lazy {
        val path = System.getProperty("curie.modelDir") ?: return@lazy null
        File(path).takeIf { directory -> directory.isDirectory }
    }

    private val folder: File by lazy { buildFolder() }

    /** The flat model folder; skips the calling test when CURIE_MODEL_DIR is not set. */
    fun requireFolder(): File {
        assumeTrue(modelDir != null, "set CURIE_MODEL_DIR to the Hugging Face export to run the model tests")
        return folder
    }

    /** Loads the engine of [requireFolder], timing it. */
    fun loadEngine(useLora: Boolean): OnnxEngine {
        val directory = requireFolder()
        val start = System.nanoTime()
        val engine = OnnxOmniEngine.load(directory, EngineOptions(useLora = useLora))
        val millis = (System.nanoTime() - start) / 1_000_000
        println("Loaded the engine (useLora = $useLora) in $millis ms")
        return engine as OnnxEngine
    }

    private fun buildFolder(): File {
        val source = checkNotNull(modelDir)
        val loraPath = checkNotNull(System.getProperty("curie.loraDir")) { "curie.loraDir is not set" }
        val loraDir = File(loraPath)

        // The test task's java.io.tmpdir is in the build folder
        val folder = Files.createTempDirectory("curie-model-").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { folder.deleteRecursively() })

        for (download in ModelFiles.DOWNLOADS) {
            val existing = File(source, download.path)
            check(existing.isFile) { "${existing.path} is missing" }
            val link = File(folder, download.fileName)
            try {
                Files.createLink(link.toPath(), existing.toPath())
            } catch (error: IOException) {
                throw IllegalStateException(
                    "cannot hard-link ${existing.path} into ${folder.path}: the model must be on the build's volume",
                    error,
                )
            }
        }
        for (name in ModelFiles.BUNDLED) {
            val asset = File(loraDir, name)
            check(asset.isFile) { "${asset.path} is missing" }
            val copy = File(folder, name)
            Files.copy(asset.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return folder
    }
}
