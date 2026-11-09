package com.brr.app.domain

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceCalculatorTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 9) = LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun ldt(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test fun daily() = assertEquals(at(2026, 11, 11), RecurrenceCalculator.next(at(2026, 11, 10), RepeatType.DAILY, 1, 0, at(2026, 11, 10), zone))

    @Test fun `daily completed late skips past occurrences`() {
        val next = RecurrenceCalculator.next(at(2026, 11, 1), RepeatType.DAILY, 1, 0, at(2026, 11, 10, 12), zone)
        assertEquals(at(2026, 11, 11), next)
    }

    @Test fun `every 3 days`() = assertEquals(at(2026, 11, 13), RecurrenceCalculator.next(at(2026, 11, 10), RepeatType.DAILY, 3, 0, at(2026, 11, 10), zone))

    @Test fun `weekly on specific days`() {
        // 10/11/2026 est un mardi. Lundi + jeudi → jeudi 12
        val mask = (1 shl 0) or (1 shl 3)
        assertEquals(at(2026, 11, 12), RecurrenceCalculator.next(at(2026, 11, 10), RepeatType.WEEKLY, 1, mask, at(2026, 11, 10), zone))
        // depuis jeudi → lundi suivant
        assertEquals(at(2026, 11, 16), RecurrenceCalculator.next(at(2026, 11, 12), RepeatType.WEEKLY, 1, mask, at(2026, 11, 12), zone))
        // toutes les 2 semaines → lundi 23
        assertEquals(at(2026, 11, 23), RecurrenceCalculator.next(at(2026, 11, 12), RepeatType.WEEKLY, 2, mask, at(2026, 11, 12), zone))
    }

    @Test fun `monthly keeps end of month sane`() {
        assertEquals(LocalDateTime.of(2027, 2, 28, 9, 0), ldt(RecurrenceCalculator.next(at(2027, 1, 31), RepeatType.MONTHLY, 1, 0, at(2027, 1, 31), zone)!!))
    }

    @Test fun `no repeat returns null`() = assertNull(RecurrenceCalculator.next(at(2026, 11, 10), RepeatType.NONE, 1, 0, 0, zone))

    @Test fun `daylight saving keeps local hour`() {
        val next = RecurrenceCalculator.next(at(2026, 10, 24), RepeatType.DAILY, 1, 0, at(2026, 10, 24), zone)!!
        assertEquals(9, ldt(next).hour)
        assertEquals(25, ldt(next).dayOfMonth)
    }
}

class QuickAddParserTest {
    private val zone = ZoneId.of("Europe/Paris")
    // Mardi 10 novembre 2026, 14h
    private val now = LocalDateTime.of(2026, 11, 10, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun ldt(ms: Long?) = Instant.ofEpochMilli(ms!!).atZone(zone).toLocalDateTime()

    @Test fun `full sentence`() {
        val r = QuickAddParser.parse("Sortir les poubelles demain 20h !1 #Maison @brr", now, zone)
        assertEquals("Sortir les poubelles", r.title)
        assertEquals(LocalDateTime.of(2026, 11, 11, 20, 0), ldt(r.dueAt))
        assertTrue(r.hasTime)
        assertEquals(Priority.URGENT, r.priority)
        assertEquals("Maison", r.projectName)
        assertEquals(ReminderLevel.BRR, r.level)
    }

    @Test fun `relative time`() {
        val r = QuickAddParser.parse("Appeler maman dans 2h", now, zone)
        assertEquals("Appeler maman", r.title)
        assertEquals(LocalDateTime.of(2026, 11, 10, 16, 0), ldt(r.dueAt))
        val r2 = QuickAddParser.parse("Thé dans 30 min", now, zone)
        assertEquals(LocalDateTime.of(2026, 11, 10, 14, 30), ldt(r2.dueAt))
    }

    @Test fun `past time today moves to tomorrow`() {
        val r = QuickAddParser.parse("Café à 9h", now, zone)
        assertEquals("Café", r.title)
        assertEquals(LocalDateTime.of(2026, 11, 11, 9, 0), ldt(r.dueAt))
    }

    @Test fun `weekday without time is all-day`() {
        val r = QuickAddParser.parse("Réunion lundi", now, zone)
        assertEquals("Réunion", r.title)
        assertEquals(LocalDateTime.of(2026, 11, 16, 0, 0), ldt(r.dueAt))
        assertFalse(r.hasTime)
    }

    @Test fun `accents and case are ignored`() {
        val r = QuickAddParser.parse("Ranger APRÈS-DEMAIN @Harcèlement !HAUTE", now, zone)
        assertEquals("Ranger", r.title)
        assertEquals(ReminderLevel.HARCELEMENT, r.level)
        assertEquals(Priority.HIGH, r.priority)
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 0), ldt(r.dueAt))
    }

    @Test fun `plain text stays untouched`() {
        val r = QuickAddParser.parse("Lire un livre", now, zone)
        assertEquals("Lire un livre", r.title)
        assertNull(r.dueAt)
        assertNull(r.priority)
    }
}

class TaskBucketsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = LocalDateTime.of(2026, 11, 10, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun buckets() {
        assertEquals(Bucket.OVERDUE, TaskBuckets.bucketOf(at(10, 9), true, now, zone))
        assertEquals(Bucket.TODAY, TaskBuckets.bucketOf(at(10, 0), false, now, zone))
        assertEquals(Bucket.TODAY, TaskBuckets.bucketOf(at(10, 18), true, now, zone))
        assertEquals(Bucket.TOMORROW, TaskBuckets.bucketOf(at(11, 9), true, now, zone))
        assertEquals(Bucket.THIS_WEEK, TaskBuckets.bucketOf(at(15, 9), true, now, zone))
        assertEquals(Bucket.LATER, TaskBuckets.bucketOf(at(25, 9), true, now, zone))
        assertEquals(Bucket.OVERDUE, TaskBuckets.bucketOf(at(9, 0), false, now, zone))
        assertEquals(Bucket.NO_DATE, TaskBuckets.bucketOf(null, false, now, zone))
        assertTrue(TaskBuckets.inView(TaskView.TODAY, Bucket.OVERDUE))
        assertFalse(TaskBuckets.inView(TaskView.TOMORROW, Bucket.TODAY))
    }
}
