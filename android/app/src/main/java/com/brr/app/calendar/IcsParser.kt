package com.brr.app.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Lecture de flux iCalendar (RFC 5545) : CELCAT, iCloud (calendrier public), Google (adresse iCal), fichiers .ics.
 * Gère : lignes repliées, TZID, UTC, journées entières, DURATION, RRULE (DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL,
 * COUNT, UNTIL, BYDAY), EXDATE, RECURRENCE-ID (cours déplacés), STATUS:CANCELLED (cours annulés).
 * Port de web/js/core/ics.js ; cas communs : design/fixtures/ics.json.
 */
object IcsParser {
    data class Value(val local: LocalDateTime, val utc: Boolean, val tz: String?, val dateOnly: Boolean)
    data class Event(
        var uid: String? = null, var summary: String? = null, var location: String? = null, var status: String? = null,
        var start: Value? = null, var end: Value? = null, var duration: Duration? = null,
        var rrule: Map<String, String>? = null, val exdates: MutableList<Value> = mutableListOf(), var recurrenceId: Value? = null,
    )
    data class Calendar(val name: String?, val events: List<Event>)
    data class Occurrence(val uid: String?, val title: String, val location: String?, val start: Long, val end: Long, val allDay: Boolean)

    private fun unescape(v: String) = v.replace(Regex("\\\\n", RegexOption.IGNORE_CASE), "\n").replace(Regex("\\\\([,;\\\\])"), "$1")

