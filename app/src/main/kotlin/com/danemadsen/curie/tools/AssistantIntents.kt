package com.danemadsen.curie.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri

/** How to travel to a destination: the google.navigation mode letter and the Maps URL travel mode. */
internal enum class TravelMode(val argument: String, val navigationMode: String?, val mapsTravelMode: String) {
    DRIVING("driving", "d", "driving"),
    WALKING("walking", "w", "walking"),
    BICYCLING("bicycling", "b", "bicycling"),

    /** Google Maps has no turn-by-turn transit mode, only transit directions. */
    TRANSIT("transit", null, "transit"),
    ;

    companion object {
        val ARGUMENTS = entries.map { mode -> mode.argument }

        fun fromArgument(argument: String): TravelMode {
            return entries.first { mode -> mode.argument == argument }
        }
    }
}

/** A settings screen the user can open by voice; apps cannot switch Wi-Fi or Bluetooth themselves. */
internal enum class SettingsPage(val argument: String) {
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    INTERNET("internet"),
    VOLUME("volume"),
    DISPLAY("display"),
    BATTERY("battery"),
    LOCATION("location"),

    /** Curie's own App info screen, where the user grants its permissions. */
    APP("app"),
    ;

    companion object {
        val ARGUMENTS = entries.map { page -> page.argument }

        fun fromArgument(argument: String): SettingsPage {
            return entries.first { page -> page.argument == argument }
        }
    }
}

/**
 * Intent builders for the assistant actions. Nothing here starts an activity, and every intent is
 * implicit (or pinned to a package with setPackage) so the user's own apps handle it.
 */
internal object AssistantIntents {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /** EXTRA_MEDIA_FOCUS for an unstructured search: "play anything that matches". */
    const val FOCUS_ANY = "vnd.android.cursor.item/*"

    /** The extra SMS apps read the message body from. */
    const val EXTRA_SMS_BODY = "sms_body"

    /**
     * MEDIA_PLAY_FROM_SEARCH. SearchManager.QUERY is always set (for apps that ignore the focus), and
     * the focus tells capable apps whether the user asked for an album, an artist or a song.
     */
    fun playMusic(query: String, artist: String?, album: String?): Intent {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        val isArtistRequest = artist != null && album == null && query.equals(artist, ignoreCase = true)

        val focus = when {
            album != null -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
            isArtistRequest -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            artist != null -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
            else -> FOCUS_ANY
        }
        intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focus)

        val searchText = searchText(query, artist, album)
        intent.putExtra(SearchManager.QUERY, searchText)

