package com.danemadsen.curie.ui.navigation

import com.danemadsen.curie.inference.EngineState
import com.danemadsen.curie.model.ModelFailure
import com.danemadsen.curie.model.ModelState
import com.danemadsen.curie.ui.common.ModelStatus
import org.junit.Test
import kotlin.test.assertEquals

class ModelStatusMappingTest {
    private fun statusOf(model: ModelState, engine: EngineState = EngineState.Unloaded): ModelStatus =
        modelStatusOf(model, engine) { failure -> "failed: ${failure.reason}" }

    @Test
    fun theDownloadDecidesUntilTheFilesAreComplete() {
        assertEquals(ModelStatus.NotDownloaded, statusOf(ModelState.NotDownloaded, EngineState.Ready))
        assertEquals(ModelStatus.Downloading(0.25f), statusOf(ModelState.Downloading(250, 1_000, "decoder")))
        assertEquals(ModelStatus.Loading, statusOf(ModelState.Verifying))

        val failure = ModelState.Failed(ModelFailure.NETWORK)
        assertEquals(ModelStatus.Failed("failed: NETWORK", filesOnDevice = false), statusOf(failure))
        val install = ModelState.Failed(ModelFailure.INSTALL)
        assertEquals(ModelStatus.Failed("failed: INSTALL", filesOnDevice = true), statusOf(install))
    }

    @Test
    fun withTheFilesReadyTheEngineDecides() {
        assertEquals(ModelStatus.Loading, statusOf(ModelState.Ready, EngineState.Unloaded))
        assertEquals(ModelStatus.Loading, statusOf(ModelState.Ready, EngineState.Loading))
        assertEquals(ModelStatus.Ready, statusOf(ModelState.Ready, EngineState.Ready))
        val failed = statusOf(ModelState.Ready, EngineState.Failed("out of memory"))
        assertEquals(ModelStatus.Failed("out of memory", filesOnDevice = true), failed)
    }
}
