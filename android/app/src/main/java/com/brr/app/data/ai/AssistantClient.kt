package com.brr.app.data.ai

import com.brr.app.domain.voice.Moment
import com.brr.app.domain.voice.Understanding
import com.brr.app.domain.voice.UnderstandingCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZoneId

/**
 * Client du serveur de compréhension Bullylist (server/server.mjs). Seul le TEXTE de la dictée est envoyé, avec la date,
 * l'heure et le fuseau du téléphone (ils font foi). La clé API du fournisseur IA n'est jamais dans l'application.
 */
class AssistantClient(private val baseUrl: String, private val token: String) {

    sealed interface Outcome {
        data class Ok(val understanding: Understanding, val issues: List<String>, val model: String) : Outcome
        /** [message] est affichable tel quel ; le moteur local prend alors le relais. */
        data class Failed(val message: String) : Outcome
    }

    suspend fun understand(text: String, now: Moment, previous: Understanding?): Outcome = withContext(Dispatchers.IO) {
        val url = runCatching { URL(baseUrl.trimEnd('/') + "/api/understand") }.getOrElse { return@withContext Outcome.Failed("Adresse du serveur IA invalide.") }
        val body = JSONObject()
            .put("text", text)
            .put("now", JSONObject().put("date", now.date.toString()).put("minute", now.minute))
            .put("tz", ZoneId.systemDefault().id)
            .put("previous", previous?.let(UnderstandingCodec::toJson) ?: JSONObject.NULL)
            .put("consent", true)
        val conn = (url.openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000 // le modèle réfléchit : la fiabilité passe avant la vitesse
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (code !in 200..299) return@withContext Outcome.Failed(json?.optString("message")?.ifBlank { null } ?: "Serveur IA : erreur $code.")
            val u = UnderstandingCodec.parse(json?.optJSONObject("understanding"), now.date)
                ?: return@withContext Outcome.Failed("Réponse du serveur IA illisible.")
            val issues = json?.optJSONArray("issues")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            Outcome.Ok(u, issues, json?.optString("model").orEmpty())
        } catch (e: SocketTimeoutException) {
            Outcome.Failed("Le serveur IA n'a pas répondu à temps.")
        } catch (e: IOException) {
            Outcome.Failed("Serveur IA injoignable (réseau ou adresse).")
        } finally {
            conn.disconnect()
        }
    }
}
