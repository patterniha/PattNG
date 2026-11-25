package com.v2ray.ang.ui.main

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * The dashboard look of the home screen: a card for the current server, the figures of the connection, and a wide
 * control at the bottom that connects when it is slid or tapped. It draws what it is given and owns no state; the
 * user's operations leave through the callbacks, the same ones the classic card uses.
 */
@Composable
fun HomeDashboard(
    model: HomeCardModel,
    onServerClick: () -> Unit,
    onConnectClick: () -> Unit,
    onRefreshPingClick: () -> Unit,
    realCountryCode: String?,
    modifier: Modifier = Modifier,
) {
    val connected = rememberSettledConnected(model.status)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DashboardServerCard(model, onServerClick, modifier = Modifier.widthIn(max = 560.dp))
        // The hero takes what is left, so the screen has no hole: the brand shape stands in the middle, calm.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 330.dp
            val mapHeight = minOf(260.dp, maxHeight * 0.62f).coerceAtLeast(120.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeMapCard(
                    realCountryCode = realCountryCode,
                    exitCountryCode = if (connected) model.details?.countryCode else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .height(mapHeight),
                )
                if (connected && model.details != null) {
                    DashboardDuration(
                        duration = model.details.duration,
                        style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                } else {
                    Text(
                        text = homeStatusText(model.status),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (connected && model.details != null) {
            DashboardPingRow(model.details, onRefreshPingClick, modifier = Modifier.widthIn(max = 560.dp))
        }
        SlideToConnect(
            status = model.status,
            onToggle = onConnectClick,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun DashboardServerCard(model: HomeCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (model.status) {
        HomeCardStatus.Connected -> scheme.primary
        HomeCardStatus.Error -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Box {
            // A faint brand shape in the corner, as a quiet decoration; the card clips it.
            val motifShape = remember { MorphShape { 0f } }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 48.dp, y = (-48).dp)
                    .size(180.dp)
                    .background(scheme.primary.copy(alpha = 0.10f), motifShape),
            )
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DashboardFlag(
                        countryCode = if (model.status == HomeCardStatus.Connected) model.details?.countryCode else null,
                        size = 64.dp,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = model.serverName ?: stringResource(R.string.home_empty_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (model.status == HomeCardStatus.Empty) stringResource(R.string.home_change_server) else homeStatusText(model.status),
                            style = MaterialTheme.typography.titleSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        // The arrow of the back button points away from the next screen; turn it to point at it.
                        modifier = Modifier.graphicsLayer { rotationZ = if (rtl) 0f else 180f },
                    )
                }
                if (rememberSettledConnected(model.status) && model.details != null) {
                    HomeIpDetailTile(
                        label = stringResource(R.string.home_details_ip),
                        ipAddress = model.details.ipAddress?.takeIf { it.isNotBlank() } ?: stringResource(R.string.connection_test_testing),
                        countryCode = model.details.countryCode,
                        plain = true,
                        showFlag = false,
                    )
                }
            }
        }
    }
}

/**
 * The country as a rounded square, the flag cropped to fill it; a country without a picture here shows its emoji flag
 * on a tonal square, and no country at all shows the icon of a server.
 */
@Composable
private fun DashboardFlag(countryCode: String?, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 0.3f)
    val code = normalizeCountryCode(countryCode)
    val emoji = countryFlagEmoji(countryCode)
    val name = remember(code) {
        code?.let { runCatching { java.util.Locale.Builder().setRegion(it).build().displayCountry }.getOrNull() }.orEmpty()
    }
    Box(
        modifier = modifier.size(size).clip(shape).background(scheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        when {
            code == "IR" || flagDrawable(code) != null -> Image(
                painter = painterResource(if (code == "IR") R.drawable.ic_flag_iran_lion_sun else flagDrawable(code)!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            emoji.isNotEmpty() -> Text(
                text = emoji,
                fontSize = (size.value * 0.5f).sp,
                modifier = Modifier.clearAndSetSemantics { contentDescription = name },
            )
            else -> Icon(
                painter = painterResource(R.drawable.ic_dns_24dp),
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** The ping as a chip, and the one action on it; both keep their size when the figure arrives. */
@Composable
private fun DashboardPingRow(details: HomeCardDetails, onRefreshPingClick: () -> Unit, modifier: Modifier = Modifier) {
    val measuring = stringResource(R.string.connection_test_testing)
    val ping = details.pingMillis?.let { stringResource(R.string.server_test_delay_value, it) } ?: measuring
    val label = stringResource(R.string.home_details_ping)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics(mergeDescendants = true) {},
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Text(text = ping, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        }
        FilledTonalIconButton(onClick = onRefreshPingClick, modifier = Modifier.size(64.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh_24dp),
                contentDescription = stringResource(R.string.home_details_test_ping),
            )
        }
    }
}

/**
 * A wide control: the knob is slid to the far end, or the whole control is tapped, to connect or disconnect; the tap
 * is what a screen reader, a keyboard or a switch uses. Nothing here animates except the knob returning to its place,
 * and that is a jump when the animations are reduced.
 */
@Composable
private fun SlideToConnect(status: HomeCardStatus, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val active = status == HomeCardStatus.Connected || status == HomeCardStatus.Connecting
    val label = stringResource(
        when {
            status == HomeCardStatus.Empty -> R.string.home_action_choose_server
            active -> R.string.home_slide_to_disconnect
            else -> R.string.home_slide_to_connect
        }
    )
    val outerDirection = LocalLayoutDirection.current
    val statusText = homeStatusText(status)
    val trackColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onTrack = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val knobColor = MaterialTheme.colorScheme.primary
    val onKnob = MaterialTheme.colorScheme.onPrimary
    val knobSize = 52.dp
    val inset = 8.dp
    val trackHeight = knobSize + inset * 2
    // The knob moves from the left to the right in every language, so that the gesture is the same everywhere.
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = modifier
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(trackColor)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = statusText
                }
                .accessibleToggle(label, onToggle),
        ) {
            val density = LocalDensity.current
            val travel = with(density) { (maxWidth - knobSize - inset * 2).toPx() }.coerceAtLeast(1f)
            var x by remember { mutableFloatStateOf(0f) }
            // A new width can make the old position too far; it is held inside the track when it is drawn.
            val drawn = x.coerceAtMost(travel)
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides outerDirection) {
            Row(
                modifier = Modifier.fillMaxWidth().height(trackHeight).absolutePadding(left = knobSize + inset * 2, right = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = statusText, color = onTrack, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.size(8.dp))
                Text(text = label, color = onTrack.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            }
            Box(
                modifier = Modifier
                    .padding(inset)
                    .offset { androidx.compose.ui.unit.IntOffset(drawn.toInt(), 0) }
                    .size(knobSize)
                    .background(knobColor, CircleShape)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta -> x = (x + delta).coerceIn(0f, travel) },
                        onDragStopped = {
                            val done = x >= travel * 0.7f
                            val from = x
                            if (reduceMotion) {
                                x = 0f
                            } else {
                                scope.launch { animate(from, 0f, animationSpec = spring()) { value, _ -> x = value } }
                            }
                            if (done) onToggle()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(if (active) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp), contentDescription = null, tint = onKnob)
            }
        }
    }
}

/**
 * A plain tap does nothing: the control is slid. A screen reader's double tap, a switch and a keyboard still toggle it,
 * through the action of the semantics and the Enter, Space and D-pad center keys (on key up, so a held key toggles once).
 */
private fun Modifier.accessibleToggle(label: String, onToggle: () -> Unit): Modifier = this
    .onKeyEvent { event ->
        val key = event.key
        val isConfirm = key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter || key == Key.Spacebar
        if (isConfirm && event.type == KeyEventType.KeyUp) {
            onToggle()
            true
        } else {
            isConfirm
        }
    }
    .focusable()
    .semantics {
        role = Role.Button
        onClick(label = label) {
            onToggle()
            true
        }
    }

/** The connection time, read here so that its tick redraws this text and nothing around it. */
@Composable
private fun DashboardDuration(duration: () -> String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Text(text = duration(), style = style, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}
