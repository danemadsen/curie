package com.danemadsen.curie

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.danemadsen.curie.assistant.WakeWordController
import com.danemadsen.curie.assistant.WakeWordControllerOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Creates the [AppContainer] and connects the process's lifecycle to it: the engine learns when
 * Curie is visible (it unloads the model only while hidden) and when memory runs out, and the
 * "Hey Curie" listener resumes when Curie is shown.
 */
class CurieApplication : Application(), WakeWordControllerOwner {
    lateinit var container: AppContainer
        private set

    override val wakeWordController: WakeWordController
        get() = container.wakeWordController

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        val visibility = ProcessVisibilityObserver(container)
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibility)

        container.applicationScope.launch {
            deleteOrphanedFiles()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.engine.onTrimMemory(level)
    }

    /** Files of crashes and lost drafts, once per start. */
    private suspend fun deleteOrphanedFiles() {
        try {
            container.chatRepository.deleteOrphanedFiles()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "cleaning up orphaned files failed", error)
        }
    }

    /** Started: an activity of Curie is visible. Stopped: none is. */
    private class ProcessVisibilityObserver(private val container: AppContainer) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            container.engine.onAppVisibilityChanged(visible = true)
            container.wakeWordController.onAppStart()
        }

        override fun onStop(owner: LifecycleOwner) {
            container.engine.onAppVisibilityChanged(visible = false)
        }
    }

    private companion object {
        const val TAG = "CurieApplication"
    }
}
