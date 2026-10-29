package com.danemadsen.curie.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.danemadsen.curie.AppContainer
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.ui.settings.SettingsViewModel
import com.danemadsen.curie.ui.theme.CurieTheme

/**
 * The main activity's content: the theme from the settings around the screens. Nothing shows
 * until the settings and the first screen are known (the splash screen covers that moment).
 */
@Composable
fun CurieApp(container: AppContainer, mainViewModel: MainViewModel) {
    val appearance by mainViewModel.appearance.collectAsStateWithLifecycle()
    val startDestination by mainViewModel.startDestination.collectAsStateWithLifecycle()
    val settings = appearance ?: Settings()
    val themeMode = SettingsViewModel.themeModeOf(settings.themeMode)
    val accentColor = Color(settings.accentColor)

    CurieTheme(themeMode = themeMode, accentColor = accentColor, useSystemColors = settings.useSystemColors) {
        val destination = startDestination
        if (destination != null) {
            CurieNavHost(container = container, mainViewModel = mainViewModel, startDestination = destination)
        }
    }
}

/**
 * Setup (until the model is downloaded), the chat with its drawer, the settings and the licences.
 * Requests from intents (shared media, opening the settings) navigate here.
 */
@Composable
private fun CurieNavHost(
    container: AppContainer,
    mainViewModel: MainViewModel,
    startDestination: Destination,
    navController: NavHostController = rememberNavController(),
) {
    val sharedMedia by mainViewModel.sharedMedia.collectAsStateWithLifecycle()
    val openSettings by mainViewModel.openSettings.collectAsStateWithLifecycle()

    LaunchedEffect(openSettings) {
        if (openSettings) {
            mainViewModel.consumeOpenSettings()
            navController.navigate(Destination.Settings) { launchSingleTop = true }
        }
    }
    LaunchedEffect(sharedMedia) {
        if (sharedMedia != null) {
            navController.navigate(Destination.Chat) {
                popUpTo(Destination.Chat)
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Destination.Setup> {
            val setupViewModel = viewModel { container.setupViewModel() }
            SetupRoute(
                viewModel = setupViewModel,
                onModelReady = {
                    navController.navigate(Destination.Chat) {
                        popUpTo(Destination.Setup) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Destination.Chat> {
            val chatViewModel = viewModel { container.chatViewModel(createSavedStateHandle()) }
            ChatRoute(
                viewModel = chatViewModel,
                sharedMedia = sharedMedia,
                onSharedMediaTaken = { mainViewModel.consumeSharedMedia() },
                onOpenSettings = { navController.navigate(Destination.Settings) { launchSingleTop = true } },
                onOpenSetup = { navController.navigate(Destination.Setup) { launchSingleTop = true } },
            )
        }
        composable<Destination.Settings> {
            val settingsViewModel = viewModel { container.settingsViewModel() }
            SettingsRoute(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenLicenses = { navController.navigate(Destination.Licenses) { launchSingleTop = true } },
            )
        }
        composable<Destination.Licenses> {
            LicensesRoute(onBack = { navController.popBackStack() })
        }
    }
}
