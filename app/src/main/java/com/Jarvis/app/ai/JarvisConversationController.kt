package com.jarvis.app.ai

import android.content.Context
import com.jarvis.app.JarvisAccessibilityService
import android.os.Handler
import android.os.Looper
import com.jarvis.app.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val onStatus: (String) -> Unit,
    /** Reflète l'état sur l'avatar HUD : "idle", "listening", "speaking", "executing". */
    private val onAvatarState: (String) -> Unit = {},
    /** Réponse à afficher en texte quand la question a été tapée au clavier (pas lue à voix haute). */
    private val onTextReply: (String) -> Unit = {}
) {
    var conversationActive = false
        private set

    /** true quand la dernière question vient du clavier : Jarvis répond alors par écrit, sans parler. */
    private var typedMode = false

    private val prefs = context.getSharedPreferences("jarvis_ui", Context.MODE_PRIVATE)

    /**
     * Mode vision : quand il est actif, Jarvis regarde l'écran (capture + texte) pour répondre
     * à tes questions. Les vraies commandes (ouvrir une app, appeler, minuteur...) marchent comme avant.
     */
    var visionMode: Boolean
        get() = prefs.getBoolean("vision_mode", false)
        set(value) { prefs.edit().putBoolean("vision_mode", value).apply() }

    /** Tâches d'écran lancées en arrière-plan : elles continuent pendant que Jarvis écoute la commande suivante. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            if (conversationActive) startListeningRound() else onAvatarState("idle")
        }
    }

    /** À appeler quand l'utilisateur active Jarvis (appui sur le micro, ou sur la bulle flottante). */
    fun activate() {
        // Un appui sur le micro / la bulle pendant une tâche en plusieurs étapes l'interrompt.
        if (TaskRunner.running) {
            TaskRunner.cancel()
            return
        }
        if (conversationActive) {
            startListeningRound()
            return
        }
        conversationActive = true
        val greeting = greetingMessage()
        onStatus("Jarvis : $greeting")
        onAvatarState("speaking")
        voiceManager.speak(greeting)
        // L'écoute démarre automatiquement une fois la salutation terminée (onSpeakDone).
    }

    fun stop() {
        conversationActive = false
        voiceManager.stopListening()
        onAvatarState("idle")
    }

    /** Question tapée au clavier : même cerveau que la voix, mais la réponse reste écrite. */
    fun onTextInput(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (conversationActive) stop() // on coupe le micro : l'utilisateur a choisi d'écrire
        voiceManager.stopSpeaking()
        typedMode = true
        handle(clean)
    }

    /** Dit la réponse à voix haute, ou l'affiche seulement si la question a été tapée. */
    private fun say(text: String) {
        if (typedMode) {
            onTextReply(text)
            onAvatarState("idle")
        } else {
            voiceManager.speak(text)
        }
    }

    fun onVoiceError(err: String) {
        onStatus(err)
        // On ne boucle pas indéfiniment sur une erreur de permission.
        if (conversationActive && !err.startsWith("Erreur STT: 9")) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (conversationActive) startListeningRound()
            }, 900)
        } else {
            onAvatarState("idle")
        }
    }

    fun onVoiceResult(text: String) {
        typedMode = false
        handle(text)
    }

    private fun handle(text: String) {
        onStatus("Toi : $text")
        // Toucher une commande dans l'écran « Commandes » l'exécute via ce contrôleur, comme à la voix.
        CommandBus.runner = { cmd -> Handler(Looper.getMainLooper()).post { handle(cmd) } }

        // « Affiche les commandes » / « Cherche les commandes pour WhatsApp » : réponse immédiate, sans réseau.
        val commandsFilter = CommandCatalog.parseShowRequest(text)
        if (commandsFilter != null) {
            val reply = runCatching { CommandsLauncher.show(context, commandsFilter) }
                .getOrElse { "Je n'arrive pas à afficher les commandes." }
            onStatus("Jarvis : $reply")
            onAvatarState("speaking")
            say(reply)
            return
        }

        // « Active / désactive la vision » à la voix ou au clavier.
        val norm = normalize(text)
        val wantsVisionOn = norm.contains("active la vision") || norm.contains("active le mode vision")
        val wantsVisionOff = norm.contains("desactive la vision") || norm.contains("desactive le mode vision")
        if (wantsVisionOn || wantsVisionOff) {
            val msg = when {
                wantsVisionOff -> { visionMode = false; "Vision désactivée." }
                JarvisAccessibilityService.instance == null ->
                    "Pour regarder ton écran, active d'abord le contrôle d'écran dans les paramètres d'accessibilité."
                else -> { visionMode = true; "Vision activée : je regarde ton écran pour répondre." }
            }
            onStatus("Jarvis : $msg")
            onAvatarState("speaking")
            say(msg)
            return
        }

        // « Arrête de jouer » : coupe la partie en cours tout de suite.
        if (TaskRunner.running && (norm.contains("arrete de jouer") || norm.contains("arrete la partie") ||
                norm.contains("stop la partie") || norm.contains("stop jarvis") || norm.contains("arrete jarvis"))
        ) {
            TaskRunner.cancel()
            val msg = "D'accord, j'arrête."
            onStatus("Jarvis : $msg")
            onAvatarState("speaking")
            say(msg)
            return
        }

        if (isStopPhrase(text)) {
            conversationActive = false
            val bye = "Très bien Monsieur, je reste disponible dès que vous avez besoin de moi."
            onStatus("Jarvis : $bye")
            onAvatarState("speaking")
            say(bye)
            return
        }

        onAvatarState("executing")
        CoroutineScope(Dispatchers.Main).launch {
            val memory = JarvisMemory.get(context)
            var failed = false
            var fromBackend = false
            // Le cerveau reçoit la question + la mémoire utile (faits, derniers échanges, vieux souvenirs).
            // 0 token : les commandes simples sont comprises localement, sans appeler l'IA.
            val actions = LocalCommands.parse(text) ?: withContext(Dispatchers.IO) {
                fromBackend = true
                runCatching {
                    BackendClient.decideActions(
                        text,
                        UserIdentity.getSafe(context),
                        memory.buildPayload(text),
                        ApiKeyStore.requestExtras(context)
                    )
                }.getOrElse {
                    failed = true
                    listOf<JarvisAction>(JarvisAction.Speak("Erreur réseau : ${it.message}"))
                }
            }

            // Mode vision : une simple conversation (pas de vraie commande) est répondue en regardant l'écran.
            if (visionMode && fromBackend && !failed && actions.all { it is JarvisAction.Speak }) {
                onStatus("Jarvis : je regarde ton écran...")
                val seen = withContext(Dispatchers.IO) { screenAnswer(text) }
                if (seen != null) {
                    withContext(Dispatchers.IO) { runCatching { memory.addTurn(text, seen) } }
                    onStatus("Jarvis : $seen")
                    onAvatarState("speaking")
                    say(seen)
                    return@launch
                }
            }

            val longTask = actions.any { CommandExecutor.isLongTask(it) }

            if (longTask && !failed) {
                // Tâche d'écran longue : on exécute tout de suite ce qui est rapide (alarme, volume...),
                // la tâche d'écran part en arrière-plan, et Jarvis reste à l'écoute pour la suite.
                val quick = actions.filter { !CommandExecutor.isScreenBound(it) }
                val screen = actions.filter { CommandExecutor.isScreenBound(it) }
                val quickReply = if (quick.isEmpty()) "" else CommandExecutor.executeAll(context, quick)
                val ack = (quickReply.takeIf { it.isNotBlank() && it != "C'est fait." }?.plus(" ") ?: "") +
                    "Je m'en occupe."
                onStatus("Jarvis : $ack")
                onAvatarState("speaking")
                say(ack)

                backgroundScope.launch {
                    val result = CommandExecutor.executeAll(context, screen)
                    runCatching { memory.addTurn(text, "$quickReply $result".trim()) }
                    withContext(Dispatchers.Main) {
                        onStatus("Jarvis : $result")
                        onAvatarState("speaking")
                        say(result)
                    }
                }
                return@launch
            }

            val reply = CommandExecutor.executeAll(context, actions)
            // Mémoire d'éléphant : chaque échange réussi est gardé pour toujours.
            if (!failed) {
                withContext(Dispatchers.IO) { runCatching { memory.addTurn(text, reply) } }
            }
            onStatus("Jarvis : $reply")
            onAvatarState("speaking")
            say(reply)
            // L'écoute repart automatiquement via onSpeakDone si la conversation est toujours active.
        }
    }

    /** Capture l'écran + son texte et demande à Gemini de répondre à [text] en regardant. null si impossible. */
    private fun screenAnswer(text: String): String? {
        val service = JarvisAccessibilityService.instance ?: return null
        val shot = runCatching { service.captureScreenBase64() }.getOrNull() ?: return null
        return runCatching {
            BackendClient.analyzeImage(
                text, shot, "image/jpeg", ApiKeyStore.requestExtras(context), service.readScreenText()
            )
        }.getOrNull()
    }

    private fun startListeningRound() {
        onStatus("J'écoute...")
        onAvatarState("listening")
        voiceManager.startListening()
    }

    /** "Bonjour <prénom>" ou "Bonsoir <prénom>" selon l'heure au Bénin (Africa/Porto-Novo, UTC+1).
     *  Le prénom vient de la mémoire (« retiens que je m'appelle… »), sinon "Monsieur". */
    private fun greetingMessage(): String {
        val beninZone = TimeZone.getTimeZone("Africa/Porto-Novo")
        val hour = Calendar.getInstance(beninZone).get(Calendar.HOUR_OF_DAY)
        val name = runCatching {
            JarvisMemory.get(context).allFacts()
                .firstOrNull { it.first in listOf("prénom", "prenom", "nom", "surnom") }?.second
        }.getOrNull() ?: "Monsieur"
        return if (hour in 5..17) "Bonjour $name." else "Bonsoir $name."
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
