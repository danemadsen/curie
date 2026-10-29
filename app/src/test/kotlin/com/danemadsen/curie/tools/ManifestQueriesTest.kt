package com.danemadsen.curie.tools

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * From API 30 an app only sees the apps its <queries> name, so resolving an intent without a matching
 * entry fails even when an app could handle it. A call defers app switches by resolving them first,
 * and the assistant settings intent is resolved too: every such intent needs an entry.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ManifestQueriesTest {
    private data class QueryIntent(
        val action: String,
        val categories: Set<String>,
        val schemes: Set<String>,
        val mimeTypes: Set<String>,
    )

    private val manifest = File("src/main/AndroidManifest.xml")
    private val queryIntents = mutableListOf<QueryIntent>()
    private val queryPackages = mutableSetOf<String>()

    init {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(manifest)

        // A manifest may hold several <queries> elements; the manifest merger combines them all.
        val queriesElements = document.getElementsByTagName("queries")
        for (queriesIndex in 0 until queriesElements.length) {
            val queries = queriesElements.item(queriesIndex) as Element
            readQueries(queries)
        }
    }

    private fun readQueries(queries: Element) {
        val intentElements = queries.getElementsByTagName("intent")
        for (index in 0 until intentElements.length) {
            val element = intentElements.item(index) as Element
            val action = attributes(element, "action", "android:name").single()
            val categories = attributes(element, "category", "android:name").toSet()
            val schemes = attributes(element, "data", "android:scheme").toSet()
            val mimeTypes = attributes(element, "data", "android:mimeType").toSet()
            queryIntents.add(QueryIntent(action, categories, schemes, mimeTypes))
        }

        val packages = attributes(queries, "package", "android:name")
        queryPackages.addAll(packages)
    }

    private fun attributes(parent: Element, tag: String, attribute: String): List<String> {
        val elements = parent.getElementsByTagName(tag)
        val values = mutableListOf<String>()
        for (index in 0 until elements.length) {
            val value = (elements.item(index) as Element).getAttribute(attribute)
            if (value.isNotEmpty()) {
                values.add(value)
            }
        }
        return values
    }

    private fun isCovered(intent: Intent, mimeType: String? = null): Boolean {
        val packageName = intent.`package` ?: intent.component?.packageName
        if (packageName != null && packageName in queryPackages) {
            return true
        }

        val categories = intent.categories ?: emptySet()
        val scheme = intent.data?.scheme
        return queryIntents.any { query ->
            val sameAction = query.action == intent.action
            val categoriesMatch = categories.containsAll(query.categories)
            val dataMatches = when {
                mimeType != null -> mimeType in query.mimeTypes
                scheme != null -> scheme in query.schemes
                else -> query.schemes.isEmpty() && query.mimeTypes.isEmpty()
            }
            sameAction && categoriesMatch && dataMatches
        }
    }

    private fun assertCovered(intents: List<Intent?>) {
        for (intent in intents.filterNotNull()) {
            assertTrue("No <queries> entry for $intent", isCovered(intent))
        }
    }

    @Test
    fun everyDeferredAppSwitchIsResolvable() {
        // open_app lists (and so may launch) the apps with a launcher icon.
        val listsLauncherApps = queryIntents.any { query ->
            query.action == Intent.ACTION_MAIN && Intent.CATEGORY_LAUNCHER in query.categories
        }
        assertTrue("open_app needs the MAIN/LAUNCHER entry", listsLauncherApps)

        val modes = TravelMode.entries
        val navigation = modes.flatMap { mode ->
            listOf(
                AssistantIntents.googleNavigation("Paris", mode),
                AssistantIntents.mapsDirections("Paris", mode),
            )
        }

        assertCovered(
            listOf(
                AssistantIntents.playMusic("x", artist = null, album = null),
                AssistantIntents.youTubeAppSearch("x"),
                AssistantIntents.youTubeWebSearch("x"),
                AssistantIntents.geoSearch("x"),
                AssistantIntents.mapsSearch("x"),
                AssistantIntents.dial("5551234"),
                AssistantIntents.call("5551234"),
                AssistantIntents.composeSms("5551234", "x"),
                AssistantIntents.composeEmail(emptyList(), null, "x"),
                AssistantIntents.webSearch("x"),
                AssistantIntents.webSearchPage("x"),
            ) + navigation,
        )

        val times = EventTimes(0L, 1L, isAllDay = false)
        val calendar = AssistantIntents.insertCalendarEvent("x", times, null)
        assertTrue(isCovered(calendar, mimeType = "vnd.android.cursor.dir/event"))
    }

    @Test
    fun everySettingsScreenIsResolvable() {
        for (page in SettingsPage.entries) {
            val candidates = AssistantIntents.settings(page, "com.danemadsen.curie")
            assertCovered(candidates)
        }

        val assistantSettings = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        assertCovered(assistantSettings)
    }

    @Test
    fun speechRecognitionServicesAreVisible() {
        val recognition = queryIntents.any { query -> query.action == "android.speech.RecognitionService" }

        assertTrue(recognition)
    }
}
