package com.brr.app.reminder

import com.brr.app.domain.model.ReminderLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class InAppAlert(val taskId: Long, val title: String, val message: String, val level: ReminderLevel)

/** Bandeau animé affiché dans l'appli quand un rappel tombe alors qu'elle est au premier plan. */
object InAppAlerts {
    private val _alerts = MutableSharedFlow<InAppAlert>(extraBufferCapacity = 8)
    val alerts: SharedFlow<InAppAlert> = _alerts
    fun emit(a: InAppAlert) { _alerts.tryEmit(a) }
}
