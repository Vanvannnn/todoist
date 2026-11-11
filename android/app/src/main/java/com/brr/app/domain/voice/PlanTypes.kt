package com.brr.app.domain.voice

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Types du cœur « assistant vocal + planning », miroir des modules de web/js/core/.
 * Les dates sont locales (LocalDate) et les heures en minutes depuis minuit : pas de fuseau dans le cœur.
 */

data class Moment(val date: LocalDate, val minute: Int)
data class Deadline(val date: LocalDate, val minute: Int, val label: String, val assumed: Boolean)
data class Window(val date: LocalDate, val start: Int, val end: Int, val fixed: Boolean, val label: String)
data class Recurrence(val type: Type, val part: String? = null, val n: Int = 0, val dates: List<LocalDate> = emptyList()) { enum class Type { DAILY, COUNT, DAYS } }
data class Horizon(val from: LocalDate, val until: LocalDate)
data class Estimate(val min: Int, val max: Int, val minutes: Int, val family: String, val familyLabel: String, val learned: Boolean)

/** Tâche comprise dans une dictée (modifiable par l'utilisateur avant validation). */
data class VoiceTask(
    val id: String,
    var title: String,
    var norm: String,
    var deadline: Deadline? = null,
    var window: Window? = null,
    var recurrence: Recurrence? = null,
    var horizon: Horizon? = null,
    var urgent: Boolean = false,
    var low: Boolean = false,
    var little: Boolean = false,
    var finishing: Boolean = false,
    var explicitMinutes: Int? = null,
    val dependsOn: MutableList<String> = mutableListOf(),
    val notes: MutableList<String> = mutableListOf(),
    var estimate: Estimate? = null,
    var minutes: Int = 30,
    var sessionMinutes: Int? = null,
    var priority: Priority = Priority.NORMAL,
    var level: ReminderLevel = ReminderLevel.INSISTANT,
    /** Projet qui regroupe les séances d'une tâche composée (compréhension IA). */
    val group: String? = null,
    /** Sous-tâches à cocher, enregistrées avec la tâche. */
    val subtasks: List<String> = emptyList(),
) {
    /** Tâche créée d'après une dépendance (« après avoir fini les recherches ») : son titre cède à une formulation explicite. */
    var fromDep: Boolean = false
    var except: List<Int> = emptyList()
}

/** Indisponibilité comprise (« mardi j'ai cours le matin », « rien après 18 heures »), en minutes locales. */
data class Constraint(val date: LocalDate, val start: Int, val end: Int, val label: String)

data class Dictation(val tasks: List<VoiceTask>, val questions: List<String>, val isPlanRequest: Boolean, val constraints: List<Constraint> = emptyList())

object PlanDates {
    private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    fun fmtTime(minute: Int): String = "%02dh%02d".format(minute / 60, minute % 60)

    fun fmtDay(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "Aujourd'hui"
        1L -> "Demain"
        else -> DAYS[date.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() } + " ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
    }

    fun round5(n: Double): Int = maxOf(5, Math.round(n / 5).toInt() * 5)
}
