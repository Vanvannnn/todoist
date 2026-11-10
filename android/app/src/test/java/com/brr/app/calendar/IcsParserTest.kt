package com.brr.app.calendar

import com.brr.app.Fixtures
import com.brr.app.optStringOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Mêmes attendus que le web (design/fixtures/ics.json), calculés à la main. */
class IcsParserTest {
    @Test fun `flux ICS partagés avec le web`() {
        val cases = Fixtures.obj("ics.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val cal = IcsParser.parse(c.getString("ics"))
            assertEquals(c.optStringOrNull("calName"), cal.name)
            val occ = IcsParser.expand(cal, Instant.parse(c.getString("from")).toEpochMilli(), Instant.parse(c.getString("to")).toEpochMilli(), ZoneId.of(c.getString("tz")))
            val exp = c.getJSONArray("expect")
            val expected = (0 until exp.length()).map { exp.getJSONObject(it) }.map {
                listOf(it.getString("title"), it.getString("start"), it.getString("end"), it.optStringOrNull("location"), it.getBoolean("allDay"))
            }
            val actual = occ.map { listOf(it.title, Instant.ofEpochMilli(it.start).toString(), Instant.ofEpochMilli(it.end).toString(), it.location, it.allDay) }
            assertEquals(c.getString("name"), expected, actual)
        }
    }

    @Test fun `tranches locales et journées entières`() {
        val c = Fixtures.obj("ics.json").getJSONArray("cases").getJSONObject(0)
        val z = ZoneId.of("Europe/Paris")
        val occ = IcsParser.expand(IcsParser.parse(c.getString("ics")), Instant.parse(c.getString("from")).toEpochMilli(), Instant.parse(c.getString("to")).toEpochMilli(), z)
        val slices = occ.flatMap { IcsParser.localSlices(it, z) }
        assertEquals(7, slices.size)
        assertEquals(Triple(java.time.LocalDate.parse("2026-11-09"), 480, 600), slices[0])
    }
}
