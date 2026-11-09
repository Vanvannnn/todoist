package com.brr.app.domain

import com.brr.app.domain.model.RepeatType
import com.brr.app.domain.model.WeekDays
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calcule la prochaine échéance d'une tâche répétitive.
 * La prochaine occurrence est toujours strictement postérieure à [after] ET à l'échéance courante,
 * ce qui évite qu'une tâche quotidienne terminée en retard ne retombe immédiatement « en retard ».
 */
object RecurrenceCalculator {

    fun next(
        currentDue: Long,
        type: RepeatType,
        interval: Int,
        daysMask: Int,
        after: Long,
        zone: ZoneId,
    ): Long? {
        if (type == RepeatType.NONE) return null
        val step = interval.coerceAtLeast(1)
        var candidate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(currentDue), zone)
        val threshold = maxOf(after, currentDue)
        var guard = 0
        do {
            candidate = advance(candidate, type, step, daysMask)
            guard++
        } while (candidate.toInstant().toEpochMilli() <= threshold && guard < 5000)
        return candidate.toInstant().toEpochMilli()
    }

    private fun advance(from: ZonedDateTime, type: RepeatType, step: Int, daysMask: Int): ZonedDateTime =
        when (type) {
            RepeatType.NONE -> from
            RepeatType.DAILY -> from.plusDays(step.toLong())
            RepeatType.WEEKLY -> if (daysMask == 0) from.plusWeeks(step.toLong()) else nextWeekday(from, step, daysMask)
            RepeatType.MONTHLY -> from.plusMonths(step.toLong())
            RepeatType.YEARLY -> from.plusYears(step.toLong())
        }

    /** Jour suivant coché dans le masque ; quand on boucle sur une nouvelle semaine, on saute (step-1) semaines. */
    private fun nextWeekday(from: ZonedDateTime, step: Int, mask: Int): ZonedDateTime {
        var d = from
        for (i in 1..7) {
            d = d.plusDays(1)
            if (d.dayOfWeek.value == 1 && step > 1) d = d.plusWeeks((step - 1).toLong())
            if (WeekDays.has(mask, d.dayOfWeek.value)) return d
        }
        return from.plusWeeks(step.toLong())
    }

    fun describe(type: RepeatType, interval: Int, daysMask: Int): String {
        val n = interval.coerceAtLeast(1)
        return when (type) {
            RepeatType.NONE -> "Pas de répétition"
            RepeatType.DAILY -> if (n == 1) "Tous les jours" else "Tous les $n jours"
            RepeatType.WEEKLY -> {
                val base = if (n == 1) "Chaque semaine" else "Toutes les $n semaines"
                if (daysMask == 0) base else {
                    val names = listOf("lun", "mar", "mer", "jeu", "ven", "sam", "dim")
                    base + " (" + (1..7).filter { WeekDays.has(daysMask, it) }.joinToString(", ") { names[it - 1] } + ")"
                }
            }
            RepeatType.MONTHLY -> if (n == 1) "Chaque mois" else "Tous les $n mois"
            RepeatType.YEARLY -> if (n == 1) "Chaque année" else "Tous les $n ans"
        }
    }
}
