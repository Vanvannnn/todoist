package com.brr.app.punchline

enum class PunchlineCategory(val label: String, val description: String) {
    SARCASTIQUE("Sarcastique", "Ironie mordante, sourire en coin."),
    PROVOCATEUR("Provocateur", "Défis et piques pour te faire réagir."),
    MOTIVATION_TOXIQUE("Motivation toxique", "Le coach Instagram, version cynique."),
    HUMILIATION_LEGERE("Humiliation légère", "Ça pique un peu. Juste un peu."),
    ABSURDE("Absurde", "Lamas, FBI des tâches et licornes."),
    COACH_MILITAIRE("Coach militaire", "GARDE-À-VOUS ! (comique, promis)."),
    AUTODERISION("Autodérision", "L'appli se moque d'elle-même."),
    DERNIER_AVERTISSEMENT("Dernier avertissement", "Réservé aux ultimes relances."),
}

/**
 * @param intensity 1 (gentil) à 4 (mode B.R.R.).
 * @param id identifiant stable utilisé pour éviter les répétitions.
 */
data class Punchline(
    val id: String,
    val text: String,
    val category: PunchlineCategory,
    val intensity: Int,
    val custom: Boolean = false,
)
