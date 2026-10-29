package com.danemadsen.curie.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log

/**
 * The gear icon next to Curie in Settings > Digital assistant app (the voice interaction service's
 * settingsActivity). It has no UI of its own: it opens Curie's settings screen and finishes.
 */
class AssistantSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsIntent = SettingsLauncher.intent(this)
        if (settingsIntent != null) {
            try {
                startActivity(settingsIntent)
            } catch (exception: ActivityNotFoundException) {
                Log.w(TAG, "Curie's settings screen is missing", exception)
            }
        }

        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }

    private companion object {
        const val TAG = "AssistantSettings"
    }
}
