package com.brr.app.domain.model

enum class Priority(val label: String, val weight: Int) {
    LOW("Basse", 0),
    NORMAL("Normale", 1),
    HIGH("Haute", 2),
    URGENT("Urgente", 3),
}

/** Les quatre niveaux d'insistance. L'ordre (ordinal) est significatif : il sert à l'escalade. */
enum class ReminderLevel(val label: String, val tagline: String) {
    NORMAL("Normal", "Une notification polie. Pour l'instant."),
    INSISTANT("Insistant", "Prioritaire, vibrante, sarcastique."),
    HARCELEMENT("Harcèlement", "Écran immersif, compte à rebours, relances."),
    BRR("Mode B.R.R.", "Urgence nationale. Persistant jusqu'à action.");

    fun atMost(cap: ReminderLevel): ReminderLevel = if (ordinal <= cap.ordinal) this else cap

    companion object {
        fun fromIndex(index: Int): ReminderLevel = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

enum class RepeatType(val label: String) {
    NONE("Jamais"),
    DAILY("Quotidienne"),
    WEEKLY("Hebdomadaire"),
    MONTHLY("Mensuelle"),
    YEARLY("Annuelle"),
}

enum class HistoryType(val label: String) {
    CREATED("Créée"),
    COMPLETED("Terminée"),
    REOPENED("Réouverte"),
    SNOOZED("Reportée"),
    REMINDER_FIRED("Rappel"),
    REMINDERS_DISABLED("Rappels coupés"),
    DELETED("Supprimée"),
}

enum class ThemeMode(val label: String) { DARK("Sombre"), LIGHT("Clair"), SYSTEM("Système") }

/** Jours de la semaine pour les répétitions personnalisées, encodés en masque (lundi = bit 0). */
object WeekDays {
    val labels = listOf("L", "M", "M", "J", "V", "S", "D")
    fun has(mask: Int, isoDay: Int): Boolean = mask and (1 shl (isoDay - 1)) != 0
    fun toggle(mask: Int, isoDay: Int): Int = mask xor (1 shl (isoDay - 1))
}
