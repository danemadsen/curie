package com.danemadsen.curie.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhoneNumbersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun dialable(text: String): String? {
        return PhoneNumbers.dialableOrNull(context, text)
    }

    @Test
    fun formattedNumbersBecomeDialable() {
        assertEquals("+15551234567", dialable("+1 (555) 123-4567"))
        assertEquals("0412345678", dialable("0412 345 678"))
        assertEquals("*31#5551234", dialable("*31# 555 1234"))
        assertEquals("112", dialable("112"))
    }

    @Test
    fun namesAndFragmentsAreNotNumbers() {
        assertNull(dialable("Mom"))
        assertNull(dialable("call 555 1234"))
        assertNull(dialable("12"))
        assertNull(dialable("1+2"))
        assertNull(dialable(""))
    }

    @Test
    fun shortEmergencyNumbersAreNumbers() {
        // France: 15 medical, 17 police, 18 fire.
        assertEquals("15", dialable("15"))
        assertEquals("17", dialable(" 17 "))
        assertEquals("18", dialable("18"))
    }

    @Test
    fun onlyPlainDigitsMayBeCalledWithoutTheDialer() {
        assertTrue(PhoneNumbers.isPlainNumber("+15551234567"))
        assertTrue(PhoneNumbers.isPlainNumber("0412345678"))
        assertFalse(PhoneNumbers.isPlainNumber("**21*5551234#"))
        assertFalse(PhoneNumbers.isPlainNumber("*123#"))
        assertFalse(PhoneNumbers.isPlainNumber("#31#5551234"))
        assertFalse(PhoneNumbers.isPlainNumber("5551234,,12"))
        assertFalse(PhoneNumbers.isPlainNumber(""))
    }

    @Test
    fun knowsTheCommonEmergencyNumbers() {
        assertTrue(PhoneNumbers.isKnownEmergencyNumber("911"))
        assertTrue(PhoneNumbers.isKnownEmergencyNumber("112"))
        assertTrue(PhoneNumbers.isKnownEmergencyNumber("999"))
        assertTrue(PhoneNumbers.isKnownEmergencyNumber("000"))
        assertTrue(PhoneNumbers.isKnownEmergencyNumber("+112"))
        assertFalse(PhoneNumbers.isKnownEmergencyNumber("+15551234567"))
    }
}

class EventTimeParserTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun millis(dateTime: LocalDateTime): Long {
        val zoned = ZonedDateTime.of(dateTime, zone)
        return zoned.toInstant().toEpochMilli()
    }

    private fun midnight(date: LocalDate): Long {
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    @Test
    fun aLocalStartWithoutEndLastsAnHour() {
        val times = EventTimeParser.parse("2026-05-01T14:30", null, zone)

        val start = LocalDateTime.of(2026, 5, 1, 14, 30)
        assertEquals(EventTimes(millis(start), millis(start.plusHours(1)), isAllDay = false), times)
    }

    @Test
    fun acceptsSecondsASpaceSeparatorAndOffsets() {
        val withSeconds = EventTimeParser.parse("2026-05-01T14:30:00", "2026-05-01 16:00", zone)
        val withOffset = EventTimeParser.parse("2026-05-01T12:30Z", null, zone)

        val start = LocalDateTime.of(2026, 5, 1, 14, 30)
        assertEquals(millis(start), withSeconds.beginMillis)
        assertEquals(millis(LocalDateTime.of(2026, 5, 1, 16, 0)), withSeconds.endMillis)
        // 12:30 UTC is 14:30 in Berlin's summer time.
        assertEquals(millis(start), withOffset.beginMillis)
    }

    @Test
    fun aDateAloneIsAnAllDayEventWithAnInclusiveEnd() {
        val oneDay = EventTimeParser.parse("2026-05-01", null, zone)
        val threeDays = EventTimeParser.parse("2026-05-01", "2026-05-03", zone)

        val first = LocalDate.of(2026, 5, 1)
        assertEquals(EventTimes(midnight(first), midnight(first.plusDays(1)), isAllDay = true), oneDay)
        assertEquals(EventTimes(midnight(first), midnight(first.plusDays(3)), isAllDay = true), threeDays)
    }

    @Test
    fun rejectsEndsBeforeStartsAndMixedForms() {
        assertThrows(InvalidToolArgumentException::class.java) {
            EventTimeParser.parse("2026-05-01T14:30", "2026-05-01T14:00", zone)
        }
        assertThrows(InvalidToolArgumentException::class.java) {
            EventTimeParser.parse("2026-05-02", "2026-05-01", zone)
        }
        assertThrows(InvalidToolArgumentException::class.java) {
            EventTimeParser.parse("2026-05-01", "2026-05-01T10:00", zone)
        }
        assertThrows(InvalidToolArgumentException::class.java) {
            EventTimeParser.parse("2026-05-01T10:00", "2026-05-02", zone)
        }
    }

    @Test
    fun rejectsTextThatIsNotIso8601() {
        val exception = assertThrows(InvalidToolArgumentException::class.java) {
            EventTimeParser.parse("tomorrow at 3", null, zone)
        }

        assertEquals(
            "\"start\" must be an ISO-8601 date-time such as 2026-05-01T14:30, or a date such as 2026-05-01.",
            exception.message,
        )
    }
}

