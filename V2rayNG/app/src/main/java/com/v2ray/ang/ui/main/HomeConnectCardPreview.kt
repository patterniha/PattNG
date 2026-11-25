package com.v2ray.ang.ui.main

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

// Mock data for the layout slice. The previews use plain MaterialTheme, not AppTheme, because AppTheme reads MMKV,
// which is not initialized in the preview renderer.
private val mockDetails = HomeCardDetails(
    pingMillis = 84,
    duration = { "30:47" },
    ipAddress = "203.0.113.10",
    countryCode = "DE",
    download = "5.8 MB/s",
    upload = "773 KB/s",
)

private class HomeCardModelProvider : PreviewParameterProvider<HomeCardModel> {
    override val values = sequenceOf(
        HomeCardModel(HomeCardStatus.Empty),
        HomeCardModel(HomeCardStatus.Disconnected, "Frankfurt"),
        HomeCardModel(HomeCardStatus.Connecting, "Frankfurt"),
        HomeCardModel(HomeCardStatus.Connected, "Frankfurt", mockDetails),
        HomeCardModel(HomeCardStatus.Error, "Frankfurt"),
        HomeCardModel(HomeCardStatus.DisconnectedUnexpected, "Frankfurt"),
    )
}

@Composable
private fun HomeCardPreviewHost(model: HomeCardModel, dark: Boolean) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HomeConnectCard(model = model, onConnectClick = {}, onStatusActionClick = {}, onRefreshPingClick = {})
        }
    }
}

@Preview(name = "Portrait light", widthDp = 360, heightDp = 800)
@Composable
private fun HomeCardPortraitLight(@PreviewParameter(HomeCardModelProvider::class) model: HomeCardModel) =
    HomeCardPreviewHost(model, dark = false)

@Preview(name = "Portrait dark", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeCardPortraitDark(@PreviewParameter(HomeCardModelProvider::class) model: HomeCardModel) =
    HomeCardPreviewHost(model, dark = true)

@Preview(name = "Landscape light", widthDp = 800, heightDp = 360)
@Composable
private fun HomeCardLandscapeLight(@PreviewParameter(HomeCardModelProvider::class) model: HomeCardModel) =
    HomeCardPreviewHost(model, dark = false)
