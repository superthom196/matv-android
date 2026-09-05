package io.github.superthom196.matv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.text.TextStyle

object HiFiColors {
    val Background = Color(0xFF181818)   // Music Assistant dark background
    val Surface = Color(0xFF232323)      // Music Assistant panel
    val SurfaceHigh = Color(0xFF2E2E2E)
    val Accent = Color(0xFF03A9F4)       // Music Assistant primary
    /** Gold, used only for the hi-res mark so it reads as a quality flag, not another accent. */
    val HiRes = Color(0xFFE8B33C)
    val AccentBright = Color(0xFF4FC3F7)
    val OnAccent = Color(0xFF00202E)
    val Text = Color(0xFFF5F5F5)
    val Muted = Color(0xFF9E9E9E)
    val Focus = Color(0xFFFFFFFF)
    val Danger = Color(0xFFE5533D)
    val Good = Color(0xFF5BC18A)
}

/** Ten-foot typography: big headings, compact body text (a TV grid gets cluttered fast). */
private val tvTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun HiFiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HiFiColors.Accent,
            onPrimary = HiFiColors.OnAccent,
            secondary = HiFiColors.Accent,
            background = HiFiColors.Background,
            onBackground = HiFiColors.Text,
            surface = HiFiColors.Surface,
            onSurface = HiFiColors.Text,
            surfaceVariant = HiFiColors.SurfaceHigh,
            onSurfaceVariant = HiFiColors.Muted,
            border = HiFiColors.Focus,
            error = HiFiColors.Danger,
        ),
        typography = tvTypography,
    ) {
        // tv-material's default content colour outside a Surface is dark; our screens sit on a dark background.
        CompositionLocalProvider(LocalContentColor provides HiFiColors.Text, content = content)
    }
}
