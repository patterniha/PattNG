package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MASK_COLUMNS = 180
private const val MASK_ROWS = 73
private const val MASK_TOP_LATITUDE = 85.0
private const val MASK_LATITUDE_SPAN = 146.0

/** The land of the world as dots, one bit per cell (public-domain country outlines, drawn once into [R.raw.world_mask]). */
@Composable
private fun rememberWorldMask(): BooleanArray? {
    val context = LocalContext.current
    val mask by produceState<BooleanArray?>(initialValue = cachedWorldMask) {
        // Decoded once for the process: the home screen comes and goes, the land does not change.
        value = cachedWorldMask ?: withContext(Dispatchers.Default) {
            runCatching {
                val bytes = context.applicationContext.resources.openRawResource(R.raw.world_mask).use { it.readBytes() }
                BooleanArray(MASK_COLUMNS * MASK_ROWS) { i -> (bytes[i / 8].toInt() shr (7 - i % 8)) and 1 == 1 }
            }.getOrNull()
        }?.also { cachedWorldMask = it }
    }
    return mask
}

@Volatile private var cachedWorldMask: BooleanArray? = null

private fun countryName(code: String?): String? =
    code?.let { runCatching { Locale.Builder().setRegion(it).build().displayCountry }.getOrNull() }?.takeIf { it.isNotBlank() }

/**
 * A dotted world map with the place the user is in, and, once connected, the place the connection comes out and the
 * arc between them. Only the picture is here: the places are given, and nothing on it animates.
 */
@Composable
fun HomeMapCard(
    realCountryCode: String?,
    exitCountryCode: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val mask = rememberWorldMask()
    val real = remember(realCountryCode) { countryCentroid(realCountryCode) }
    val exit = remember(exitCountryCode) { countryCentroid(exitCountryCode) }
    val realName = countryName(realCountryCode)
    val exitName = countryName(exitCountryCode)
    val land = scheme.onSurface.copy(alpha = 0.20f)
    val realColor = scheme.onSurfaceVariant
    val exitColor = scheme.primary
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .drawWithCache {
                        val columnWidth = size.width / MASK_COLUMNS
                        // The map is as wide as the card and is held in the middle of its height.
                        val mapHeight = columnWidth * MASK_ROWS * (MASK_LATITUDE_SPAN / (MASK_ROWS * 2.0)).toFloat()
                        val top = (size.height - mapHeight) / 2f
                        fun project(p: GeoPoint) = Offset(
                            ((p.longitude + 180.0) / 360.0 * size.width).toFloat(),
                            (top + (MASK_TOP_LATITUDE - p.latitude) / MASK_LATITUDE_SPAN * mapHeight).toFloat(),
                        )
                        val dots = ArrayList<Offset>()
                        if (mask != null) {
                            val rowHeight = mapHeight / MASK_ROWS
                            for (i in mask.indices) {
                                if (mask[i]) {
                                    dots.add(Offset((i % MASK_COLUMNS + 0.5f) * columnWidth, top + (i / MASK_COLUMNS + 0.5f) * rowHeight))
                                }
                            }
                        }
                        val points = dots.toList()
                        val a = real?.let { project(it) }
                        val b = exit?.let { project(it) }
                        val arc = if (a != null && b != null && a != b) {
                            Path().apply {
                                moveTo(a.x, a.y)
                                quadraticTo((a.x + b.x) / 2f, minOf(a.y, b.y) - size.height * 0.25f, b.x, b.y)
                            }
                        } else {
                            null
                        }
                        onDrawBehind {
                            drawPoints(points, PointMode.Points, land, columnWidth * 0.62f, StrokeCap.Round)
                            if (arc != null) drawPath(arc, exitColor.copy(alpha = 0.85f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                            if (a != null) {
                                drawCircle(realColor.copy(alpha = 0.25f), 12.dp.toPx(), a)
                                drawCircle(realColor, 5.dp.toPx(), a)
                            }
                            if (b != null) {
                                drawCircle(exitColor.copy(alpha = 0.25f), 14.dp.toPx(), b)
                                drawCircle(exitColor, 6.dp.toPx(), b)
                            }
                        }
                    },
            )
            // The route, as in a label on the map: where the user is, and where the connection comes out.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (realName != null) RouteChip(realName, filled = false)
                if (realName != null && exitName != null) {
                    Text(
                        // The arrow points the way the route is read: left to right in LTR, right to left in RTL.
                        text = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "\u2190" else "\u2192",
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (exitName != null) RouteChip(exitName, filled = true)
            }
        }
    }
}

@Composable
private fun RouteChip(text: String, filled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (filled) scheme.primary else scheme.surfaceContainerHighest,
        contentColor = if (filled) scheme.onPrimary else scheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
