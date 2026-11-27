package com.v2ray.ang.ui.main

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The look of the round connect button on the home card. */
enum class HomeConnectShape {
    /** Not running, or still coming up. */
    Idle,

    /** Running. */
    Active,
}

/**
 * A scalloped ("cookie") outline: a circle whose radius swells and shrinks [lobes] times round, by [depth] of itself.
 * Drawn from this formula with the stable [GenericShape], so that no experimental Material 3 Expressive API and no
 * further dependency is needed.
 */
internal data class CookieSpec(val lobes: Int, val depth: Double)

internal fun cookieSpec(kind: HomeConnectShape): CookieSpec = when (kind) {
    HomeConnectShape.Idle -> CookieSpec(lobes = 9, depth = 0.07)
    HomeConnectShape.Active -> CookieSpec(lobes = 12, depth = 0.09)
}

/** The radius of the outline at [angle], in radians, as a part of the radius of the box: 1 at the tips of the lobes. */
internal fun cookieRadius(spec: CookieSpec, angle: Double): Double =
    (1.0 + spec.depth * cos(spec.lobes * angle)) / (1.0 + spec.depth)

/** [samples] points of the outline round the origin, in the unit circle; enough of them make the outline smooth. */
internal fun cookiePoints(spec: CookieSpec, samples: Int = COOKIE_SAMPLES): List<Pair<Float, Float>> =
    List(samples) { i ->
        val angle = 2 * PI * i / samples
        val r = cookieRadius(spec, angle)
        Pair((r * cos(angle)).toFloat(), (r * sin(angle)).toFloat())
    }

private const val COOKIE_SAMPLES = 360

/**
 * The outline between the idle cookie ([progress] 0) and the running one (1). Their radii are blended at each angle,
 * so that lobes of different counts flow into one another instead of jumping.
 */
internal fun cookieMorphRadius(angle: Double, progress: Float): Double {
    val t = progress.coerceIn(0f, 1f).toDouble()
    return cookieRadius(cookieSpec(HomeConnectShape.Idle), angle) * (1 - t) +
        cookieRadius(cookieSpec(HomeConnectShape.Active), angle) * t
}

private const val MORPH_SAMPLES = 120

// Tables made once, so that a frame of the morph costs only a blend and no trigonometry.
private val idleRadii = DoubleArray(MORPH_SAMPLES) { cookieMorphRadius(2 * PI * it / MORPH_SAMPLES, 0f) }
private val activeRadii = DoubleArray(MORPH_SAMPLES) { cookieMorphRadius(2 * PI * it / MORPH_SAMPLES, 1f) }
private val cosTable = DoubleArray(MORPH_SAMPLES) { cos(2 * PI * it / MORPH_SAMPLES) }
private val sinTable = DoubleArray(MORPH_SAMPLES) { sin(2 * PI * it / MORPH_SAMPLES) }

/**
 * The cookie between the idle ([progress] 0) and the running look (1). [progress] is read when the outline is made, that
 * is while drawing, so animating it invalidates only the drawing and one shape object serves the whole animation.
 */
class MorphShape(private val progress: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = progress().coerceIn(0f, 1f).toDouble()
        val radius = min(size.width, size.height) / 2f
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val path = Path()
        for (i in 0 until MORPH_SAMPLES) {
            val r = (idleRadii[i] * (1 - t) + activeRadii[i] * t) * radius
            val px = centerX + (r * cosTable[i]).toFloat()
            val py = centerY + (r * sinTable[i]).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return Outline.Generic(path)
    }
}
