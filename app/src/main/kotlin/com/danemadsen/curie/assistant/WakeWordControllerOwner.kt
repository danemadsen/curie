package com.danemadsen.curie.assistant

import android.content.Context

/**
 * Implemented by the Application: the components Android creates by itself (the voice interaction
 * service, the listening service, the boot receiver) reach the app's single [WakeWordController]
 * through it. Without it they do nothing.
 */
interface WakeWordControllerOwner {
    val wakeWordController: WakeWordController

    companion object {
        internal fun controller(context: Context): WakeWordController? {
            val owner = context.applicationContext as? WakeWordControllerOwner
            return owner?.wakeWordController
        }
    }
}
