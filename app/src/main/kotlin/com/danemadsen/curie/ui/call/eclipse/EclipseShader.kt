package com.danemadsen.curie.ui.call.eclipse

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size

/** The compiled eclipse shader; [update] sets its uniforms for one frame before it is drawn. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EclipseShader private constructor(val runtimeShader: RuntimeShader) {
    /** Points the shader at [frame], drawn into bounds of [size] pixels. */
    fun update(size: Size, frame: EclipseFrame) {
        val phases = CoronaShape.phasesAt(frame.driftSeconds)
        val streamerPhases = phases.streamers
        val flow = CoronaShape.flowAt(frame.shimmerSeconds)
        val beadFlicker = CoronaShape.beadFlickerAt(frame.shimmerSeconds)
        val gains = frame.gains

        runtimeShader.setFloatUniform(EclipseShaderSource.SIZE, size.width, size.height)
        runtimeShader.setFloatUniform(
            EclipseShaderSource.STREAMER_PHASES,
            streamerPhases.x,
            streamerPhases.y,
            streamerPhases.z,
            streamerPhases.w,
        )
        runtimeShader.setFloatUniform(
            EclipseShaderSource.SHAPE_PHASES,
            phases.bend1,
            phases.bend2,
            phases.unevenness1,
            phases.unevenness2,
        )
        runtimeShader.setFloatUniform(EclipseShaderSource.FLOW, flow.plumes, flow.fine, flow.finer, flow.finest)
        runtimeShader.setFloatUniform(
            EclipseShaderSource.GAINS,
            gains.inner,
            gains.streamers,
            gains.reach,
            gains.exposure,
        )
        runtimeShader.setFloatUniform(EclipseShaderSource.GLINT, frame.glintAngle, frame.glintStrength)
        runtimeShader.setFloatUniform(
            EclipseShaderSource.BEAD_FLICKER,
            beadFlicker.main,
            beadFlicker.second,
            beadFlicker.third,
        )
        runtimeShader.setFloatUniform(EclipseShaderSource.TWINKLE, frame.twinklePhase)
    }

    companion object {
        private const val TAG = "EclipseShader"

        /** Compiles the shader, or returns null (and logs why) when this device's AGSL compiler rejects it. */
        fun compileOrNull(): EclipseShader? = try {
            val runtimeShader = RuntimeShader(EclipseShaderSource.SOURCE)
            EclipseShader(runtimeShader)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "The eclipse shader did not compile, so the bitmap fallback draws the eclipse", error)
            null
        }
    }
}
