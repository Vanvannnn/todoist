// GÉNÉRÉ par design/build.mjs depuis design/*.json — ne pas modifier à la main.
package com.brr.app.ui.theme

import androidx.compose.ui.graphics.Color

object BullyDark {
    val bg = Color(0xFF121419)
    val surface1 = Color(0xFF181B21)
    val surface2 = Color(0xFF1F232B)
    val surface3 = Color(0xFF282D37)
    val line = Color(0xFF2C313B)
    val lineStrong = Color(0xFF3C4250)
    val text = Color(0xFFF2EFE8)
    val text2 = Color(0xFFA9AAB0)
    val text3 = Color(0xFF71747D)
    val accent = Color(0xFFF2EFE8)
    val onAccent = Color(0xFF121419)
    val accentText = Color(0xFFF2EFE8)
    val success = Color(0xFF3FD18B)
    val info = Color(0xFF7C9CFF)
}

object BullyLight {
    val bg = Color(0xFFE9ECEF)
    val surface1 = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFF5F6F8)
    val surface3 = Color(0xFFE1E5E9)
    val line = Color(0xFFD5DAE0)
    val lineStrong = Color(0xFFB9C0C8)
    val text = Color(0xFF15171C)
    val text2 = Color(0xFF4C515B)
    val text3 = Color(0xFF7A808A)
    val accent = Color(0xFF15171C)
    val onAccent = Color(0xFFF7F7F5)
    val accentText = Color(0xFF15171C)
    val success = Color(0xFF14935A)
    val info = Color(0xFF3E5FD8)
}

/** Identité des quatre niveaux d'alerte (index 0 = niveau 1). */
object BullyLevels {
    val base = listOf(Color(0xFF7C9CFF), Color(0xFFFFB000), Color(0xFFFF5A1F), Color(0xFFFF2340))
    val deep = listOf(Color(0xFF0D1430), Color(0xFF2A1C00), Color(0xFF2B0C02), Color(0xFF30030B))
    val ink = listOf(Color(0xFFFFFFFF), Color(0xFF140C00), Color(0xFFFFFFFF), Color(0xFFFFFFFF))
    val names = listOf("Normal", "Insistant", "Bully Mode", "Bully Mode: Nuclear")
    val pulseMs = listOf(0, 1600, 1200, 1000)
}

object BullyPriority {
    val p1 = Color(0xFFFF2340)
    val p2 = Color(0xFFFF5A1F)
    val p3 = Color(0xFFA9AAB0)
    val p4 = Color(0xFF71747D)
}

object BullyTokens {
    val projectColors = listOf(0xFF9AA7B8, 0xFFB59F84, 0xFF8FB3A2, 0xFFA99BC4, 0xFFC49A9A, 0xFF9FB6C9, 0xFFB8B08A, 0xFF8E9AAF)
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
