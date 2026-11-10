package com.brr.app.domain

import com.brr.app.domain.model.ReminderLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val settings = ReminderSettings(intervalsMinutes = listOf(5, 5, 5, 5, 10), maxRelances = 5)
    private fun at(h: Int, m: Int = 0, day: Int = 10) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun task(
        due: Long? = at(10), level: ReminderLevel = ReminderLevel.BRR, offsets: List<Int> = emptyList(),
        state: ReminderState = ReminderState(), hasTime: Boolean = true, nag: Int? = null,
    ) = PlannerTask(due, hasTime, completed = false, remindersEnabled = true, level = level, nagIntervalMinutes = nag, preOffsetsMinutes = offsets, state = state)

    @Test fun `next trigger is the due time when no pre-reminders`() {
        val t = ReminderPlanner.nextTrigger(task(), settings, at(9), zone)
        assertEquals(PlannedTrigger(at(10), TriggerKind.DUE), t)
    }

    @Test fun `pre-reminders come first`() {
        val t = ReminderPlanner.nextTrigger(task(offsets = listOf(15, 60)), settings, at(8), zone)
        assertEquals(PlannedTrigger(at(9), TriggerKind.PRE_REMINDER), t)
        val t2 = ReminderPlanner.nextTrigger(task(offsets = listOf(15, 60), state = ReminderState(lastFiredAt = at(9))), settings, at(9, 1), zone)
        assertEquals(PlannedTrigger(at(9, 45), TriggerKind.PRE_REMINDER), t2)
    }

    @Test fun `completed or disabled tasks never trigger`() {
        assertNull(ReminderPlanner.nextTrigger(task().copy(completed = true), settings, at(9), zone))
        assertNull(ReminderPlanner.nextTrigger(task().copy(remindersEnabled = false), settings, at(9), zone))
        assertNull(ReminderPlanner.nextTrigger(task(due = null), settings, at(9), zone))
    }

    @Test fun `missed due time catches up immediately but not if too old`() {
        assertEquals(PlannedTrigger(at(11), TriggerKind.DUE), ReminderPlanner.nextTrigger(task(), settings, at(11), zone))
        assertNull(ReminderPlanner.nextTrigger(task(), settings, at(23), zone))
    }

    @Test fun `full escalation scenario follows configured intervals then stops`() {
        var t = task()
        val fires = mutableListOf<Pair<Long, FirePlan>>()
        var now = at(10)
        for (i in 0 until 20) {
            val plan = ReminderPlanner.onFire(t, settings, now, zone) ?: break
            fires += now to plan
            t = t.copy(state = plan.newState)
            val next = ReminderPlanner.nextTrigger(t, settings, now + 1, zone) ?: break
            now = next.at
        }
        // échéance + 5 relances = 6 déclenchements, puis plus rien (pas de boucle infinie)
        assertEquals(6, fires.size)
        assertEquals(listOf(at(10), at(10, 5), at(10, 10), at(10, 15), at(10, 20), at(10, 30)), fires.map { it.first })
        assertEquals(
            listOf(ReminderLevel.NORMAL, ReminderLevel.INSISTANT, ReminderLevel.HARCELEMENT, ReminderLevel.BRR, ReminderLevel.BRR, ReminderLevel.BRR),
            fires.map { it.second.level },
        )
        assertTrue(fires.last().second.isFinalWarning)
        assertFalse(fires[4].second.isFinalWarning)
        assertNull(ReminderPlanner.nextTrigger(t, settings, now + 1, zone))
        // Une alarme parasite après le dernier avertissement ne produit rien.
        assertNull(ReminderPlanner.onFire(t, settings, now + 60 * min, zone))
    }

    @Test fun `escalation is capped by task level and by global BRR switch`() {
        val st = ReminderState(escalationStep = 3, lastFiredAt = at(10, 10))
        assertEquals(ReminderLevel.INSISTANT, ReminderPlanner.onFire(task(level = ReminderLevel.INSISTANT, state = st), settings, at(10, 15), zone)!!.level)
        val noBrr = settings.copy(brrModeEnabled = false)
        assertEquals(ReminderLevel.HARCELEMENT, ReminderPlanner.onFire(task(state = st), noBrr, at(10, 15), zone)!!.level)
    }

    @Test fun `without progressive escalation the task level applies from the start`() {
        val s = settings.copy(progressiveEscalation = false)
        assertEquals(ReminderLevel.BRR, ReminderPlanner.onFire(task(), s, at(10), zone)!!.level)
    }

    @Test fun `snooze takes precedence and restarts escalation gently`() {
        val fired = ReminderState(escalationStep = 4, lastFiredAt = at(10, 15))
        val snoozed = ReminderStateTransitions.snooze(fired, at(10, 30))
        assertEquals(1, snoozed.escalationStep)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(PlannedTrigger(at(10, 30), TriggerKind.SNOOZE), ReminderPlanner.nextTrigger(task(state = snoozed), settings, at(10, 16), zone))
        val plan = ReminderPlanner.onFire(task(state = snoozed), settings, at(10, 30), zone)!!
        assertEquals(TriggerKind.SNOOZE, plan.kind)
        assertNull(plan.newState.snoozedUntil)
        assertEquals(2, plan.newState.escalationStep)
    }

    @Test fun `snooze firing before due date does not start escalation`() {
        val st = ReminderStateTransitions.snooze(ReminderState(lastFiredAt = at(9)), at(9, 5))
        val plan = ReminderPlanner.onFire(task(offsets = listOf(60), state = st), settings, at(9, 5), zone)!!
        assertEquals(0, plan.newState.escalationStep)
        val next = ReminderPlanner.nextTrigger(task(offsets = listOf(60), state = plan.newState), settings, at(9, 6), zone)
        assertEquals(PlannedTrigger(at(10), TriggerKind.DUE), next)
    }

    @Test fun `per task nag interval overrides global intervals`() {
        val st = ReminderState(escalationStep = 1, lastFiredAt = at(10))
        assertEquals(at(10, 2), ReminderPlanner.nextTrigger(task(state = st, nag = 2), settings, at(10, 1), zone)!!.at)
    }

    @Test fun `all-day tasks ring at the configured hour`() {
        val midnight = at(0)
        val t = ReminderPlanner.nextTrigger(task(due = midnight, hasTime = false), settings.copy(allDayReminderMinute = 9 * 60 + 30), at(7), zone)
        assertEquals(at(9, 30), t!!.at)
    }

    @Test fun `allowed hours push triggers to the next window`() {
        val s = settings.copy(allowedHoursEnabled = true, allowedStartMinute = 8 * 60, allowedEndMinute = 22 * 60)
        assertEquals(at(8, 0, 11), ReminderPlanner.applyAllowedWindow(at(23), s, zone))
        assertEquals(at(8, 0, 10), ReminderPlanner.applyAllowedWindow(at(6), s, zone))
        assertEquals(at(12), ReminderPlanner.applyAllowedWindow(at(12), s, zone))
        // plage de nuit (22h → 6h)
        val night = settings.copy(allowedHoursEnabled = true, allowedStartMinute = 22 * 60, allowedEndMinute = 6 * 60)
        assertEquals(at(23), ReminderPlanner.applyAllowedWindow(at(23), night, zone))
        assertEquals(at(22), ReminderPlanner.applyAllowedWindow(at(12), night, zone))
    }

    @Test fun `stale fire for a future snooze is ignored`() {
        val st = ReminderState(snoozedUntil = at(11), escalationStep = 1, lastFiredAt = at(10))
        assertNull(ReminderPlanner.onFire(task(state = st), settings, at(10, 5), zone))
    }

    @Test fun `max relances zero means a single reminder`() {
        val s = settings.copy(maxRelances = 0)
        val plan = ReminderPlanner.onFire(task(), s, at(10), zone)!!
        assertTrue(plan.isFinalWarning)
        assertNull(ReminderPlanner.nextTrigger(task(state = plan.newState), s, at(10, 1), zone))
        assertNotNull(plan)
    }
}

