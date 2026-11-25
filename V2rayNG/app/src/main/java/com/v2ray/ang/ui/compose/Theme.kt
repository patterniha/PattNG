package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppNotificationManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val DefaultTypography = Typography()

private val VazirmatnFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, weight = FontWeight.Normal),
    Font(R.font.vazirmatn_bold, weight = FontWeight.Bold),
)

private val VazirmatnTypography = DefaultTypography.run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = VazirmatnFontFamily),
        displayMedium = displayMedium.copy(fontFamily = VazirmatnFontFamily),
        displaySmall = displaySmall.copy(fontFamily = VazirmatnFontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = VazirmatnFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = VazirmatnFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = VazirmatnFontFamily),
        titleLarge = titleLarge.copy(fontFamily = VazirmatnFontFamily),
        titleMedium = titleMedium.copy(fontFamily = VazirmatnFontFamily),
        titleSmall = titleSmall.copy(fontFamily = VazirmatnFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = VazirmatnFontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = VazirmatnFontFamily),
        bodySmall = bodySmall.copy(fontFamily = VazirmatnFontFamily),
        labelLarge = labelLarge.copy(fontFamily = VazirmatnFontFamily),
        labelMedium = labelMedium.copy(fontFamily = VazirmatnFontFamily),
        labelSmall = labelSmall.copy(fontFamily = VazirmatnFontFamily),
    )
}

// PattNG palette (the fallback when dynamic color is off, which is the default): firouzeh turquoise-green from the
// green of the app icon, lapis-teal as the second color and saffron as the accent, on soft green-tinted surfaces.
private val LightColor = lightColorScheme(
    primary = Color(0xFF006B55), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8FE3C4), onPrimaryContainer = Color(0xFF00382B),
    secondary = Color(0xFF2F6B7A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFE6F0), onSecondaryContainer = Color(0xFF001F27),
    tertiary = Color(0xFF8A5A00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDA3), onTertiaryContainer = Color(0xFF2C1A00),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBF8), onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF7FBF8), onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF), onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975), outlineVariant = Color(0xFFBFC9C4),
    inverseSurface = Color(0xFF2C322F), inverseOnSurface = Color(0xFFEDF2EE),
    inversePrimary = Color(0xFF6DDBB8), scrim = Color(0xFF000000), surfaceTint = Color(0xFF006B55),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F6F3),
    surfaceContainer = Color(0xFFEBF0ED), surfaceContainerHigh = Color(0xFFE5EAE7),
    surfaceContainerHighest = Color(0xFFDFE4E1),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF6DDBB8), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF0B5A47), onPrimaryContainer = Color(0xFFA8F0D8),
    secondary = Color(0xFF8FCFE0), onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF164F5C), onSecondaryContainer = Color(0xFFB8EAF6),
    tertiary = Color(0xFFFFC857), onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5F4100), onTertiaryContainer = Color(0xFFFFDDA3),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1512), onBackground = Color(0xFFDDE4E0),
    surface = Color(0xFF0E1512), onSurface = Color(0xFFDDE4E0),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938F), outlineVariant = Color(0xFF3F4945),
    inverseSurface = Color(0xFFDDE4E0), inverseOnSurface = Color(0xFF2C322F),
    inversePrimary = Color(0xFF006B55), scrim = Color(0xFF000000), surfaceTint = Color(0xFF6DDBB8),
    surfaceContainerLowest = Color(0xFF090F0D), surfaceContainerLow = Color(0xFF161D1A),
    surfaceContainer = Color(0xFF1A211E), surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF2F3633),
)

// Color palettes selectable in the settings. "0" is the default PattNG palette above.
// Mocha: Mocha Brown #87624A, Platinum White #DDE3E8, Pale Lavender #E8D0F2.
private val MochaLight = lightColorScheme(
    primary = Color(0xFF87624A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF4DFD0), onPrimaryContainer = Color(0xFF301A0B),
    secondary = Color(0xFF5C6670), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE3E8), onSecondaryContainer = Color(0xFF171E24),
    tertiary = Color(0xFF7C5C8A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8D0F2), onTertiaryContainer = Color(0xFF2E1539),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF211A16),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF211A16),
    surfaceVariant = Color(0xFFF1DFD5), onSurfaceVariant = Color(0xFF50443D),
    outline = Color(0xFF82746B), outlineVariant = Color(0xFFD4C3B9),
    inverseSurface = Color(0xFF362F2B), inverseOnSurface = Color(0xFFFBEEE8),
    inversePrimary = Color(0xFFE8BDA3), scrim = Color(0xFF000000), surfaceTint = Color(0xFF87624A),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBF1EC),
    surfaceContainer = Color(0xFFF6EBE5), surfaceContainerHigh = Color(0xFFF0E5DF),
    surfaceContainerHighest = Color(0xFFEADFD9),
)

