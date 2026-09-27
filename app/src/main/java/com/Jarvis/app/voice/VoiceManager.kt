package com.jarvis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Encapsule l'écoute vocale (Speech-to-Text) et la réponse vocale (Text-to-Speech).
 * Utilise les API natives Android — pas besoin de service externe pour ça.
 */
class VoiceManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var speechRecognizer: SpeechRecognizer = createRecognizer()
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var isDestroyed = false

    /** Appelé (sur le thread principal) juste après que Jarvis ait fini de parler. */
    var onSpeakDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onSpeakDone?.invoke() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onSpeakDone?.invoke() }
                    }
                })
            }
        }
    }

    /**
     * Crée un SpeechRecognizer et lui attache son listener UNE SEULE FOIS.
     * Le ré-attacher à chaque startListening() (comme avant) laissait le moteur
     * se "griper" après une erreur : il fallait le détruire/recréer pour qu'il
     * réponde à nouveau, d'où le blocage sur "Erreur STT: 5" qui ne se résorbait
     * jamais tout seul.
     */
    private fun createRecognizer(): SpeechRecognizer {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text != null) onResult(text) else onError("Rien compris")
            }

            override fun onError(error: Int) {
                isListening = false
                // ERROR_CLIENT (5) et ERROR_RECOGNIZER_BUSY (8) laissent souvent le moteur
                // bloqué : on le recrée immédiatement pour que la tentative suivante fonctionne.
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    mainHandler.post { recreateRecognizer() }
                }
                onError(sttErrorMessage(error))
            }

            // Callbacks non utilisés mais requis par l'interface
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return recognizer
    }

    private fun recreateRecognizer() {
        if (isDestroyed) return
        runCatching { speechRecognizer.destroy() }
        speechRecognizer = createRecognizer()
    }

    /**
     * Traduit un code d'erreur SpeechRecognizer en message lisible.
     * Le préfixe "Erreur STT: <code>" est conservé pour que le code appelant
     * puisse toujours détecter un code précis via startsWith (ex: la permission refusée).
     */
    private fun sttErrorMessage(code: Int): String {
        val detail = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "réseau trop lent, nouvelle tentative."
            SpeechRecognizer.ERROR_NETWORK -> "pas de connexion réseau."
            SpeechRecognizer.ERROR_AUDIO -> "problème avec le micro."
            SpeechRecognizer.ERROR_SERVER -> "service de reconnaissance vocale indisponible."
            SpeechRecognizer.ERROR_CLIENT -> "je relance l'écoute."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "rien entendu, je réécoute."
            SpeechRecognizer.ERROR_NO_MATCH -> "je n'ai pas compris, réessaie."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "micro occupé, je relance."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission micro refusée."
            else -> "erreur inconnue."
        }
        return "Erreur STT: $code — $detail"
    }

    fun startListening() {
        if (isDestroyed) return
        if (isListening) {
            // Une session précédente n'est pas terminée proprement : on l'annule
            // avant d'en relancer une, sinon le moteur renvoie ERROR_CLIENT.
            runCatching { speechRecognizer.cancel() }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.FRENCH)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        isListening = true
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                isListening = false
                recreateRecognizer()
                onError("Erreur STT: relance")
            }
    }

    /** Coupe une écoute en cours (utilisé quand on arrête la conversation). */
    fun stopListening() {
        isListening = false
        runCatching { speechRecognizer.cancel() }
    }

    fun speak(text: String) {
        if (isDestroyed) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    fun destroy() {
        isDestroyed = true
        runCatching { speechRecognizer.destroy() }
        tts?.shutdown()
    }
}
