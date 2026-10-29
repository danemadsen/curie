package com.danemadsen.curie.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** When a calendar event takes place, as the epoch milliseconds the calendar's insert intent expects. */
internal data class EventTimes(
    val beginMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
)

/**
 * Reads the ISO-8601 times the model writes for calendar events: "2026-05-01T14:30" in the phone's
 * time zone, with an offset ("...+02:00", "...Z"), or a date alone for an all-day event.
 */
internal object EventTimeParser {
    private const val DEFAULT_DURATION_HOURS = 1L

    private sealed interface ParsedTime {
        data class Day(val date: LocalDate) : ParsedTime

        data class Moment(val dateTime: ZonedDateTime) : ParsedTime
    }

    /** Without [end], a timed event lasts an hour and an all-day event one day. A date [end] is inclusive. */
    fun parse(start: String, end: String?, zone: ZoneId): EventTimes {
        val parsedStart = parseTime("start", start, zone)
        val parsedEnd = if (end == null) null else parseTime("end", end, zone)

        return when (parsedStart) {
            is ParsedTime.Day -> allDayTimes(parsedStart, parsedEnd, zone)
            is ParsedTime.Moment -> timedTimes(parsedStart, parsedEnd)
        }
    }

    private fun allDayTimes(start: ParsedTime.Day, end: ParsedTime?, zone: ZoneId): EventTimes {
        val lastDay = when (end) {
            null -> start.date
            is ParsedTime.Day -> end.date
            is ParsedTime.Moment -> throw InvalidToolArgumentException(
                "\"end\" must be a date without a time when \"start\" is a date.",
            )
        }
        if (lastDay.isBefore(start.date)) {
            throw InvalidToolArgumentException("\"end\" must not be before \"start\".")
        }

        // The calendar shows all-day events on the dates of these local midnights; the end is exclusive.
        val beginMillis = startOfDayMillis(start.date, zone)
        val dayAfterLast = lastDay.plusDays(1)
        val endMillis = startOfDayMillis(dayAfterLast, zone)
        return EventTimes(beginMillis, endMillis, isAllDay = true)
    }

    private fun timedTimes(start: ParsedTime.Moment, end: ParsedTime?): EventTimes {
        val endDateTime = when (end) {
            null -> start.dateTime.plusHours(DEFAULT_DURATION_HOURS)
            is ParsedTime.Moment -> end.dateTime
            is ParsedTime.Day -> throw InvalidToolArgumentException(
                "\"end\" must include a time when \"start\" does.",
            )
        }
        if (!endDateTime.isAfter(start.dateTime)) {
            throw InvalidToolArgumentException("\"end\" must be after \"start\".")
        }

        val beginMillis = start.dateTime.toInstant().toEpochMilli()
        val endMillis = endDateTime.toInstant().toEpochMilli()
        return EventTimes(beginMillis, endMillis, isAllDay = false)
    }

    private fun parseTime(name: String, text: String, zone: ZoneId): ParsedTime {
        val isoText = withTimeSeparator(text.trim())

        val withOffset = tryParse { OffsetDateTime.parse(isoText) }
        if (withOffset != null) {
            val dateTime = withOffset.atZoneSameInstant(zone)
            return ParsedTime.Moment(dateTime)
        }

        val local = tryParse { LocalDateTime.parse(isoText) }
        if (local != null) {
            val dateTime = local.atZone(zone)
            return ParsedTime.Moment(dateTime)
        }

        val date = tryParse { LocalDate.parse(isoText) }
        if (date != null) {
            return ParsedTime.Day(date)
        }

        throw InvalidToolArgumentException(
            "\"$name\" must be an ISO-8601 date-time such as 2026-05-01T14:30, or a date such as 2026-05-01.",
        )
    }

    /** "2026-05-01 14:30" is common enough to accept: ISO-8601 wants a T between date and time. */
    private fun withTimeSeparator(text: String): String {
        val dateLength = "2026-05-01".length
        val hasSpaceSeparator = text.length > dateLength && text[dateLength] == ' '
        if (!hasSpaceSeparator) {
            return text
        }
        val date = text.substring(0, dateLength)
        val time = text.substring(dateLength + 1)
        return date + "T" + time
    }

    private fun <T> tryParse(parse: () -> T): T? {
        return try {
            parse()
        } catch (exception: DateTimeParseException) {
            null
        }
    }

    private fun startOfDayMillis(date: LocalDate, zone: ZoneId): Long {
        val startOfDay = date.atStartOfDay(zone)
        return startOfDay.toInstant().toEpochMilli()
    }
}
