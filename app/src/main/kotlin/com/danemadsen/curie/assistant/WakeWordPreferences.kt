package com.danemadsen.curie.assistant

import kotlinx.coroutines.flow.Flow

/** The persisted "Hey Curie" switch. The app's settings store implements it; [WakeWordController] owns its meaning. */
interface WakeWordPreferences {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
