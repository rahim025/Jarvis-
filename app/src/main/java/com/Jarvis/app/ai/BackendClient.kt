package com.jarvis.app.ai

import com.jarvis.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Une action décidée par le cerveau (Groq, via le backend), à exécuter sur le téléphone.
 */
sealed class JarvisAction {
    data class Speak(val text: String) : JarvisAction()
    data class OpenApp(val appName: String) : JarvisAction()
    data class SendSms(val contact: String, val message: String) : JarvisAction()
    data class CallContact(val contact: String, val app: String = "") : JarvisAction()
    data class WhatsAppCall(val contact: String, val video: Boolean) : JarvisAction()
    object Redial : JarvisAction()
    object EndCall : JarvisAction()
    data class Speaker(val on: Boolean) : JarvisAction()
    data class ClickOnScreen(val label: String) : JarvisAction()
    data class TypeText(val text: String) : JarvisAction()
    object GoHome : JarvisAction()
    object GoBack : JarvisAction()
    object CloseApp : JarvisAction()
    data class Scroll(val direction: String) : JarvisAction()

    // ── Mémoire d'éléphant ──
    data class Memorize(val key: String, val value: String) : JarvisAction()
    data class Forget(val key: String) : JarvisAction()
    object ListMemory : JarvisAction()

    // ── Fonctions portées de la version Windows ──
    data class SetVolume(val direction: String, val percent: Int?) : JarvisAction()
    data class MediaControl(val command: String) : JarvisAction()
    data class PlayMusic(val query: String, val app: String) : JarvisAction()
    data class Flashlight(val on: Boolean) : JarvisAction()
    data class SetBrightness(val percent: Int) : JarvisAction()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : JarvisAction()
    data class SetTimer(val seconds: Int, val label: String) : JarvisAction()
    data class OpenUrl(val url: String) : JarvisAction()
    data class Navigate(val destination: String) : JarvisAction()
    object DeviceStatus : JarvisAction()
    data class DescribeScreen(val question: String) : JarvisAction()

    // ── Tâches en plusieurs étapes, réglages, aide ──
    /** kind : "send_message", "search" ou "other" (voir TaskRunner). */
    data class RunTask(
        val kind: String,
        val app: String,
        val goal: String,
        val contact: String,
        val message: String,
        val query: String,
        val submit: Boolean
    ) : JarvisAction()
    data class SetToggle(val setting: String, val on: Boolean) : JarvisAction()
    data class ShowCommands(val filter: String) : JarvisAction()
    /** mode : "on", "off" ou "status" — réponses automatiques aux messages entrants (voir JarvisNotificationListener). */
    data class AutoReply(val mode: String, val contact: String, val app: String) : JarvisAction()
    /** « Garde la conversation avec cette personne » : lit la discussion ouverte à l'écran (voir ScreenChat). */
    data class KeepConversation(val contact: String) : JarvisAction()
}

/** Prochaine action décidée par le cerveau pour piloter l'écran (boucle adaptative de TaskRunner). */
data class AgentDecision(
    val action: String,
    val index: Int?,
    val text: String,
    val direction: String,
    val app: String,
    val reason: String,
    val say: String
)

/**
 * Parle uniquement au backend Render — plus jamais directement à Groq ou Gemini.
 * Les clés API restent côté serveur ; ici on n'a que l'URL du backend et,
 * si configuré, un secret partagé envoyé dans un header.
 */
object BackendClient {

