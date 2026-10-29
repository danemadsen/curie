package com.danemadsen.curie.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.ActivityStartPolicy
import com.danemadsen.curie.assistant.VisibleOrAssistantPolicy
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock

/**
 * The phone actions the model can call, like a voice assistant's: media, maps, calls and messages,
 * alarms, the calendar, the web, apps, the flashlight, volume, settings and the device status.
 *
 * Nothing is sent or called behind the user's back: messages, emails and calendar events open
 * prefilled for the user to confirm, and calls are placed only with CALL_PHONE (never to emergency
 * numbers or to service codes such as *21#, which always open the dialer).
 */
class AssistantTools internal constructor(
    context: Context,
    startPolicy: ActivityStartPolicy,
    ioDispatcher: CoroutineDispatcher,
    clockProvider: () -> Clock,
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        startPolicy = VisibleOrAssistantPolicy(context.applicationContext),
        ioDispatcher = Dispatchers.IO,
        clockProvider = { Clock.systemDefaultZone() },
    )

    private val services: ToolServices

    init {
        val launcher = ActivityLauncher(context, startPolicy)
        val contacts = ContactDirectory(context)
        val apps = InstalledApps(context)
        val device = DeviceControls(context)

        services = ToolServices(
            context = context,
            launcher = launcher,
            contacts = contacts,
            apps = apps,
            device = device,
            ioDispatcher = ioDispatcher,
            clockProvider = clockProvider,
        )
    }

    private val tools: Map<String, AssistantTool> = createTools(services)

    /** The tool list for the system prompt, in a fixed order so the prompt cache stays valid. */
    val definitions: List<ToolDefinition> = DEFINITIONS

    /**
     * Runs one tool call. Invalid arguments and failures come back as an "error" result for the
     * model to relay; only cancellation propagates.
     */
    suspend fun execute(call: ToolCall, origin: ToolOrigin): ToolOutcome {
        val tool = tools[call.name]
        if (tool == null) {
            val summary = services.text(R.string.tool_failure_unknown)
            return toolFailure(UNKNOWN_TOOL_ICON, summary, "There is no tool called \"${call.name}\".")
        }

        val arguments = ToolArguments(call.arguments)
        return try {
            tool.execute(arguments, origin)
        } catch (invalid: InvalidToolArgumentException) {
            val summary = services.text(tool.failureSummary)
            val error = invalid.message ?: "The arguments are not valid."
            toolFailure(tool.icon, summary, error)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: RuntimeException) {
            // Platform services differ between devices; one misbehaving must not take Curie down.
            Log.e(TAG, "The ${call.name} tool failed", unexpected)
            val summary = services.text(tool.failureSummary)
            toolFailure(tool.icon, summary, "Something went wrong on the phone while doing this.")
        }
    }

    /**
     * What a web tool [call] would open (the search or the page), to open it later from the chat;
     * null for other tools, invalid arguments, or when no app here can open it.
     */
    fun webLinkOf(call: ToolCall): Intent? {
        val tool = tools[call.name] as? WebLinkTool ?: return null
        val arguments = ToolArguments(call.arguments)
        val candidates = try {
            tool.linkCandidates(arguments)
        } catch (invalid: InvalidToolArgumentException) {
            return null
        }
        return services.launcher.firstResolvable(candidates)
    }

    /**
     * Starts an app a tool call deferred ([ToolOutcome.deferredLaunch]) now that the answer is done;
     * false when Android would block it because Curie is in the background.
     */
    fun startDeferredLaunch(intent: Intent): Boolean {
        return services.launcher.startDeferred(intent)
    }

    companion object {
        private const val TAG = "AssistantTools"
        private const val UNKNOWN_TOOL_ICON = "tool"

        /** Every tool definition, without needing a Context (the order is the prompt order). */
        internal val DEFINITIONS: List<ToolDefinition> = listOf(
            PlayMusicTool.DEFINITION,
            PlayVideoTool.DEFINITION,
            NavigateTool.DEFINITION,
            ShowOnMapTool.DEFINITION,
            CallContactTool.DEFINITION,
            SendTextMessageTool.DEFINITION,
            SendEmailTool.DEFINITION,
            SetAlarmTool.DEFINITION,
            SetTimerTool.DEFINITION,
            CreateCalendarEventTool.DEFINITION,
            WebSearchTool.DEFINITION,
            OpenAppTool.DEFINITION,
            OpenUrlTool.DEFINITION,
            SetFlashlightTool.DEFINITION,
            SetVolumeTool.DEFINITION,
            MediaControlTool.DEFINITION,
            OpenSettingsTool.DEFINITION,
            GetDeviceStatusTool.DEFINITION,
        )

        private fun createTools(services: ToolServices): Map<String, AssistantTool> {
            val tools = listOf(
                PlayMusicTool(services),
                PlayVideoTool(services),
                NavigateTool(services),
                ShowOnMapTool(services),
                CallContactTool(services),
                SendTextMessageTool(services),
                SendEmailTool(services),
                SetAlarmTool(services),
                SetTimerTool(services),
                CreateCalendarEventTool(services),
                WebSearchTool(services),
                OpenAppTool(services),
                OpenUrlTool(services),
                SetFlashlightTool(services),
                SetVolumeTool(services),
                MediaControlTool(services),
                OpenSettingsTool(services),
                GetDeviceStatusTool(services),
            )
            return tools.associateBy { tool -> tool.definition.name }
        }
    }
}
