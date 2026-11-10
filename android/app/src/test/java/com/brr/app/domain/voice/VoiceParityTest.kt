package com.brr.app.domain.voice

import com.brr.app.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** La dictée et le planning donnent exactement les mêmes résultats qu'en JavaScript (design/fixtures/nlu.json). */
class VoiceParityTest {
    private fun moment(o: JSONObject) = Moment(LocalDate.parse(o.getString("date")), o.getInt("minute"))

    @Test fun `cas de dictée partagés avec le web`() {
        val cases = Fixtures.obj("nlu.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val r = VoiceParser.parse(c.getString("text"), moment(c.getJSONObject("now")))
            val exp = c.getJSONObject("expect")
            val ctx = c.getString("text").take(50)
            assertEquals(ctx, exp.getString("intent") == "plan", r.isPlanRequest)
            assertEquals(ctx, exp.getInt("questions"), r.questions.size)
            val et = exp.getJSONArray("tasks")
            assertEquals("$ctx : ${r.tasks.map { it.title }}", et.length(), r.tasks.size)
            for (k in 0 until et.length()) {
                val e = et.getJSONObject(k); val t = r.tasks[k]
                assertEquals(ctx, e.getString("title"), t.title)
                if (e.isNull("deadline")) assertEquals("$ctx ${t.title}", null, t.deadline)
                else e.getJSONObject("deadline").let { assertEquals("$ctx ${t.title}", it.getString("date") to it.getInt("minute"), t.deadline?.date.toString() to t.deadline?.minute) }
                if (e.isNull("window")) assertEquals("$ctx ${t.title}", null, t.window)
                else e.getJSONObject("window").let {
                    assertEquals("$ctx ${t.title}", listOf(it.getString("date"), it.getInt("start"), it.getInt("end"), it.getBoolean("fixed")),
                        listOf(t.window?.date.toString(), t.window?.start, t.window?.end, t.window?.fixed))
                }
                if (e.isNull("recurrence")) assertEquals(null, t.recurrence)
                else e.getJSONObject("recurrence").let { assertEquals("$ctx ${t.title}", it.getString("type").uppercase(), t.recurrence?.type?.name) }
                assertEquals("$ctx ${t.title}", e.getString("priority"), t.priority.name)
                assertEquals("$ctx ${t.title}", e.getString("level"), t.level.name)
                assertEquals("$ctx ${t.title}", e.getInt("minutes"), t.minutes)
                assertEquals("$ctx ${t.title}", if (e.isNull("sessionMinutes")) null else e.getInt("sessionMinutes"), t.sessionMinutes)
                val deps = e.getJSONArray("dependsOn")
                assertEquals("$ctx ${t.title}", (0 until deps.length()).map { deps.getString(it) }, t.dependsOn)
            }
        }
    }

    private val week = Fixtures.obj("student-week.json")
    private val now = moment(week.getJSONObject("now"))
    private val events = week.getJSONArray("events").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            SchedulePlanner.BusyEvent(LocalDate.parse(it.getString("date")), it.getInt("start"), it.getInt("end"), it.getString("title"),
                it.optString("location").ifEmpty { null }, it.optInt("travelBefore"), it.optInt("travelAfter"))
        }
    }

    @Test fun `semaine type planifiée sans violer aucune règle`() {
        val d = VoiceParser.parse(week.getString("dictation"), now)
        val r = SchedulePlanner.plan(d.tasks, events, now)
        assertEquals(emptyList<String>(), SchedulePlanner.validate(r, d.tasks, now))
        assertTrue(r.unplaced.toString(), r.feasible)
        val tonight = r.days[0].blocks.filter { it.kind == SchedulePlanner.Kind.TASK && it.title.contains("exposé") }
        assertTrue(tonight.isNotEmpty() && tonight.all { it.start >= 18 * 60 })
        assertTrue(r.days.all { it.free >= 60 })
    }

    @Test fun `planning impossible signalé avec des alternatives`() {
        val d = VoiceParser.parse("Je dois rédiger mon mémoire pour demain matin, ça va me prendre 10 heures.", now)
        val r = SchedulePlanner.plan(d.tasks, events, now)
        assertTrue(!r.feasible)
        assertTrue(r.unplaced[0].alternatives.size >= 2)
        assertEquals(emptyList<String>(), SchedulePlanner.validate(r, d.tasks, now))
    }

    @Test fun `propriété - 300 semaines aléatoires respectent toutes les règles`() {
        var seed = 42L
        fun rnd(): Double { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648.0 }
        val n = Moment(LocalDate.parse("2026-11-09"), 540)
        repeat(300) { k ->
            val ev = mutableListOf<SchedulePlanner.BusyEvent>()
            for (day in 0..6) repeat((rnd() * 4).toInt()) {
                val st = 480 + (rnd() * 24).toInt() * 30
                ev += SchedulePlanner.BusyEvent(n.date.plusDays(day.toLong()), st, minOf(1430, st + 60 + (rnd() * 4).toInt() * 30), "Cours", if (rnd() < 0.5) "Salle" else null)
            }
            val tasks = List(1 + (rnd() * 8).toInt()) { i ->
                VoiceTask("t$i", "Tâche $i", "tache $i", minutes = 15 + (rnd() * 30).toInt() * 10,
                    priority = com.brr.app.domain.model.Priority.entries[(rnd() * 4).toInt()],
                    deadline = if (rnd() < 0.5) Deadline(n.date.plusDays((rnd() * 7).toLong()), 480 + (rnd() * 28).toInt() * 30, "", false) else null,
                    recurrence = if (rnd() < 0.15) Recurrence(Recurrence.Type.DAILY) else null, sessionMinutes = 30,
                    dependsOn = if (rnd() < 0.2 && i > 0) mutableListOf("t${i - 1}") else mutableListOf(), horizon = Horizon(n.date, n.date.plusDays(6)))
            }
            val r = SchedulePlanner.plan(tasks, ev, n)
            assertEquals("semaine $k", emptyList<String>(), SchedulePlanner.validate(r, tasks, n))
        }
    }

    @Test fun `estimation - défaut, verbe et apprentissage`() {
        assertEquals(5 to 15, DurationEstimator.estimate("envoyer un mail").let { it.min to it.max })
        assertTrue(DurationEstimator.estimate("terminer mon expose").minutes < DurationEstimator.estimate("preparer mon expose").minutes)
        var l = emptyMap<String, List<Int>>()
        repeat(5) { l = DurationEstimator.learn(l, "faire les courses", 30) }
        assertTrue(DurationEstimator.estimate("faire les courses", l).let { it.learned && it.minutes < DurationEstimator.estimate("faire les courses").minutes })
        assertEquals(l, DurationEstimator.decode(DurationEstimator.encode(l)))
    }

    @Test fun `découpage en séances`() {
        assertEquals(listOf(55, 55), SchedulePlanner.chunks(110, 90, 25))
        assertEquals(listOf(60), SchedulePlanner.chunks(60, 90, 25))
    }
}
