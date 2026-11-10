package com.brr.app.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.brr.app.data.db.BrrDatabase
import com.brr.app.data.db.CalendarEventEntity
import com.brr.app.data.db.CalendarSourceEntity
import com.brr.app.data.prefs.SettingsRepository
import com.brr.app.domain.PauseWindow
import com.brr.app.domain.voice.SchedulePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/** Profils de pause : ce qu'un engagement suspend, et le trajet à prévoir autour. */
enum class PauseProfile(val label: String, val travelBefore: Int, val travelAfter: Int) {
    COURS("Cours", 20, 20), RDV("Rendez-vous", 20, 20), TRAVAIL("Travail", 30, 30), PERSO("Temps personnel", 0, 0);
    companion object { fun of(name: String) = entries.firstOrNull { it.name == name } ?: COURS }
}

/**
 * Calendriers en lecture seule : lien iCal (CELCAT, iCloud public, Google « adresse secrète »), fichier .ics,
 * agendas déjà synchronisés sur le téléphone (Google Agenda…) via CalendarContract — sans mot de passe ni OAuth.
 */
class CalendarRepository(private val context: Context, db: BrrDatabase, private val settings: SettingsRepository) {
    private val dao = db.calendarDao()
    private val zone get() = ZoneId.systemDefault()

    fun observeSources(): Flow<List<CalendarSourceEntity>> = dao.observeSources()
    fun observeUpcoming(): Flow<List<CalendarEventEntity>> = dao.observeUpcoming(System.currentTimeMillis())

    suspend fun addUrl(name: String, rawUrl: String, profile: PauseProfile): Result<Int> {
        val url = normalizeUrl(rawUrl) ?: return Result.failure(IllegalArgumentException("Le lien doit commencer par https:// ou webcal://"))
        val id = dao.insertSource(CalendarSourceEntity(name = name.ifBlank { URL(url).host }, kind = KIND_URL, url = url, profile = profile.name))
        return sync(id)
    }

    /** Un fichier n'est pas actualisé : ses occurrences sont développées une fois, sur 6 mois. */
    suspend fun addFile(name: String, text: String, profile: PauseProfile): Result<Int> = runCatching {
        val cal = IcsParser.parse(text)
        require(cal.events.isNotEmpty()) { "Aucun événement trouvé dans ce fichier." }
        val id = dao.insertSource(CalendarSourceEntity(name = cal.name ?: name, kind = KIND_FILE, profile = profile.name))
        store(id, IcsParser.expand(cal, System.currentTimeMillis() - DAY, System.currentTimeMillis() + 183 * DAY))
    }

    fun hasCalendarPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    data class DeviceCalendar(val id: Long, val name: String, val account: String)

