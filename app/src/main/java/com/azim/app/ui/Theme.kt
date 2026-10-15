package com.azim.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Sade, ölçülü bir palet: sıcak kırık beyaz zemin, koyu mürekkep metin, sönük deniz yeşili vurgu.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D62),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7E4),
    onPrimaryContainer = Color(0xFF16302F),
    secondary = Color(0xFF5B6770),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E3DB),
    onSecondaryContainer = Color(0xFF2A3036),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1F2933),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1F2933),
    surfaceVariant = Color(0xFFEAE7E0),
    onSurfaceVariant = Color(0xFF4A545C),
    outline = Color(0xFFB8B3A8),
    error = Color(0xFF9B3D3D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF1DEDC),
    onErrorContainer = Color(0xFF4A1C1C)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB3B1),
    onPrimary = Color(0xFF0F2423),
    primaryContainer = Color(0xFF244245),
    onPrimaryContainer = Color(0xFFD3E4E2),
    secondary = Color(0xFFA7B1B8),
    onSecondary = Color(0xFF1B2125),
    secondaryContainer = Color(0xFF2B3338),
    onSecondaryContainer = Color(0xFFDDE3E7),
    background = Color(0xFF14181B),
    onBackground = Color(0xFFE6E8E9),
    surface = Color(0xFF1B2125),
    onSurface = Color(0xFFE6E8E9),
    surfaceVariant = Color(0xFF283035),
    onSurfaceVariant = Color(0xFFB4BCC1),
    outline = Color(0xFF4F5A61),
    error = Color(0xFFD08B87),
    onError = Color(0xFF3B1210),
    errorContainer = Color(0xFF4A2523),
    onErrorContainer = Color(0xFFF3D6D3)
)

private val base = Typography()
private val AzimTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium)
)

private val AzimShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun AzimTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AzimTypography,
        shapes = AzimShapes,
        content = content
    )
}
