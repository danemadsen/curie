package com.danemadsen.curie.assistant

import android.Manifest
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.R
import com.danemadsen.curie.assistant.wakeword.PauseReason
import com.danemadsen.curie.assistant.wakeword.StopReason
import com.danemadsen.curie.assistant.wakeword.WakeWordNotifications
import com.danemadsen.curie.assistant.wakeword.WakeWordService
import com.danemadsen.curie.audio.MicrophoneArbiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowVoiceInteractionService

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WakeWordControllerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val preferences = FakeWakeWordPreferences()
    private lateinit var controller: WakeWordController

    @Before
    fun setUp() {
        installRecognitionService(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller = WakeWordController(context, preferences, MicrophoneArbiter())
        idleMainLooper()
    }

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun grantMicrophone() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun blockNotifications() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun nextStartedService(): Intent? {
        return shadowOf(context).nextStartedService
    }

    private fun postedNotificationIds(): List<Int> {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(notificationManager).activeNotifications
        return notifications.map { notification -> notification.id }
    }

    private fun reason(id: Int): String {
        return context.getString(id)
    }

    /** Installs the default speech service's app and selects it, as the disclosure looks it up. */
    private fun installSpeechServiceApp(label: String) {
        val applicationInfo = ApplicationInfo()
        applicationInfo.packageName = "com.example.speech"
        applicationInfo.nonLocalizedLabel = label

        val packageInfo = PackageInfo()
        packageInfo.packageName = "com.example.speech"
        packageInfo.applicationInfo = applicationInfo
        shadowOf(context.packageManager).installPackage(packageInfo)

        val contentResolver = context.contentResolver
        Settings.Secure.putString(contentResolver, "voice_recognition_service", "com.example.speech/.RecognizerService")
    }

    @Test
    fun startsDisabled() {
        assertEquals(WakeWordStatus.Disabled, controller.status.value)
    }

    @Test
    fun turningOnWithoutTheMicrophonePermissionAsksForIt() = runTest {
        controller.setEnabled(true)

        assertTrue(preferences.state.value)
        assertEquals(WakeWordStatus.MissingPermission, controller.status.value)
        assertNull(nextStartedService())
    }

    @Test
    fun turningOnStartsTheListeningService() = runTest {
        grantMicrophone()

        controller.setEnabled(true)

        val started = nextStartedService()
        assertEquals(WakeWordService::class.java.name, started?.component?.className)
        val starting = reason(R.string.wake_word_status_starting)
        assertEquals(WakeWordStatus.Paused(starting), controller.status.value)
    }

    @Test
    fun aSlowSettingsReadDoesNotOverwriteAStartThatHappenedMeanwhile() = runTest {
        grantMicrophone()
        val settingRead = CompletableDeferred<Unit>()
        val slowPreferences = object : WakeWordPreferences {
            override val enabled: Flow<Boolean> = flow {
                settingRead.await()
                emit(true)
            }

            override suspend fun setEnabled(enabled: Boolean) = Unit
        }
        val slowController = WakeWordController(context, slowPreferences, MicrophoneArbiter())
        idleMainLooper()

        slowController.setEnabled(true)
        settingRead.complete(Unit)
        idleMainLooper()

        val starting = reason(R.string.wake_word_status_starting)
        assertEquals(WakeWordStatus.Paused(starting), slowController.status.value)
    }

    @Test
    fun turningOffSendsAStopCommandInsteadOfStoppingTheServiceAbruptly() = runTest {
        grantMicrophone()
        controller.setEnabled(true)
        nextStartedService()

        controller.setEnabled(false)

        assertFalse(preferences.state.value)
        assertEquals(WakeWordStatus.Disabled, controller.status.value)
        val stop = nextStartedService()
        assertEquals(WakeWordService::class.java.name, stop?.component?.className)
        assertTrue(stop?.action?.endsWith("STOP_LISTENING") == true)
    }

    @Test
    fun turningOffWhenNothingRunsSendsNothing() = runTest {
        controller.setEnabled(false)

        assertNull(nextStartedService())
        assertEquals(WakeWordStatus.Disabled, controller.status.value)
    }

    @Test
    fun withoutASpeechServiceItIsUnavailable() = runTest {
        grantMicrophone()
        val recognizerService = ComponentName("com.example.speech", "com.example.speech.RecognizerService")
        shadowOf(context.packageManager).removeService(recognizerService)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)

        controller.setEnabled(true)

        assertTrue(controller.status.value is WakeWordStatus.Unavailable)
        assertNull(nextStartedService())
    }

    @Test
    fun withoutAnyWayToOpenCurieFromTheBackgroundNothingListens() = runTest {
        grantMicrophone()
        blockNotifications()

        controller.setEnabled(true)

        // Not the active assistant, no "Display over other apps", and no "tap to talk" notification.
        assertTrue(preferences.state.value)
        assertNull(nextStartedService())
        val notificationsNeeded = reason(R.string.wake_word_paused_notifications_blocked)
        assertEquals(WakeWordStatus.Paused(notificationsNeeded), controller.status.value)
    }

    @Test
    fun theOverlayPermissionLetsItListenWithoutNotifications() = runTest {
        grantMicrophone()
        blockNotifications()
        ShadowSettings.setCanDrawOverlays(true)

        controller.setEnabled(true)

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun theActiveAssistantListensWithoutNotifications() = runTest {
        grantMicrophone()
        blockNotifications()
        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))

        controller.setEnabled(true)

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun notificationsBlockedWhileListeningStopTheServiceWhenTheAppOpens() = runTest {
        grantMicrophone()
        controller.setEnabled(true)
        nextStartedService()
        controller.onServiceCreated()
        controller.onListening()

        blockNotifications()
        controller.onAppStart()
        idleMainLooper()

        val stop = nextStartedService()
        assertTrue(stop?.action?.endsWith("STOP_LISTENING") == true)
        val notificationsNeeded = reason(R.string.wake_word_paused_notifications_blocked)
        assertEquals(WakeWordStatus.Paused(notificationsNeeded), controller.status.value)
    }

    @Test
    fun aWakeWordThatCouldNotOpenCurieAsksForNotifications() {
        controller.onServiceCreated()
        controller.onListening()
        blockNotifications()

        controller.onListeningStopped(StopReason.CANNOT_OPEN_CONVERSATION)
        controller.onServiceDestroyed()

        val notificationsNeeded = reason(R.string.wake_word_paused_notifications_blocked)
        assertEquals(WakeWordStatus.Paused(notificationsNeeded), controller.status.value)
    }

    @Test
    fun openingTheAppResumesListeningThatWasOn() {
        grantMicrophone()
        preferences.state.value = true

        controller.onAppStart()
        idleMainLooper()

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun openingTheAppLeavesItOffWhenItIsOff() {
        controller.onAppStart()
        idleMainLooper()

        assertNull(nextStartedService())
        assertEquals(WakeWordStatus.Disabled, controller.status.value)
    }

    @Test
    fun afterABootTheUserIsAskedToResume() {
        grantMicrophone()
        preferences.state.value = true

        controller.onSystemStartup()
        idleMainLooper()

        assertNull(nextStartedService())
        assertTrue(WakeWordNotifications.RESUME_ID in postedNotificationIds())
        val openApp = reason(R.string.wake_word_paused_open_app)
        assertEquals(WakeWordStatus.Paused(openApp), controller.status.value)
    }

    @Test
    fun afterABootTheActiveAssistantResumesByItself() {
        grantMicrophone()
        preferences.state.value = true
        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))

        controller.onSystemStartup()
        idleMainLooper()

        assertNull(nextStartedService())
        assertTrue(postedNotificationIds().isEmpty())
    }

    @Test
    fun theAssistantStartsListeningWhenReady() {
        grantMicrophone()
        preferences.state.value = true

        controller.onAssistantReady()
        idleMainLooper()

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun afterTheUserStoppedCurieTheAssistantDoesNotRestartListening() {
        grantMicrophone()
        preferences.state.value = true
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)

        // Android binds the assistant again right after a Force stop or a Task Manager stop.
        controller.onAssistantReady()
        idleMainLooper()

        assertNull(nextStartedService())
        assertTrue(preferences.state.value)
        assertTrue(postedNotificationIds().isEmpty())
        val openApp = reason(R.string.wake_word_paused_open_app)
        assertEquals(WakeWordStatus.Paused(openApp), controller.status.value)
    }

    @Test
    fun openingTheAppAfterAUserStopResumesListening() {
        grantMicrophone()
        preferences.state.value = true
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)

        controller.onAppStart()
        idleMainLooper()

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun aUserStopThatARequestedStartAnsweredNoLongerHolds() {
        grantMicrophone()
        preferences.state.value = true
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)
        controller.onRequestedStart()

        // For example after a later reboot, or a process death for another reason.
        recordProcessExit(context, ApplicationExitInfo.REASON_LOW_MEMORY, timestampMillis = 2_000L)
        controller.onAssistantReady()
        idleMainLooper()

        assertEquals(WakeWordService::class.java.name, nextStartedService()?.component?.className)
    }

    @Test
    fun anAssistantHeldByAUserStopIsOfferedTheResumePromptAfterABootOrAnUpdate() {
        grantMicrophone()
        preferences.state.value = true
        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)

        controller.onSystemStartup()
        idleMainLooper()

        assertNull(nextStartedService())
        assertTrue(WakeWordNotifications.RESUME_ID in postedNotificationIds())
    }

    @Test
    fun theServiceReportsItsState() {
        controller.onListening()
        assertEquals(WakeWordStatus.Listening, controller.status.value)

        controller.onPaused(PauseReason.BATTERY_SAVER)
        val batterySaver = reason(R.string.wake_word_paused_battery_saver)
        assertEquals(WakeWordStatus.Paused(batterySaver), controller.status.value)

        controller.onListeningStopped(StopReason.LANGUAGE_NOT_SUPPORTED)
        val language = reason(R.string.wake_word_unavailable_language)
        assertEquals(WakeWordStatus.Unavailable(language), controller.status.value)
    }

    @Test
    fun lostBackgroundMicrophoneAccessOffersToResume() {
        grantMicrophone()

        controller.onListeningStopped(StopReason.NO_PERMISSION)

        assertTrue(WakeWordNotifications.RESUME_ID in postedNotificationIds())
        assertTrue(controller.status.value is WakeWordStatus.Paused)
    }

    @Test
    fun aRevokedPermissionIsReportedAsSuch() {
        controller.onListeningStopped(StopReason.NO_PERMISSION)

        assertEquals(WakeWordStatus.MissingPermission, controller.status.value)
    }

    @Test
    fun anUnexpectedlyStoppedServiceNeedsTheApp() {
        controller.onServiceCreated()
        controller.onListening()

        controller.onServiceDestroyed()

        val openApp = reason(R.string.wake_word_paused_open_app)
        assertEquals(WakeWordStatus.Paused(openApp), controller.status.value)
    }

    @Test
    fun theDisclosureNamesTheSpeechService() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)

        val genericName = reason(R.string.wake_word_speech_service_generic)
        val genericDisclosure = controller.disclosure()
        assertTrue(genericDisclosure.text, genericDisclosure.text.contains("recognised by $genericName,"))
        assertEquals("Turn on “Hey Curie”?", genericDisclosure.title)
        assertEquals("Turn on", genericDisclosure.acceptLabel)
        assertEquals("Not now", genericDisclosure.declineLabel)

        installSpeechServiceApp("Example Speech")
        val disclosure = controller.disclosure()

        assertTrue(disclosure.text, disclosure.text.contains("recognised by Example Speech, which may send it"))
        assertFalse(disclosure.text, disclosure.text.contains("on-device"))
    }

    @Test
    fun whereTheOnDeviceRecognizerMayListenTheDisclosureNamesItToo() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        installSpeechServiceApp("Example Speech")

        val disclosure = controller.disclosure()

        assertTrue(disclosure.text, disclosure.text.contains("your phone's on-device speech recognition"))
        assertTrue(disclosure.text, disclosure.text.contains("otherwise by Example Speech, which may send it"))
    }

    @Test
    fun asksForTheMicrophoneAndNotifications() {
        val permissions = controller.requiredPermissions

        assertEquals(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), permissions)
        assertFalse(controller.isExperimental)
        assertNull(controller.experimentalNote())
    }

    @Test
    fun createsItsNotificationChannels() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        assertNotNull(notificationManager.getNotificationChannel(WakeWordNotifications.CHANNEL_LISTENING))
        assertNotNull(notificationManager.getNotificationChannel(WakeWordNotifications.CHANNEL_RESUME))
        assertNotNull(notificationManager.getNotificationChannel(WakeWordNotifications.CHANNEL_HEARD))
    }
}
