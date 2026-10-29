package com.danemadsen.curie.inference

import android.content.Intent
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.chat.PythonJson
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.tools.AssistantTools
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.serialization.json.JsonArray

/** The phone actions the model can call. */
interface ToolExecutor {
    /** In a fixed order: they are part of every system prompt, and so of its cache. */
    val definitions: List<ToolDefinition>

    suspend fun execute(call: ToolCall, origin: ToolOrigin): ToolOutcome

    /** Starts a [ToolOutcome.deferredLaunch]; false when Android blocked it (Curie was in the background). */
    fun startDeferredLaunch(intent: Intent): Boolean

    /** What a web tool [call] would open, to open it later; null for other tools or when nothing here can. */
    fun webLinkOf(call: ToolCall): Intent?
}

/** [ToolExecutor] over the app's [AssistantTools]. */
class AssistantToolExecutor(private val tools: AssistantTools) : ToolExecutor {
    override val definitions: List<ToolDefinition>
        get() = tools.definitions

    override suspend fun execute(call: ToolCall, origin: ToolOrigin): ToolOutcome = tools.execute(call, origin)

    override fun startDeferredLaunch(intent: Intent): Boolean = tools.startDeferredLaunch(intent)

    override fun webLinkOf(call: ToolCall): Intent? = tools.webLinkOf(call)
}

/** How tool calls and their results are written down. */
object ToolResults {
    /**
     * The content of the one tool message that answers a turn's calls: the JSON array of their
     * results, in call order, as the model card's example sends it (json.dumps of the list).
     */
    fun toolMessageText(outcomes: List<ToolOutcome>): String {
        val results = outcomes.map { outcome -> outcome.result }
        val array = JsonArray(results)
        return PythonJson.dumps(array)
    }

    /** The record of [call] stored with the assistant message, with what running it gave. */
    fun record(call: ToolCall, outcome: ToolOutcome?): ToolCallRecord {
        val resultJson = outcome?.let { ran -> PythonJson.dumps(ran.result) }
        return ToolCallRecord(
            name = call.name,
            argumentsJson = PythonJson.dumps(call.arguments),
            resultJson = resultJson,
            summary = outcome?.summary,
        )
    }
}
