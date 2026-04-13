package com.ued.universaltvremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── UED Logo-inspired color palette ──
// Primary: Gold / Amber tones from the logo's circular border
val GoldPrimary = Color(0xFFD4A843)
val GoldLight = Color(0xFFE8C56A)
val GoldDark = Color(0xFFB08930)

// Secondary: Deep Royal Blue from the "SP" element
val RoyalBlue = Color(0xFF1A5DAB)
val RoyalBlueLight = Color(0xFF3A7DD4)
val RoyalBlueDark = Color(0xFF0E3D73)

// Accent: Vibrant Red from the mountain/triangle element
val AccentRed = Color(0xFFE53935)
val AccentRedLight = Color(0xFFFF6659)
val AccentRedDark = Color(0xFFAB000D)

// Backgrounds: Rich dark navy
val NavyBackground = Color(0xFF0B0F1A)
val SurfaceDark = Color(0xFF131829)
val SurfaceVariantDark = Color(0xFF1C2237)

// Text colors
val OnSurface = Color(0xFFF0E6D2)        // Warm off-white (parchment-like, matching gold)
val OnSurfaceMuted = Color(0xFF8A8FA0)

// Status
val ErrorRed = Color(0xFFFF6B6B)
val SuccessGreen = Color(0xFF3FB950)

// Legacy aliases used throughout the app (now mapped to gold theme)
val CyanAccent = GoldPrimary
val PurpleAccent = RoyalBlue

private val DarkColors = darkColorScheme(
    primary = GoldPrimary,
    onPrimary = NavyBackground,
    primaryContainer = GoldDark.copy(alpha = 0.3f),
    onPrimaryContainer = GoldLight,
    secondary = RoyalBlue,
    onSecondary = Color.White,
    secondaryContainer = RoyalBlueDark.copy(alpha = 0.4f),
    onSecondaryContainer = RoyalBlueLight,
    tertiary = AccentRed,
    onTertiary = Color.White,
    background = NavyBackground,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Color(0xFF2A3045),
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun UniversalTvRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content
    )
}