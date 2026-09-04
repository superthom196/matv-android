package io.github.superthom196.hifitv.ui

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
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF15151C)
    val SurfaceHigh = Color(0xFF1F1F29)
    val Accent = Color(0xFFF2A93B)
    val OnAccent = Color(0xFF1A1200)
    val Text = Color(0xFFF4F1EA)
    val Muted = Color(0xFF9A9AA8)
    val Focus = Color(0xFFFFFFFF)
    val Danger = Color(0xFFE5533D)
    val Good = Color(0xFF5BC18A)
}

/** Ten-foot typography: nothing under 18sp, generous line heights. */
private val tvTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
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