class WebAddressesTest {
    @Test
    fun webAddressesGetHttpsWhenTheyHaveNoScheme() {
        assertEquals(WebAddress("https://example.com/page?q=1", "example.com"), WebAddresses.parse(" example.com/page?q=1 "))
        assertEquals(WebAddress("http://example.org", "example.org"), WebAddresses.parse("http://example.org"))
        assertEquals("example.com", WebAddresses.parse("example.com:8080/admin")?.host)
    }

    /**
     * Android matches intent schemes case-sensitively, so the scheme is lowered; the rest stays as
     * written.
     */
    @Test
    fun schemesAreWrittenInLowerCase() {
        val upperCase = WebAddresses.parse("HTTPS://www.example.com")
        val titleCase = WebAddresses.parse("Https://Example.com/Page")
        val mixedCase = WebAddresses.parse("hTTp://example.org/A?b=C")

        assertEquals(WebAddress("https://www.example.com", "www.example.com"), upperCase)
        assertEquals(WebAddress("https://Example.com/Page", "Example.com"), titleCase)
        assertEquals(WebAddress("http://example.org/A?b=C", "example.org"), mixedCase)
    }

    @Test
    fun aWebAddressInTheQueryDoesNotCountAsTheScheme() {
        val expected = WebAddress("https://example.com/login?next=https://example.com/home", "example.com")

        assertEquals(expected, WebAddresses.parse("example.com/login?next=https://example.com/home"))
    }

    @Test
    fun onlyWebSchemesAreAccepted() {
        assertNull(WebAddresses.parse("javascript:alert(1)"))
        assertNull(WebAddresses.parse("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(WebAddresses.parse("file:///sdcard/secret.txt"))
        assertNull(WebAddresses.parse("content://com.example/data"))
        assertNull(WebAddresses.parse("mailto:a@example.com"))
        assertNull(WebAddresses.parse("ftp://example.com"))
        assertNull(WebAddresses.parse("JavaScript:alert(1)"))
        assertNull(WebAddresses.parse("https://bank.example.com@evil.example"))
    }

    @Test
    fun malformedAddressesAreRejected() {
        assertNull(WebAddresses.parse("localhost"))
        assertNull(WebAddresses.parse("not a url"))
        assertNull(WebAddresses.parse(""))
    }

    @Test
    fun emailAddressesAreToldApartFromNames() {
        assertTrue(EmailAddresses.isAddress("ada@example.com"))
        assertTrue(EmailAddresses.isAddress(" a.b+c@mail.example.co.uk "))
        assertFalse(EmailAddresses.isAddress("Ada Lovelace"))
        assertFalse(EmailAddresses.isAddress("ada@example"))
        assertFalse(EmailAddresses.isAddress("ada @example.com"))
    }
}

class WeekDaysTest {
    @Test
    fun namesAbbreviationsAndPluralsBecomeCalendarDays() {
        val days = WeekDays.parse(listOf("Friday", "mon", "wednesdays"))

        assertEquals(listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY), days)
    }

    @Test
    fun groupsExpandToTheirDays() {
        val weekdays = WeekDays.parse(listOf("weekdays"))
        val weekend = WeekDays.parse(listOf("weekend"))
        val everyDay = WeekDays.parse(listOf("every day"))

        assertEquals(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY), weekdays)
        assertEquals(listOf(Calendar.SATURDAY, Calendar.SUNDAY), weekend)
        assertEquals(7, everyDay.size)
    }

    @Test
    fun fillerWordsAreIgnoredAndDuplicatesMerged() {
        val days = WeekDays.parse(listOf("on monday and friday", "Monday"))

        assertEquals(listOf(Calendar.MONDAY, Calendar.FRIDAY), days)
        assertTrue(WeekDays.parse(emptyList()).isEmpty())
    }

    @Test
    fun unknownWordsAreRejected() {
        assertThrows(InvalidToolArgumentException::class.java) {
            WeekDays.parse(listOf("someday"))
        }
    }

    @Test
    fun calendarDaysTurnBackIntoNames() {
        val names = WeekDays.names(listOf(Calendar.MONDAY, Calendar.SUNDAY))

        assertEquals(listOf("monday", "sunday"), names)
    }
}
