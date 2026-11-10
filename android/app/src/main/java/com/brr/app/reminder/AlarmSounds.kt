package com.brr.app.reminder

import android.content.ContentResolver
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.brr.app.R

data class AlarmSound(val id: String, val label: String, val description: String, val rawRes: Int?)

/** Bibliothèque de sons originaux (synthétisés pour Bullylist, libres de droits — voir tools/generate_sounds.py). */
object AlarmSounds {
    const val SILENT = "none"
    const val SYSTEM = "system"

    val all = listOf(
        AlarmSound("brr_carillon", "Carillon doux", "Deux notes cristallines. Poli.", R.raw.brr_carillon),
        AlarmSound("brr_ping", "Double ping", "Court, net, efficace.", R.raw.brr_ping),
        AlarmSound("brr_klaxon", "Klaxon", "Comme un embouteillage dans ta poche.", R.raw.brr_klaxon),
        AlarmSound("brr_sirene", "Sirène", "Urgence nationale pour tâche banale.", R.raw.brr_sirene),
        AlarmSound("brr_clairon", "Clairon", "Rassemblement ! Garde-à-vous !", R.raw.brr_clairon),
        AlarmSound("brr_alerte", "Alerte rouge", "Bips frénétiques de fin du monde.", R.raw.brr_alerte),
        AlarmSound(SYSTEM, "Son du système", "La notification par défaut du téléphone.", null),
        AlarmSound(SILENT, "Aucun son", "Vibration et visuel uniquement.", null),
    )

    fun byId(id: String): AlarmSound = all.firstOrNull { it.id == id } ?: all.first()

    fun uri(context: Context, id: String): Uri? {
        val s = byId(id)
        return when {
            s.id == SILENT -> null
            s.rawRes == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.packageName).appendPath("raw").appendPath(s.id).build()
        }
    }
}
