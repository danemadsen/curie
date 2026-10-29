package com.danemadsen.curie.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Colours the status and navigation bar icons for the content drawn behind them (edge to edge, the
 * bars have no background of their own). While this effect is in the composition the icons are dark
 * when [useDarkIcons] is true; leaving the composition restores what was there before, so a screen
 * that is always dark can nest it inside the app theme.
 */
@Composable
internal fun SystemBarAppearanceEffect(useDarkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) {
        return
    }

    DisposableEffect(view, useDarkIcons) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            return@DisposableEffect onDispose {}
        }

        val controller = WindowCompat.getInsetsController(window, view)
        val previousStatusBars = controller.isAppearanceLightStatusBars
        val previousNavigationBars = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons

        onDispose {
            controller.isAppearanceLightStatusBars = previousStatusBars
            controller.isAppearanceLightNavigationBars = previousNavigationBars
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