    private fun parseValue(params: Map<String, String>, value: String): Value? {
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})?(Z)?)?$").find(value.trim()) ?: return null
        val g = m.groupValues
        val dateOnly = params["VALUE"] == "DATE" || g[4].isEmpty()
        return runCatching {
            Value(LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt()),
                g[7] == "Z", params["TZID"], dateOnly)
        }.getOrNull()
    }

    fun parseDuration(v: String): Duration? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v.trim()) ?: return null
        val g = m.groupValues.map { it.ifEmpty { "0" } }
        val d = Duration.ofDays(g[2].toLong() * 7 + g[3].toLong()).plusHours(g[4].toLong()).plusMinutes(g[5].toLong()).plusSeconds(g[6].toLong())
        return if (m.groupValues[1] == "-") d.negated() else d
    }

    fun parse(text: String): Calendar {
        val events = mutableListOf<Event>(); var cur: Event? = null; var name: String? = null; var depth = 0
        for (line in text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')) {
            if (line.isBlank()) continue
            val idx = line.indexOf(':'); if (idx < 0) continue
            val head = line.substring(0, idx); val value = line.substring(idx + 1).trim()
            val parts = head.split(';')
            val key = parts[0].uppercase()
            val params = parts.drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=', "").trim('"') }
            when {
                key == "BEGIN" -> { if (value == "VEVENT") cur = Event() else if (cur != null) depth++; continue }
                key == "END" -> { if (value == "VEVENT" && cur != null) { if (cur.start != null) events += cur; cur = null } else if (cur != null && depth > 0) depth--; continue }
            }
            val e = cur
            if (e == null) { if (key == "X-WR-CALNAME") name = unescape(value); continue }
            if (depth > 0) continue // VALARM, etc.
            when (key) {
                "UID" -> e.uid = value
                "SUMMARY" -> e.summary = unescape(value)
                "LOCATION" -> e.location = unescape(value)
                "STATUS" -> e.status = value.uppercase()
                "DTSTART" -> e.start = parseValue(params, value)
                "DTEND" -> e.end = parseValue(params, value)
                "DURATION" -> e.duration = parseDuration(value)
                "RRULE" -> e.rrule = value.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
                "EXDATE" -> value.split(',').mapNotNull { parseValue(params, it) }.forEach { e.exdates += it }
                "RECURRENCE-ID" -> e.recurrenceId = parseValue(params, value)
            }
        }
        return Calendar(name, events)
    }

    /** Heure murale → instant. TZID inconnu (ex. noms Windows) : fuseau de l'appareil. */
    fun toInstant(v: Value, fallbackTz: String? = null, device: ZoneId = ZoneId.systemDefault()): Long {
        if (v.utc) return v.local.toInstant(ZoneOffset.UTC).toEpochMilli()
        val zone = if (v.dateOnly) device else (v.tz ?: fallbackTz)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: device
        return v.local.atZone(zone).toInstant().toEpochMilli()
    }

    private val BYDAY = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    /** Développe les occurrences qui chevauchent [from, to[ (millisecondes). */
    fun expand(cal: Calendar, from: Long, to: Long, device: ZoneId = ZoneId.systemDefault()): List<Occurrence> {
        val out = mutableListOf<Occurrence>()
        val overrides = mutableMapOf<String, Event>()
        for (e in cal.events) { val rid = e.recurrenceId; if (rid != null && e.uid != null) overrides["${e.uid}|${toInstant(rid, e.start?.tz, device)}"] = e }

        fun push(e: Event, startMs: Long) {
            val s = e.start!!
            var dur = when {
                e.end != null -> toInstant(e.end!!, s.tz, device) - toInstant(s, null, device)
                e.duration != null -> e.duration!!.toMillis()
                s.dateOnly -> 86_400_000L
                else -> 0L
            }
            if (dur < 0) dur = 0
            val endMs = startMs + dur
            if (endMs > from && startMs < to && e.status != "CANCELLED") out += Occurrence(e.uid, e.summary ?: "(sans titre)", e.location, startMs, endMs, s.dateOnly)
        }
        fun emitOrOverride(e: Event, ms: Long) {
            val k = "${e.uid}|$ms"
            val ov = overrides.remove(k)
            if (ov != null) push(ov, toInstant(ov.start!!, null, device)) else push(e, ms)
        }

        for (e in cal.events) {
            if (e.recurrenceId != null) continue
            val start = e.start!!
            val r = e.rrule
            if (r == null) { emitOrOverride(e, toInstant(start, null, device)); continue }
            val freq = r["FREQ"]; val interval = maxOf(1, r["INTERVAL"]?.toIntOrNull() ?: 1)
            val count = r["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
            val until = r["UNTIL"]?.let { u -> parseValue(emptyMap(), u)?.let { toInstant(it.copy(tz = if (u.endsWith("Z")) null else start.tz), null, device) } } ?: Long.MAX_VALUE
            val byday = r["BYDAY"]?.split(',')?.mapNotNull { BYDAY[it.replace(Regex("^[+-]?\\d+"), "")] }
            fun ms(w: LocalDateTime) = toInstant(start.copy(local = w), null, device)
            fun isEx(w: LocalDateTime) = e.exdates.any { x -> if (x.dateOnly) x.local.toLocalDate() == w.toLocalDate() else toInstant(x, start.tz, device) == ms(w) }
            var emitted = 0
            fun emit(w: LocalDateTime): Boolean {
                val t = ms(w)
                if (t > until || emitted >= count) return false
                emitted++
                if (!isEx(w)) emitOrOverride(e, t)
                return true
            }
            val limit = to + 7 * 86_400_000L
            var w = start.local; var guard = 0
            while (guard++ < 3000) {
                if (ms(w) > limit || ms(w) > until || emitted >= count) break
                if (freq == "WEEKLY" && byday != null) {
                    val monday = w.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    for (i in 0..6) {
                        val cand = monday.plusDays(i.toLong())
                        if (cand.dayOfWeek in byday && !cand.isBefore(start.local)) if (!emit(cand)) break
                    }
                    w = monday.plusWeeks(interval.toLong()).with(start.local.toLocalTime())
                    continue
                }
                if (!(freq == "DAILY" && byday != null && w.dayOfWeek !in byday)) emit(w)
                w = when (freq) {
                    "DAILY" -> w.plusDays(interval.toLong())
                    "WEEKLY" -> w.plusWeeks(interval.toLong())
                    "MONTHLY" -> { var n = w.withDayOfMonth(1).plusMonths(interval.toLong()); while (start.local.dayOfMonth > n.toLocalDate().lengthOfMonth()) n = n.plusMonths(interval.toLong()); n.withDayOfMonth(start.local.dayOfMonth) }
                    "YEARLY" -> w.plusYears(interval.toLong())
                    else -> break
                }
            }
        }
        // Occurrences déplacées vers la fenêtre depuis une date hors période de développement.
        for (ov in overrides.values) push(ov, toInstant(ov.start!!, null, device))
        return out.sortedBy { it.start }
    }

    /** Découpe une occurrence en tranches locales par jour (les journées entières ne bloquent pas l'emploi du temps). */
    fun localSlices(o: Occurrence, zone: ZoneId = ZoneId.systemDefault()): List<Triple<LocalDate, Int, Int>> {
        if (o.allDay) return emptyList()
        val out = mutableListOf<Triple<LocalDate, Int, Int>>()
        var s = java.time.Instant.ofEpochMilli(o.start).atZone(zone)
        val e = java.time.Instant.ofEpochMilli(o.end).atZone(zone)
        while (s.isBefore(e)) {
            val dayEnd = s.toLocalDate().plusDays(1).atStartOfDay(zone)
            val end = if (e.isBefore(dayEnd)) e else dayEnd
            out += Triple(s.toLocalDate(), s.hour * 60 + s.minute, if (end == dayEnd) 1440 else end.hour * 60 + end.minute)
            s = dayEnd
        }
        return out
    }

}