private val MochaDark = darkColorScheme(
    primary = Color(0xFFE8D0F2), onPrimary = Color(0xFF3A2446),
    primaryContainer = Color(0xFF87624A), onPrimaryContainer = Color(0xFFFFE9DA),
    secondary = Color(0xFFDDE3E8), onSecondary = Color(0xFF2B3237),
    secondaryContainer = Color(0xFF4C3E36), onSecondaryContainer = Color(0xFFF1DFD5),
    tertiary = Color(0xFFE8BDA3), onTertiary = Color(0xFF45290F),
    tertiaryContainer = Color(0xFF63401F), onTertiaryContainer = Color(0xFFFFDCC4),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1D1310), onBackground = Color(0xFFEDE0DA),
    surface = Color(0xFF1D1310), onSurface = Color(0xFFEDE0DA),
    surfaceVariant = Color(0xFF50443D), onSurfaceVariant = Color(0xFFD4C3B9),
    outline = Color(0xFF9C8D84), outlineVariant = Color(0xFF50443D),
    inverseSurface = Color(0xFFEDE0DA), inverseOnSurface = Color(0xFF362F2B),
    inversePrimary = Color(0xFF87624A), scrim = Color(0xFF000000), surfaceTint = Color(0xFFE8D0F2),
    surfaceContainerLowest = Color(0xFF160D0A), surfaceContainerLow = Color(0xFF261B17),
    surfaceContainer = Color(0xFF2B1F1B), surfaceContainerHigh = Color(0xFF362A25),
    surfaceContainerHighest = Color(0xFF41342F),
)

// Canyon: Ice Blue #D3E1F0, Deep Purple #4A0F3D, Canyon Gold #F4A300.
private val CanyonLight = lightColorScheme(
    primary = Color(0xFF4A0F3D), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6D7EC), onPrimaryContainer = Color(0xFF330028),
    secondary = Color(0xFF8A5A00), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDDA3), onSecondaryContainer = Color(0xFF2C1A00),
    tertiary = Color(0xFF3E5F85), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E1F0), onTertiaryContainer = Color(0xFF0A1D31),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF7FB), onBackground = Color(0xFF211820),
    surface = Color(0xFFFFF7FB), onSurface = Color(0xFF211820),
    surfaceVariant = Color(0xFFEFDDE9), onSurfaceVariant = Color(0xFF4E4350),
    outline = Color(0xFF80737F), outlineVariant = Color(0xFFD2C1CE),
    inverseSurface = Color(0xFF362D35), inverseOnSurface = Color(0xFFFBEDF7),
    inversePrimary = Color(0xFFF2B0E2), scrim = Color(0xFF000000), surfaceTint = Color(0xFF4A0F3D),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFBEFF6),
    surfaceContainer = Color(0xFFF6E9F1), surfaceContainerHigh = Color(0xFFF0E3EB),
    surfaceContainerHighest = Color(0xFFEAD8E6),
)

private val CanyonDark = darkColorScheme(
    primary = Color(0xFFD3E1F0), onPrimary = Color(0xFF1A2E44),
    primaryContainer = Color(0xFF5C1D4D), onPrimaryContainer = Color(0xFFFFD8F0),
    secondary = Color(0xFFF4A300), onSecondary = Color(0xFF3B2600),
    secondaryContainer = Color(0xFF6B4A00), onSecondaryContainer = Color(0xFFFFDDA3),
    tertiary = Color(0xFFB9CDE6), onTertiary = Color(0xFF1E3248),
    tertiaryContainer = Color(0xFF354B66), onTertiaryContainer = Color(0xFFD3E1F0),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1B0A18), onBackground = Color(0xFFEFDFEA),
    surface = Color(0xFF1B0A18), onSurface = Color(0xFFEFDFEA),
    surfaceVariant = Color(0xFF4E4350), onSurfaceVariant = Color(0xFFD2C1CE),
    outline = Color(0xFF9A8B98), outlineVariant = Color(0xFF4E4350),
    inverseSurface = Color(0xFFEFDFEA), inverseOnSurface = Color(0xFF362D35),
    inversePrimary = Color(0xFF4A0F3D), scrim = Color(0xFF000000), surfaceTint = Color(0xFFD3E1F0),
    surfaceContainerLowest = Color(0xFF130610), surfaceContainerLow = Color(0xFF25121F),
    surfaceContainer = Color(0xFF2A1724), surfaceContainerHigh = Color(0xFF35212F),
    surfaceContainerHighest = Color(0xFF402C3A),
)

