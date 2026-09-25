// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightPalette = lightColorScheme(
    primary = Color(0xFF355D50), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE2), onPrimaryContainer = Color(0xFF17392D),
    secondary = Color(0xFF626B5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EBDF), onSecondaryContainer = Color(0xFF354131),
    tertiary = Color(0xFF86682D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7EAC5), onTertiaryContainer = Color(0xFF5C471E),
    background = Color(0xFFF7F8F3), onBackground = Color(0xFF202B25),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF202B25),
    surfaceVariant = Color(0xFFE7EBE3), onSurfaceVariant = Color(0xFF626B62),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3EC),
    surfaceContainer = Color(0xFFECEFE7), surfaceContainerHigh = Color(0xFFE5E9E0),
    surfaceContainerHighest = Color(0xFFDDE3D8),
    outline = Color(0xFF7C887B), outlineVariant = Color(0xFFDCE2D7),
    error = Color(0xFFA24336), onError = Color.White,
    errorContainer = Color(0xFFFBE1DB), onErrorContainer = Color(0xFF772D23),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFA9D2BB), onPrimary = Color(0xFF153B2B),
    primaryContainer = Color(0xFF2B4D3D), onPrimaryContainer = Color(0xFFD0EAD9),
    secondary = Color(0xFFBBC6B4), onSecondary = Color(0xFF283424),
    secondaryContainer = Color(0xFF3C4936), onSecondaryContainer = Color(0xFFDEE8D6),
    tertiary = Color(0xFFE7CE92), onTertiary = Color(0xFF453514),
    tertiaryContainer = Color(0xFF59451F), onTertiaryContainer = Color(0xFFF6E5BB),
    background = Color(0xFF121A16), onBackground = Color(0xFFE5EBE2),
    surface = Color(0xFF18211B), onSurface = Color(0xFFE5EBE2),
    surfaceVariant = Color(0xFF344037), onSurfaceVariant = Color(0xFFB6C1B5),
    surfaceContainerLowest = Color(0xFF0E1510), surfaceContainerLow = Color(0xFF1C261F),
    surfaceContainer = Color(0xFF232E26), surfaceContainerHigh = Color(0xFF2A362D),
    surfaceContainerHighest = Color(0xFF334035),
    outline = Color(0xFF859582), outlineVariant = Color(0xFF3D4B3F),
    error = Color(0xFFFFB4A5), onError = Color(0xFF601D14),
    errorContainer = Color(0xFF743529), onErrorContainer = Color(0xFFFFDAD1),
)
private fun heading(size: Int, height: Int) = TextStyle(fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = height.sp, letterSpacing = (-0.4).sp)
private val MemoroTypography = Typography(
    displaySmall = heading(36, 42), headlineLarge = heading(30, 38),
    headlineMedium = heading(26, 34), headlineSmall = heading(24, 32),
    titleLarge = heading(22, 29), titleMedium = heading(17, 24),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun MemoroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkPalette else LightPalette,
        typography = MemoroTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
