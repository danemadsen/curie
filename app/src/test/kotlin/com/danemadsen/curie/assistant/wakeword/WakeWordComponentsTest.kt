package com.danemadsen.curie.assistant.wakeword

import android.Manifest
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.assistant.ActivityStartPolicy
import com.danemadsen.curie.assistant.CallTriggers
import com.danemadsen.curie.assistant.CurieVoiceInteractionService
import com.danemadsen.curie.assistant.TestAssistantApplication
import com.danemadsen.curie.assistant.WakeWordStatus
import com.danemadsen.curie.assistant.assistantComponent
import com.danemadsen.curie.assistant.installRecognitionService
import com.danemadsen.curie.assistant.recordProcessExit
import com.danemadsen.curie.assistant.triggerExtra
import com.danemadsen.curie.call.CallLauncher
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
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowVoiceInteractionService

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WakeWordHandoffTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var curieIsVisible = false
    private lateinit var notifications: WakeWordNotifications
    private lateinit var handoff: WakeWordHandoff

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications = WakeWordNotifications(context)
        notifications.createChannels()

        val startPolicy = ActivityStartPolicy { curieIsVisible }
        handoff = WakeWordHandoff(context, notifications, startPolicy)
    }

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun notification(id: Int): Notification? {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return shadowOf(notificationManager).getNotification(id)
    }

    @Test
    fun theActiveAssistantShowsASession() {
        val serviceController = Robolectric.buildService(CurieVoiceInteractionService::class.java)
        val service = serviceController.create().get()
        service.onReady()
        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))

        val result = handoff.openConversation()

        assertEquals(Handoff.ASSISTANT_SESSION, result)
        val sessionArguments = shadowOf(service).lastSessionBundle
        assertEquals(CallTriggers.WAKE_WORD, sessionArguments?.getString(CallLauncher.EXTRA_TRIGGER))
        assertNull(shadowOf(context).nextStartedActivity)
        service.onShutdown()
    }

    @Test
    fun theOverlayPermissionOpensTheCallScreenDirectly() {
        ShadowSettings.setCanDrawOverlays(true)

        val result = handoff.openConversation()

        assertEquals(Handoff.DIRECT_START, result)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(CallLauncher.ACTION_START_CALL, started?.action)
        assertEquals(CallTriggers.WAKE_WORD, started?.triggerExtra())
    }

    @Test
    fun aVisibleCurieOpensTheCallScreenDirectly() {
        curieIsVisible = true

        val result = handoff.openConversation()

        assertEquals(Handoff.DIRECT_START, result)
        assertEquals(CallLauncher.ACTION_START_CALL, shadowOf(context).nextStartedActivity?.action)
        assertNull(notification(WakeWordNotifications.TAP_TO_TALK_ID))
    }

    @Test
    fun otherwiseANotificationLetsTheUserTapToTalk() {
        val result = handoff.openConversation()

        assertEquals(Handoff.NOTIFICATION, result)
        assertNull(shadowOf(context).nextStartedActivity)

        val tapToTalk = notification(WakeWordNotifications.TAP_TO_TALK_ID)
        assertNotNull(tapToTalk)
        assertEquals(WakeWordNotifications.CHANNEL_HEARD, tapToTalk?.channelId)
        val contentIntent = shadowOf(tapToTalk?.contentIntent)
        assertTrue(contentIntent.isActivity)
        assertTrue(contentIntent.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(CallLauncher.ACTION_START_CALL, contentIntent.savedIntent.action)
        assertEquals(CallTriggers.NOTIFICATION, contentIntent.savedIntent.triggerExtra())
        assertNotNull("API 34+ sets the creator's background start mode", contentIntent.options)
    }

    @Test
    fun withoutTheNotificationPermissionNothingCanShow() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = handoff.openConversation()

        assertEquals(Handoff.NONE, result)
        assertNull(notification(WakeWordNotifications.TAP_TO_TALK_ID))
        assertNull(shadowOf(context).nextStartedActivity)
        assertFalse(handoff.canOpenFromBackground())
    }

    @Test
    fun aBlockedTapToTalkChannelCountsAsNoWayToOpen() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val heardChannel = notificationManager.getNotificationChannel(WakeWordNotifications.CHANNEL_HEARD)
        heardChannel.importance = NotificationManager.IMPORTANCE_NONE

        assertFalse(handoff.canOpenFromBackground())
        assertEquals(Handoff.NONE, handoff.openConversation())
    }

    @Test
    fun blockedNotificationsCountAsNoWayToOpen() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertFalse(handoff.canOpenFromBackground())
    }

    @Test
    fun theAssistantOrTheOverlayPermissionNeedNoNotifications() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(handoff.canOpenFromBackground())

        ShadowSettings.setCanDrawOverlays(false)
        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))
        assertTrue(handoff.canOpenFromBackground())
    }

    @Test
    fun aVisibleWindowAloneDoesNotCountForTheBackground() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        curieIsVisible = true

        assertFalse(handoff.canOpenFromBackground())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestAssistantApplication::class)
class WakeWordServiceTest {
    private val application: TestAssistantApplication = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        installRecognitionService(application)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startService(intent: Intent?): WakeWordService {
        val controller = Robolectric.buildService(WakeWordService::class.java, intent)
        val service = controller.create().get()
        service.onStartCommand(intent, 0, 1)
        idleMainLooper()
        return service
    }

