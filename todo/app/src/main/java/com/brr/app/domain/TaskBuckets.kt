package com.brr.app.domain

import java.time.Instant
import java.time.ZoneId

enum class TaskView(val label: String) {
    TODAY("Aujourd'hui"),
    TOMORROW("Demain"),
    WEEK("Cette semaine"),
    ALL("Toutes"),
    OVERDUE("En retard"),
}

enum class Bucket(val label: String) {
    OVERDUE("En retard"),
    TODAY("Aujourd'hui"),
    TOMORROW("Demain"),
    THIS_WEEK("Cette semaine"),
    LATER("Plus tard"),
    NO_DATE("Sans date"),
}

object TaskBuckets {
    fun bucketOf(dueAt: Long?, hasTime: Boolean, now: Long, zone: ZoneId): Bucket {
        if (dueAt == null) return Bucket.NO_DATE
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        return when {
            hasTime && dueAt < now -> Bucket.OVERDUE
            day.isBefore(today) -> Bucket.OVERDUE
            day == today -> Bucket.TODAY
            day == today.plusDays(1) -> Bucket.TOMORROW
            !day.isAfter(today.plusDays(6)) -> Bucket.THIS_WEEK
            else -> Bucket.LATER
        }
    }

    fun inView(view: TaskView, bucket: Bucket): Boolean = when (view) {
        TaskView.TODAY -> bucket == Bucket.TODAY || bucket == Bucket.OVERDUE
        TaskView.TOMORROW -> bucket == Bucket.TOMORROW
        TaskView.WEEK -> bucket in setOf(Bucket.OVERDUE, Bucket.TODAY, Bucket.TOMORROW, Bucket.THIS_WEEK)
        TaskView.ALL -> true
        TaskView.OVERDUE -> bucket == Bucket.OVERDUE
    }
}
