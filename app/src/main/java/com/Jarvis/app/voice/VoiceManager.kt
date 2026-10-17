package com.jarvis.app.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import com.jarvis.app.ai.ApiKeyStore
import com.jarvis.app.ai.UserProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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

    // --- Voix ElevenLabs (si une clé est saisie dans ⚙ > Clés API) ; sinon / en cas d'échec : voix Android ---
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var player: MediaPlayer? = null
    private var elevenCall: okhttp3.Call? = null
    @Volatile private var elevenSpeaking = false
    @Volatile private var speakToken = 0

    /** Appelé (sur le thread principal) juste après que Jarvis ait fini de parler. */
    var onSpeakDone: (() -> Unit)? = null

    /** Appelé (sur le thread principal) avec ce que l'utilisateur a dit PENDANT que Jarvis parle
     *  (écoute "barge-in" lancée par startBargeInListening). Jarvis ne se coupe plus tout seul
     *  quand on parle : c'est à l'appelant de décider (ex. couper uniquement sur le mot "stop"). */
    var onBargeInResult: ((String) -> Unit)? = null

    /** true pendant une écoute "barge-in" : on tolère un résultat vide (l'utilisateur n'a peut-être
     *  rien dit) sans le remonter comme une vraie erreur à l'appelant. */
    private var isBargeInListening = false

    /** Déroute temporairement un résultat/erreur ailleurs que vers les callbacks fixes du
     *  constructeur — utilisé par le portier d'appel entrant pour poser une question
     *  ponctuelle ("tu es disponible ?") sans toucher au câblage normal de la conversation. */
    var resultOverride: ((String) -> Unit)? = null
    var errorOverride: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRENCH
                if (UserProfile.voice(context) == UserProfile.VOICE_KAWAI) {
                    tts?.setPitch(1.7f)
                    tts?.setSpeechRate(1.1f)
                }
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
                if (isBargeInListening) {
                    // Écoute pendant que Jarvis parle : on ne traite pas ça comme une question.
                    isBargeInListening = false
                    val handler = onBargeInResult
                    if (text != null && handler != null) handler(text)
                    rearmBargeInIfSpeaking()
                    return
                }
                if (text != null) (resultOverride ?: onResult)(text) else (errorOverride ?: onError)("Rien compris")
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
                if (isBargeInListening) {
                    // Silence / rien compris pendant que Jarvis parle : ce n'est pas une erreur,
                    // on continue simplement à guetter le mot "stop" tant qu'il parle.
                    isBargeInListening = false
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) rearmBargeInIfSpeaking()
                    return
                }
                (errorOverride ?: onError)(sttErrorMessage(error))
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
        isBargeInListening = false // une écoute normale n'est jamais une écoute "barge-in"
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
        isBargeInListening = false
        runCatching { speechRecognizer.cancel() }
    }

    /** Écoute lancée PENDANT que Jarvis parle. Il ne s'arrête plus dès qu'on parle : ce qui est
     *  entendu arrive dans onBargeInResult, et c'est l'appelant qui décide de couper (mot "stop"). */
    fun startBargeInListening() {
        if (isDestroyed || isListening) return
        startListening()
        isBargeInListening = true
    }

    /** Tant que Jarvis parle, on relance l'écoute pour pouvoir entendre un "stop". */
    private fun rearmBargeInIfSpeaking() {
        mainHandler.postDelayed({
            if (!isDestroyed && !isListening && isSpeakingNow()) startBargeInListening()
        }, 300)
    }

    /** true si Jarvis est en train de parler (voix ElevenLabs ou voix Android). */
    fun isSpeakingNow(): Boolean = elevenSpeaking || tts?.isSpeaking == true

    fun speak(text: String) {
        if (isDestroyed) return
        val key = ApiKeyStore.getKey(context, ApiKeyStore.ELEVENLABS_ID)
        if (key.isBlank()) {
            speakNative(text)
            return
        }
        stopSpeaking() // coupe une réponse précédente encore en cours
        val token = ++speakToken
        elevenSpeaking = true
        val voice = ApiKeyStore.getVoiceId(context)
        val model = ApiKeyStore.getModel(context, ApiKeyStore.ELEVENLABS_ID)
        Thread {
            val file = runCatching { fetchEleven(text, key, voice, model) }.getOrNull()
            mainHandler.post {
                if (isDestroyed || token != speakToken) { file?.delete(); return@post }
                if (file == null) {
                    // Clé refusée, quota atteint, pas de réseau… : Jarvis reste audible avec la voix Android.
                    elevenSpeaking = false
                    speakNative(text)
                } else {
                    playFile(file, text)
                }
            }
        }.start()
    }

    private fun speakNative(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    /** Demande l'audio MP3 à ElevenLabs et le range dans le cache. null en cas d'erreur. */
    private fun fetchEleven(text: String, key: String, voice: String, model: String): File? {
        val body = JSONObject().put("text", text).put("model_id", model)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
            .addHeader("xi-api-key", key)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()
        val call = http.newCall(request)
        elevenCall = call
        call.execute().use { r ->
            if (!r.isSuccessful) return null
            val out = File.createTempFile("jarvis_tts_", ".mp3", context.cacheDir)
            val stream = r.body?.byteStream() ?: return null
            stream.use { input -> out.outputStream().use { input.copyTo(it) } }
            return out
        }
    }

    private fun playFile(file: File, text: String) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                finishEleven(mp, file)
                onSpeakDone?.invoke()
            }
            mp.setOnErrorListener { _, _, _ ->
                finishEleven(mp, file)
                speakNative(text)
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            finishEleven(mp, file)
            speakNative(text)
        }
    }

    private fun finishEleven(mp: MediaPlayer, file: File) {
        elevenSpeaking = false
        if (player === mp) player = null
        runCatching { mp.release() }
        file.delete()
    }

    /** Coupe la voix de Jarvis immédiatement (bouton STOP). */
    fun stopSpeaking() {
        speakToken++ // invalide toute synthèse ElevenLabs encore en route
        runCatching { elevenCall?.cancel() }
        elevenSpeaking = false
        player?.let { p -> player = null; runCatching { p.stop() }; runCatching { p.release() } }
        runCatching { tts?.stop() }
    }

    fun destroy() {
        isDestroyed = true
        stopSpeaking()
        runCatching { speechRecognizer.destroy() }
        tts?.shutdown()
    }
}