    /** Agendas présents sur le téléphone (Google, Exchange, iCloud via DAVx5…). Nécessite READ_CALENDAR. */
    suspend fun deviceCalendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission()) return@withContext emptyList()
        val out = mutableListOf<DeviceCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            null, null, null)?.use { c -> while (c.moveToNext()) out += DeviceCalendar(c.getLong(0), c.getString(1) ?: "Agenda", c.getString(2) ?: "") }
        out
    }

    suspend fun addDevice(cal: DeviceCalendar, profile: PauseProfile): Result<Int> {
        val id = dao.insertSource(CalendarSourceEntity(name = cal.name, kind = KIND_DEVICE, deviceCalendarId = cal.id, profile = profile.name))
        return sync(id)
    }

    suspend fun remove(id: Long) = dao.deleteSource(id)
    suspend fun update(s: CalendarSourceEntity) = dao.updateSource(s)

    /** Synchronise une source (ou toutes) ; ne lève jamais : l'erreur est enregistrée sur la source. */
    suspend fun sync(id: Long? = null): Result<Int> {
        if (!settings.current().calendarSyncEnabled) return Result.failure(IllegalStateException("Synchronisation désactivée"))
        var total = 0; var last: Throwable? = null
        for (s in dao.sources().filter { id == null || it.id == id }) {
            val now = System.currentTimeMillis()
            val r = runCatching {
                when (s.kind) {
                    KIND_URL -> store(s.id, IcsParser.expand(IcsParser.parse(download(s.url!!)), now - DAY, now + 60 * DAY))
                    KIND_DEVICE -> store(s.id, readDevice(s.deviceCalendarId!!, now - DAY, now + 60 * DAY))
                    else -> s.eventCount
                }
            }
            r.onSuccess { total += it }.onFailure { last = it }
            dao.updateSource((dao.source(s.id) ?: continue).copy(lastSyncAt = now, lastError = r.exceptionOrNull()?.let(::describe)))
        }
        return last?.takeIf { id != null }?.let { Result.failure(it) } ?: Result.success(total)
    }

    private suspend fun store(sourceId: Long, occ: List<IcsParser.Occurrence>): Int {
        dao.clearEvents(sourceId)
        dao.insertEvents(occ.map { CalendarEventEntity(sourceId = sourceId, title = it.title, location = it.location, start = it.start, end = it.end, allDay = it.allDay) })
        dao.source(sourceId)?.let { dao.updateSource(it.copy(eventCount = occ.size)) }
        return occ.size
    }

    private suspend fun readDevice(calendarId: Long, from: Long, to: Long): List<IcsParser.Occurrence> = withContext(Dispatchers.IO) {
        check(hasCalendarPermission()) { "Autorisation « Agenda » refusée." }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<IcsParser.Occurrence>()
        context.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS),
            "${CalendarContract.Instances.CALENDAR_ID} = ?", arrayOf(calendarId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == CalendarContract.Instances.STATUS_CANCELED) continue
                out += IcsParser.Occurrence(null, c.getString(0) ?: "(sans titre)", c.getString(1)?.ifBlank { null }, c.getLong(2), c.getLong(3), c.getInt(4) == 1)
            }
        }
        out
    }

    /** Téléchargement borné d'un flux iCal (frontière de confiance : schéma, taille, délai). */
    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
            setRequestProperty("User-Agent", "Bullylist-Android")
        }
        try {
            check(conn.responseCode in 200..299) { "Le serveur a répondu ${conn.responseCode}." }
            check(conn.url.protocol == "https") { "Redirection vers une adresse non sécurisée refusée." }
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BYTES + 1) }
            check(bytes.size <= MAX_BYTES) { "Calendrier trop volumineux (plus de 5 Mo)." }
            val text = String(bytes, Charsets.UTF_8)
            check(text.contains("BEGIN:VCALENDAR")) { "Ce lien ne renvoie pas un calendrier iCal." }
            text
        } finally { conn.disconnect() }
    }

    /** Intervalles de pause des 14 prochains jours : engagement + trajet, pour les profils qui suspendent les rappels. */
    suspend fun pauseWindows(): List<PauseWindow> {
        val s = settings.current()
        if (!s.pauseEnabled) return emptyList()
        val now = System.currentTimeMillis()
        val sources = dao.sources().associateBy { it.id }
        val raw = dao.eventsBetween(now - DAY, now + 14 * DAY).filter { !it.allDay }.mapNotNull { e ->
            val src = sources[e.sourceId] ?: return@mapNotNull null
            if (src.profile !in s.pausedProfiles) return@mapNotNull null
            val after = src.travelAfter ?: PauseProfile.of(src.profile).travelAfter
            PauseWindow(e.start, e.end + after * MINUTE, e.title)
        }.sortedBy { it.start }
        val merged = mutableListOf<PauseWindow>()
        for (p in raw) {
            val last = merged.lastOrNull()
            if (last != null && p.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, p.end), title = "${last.title} + ${p.title}")
            else merged += p
        }
        return merged
    }

    /** Engagements à respecter par le planificateur, en heure locale, trajets inclus. */
    suspend fun busyEvents(days: Int = 14): List<SchedulePlanner.BusyEvent> {
        val now = System.currentTimeMillis()
        val sources = dao.sources().associateBy { it.id }
        return dao.eventsBetween(now - DAY, now + days * DAY).flatMap { e ->
            val src = sources[e.sourceId]
            val p = PauseProfile.of(src?.profile ?: "COURS")
            IcsParser.localSlices(IcsParser.Occurrence(null, e.title, e.location, e.start, e.end, e.allDay), zone).map { (d, a, b) ->
                SchedulePlanner.BusyEvent(d, a, b, e.title, e.location, src?.travelBefore ?: p.travelBefore, src?.travelAfter ?: p.travelAfter)
            }
        }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    companion object {
        const val KIND_URL = "ICS_URL"; const val KIND_FILE = "ICS_FILE"; const val KIND_DEVICE = "DEVICE"
        private const val DAY = 86_400_000L
        private const val MINUTE = 60_000L
        private const val MAX_BYTES = 5 * 1024 * 1024

        /** webcal:// → https:// ; tout autre schéma est refusé. */
        fun normalizeUrl(raw: String): String? {
            val u = raw.trim().replace(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
            return u.takeIf { Regex("^https://[^\\s/]+\\.[^\\s]+$", RegexOption.IGNORE_CASE).matches(it) }
        }

        fun describe(t: Throwable): String = when (t) {
            is java.net.UnknownHostException -> "Adresse introuvable (vérifie le lien ou la connexion)."
            is java.net.SocketTimeoutException -> "Le serveur ne répond pas."
            else -> t.message ?: "Erreur inconnue."
        }
    }
}

/** InputStream.readNBytes n'existe qu'à partir d'Android 13. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
    while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}