        if (artist != null) {
            intent.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
        }
        if (album != null) {
            intent.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, album)
        }
        val isSongRequest = artist != null && album == null && !isArtistRequest
        if (isSongRequest) {
            intent.putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
        }
        return intent
    }

    /** The YouTube app's own search; undocumented, so [youTubeWebSearch] always follows it. */
    fun youTubeAppSearch(query: String): Intent {
        val intent = Intent(Intent.ACTION_SEARCH)
        intent.setPackage(YOUTUBE_PACKAGE)
        intent.putExtra(SearchManager.QUERY, query)
        return intent
    }

    /** Opens the YouTube app when it is verified for the domain, otherwise the browser. */
    fun youTubeWebSearch(query: String): Intent {
        val builder = Uri.Builder()
        builder.scheme("https")
        builder.authority("www.youtube.com")
        builder.path("/results")
        builder.appendQueryParameter("search_query", query)

        val searchUri = builder.build()
        return browsableView(searchUri)
    }

    /** Turn-by-turn navigation in Google Maps; null for [TravelMode.TRANSIT], which it cannot navigate. */
    fun googleNavigation(destination: String, mode: TravelMode): Intent? {
        val navigationMode = mode.navigationMode ?: return null
        val encodedDestination = Uri.encode(destination)
        val navigationUri = "google.navigation:q=$encodedDestination&mode=$navigationMode".toUri()

        val intent = Intent(Intent.ACTION_VIEW, navigationUri)
        intent.setPackage(GOOGLE_MAPS_PACKAGE)
        return intent
    }

    /** A Maps URL with directions: the Google Maps app when installed, otherwise the browser. */
    fun mapsDirections(destination: String, mode: TravelMode): Intent {
        val builder = Uri.Builder()
        builder.scheme("https")
        builder.authority("www.google.com")
        builder.path("/maps/dir/")
        builder.appendQueryParameter("api", "1")
        builder.appendQueryParameter("destination", destination)
        builder.appendQueryParameter("travelmode", mode.mapsTravelMode)
        builder.appendQueryParameter("dir_action", "navigate")

        val directionsUri = builder.build()
        return browsableView(directionsUri)
    }

    /** A place search any maps app understands (Google Maps, OsmAnd, Organic Maps, ...). */
    fun geoSearch(query: String): Intent {
        val encodedQuery = Uri.encode(query)
        val geoUri = "geo:0,0?q=$encodedQuery".toUri()
        return Intent(Intent.ACTION_VIEW, geoUri)
    }

    fun mapsSearch(query: String): Intent {
        val builder = Uri.Builder()
        builder.scheme("https")
        builder.authority("www.google.com")
        builder.path("/maps/search/")
        builder.appendQueryParameter("api", "1")
        builder.appendQueryParameter("query", query)

        val searchUri = builder.build()
        return browsableView(searchUri)
    }

    /** Opens the dialer with the number filled in; the user taps Call. Needs no permission. */
    fun dial(number: String): Intent {
        val telUri = Uri.fromParts("tel", number, null)
        return Intent(Intent.ACTION_DIAL, telUri)
    }

    /** Places the call straight away. Needs CALL_PHONE and never reaches emergency numbers. */
    fun call(number: String): Intent {
        val telUri = Uri.fromParts("tel", number, null)
        return Intent(Intent.ACTION_CALL, telUri)
    }

    /** Opens the messaging app with the recipient and the text filled in; the user taps Send. */
    fun composeSms(number: String, message: String): Intent {
        val smsUri = Uri.fromParts("smsto", number, null)
        val intent = Intent(Intent.ACTION_SENDTO, smsUri)
        intent.putExtra(EXTRA_SMS_BODY, message)
        return intent
    }

    /** Opens an email app with the draft filled in; the user taps Send. */
    fun composeEmail(recipients: List<String>, subject: String?, body: String): Intent {
        val mailtoUri = "mailto:".toUri()
        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri)

        if (recipients.isNotEmpty()) {
            val recipientArray = recipients.toTypedArray()
            intent.putExtra(Intent.EXTRA_EMAIL, recipientArray)
        }
        if (subject != null) {
            intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        intent.putExtra(Intent.EXTRA_TEXT, body)
        return intent
    }

    /**
     * Sets an alarm in the clock app without showing its UI. [days] are java.util.Calendar day
     * constants (Calendar.MONDAY...); empty means a one-time alarm.
     */
    fun setAlarm(hour: Int, minute: Int, label: String?, days: List<Int>): Intent {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        intent.putExtra(AlarmClock.EXTRA_HOUR, hour)
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minute)
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        if (label != null) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        if (days.isNotEmpty()) {
            val dayList = ArrayList(days)
            intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, dayList)
        }
        return intent
    }

    /** Starts a countdown in the clock app without showing its UI. [seconds] is 1 to 86,400. */
    fun setTimer(seconds: Int, label: String?): Intent {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
        intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)

        if (label != null) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return intent
    }

    /** Opens the calendar's event editor filled in; the user taps Save. Needs no calendar permission. */
    fun insertCalendarEvent(title: String, times: EventTimes, location: String?): Intent {
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        intent.putExtra(CalendarContract.Events.TITLE, title)
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, times.beginMillis)
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, times.endMillis)
        intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, times.isAllDay)

        if (location != null) {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        }
        return intent
    }

    /** The search app's web search; a URL query opens that site, plain text searches the web. */
    fun webSearch(query: String): Intent {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
        intent.putExtra(SearchManager.QUERY, query)
        return intent
    }

    fun webSearchPage(query: String): Intent {
        val builder = Uri.Builder()
        builder.scheme("https")
        builder.authority("www.google.com")
        builder.path("/search")
        builder.appendQueryParameter("q", query)

        val searchUri = builder.build()
        return browsableView(searchUri)
    }

    fun browsableView(uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return intent
    }

    /** Starts an app the way the launcher does, resuming its task when it is already running. */
    fun launchApp(component: ComponentName): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.component = component
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return intent
    }

    /**
     * The screens to try for [page], most specific first: a Settings panel (API 29) where one exists,
     * then the full settings page, then the Settings app itself.
     */
    fun settings(page: SettingsPage, packageName: String): List<Intent> {
        val specificIntents = when (page) {
            SettingsPage.WIFI -> listOf(
                Intent(Settings.Panel.ACTION_WIFI),
                Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )

            SettingsPage.BLUETOOTH -> listOf(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )

            SettingsPage.INTERNET -> listOf(
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )

            SettingsPage.VOLUME -> listOf(
                Intent(Settings.Panel.ACTION_VOLUME),
                Intent(Settings.ACTION_SOUND_SETTINGS),
            )

            SettingsPage.DISPLAY -> listOf(
                Intent(Settings.ACTION_DISPLAY_SETTINGS),
            )

            SettingsPage.BATTERY -> listOf(
                Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            )

            SettingsPage.LOCATION -> listOf(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )

            SettingsPage.APP -> listOf(
                appDetails(packageName),
            )
        }

        val settingsHome = Intent(Settings.ACTION_SETTINGS)
        return specificIntents + settingsHome
    }

    private fun appDetails(packageName: String): Intent {
        val packageUri = Uri.fromParts("package", packageName, null)
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }

    /** The search text for apps that ignore the focus extras: the query plus any artist or album not in it. */
    private fun searchText(query: String, artist: String?, album: String?): String {
        val parts = mutableListOf(query)
        val extras = listOfNotNull(album, artist)
        for (extra in extras) {
            val isAlreadyMentioned = parts.any { part -> part.contains(extra, ignoreCase = true) }
            if (!isAlreadyMentioned) {
                parts.add(extra)
            }
        }
        return parts.joinToString(" ")
    }
}
