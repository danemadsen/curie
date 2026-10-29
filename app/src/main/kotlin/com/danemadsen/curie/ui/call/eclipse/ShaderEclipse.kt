package com.danemadsen.curie.ui.call.eclipse

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The eclipse drawn by [shader] over the whole bounds. Only the drawing reads [frame], so each new frame
 * redraws without recomposing.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun ShaderEclipse(shader: EclipseShader, frame: State<EclipseFrame>, modifier: Modifier = Modifier) {
    val brush = remember(shader) { ShaderBrush(shader.runtimeShader) }

    Canvas(modifier = modifier) {
        shader.update(size, frame.value)
        drawRect(brush = brush)
    }
}
