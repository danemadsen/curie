package com.danemadsen.curie.assistant.wakeword

import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.assistant.recordProcessExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UserStopHoldTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withNoExitOnRecordNothingHolds() {
        val hold = UserStopHold(context)

        assertFalse(hold.isHolding())
    }

    @Test
    fun exitsTheUserDidNotAskForDoNotHold() {
        recordProcessExit(context, ApplicationExitInfo.REASON_LOW_MEMORY, timestampMillis = 1_000L)
        recordProcessExit(context, ApplicationExitInfo.REASON_PACKAGE_UPDATED, timestampMillis = 2_000L)
        recordProcessExit(context, ApplicationExitInfo.REASON_CRASH, timestampMillis = 3_000L)

        val hold = UserStopHold(context)

        assertFalse(hold.isHolding())
    }

    @Test
    fun aUserStopHoldsUntilARequestedStartAnswersIt() {
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)
        val hold = UserStopHold(context)
        assertTrue(hold.isHolding())

        hold.lift()

        assertFalse(hold.isHolding())
        // The answer is persisted: the next process (a new instance) sees it too.
        assertFalse(UserStopHold(context).isHolding())
    }

    @Test
    fun aStopAfterTheAnsweredOneHoldsAgain() {
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)
        val hold = UserStopHold(context)
        hold.lift()

        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 5_000L)

        assertTrue(hold.isHolding())
    }

    @Test
    fun aStopKeepsHoldingWhenAndroidLosesItsRecord() {
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)
        assertTrue(UserStopHold(context).isHolding())

        // A reboot drops exit records Android had not written to storage yet.
        ShadowActivityManager.reset()

        val holdAfterReboot = UserStopHold(context)
        assertTrue(holdAfterReboot.isHolding())
        holdAfterReboot.lift()
        assertFalse(holdAfterReboot.isHolding())
    }

    @Test
    fun aStopKeepsHoldingWhenTheNextProcessDiesForAnotherReason() {
        recordProcessExit(context, ApplicationExitInfo.REASON_USER_REQUESTED, timestampMillis = 1_000L)
        recordProcessExit(context, ApplicationExitInfo.REASON_LOW_MEMORY, timestampMillis = 2_000L)

        val hold = UserStopHold(context)

        assertTrue(hold.isHolding())
    }
}
