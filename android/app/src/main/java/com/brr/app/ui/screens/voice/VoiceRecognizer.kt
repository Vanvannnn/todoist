package com.brr.app.ui.screens.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictée continue avec la reconnaissance vocale du téléphone. Aucun fichier audio n'est créé ni conservé.
 * Android s'arrête après un silence : tant que l'utilisateur n'a pas mis en pause, l'écoute reprend.
 * Hors ligne demandé en priorité (EXTRA_PREFER_OFFLINE) ; sinon le service du téléphone peut utiliser le réseau.
 */
class VoiceRecognizer(
    private val context: Context,
    private val onText: (final: String, partial: String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onState: (State) -> Unit,
) {
    enum class State { IDLE, LISTENING, ERROR_NO_SERVICE, ERROR_PERMISSION, ERROR_NETWORK }

    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var finalText = ""

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(initial: String) {
        if (!available) { onState(State.ERROR_NO_SERVICE); return }
        finalText = initial.trim().let { if (it.isEmpty()) "" else "$it " }
        wanted = true
        listen()
        onState(State.LISTENING)
    }

    fun pause() {
        wanted = false
        recognizer?.stopListening()
        onLevel(0f)
        onState(State.IDLE)
    }

    fun release() {
        wanted = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it; it.setRecognitionListener(listener) }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        })
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { finalText += it.trim() + ". " }
            onText(finalText, "")
            if (wanted) listen() else onState(State.IDLE)
        }
        override fun onPartialResults(partial: Bundle?) {
            onText(finalText, partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    if (wanted) listen() else onState(State.IDLE)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { wanted = false; onState(State.ERROR_PERMISSION) }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> { wanted = false; onState(State.ERROR_NETWORK) }
                else -> { wanted = false; onState(State.IDLE) }
            }
            onLevel(0f)
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = onLevel(0f)
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
