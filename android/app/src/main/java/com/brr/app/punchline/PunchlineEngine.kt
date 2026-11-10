package com.brr.app.punchline

import com.brr.app.domain.model.ReminderLevel
import kotlin.math.abs
import kotlin.random.Random

data class PickRequest(
    val level: ReminderLevel,
    val isFinalWarning: Boolean,
    val disabledCategories: Set<PunchlineCategory>,
    val recentIds: List<String>,
    val taskTitle: String? = null,
    val minutesLate: Long = 0,
    val snoozeCount: Int = 0,
    val contextualEnabled: Boolean = true,
)

data class PickedPunchline(val text: String, val id: String?, val category: PunchlineCategory?)

/**
 * Tire une punchline adaptée à l'intensité du rappel, en évitant les répétitions récentes
 * et en respectant les catégories désactivées.
 */
class PunchlineEngine(private val random: Random = Random.Default) {

    fun pick(pool: List<Punchline>, req: PickRequest): PickedPunchline {
        val title = req.taskTitle?.takeIf { it.isNotBlank() }
        // Une part des messages est contextualisée avec le nom de la tâche.
        if (req.contextualEnabled && title != null && !req.isFinalWarning && random.nextFloat() < CONTEXTUAL_RATE) {
            return PickedPunchline(ContextualPunchlines.generate(title, req.minutesLate, req.snoozeCount, random), null, null)
        }
        val chosen = candidates(pool, req).let { list -> list[random.nextInt(list.size)] }
        return PickedPunchline(chosen.text, chosen.id, chosen.category)
    }

    /** Liste des candidats après filtrage (exposé pour les tests). */
    fun candidates(pool: List<Punchline>, req: PickRequest): List<Punchline> {
        val enabled = pool.filter { it.category !in req.disabledCategories }
        if (enabled.isEmpty()) return listOf(FALLBACK)

        val target = req.level.ordinal + 1
        val finals = enabled.filter { it.category == PunchlineCategory.DERNIER_AVERTISSEMENT }
        val base = when {
            req.isFinalWarning && finals.isNotEmpty() -> finals
            else -> enabled.filter { it.category != PunchlineCategory.DERNIER_AVERTISSEMENT || req.level == ReminderLevel.BRR }
                .ifEmpty { enabled }
        }
        val byIntensity = base.filter { abs(it.intensity - target) <= 1 }.ifEmpty { base }
        val recent = req.recentIds.toSet()
        return byIntensity.filter { it.id !in recent }.ifEmpty { byIntensity }
    }

    companion object {
        const val CONTEXTUAL_RATE = 0.35f
        const val RECENT_MEMORY = 40
        val FALLBACK = Punchline("fallback", "Il est temps de s'y mettre.", PunchlineCategory.SARCASTIQUE, 1)

        fun pushRecent(recent: List<String>, id: String?): List<String> =
            if (id == null) recent else (recent.filter { it != id } + id).takeLast(RECENT_MEMORY)
    }
}