/** Mode pause : mêmes cas que le web (design/fixtures/pause.json). */
class PausePlannerTest {
    private val day = 1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L
    private val M = 60_000L

    @org.junit.Test fun `reports attendus pendant cours et rendez-vous`() {
        val p = com.brr.app.Fixtures.obj("pause.json")
        val ev = p.getJSONArray("events")
        // Fusion des intervalles comme côté web (fin d'engagement + trajet).
        val raw = (0 until ev.length()).map { ev.getJSONObject(it) }.filter { it.getString("profile") != "PERSO" }
            .map { com.brr.app.domain.PauseWindow(day + it.getInt("start") * M, day + (it.getInt("end") + it.getInt("travelAfter")) * M, it.getString("title")) }
        val s = ReminderSettings(pauses = raw)
        val cases = p.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val task = PlannerTask(null, true, false, true, com.brr.app.domain.model.ReminderLevel.valueOf(c.getString("level")), null, emptyList(), ReminderState(), bypassPause = c.getBoolean("bypass"))
            org.junit.Assert.assertEquals(c.getString("why"), c.getInt("expect").toLong(), (ReminderPlanner.applyPauses(day + c.getInt("at") * M, s, task) - day) / M)
        }
    }

    @org.junit.Test fun `un rappel pendant un cours est décalé, jamais supprimé`() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        val due = day + 14 * 60 * M + 30 * M
        val s = ReminderSettings(pauses = listOf(com.brr.app.domain.PauseWindow(day + 14 * 60 * M, day + 16 * 60 * M + 30 * M, "Cours")))
        val task = PlannerTask(due, true, false, true, com.brr.app.domain.model.ReminderLevel.INSISTANT, null, emptyList(), ReminderState())
        org.junit.Assert.assertEquals(day + (16 * 60 + 30) * M, ReminderPlanner.nextTrigger(task, s, day + 9 * 60 * M, zone)?.at)
        org.junit.Assert.assertEquals(due, ReminderPlanner.nextTrigger(task, s.copy(pauseEnabled = false), day + 9 * 60 * M, zone)?.at)
    }
}
