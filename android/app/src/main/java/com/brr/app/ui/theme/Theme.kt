package com.brr.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode

/**
 * Charte BULLYLIST (valeurs générées dans Tokens.kt depuis design/tokens.json).
 * Principe : « la couleur, c'est l'alarme ». L'interface au calme est monochrome ; les couleurs de niveau
 * ne servent qu'à signaler une urgence (retard, report, niveau d'alerte).
 */
object BrrPalette {
    val Red = BullyLevels.base[3]
    val Orange = BullyLevels.base[2]
    val Amber = BullyLevels.base[1]
    val Blue = BullyLevels.base[0]
    val Green = BullyDark.success
}

/** Coins chanfreinés : réservés à ce qui relève de l'alerte. */
val AlertShape = CutCornerShape(topStart = BullyTokens.radiusChamfer.dp, bottomEnd = BullyTokens.radiusChamfer.dp)
val AlertShapeSmall = CutCornerShape(topStart = BullyTokens.radiusChamferSm.dp, bottomEnd = BullyTokens.radiusChamferSm.dp)

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
        Priority.URGENT -> BullyPriority.p1
        Priority.HIGH -> BullyPriority.p2
        Priority.NORMAL -> if (isDark) BullyPriority.p3 else BullyLight.text2
        Priority.LOW -> muted
    }

    fun level(l: ReminderLevel): Color = BullyLevels.base[l.ordinal]
}

private val DarkExtras = BrrExtraColors(
    muted = BullyDark.text2, faint = BullyDark.text3, card = BullyDark.surface1, cardHigh = BullyDark.surface2,
    hairline = BullyDark.line, success = BullyDark.success, isDark = true,
)
private val LightExtras = BrrExtraColors(
    muted = BullyLight.text2, faint = BullyLight.text3, card = BullyLight.surface1, cardHigh = BullyLight.surface2,
    hairline = BullyLight.line, success = BullyLight.success, isDark = false,
)

private val DarkScheme = darkColorScheme(
    primary = BullyDark.accent, onPrimary = BullyDark.onAccent,
    primaryContainer = BullyDark.surface3, onPrimaryContainer = BullyDark.text,
    secondary = BullyDark.text2, onSecondary = BullyDark.bg,
    secondaryContainer = BullyDark.surface2, onSecondaryContainer = BullyDark.text,
    tertiary = BullyLevels.base[1], onTertiary = BullyLevels.ink[1],
    background = BullyDark.bg, onBackground = BullyDark.text,
    surface = BullyDark.bg, onSurface = BullyDark.text,
    surfaceVariant = BullyDark.surface2, onSurfaceVariant = BullyDark.text2,
    surfaceContainerLowest = BullyDark.bg, surfaceContainerLow = BullyDark.surface1,
    surfaceContainer = BullyDark.surface1, surfaceContainerHigh = BullyDark.surface2, surfaceContainerHighest = BullyDark.surface3,
    outline = BullyDark.lineStrong, outlineVariant = BullyDark.line,
    error = BullyLevels.base[3], onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = BullyLight.accent, onPrimary = BullyLight.onAccent,
    primaryContainer = BullyLight.surface3, onPrimaryContainer = BullyLight.text,
    secondary = BullyLight.text2, onSecondary = BullyLight.surface1,
    secondaryContainer = BullyLight.surface3, onSecondaryContainer = BullyLight.text,
    tertiary = Color(0xFFB27A00), onTertiary = Color.White,
    background = BullyLight.bg, onBackground = BullyLight.text,
    surface = BullyLight.bg, onSurface = BullyLight.text,
    surfaceVariant = BullyLight.surface3, onSurfaceVariant = BullyLight.text2,
    surfaceContainerLowest = BullyLight.surface1, surfaceContainerLow = BullyLight.surface2,
    surfaceContainer = BullyLight.surface1, surfaceContainerHigh = BullyLight.surface2, surfaceContainerHighest = BullyLight.surface3,
    outline = BullyLight.lineStrong, outlineVariant = BullyLight.line,
    error = Color(0xFFD0102C), onError = Color.White,
)

private val BullyShapes = Shapes(
    extraSmall = RoundedCornerShape(BullyTokens.radiusXs.dp),
    small = RoundedCornerShape(BullyTokens.radiusSm.dp),
    medium = RoundedCornerShape(BullyTokens.radiusMd.dp),
    large = RoundedCornerShape(BullyTokens.radiusLg.dp),
    extraLarge = RoundedCornerShape(BullyTokens.radiusXl.dp),
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
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = BrrTypography, shapes = BullyShapes, content = content)
    }
}