// Wheat: Pastel Sage Gray #A7A594, Wheat Beige #E9D2B8, Deep Eggplant #6A0D12.
private val WheatLight = lightColorScheme(
    primary = Color(0xFF6A0D12), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9D2B8), onPrimaryContainer = Color(0xFF2B0507),
    secondary = Color(0xFF65634F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E1CF), onSecondaryContainer = Color(0xFF1C1B10),
    tertiary = Color(0xFF7A5A2B), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6DDB9), onTertiaryContainer = Color(0xFF2A1800),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF9F2), onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFF9F2), onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEBE1D3), onSurfaceVariant = Color(0xFF4B463D),
    outline = Color(0xFF7C766B), outlineVariant = Color(0xFFCEC5B8),
    inverseSurface = Color(0xFF34302A), inverseOnSurface = Color(0xFFF8EFE6),
    inversePrimary = Color(0xFFFFB3AE), scrim = Color(0xFF000000), surfaceTint = Color(0xFF6A0D12),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAF1E8),
    surfaceContainer = Color(0xFFF4EBE2), surfaceContainerHigh = Color(0xFFEEE5DC),
    surfaceContainerHighest = Color(0xFFE8DFD6),
)

private val WheatDark = darkColorScheme(
    primary = Color(0xFFE9D2B8), onPrimary = Color(0xFF3B2A14),
    primaryContainer = Color(0xFF6A0D12), onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFA7A594), onSecondary = Color(0xFF2F2F22),
    secondaryContainer = Color(0xFF4A4A3A), onSecondaryContainer = Color(0xFFE3E1CF),
    tertiary = Color(0xFFE9D2B8), onTertiary = Color(0xFF3B2A14),
    tertiaryContainer = Color(0xFF5A4326), onTertiaryContainer = Color(0xFFF6DDB9),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1411), onBackground = Color(0xFFEBE1D6),
    surface = Color(0xFF1A1411), onSurface = Color(0xFFEBE1D6),
    surfaceVariant = Color(0xFF4B463D), onSurfaceVariant = Color(0xFFCEC5B8),
    outline = Color(0xFF979082), outlineVariant = Color(0xFF4B463D),
    inverseSurface = Color(0xFFEBE1D6), inverseOnSurface = Color(0xFF34302A),
    inversePrimary = Color(0xFF6A0D12), scrim = Color(0xFF000000), surfaceTint = Color(0xFFE9D2B8),
    surfaceContainerLowest = Color(0xFF130E0B), surfaceContainerLow = Color(0xFF231C18),
    surfaceContainer = Color(0xFF281F1B), surfaceContainerHigh = Color(0xFF322925),
    surfaceContainerHighest = Color(0xFF3D3430),
)

// Cobalt: Midnight Black #030407, Electric Cobalt Blue #1558C9, Acid Lime #D4FF24.
private val CobaltLight = lightColorScheme(
    primary = Color(0xFF1558C9), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E5FF), onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF566500), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4FF24), onSecondaryContainer = Color(0xFF1A2000),
    tertiary = Color(0xFF3F4F00), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6F78A), onTertiaryContainer = Color(0xFF141B00),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
    onError = Color(0xFFFFFFFF), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFF), onBackground = Color(0xFF191C22),
    surface = Color(0xFFF8FAFF), onSurface = Color(0xFF191C22),
    surfaceVariant = Color(0xFFDFE3F0), onSurfaceVariant = Color(0xFF424652),
    outline = Color(0xFF727683), outlineVariant = Color(0xFFC2C6D4),
    inverseSurface = Color(0xFF2E3037), inverseOnSurface = Color(0xFFF0F0F9),
    inversePrimary = Color(0xFFB0C6FF), scrim = Color(0xFF000000), surfaceTint = Color(0xFF1558C9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEDF4), surfaceContainerHigh = Color(0xFFE6E8EF),
    surfaceContainerHighest = Color(0xFFE1E2E9),
)

