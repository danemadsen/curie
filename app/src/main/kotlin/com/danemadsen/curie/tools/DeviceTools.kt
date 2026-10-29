package com.danemadsen.curie.tools

import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** open_app: an installed app found by its launcher label, fuzzily ("you tube", "the maps app"). */
internal class OpenAppTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "apps"
    override val failureSummary = R.string.tool_failure_open_app

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val name = arguments.requiredString("name")
        val failureText = services.text(failureSummary)

        val apps = withContext(services.ioDispatcher) { services.apps.launchableApps() }
        val app = AppMatcher.bestMatch(name, apps)
        if (app == null) {
            val suggestions = AppMatcher.suggestions(name, apps, limit = MAX_SUGGESTIONS)
            val suggestionPrimitives = suggestions.map { label -> JsonPrimitive(label) }
            val suggestionArray = JsonArray(suggestionPrimitives)
            val details = buildJsonObject {
                if (suggestions.isNotEmpty()) {
                    put("similar_apps", suggestionArray)
                }
            }
            return toolFailure(
                icon = icon,
                summary = failureText,
                error = "No app called \"$name\" is installed.",
                details = details,
            )
        }

        val launchIntent = AssistantIntents.launchApp(app.component)
        val candidates = listOf(launchIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("app", app.label)
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_open_app, app.label),
            failureSummary = failureText,
            status = "opened",
            details = details,
            noHandlerError = "${app.label} could not be opened.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        private const val MAX_SUGGESTIONS = 3

        val DEFINITION = ToolDefinition(
            name = "open_app",
            description = "Open an app.",
            parameters = toolParameters {
                string("name", required = true)
            },
        )
    }
}

/** set_flashlight: the camera torch, switched directly (no activity, no permission). */
internal class SetFlashlightTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "bulb"
    override val failureSummary = R.string.tool_failure_flashlight

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val on = arguments.requiredBoolean("on")
        val failureText = services.text(failureSummary)

        val torchResult = services.device.setTorch(on)
        return when (torchResult) {
            TorchResult.SWITCHED -> {
                val status = if (on) "flashlight_on" else "flashlight_off"
                val result = buildJsonObject {
                    put("status", status)
                }
                val summaryId = if (on) R.string.tool_summary_flashlight_on else R.string.tool_summary_flashlight_off
                val summary = services.text(summaryId)
                toolSuccess(icon, summary, result)
            }

            TorchResult.NO_FLASH -> toolFailure(
                icon = icon,
                summary = failureText,
                error = "This phone has no flashlight.",
            )

            TorchResult.UNAVAILABLE -> toolFailure(
                icon = icon,
                summary = failureText,
                error = "The camera is in use, so the flashlight cannot be switched right now.",
            )
        }
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "set_flashlight",
            description = "Turn the flashlight on or off.",
            parameters = toolParameters {
                boolean("on", required = true)
            },
        )
    }
}

/** open_settings: a Settings panel or page. Apps cannot switch Wi-Fi or Bluetooth on or off themselves. */
internal class OpenSettingsTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "adjustments"
    override val failureSummary = R.string.tool_failure_settings

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val pageArgument = arguments.requiredChoice("page", SettingsPage.ARGUMENTS)
        val page = SettingsPage.fromArgument(pageArgument)

        val packageName = services.context.packageName
        val candidates = AssistantIntents.settings(page, packageName)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val isToggle = page == SettingsPage.WIFI || page == SettingsPage.BLUETOOTH || page == SettingsPage.INTERNET
        val details = buildJsonObject {
            put("page", page.argument)
            if (isToggle) {
                put("note", "Apps cannot switch this on or off; the user can do it on the screen that opened.")
            }
        }
        val summaryId = summaryFor(page)
        val report = LaunchReport(
            icon = icon,
            summary = services.text(summaryId),
            failureSummary = services.text(failureSummary),
            status = "settings_opened",
            details = details,
            noHandlerError = "The settings screen could not be opened.",
        )
        return launchOutcome(launch, report)
    }

    @StringRes
    private fun summaryFor(page: SettingsPage): Int {
        return when (page) {
            SettingsPage.WIFI -> R.string.tool_summary_settings_wifi
            SettingsPage.BLUETOOTH -> R.string.tool_summary_settings_bluetooth
            SettingsPage.INTERNET -> R.string.tool_summary_settings_internet
            SettingsPage.VOLUME -> R.string.tool_summary_settings_volume
            SettingsPage.DISPLAY -> R.string.tool_summary_settings_display
            SettingsPage.BATTERY -> R.string.tool_summary_settings_battery
            SettingsPage.LOCATION -> R.string.tool_summary_settings_location
            SettingsPage.APP -> R.string.tool_summary_settings_app
        }
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "open_settings",
            description = "Open a settings screen.",
            parameters = toolParameters {
                choice("page", SettingsPage.ARGUMENTS, required = true)
            },
        )
    }
}

/** get_device_status: the local time, date, time zone and battery, read directly. */
internal class GetDeviceStatusTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "device-mobile"
    override val failureSummary = R.string.tool_failure_device_status

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val clock = services.currentClock()
        val now = ZonedDateTime.now(clock)

        // The model speaks English, so the texts are English in the user's 12 or 24 hour style.
        val timePattern = if (DateFormat.is24HourFormat(services.context)) "HH:mm" else "h:mm a"
        val timeFormat = DateTimeFormatter.ofPattern(timePattern, Locale.ENGLISH)
        val localTime = timeFormat.format(now)
        val date = DATE_FORMAT.format(now)
        val utcOffset = now.offset.id

        val batteryPercent = services.device.batteryPercent()
        val isCharging = services.device.isCharging()

        val result = buildJsonObject {
            put("local_time", localTime)
            put("date", date)
            put("timezone", clock.zone.id)
            put("utc_offset", utcOffset)
            if (batteryPercent != null) {
                put("battery_percent", batteryPercent)
            }
            put("charging", isCharging)
        }
        val summary = services.text(R.string.tool_summary_device_status)
        return toolSuccess(icon, summary, result)
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

        val DEFINITION = ToolDefinition(
            name = "get_device_status",
            description = "Get the time, date and battery level.",
            parameters = toolParameters {},
        )
    }
}
