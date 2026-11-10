package com.brr.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.CustomPunchlineEntity
import com.brr.app.data.prefs.AppSettings
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode
import com.brr.app.punchline.PunchlineCategory
import com.brr.app.reminder.AlertFeedback
import com.brr.app.reminder.VerificationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.map { it as AppSettings? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val customPunchlines: StateFlow<List<CustomPunchlineEntity>> = c.punchlines.observeCustom()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sample = MutableStateFlow<String?>(null)
    val report = MutableStateFlow<VerificationReport?>(null)
    private val feedback = AlertFeedback(c.app)

    private fun edit(block: suspend () -> Unit, reschedule: Boolean = false) = viewModelScope.launch {
        block()
        if (reschedule) c.reminderCoordinator.rescheduleAll()
    }

    fun setTheme(m: ThemeMode) = edit({ c.settings.setTheme(m) })
    fun setDefaultLevel(l: ReminderLevel) = edit({ c.settings.setDefaultLevel(l) })
    fun setBrr(v: Boolean) = edit({ c.settings.setBrrMode(v) }, reschedule = true)
    fun setProgressive(v: Boolean) = edit({ c.settings.setProgressive(v) })
    fun setIntervals(v: List<Int>) = edit({ c.settings.setIntervals(v) }, reschedule = true)
    fun setMaxRelances(v: Int) = edit({ c.settings.setMaxRelances(v) }, reschedule = true)
    fun setAllowedHours(e: Boolean, s: Int, end: Int) = edit({ c.settings.setAllowedHours(e, s, end) }, reschedule = true)
    fun setAllDayMinute(v: Int) = edit({ c.settings.setAllDayMinute(v) }, reschedule = true)
    fun setSound(l: ReminderLevel, id: String) = edit({ c.settings.setSound(l, id) })
    fun setVolume(v: Float) = edit({ c.settings.setVolume(v) })
    fun setVibration(e: Boolean, s: Float) = edit({ c.settings.setVibration(e, s) })
    fun setCategory(cat: PunchlineCategory, enabled: Boolean) = edit({ c.settings.setCategoryEnabled(cat, enabled) })
    fun setContextual(v: Boolean) = edit({ c.settings.setContextual(v) })

    fun addPunchline(text: String, cat: PunchlineCategory, intensity: Int) = edit({ if (text.isNotBlank()) c.punchlines.addCustom(text, cat, intensity) })
    fun deletePunchline(p: CustomPunchlineEntity) = edit({ c.punchlines.deleteCustom(p) })

    fun drawSample(level: ReminderLevel) = viewModelScope.launch {
        sample.value = c.punchlines.pick(level, false, listOf("Faire la vaisselle", "Sortir les poubelles", "Répondre aux mails").random(), 23, 3).text
    }

    fun preview(level: ReminderLevel) = viewModelScope.launch { c.reminderEngine.preview(level, launchImmersive = true) }

    fun playSound(id: String) { feedback.preview(id, settings.value?.volume ?: 0.8f) }
    fun stopSound() = feedback.stop()

    fun verify() = viewModelScope.launch { report.value = c.reminderCoordinator.rescheduleAll() }

    override fun onCleared() { feedback.stop() }
}
