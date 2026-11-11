package com.brr.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brr.app.domain.ReminderSettings
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode
import com.brr.app.punchline.PunchlineCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "brr_settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultLevel: ReminderLevel = ReminderLevel.INSISTANT,
    val brrModeEnabled: Boolean = true,
    val progressiveEscalation: Boolean = true,
    val intervals: List<Int> = DEFAULT_INTERVALS,
    val maxRelances: Int = 5,
    val allowedHoursEnabled: Boolean = false,
    val allowedStartMinute: Int = 8 * 60,
    val allowedEndMinute: Int = 22 * 60,
    val allDayReminderMinute: Int = 9 * 60,
    val sounds: Map<ReminderLevel, String> = DEFAULT_SOUNDS,
    val volume: Float = 0.8f,
    val vibrationEnabled: Boolean = true,
    val vibrationStrength: Float = 1f,
    val disabledCategories: Set<PunchlineCategory> = emptySet(),
    val contextualPunchlines: Boolean = true,
    val recentPunchlines: List<String> = emptyList(),
    // V2
    /** Intensité maximale de l'humour (1 douce … 4 nucléaire). */
    val humorMax: Int = 4,
    val pauseEnabled: Boolean = true,
    /** Coupure manuelle de la synchronisation des calendriers. */
    val calendarSyncEnabled: Boolean = true,
    val pausedProfiles: Set<String> = DEFAULT_PAUSED_PROFILES,
    val learnedDurations: Map<String, List<Int>> = emptyMap(),
    val maxFocusMinutes: Int = 300,
    val voiceConsent: Boolean = false,
    // V2.1 : assistant IA (compréhension des dictées par un serveur qui garde la clé API)
    val aiEnabled: Boolean = false,
    val aiServerUrl: String = "",
    val aiToken: String = "",
    /** Accord explicite pour envoyer le TEXTE des dictées au service IA (jamais l'audio). */
    val aiConsent: Boolean = false,
) {
    /** L'IA n'est utilisée que si l'utilisateur l'a activée, configurée et acceptée. */
    val aiReady get() = aiEnabled && aiConsent && aiServerUrl.isNotBlank()

    fun reminderSettings(pauses: List<com.brr.app.domain.PauseWindow> = emptyList()) = ReminderSettings(
        pauses = pauses,
        pauseEnabled = pauseEnabled,
        brrModeEnabled = brrModeEnabled,
        progressiveEscalation = progressiveEscalation,
        intervalsMinutes = intervals,
        maxRelances = maxRelances,
        allowedHoursEnabled = allowedHoursEnabled,
        allowedStartMinute = allowedStartMinute,
        allowedEndMinute = allowedEndMinute,
        allDayReminderMinute = allDayReminderMinute,
    )

    fun soundFor(level: ReminderLevel): String = sounds[level] ?: DEFAULT_SOUNDS.getValue(level)

    companion object {
        val DEFAULT_INTERVALS = listOf(5, 5, 5, 5, 10)
        val DEFAULT_PAUSED_PROFILES = setOf("COURS", "RDV", "TRAVAIL")
        val DEFAULT_SOUNDS = mapOf(
            ReminderLevel.NORMAL to "brr_carillon",
            ReminderLevel.INSISTANT to "brr_ping",
            ReminderLevel.HARCELEMENT to "brr_klaxon",
            ReminderLevel.BRR to "brr_sirene",
        )
    }
}

