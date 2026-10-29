package com.danemadsen.curie.tools

import android.content.Context
import android.text.format.DateFormat
import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** set_alarm: the clock app's alarm, set without showing its UI (so it also runs during a call). */
internal class SetAlarmTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "alarm"
    override val failureSummary = R.string.tool_failure_alarm

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val hour = arguments.requiredInt("hour", 0..23)
        val minute = arguments.requiredInt("minute", 0..59)
        val label = arguments.optionalString("label")
        val dayNames = arguments.optionalStringList("days")
        val days = WeekDays.parse(dayNames)

        val alarmIntent = AssistantIntents.setAlarm(hour, minute, label, days)
        val candidates = listOf(alarmIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.IN_PLACE)

        val time = LocalTime.of(hour, minute)
        val isoTime = ISO_TIME.format(time)
        val repeatDays = WeekDays.names(days)
        val repeatDayPrimitives = repeatDays.map { day -> JsonPrimitive(day) }
        val repeatArray = JsonArray(repeatDayPrimitives)
        val details = buildJsonObject {
            put("time", isoTime)
            put("repeats", repeatArray)
            putIfPresent("label", label)
        }

        val spokenTime = clockTime(services.context, time)
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_alarm, spokenTime),
            failureSummary = services.text(failureSummary),
            status = "alarm_set",
            details = details,
            noHandlerError = "No clock app on this phone can set alarms.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        private val ISO_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        val DEFINITION = ToolDefinition(
            name = "set_alarm",
            description = "Set an alarm.",
            parameters = toolParameters {
                integer("hour", "0-23", required = true)
                integer("minute", required = true)
                string("label")
                stringList("days", "Weekdays to repeat on")
            },
        )
    }
}

/** set_timer: a countdown in the clock app, set without showing its UI. */
internal class SetTimerTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "hourglass"
    override val failureSummary = R.string.tool_failure_timer

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val seconds = arguments.requiredInt("seconds", 1..MAX_SECONDS)
        val label = arguments.optionalString("label")

        val timerIntent = AssistantIntents.setTimer(seconds, label)
        val candidates = listOf(timerIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.IN_PLACE)

        val details = buildJsonObject {
            put("seconds", seconds)
            putIfPresent("label", label)
        }
        val duration = durationText(services.context, seconds)
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_timer, duration),
            failureSummary = services.text(failureSummary),
            status = "timer_started",
            details = details,
            noHandlerError = "No clock app on this phone can set timers.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        /** AlarmClock.EXTRA_LENGTH accepts 1 to 86,400 seconds (a day). */
        private const val MAX_SECONDS = 86_400

        val DEFINITION = ToolDefinition(
            name = "set_timer",
            description = "Start a timer.",
            parameters = toolParameters {
                integer("seconds", required = true)
                string("label")
            },
        )
    }
}

/** create_calendar_event: the calendar's event editor, filled in; the user taps Save. */
internal class CreateCalendarEventTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "calendar-event"
    override val failureSummary = R.string.tool_failure_calendar

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val title = arguments.requiredString("title")
        val start = arguments.requiredString("start")
        val end = arguments.optionalString("end")
        val location = arguments.optionalString("location")

        val clock = services.currentClock()
        val times = EventTimeParser.parse(start, end, clock.zone)

        val insertIntent = AssistantIntents.insertCalendarEvent(title, times, location)
        val candidates = listOf(insertIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val beginInstant = Instant.ofEpochMilli(times.beginMillis)
        val endInstant = Instant.ofEpochMilli(times.endMillis)
        val beginLocal = beginInstant.atZone(clock.zone).toLocalDateTime()
        val endLocal = endInstant.atZone(clock.zone).toLocalDateTime()

        // All-day events read as dates, with the (exclusive) end turned back into the last day.
        val startText: String
        val endText: String
        if (times.isAllDay) {
            val lastDay = endLocal.toLocalDate().minusDays(1)
            startText = beginLocal.toLocalDate().toString()
            endText = lastDay.toString()
        } else {
            startText = beginLocal.toString()
            endText = endLocal.toString()
        }

        val details = buildJsonObject {
            put("title", title)
            put("start", startText)
            put("end", endText)
            put("all_day", times.isAllDay)
            putIfPresent("location", location)
            put("note", "The event is filled in but not saved; the user taps Save.")
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_calendar, title),
            failureSummary = services.text(failureSummary),
            status = "event_ready_to_save",
            details = details,
            noHandlerError = "No calendar app on this phone can add events.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "create_calendar_event",
            description = "Create a calendar event.",
            parameters = toolParameters {
                string("title", required = true)
                string("start", "e.g. 2026-05-01T14:30", required = true)
                string("end")
                string("location")
            },
        )
    }
}

