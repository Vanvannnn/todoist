// GÉNÉRÉ par design/build.mjs depuis design/*.json — ne pas modifier à la main.
package com.brr.app.ui.theme

import androidx.compose.ui.graphics.Color

object BullyDark {
    val bg = Color(0xFF07080A)
    val surface1 = Color(0xFF0D0F13)
    val surface2 = Color(0xFF13161C)
    val surface3 = Color(0xFF1B1F27)
    val line = Color(0xFF262B35)
    val lineStrong = Color(0xFF363C49)
    val text = Color(0xFFEEF0F4)
    val text2 = Color(0xFFA3A9B7)
    val text3 = Color(0xFF646B7A)
    val accent = Color(0xFFD7FF3A)
    val onAccent = Color(0xFF0A0C00)
    val accentText = Color(0xFFD7FF3A)
    val success = Color(0xFF34E3A0)
    val info = Color(0xFF6C8CFF)
}

object BullyLight {
    val bg = Color(0xFFF3F3EF)
    val surface1 = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFF8F8F5)
    val surface3 = Color(0xFFECECE6)
    val line = Color(0xFFE1E1DA)
    val lineStrong = Color(0xFFC9C9C0)
    val text = Color(0xFF0B0D11)
    val text2 = Color(0xFF4F5563)
    val text3 = Color(0xFF80858F)
    val accent = Color(0xFFD7FF3A)
    val onAccent = Color(0xFF0A0C00)
    val accentText = Color(0xFF4E6400)
    val success = Color(0xFF0E9F6A)
    val info = Color(0xFF3A5BE0)
}

/** Identité des quatre niveaux d'alerte (index 0 = niveau 1). */
object BullyLevels {
    val base = listOf(Color(0xFF6C8CFF), Color(0xFFFFB020), Color(0xFFFF5B1F), Color(0xFFFF1F45))
    val deep = listOf(Color(0xFF0B1330), Color(0xFF2B1B00), Color(0xFF2A0B00), Color(0xFF33000A))
    val ink = listOf(Color(0xFFFFFFFF), Color(0xFF140C00), Color(0xFFFFFFFF), Color(0xFFFFFFFF))
    val names = listOf("Normal", "Insistant", "Bully Mode", "Bully Mode: Nuclear")
    val pulseMs = listOf(0, 1600, 1200, 1000)
}

object BullyPriority {
    val p1 = Color(0xFFFF1F45)
    val p2 = Color(0xFFFF5B1F)
    val p3 = Color(0xFF6C8CFF)
    val p4 = Color(0xFF646B7A)
}

object BullyTokens {
    val projectColors = listOf(0xFFD7FF3A, 0xFF6C8CFF, 0xFFFF5B1F, 0xFF34E3A0, 0xFFC46BFF, 0xFFFFB020, 0xFFFF4FA3, 0xFF3FD0FF)
    const val radiusXs = 4
    const val radiusSm = 8
    const val radiusMd = 12
    const val radiusLg = 18
    const val radiusXl = 26
    const val radiusChamfer = 12
    const val radiusChamferSm = 7
    const val motionFast = 140
    const val motionBase = 220
    const val motionSlow = 420
    const val BRAND = "BULLYLIST"
    const val TAGLINE = "YOUR TASKS. YOUR PROBLEM. OUR BUSINESS."
}
