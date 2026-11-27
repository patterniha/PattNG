package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.CardStyle
import com.v2ray.ang.ui.compose.LocalCardStyle
import com.v2ray.ang.ui.compose.LocalReduceMotion
import com.v2ray.ang.ui.compose.glassBlobs
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.glassRim
import com.v2ray.ang.ui.compose.AppTopBar
import kotlinx.coroutines.delay

/**
 * The home screen: the connection card over the selector of the server. The list of servers is a screen of its own,
 * which [onOpenServerList] opens. State and operations belong to [mainViewModel] and [onAction]; the only state here
 * is the ticking of the duration, which is presentation.
 */
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    onMenuClick: () -> Unit,
    onOpenServerList: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val serverName by mainViewModel.selectedServerName.collectAsStateWithLifecycle()
    val status = HomeConnection.cardStatus(uiState)

    // Test the active route once when it reaches Connected; never poll in a loop.
    LaunchedEffect(status, uiState.connectionPingMillis) {
        if (status == HomeCardStatus.Connected && uiState.connectionPingMillis == null) {
            onAction(MainAction.TestCurrentServer)
        }
    }

    val since = uiState.connectedSinceNanos
    // The clock ticks only while the screen is shown; in the background nothing wakes up each second.
    val lifecycleOwner = LocalLifecycleOwner.current
    val durationState = produceState(initialValue = HomeConnection.formatDuration(0), since, lifecycleOwner) {
        if (since == null) {
            value = HomeConnection.formatDuration(0)
            return@produceState
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = HomeConnection.formatDuration((System.nanoTime() - since) / NANOS_PER_SECOND)
                delay(1_000)
            }
        }
    }

    // Read by the text that shows it, not here: this screen does not recompose at each tick.
    val durationText = remember(durationState) { { durationState.value } }

    val model = HomeCardModel(
        status = status,
        serverName = serverName,
        details = if (status == HomeCardStatus.Connected) {
            HomeCardDetails(
                pingMillis = uiState.connectionPingMillis?.toInt(),
                duration = durationText,
                ipAddress = uiState.connectionIpAddress,
                countryCode = uiState.connectionCountryCode,
            )
        } else {
            null
        },
        cancellable = uiState.isRunning,
    )

    val glass = LocalCardStyle.current == CardStyle.Glass
    val blobs = glass && !LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        // The corner waves lie behind the bar too, so the bar must not paint over them.
        modifier = if (blobs) Modifier.background(scheme.background).glassBlobs(scheme.primary, scheme.tertiary) else Modifier,
        containerColor = if (blobs) Color.Transparent else scheme.background,
        topBar = {
            AppTopBar(
                containerColor = if (blobs) Color.Transparent else Color.Unspecified,
                title = stringResource(R.string.app_name),
                onBackClick = {},
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painterResource(R.drawable.ic_menu_24dp),
                            contentDescription = stringResource(R.string.acc_open_menu),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dashboard = ThemeManager.homeStyle.collectAsStateWithLifecycle().value == "dashboard"
            if (dashboard) {
                HomeDashboard(
                    model = model,
                    onServerClick = onOpenServerList,
                    // Only the user starts the service: a failed or dropped connection is not retried by itself.
                    onConnectClick = { if (status == HomeCardStatus.Empty) onOpenServerList() else onAction(MainAction.ToggleService) },
                    onRefreshPingClick = { onAction(MainAction.TestCurrentServer) },
                    realCountryCode = uiState.realCountryCode,
                    modifier = Modifier.weight(1f),
                )
            } else HomeConnectCard(
                model = model,
                onConnectClick = { onAction(MainAction.ToggleService) },
                onStatusActionClick = {
                    // Only the user starts the service: a failed or dropped connection is not retried by itself.
                    if (status == HomeCardStatus.Empty) onOpenServerList() else onAction(MainAction.ToggleService)
                },
                onRefreshPingClick = { onAction(MainAction.TestCurrentServer) },
                modifier = Modifier.weight(1f),
            )
            // The card already names the server and the pill opens the list; a short screen keeps its room for the card.
            if (!dashboard && LocalConfiguration.current.screenHeightDp >= 700) {
                HomeServerSelector(
                    serverName = serverName,
                    onClick = onOpenServerList,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            HomeQuickActions(
                onOpenServerList = onOpenServerList,
                onNavigate = onNavigate,
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L

/** The fill of a glass surface is a little see-through, so the corner waves show; the text colors stay the same. */
@Composable
private fun glassFill(color: Color): Color =
    if (LocalCardStyle.current == CardStyle.Glass) color.copy(alpha = 0.82f) else color

/** One row that opens the list of servers; it is one semantic node, spoken with the server it would change. */
@Composable
private fun HomeServerSelector(serverName: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = glassFill(MaterialTheme.colorScheme.surfaceContainer),
        // A color with its own alpha is no theme token, so the content color is named, or the text turns dark.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .then(if (LocalCardStyle.current == CardStyle.Glass) Modifier.glassRim(24.dp, MaterialTheme.colorScheme.tertiary) else Modifier)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = serverName ?: stringResource(R.string.home_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.home_change_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/** ZedSecure-style pill: the places one tap away from home. Home is the current one; the others open their screens. */
@Composable
private fun HomeQuickActions(
    onOpenServerList: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = glassFill(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .then(if (LocalCardStyle.current == CardStyle.Glass) Modifier.glassRim(32.dp, MaterialTheme.colorScheme.tertiary) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomePillItem(R.drawable.ic_home_24dp, R.string.home_nav_home, selected = true, onClick = {}, Modifier.weight(1f))
            HomePillItem(R.drawable.ic_dns_24dp, R.string.home_quick_servers, selected = false, onClick = onOpenServerList, Modifier.weight(1f))
            HomePillItem(
                R.drawable.ic_subscriptions_24dp, R.string.home_quick_subscriptions, selected = false,
                onClick = { onNavigate(MainDestination.Subscriptions) }, Modifier.weight(1f),
            )
            HomePillItem(
                R.drawable.ic_settings_24dp, R.string.title_settings, selected = false,
                onClick = { onNavigate(MainDestination.Settings) }, Modifier.weight(1f),
            )
        }
    }
}

/** One tab of the pill: a single focusable node with its label; the selected one is marked by a softly fading chip. */
@Composable
private fun HomePillItem(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chip by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "home-pill-chip",
    )
    val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(chip)
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
