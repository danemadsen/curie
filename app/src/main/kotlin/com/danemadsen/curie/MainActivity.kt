package com.danemadsen.curie

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.danemadsen.curie.assistant.SettingsLauncher
import com.danemadsen.curie.ui.navigation.CurieApp
import com.danemadsen.curie.ui.navigation.MainViewModel
import com.danemadsen.curie.ui.navigation.SharedMedia

/**
 * The app's screens: setup, the chats and the settings. It also answers images, videos and audio
 * shared from other apps (a new chat with them in the composer) and the request to open the
 * settings (from the voice assistant's settings entry).
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as CurieApplication).container

    private val mainViewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer { MainViewModel(container.settingsRepository.settings, container::hasCompleteDownload) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { mainViewModel.startDestination.value == null }
        enableEdgeToEdge()

        // A recreated activity already handled its intent
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            CurieApp(container = container, mainViewModel = mainViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val opensSettings = intent.getBooleanExtra(SettingsLauncher.EXTRA_OPEN_SETTINGS, false)
        if (opensSettings) {
            mainViewModel.onOpenSettings()
        }

        if (intent.action == Intent.ACTION_SEND) {
            val media = sharedMediaOf(intent)
            if (media != null) {
                mainViewModel.onSharedMedia(media)
            }
        }
    }

    /**
     * The stream another app shared, when it is an image, a video or audio behind a content Uri.
     * A file Uri is refused: it could name Curie's own private files.
     */
    private fun sharedMediaOf(intent: Intent): SharedMedia? {
        val mimeType = intent.type ?: return null
        val isMedia = SHARED_TYPES.any { prefix -> mimeType.startsWith(prefix) }
        if (!isMedia) {
            return null
        }

        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return null
        }
        return SharedMedia(listOf(uri), mimeType)
    }

    private companion object {
        val SHARED_TYPES = listOf("image/", "video/", "audio/")
    }
}
