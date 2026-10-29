package com.danemadsen.curie.ui.navigation

import com.danemadsen.curie.inference.EngineState
import com.danemadsen.curie.model.ModelFailure
import com.danemadsen.curie.model.ModelState
import com.danemadsen.curie.ui.common.ModelStatus

/**
 * The model's status for the screens: the download decides until the files are complete, then the
 * engine does. An unloaded engine shows as loading: the screens that need it load it.
 */
internal fun modelStatusOf(
    model: ModelState,
    engine: EngineState,
    describeFailure: (ModelState.Failed) -> String,
): ModelStatus = when (model) {
    ModelState.NotDownloaded -> ModelStatus.NotDownloaded
    is ModelState.Downloading -> {
        val progress = if (model.totalBytes > 0) model.doneBytes.toFloat() / model.totalBytes else null
        ModelStatus.Downloading(progress)
    }
    ModelState.Verifying -> ModelStatus.Loading
    is ModelState.Failed -> {
        val filesOnDevice = model.reason == ModelFailure.INSTALL
        ModelStatus.Failed(describeFailure(model), filesOnDevice)
    }
    ModelState.Ready -> when (engine) {
        EngineState.Unloaded, EngineState.Loading -> ModelStatus.Loading
        EngineState.Ready -> ModelStatus.Ready
        is EngineState.Failed -> ModelStatus.Failed(engine.message, filesOnDevice = true)
    }
}
