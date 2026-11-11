package com.brr.app.domain.voice

import com.brr.app.Fixtures
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Contrat IA côté Android : même conversion qu'en JavaScript (design/fixtures/understanding-parity.json), lecture défensive. */
class UnderstandingTest {
    private val fx = Fixtures.obj("understanding-parity.json")
    private val now = fx.getJSONObject("now").let { Moment(LocalDate.parse(it.getString("date")), it.getInt("minute")) }

    private fun list(a: JSONArray) = (0 until a.length()).map { a.get(it).toString() }

    @Test fun `conversion en séances identique au JavaScript`() {
        val cases = fx.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val u = UnderstandingCodec.parse(c.getJSONObject("understanding"), now.date)!!
            val p = UnderstandingCodec.toPlanInput(u, now)
            val et = c.getJSONObject("expect").getJSONArray("tasks")
            assertEquals((0 until et.length()).map { et.getJSONObject(it).getString("id") }, p.tasks.map { it.id })
            for (k in 0 until et.length()) {
                val e = et.getJSONObject(k); val t = p.tasks[k]; val at = "cas $i · ${t.id}"
                assertEquals(at, e.getString("title"), t.title)
                assertEquals(at, if (e.isNull("group")) null else e.getString("group"), t.group)
                assertEquals(at, list(e.getJSONArray("subtasks")), t.subtasks)
                assertEquals(at, e.getInt("minutes"), t.minutes)
                assertEquals(at, e.getString("priority"), t.priority.name)
                assertEquals(at, e.getString("level"), t.level.name)
                assertEquals(at, list(e.getJSONArray("dependsOn")), t.dependsOn)
                assertEquals(at, if (e.isNull("window")) null else e.getJSONObject("window").let { listOf(it.getString("date"), it.getInt("start"), it.getInt("end"), it.getBoolean("fixed")) },
                    t.window?.let { listOf(it.date.toString(), it.start, it.end, it.fixed) })
                assertEquals(at, if (e.isNull("deadline")) null else e.getJSONObject("deadline").let { it.getString("date") to it.getInt("minute") }, t.deadline?.let { it.date.toString() to it.minute })
                assertEquals(at, if (e.isNull("recurrence")) null else list(e.getJSONObject("recurrence").getJSONArray("dates")), t.recurrence?.dates?.map { it.toString() })
                assertEquals(at, if (e.isNull("sessionMinutes")) null else e.getInt("sessionMinutes"), t.sessionMinutes)
                assertEquals(at, e.getString("horizonUntil"), t.horizon?.until.toString())
            }
            val ec = c.getJSONObject("expect").getJSONArray("constraints")
            assertEquals((0 until ec.length()).map { ec.getJSONObject(it).let { o -> listOf(o.getString("date"), o.getInt("start"), o.getInt("end"), o.getString("label")) } },
                p.constraints.map { listOf(it.date.toString(), it.start, it.end, it.label) })
        }
    }

    @Test fun `lecture défensive - structure inutilisable refusée, champs invalides ignorés`() {
        assertNull(UnderstandingCodec.parse(JSONObject("""{"foo":1}"""), now.date))
        val u = UnderstandingCodec.parse(JSONObject("""{"tasks":[
            {"id":"t1","title":"  Courses  ","planned_day":{"date":"2026-11-03","said":"le 3"},"duration_minutes":9999,"priority":"énorme","start_time":"25:00"},
            {"id":"t2","title":""}
          ],"constraints":[{"date":"2026-11-10","start_time":"18:00","end_time":"17:00","reason":"x","said":""}],"questions":[],"summary":"s"}"""), now.date)
        assertNotNull(u)
        val t = u!!.tasks.single()
        assertEquals("Courses", t.title)
        assertNull("date passée ignorée", t.plannedDay)
        assertNull("durée absurde ignorée", t.durationMinutes)
        assertEquals("normal", t.priority)
        assertNull(t.startTime)
        assertTrue("plage incohérente ignorée", u.constraints.isEmpty())
    }

    @Test fun `aller-retour JSON - le contexte renvoyé au serveur est identique`() {
        val c = fx.getJSONArray("cases").getJSONObject(0).getJSONObject("understanding")
        val u = UnderstandingCodec.parse(c, now.date)!!
        assertEquals(u, UnderstandingCodec.parse(UnderstandingCodec.toJson(u), now.date))
    }

    @Test fun `retouches - modifient la compréhension sans toucher l'original`() {
        val u = UnderstandingCodec.parse(fx.getJSONArray("cases").getJSONObject(0).getJSONObject("understanding"), now.date)!!
        val moved = UnderstandingCodec.applyEdit(u, "t1/t1.1", UnderstandingCodec.Edit.Day(LocalDate.parse("2026-11-11")))
        assertEquals(LocalDate.parse("2026-11-11"), moved.tasks[0].subtasks[0].plannedDay?.date)
        assertEquals(LocalDate.parse("2026-11-10"), u.tasks[0].subtasks[0].plannedDay?.date)
        val removed = UnderstandingCodec.applyEdit(moved, "t1", UnderstandingCodec.Edit.Remove)
        assertEquals("la séance principale disparaît, les séances datées restent", listOf("t1.1", "t1.2"), removed.tasks[0].subtasks.map { it.id })
        val gone = UnderstandingCodec.applyEdit(u, "t4", UnderstandingCodec.Edit.Remove)
        assertTrue(gone.tasks.none { it.id == "t4" })
        val longer = UnderstandingCodec.applyEdit(u, "t2", UnderstandingCodec.Edit.Minutes(60))
        assertEquals(60, longer.tasks[1].sessionMinutes)
    }
}
