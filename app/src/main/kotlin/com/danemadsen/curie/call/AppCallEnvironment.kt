package com.danemadsen.curie.call

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.util.Log
import com.danemadsen.curie.audio.AudioFocus
import com.danemadsen.curie.audio.FocusChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The phone for a call: audio focus for the assistant's voice, the lock screen (unlocked through
 * the visible [CallActivity]) and app launches (from that activity when it is shown).
 */
class AppCallEnvironment(context: Context) : CallEnvironment {
    private val appContext = context.applicationContext
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private var focus: AudioFocus? = null
    private var visibleActivity = WeakReference<Activity>(null)

    /** The call screen is shown: unlocking and launching go through it. Null when it is gone. */
    fun attach(activity: Activity?) {
        visibleActivity = WeakReference(activity)
    }

    /** One focus request per call, made again after a loss; [abandonAudioFocus] ends it. */
    override fun requestAudioFocus(onChange: (FocusChange) -> Unit): Boolean {
        var callFocus = focus
        if (callFocus == null) {
            val attributes = AudioFocus.speechAttributes(AudioAttributes.USAGE_ASSISTANT)
            callFocus = AudioFocus(appContext, attributes, onChange)
            focus = callFocus
        }
        return callFocus.request()
    }

    override fun abandonAudioFocus() {
        focus?.abandon()
        focus = null
    }

    override suspend fun ensureUnlocked(): Boolean {
        val isLocked = keyguardManager?.isKeyguardLocked ?: false
        if (!isLocked) {
            return true
        }
        val activity = visibleActivity.get() ?: return false
        return withContext(Dispatchers.Main) { requestUnlock(activity) }
    }

    override fun launch(intent: Intent): Boolean {
        val launcher: Context = visibleActivity.get() ?: appContext
        return try {
            launcher.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "nothing opens $intent", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "not allowed to open $intent", error)
            false
        }
    }

    private suspend fun requestUnlock(activity: Activity): Boolean {
        val manager = keyguardManager ?: return false
        val result = CompletableDeferred<Boolean>()
        val callback = object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                result.complete(true)
            }

            override fun onDismissCancelled() {
                result.complete(false)
            }

            override fun onDismissError() {
                result.complete(false)
            }
        }
        manager.requestDismissKeyguard(activity, callback)
        return result.await()
    }

    private companion object {
        const val TAG = "CallEnvironment"
    }
}
