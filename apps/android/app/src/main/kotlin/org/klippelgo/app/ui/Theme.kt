package org.klippelgo.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.klippelgo.app.R

// Warm greys with ink buttons and one blue accent, as in the wireframes: blue marks locked fares and privacy
// labels. The slots map to the wireframe tokens like this: primary is the ink, secondary the blue text,
// secondaryContainer the blue tag, tertiary the blue of dots and routes, surfaceContainerHighest the map.
private val Light = lightColorScheme(
    primary = Color(0xFF1C1C1C),
    onPrimary = Color.White,
    secondary = Color(0xFF1F4FB0),
    secondaryContainer = Color(0xFFE6ECF8),
    onSecondaryContainer = Color(0xFF1F4FB0),
    tertiary = Color(0xFF1F5FD1),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEF2FA),
    background = Color(0xFFF4F4F2),
    onBackground = Color(0xFF1C1C1C),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFEFEFEC),
    onSurfaceVariant = Color(0xFF5E5E5A),
    surfaceContainerHighest = Color(0xFFE4E4E0),
    outline = Color(0xFFC9C9C4),
    outlineVariant = Color(0xFFD6D6D2),
    error = Color(0xFFA51D1D),
    onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF1F1EE),
    onPrimary = Color(0xFF1C1C1C),
    secondary = Color(0xFF8AB0FF),
    secondaryContainer = Color(0xFF1B2A4A),
    onSecondaryContainer = Color(0xFF8AB0FF),
    tertiary = Color(0xFF5B8DEF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF18233A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF1F1EE),
    surface = Color(0xFF1E1E1D),
    onSurface = Color(0xFFF1F1EE),
    surfaceVariant = Color(0xFF2A2A28),
    onSurfaceVariant = Color(0xFFA9A9A4),
    surfaceContainerHighest = Color(0xFF262624),
    outline = Color(0xFF4A4A47),
    outlineVariant = Color(0xFF3A3A38),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF1C1C1C),
)

val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_medium, FontWeight.Medium),
    Font(R.font.ibmplexsans_semibold, FontWeight.SemiBold),
    Font(R.font.ibmplexsans_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(Font(R.font.ibmplexmono_medium, FontWeight.Medium))

private fun TextStyle.plex() = copy(fontFamily = PlexSans)

private val Type = Typography().run {
    copy(
        displayLarge = displayLarge.plex(),
        displayMedium = displayMedium.plex(),
        displaySmall = displaySmall.plex(),
        headlineLarge = headlineLarge.plex(),
        headlineMedium = headlineMedium.plex(),
        headlineSmall = headlineSmall.plex(),
        titleLarge = titleLarge.plex(),
        titleMedium = titleMedium.plex(),
        titleSmall = titleSmall.plex(),
        bodyLarge = bodyLarge.plex().copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.plex().copy(letterSpacing = 0.sp),
        bodySmall = bodySmall.plex().copy(letterSpacing = 0.sp),
        labelLarge = labelLarge.plex(),
        labelMedium = labelMedium.plex(),
        labelSmall = labelSmall.plex(),
    )
}

@Composable
fun KlippelGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
