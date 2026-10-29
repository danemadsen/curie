package com.danemadsen.curie.tools

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.assistant.ActivityStartPolicy
import com.danemadsen.curie.engine.chat.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowSettings
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AssistantToolsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.parse("2026-10-29T13:05:00Z"), zone)
    private var curieIsVisible = true
    private lateinit var tools: AssistantTools

    @Before
    fun setUp() {
        FakeContactsProvider.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, "com.android.contacts")
        shadowOf(context).checkActivities(true)

        val startPolicy = ActivityStartPolicy { curieIsVisible }
        tools = AssistantTools(context, startPolicy, UnconfinedTestDispatcher(), clockProvider = { clock })
    }

    @After
    fun tearDown() {
        FakeContactsProvider.reset()
    }

    /** Runs a tool call; a chat's deferred app switch then starts, as it does once the answer is done. */
    private suspend fun run(name: String, origin: ToolOrigin = ToolOrigin.CHAT, arguments: JsonObjectBuilder.() -> Unit = {}): ToolOutcome {
        val outcome = execute(name, origin, arguments)
        val deferred = outcome.deferredLaunch
        if (origin == ToolOrigin.CHAT && deferred != null) {
            tools.startDeferredLaunch(deferred)
        }
        return outcome
    }

    /** Runs a tool call and leaves what it deferred undone. */
    private suspend fun execute(name: String, origin: ToolOrigin, arguments: JsonObjectBuilder.() -> Unit = {}): ToolOutcome {
        val json = buildJsonObject(arguments)
        val call = ToolCall(name, json)
        return tools.execute(call, origin)
    }

    /** Installs an activity that handles [action] (with CATEGORY_DEFAULT, like startActivity needs). */
    private fun installHandler(
        packageName: String,
        action: String,
        scheme: String? = null,
        mimeType: String? = null,
        browsable: Boolean = false,
    ): ComponentName {
        val component = ComponentName(packageName, "$packageName.Handler")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)

        val filter = IntentFilter(action)
        filter.addCategory(Intent.CATEGORY_DEFAULT)
        if (browsable) {
            filter.addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (scheme != null) {
            filter.addDataScheme(scheme)
        }
        if (mimeType != null) {
            filter.addDataType(mimeType)
        }
        packageManager.addIntentFilterForActivity(component, filter)
        return component
    }

    private fun installBrowser() {
        installHandler("com.example.browser", Intent.ACTION_VIEW, scheme = "https", browsable = true)
    }

    private fun installLauncherApp(packageName: String, label: String): ComponentName {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val packageManager = shadowOf(context.packageManager)
        val activityInfo = packageManager.addActivityIfNotPresent(component)
        activityInfo.nonLocalizedLabel = label
        activityInfo.applicationInfo.nonLocalizedLabel = label

        val filter = IntentFilter(Intent.ACTION_MAIN)
        filter.addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.addIntentFilterForActivity(component, filter)
        return component
    }

    private fun nextStartedActivity(): Intent? {
        return shadowOf(context).nextStartedActivity
    }

    private fun JsonObject.text(key: String): String? {
        return this[key]?.jsonPrimitive?.content
    }

    // Validation and dispatch

    @Test
    fun invalidArgumentsBecomeAnErrorTheModelCanRelay() = runTest {
        val outcome = run("set_alarm") {
            put("hour", 25)
            put("minute", 0)
        }

        assertFalse(outcome.success)
        assertEquals("\"hour\" must be between 0 and 23.", outcome.result.text("error"))
        assertEquals("Couldn't set the alarm", outcome.summary)
        assertEquals("alarm", outcome.icon)
        assertNull(nextStartedActivity())
    }

    @Test
    fun missingRequiredArgumentsAreReported() = runTest {
        val outcome = run("play_video")

        assertFalse(outcome.success)
        assertEquals("\"query\" is required.", outcome.result.text("error"))
    }

    @Test
    fun unknownToolsAreReported() = runTest {
        val outcome = run("fly_to_the_moon")

        assertFalse(outcome.success)
        assertEquals("There is no tool called \"fly_to_the_moon\".", outcome.result.text("error"))
        assertEquals("tool", outcome.icon)
    }

    @Test
    fun everyDefinitionHasATool() = runTest {
        for (definition in tools.definitions) {
            val outcome = run(definition.name, ToolOrigin.CALL)
            val error = outcome.result.text("error")
            assertFalse(definition.name, error?.startsWith("There is no tool") == true)
        }
    }

    // Launching from a chat

    @Test
    fun aChatOpensTheMusicAppOnceTheAnswerIsDone() = runTest {
        installHandler("com.example.music", MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)

        val outcome = execute("play_music", ToolOrigin.CHAT) { put("query", "Bohemian Rhapsody") }

        // Opening the app now would cover the chat while Curie is still answering
        assertTrue(outcome.success)
        assertEquals("playing", outcome.result.text("status"))
        assertEquals(ToolMessages.OPENS_AFTER_ANSWER, outcome.result.text("note"))
        assertEquals("Playing “Bohemian Rhapsody”", outcome.summary)
        assertNull(nextStartedActivity())

        val deferred = outcome.deferredLaunch
        assertNotNull(deferred)
        assertTrue(tools.startDeferredLaunch(deferred!!))
        val started = nextStartedActivity()
        assertEquals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH, started?.action)
        assertTrue((started?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aDeferredAppDoesNotStartOnceCurieIsInTheBackground() = runTest {
        installBrowser()
        val outcome = execute("open_url", ToolOrigin.CHAT) { put("url", "example.com") }
        val deferred = outcome.deferredLaunch
        assertNotNull(deferred)

        curieIsVisible = false

        assertFalse(tools.startDeferredLaunch(deferred!!))
        assertNull(nextStartedActivity())
    }

    @Test
    fun aWebSearchTellsTheModelItCannotReadTheResults() = runTest {
        installBrowser()

        val outcome = execute("web_search", ToolOrigin.CHAT) { put("query", "weather in Paris") }

        assertTrue(outcome.success)
        val note = outcome.result.text("note") ?: ""
        assertTrue(note, note.contains("you cannot read them"))
        assertTrue(note, note.contains(ToolMessages.OPENS_AFTER_ANSWER))
    }

    @Test
    fun webToolsAreLookupsAndOthersAreNot() = runTest {
        installBrowser()
        installHandler("com.example.music", MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)

        assertTrue(execute("web_search", ToolOrigin.CHAT) { put("query", "pizza") }.isWebLookup)
        assertTrue(execute("open_url", ToolOrigin.CHAT) { put("url", "example.com") }.isWebLookup)
        assertFalse(execute("play_music", ToolOrigin.CHAT) { put("query", "Queen") }.isWebLookup)
    }

    @Test
    fun aStoredWebCallCanBeOpenedLater() {
        installBrowser()
        val search = ToolCall("web_search", buildJsonObject { put("query", "capital of France") })
        val page = ToolCall("open_url", buildJsonObject { put("url", "en.wikipedia.org/wiki/Paris") })

        val searchLink = tools.webLinkOf(search)
        assertNotNull(searchLink)
        assertTrue(searchLink!!.dataString!!.contains("capital"))
        assertTrue(searchLink.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("https://en.wikipedia.org/wiki/Paris", tools.webLinkOf(page)?.dataString)

        // Only web tools have links, and only valid web addresses
        val timer = ToolCall("set_timer", buildJsonObject { put("seconds", 60) })
        assertNull(tools.webLinkOf(timer))
        val script = ToolCall("open_url", buildJsonObject { put("url", "javascript:alert(1)") })
        assertNull(tools.webLinkOf(script))
    }

    @Test
    fun anOpenedPageTellsTheModelItCannotReadIt() = runTest {
        installBrowser()

        val outcome = execute("open_url", ToolOrigin.CHAT) { put("url", "en.wikipedia.org/wiki/Paris") }

        assertTrue(outcome.success)
        val note = outcome.result.text("note") ?: ""
        assertTrue(note, note.contains("you cannot read it"))
    }

    @Test
    fun withoutAMusicAppItSearchesYouTube() = runTest {
        installBrowser()

        val outcome = run("play_music") { put("query", "lofi beats") }

        assertTrue(outcome.success)
        assertEquals("no_music_app_searching_youtube", outcome.result.text("status"))
        assertEquals("Searching YouTube for “lofi beats”", outcome.summary)
        assertEquals("www.youtube.com", nextStartedActivity()?.data?.host)
    }

    @Test
    fun withNoAppAtAllTheModelHearsWhy() = runTest {
        val outcome = run("play_music") { put("query", "anything") }

        assertFalse(outcome.success)
        assertEquals("No app on this phone can play music or open YouTube.", outcome.result.text("error"))
        assertEquals("Couldn't play music", outcome.summary)
    }

    @Test
    fun inTheBackgroundNothingIsStartedThatAndroidWouldDrop() = runTest {
        installBrowser()
        curieIsVisible = false

        val outcome = run("web_search") { put("query", "weather") }

        assertFalse(outcome.success)
        assertEquals(ToolMessages.NOT_VISIBLE, outcome.result.text("error"))
        assertNull(nextStartedActivity())
    }

    // Launching during a call

    @Test
    fun aCallDefersAppSwitchesUntilItEnds() = runTest {
        installBrowser()

        val outcome = run("open_url", ToolOrigin.CALL) { put("url", "example.com/news") }

        assertTrue(outcome.success)
        val note = outcome.result.text("note") ?: ""
        assertTrue(note, note.endsWith(ToolMessages.OPENS_AFTER_ANSWER))
        assertEquals("Opening example.com", outcome.summary)
        val deferred = outcome.deferredLaunch
        assertNotNull(deferred)
        assertEquals("https://example.com/news", deferred?.dataString)
        assertTrue((deferred?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(nextStartedActivity())
    }

    @Test
    fun aDeferredDialKeepsBothNotes() = runTest {
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")

        val outcome = run("call_contact", ToolOrigin.CALL) { put("name_or_number", "5550100123") }

        assertTrue(outcome.success)
        assertEquals(Intent.ACTION_DIAL, outcome.deferredLaunch?.action)
        val note = outcome.result.text("note") ?: ""
        assertTrue(note, note.contains("the user taps Call"))
        assertTrue(note, note.contains(ToolMessages.OPENS_AFTER_ANSWER))
    }

    @Test
    fun aCallDefersTheFirstCandidateAnAppHandles() = runTest {
        installHandler("com.example.maps", Intent.ACTION_VIEW, scheme = "geo")

        val outcome = run("navigate", ToolOrigin.CALL) {
            put("destination", "Alexanderplatz")
            put("mode", "walking")
        }

        // No Google Maps and no browser: only the geo: fallback, which shows the place.
        assertTrue(outcome.success)
        assertEquals("place_shown_no_navigation_app", outcome.result.text("status"))
        assertEquals("geo", outcome.deferredLaunch?.data?.scheme)
    }

    @Test
    fun aCallReportsWhenNothingCouldOpenLater() = runTest {
        val outcome = run("show_on_map", ToolOrigin.CALL) { put("query", "Louvre") }

        assertFalse(outcome.success)
        assertNull(outcome.deferredLaunch)
        assertEquals("No maps app or browser on this phone can show places.", outcome.result.text("error"))
    }

    @Test
    fun timersRunAtOnceEvenDuringACall() = runTest {
        installHandler("com.example.clock", AlarmClock.ACTION_SET_TIMER)

        val outcome = run("set_timer", ToolOrigin.CALL) {
            put("seconds", 5_400)
            put("label", "Bread")
        }

        assertTrue(outcome.success)
        assertNull(outcome.deferredLaunch)
        assertEquals("Timer set for 1 hour 30 minutes", outcome.summary)
        val started = nextStartedActivity()
        assertEquals(AlarmClock.ACTION_SET_TIMER, started?.action)
        assertEquals(5_400, started?.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
    }

    @Test
    fun alarmsRepeatOnTheGivenDays() = runTest {
        installHandler("com.example.clock", AlarmClock.ACTION_SET_ALARM)
        ShadowSettings.set24HourTimeFormat(true)

        val outcome = run("set_alarm") {
            put("hour", 7)
            put("minute", 5)
            putJsonArray("days") {
                add(kotlinx.serialization.json.JsonPrimitive("friday"))
                add(kotlinx.serialization.json.JsonPrimitive("monday"))
            }
        }

        assertTrue(outcome.success)
        assertEquals("Alarm set for 07:05", outcome.summary)
        assertEquals("07:05", outcome.result.text("time"))
        val repeats = outcome.result["repeats"]?.jsonArray?.map { day -> day.jsonPrimitive.content }
        assertEquals(listOf("monday", "friday"), repeats)
    }

    // Calls, messages and email

    @Test
    fun withoutCallPermissionTheDialerOpensForTheUserToConfirm() = runTest {
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")

        val outcome = run("call_contact") { put("name_or_number", "+1 (555) 010-0123") }

        assertTrue(outcome.success)
        assertEquals("dialer_opened", outcome.result.text("status"))
        assertEquals("+15550100123", outcome.result.text("number"))
        assertEquals(listOf(Manifest.permission.CALL_PHONE), outcome.permissionsNeeded)
        assertEquals(Intent.ACTION_DIAL, nextStartedActivity()?.action)
    }

    @Test
    fun withCallPermissionTheCallIsPlaced() = runTest {
        installHandler("com.example.telecom", Intent.ACTION_CALL, scheme = "tel")
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")
        shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)

        val outcome = run("call_contact") { put("name_or_number", "5550100123") }

        assertTrue(outcome.success)
        assertEquals("calling", outcome.result.text("status"))
        assertEquals("Calling 5550100123", outcome.summary)
        assertTrue(outcome.permissionsNeeded.isEmpty())
        assertEquals(Intent.ACTION_CALL, nextStartedActivity()?.action)
    }

    @Test
    fun emergencyNumbersAlwaysOpenTheDialer() = runTest {
        installHandler("com.example.telecom", Intent.ACTION_CALL, scheme = "tel")
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")
        shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)

        val outcome = run("call_contact") { put("name_or_number", "911") }

        assertTrue(outcome.success)
        assertEquals("dialer_opened", outcome.result.text("status"))
        assertTrue(outcome.permissionsNeeded.isEmpty())
        assertEquals(Intent.ACTION_DIAL, nextStartedActivity()?.action)
    }

    @Test
    fun shortEmergencyNumbersOpenTheDialerToo() = runTest {
        installHandler("com.example.telecom", Intent.ACTION_CALL, scheme = "tel")
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")

        for (number in listOf("15", "17", "18")) {
            val withoutPermission = run("call_contact") { put("name_or_number", number) }

            assertTrue(number, withoutPermission.success)
            assertTrue(number, withoutPermission.permissionsNeeded.isEmpty())
            val dialed = nextStartedActivity()
            assertEquals(Intent.ACTION_DIAL, dialed?.action)
            assertEquals(number, dialed?.data?.schemeSpecificPart)
        }

        shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)
        val withPermission = run("call_contact") { put("name_or_number", "17") }

        assertEquals("dialer_opened", withPermission.result.text("status"))
        assertEquals(Intent.ACTION_DIAL, nextStartedActivity()?.action)
    }

    @Test
    fun serviceCodesAlwaysOpenTheDialer() = runTest {
        installHandler("com.example.telecom", Intent.ACTION_CALL, scheme = "tel")
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")
        shadowOf(context).grantPermissions(Manifest.permission.CALL_PHONE)

        // Unconditional call forwarding and a balance check: Android 10 to 13 would run them from ACTION_CALL.
        for (code in listOf("**21*5551234#", "*123#")) {
            val outcome = run("call_contact") { put("name_or_number", code) }

            assertTrue(code, outcome.success)
            assertEquals(code, "dialer_opened", outcome.result.text("status"))
            assertTrue(code, outcome.permissionsNeeded.isEmpty())
            val dialed = nextStartedActivity()
            assertEquals(code, Intent.ACTION_DIAL, dialed?.action)
            assertEquals(code, dialed?.data?.schemeSpecificPart)
        }
    }

    @Test
    fun aContactsServiceCodeOpensTheDialerAndNeedsNoCallPermission() = runTest {
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(1, "Voicemail", "*86", null))

        val outcome = run("call_contact") { put("name_or_number", "Voicemail") }

        assertTrue(outcome.success)
        assertEquals("dialer_opened", outcome.result.text("status"))
        assertTrue(outcome.permissionsNeeded.isEmpty())
        assertEquals("*86", nextStartedActivity()?.data?.schemeSpecificPart)
    }

    @Test
    fun namesNeedTheContactsPermission() = runTest {
        val outcome = run("call_contact") { put("name_or_number", "Mom") }

        assertFalse(outcome.success)
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), outcome.permissionsNeeded)
        assertTrue(outcome.result.text("error")?.contains("needs access to the contacts") == true)
        assertTrue(FakeContactsProvider.queries.isEmpty())
    }

    @Test
    fun aTextGoesToTheContactsNumberWithTheMessageFilledIn() = runTest {
        installHandler("com.example.messages", Intent.ACTION_SENDTO, scheme = "smsto")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(1, "Mom", "+1 555 0100", null))
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(2, "Tom Mommsen", "+1 555 0199", null))

        val outcome = run("send_text_message") {
            put("recipient", "mom")
            put("message", "On my way")
        }

        assertTrue(outcome.success)
        assertEquals("Mom", outcome.result.text("name"))
        assertEquals("+15550100", outcome.result.text("number"))
        assertEquals("Writing a text to Mom", outcome.summary)
        val started = nextStartedActivity()
        assertEquals("+15550100", started?.data?.schemeSpecificPart)
        assertEquals("On my way", started?.getStringExtra(AssistantIntents.EXTRA_SMS_BODY))
    }

    @Test
    fun ambiguousNamesAreReturnedForTheModelToAsk() = runTest {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(1, "John Smith", "+15550001", null))
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(2, "John Doe", "+15550002", null))

        val outcome = run("call_contact") { put("name_or_number", "John") }

        assertFalse(outcome.success)
        val matches = outcome.result["matches"]?.jsonArray?.map { name -> name.jsonPrimitive.content }
        assertEquals(listOf("John Smith", "John Doe"), matches)
        assertNull(nextStartedActivity())
    }

    @Test
    fun misheardNamesAreFoundByScanningAllContacts() = runTest {
        installHandler("com.example.dialer", Intent.ACTION_DIAL, scheme = "tel")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(1, "Catherine Bell", "+15550001", null))

        val outcome = run("call_contact") { put("name_or_number", "Katherine Bell") }

        assertTrue(outcome.success)
        assertEquals("Catherine Bell", outcome.result.text("name"))
        assertEquals(2, FakeContactsProvider.queries.size)
    }

    @Test
    fun emailsResolveContactNamesToAddresses() = runTest {
        installHandler("com.example.mail", Intent.ACTION_SENDTO, scheme = "mailto")
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.rows.add(FakeContactsProvider.Row(3, "Ada Lovelace", null, "ada@example.com"))

        val outcome = run("send_email") {
            put("to", "Ada, grace@example.com")
            put("subject", "Notes")
            put("body", "Here they are")
        }

        assertTrue(outcome.success)
        assertEquals("Writing an email to Ada Lovelace, grace@example.com", outcome.summary)
        val started = nextStartedActivity()
        val recipients = started?.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList()
        assertEquals(listOf("ada@example.com", "grace@example.com"), recipients)
    }

    // Apps, pages and settings

    @Test
    fun appsOpenByTheirSpokenName() = runTest {
        val youTube = installLauncherApp("com.google.android.youtube", "YouTube")
        installLauncherApp("com.spotify.music", "Spotify")

        val outcome = run("open_app") { put("name", "you tube") }

        assertTrue(outcome.success)
        assertEquals("YouTube", outcome.result.text("app"))
        assertEquals("Opening YouTube", outcome.summary)
        assertEquals(youTube, nextStartedActivity()?.component)
    }

    @Test
    fun unknownAppsComeWithSuggestions() = runTest {
        installLauncherApp("com.google.android.youtube", "YouTube")

        val outcome = run("open_app") { put("name", "youtub kids") }

        assertFalse(outcome.success)
        val similar = outcome.result["similar_apps"]?.jsonArray?.map { label -> label.jsonPrimitive.content }
        assertEquals(listOf("YouTube"), similar)
    }

    @Test
    fun anUpperCaseSchemeStillReachesTheBrowser() = runTest {
        installBrowser()

        val outcome = run("open_url") { put("url", "HTTPS://Example.com/news") }

        assertTrue(outcome.success)
        assertEquals("https://Example.com/news", outcome.result.text("url"))
        assertEquals("https://Example.com/news", nextStartedActivity()?.dataString)
    }

    @Test
    fun onlyWebPagesCanBeOpened() = runTest {
        installBrowser()

        val outcome = run("open_url") { put("url", "javascript:alert(1)") }

        assertFalse(outcome.success)
        assertNull(nextStartedActivity())
    }

    @Test
    fun calendarEventsUseTheLocalTimeZone() = runTest {
        // Calendar apps filter on the events MIME type, which Android resolves through the calendar
        // provider; Robolectric's start check does not, so this handler matches the content scheme.
        installHandler("com.example.calendar", Intent.ACTION_INSERT, scheme = "content")

        val outcome = run("create_calendar_event") {
            put("title", "Dentist")
            put("start", "2026-11-02T09:30")
        }

        assertTrue(outcome.result.toString(), outcome.success)
        assertEquals("2026-11-02T09:30", outcome.result.text("start"))
        assertEquals("2026-11-02T10:30", outcome.result.text("end"))
        val started = nextStartedActivity()
        assertEquals(CalendarContract.Events.CONTENT_URI, started?.data)
        val begin = started?.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
        assertEquals(Instant.parse("2026-11-02T08:30:00Z").toEpochMilli(), begin)
    }

    @Test
    fun wifiCannotBeSwitchedSoTheSettingsOpen() = runTest {
        installHandler("com.android.settings", "android.settings.panel.action.WIFI")

        val outcome = run("open_settings") { put("page", "wifi") }

        assertTrue(outcome.success)
        assertEquals("Opening Wi-Fi settings", outcome.summary)
        assertTrue(outcome.result.text("note")?.contains("cannot switch") == true)
        assertEquals("android.settings.panel.action.WIFI", nextStartedActivity()?.action)
    }

    // Direct device controls

    @Test
    fun theFlashlightSwitchesTheBackCameraTorch() = runTest {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowCharacteristics = shadowOf(characteristics)
        shadowCharacteristics.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowCharacteristics.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        val cameraManager = context.getSystemService(CameraManager::class.java)
        shadowOf(cameraManager).addCamera("0", characteristics)

        val outcome = run("set_flashlight", ToolOrigin.CALL) { put("on", true) }

        assertTrue(outcome.success)
        assertEquals("Flashlight on", outcome.summary)
        assertTrue(shadowOf(cameraManager).getTorchMode("0"))
    }

    @Test
    fun aPhoneWithoutFlashSaysSo() = runTest {
        val outcome = run("set_flashlight") { put("on", "on") }

        assertFalse(outcome.success)
        assertEquals("This phone has no flashlight.", outcome.result.text("error"))
    }

    @Test
    fun volumeIsSetAsAPercentageOfTheMediaRange() = runTest {
        val audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setStreamMaxVolume(15)

        val outcome = run("set_volume", ToolOrigin.CALL) { put("percent", 40) }

        assertTrue(outcome.success)
        assertEquals(6, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(40, outcome.result["percent"]?.jsonPrimitive?.int)
        assertEquals("Volume set to 40%", outcome.summary)
    }

    @Test
    fun mediaControlPressesTheMediaKey() = runTest {
        val audioManager = context.getSystemService(AudioManager::class.java)

        val outcome = run("media_control", ToolOrigin.CALL) { put("action", "pause") }

        assertTrue(outcome.success)
        val events = shadowOf(audioManager).dispatchedMediaKeyEvents
        val actions = events.map { event -> event.action }
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), actions)
        assertTrue(events.all { event -> event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE })
    }

    @Test
    fun theDeviceStatusHasTimeDateZoneAndBattery() = runTest {
        ShadowSettings.set24HourTimeFormat(false)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        shadowOf(batteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
        shadowOf(batteryManager).setIsCharging(true)

        val outcome = run("get_device_status")

        assertTrue(outcome.success)
        assertEquals("2:05 PM", outcome.result.text("local_time"))
        assertEquals("Thursday, 29 October 2026", outcome.result.text("date"))
        assertEquals("Europe/Berlin", outcome.result.text("timezone"))
        assertEquals("+01:00", outcome.result.text("utc_offset"))
        assertEquals(80, outcome.result["battery_percent"]?.jsonPrimitive?.int)
        assertTrue(outcome.result["charging"]?.jsonPrimitive?.boolean == true)
    }
}
