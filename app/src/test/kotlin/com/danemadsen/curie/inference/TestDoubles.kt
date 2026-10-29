package com.danemadsen.curie.inference

import android.content.Intent
import com.danemadsen.curie.data.Attachment
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.media.RgbImage
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.VideoInput
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One tool, set_timer, that always succeeds; it records its calls. */
class FakeToolExecutor : ToolExecutor {
    val calls = ArrayList<Pair<ToolCall, ToolOrigin>>()

    /** The outcome of the next calls; null gives the default success. */
    var nextOutcome: ToolOutcome? = null

    /** The deferred launches started, in order. */
    val startedLaunches = MutableStateFlow<List<Intent>>(emptyList())

    /** False refuses every deferred launch, as Android does while Curie is in the background. */
    var canStartLaunches = true

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "set_timer",
            description = "Sets a timer.",
            parameters = JsonObject(mapOf("type" to JsonPrimitive("object"))),
        ),
    )

    override suspend fun execute(call: ToolCall, origin: ToolOrigin): ToolOutcome {
        synchronized(calls) {
            calls.add(call to origin)
        }
        return nextOutcome ?: success()
    }

    /** What [webLinkOf] gives for any call; null when nothing could open it. */
    var webLink: Intent? = null

    override fun webLinkOf(call: ToolCall): Intent? = webLink

    override fun startDeferredLaunch(intent: Intent): Boolean {
        if (!canStartLaunches) {
            return false
        }
        startedLaunches.update { launches -> launches + intent }
        return true
    }

    companion object {
        fun success(): ToolOutcome {
            val result = JsonObject(mapOf("ok" to JsonPrimitive(true)))
            return ToolOutcome(result = result, summary = "Timer set", icon = "hourglass", deferredLaunch = null, success = true)
        }
    }
}

/** Media inputs whose loaders give blank media of the attachment's size. */
class FakeMediaInputs : MediaInputFactory {
    override fun image(attachment: Attachment): ImageInput =
        ImageInput(attachment.id, attachment.width, attachment.height) { blank(attachment.width, attachment.height) }

    override fun video(attachment: Attachment): VideoInput = VideoInput(
        key = attachment.id,
        width = attachment.width,
        height = attachment.height,
        frameCount = attachment.frameCount,
        fps = attachment.fps,
        soundtrackSamples = attachment.sampleCount16k,
        loadFrames = { indices -> indices.map { blank(attachment.width, attachment.height) } },
        loadSoundtrack = { FloatArray(attachment.sampleCount16k) },
    )

    override fun audio(attachment: Attachment): AudioInput =
        AudioInput(attachment.id, attachment.sampleCount16k) { FloatArray(attachment.sampleCount16k) }

    private fun blank(width: Int, height: Int): RgbImage = RgbImage(width, height, ByteArray(width * height * 3))
}