    // Défini dans local.properties -> BuildConfig, ex: "https://jarvis-43io.onrender.com"
    private val BASE_URL = BuildConfig.BACKEND_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        // Le plan gratuit de Render met le service en veille après inactivité ;
        // le premier appel après une pause peut prendre 30-50s à répondre.
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    /** Ajoute les clés/fournisseurs saisis dans l'app (menu ⚙ > Clés API) à la requête. */
    private fun copyInto(body: JSONObject, extras: JSONObject?) {
        if (extras == null) return
        val keys = extras.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            body.put(k, extras.get(k))
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url).addHeader("Content-Type", "application/json")
        if (BuildConfig.APP_SHARED_SECRET.isNotBlank()) {
            builder.addHeader("x-app-secret", BuildConfig.APP_SHARED_SECRET)
        }
        return builder
    }

    /**
     * Envoie le texte reconnu + la mémoire utile (faits, derniers échanges, souvenirs
     * pertinents) au backend, qui interroge Groq et renvoie une ou plusieurs actions.
     */
    fun decideActions(
        userText: String,
        userId: String,
        memory: JSONObject,
        extras: JSONObject? = null
    ): List<JarvisAction> {
        val body = JSONObject(memory.toString())
            .put("text", userText)
            .put("userId", userId)
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/ask")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
                ?: return listOf(JarvisAction.Speak("Pas de réponse du backend."))
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: return listOf(JarvisAction.Speak("Réponse illisible du backend."))

            if (!response.isSuccessful) {
                return listOf(JarvisAction.Speak("Erreur backend : ${json.optString("error", "inconnue")}"))
            }

            val out = mutableListOf<JarvisAction>()
            when (json.optString("type")) {
                "actions" -> {
                    val arr = json.optJSONArray("actions")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            out.add(parseAction(a.getString("name"), a.optJSONObject("args") ?: JSONObject()))
                        }
                    }
                }
                "action" -> out.add(
                    parseAction(json.getString("name"), json.optJSONObject("args") ?: JSONObject())
                )
                else -> out.add(JarvisAction.Speak(json.optString("text", "...")))
            }
            if (out.isEmpty()) out.add(JarvisAction.Speak("Je n'ai rien à faire pour cette demande."))
            return out
        }
    }

    /** Envoie une image (ex: capture d'écran) en base64 au backend pour analyse par Gemini. */
    fun analyzeImage(
        prompt: String,
        imageBase64: String,
        mimeType: String = "image/png",
        extras: JSONObject? = null,
        screenText: String = ""
    ): String {
        val body = JSONObject().apply {
            put("prompt", prompt)
            put("imageBase64", imageBase64)
            put("mimeType", mimeType)
            if (screenText.isNotBlank()) put("screenText", screenText)
        }
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/vision")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string() ?: "") }.getOrNull()
                ?: return "Réponse illisible du backend."
            if (!response.isSuccessful) return "Erreur vision : ${json.optString("error", "inconnue")}"
            return json.optString("text", "Pas de réponse.")
        }
    }

    /**
     * Demande au cerveau la PROCHAINE action pour atteindre [goal], vu l'écran actuel et ce qui a
     * déjà été tenté. C'est ce qui permet de s'adapter quand l'interface change.
     */
    fun agentStep(
        goal: String,
        pkg: String,
        elements: JSONArray,
        history: List<String>,
        extras: JSONObject? = null
    ): AgentDecision? {
        val body = JSONObject().apply {
            put("goal", goal)
            put("package", pkg)
            put("elements", elements)
            put("history", JSONArray(history))
        }
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/agent")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string() ?: "") }.getOrNull() ?: return null
            if (!response.isSuccessful) return null
            return AgentDecision(
                action = json.optString("action"),
                index = if (json.has("index") && !json.isNull("index")) json.optString("index").toIntOrNull() else null,
                text = json.optString("text"),
                direction = json.optString("direction"),
                app = json.optString("app"),
                reason = json.optString("reason"),
                say = json.optString("say")
            )
        }
    }

    data class AutoReplyResult(val reply: String, val skip: Boolean, val reason: String, val remember: String)

    private fun historyJson(history: List<Triple<Boolean, String, Long>>): JSONArray {
        val msgs = JSONArray()
        history.forEach { (mine, text, ts) -> msgs.put(JSONObject().put("fromMe", mine).put("text", text).put("ts", ts)) }
        return msgs
    }

    /**
     * Demande au cerveau la réponse que l'utilisateur aurait écrite. [history] = (écrit par moi ?, texte, horodatage),
     * [summary] = ce que Jarvis sait déjà de cette conversation (échanges plus anciens, détails retenus).
     */
    fun autoReply(
        contact: String,
        app: String,
        history: List<Triple<Boolean, String, Long>>,
        summary: String,
        memory: JSONObject,
        extras: JSONObject? = null
    ): AutoReplyResult? {
        val body = JSONObject(memory.toString())
            .put("contact", contact)
            .put("app", app)
            .put("history", historyJson(history))
            .put("summary", summary)
            .put("nowMs", System.currentTimeMillis())
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/reply")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string() ?: "") }.getOrNull() ?: return null
            if (!response.isSuccessful) return null
            return AutoReplyResult(
                json.optString("reply"), json.optBoolean("skip", false),
                json.optString("reason"), json.optString("remember")
            )
        }
    }

    /** Condense les vieux échanges + l'ancien résumé en un nouveau résumé de la conversation. */
    fun summarizeConversation(
        contact: String,
        previous: String,
        older: List<Triple<Boolean, String, Long>>,
        extras: JSONObject? = null
    ): String? {
        val body = JSONObject()
            .put("contact", contact)
            .put("summary", previous)
            .put("messages", historyJson(older))
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/summarize")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string() ?: "") }.getOrNull() ?: return null
            if (!response.isSuccessful) return null
            return json.optString("summary").takeIf { it.isNotBlank() }
        }
    }

    data class ScreenChatResult(
        val isChat: Boolean,
        val group: Boolean,
        val contact: String,
        /** (écrit par moi ?, texte), du plus ancien au plus récent. */
        val messages: List<Pair<Boolean, String>>,
        val summary: String
    )

    /** Fait lire par le cerveau la discussion affichée à l'écran (capture + éléments avec leur position). */
    fun readChat(imageBase64: String?, elements: JSONArray, app: String, extras: JSONObject? = null): ScreenChatResult? {
        val body = JSONObject().put("elements", elements).put("app", app)
        if (!imageBase64.isNullOrBlank()) body.put("imageBase64", imageBase64).put("mimeType", "image/jpeg")
        copyInto(body, extras)
        val request = requestBuilder("$BASE_URL/read-chat")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string() ?: "") }.getOrNull() ?: return null
            if (!response.isSuccessful) return null
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val msgs = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = m.optString("text").trim()
                if (t.isBlank()) null else m.optBoolean("fromMe", false) to t
            }
            return ScreenChatResult(
                json.optBoolean("isChat", false), json.optBoolean("group", false),
                json.optString("contact"), msgs, json.optString("summary")
            )
        }
    }

    private fun parseAction(name: String, args: JSONObject): JarvisAction = when (name) {
        "speak" -> JarvisAction.Speak(args.optString("text", "..."))
        "open_app" -> JarvisAction.OpenApp(args.optString("app_name"))
        "send_sms" -> JarvisAction.SendSms(args.optString("contact"), args.optString("message"))
        "call_contact" -> JarvisAction.CallContact(args.optString("contact"), args.optString("app"))
        "whatsapp_call" -> JarvisAction.WhatsAppCall(
            args.optString("contact"),
            args.optString("video").lowercase() in listOf("true", "oui", "video", "vidéo", "1")
        )
        "redial" -> JarvisAction.Redial
        "end_call" -> JarvisAction.EndCall
        "speaker" -> JarvisAction.Speaker(
            args.optString("state").lowercase() in listOf("on", "true", "allume", "active", "1")
        )
        "click_on_screen" -> JarvisAction.ClickOnScreen(args.optString("label"))
        "type_text" -> JarvisAction.TypeText(args.optString("text"))
        "go_home" -> JarvisAction.GoHome
        "go_back" -> JarvisAction.GoBack
        "close_app" -> JarvisAction.CloseApp
        "scroll_up" -> JarvisAction.Scroll("up")
        "scroll_down" -> JarvisAction.Scroll("down")
        "scroll_left" -> JarvisAction.Scroll("left")
        "scroll_right" -> JarvisAction.Scroll("right")

        "memorize" -> JarvisAction.Memorize(args.optString("key"), args.optString("value"))
        "forget" -> JarvisAction.Forget(args.optString("key"))
        "list_memory" -> JarvisAction.ListMemory

        "set_volume" -> JarvisAction.SetVolume(
            args.optString("direction", "set"), args.optString("percent").toIntOrNull()
        )
        "media_control" -> JarvisAction.MediaControl(args.optString("command"))
        "play_music" -> JarvisAction.PlayMusic(args.optString("query"), args.optString("app"))
        "flashlight" -> JarvisAction.Flashlight(
            args.optString("state").lowercase() in listOf("on", "true", "allume", "allumer", "1")
        )
        "set_brightness" -> JarvisAction.SetBrightness(args.optString("percent").toIntOrNull() ?: 50)
        "set_alarm" -> JarvisAction.SetAlarm(
            args.optString("hour").toIntOrNull() ?: 7,
            args.optString("minute").toIntOrNull() ?: 0,
            args.optString("label", "Jarvis")
        )
        "set_timer" -> JarvisAction.SetTimer(
            args.optString("seconds").toIntOrNull() ?: 60, args.optString("label", "Jarvis")
        )
        "open_url" -> JarvisAction.OpenUrl(args.optString("url"))
        "navigate" -> JarvisAction.Navigate(args.optString("destination"))
        "device_status" -> JarvisAction.DeviceStatus
        "describe_screen" -> JarvisAction.DescribeScreen(args.optString("question", "Décris ce qui est affiché."))
        "run_task" -> JarvisAction.RunTask(
            kind = args.optString("kind", "other").lowercase(),
            app = args.optString("app"),
            goal = args.optString("goal"),
            contact = args.optString("contact"),
            message = args.optString("message"),
            query = args.optString("query"),
            submit = args.optString("submit").lowercase() in listOf("true", "oui", "1", "yes")
        )
        "set_toggle" -> JarvisAction.SetToggle(
            args.optString("setting").lowercase(),
            args.optString("state").lowercase() in listOf("on", "true", "allume", "active", "1")
        )
        "show_commands" -> JarvisAction.ShowCommands(args.optString("filter"))
        "keep_conversation" -> JarvisAction.KeepConversation(args.optString("contact"))
        "auto_reply" -> JarvisAction.AutoReply(
            args.optString("mode", "on").lowercase(), args.optString("contact"), args.optString("app")
        )
        else -> JarvisAction.Speak("Action inconnue : $name.")
    }
}
