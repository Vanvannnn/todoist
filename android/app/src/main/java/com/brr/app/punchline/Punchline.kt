package com.brr.app.punchline

// PunchlineCategory et le catalogue sont générés depuis design/punchlines.json (PunchlineCatalog.kt).

/**
 * @param intensity 1 (gentil) à 4 (Bully Mode: Nuclear).
 * @param id identifiant stable utilisé pour éviter les répétitions.
 */
data class Punchline(
    val id: String,
    val text: String,
    val category: PunchlineCategory,
    val intensity: Int,
    val custom: Boolean = false,
)
