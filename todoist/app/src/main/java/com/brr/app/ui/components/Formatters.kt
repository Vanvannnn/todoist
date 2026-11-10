package com.brr.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val fr = Locale.FRENCH
    private val time = DateTimeFormatter.ofPattern("HH:mm", fr)
    private val dayShort = DateTimeFormatter.ofPattern("EEE d MMM", fr)
    private val dayLong = DateTimeFormatter.ofPattern("EEEE d MMMM", fr)
    private val dayYear = DateTimeFormatter.ofPattern("d MMM yyyy", fr)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun time(ms: Long): String = time.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun minuteOfDay(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    fun todayLong(): String = dayLong.format(LocalDate.now(zone)).replaceFirstChar { it.titlecase(fr) }

    fun day(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            d == today -> "Aujourd'hui"
            d == today.plusDays(1) -> "Demain"
            d == today.minusDays(1) -> "Hier"
            d.year != today.year -> dayYear.format(d)
            else -> dayShort.format(d).replaceFirstChar { it.titlecase(fr) }
        }
    }

    fun due(dueAt: Long, hasTime: Boolean, now: Long = System.currentTimeMillis()): String =
        if (hasTime) "${day(dueAt, now)} · ${time(dueAt)}" else day(dueAt, now)

    fun dateTime(ms: Long, now: Long = System.currentTimeMillis()): String = "${day(ms, now)} · ${time(ms)}"

    fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (ms - now) / 60_000
        val abs = kotlin.math.abs(diff)
        val txt = when {
            abs < 1 -> return "maintenant"
            abs < 60 -> "$abs min"
            abs < 60 * 24 -> "${abs / 60} h${if (abs % 60 != 0L) " ${abs % 60}" else ""}"
            else -> "${abs / (60 * 24)} j"
        }
        return if (diff > 0) "dans $txt" else "il y a $txt"
    }

    fun minutes(m: Int): String = when {
        m < 60 -> "$m min"
        m % 1440 == 0 -> "${m / 1440} j"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60}"
    }
}
