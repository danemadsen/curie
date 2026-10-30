package com.danemadsen.curie.ui.call.eclipse

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** Each colour channel turned into its complement; the matrix's offsets are on the 0 to 255 scale. */
private val INVERT_COLORS = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/**
 * Draws the content as its photographic negative on a light page. The eclipse is light added to
 * black, so the content is first drawn over black in a layer of its own, which looks exactly as it
 * does on a dark page; the layer is then inverted and multiplied onto what lies beneath. The flares
 * come out black and the Moon and the sky as light as the page, and where the eclipse fades to
 * black at its edges the layer fades into the page, so no edge of the bounds shows.
 *
 * The layer is opaque, so inverting it is exact. Put this before the content's own layer
 * ([androidx.compose.ui.graphics.graphicsLayer]) so that it wraps it.
 */
internal fun Modifier.drawAsNegative(): Modifier = drawWithCache {
    val bounds = Rect(Offset.Zero, size)
    val negativePaint = Paint().apply {
        blendMode = BlendMode.Multiply
        colorFilter = ColorFilter.colorMatrix(INVERT_COLORS)
    }

    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(bounds, negativePaint) }
        drawRect(Color.Black)
        drawContent()
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
