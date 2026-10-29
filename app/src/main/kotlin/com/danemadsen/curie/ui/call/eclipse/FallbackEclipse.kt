package com.danemadsen.curie.ui.call.eclipse

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The eclipse without a runtime shader (Android 10 to 12, or when the shader does not compile): the corona is
 * rendered once on the CPU from the same model as the shader ([FallbackCorona]), then animated cheaply. Its
 * exposure breathes by mixing a dim and a bright rendering, Curie's voice adds the streamers' extra light, it
 * sways a few degrees, and the inner corona's boost, the Baily's beads and the Moon are drawn on top. A plain
 * gradient eclipse shows while the corona renders, then fades into it.
 *
 * With [renderSynchronously] (previews) the corona is rendered small, straight away, on the calling thread.
 */
@Composable
internal fun FallbackEclipse(frame: State<EclipseFrame>, renderSynchronously: Boolean, modifier: Modifier = Modifier) {
    val corona = rememberFallbackCorona(renderSynchronously)
    val reveal = rememberReveal(corona)

    // Nothing is drawn outside the circle inscribed in the bounds; the clip only guards against mistakes.
    val drawModifier = modifier
        .clipToBounds()
        .drawWithCache {
            val geometry = EclipseGeometry(size)
            val brushes = FallbackBrushes(geometry.moonRadius)

            onDrawBehind {
                val current = frame.value
                val renderedCorona = corona.value
                val revealed = reveal.value
                if (renderedCorona != null) {
                    drawCoronaLayers(renderedCorona, geometry, current, alpha = revealed)
                }
                if (revealed < 1f) {
                    drawPlaceholderCorona(geometry, brushes, alpha = 1f - revealed)
                }
                drawInnerBoost(geometry, brushes, current)
                drawBailysBeads(geometry, brushes, current)
                drawMoon(geometry)
            }
        }

    Spacer(modifier = drawModifier)
}

@Composable
private fun rememberFallbackCorona(renderSynchronously: Boolean): State<FallbackCoronaImages?> {
    val initialCorona = remember(renderSynchronously) {
        if (renderSynchronously) {
            FallbackCorona.renderNow(FallbackCorona.PREVIEW_SIZE)
        } else {
            null
        }
    }

    return produceState(initialValue = initialCorona) {
        if (value == null) {
            value = FallbackCorona.render()
        }
    }
}

/** How far the rendered corona has replaced the placeholder, from 0 to 1, fading in once [corona] is ready. */
@Composable
private fun rememberReveal(corona: State<FallbackCoronaImages?>): State<Float> {
    val reveal = remember {
        val initialReveal = if (corona.value != null) 1f else 0f
        Animatable(initialReveal)
    }
    val isRendered = corona.value != null

    LaunchedEffect(isRendered) {
        if (isRendered) {
            val fade = tween<Float>(durationMillis = REVEAL_MILLIS)
            reveal.animateTo(targetValue = 1f, animationSpec = fade)
        }
    }

    return reveal.asState()
}

/** Where the eclipse sits in bounds of [size]: centred, with the Moon sized by the smaller side. */
private class EclipseGeometry(size: Size) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val side = size.minDimension
    val moonRadius = side * CoronaLook.MOON_RADIUS_FRACTION

    /** The point [radius] lunar radii from the centre at [angle] (radians anticlockwise from three o'clock). */
    fun pointAt(angle: Float, radius: Float): Offset {
        val direction = Offset(cos(angle), -sin(angle))
        return center + direction * (radius * moonRadius)
    }
}

/**
 * The rendered corona, swaying a few degrees: the dim and the bright rendering mixed to the frame's exposure,
 * and the streamers' extra light in proportion to how far Curie's voice stretches them. All of it is light
 * added to what lies beneath, so mixing is exact, and [alpha] fades it in over the placeholder.
 */
