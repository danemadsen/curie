package com.danemadsen.curie.ui.call.eclipse

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** The fallback renderer's still layers as images; see [CoronaLayers] for what each one holds. */
internal class FallbackCoronaImages(
    val dimEclipse: ImageBitmap,
    val brightEclipse: ImageBitmap,
    val loudStreamers: ImageBitmap,
)

/** Renders the fallback's still layers with [CoronaRaster]. */
internal object FallbackCorona {
    /** Enough detail for a phone-wide eclipse, and quick to render. */
    const val SIZE = 768

    /** Smaller layers for previews, which render on the calling thread. */
    const val PREVIEW_SIZE = 384

    /**
     * At most this many threads render, so the call screen's other work (loading the model, say) keeps the
     * rest of the CPU; the placeholder shows meanwhile.
     */
    private const val RENDER_THREADS = 2

    /** Bands of rows rendered in parallel, and rows rendered between cancellation checks. */
    private const val BANDS = 8
    private const val ROWS_PER_CHUNK = 16

    private val renderDispatcher = Dispatchers.Default.limitedParallelism(RENDER_THREADS)

    /** Renders the layers, squares of [size] pixels, in the background; cancellable. */
    suspend fun render(size: Int = SIZE): FallbackCoronaImages = withContext(renderDispatcher) {
        val phases = CoronaShape.phasesAt(driftSeconds = 0.0)
        val flow = CoronaShape.flowAt(shimmerSeconds = 0.0)
        val layers = CoronaLayers(size)

        coroutineScope {
            for (band in bandsOf(size)) {
                launch {
                    renderInChunks(band) { rows -> CoronaRaster.renderRows(layers, rows, phases, flow) }
                }
            }
        }

        imagesOf(layers)
    }

    /** Renders the layers, squares of [size] pixels, on the calling thread, for previews. */
    fun renderNow(size: Int): FallbackCoronaImages {
        val phases = CoronaShape.phasesAt(driftSeconds = 0.0)
        val flow = CoronaShape.flowAt(shimmerSeconds = 0.0)
        val layers = CoronaLayers(size)

        CoronaRaster.renderRows(layers, 0 until size, phases, flow)
        return imagesOf(layers)
    }

    private fun bandsOf(size: Int): List<IntRange> {
        val rowsPerBand = (size + BANDS - 1) / BANDS
        val starts = 0 until size step rowsPerBand
        return starts.map { start -> start until min(start + rowsPerBand, size) }
    }

    private suspend fun renderInChunks(band: IntRange, renderRows: (IntRange) -> Unit) {
        var start = band.first
        while (start <= band.last) {
            currentCoroutineContext().ensureActive()
            val end = min(start + ROWS_PER_CHUNK - 1, band.last)
            renderRows(start..end)
            start = end + 1
        }
    }

    private fun imagesOf(layers: CoronaLayers): FallbackCoronaImages = FallbackCoronaImages(
        dimEclipse = imageOf(layers.dimEclipse, layers.size),
        brightEclipse = imageOf(layers.brightEclipse, layers.size),
        loudStreamers = imageOf(layers.loudStreamers, layers.size),
    )

    private fun imageOf(pixels: IntArray, size: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return bitmap.asImageBitmap()
    }
}
