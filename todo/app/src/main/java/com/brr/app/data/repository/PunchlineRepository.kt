package com.brr.app.data.repository

import com.brr.app.data.db.BrrDatabase
import com.brr.app.data.db.CustomPunchlineEntity
import com.brr.app.data.prefs.AppSettings
import com.brr.app.data.prefs.SettingsRepository
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.punchline.PickRequest
import com.brr.app.punchline.PickedPunchline
import com.brr.app.punchline.Punchline
import com.brr.app.punchline.PunchlineCatalog
import com.brr.app.punchline.PunchlineCategory
import com.brr.app.punchline.PunchlineEngine
import kotlinx.coroutines.flow.Flow

class PunchlineRepository(
    db: BrrDatabase,
    private val settings: SettingsRepository,
    private val engine: PunchlineEngine = PunchlineEngine(),
) {
    private val dao = db.punchlineDao()

    fun observeCustom(): Flow<List<CustomPunchlineEntity>> = dao.observeAll()

    suspend fun addCustom(text: String, category: PunchlineCategory, intensity: Int) {
        dao.insert(CustomPunchlineEntity(text = text.trim(), category = category, intensity = intensity.coerceIn(1, 4),
            createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteCustom(p: CustomPunchlineEntity) = dao.delete(p)

    private suspend fun pool(): List<Punchline> = PunchlineCatalog.all + dao.getAll().map {
        Punchline("c${it.id}", it.text, it.category, it.intensity, custom = true)
    }

    /** Tire une punchline et mémorise son identifiant pour éviter les répétitions. */
    suspend fun pick(
        level: ReminderLevel,
        isFinal: Boolean,
        taskTitle: String?,
        minutesLate: Long = 0,
        snoozeCount: Int = 0,
        appSettings: AppSettings? = null,
    ): PickedPunchline {
        val s = appSettings ?: settings.current()
        val picked = engine.pick(
            pool(),
            PickRequest(level, isFinal, s.disabledCategories, s.recentPunchlines, taskTitle, minutesLate, snoozeCount, s.contextualPunchlines),
        )
        if (picked.id != null) settings.setRecentPunchlines(PunchlineEngine.pushRecent(s.recentPunchlines, picked.id))
        return picked
    }
}