private val CobaltDark = darkColorScheme(
    primary = Color(0xFFD4FF24), onPrimary = Color(0xFF263300),
    primaryContainer = Color(0xFF1558C9), onPrimaryContainer = Color(0xFFE0EBFF),
    secondary = Color(0xFF9DBBF5), onSecondary = Color(0xFF05295F),
    secondaryContainer = Color(0xFF1C3A6E), onSecondaryContainer = Color(0xFFD9E5FF),
    tertiary = Color(0xFFD4FF24), onTertiary = Color(0xFF263300),
    tertiaryContainer = Color(0xFF3F4F00), onTertiaryContainer = Color(0xFFE6F78A),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF030407), onBackground = Color(0xFFE2E4EC),
    surface = Color(0xFF030407), onSurface = Color(0xFFE2E4EC),
    surfaceVariant = Color(0xFF2A2E3A), onSurfaceVariant = Color(0xFFC2C6D4),
    outline = Color(0xFF8C909E), outlineVariant = Color(0xFF2A2E3A),
    inverseSurface = Color(0xFFE2E4EC), inverseOnSurface = Color(0xFF2E3037),
    inversePrimary = Color(0xFF1558C9), scrim = Color(0xFF000000), surfaceTint = Color(0xFFD4FF24),
    surfaceContainerLowest = Color(0xFF000002), surfaceContainerLow = Color(0xFF0C0E14),
    surfaceContainer = Color(0xFF11131A), surfaceContainerHigh = Color(0xFF191C24),
    surfaceContainerHighest = Color(0xFF22252E),
)

private fun paletteScheme(palette: String, dark: Boolean): ColorScheme = when (palette) {
    "1" -> if (dark) MochaDark else MochaLight
    "2" -> if (dark) CanyonDark else CanyonLight
    "3" -> if (dark) WheatDark else WheatLight
    "4" -> if (dark) CobaltDark else CobaltLight
    else -> if (dark) DarkColor else LightColor
}

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = Color(0xFFf97910) // Orange
val colorFabActive = Color(0xFFf97910) // Orange
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    private val _palette = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_COLOR_PALETTE, "0") ?: "0"
    )
    val palette: StateFlow<String> = _palette.asStateFlow()

    private val _cardStyle = MutableStateFlow(
        CardStyle.of(MmkvManager.decodeSettingsString(AppConfig.PREF_CARD_STYLE, CardStyle.Classic.key))
    )
    val cardStyle: StateFlow<CardStyle> = _cardStyle.asStateFlow()

    fun setCardStyle(style: CardStyle) {
        MmkvManager.encodeSettings(AppConfig.PREF_CARD_STYLE, style.key)
        _cardStyle.value = style
    }

    private val _homeStyle = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_HOME_STYLE, "classic") ?: "classic"
    )
    /** "classic" is the centered card, "dashboard" the server card with the slide control. */
    val homeStyle: StateFlow<String> = _homeStyle.asStateFlow()

    fun setHomeStyle(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_HOME_STYLE, value)
        _homeStyle.value = value
    }

    fun setPalette(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_COLOR_PALETTE, value)
        _palette.value = value
    }

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
        _palette.value = MmkvManager.decodeSettingsString(AppConfig.PREF_COLOR_PALETTE, "0") ?: "0"
        _cardStyle.value = CardStyle.of(MmkvManager.decodeSettingsString(AppConfig.PREF_CARD_STYLE, CardStyle.Classic.key))
        _homeStyle.value = MmkvManager.decodeSettingsString(AppConfig.PREF_HOME_STYLE, "classic") ?: "classic"
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val palette by ThemeManager.palette.collectAsState()
    val cardStyle by ThemeManager.cardStyle.collectAsState()
    val context = LocalContext.current
    val usePersianFont = LocalConfiguration.current.locales[0].language == "fa"
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> paletteScheme(palette, darkTheme)
    }
    // Read again whenever the screen resumes, so that the switch of the settings applies on return.
    var reduceMotion by remember { mutableStateOf(MotionPolicy.isReduced(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduceMotion = MotionPolicy.isReduced(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalCardStyle provides cardStyle,
        LocalReduceMotion provides reduceMotion,
        LocalAppNotification provides AppNotificationManager
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (usePersianFont) VazirmatnTypography else DefaultTypography,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppNotificationBridge()
                content()
            }
        }
    }
}
