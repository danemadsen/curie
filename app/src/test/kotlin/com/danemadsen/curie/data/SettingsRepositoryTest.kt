package com.danemadsen.curie.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One DataStore per file and process: every test gets its own file, configured as the app's. */
    private val settingsFile = File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = settingsCorruptionHandler(),
        scope = scope,
        produceFile = { settingsFile },
    )
    private val profileDirectory = File(context.filesDir, "profile")
    private val profileImages = AttachmentStore(
        context = context,
        directory = profileDirectory,
        limits = AttachmentLimits(maxImageBytes = 1_000, maxVideoBytes = 1_000, maxAudioBytes = 1_000),
        probe = FakeMediaProbe(),
    )
    private val repository = SettingsRepository(dataStore, profileImages)

    @After
    fun tearDown() {
        scope.cancel()
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
    }

    private fun sourceUri(content: String): Uri {
        val sources = File(context.cacheDir, "sources")
        sources.mkdirs()
        val file = File.createTempFile("picked", ".bin", sources)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    @Test
    fun aFreshInstallHasTheDefaults() = runTest {
        val settings = repository.settings.first()

        assertEquals("User", settings.userName)
        assertEquals("Curie", settings.assistantName)
        assertNull(settings.userImage)
        assertNull(settings.assistantImage)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(0xFF2196F3.toInt(), settings.accentColor)
        assertTrue(settings.useSystemColors)
        assertFalse(settings.wakeWordEnabled)
        assertEquals(SpeakReplies.VOICE_MESSAGES, settings.speakReplies)
        assertTrue(settings.downloadOnWifiOnly)
    }

    @Test
    fun updatesPersistAndOnlyChangedValuesAreStored() = runTest {
        repository.update { settings ->
            settings.copy(userName = "Dane", themeMode = ThemeMode.DARK, speakReplies = SpeakReplies.ALWAYS)
        }

        val expected = Settings(userName = "Dane", themeMode = ThemeMode.DARK, speakReplies = SpeakReplies.ALWAYS)
        assertEquals(expected, repository.settings.first())

        val stored = dataStore.data.first()
        val storedKeys = stored.asMap().keys.map { key -> key.name }.toSet()
        assertEquals(setOf("user_name", "theme_mode", "speak_replies"), storedKeys)

        repository.update { settings -> settings.copy(downloadOnWifiOnly = false, accentColor = 0xFF00FF00.toInt()) }
        val updated = repository.settings.first()
        assertFalse(updated.downloadOnWifiOnly)
        assertEquals(0xFF00FF00.toInt(), updated.accentColor)
        assertEquals("Dane", updated.userName)
    }

    @Test
    fun turningSystemColorsOffIsKept() = runTest {
        repository.update { settings -> settings.copy(useSystemColors = false) }

        assertFalse(repository.settings.first().useSystemColors)
        val stored = dataStore.data.first()
        val storedKeys = stored.asMap().keys.map { key -> key.name }.toSet()
        assertEquals(setOf("use_system_colors"), storedKeys)
    }

    @Test
    fun anUnknownStoredNameFallsBackToTheDefault() = runTest {
        dataStore.edit { stored -> stored[stringPreferencesKey("theme_mode")] = "PURPLE" }

        assertEquals(ThemeMode.SYSTEM, repository.settings.first().themeMode)
    }

    @Test
    fun aDamagedFileIsReplacedByTheDefaultsAndCanBeWrittenAgain() = runTest {
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertEquals(Settings(), repository.settings.first())

        repository.update { settings -> settings.copy(userName = "Dane") }
        assertEquals("Dane", repository.settings.first().userName)
    }

    @Test
    fun avatarsAreCopiedAndTheReplacedOnesDeleted() = runTest {
        repository.setUserImage(sourceUri("IMAGE"))
        val first = repository.settings.first().userImage
        assertNotNull(first)
        assertEquals(profileDirectory.path, File(first).parent)

        repository.setAssistantImage(sourceUri("IMAGE"))
        repository.setUserImage(sourceUri("IMAGE"))
        val settings = repository.settings.first()
        val second = settings.userImage
        assertNotNull(second)
        assertFalse(File(first).exists())
        assertTrue(File(second).exists())
        assertTrue(File(settings.assistantImage.orEmpty()).exists())

        repository.setUserImage(null)
        assertNull(repository.settings.first().userImage)
        assertFalse(File(second).exists())
    }

    @Test
    fun anAvatarMustBeAnImage() = runTest {
        assertFailsWith<AttachmentImportException.Unsupported> {
            repository.setAssistantImage(sourceUri("AUDIO 1000"))
        }

        assertNull(repository.settings.first().assistantImage)
        assertTrue(profileDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun avatarsAreStoredByNameAndAMissingFileIsNoAvatar() = runTest {
        repository.setUserImage(sourceUri("IMAGE"))
        val path = repository.settings.first().userImage
        assertNotNull(path)

        val stored = dataStore.data.first()
        assertEquals(File(path).name, stored[stringPreferencesKey("user_image")])

        // A cloud backup restores the settings without the avatars
        File(path).delete()
        repository.update { settings -> settings.copy(userName = "Dane") }
        val settings = repository.settings.first()
        assertNull(settings.userImage)
        assertEquals("Dane", settings.userName)
    }

    @Test
    fun anAvatarOutsideAppStorageIsRefused() = runTest {
        val elsewhere = File(context.cacheDir, "avatar.png").path

        assertFailsWith<IllegalArgumentException> {
            repository.update { settings -> settings.copy(userImage = elsewhere) }
        }
        assertNull(repository.settings.first().userImage)
    }
}
