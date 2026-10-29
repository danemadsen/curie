package com.danemadsen.curie.tools

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock

/** One action the model can call. */
internal interface AssistantTool {
    val definition: ToolDefinition

    /** The Tabler icon name the UI shows for this tool's outcomes. */
    val icon: String

    /** The UI text for a failed call, such as "Couldn't play music". */
    @get:StringRes
    val failureSummary: Int

    /** Runs the call; invalid arguments throw [InvalidToolArgumentException]. */
    suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome
}

/** A tool that opens something on the web, which the chat can open later from the call's arguments. */
internal interface WebLinkTool {
    /** The intents to try in order; invalid arguments throw [InvalidToolArgumentException]. */
    fun linkCandidates(arguments: ToolArguments): List<Intent>
}

/** The services the tools share. */
internal class ToolServices(
    val context: Context,
    val launcher: ActivityLauncher,
    val contacts: ContactDirectory,
    val apps: InstalledApps,
    val device: DeviceControls,

    /** For content provider and package manager queries. */
    val ioDispatcher: CoroutineDispatcher,

    /** Asked on every use, so a time zone change while Curie runs is picked up. */
    private val clockProvider: () -> Clock,
) {
    fun currentClock(): Clock {
        return clockProvider()
    }

    fun text(@StringRes id: Int, vararg formatArguments: Any): String {
        return context.getString(id, *formatArguments)
    }
}

internal fun toolSuccess(
    icon: String,
    summary: String,
    result: JsonObject,
    deferredLaunch: Intent? = null,
    permissionsNeeded: List<String> = emptyList(),
): ToolOutcome {
    return ToolOutcome(
        result = result,
        summary = summary,
        icon = icon,
        deferredLaunch = deferredLaunch,
        success = true,
        permissionsNeeded = permissionsNeeded,
    )
}

/** A failed call: [error] is what the model relays to the user, [details] what it may need to ask about. */
internal fun toolFailure(
    icon: String,
    summary: String,
    error: String,
    permissionsNeeded: List<String> = emptyList(),
    details: JsonObject = NO_DETAILS,
): ToolOutcome {
    val result = buildJsonObject {
        put("error", error)
        for ((key, value) in details) {
            put(key, value)
        }
    }
    return ToolOutcome(
        result = result,
        summary = summary,
        icon = icon,
        deferredLaunch = null,
        success = false,
        permissionsNeeded = permissionsNeeded,
    )
}

/** What a tool says about one of the launches it asked [ActivityLauncher] for. */
internal class LaunchReport(
    val icon: String,
    val summary: String,
    val failureSummary: String,

    /** What happened, for the model: "playing", "navigating", "dialer_opened"... */
    val status: String,

    /** Facts the model can mention, such as the app or the contact. */
    val details: JsonObject,

    /** Why nothing happened when no app handles the request. */
    val noHandlerError: String,
)

/**
 * Texts the model reads in tool results. Like the system prompts they stay English whatever the
 * phone's language; the UI texts (summaries) are string resources.
 */
internal object ToolMessages {
    const val OPENS_AFTER_ANSWER = "It opens when you have finished answering."
    const val NOT_VISIBLE =
        "Curie is in the background, where Android does not let it open apps. Ask the user to open Curie and try again."
}

/** Turns the result of a launch into the outcome the model and the UI see. */
internal fun launchOutcome(launch: LaunchResult, report: LaunchReport): ToolOutcome {
    return when (launch) {
        is LaunchResult.Started -> {
            val result = statusResult(report, note = null)
            toolSuccess(report.icon, report.summary, result)
        }

        is LaunchResult.Deferred -> {
            val result = statusResult(report, ToolMessages.OPENS_AFTER_ANSWER)
            toolSuccess(report.icon, report.summary, result, deferredLaunch = launch.intent)
        }

        LaunchResult.NoHandler -> {
            toolFailure(report.icon, report.failureSummary, report.noHandlerError)
        }

        LaunchResult.NotVisible -> {
            toolFailure(report.icon, report.failureSummary, ToolMessages.NOT_VISIBLE)
        }
    }
}

/** Leaves optional facts out of results instead of writing null for the model to read. */
internal fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (value != null) {
        put(key, value)
    }
}

/** The status, then the details; a tool's own note and the launch's [note] are kept both. */
private fun statusResult(report: LaunchReport, note: String?): JsonObject {
    val detailNote = report.details[NOTE_KEY]?.jsonPrimitive?.contentOrNull
    val notes = listOfNotNull(detailNote, note)

    return buildJsonObject {
        put("status", report.status)
        for ((key, value) in report.details) {
            if (key != NOTE_KEY) {
                put(key, value)
            }
        }
        if (notes.isNotEmpty()) {
            put(NOTE_KEY, notes.joinToString(" "))
        }
    }
}

private const val NOTE_KEY = "note"

private val NO_DETAILS = JsonObject(emptyMap())