private fun DrawScope.drawCoronaLayers(
    corona: FallbackCoronaImages,
    geometry: EclipseGeometry,
    frame: EclipseFrame,
    alpha: Float,
) {
    val gains = frame.gains
    val exposureRange = EclipseMotion.HIGHEST_EXPOSURE - EclipseMotion.LOWEST_EXPOSURE
    val brightShare = saturate((gains.exposure - EclipseMotion.LOWEST_EXPOSURE) / exposureRange)
    val loudestReach = EclipseMotion.LOUDEST_VOICE_GAINS.reach
    val streamerShare = saturate((gains.reach - 1f) / (loudestReach - 1f))

    val swayCycles = frame.driftSeconds / CORONA_SWAY_PERIOD_SECONDS
    val swayDegrees = (CORONA_SWAY_DEGREES * sin(2.0 * PI * swayCycles)).toFloat()

    rotate(degrees = swayDegrees, pivot = geometry.center) {
        drawLayer(corona.dimEclipse, geometry, alpha * (1f - brightShare), BlendMode.Plus)
        drawLayer(corona.brightEclipse, geometry, alpha * brightShare, BlendMode.Plus)
        drawLayer(corona.loudStreamers, geometry, alpha * streamerShare, ADD_LIGHT)
    }
}

/** One square layer scaled to the bounds' smaller side and centred, unless it would be invisible. */
private fun DrawScope.drawLayer(image: ImageBitmap, geometry: EclipseGeometry, alpha: Float, blendMode: BlendMode) {
    if (alpha <= 0f) {
        return
    }

    val layerScale = geometry.side / image.width
    val half = image.width / 2f
    withTransform({
        translate(geometry.center.x, geometry.center.y)
        scale(scaleX = layerScale, scaleY = layerScale, pivot = Offset.Zero)
        translate(-half, -half)
    }) {
        drawImage(image = image, alpha = min(alpha, 1f), blendMode = blendMode)
    }
}

/** A soft gradient eclipse, shown for the moment it takes to render the corona, at [alpha]. */
private fun DrawScope.drawPlaceholderCorona(geometry: EclipseGeometry, brushes: FallbackBrushes, alpha: Float) {
    val radius = geometry.moonRadius * FallbackBrushes.PLACEHOLDER_EXTENT
    translate(geometry.center.x, geometry.center.y) {
        drawCircle(
            brush = brushes.placeholder,
            radius = radius,
            center = Offset.Zero,
            alpha = alpha,
            blendMode = BlendMode.Plus,
        )
    }
}

/** Extra light on the inner corona when it is brighter than at rest (the user's voice). */
private fun DrawScope.drawInnerBoost(geometry: EclipseGeometry, brushes: FallbackBrushes, frame: EclipseFrame) {
    val boost = (frame.gains.inner - 1f) * INNER_BOOST_STRENGTH
    if (boost <= 0f) {
        return
    }

    val radius = geometry.moonRadius * FallbackBrushes.INNER_BOOST_EXTENT
    translate(geometry.center.x, geometry.center.y) {
        drawCircle(
            brush = brushes.innerBoost,
            radius = radius,
            center = Offset.Zero,
            alpha = min(boost, 1f),
            blendMode = ADD_LIGHT,
        )
    }
}

/**
 * The Baily's beads while Curie thinks: three brilliant spots strung along the limb, each shimmering on its
 * own, with glare and diffraction spikes round the main one.
 */
