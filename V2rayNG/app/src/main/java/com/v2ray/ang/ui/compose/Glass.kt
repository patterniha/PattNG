package com.v2ray.ang.ui.compose

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How the cards of the Home screen are drawn. Glass is a cheap look: no blur, no layers, nothing drawn per frame. */
enum class CardStyle(val key: String) {
    Classic("classic"),
    Glass("glass");

    companion object {
        fun of(key: String?): CardStyle = entries.firstOrNull { it.key == key } ?: Classic
    }
}

val LocalCardStyle = staticCompositionLocalOf { CardStyle.Classic }

/** A glossy sheen and a light-to-accent rim over the content, built once per size. */
fun Modifier.glassRim(corner: Dp, accent: Color): Modifier = drawWithCache {
    val radius = CornerRadius(corner.toPx())
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), endY = size.height * 0.45f)
    val rim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), accent.copy(alpha = 0.7f)),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val stroke = Stroke(width = 1.5.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawRoundRect(brush = sheen, cornerRadius = radius)
        drawRoundRect(brush = rim, cornerRadius = radius, style = stroke)
    }
}

/** Two soft static corner waves behind the screen; mirrored in right-to-left layouts. */
fun Modifier.glassBlobs(first: Color, second: Color): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val flip = layoutDirection == LayoutDirection.Rtl
    fun x(v: Float) = if (flip) w - v else v
    val top = Path().apply {
        moveTo(x(w * 0.55f), 0f)
        cubicTo(x(w * 0.7f), h * 0.04f, x(w * 0.78f), h * 0.12f, x(w), h * 0.16f)
        lineTo(x(w), 0f)
        close()
    }
    val bottom = Path().apply {
        moveTo(x(0f), h * 0.88f)
        cubicTo(x(w * 0.25f), h * 0.9f, x(w * 0.4f), h * 0.96f, x(w * 0.6f), h)
        lineTo(x(0f), h)
        close()
    }
    val topBrush = Brush.linearGradient(
        listOf(first.copy(alpha = 0.30f), first.copy(alpha = 0.04f)),
        start = Offset(x(w), 0f), end = Offset(x(w * 0.5f), h * 0.16f),
    )
    val bottomBrush = Brush.linearGradient(
        listOf(second.copy(alpha = 0.30f), second.copy(alpha = 0.04f)),
        start = Offset(x(0f), h), end = Offset(x(w * 0.5f), h * 0.88f),
    )
    onDrawBehind {
        drawPath(top, topBrush)
        drawPath(bottom, bottomBrush)
    }
}
