package com.brr.app.reminder

import com.brr.app.data.db.ReminderEntity
import com.brr.app.data.db.TaskEntity
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.ReminderSettings
import com.brr.app.domain.ReminderState
import com.brr.app.domain.TriggerKind
import com.brr.app.domain.model.HistoryType
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Faux AlarmManager : mémorise les alarmes programmées. */
class FakeScheduler(var exact: Boolean = true) : AlarmScheduler {
    val alarms = mutableMapOf<Long, Long>()
    val cancelled = mutableListOf<Long>()
    override fun schedule(taskId: Long, triggerAt: Long, level: ReminderLevel) { alarms[taskId] = triggerAt }
    override fun cancel(taskId: Long) { alarms.remove(taskId); cancelled += taskId }
    override fun isScheduled(taskId: Long) = taskId in alarms
    override fun canScheduleExact() = exact
}

class FakeStore : ReminderStore {
    val tasks = mutableMapOf<Long, TaskEntity>()
    val offsets = mutableMapOf<Long, List<Int>>()
    val logs = mutableListOf<Pair<Long?, HistoryType>>()

    fun put(t: TaskEntity, pre: List<Int> = emptyList()) { tasks[t.id] = t; offsets[t.id] = pre }

    override suspend fun task(id: Long): TaskWithDetails? = tasks[id]?.let { t ->
        TaskWithDetails(t, null, emptyList(), offsets[id].orEmpty().mapIndexed { i, m -> ReminderEntity(i.toLong(), id, m) })
    }
    override suspend fun schedulableTasks() = tasks.values.filter { !it.completed && (it.dueAt != null || it.snoozedUntil != null) }.mapNotNull { task(it.id) }
    override suspend fun staleScheduledIds() = tasks.values.filter { (it.completed || !it.remindersEnabled) && it.nextReminderAt != null }.map { it.id }
    override suspend fun saveState(taskId: Long, state: ReminderState, next: Long?) { tasks[taskId] = tasks.getValue(taskId).withReminderState(state, next) }
    override suspend fun markCompleted(taskId: Long, at: Long) { tasks[taskId] = tasks.getValue(taskId).copy(completed = true, completedAt = at) }
    override suspend fun markOpen(taskId: Long) { tasks[taskId] = tasks.getValue(taskId).copy(completed = false, completedAt = null) }
    override suspend fun moveDue(taskId: Long, newDue: Long, hasTime: Boolean) {
        tasks[taskId] = tasks.getValue(taskId).copy(dueAt = newDue, hasTime = hasTime).withReminderState(ReminderState(), null)
    }
    override suspend fun setRemindersEnabled(taskId: Long, enabled: Boolean) { tasks[taskId] = tasks.getValue(taskId).copy(remindersEnabled = enabled) }
    override suspend fun resetSubtasks(taskId: Long) = Unit
    override suspend fun log(taskId: Long?, title: String, type: HistoryType, detail: String) { logs += taskId to type }
}

class ReminderCoordinatorTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int = 0, d: Int = 10) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private lateinit var store: FakeStore
    private lateinit var scheduler: FakeScheduler
    private val dismissed = mutableListOf<Long>()
    private var now = 0L
    private var settings = ReminderSettings(intervalsMinutes = listOf(5, 5, 5, 5, 10), maxRelances = 5)
    private lateinit var c: ReminderCoordinator

    private fun task(id: Long, due: Long?, level: ReminderLevel = ReminderLevel.BRR, repeat: RepeatType = RepeatType.NONE) =
        TaskEntity(id = id, title = "Tâche $id", dueAt = due, hasTime = true, reminderLevel = level, repeatType = repeat, createdAt = 0, updatedAt = 0)

    @Before fun setup() {
        store = FakeStore(); scheduler = FakeScheduler(); dismissed.clear(); now = at(9)
        c = ReminderCoordinator(store, scheduler, { dismissed += it }, { settings }, { now }, { zone })
    }

    @Test fun `scheduling a task programs its alarm`() = runTest {
        store.put(task(1, at(10)), pre = listOf(30))
        assertEquals(at(9, 30), c.reschedule(1))
        assertEquals(at(9, 30), scheduler.alarms[1])
        assertEquals(at(9, 30), store.tasks[1]!!.nextReminderAt)
    }

    @Test fun `completing a task cancels every reminder immediately`() = runTest {
        store.put(task(1, at(10)))
        c.reschedule(1)
        assertTrue(scheduler.isScheduled(1))
        c.complete(1)
        assertFalse(scheduler.isScheduled(1))
        assertTrue(1L in dismissed)
        assertTrue(store.tasks[1]!!.completed)
        assertNull(store.tasks[1]!!.nextReminderAt)
        assertTrue(store.logs.contains(1L to HistoryType.COMPLETED))
        // Une alarme résiduelle qui sonnerait quand même ne produit rien.
        now = at(10)
        assertNull(c.handleFire(1))
    }

    @Test fun `full escalation then silence`() = runTest {
        store.put(task(1, at(10)))
        c.reschedule(1)
        val levels = mutableListOf<ReminderLevel>()
        var guard = 0
        while (scheduler.alarms[1] != null && guard++ < 50) {
            now = scheduler.alarms.getValue(1)
            val out = c.handleFire(1) ?: break
            levels += out.plan.level
        }
        assertEquals(6, levels.size)
        assertEquals(ReminderLevel.BRR, levels.last())
        assertFalse("plus aucune alarme après la dernière relance", scheduler.isScheduled(1))
    }

    @Test fun `snooze reprograms and dismisses the notification`() = runTest {
        store.put(task(1, at(10)))
        now = at(10); c.reschedule(1); c.handleFire(1)
        now = at(10, 1)
        val next = c.snooze(1, 15)
        assertEquals(at(10, 16), next)
        assertEquals(at(10, 16), scheduler.alarms[1])
        assertTrue(1L in dismissed)
        now = at(10, 16)
        val out = c.handleFire(1)!!
        assertEquals(TriggerKind.SNOOZE, out.plan.kind)
        assertEquals(at(10, 21), scheduler.alarms[1])
    }

    @Test fun `postpone to tomorrow keeps the hour and resets escalation`() = runTest {
        store.put(task(1, at(10)))
        now = at(10); c.reschedule(1); c.handleFire(1)
        now = at(10, 2)
        c.postponeToTomorrow(1)
        assertEquals(at(10, 0, 11), store.tasks[1]!!.dueAt)
        assertEquals(0, store.tasks[1]!!.escalationStep)
        assertEquals(at(10, 0, 11), scheduler.alarms[1])
    }

    @Test fun `recurring task moves to next occurrence and stays scheduled`() = runTest {
        store.put(task(1, at(10), repeat = RepeatType.DAILY))
        now = at(10, 5)
        val next = c.complete(1)
        assertEquals(at(10, 0, 11), next)
        assertFalse(store.tasks[1]!!.completed)
        assertEquals(at(10, 0, 11), scheduler.alarms[1])
    }

    @Test fun `disabling reminders cancels alarm`() = runTest {
        store.put(task(1, at(10)))
        c.reschedule(1)
        c.disableReminders(1)
        assertFalse(scheduler.isScheduled(1))
        assertFalse(store.tasks[1]!!.remindersEnabled)
        assertNull(c.reschedule(1))
    }

    @Test fun `stale alarm is ignored and rescheduled`() = runTest {
        store.put(task(1, at(12)))
        c.reschedule(1)
        now = at(10) // une vieille alarme de 10h sonne alors que l'échéance a été déplacée à 12h
        assertNull(c.handleFire(1))
        assertEquals(at(12), scheduler.alarms[1])
    }

    @Test fun `reschedule all repairs lost alarms and cleans stale ones`() = runTest {
        store.put(task(1, at(10)))
        store.put(task(2, at(11)))
        store.put(task(3, at(12)))
        c.rescheduleAll()
        scheduler.alarms.remove(2) // alarme supprimée par le système
        store.tasks[3] = store.tasks[3]!!.copy(completed = true) // terminée sans annulation (cas limite)
        val report = c.rescheduleAll()
        assertEquals(2, report.scheduled)
        assertEquals(1, report.repaired)
        assertEquals(1, report.cancelled)
        assertNotNull(scheduler.alarms[2])
        assertNull(scheduler.alarms[3])
    }

    @Test fun `deleted task alarm is cancelled`() = runTest {
        assertNull(c.handleFire(99))
        assertTrue(99L in scheduler.cancelled)
    }

    @Test fun `allowed hours delay night alarms`() = runTest {
        settings = settings.copy(allowedHoursEnabled = true, allowedStartMinute = 8 * 60, allowedEndMinute = 22 * 60)
        store.put(task(1, at(23)))
        c.reschedule(1)
        assertEquals(at(8, 0, 11), scheduler.alarms[1])
    }
}
