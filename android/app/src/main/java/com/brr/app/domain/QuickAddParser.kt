package com.brr.app.domain

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class QuickAddResult(
    val title: String,
    val dueAt: Long? = null,
    val hasTime: Boolean = false,
    val priority: Priority? = null,
    val projectName: String? = null,
    val level: ReminderLevel? = null,
)

/**
 * Analyse une saisie rapide en langage naturel (français).
 * Exemples : « Sortir les poubelles demain 20h !1 #Maison @nuclear », « Appeler maman dans 2h ».
 */
object QuickAddParser {
    private val timeH = Regex("^(\\d{1,2})h(\\d{2})?$")
    private val timeColon = Regex("^(\\d{1,2}):(\\d{2})$")
    private val weekdays = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY,
    )
    private val priorities = mapOf(
        "!1" to Priority.URGENT, "!urgent" to Priority.URGENT, "!urgente" to Priority.URGENT,
        "!2" to Priority.HIGH, "!haute" to Priority.HIGH, "!3" to Priority.NORMAL, "!normale" to Priority.NORMAL,
        "!4" to Priority.LOW, "!basse" to Priority.LOW,
    )
    private val levels = mapOf(
        "@normal" to ReminderLevel.NORMAL, "@insistant" to ReminderLevel.INSISTANT,
        "@harcelement" to ReminderLevel.HARCELEMENT, "@brr" to ReminderLevel.BRR,
        "@bully" to ReminderLevel.HARCELEMENT, "@nuclear" to ReminderLevel.BRR, "@nucleaire" to ReminderLevel.BRR,
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('’', '\'')

    fun parse(input: String, now: Long, zone: ZoneId): QuickAddResult {
        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var relative: ZonedDateTime? = null
        var priority: Priority? = null
        var project: String? = null
        var level: ReminderLevel? = null

        var i = 0
        while (i < tokens.size) {
            val raw = tokens[i]
            val t = normalize(raw)
            val next = tokens.getOrNull(i + 1)?.let { normalize(it) }
            when {
                t == "aujourd'hui" || t == "auj" -> date = today
                t == "demain" -> date = today.plusDays(1)
                t == "apres-demain" -> date = today.plusDays(2)
                t == "ce" && next == "soir" -> { date = date ?: today; if (time == null) time = LocalTime.of(19, 0); i++ }
                t in weekdays -> {
                    val dow = weekdays.getValue(t)
                    var d = today.plusDays(1)
                    while (d.dayOfWeek != dow) d = d.plusDays(1)
                    date = d
                }
                parseTime(t) != null -> time = parseTime(t)
                (t == "a" || t == "vers") && next != null && parseTime(next) != null -> { time = parseTime(next); i++ }
                t == "dans" && next != null -> {
                    val consumed = parseRelative(tokens, i + 1, now, zone)
                    if (consumed != null) { relative = consumed.first; i += consumed.second } else kept += raw
                }
                t in priorities -> priority = priorities.getValue(t)
                t in levels -> level = levels.getValue(t)
                raw.startsWith("#") && raw.length > 1 -> project = raw.substring(1).replace('_', ' ')
                else -> kept += raw
            }
            i++
        }

        var dueAt: Long? = null
        var hasTime = false
        when {
            relative != null -> { dueAt = relative.toInstant().toEpochMilli(); hasTime = true }
            date != null || time != null -> {
                var d = date ?: today
                if (time != null) {
                    hasTime = true
                    var z = d.atTime(time).atZone(zone)
                    if (date == null && z.toInstant().toEpochMilli() <= now) { d = d.plusDays(1); z = d.atTime(time).atZone(zone) }
                    dueAt = z.toInstant().toEpochMilli()
                } else {
                    dueAt = d.atStartOfDay(zone).toInstant().toEpochMilli()
                }
            }
        }
        val title = kept.joinToString(" ").trim().ifEmpty { input.trim() }
        return QuickAddResult(title, dueAt, hasTime, priority, project, level)
    }

    private fun parseTime(t: String): LocalTime? {
        val m = timeH.matchEntire(t) ?: timeColon.matchEntire(t) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    /** « dans 2h », « dans 30 min », « dans 3 jours », « dans 2 heures ». Retourne (date, nb de jetons consommés). */
    private fun parseRelative(tokens: List<String>, from: Int, now: Long, zone: ZoneId): Pair<ZonedDateTime, Int>? {
        val base = Instant.ofEpochMilli(now).atZone(zone)
        val a = normalize(tokens.getOrNull(from) ?: return null)
        Regex("^(\\d+)(min|h|j)$").matchEntire(a)?.let { m ->
            return apply(base, m.groupValues[1].toLong(), m.groupValues[2])?.let { it to 1 }
        }
        val n = a.toLongOrNull() ?: return null
        val unit = normalize(tokens.getOrNull(from + 1) ?: return null)
        return apply(base, n, unit)?.let { it to 2 }
    }

    private fun apply(base: ZonedDateTime, n: Long, unit: String): ZonedDateTime? = when (unit) {
        "min", "mn", "minute", "minutes" -> base.plusMinutes(n)
        "h", "heure", "heures" -> base.plusHours(n)
        "j", "jour", "jours" -> base.plusDays(n)
        "semaine", "semaines" -> base.plusWeeks(n)
        else -> null
    }
}