class SettingsRepository(private val context: Context) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val defaultLevel = stringPreferencesKey("default_level")
        val brr = booleanPreferencesKey("brr_mode")
        val progressive = booleanPreferencesKey("progressive")
        val intervals = stringPreferencesKey("intervals")
        val maxRelances = intPreferencesKey("max_relances")
        val allowedEnabled = booleanPreferencesKey("allowed_enabled")
        val allowedStart = intPreferencesKey("allowed_start")
        val allowedEnd = intPreferencesKey("allowed_end")
        val allDay = intPreferencesKey("all_day_minute")
        fun sound(l: ReminderLevel) = stringPreferencesKey("sound_${l.name}")
        val volume = floatPreferencesKey("volume")
        val vibration = booleanPreferencesKey("vibration")
        val vibrationStrength = floatPreferencesKey("vibration_strength")
        val disabledCats = stringSetPreferencesKey("disabled_categories")
        val contextual = booleanPreferencesKey("contextual")
        val recent = stringPreferencesKey("recent_punchlines")
        val humorMax = intPreferencesKey("humor_max")
        val pause = booleanPreferencesKey("pause_enabled")
        val calSync = booleanPreferencesKey("calendar_sync")
        val pausedProfiles = stringSetPreferencesKey("paused_profiles")
        val learned = stringPreferencesKey("learned_durations")
        val maxFocus = intPreferencesKey("max_focus")
        val voiceConsent = booleanPreferencesKey("voice_consent")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val aiUrl = stringPreferencesKey("ai_url")
        val aiToken = stringPreferencesKey("ai_token")
        val aiConsent = booleanPreferencesKey("ai_consent")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = this[K.onboarding] ?: d.onboardingDone,
            themeMode = enumOr(this[K.theme], d.themeMode),
            defaultLevel = enumOr(this[K.defaultLevel], d.defaultLevel),
            brrModeEnabled = this[K.brr] ?: d.brrModeEnabled,
            progressiveEscalation = this[K.progressive] ?: d.progressiveEscalation,
            intervals = parseIntervals(this[K.intervals]) ?: d.intervals,
            maxRelances = this[K.maxRelances] ?: d.maxRelances,
            allowedHoursEnabled = this[K.allowedEnabled] ?: d.allowedHoursEnabled,
            allowedStartMinute = this[K.allowedStart] ?: d.allowedStartMinute,
            allowedEndMinute = this[K.allowedEnd] ?: d.allowedEndMinute,
            allDayReminderMinute = this[K.allDay] ?: d.allDayReminderMinute,
            sounds = ReminderLevel.entries.associateWith { this[K.sound(it)] ?: d.soundFor(it) },
            volume = this[K.volume] ?: d.volume,
            vibrationEnabled = this[K.vibration] ?: d.vibrationEnabled,
            vibrationStrength = this[K.vibrationStrength] ?: d.vibrationStrength,
            disabledCategories = (this[K.disabledCats] ?: emptySet())
                .mapNotNull { n -> PunchlineCategory.entries.firstOrNull { it.name == n } }.toSet(),
            contextualPunchlines = this[K.contextual] ?: d.contextualPunchlines,
            recentPunchlines = this[K.recent]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            humorMax = this[K.humorMax] ?: d.humorMax,
            pauseEnabled = this[K.pause] ?: d.pauseEnabled,
            calendarSyncEnabled = this[K.calSync] ?: d.calendarSyncEnabled,
            pausedProfiles = this[K.pausedProfiles] ?: d.pausedProfiles,
            learnedDurations = com.brr.app.domain.voice.DurationEstimator.decode(this[K.learned]),
            maxFocusMinutes = this[K.maxFocus] ?: d.maxFocusMinutes,
            voiceConsent = this[K.voiceConsent] ?: d.voiceConsent,
            aiEnabled = this[K.aiEnabled] ?: d.aiEnabled,
            aiServerUrl = this[K.aiUrl] ?: d.aiServerUrl,
            aiToken = this[K.aiToken] ?: d.aiToken,
            aiConsent = this[K.aiConsent] ?: d.aiConsent,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun setTheme(m: ThemeMode) = context.dataStore.edit { it[K.theme] = m.name }
    suspend fun setDefaultLevel(l: ReminderLevel) = context.dataStore.edit { it[K.defaultLevel] = l.name }
    suspend fun setBrrMode(v: Boolean) = context.dataStore.edit { it[K.brr] = v }
    suspend fun setProgressive(v: Boolean) = context.dataStore.edit { it[K.progressive] = v }
    suspend fun setIntervals(v: List<Int>) = context.dataStore.edit {
        it[K.intervals] = v.filter { m -> m in 1..1440 }.ifEmpty { AppSettings.DEFAULT_INTERVALS }.joinToString(",")
    }
    suspend fun setMaxRelances(v: Int) = context.dataStore.edit { it[K.maxRelances] = v.coerceIn(0, 30) }
    suspend fun setAllowedHours(enabled: Boolean, start: Int, end: Int) = context.dataStore.edit {
        it[K.allowedEnabled] = enabled; it[K.allowedStart] = start.coerceIn(0, 1439); it[K.allowedEnd] = end.coerceIn(0, 1439)
    }
    suspend fun setAllDayMinute(v: Int) = context.dataStore.edit { it[K.allDay] = v.coerceIn(0, 1439) }
    suspend fun setSound(level: ReminderLevel, soundId: String) = context.dataStore.edit { it[K.sound(level)] = soundId }
    suspend fun setVolume(v: Float) = context.dataStore.edit { it[K.volume] = v.coerceIn(0f, 1f) }
    suspend fun setVibration(enabled: Boolean, strength: Float) = context.dataStore.edit {
        it[K.vibration] = enabled; it[K.vibrationStrength] = strength.coerceIn(0.5f, 2f)
    }
    suspend fun setCategoryEnabled(c: PunchlineCategory, enabled: Boolean) = context.dataStore.edit {
        val cur = it[K.disabledCats] ?: emptySet()
        it[K.disabledCats] = if (enabled) cur - c.name else cur + c.name
    }
    suspend fun setContextual(v: Boolean) = context.dataStore.edit { it[K.contextual] = v }
    suspend fun setRecentPunchlines(ids: List<String>) = context.dataStore.edit { it[K.recent] = ids.joinToString(",") }
    suspend fun setHumorMax(v: Int) = context.dataStore.edit { it[K.humorMax] = v.coerceIn(1, 4) }
    suspend fun setPauseEnabled(v: Boolean) = context.dataStore.edit { it[K.pause] = v }
    suspend fun setCalendarSync(v: Boolean) = context.dataStore.edit { it[K.calSync] = v }
    suspend fun setProfilePaused(profile: String, paused: Boolean) = context.dataStore.edit {
        val cur = it[K.pausedProfiles] ?: AppSettings.DEFAULT_PAUSED_PROFILES
        it[K.pausedProfiles] = if (paused) cur + profile else cur - profile
    }
    suspend fun setMaxFocus(v: Int) = context.dataStore.edit { it[K.maxFocus] = v.coerceIn(60, 720) }
    suspend fun setVoiceConsent() = context.dataStore.edit { it[K.voiceConsent] = true }
    suspend fun setAiEnabled(v: Boolean) = context.dataStore.edit { it[K.aiEnabled] = v }
    suspend fun setAiServer(url: String, token: String) = context.dataStore.edit { it[K.aiUrl] = url.trim(); it[K.aiToken] = token.trim() }
    suspend fun setAiConsent(v: Boolean) = context.dataStore.edit { it[K.aiConsent] = v }
    suspend fun learnDuration(normTitle: String, minutes: Int) = context.dataStore.edit {
        val cur = com.brr.app.domain.voice.DurationEstimator.decode(it[K.learned])
        it[K.learned] = com.brr.app.domain.voice.DurationEstimator.encode(com.brr.app.domain.voice.DurationEstimator.learn(cur, normTitle, minutes))
    }

    companion object {
        fun parseIntervals(raw: String?): List<Int>? =
            raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it in 1..1440 }?.takeIf { it.isNotEmpty() }
    }
}
