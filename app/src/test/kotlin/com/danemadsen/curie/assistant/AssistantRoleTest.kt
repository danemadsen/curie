package com.danemadsen.curie.assistant

import android.app.ActivityManager
import android.app.Application
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.call.CallLauncher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowVoiceInteractionService

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AssistantRoleTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
    }

    private fun installSettingsScreen(action: String) {
        val component = ComponentName("com.android.settings", "com.android.settings.$action")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)

        val filter = IntentFilter(action)
        filter.addCategory(Intent.CATEGORY_DEFAULT)
        packageManager.addIntentFilterForActivity(component, filter)
    }

    private fun holdAssistantRole() {
        ShadowRoleManager.addRoleHolder(RoleManager.ROLE_ASSISTANT, context.packageName, Process.myUserHandle())
    }

    @Test
    fun theDefaultAssistantIsTheRoleHolder() {
        assertFalse(AssistantRole.isDefaultAssistant(context))

        holdAssistantRole()

        assertTrue(AssistantRole.isDefaultAssistant(context))
    }

    @Test
    fun theActiveServiceIsTheBoundVoiceInteractionService() {
        assertFalse(AssistantRole.isActiveVoiceInteractionService(context))

        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))

        assertTrue(AssistantRole.isActiveVoiceInteractionService(context))
    }

    @Test
    fun theRequestOpensTheDigitalAssistantSettings() {
        installSettingsScreen(Settings.ACTION_VOICE_INPUT_SETTINGS)
        installSettingsScreen(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

        val intent = AssistantRole.requestIntent(context)

        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theRequestFallsBackToDefaultAppsThenSettings() {
        val withoutScreens = AssistantRole.requestIntent(context)
        installSettingsScreen(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        val withDefaultApps = AssistantRole.requestIntent(context)

        assertEquals(Settings.ACTION_SETTINGS, withoutScreens.action)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, withDefaultApps.action)
    }

    @Test
    fun reselectionIsNeededWhenTheRoleIsHeldButTheServiceIsNotBound() {
        shadowOf(context.packageManager).setSystemFeature("android.software.voice_recognizers", true)
        holdAssistantRole()

        assertTrue(AssistantRole.needsReselection(context))
        assertTrue(AssistantRole.reselectionHint(context)?.contains("restart your phone") == true)

        ShadowVoiceInteractionService.setActiveService(assistantComponent(context))
        assertFalse(AssistantRole.needsReselection(context))
        assertNull(AssistantRole.reselectionHint(context))
    }

    @Test
    fun devicesThatCannotBindAServiceNeverNeedReselection() {
        holdAssistantRole()

        val withoutFeature = AssistantRole.needsReselection(context)
        shadowOf(context.packageManager).setSystemFeature("android.software.voice_recognizers", true)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).setIsLowRamDevice(true)
        val onLowRam = AssistantRole.needsReselection(context)

        assertFalse(withoutFeature)
        assertFalse(onLowRam)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsLauncherTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun opensTheMainScreenOnItsSettings() {
        val intent = SettingsLauncher.intent(context)

        assertEquals("com.danemadsen.curie.MainActivity", intent?.component?.className)
        assertTrue(intent?.getBooleanExtra(SettingsLauncher.EXTRA_OPEN_SETTINGS, false) == true)
        val flags = intent?.flags ?: 0
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun theAssistantSettingsActivityHandsOverAndFinishes() {
        val controller = Robolectric.buildActivity(AssistantSettingsActivity::class.java)
        val activity = controller.create().get()

        val started = shadowOf(activity).nextStartedActivity
        assertTrue(started.getBooleanExtra(SettingsLauncher.EXTRA_OPEN_SETTINGS, false))
        assertTrue(activity.isFinishing)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CurieVoiceInteractionTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowVoiceInteractionService.setActiveService(null)
    }

    private fun showSession(arguments: Bundle?): CurieVoiceInteractionSession {
        val session = CurieVoiceInteractionSession(context)
        shadowOf(session).create()
        session.onPrepareShow(arguments, 0)
        session.onShow(arguments, 0)
        return session
    }

    @Test
    fun theSessionStartsTheCallScreenWithoutAWindowOfItsOwn() {
        val session = showSession(null)

        val shadowSession = shadowOf(session)
        assertFalse(shadowSession.isUiEnabled)
        val started = shadowSession.lastAssistantActivityIntent
        assertEquals(CallLauncher.ACTION_START_CALL, started?.action)
        assertEquals(context.packageName, started?.`package`)
        assertEquals(CallTriggers.ASSIST, started?.triggerExtra())
    }

    @Test
    fun theSessionPassesTheWakeWordTriggerOn() {
        val arguments = Bundle()
        arguments.putString(CallLauncher.EXTRA_TRIGGER, CallTriggers.WAKE_WORD)

        val session = showSession(arguments)

        assertEquals(CallTriggers.WAKE_WORD, shadowOf(session).lastAssistantActivityIntent?.triggerExtra())
    }

    @Test
    fun aRefusedAssistantActivityDoesNotCrashTheSession() {
        val session = CurieVoiceInteractionSession(context)
        shadowOf(session).create()
        shadowOf(session).setStartAssistantActivityException(IllegalStateException("hidden"))

        session.onPrepareShow(null, 0)
        session.onShow(null, 0)
    }

    @Test
    fun theServiceIsTheActiveAssistantOnlyWhileReady() {
        val serviceController = Robolectric.buildService(CurieVoiceInteractionService::class.java)
        val service = serviceController.create().get()
        assertNull(ActiveAssistant.current())
        assertFalse(service.showConversation(CallTriggers.WAKE_WORD))

        service.onReady()
        assertEquals(service, ActiveAssistant.current())
        assertTrue(service.showConversation(CallTriggers.WAKE_WORD))
        val sessionArguments = shadowOf(service).lastSessionBundle
        assertEquals(CallTriggers.WAKE_WORD, sessionArguments?.getString(CallLauncher.EXTRA_TRIGGER))

        service.onShutdown()
        assertNull(ActiveAssistant.current())
        assertFalse(service.showConversation(CallTriggers.WAKE_WORD))
    }

    @Test
    fun aLateOnReadyAfterDestroyIsIgnored() {
        val serviceController = Robolectric.buildService(CurieVoiceInteractionService::class.java)
        val service = serviceController.create().get()

        serviceController.destroy()
        service.onReady()

        assertNull(ActiveAssistant.current())
    }

    @Test
    fun theLockScreenShortcutOpensTheCallScreen() {
        val serviceController = Robolectric.buildService(CurieVoiceInteractionService::class.java)
        val service = serviceController.create().get()

        service.onLaunchVoiceAssistFromKeyguard()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(CallLauncher.ACTION_START_CALL, started?.action)
        assertEquals(CallTriggers.ASSIST, started?.triggerExtra())
    }
}