private fun DrawScope.drawBailysBeads(geometry: EclipseGeometry, brushes: FallbackBrushes, frame: EclipseFrame) {
    if (frame.glintStrength <= 0f) {
        return
    }

    val flicker = CoronaShape.beadFlickerAt(frame.shimmerSeconds)
    val mainStrength = min(frame.glintStrength * flicker.main, 1f)
    val mainBead = geometry.pointAt(frame.glintAngle, 1f + CoronaLook.BEAD_LIFT)
    val glareRadius = geometry.moonRadius * CoronaLook.GLINT_REACH

    translate(mainBead.x, mainBead.y) {
        drawCircle(
            brush = brushes.glare,
            radius = glareRadius,
            center = Offset.Zero,
            alpha = mainStrength,
            blendMode = ADD_LIGHT,
        )
    }
    drawSpikes(mainBead, geometry, brushes, mainStrength)

    val secondAngle = frame.glintAngle + CoronaLook.BEAD_OFFSET_2
    val thirdAngle = frame.glintAngle + CoronaLook.BEAD_OFFSET_3
    val secondStrength = frame.glintStrength * flicker.second * CoronaLook.BEAD_WEIGHT_2
    val thirdStrength = frame.glintStrength * flicker.third * CoronaLook.BEAD_WEIGHT_3
    drawBeadSpot(frame.glintAngle, mainStrength, geometry, brushes)
    drawBeadSpot(secondAngle, secondStrength, geometry, brushes)
    drawBeadSpot(thirdAngle, thirdStrength, geometry, brushes)
}

/** The six rays of the three spike lines through [bead]; the Moon, drawn later, covers those pointing inwards. */
private fun DrawScope.drawSpikes(bead: Offset, geometry: EclipseGeometry, brushes: FallbackBrushes, strength: Float) {
    val rayLength = geometry.moonRadius * CoronaLook.GLINT_REACH
    val rayEnd = Offset(rayLength, 0f)

    for (direction in CoronaLook.SPIKE_DIRECTIONS) {
        for (halfTurns in 0..1) {
            // Screen angles turn clockwise, the model's anticlockwise.
            val rayRadians = direction + halfTurns * PI.toFloat()
            val rayDegrees = -rayRadians * DEGREES_PER_RADIAN
            withTransform({
                translate(bead.x, bead.y)
                rotate(degrees = rayDegrees, pivot = Offset.Zero)
            }) {
                drawLine(
                    brush = brushes.spikeRay,
                    start = Offset.Zero,
                    end = rayEnd,
                    strokeWidth = SPIKE_STROKE_PX,
                    alpha = strength,
                    blendMode = ADD_LIGHT,
                )
            }
        }
    }
}

/** One bead at [angle] on the limb: its round profile stretched along the limb. */
private fun DrawScope.drawBeadSpot(angle: Float, strength: Float, geometry: EclipseGeometry, brushes: FallbackBrushes) {
    val center = geometry.pointAt(angle, 1f + CoronaLook.BEAD_LIFT)
    val radius = geometry.moonRadius * FallbackBrushes.BEAD_SPOT_EXTENT
    val outwardDegrees = -angle * DEGREES_PER_RADIAN

    withTransform({
        translate(center.x, center.y)
        rotate(degrees = outwardDegrees, pivot = Offset.Zero)
        scale(scaleX = 1f, scaleY = FallbackBrushes.BEAD_STRETCH, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = brushes.beadSpot,
            radius = radius,
            center = Offset.Zero,
            alpha = min(strength, 1f),
            blendMode = ADD_LIGHT,
        )
    }
}

/** The Moon: an exact black disk. */
private fun DrawScope.drawMoon(geometry: EclipseGeometry) {
    drawCircle(color = Color.Black, radius = geometry.moonRadius, center = geometry.center)
}

/**
 * Light is added with Screen, `1 - (1 - a)(1 - b)`, the display-space twin of the shader's tone mapping
 * `1 - e^-(a + b)`: where the corona is already white it saturates softly instead of clipping into a
 * hard-edged white band.
 */
private val ADD_LIGHT = BlendMode.Screen

/** The rendered corona fades in over the placeholder in this long. */
private const val REVEAL_MILLIS = 300

/** The corona layers sway this many degrees either way, once every two minutes of drift time. */
private const val CORONA_SWAY_DEGREES = 3.0
private const val CORONA_SWAY_PERIOD_SECONDS = 120.0

/** How strongly the inner corona's extra gain shows in the boost layer. */
private const val INNER_BOOST_STRENGTH = 2.5f

/** A spike's line is about as wide as the shader's Gaussian across it is at half its height. */
private const val SPIKE_STROKE_PX = 2f

private const val DEGREES_PER_RADIAN = (180.0 / PI).toFloat()
