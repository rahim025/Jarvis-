package com.jarvis.app.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.app.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Cerveau de la conversation continue de Jarvis (façon Iron Man) :
 * salutation selon l'heure, boucle écoute -> décision -> action -> réponse -> ré-écoute,
 * jusqu'à ce que l'utilisateur dise une phrase d'arrêt.
 *
 * Partagé entre MainActivity (interface visible) et JarvisForegroundService
 * (bulle flottante en arrière-plan) : le comportement de Jarvis est identique
 * qu'on soit dans l'app ou non.
 */
class JarvisConversationController(
    private val context: Context,
    private val voiceManager: VoiceManager,
    private val onStatus: (String) -> Unit
) {
    var conversationActive = false
        private set

    private val stopPhrases = listOf(
        "c'est bon pour le moment",
        "c'est bon pour l'instant",
        "ca suffit pour le moment",
        "ca suffira pour le moment",
        "ca suffit",
        "stop jarvis",
        "arrete jarvis",
        "silence jarvis",
        "c'est tout pour le moment",
        "merci ca suffit"
    )

    init {
        voiceManager.onSpeakDone = {
            if (conversationActive) startListeningRound()
        }
    }

    /** À appeler quand l'utilisateur active Jarvis (appui sur le micro, ou sur la bulle flottante). */
    fun activate() {
        if (conversationActive) {
            startListeningRound()
            return
        }
        conversationActive = true
        val greeting = greetingMessage()
        onStatus("Jarvis : $greeting")
        voiceManager.speak(greeting)
        // L'écoute démarre automatiquement une fois la salutation terminée (onSpeakDone).
    }

    fun stop() {
        conversationActive = false
    }

    fun onVoiceError(err: String) {
        onStatus(err)
        // On ne boucle pas indéfiniment sur une erreur de permission.
        if (conversationActive && err != "Erreur STT: 9") {
            Handler(Looper.getMainLooper()).postDelayed({
                if (conversationActive) startListeningRound()
            }, 900)
        }
    }

    fun onVoiceResult(text: String) {
        onStatus("Toi : $text")

        if (isStopPhrase(text)) {
            conversationActive = false
            val bye = "Très bien Monsieur, je reste disponible dès que vous avez besoin de moi."
            onStatus("Jarvis : $bye")
            voiceManager.speak(bye)
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val action = withContext(Dispatchers.IO) {
                runCatching { BackendClient.decideAction(text) }
                    .getOrElse { JarvisAction.Speak("Erreur réseau : ${it.message}") }
            }
            val reply = CommandExecutor.execute(context, action)
            onStatus("Jarvis : $reply")
            voiceManager.speak(reply)
            // L'écoute repart automatiquement via onSpeakDone si la conversation est toujours active.
        }
    }

    private fun startListeningRound() {
        onStatus("J'écoute...")
        voiceManager.startListening()
    }

    /** "Bonjour Monsieur" ou "Bonsoir Monsieur" selon l'heure au Bénin (Africa/Porto-Novo, UTC+1). */
    private fun greetingMessage(): String {
        val beninZone = TimeZone.getTimeZone("Africa/Porto-Novo")
        val hour = Calendar.getInstance(beninZone).get(Calendar.HOUR_OF_DAY)
        return if (hour in 5..17) "Bonjour Monsieur." else "Bonsoir Monsieur."
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.FRENCH)
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâ]"), "a")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace(Regex("[ûùü]"), "u")
            .replace(Regex("[^a-z0-9' ]"), "")
            .trim()
    }

    private fun isStopPhrase(text: String): Boolean {
        val normalized = normalize(text)
        return stopPhrases.any { normalized.contains(it) }
    }
}
