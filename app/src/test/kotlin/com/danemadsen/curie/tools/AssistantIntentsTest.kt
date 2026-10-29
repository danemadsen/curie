package com.danemadsen.curie.tools

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AssistantIntentsTest {
    @Test
    fun playMusicSearchesAnythingByDefault() {
        val intent = AssistantIntents.playMusic("relaxing jazz", artist = null, album = null)

        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, intent.action)
        assertEquals(AssistantIntents.FOCUS_ANY, intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("relaxing jazz", intent.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun playMusicFocusesOnArtistsAlbumsAndSongs() {
        val artist = AssistantIntents.playMusic("Queen", artist = "Queen", album = null)
        val album = AssistantIntents.playMusic("A Night at the Opera", artist = "Queen", album = "A Night at the Opera")
        val song = AssistantIntents.playMusic("Bohemian Rhapsody", artist = "Queen", album = null)

        assertEquals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE, artist.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Queen", artist.getStringExtra(SearchManager.QUERY))
        assertEquals("Queen", artist.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST))

        assertEquals(MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE, album.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("A Night at the Opera", album.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM))
        assertEquals("A Night at the Opera Queen", album.getStringExtra(SearchManager.QUERY))

        assertEquals(MediaStore.Audio.Media.ENTRY_CONTENT_TYPE, song.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS))
        assertEquals("Bohemian Rhapsody", song.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE))
        assertEquals("Bohemian Rhapsody Queen", song.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun youTubeSearchesUseTheAppThenTheWebsite() {
        val appSearch = AssistantIntents.youTubeAppSearch("cat videos")
        val webSearch = AssistantIntents.youTubeWebSearch("cat videos & dogs")

        assertEquals(Intent.ACTION_SEARCH, appSearch.action)
        assertEquals(AssistantIntents.YOUTUBE_PACKAGE, appSearch.`package`)
        assertEquals("cat videos", appSearch.getStringExtra(SearchManager.QUERY))

        assertEquals(Intent.ACTION_VIEW, webSearch.action)
        assertTrue(webSearch.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("www.youtube.com", webSearch.data?.host)
        assertEquals("cat videos & dogs", webSearch.data?.getQueryParameter("search_query"))
    }

    @Test
    fun navigationUsesGoogleMapsWithTheTravelMode() {
        val walking = AssistantIntents.googleNavigation("Brandenburger Tor, Berlin", TravelMode.WALKING)

        assertEquals(Intent.ACTION_VIEW, walking?.action)
        assertEquals(AssistantIntents.GOOGLE_MAPS_PACKAGE, walking?.`package`)
        assertEquals("google.navigation", walking?.data?.scheme)
        assertEquals("q=Brandenburger%20Tor%2C%20Berlin&mode=w", walking?.data?.encodedSchemeSpecificPart)
        assertNull(AssistantIntents.googleNavigation("Paris", TravelMode.TRANSIT))
    }

    @Test
    fun mapsUrlsCarryTheDestinationAndMode() {
        val directions = AssistantIntents.mapsDirections("Eiffel Tower", TravelMode.TRANSIT)
        val search = AssistantIntents.mapsSearch("pizza near me")
        val geo = AssistantIntents.geoSearch("pizza near me")

        assertEquals("/maps/dir/", directions.data?.path)
        assertEquals("Eiffel Tower", directions.data?.getQueryParameter("destination"))
        assertEquals("transit", directions.data?.getQueryParameter("travelmode"))
        assertEquals("1", directions.data?.getQueryParameter("api"))
        assertEquals("pizza near me", search.data?.getQueryParameter("query"))
        assertEquals("geo:0,0?q=pizza%20near%20me", geo.dataString)
    }

    @Test
    fun dialAndCallUseTelUris() {
        val dial = AssistantIntents.dial("+15551234567")
        val call = AssistantIntents.call("*31#5551234")

        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:%2B15551234567", dial.dataString)
        assertEquals(Intent.ACTION_CALL, call.action)
        assertEquals("tel", call.data?.scheme)
        assertEquals("*31#5551234", call.data?.schemeSpecificPart)
    }

    @Test
    fun messagesAndEmailsAreComposedNotSent() {
        val sms = AssistantIntents.composeSms("+15551234567", "Running late")
        val email = AssistantIntents.composeEmail(listOf("ada@example.com"), "Hello", "See you soon")
        val emailWithoutRecipients = AssistantIntents.composeEmail(emptyList(), null, "Note")

        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto", sms.data?.scheme)
        assertEquals("+15551234567", sms.data?.schemeSpecificPart)
        assertEquals("Running late", sms.getStringExtra(AssistantIntents.EXTRA_SMS_BODY))

        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals("mailto:", email.dataString)
        assertArrayEquals(arrayOf("ada@example.com"), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Hello", email.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("See you soon", email.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(emailWithoutRecipients.hasExtra(Intent.EXTRA_EMAIL))
        assertFalse(emailWithoutRecipients.hasExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun alarmsAndTimersSkipTheClockUi() {
        val days = listOf(Calendar.MONDAY, Calendar.FRIDAY)
        val alarm = AssistantIntents.setAlarm(7, 30, "Gym", days)
        val oneTimeAlarm = AssistantIntents.setAlarm(6, 0, null, emptyList())
        val timer = AssistantIntents.setTimer(300, "Pasta")

        assertEquals(AlarmClock.ACTION_SET_ALARM, alarm.action)
        assertEquals(7, alarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Gym", alarm.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(days, alarm.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
        assertTrue(alarm.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertFalse(oneTimeAlarm.hasExtra(AlarmClock.EXTRA_DAYS))
        assertFalse(oneTimeAlarm.hasExtra(AlarmClock.EXTRA_MESSAGE))

        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(300, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("Pasta", timer.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test
    fun calendarEventsOpenTheEditor() {
        val times = EventTimes(beginMillis = 1_000L, endMillis = 2_000L, isAllDay = false)

        val intent = AssistantIntents.insertCalendarEvent("Dentist", times, "Main St 1")

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Dentist", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L))
        assertEquals(2_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L))
        assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true))
        assertEquals("Main St 1", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
    }

    @Test
    fun webSearchesUseTheSearchAppThenGoogle() {
        val searchApp = AssistantIntents.webSearch("weather berlin")
        val searchPage = AssistantIntents.webSearchPage("weather berlin")

        assertEquals(Intent.ACTION_WEB_SEARCH, searchApp.action)
        assertEquals("weather berlin", searchApp.getStringExtra(SearchManager.QUERY))
        assertEquals("https", searchPage.data?.scheme)
        assertEquals("www.google.com", searchPage.data?.host)
        assertEquals("weather berlin", searchPage.data?.getQueryParameter("q"))
        assertTrue(searchPage.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun appsLaunchLikeFromTheLauncher() {
        val component = ComponentName("com.google.android.youtube", "com.google.android.youtube.HomeActivity")

        val intent = AssistantIntents.launchApp(component)

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(component, intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
    }

    @Test
    fun settingsPagesTryPanelsThenPagesThenSettings() {
        val wifi = AssistantIntents.settings(SettingsPage.WIFI, "com.danemadsen.curie")
        val app = AssistantIntents.settings(SettingsPage.APP, "com.danemadsen.curie")

        val wifiActions = wifi.map { intent -> intent.action }
        val expectedWifi = listOf(
            Settings.Panel.ACTION_WIFI,
            Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        assertEquals(expectedWifi, wifiActions)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.first().action)
        assertEquals("package:com.danemadsen.curie", app.first().dataString)
        assertEquals(Settings.ACTION_SETTINGS, app.last().action)

        for (page in SettingsPage.entries) {
            val candidates = AssistantIntents.settings(page, "com.danemadsen.curie")
            assertEquals(page.name, Settings.ACTION_SETTINGS, candidates.last().action)
        }
    }
}