    @Test
    fun everyStartEntersTheForegroundAsAMicrophoneService() {
        val service = startService(WakeWordService.listenIntent(application))

        val shadowService = shadowOf(service)
        assertEquals(WakeWordNotifications.LISTENING_ID, shadowService.lastForegroundNotificationId)
        val notification = shadowService.lastForegroundNotification
        assertEquals(WakeWordNotifications.CHANNEL_LISTENING, notification?.channelId)
        assertTrue((notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, notification?.actions?.size)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
    }

    @Test
    fun withoutTheMicrophonePermissionItStopsAfterEnteringTheForeground() {
        application.preferences.state.value = true

        val service = startService(WakeWordService.listenIntent(application))

        // startForegroundService() obliges startForeground() first; stopping before it crashes the app.
        assertEquals(WakeWordNotifications.LISTENING_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(WakeWordStatus.MissingPermission, application.wakeWordController.status.value)
    }

    @Test
    fun aStartWhileTheSettingIsOffStopsAgain() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        application.preferences.state.value = false

        val service = startService(WakeWordService.listenIntent(application))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(WakeWordStatus.Disabled, application.wakeWordController.status.value)
    }

    @Test
    fun aStickyRestartInTheBackgroundOffersToResumeInstead() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        application.preferences.state.value = true

        val service = startService(null)

        assertTrue(shadowOf(service).isStoppedBySelf)
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        assertNotNull(shadowOf(notificationManager).getNotification(WakeWordNotifications.RESUME_ID))
    }

    // In the next two tests the setting is off, so the engine stops at once (Robolectric cannot run it);
    // the start answers or keeps a user stop before the engine reads the setting.

    @Test
    fun aRequestedStartAnswersAnEarlierUserStop() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        recordProcessExit(application, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)

        startService(WakeWordService.listenIntent(application))

        assertFalse(UserStopHold(application).isHolding())
    }

    @Test
    fun aStickyRestartLeavesAUserStopHolding() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowVoiceInteractionService.setActiveService(assistantComponent(application))
        recordProcessExit(application, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)

        val service = startService(null)

        assertTrue(UserStopHold(application).isHolding())
        // It got past the background check: only the setting stopped it.
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(WakeWordStatus.Disabled, application.wakeWordController.status.value)
    }

    @Test
    fun theStopCommandStopsWithoutChangingTheSetting() {
        application.preferences.state.value = true

        val service = startService(WakeWordService.stopIntent(application))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(application.preferences.state.value)
    }

    @Test
    fun theNotificationsTurnOffSwitchesTheSettingOff() {
        application.preferences.state.value = true

        val service = startService(WakeWordService.turnOffIntent(application))

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(application.preferences.state.value)
        assertEquals(WakeWordStatus.Disabled, application.wakeWordController.status.value)
    }

    @Test
    fun theListeningNotificationOffersTurnOffAndOpensTheSettings() {
        val notifications = WakeWordNotifications(application)
        notifications.createChannels()

        val listening = notifications.listening(WakeWordStatus.Listening)

        val turnOff = shadowOf(listening.actions.single().actionIntent)
        assertTrue(turnOff.isService)
        assertTrue(turnOff.savedIntent.action?.endsWith("TURN_OFF_WAKE_WORD") == true)
        val openSettings = shadowOf(listening.contentIntent)
        assertTrue(openSettings.isActivity)
        assertTrue(openSettings.savedIntent.getBooleanExtra("com.danemadsen.curie.extra.OPEN_SETTINGS", false))
    }

    @Test
    fun theResumePromptRestartsTheServiceFromTheTap() {
        val notifications = WakeWordNotifications(application)
        notifications.createChannels()

        notifications.showResumePrompt()

        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val prompt = shadowOf(notificationManager).getNotification(WakeWordNotifications.RESUME_ID)
        val resume = shadowOf(prompt.contentIntent)
        assertTrue(resume.isForegroundService)
        assertEquals(WakeWordService::class.java.name, resume.savedIntent.component?.className)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = TestAssistantApplication::class)
class WakeWordBootReceiverTest {
    private val application: TestAssistantApplication = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        installRecognitionService(application)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
    }

    private fun sendBroadcast(action: String) {
        val intent = Intent(action)
        intent.setClass(application, WakeWordBootReceiver::class.java)
        application.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resumePromptIsPosted(): Boolean {
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val prompt = shadowOf(notificationManager).getNotification(WakeWordNotifications.RESUME_ID)
        return prompt != null
    }

    @Test
    fun afterABootItOffersToResumeButNeverStartsListening() {
        application.preferences.state.value = true

        sendBroadcast(Intent.ACTION_BOOT_COMPLETED)

        assertTrue(resumePromptIsPosted())
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun afterAnUpdateItOffersToResumeToo() {
        application.preferences.state.value = true

        sendBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertTrue(resumePromptIsPosted())
    }

    @Test
    fun whenTheWakeWordIsOffNothingHappens() {
        sendBroadcast(Intent.ACTION_BOOT_COMPLETED)

        assertFalse(resumePromptIsPosted())
    }

    @Test
    fun theActiveAssistantResumesWithoutAPrompt() {
        application.preferences.state.value = true
        ShadowVoiceInteractionService.setActiveService(assistantComponent(application))

        sendBroadcast(Intent.ACTION_BOOT_COMPLETED)

        assertFalse(resumePromptIsPosted())
        assertNull(shadowOf(application).nextStartedService)
    }
}
