package com.brr.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode

object BrrPalette {
    val Red = Color(0xFFFF2D3D)
    val RedDeep = Color(0xFFB0001A)
    val Orange = Color(0xFFFF6A1A)
    val Amber = Color(0xFFFFB020)
    val Blue = Color(0xFF4C8DFF)
    val Green = Color(0xFF2ECC71)
    val Gray = Color(0xFF6B6B75)
}

@Immutable
data class BrrExtraColors(
    val muted: Color,
    val faint: Color,
    val card: Color,
    val cardHigh: Color,
    val hairline: Color,
    val success: Color,
    val isDark: Boolean,
) {
    fun priority(p: Priority): Color = when (p) {
        Priority.URGENT -> BrrPalette.Red
        Priority.HIGH -> BrrPalette.Orange
        Priority.NORMAL -> BrrPalette.Blue
        Priority.LOW -> muted
    }

    fun level(l: ReminderLevel): Color = when (l) {
        ReminderLevel.NORMAL -> BrrPalette.Blue
        ReminderLevel.INSISTANT -> BrrPalette.Amber
        ReminderLevel.HARCELEMENT -> BrrPalette.Orange
        ReminderLevel.BRR -> BrrPalette.Red
    }
}

private val DarkExtras = BrrExtraColors(
    muted = Color(0xFF8B8B94), faint = Color(0xFF55555C), card = Color(0xFF141416), cardHigh = Color(0xFF1C1C20),
    hairline = Color(0xFF26262B), success = BrrPalette.Green, isDark = true,
)
private val LightExtras = BrrExtraColors(
    muted = Color(0xFF6B6B73), faint = Color(0xFFA3A3AA), card = Color(0xFFFFFFFF), cardHigh = Color(0xFFF1F1EF),
    hairline = Color(0xFFE5E5E1), success = Color(0xFF1E9E55), isDark = false,
)

private val DarkScheme = darkColorScheme(
    primary = BrrPalette.Red, onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0A10), onPrimaryContainer = Color(0xFFFFD9DC),
    secondary = BrrPalette.Orange, onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A1A08), onSecondaryContainer = Color(0xFFFFDCC8),
    background = Color(0xFF0A0A0B), onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF0A0A0B), onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF1C1C20), onSurfaceVariant = Color(0xFF9A9AA3),
    surfaceContainerLowest = Color(0xFF0A0A0B), surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF141416), surfaceContainerHigh = Color(0xFF1A1A1D), surfaceContainerHighest = Color(0xFF222226),
    outline = Color(0xFF3A3A40), outlineVariant = Color(0xFF26262B),
    error = BrrPalette.Red, onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE0182D), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E3), onPrimaryContainer = Color(0xFF5C0010),
    secondary = Color(0xFFE85A0C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6D6), onSecondaryContainer = Color(0xFF4A1A00),
    background = Color(0xFFF7F7F5), onBackground = Color(0xFF111113),
    surface = Color(0xFFF7F7F5), onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFEDEDEA), onSurfaceVariant = Color(0xFF5E5E66),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFA),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1F1EF), surfaceContainerHighest = Color(0xFFEAEAE7),
    outline = Color(0xFFC9C9C4), outlineVariant = Color(0xFFE5E5E1),
    error = Color(0xFFE0182D), onError = Color.White,
)

val LocalBrrColors = staticCompositionLocalOf { DarkExtras }

object Brr {
    val colors: BrrExtraColors @Composable get() = LocalBrrColors.current
}

@Composable
fun BrrTheme(mode: ThemeMode?, content: @Composable () -> Unit) {
    val dark = when (mode ?: ThemeMode.DARK) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalBrrColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = BrrTypography, content = content)
    }
}
