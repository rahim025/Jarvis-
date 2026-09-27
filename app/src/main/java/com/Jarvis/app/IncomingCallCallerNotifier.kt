package com.jarvis.app

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Tente de faire entendre un message à l'interlocuteur une fois l'appel décroché
 * ("Le créateur de Jarvis n'est pas disponible...").
 *
 * IMPORTANT — limite réelle d'Android : une appli tierce (qui n'est pas l'appli
 * téléphone par défaut) n'a AUCUNE API garantie pour injecter de l'audio dans le flux
 * vocal d'un appel, ni pour raccrocher proprement. Ce qui suit est du best-effort :
 *  - on force le flux audio de la synthèse vocale sur STREAM_VOICE_CALL, ce qui marche
 *    sur beaucoup d'appareils (AOSP, Pixel...) mais peut être bloqué par certains
 *    fabricants/versions d'Android.
 *  - pour raccrocher ensuite, on essaie de cliquer sur le bouton "Terminer l'appel"
 *    via le service d'accessibilité (mêmes mécanismes que "clique sur X"), en tentant
 *    plusieurs libellés possibles — ça dépend de l'appli téléphone/langue du système.
 * Sans ça (donc dans le pire des cas), l'appel reste simplement décroché et silencieux
 * côté appelant jusqu'à ce que l'utilisateur raccroche lui-même.
 */
object IncomingCallCallerNotifier {

    private val handler = Handler(Looper.getMainLooper())

    private val hangUpLabels = listOf(
        "Terminer l'appel", "Raccrocher", "Fin d'appel", "End call", "Hang up"
    )

    fun speakToCaller(context: Context, message: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        runCatching { audioManager.mode = AudioManager.MODE_IN_CALL }

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                tryHangUp()
                runCatching { audioManager.mode = previousMode }
                return@TextToSpeech
            }
            tts?.language = Locale.FRENCH
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        tryHangUp()
                        runCatching { audioManager.mode = previousMode }
                        tts?.shutdown()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        tryHangUp()
                        runCatching { audioManager.mode = previousMode }
                        tts?.shutdown()
                    }
                }
            })
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "jarvis_caller_notice")
        }
    }

    /** Best-effort : essaie plusieurs libellés du bouton "raccrocher" selon l'appli
     *  téléphone/langue installée. Nécessite que le service d'accessibilité soit activé. */
    private fun tryHangUp() {
        val service = JarvisAccessibilityService.instance ?: return
        for (label in hangUpLabels) {
            if (service.clickByLabel(label)) return
        }
    }
}
