package com.brr.app.punchline

import com.brr.app.domain.model.ReminderLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PunchlineEngineTest {
    private val all = PunchlineCatalog.all

    @Test fun `catalog has at least 150 unique punchlines across all categories`() {
        assertTrue(all.size >= 150)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.text }.toSet().size)
        PunchlineCategory.entries.forEach { c -> assertTrue("$c vide", all.count { it.category == c } >= 15) }
        assertTrue(all.all { it.intensity in 1..4 })
    }

    @Test fun `disabled categories are never picked`() {
        val engine = PunchlineEngine(Random(1))
        val disabled = PunchlineCategory.entries.toSet() - PunchlineCategory.ABSURDE
        repeat(200) {
            val p = engine.pick(all, PickRequest(ReminderLevel.INSISTANT, false, disabled, emptyList(), contextualEnabled = false))
            assertEquals(PunchlineCategory.ABSURDE, p.category)
        }
    }

    @Test fun `recent punchlines are avoided`() {
        val engine = PunchlineEngine(Random(42))
        var recent = emptyList<String>()
        val seen = mutableListOf<String>()
        repeat(30) {
            val p = engine.pick(all, PickRequest(ReminderLevel.INSISTANT, false, emptySet(), recent, contextualEnabled = false))
            seen += p.id!!
            recent = PunchlineEngine.pushRecent(recent, p.id)
        }
        assertEquals(seen.size, seen.toSet().size)
    }

    @Test fun `intensity matches level`() {
        val engine = PunchlineEngine(Random(3))
        val gentle = engine.candidates(all, PickRequest(ReminderLevel.NORMAL, false, emptySet(), emptyList()))
        assertTrue(gentle.all { it.intensity <= 2 })
        assertFalse(gentle.any { it.category == PunchlineCategory.DERNIER_AVERTISSEMENT })
        val brutal = engine.candidates(all, PickRequest(ReminderLevel.BRR, false, emptySet(), emptyList()))
        assertTrue(brutal.all { it.intensity >= 3 })
    }

    @Test fun `final warning uses the dedicated category`() {
        val c = PunchlineEngine().candidates(all, PickRequest(ReminderLevel.BRR, true, emptySet(), emptyList()))
        assertTrue(c.all { it.category == PunchlineCategory.DERNIER_AVERTISSEMENT })
    }

    @Test fun `everything disabled falls back safely`() {
        val p = PunchlineEngine().pick(all, PickRequest(ReminderLevel.BRR, false, PunchlineCategory.entries.toSet(), emptyList(), contextualEnabled = false))
        assertEquals(PunchlineEngine.FALLBACK.text, p.text)
    }

    @Test fun `contextual messages use the task name`() {
        assertTrue(ContextualPunchlines.keywordLines("Faire la vaisselle").any { it.contains("vaisselle ne va pas se laver") })
        val msg = ContextualPunchlines.generate("Sortir les poubelles", 37, 0, Random(5))
        assertFalse(msg.contains("{"))
        val generic = ContextualPunchlines.fill("« {task} » attend.", "Arroser", 0, 0)
        assertEquals("« Arroser » attend.", generic)
    }

    @Test fun `recent memory is bounded`() {
        var r = emptyList<String>()
        repeat(100) { r = PunchlineEngine.pushRecent(r, "id$it") }
        assertEquals(PunchlineEngine.RECENT_MEMORY, r.size)
        assertEquals("id99", r.last())
    }
}