/** Weekday names in tool arguments, as the java.util.Calendar constants AlarmClock.EXTRA_DAYS takes. */
internal object WeekDays {
    val NAMES = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private val DAYS = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY,
    )

    private val WORKING_DAYS = DAYS.subList(0, 5)
    private val WEEKEND = DAYS.subList(5, 7)

    private val ABBREVIATIONS = mapOf(
        "mon" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY,
        "tues" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY,
        "thur" to Calendar.THURSDAY,
        "thurs" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY,
        "sun" to Calendar.SUNDAY,
    )

    private val FILLER_WORDS = setOf("and", "on", "every", "each")

    /** The distinct days named in [items], in calendar order (Monday first); unknown words are rejected. */
    fun parse(items: List<String>): List<Int> {
        val days = mutableSetOf<Int>()
        for (item in items) {
            val words = NameMatching.words(item)
            val phrase = words.joinToString(" ")
            val isEveryDay = phrase == "every day" || phrase == "everyday" || phrase == "daily"
            if (isEveryDay) {
                days.addAll(DAYS)
                continue
            }

            for (word in words) {
                if (word in FILLER_WORDS) {
                    continue
                }
                val wordDays = daysForWord(word)
                if (wordDays == null) {
                    throw InvalidToolArgumentException("\"days\" must list weekdays such as monday or friday.")
                }
                days.addAll(wordDays)
            }
        }
        return DAYS.filter { day -> day in days }
    }

    /** The lower-case names of [days] (Calendar constants), for the tool result. */
    fun names(days: List<Int>): List<String> {
        return days.map { day ->
            val index = DAYS.indexOf(day)
            NAMES[index]
        }
    }

    private fun daysForWord(word: String): List<Int>? {
        // "mondays" means every Monday.
        val singular = if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word

        val nameIndex = NAMES.indexOf(singular)
        if (nameIndex >= 0) {
            val day = DAYS[nameIndex]
            return listOf(day)
        }

        val abbreviation = ABBREVIATIONS[word] ?: ABBREVIATIONS[singular]
        if (abbreviation != null) {
            return listOf(abbreviation)
        }

        return when (singular) {
            "weekday" -> WORKING_DAYS
            "weekend" -> WEEKEND
            "daily", "everyday" -> DAYS
            else -> null
        }
    }
}

/** "7:30 AM" or "07:30", as the user's clock shows times. */
private fun clockTime(context: Context, time: LocalTime): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    return formatter.format(time)
}

/** "1 hour 30 minutes", "45 seconds". */
private fun durationText(context: Context, totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60

    val resources = context.resources
    val parts = mutableListOf<String>()
    if (hours > 0) {
        val hoursText = resources.getQuantityString(R.plurals.tool_duration_hours, hours, hours)
        parts.add(hoursText)
    }
    if (minutes > 0) {
        val minutesText = resources.getQuantityString(R.plurals.tool_duration_minutes, minutes, minutes)
        parts.add(minutesText)
    }
    if (seconds > 0) {
        val secondsText = resources.getQuantityString(R.plurals.tool_duration_seconds, seconds, seconds)
        parts.add(secondsText)
    }
    return parts.joinToString(" ")
}
