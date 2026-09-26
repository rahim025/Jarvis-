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
 * Résultat renvoyé par Groq : soit une réponse texte à dire à l'utilisateur,
 * soit une action à exécuter (avec ses paramètres).
 */
sealed class JarvisAction {
    data class Speak(val text: String) : JarvisAction()
    data class OpenApp(val appName: String) : JarvisAction()
    data class SendSms(val contact: String, val message: String) : JarvisAction()
    data class ClickOnScreen(val label: String) : JarvisAction()
    data class TypeText(val text: String) : JarvisAction()
    object GoHome : JarvisAction()
    object GoBack : JarvisAction()
}

object GroqClient {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.3-70b-versatile"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Définit les "outils" que Groq peut décider d'appeler.
    // C'est ça le cœur du système : au lieu de juste répondre du texte,
    // le modèle choisit une fonction + des arguments, qu'on exécute nous-mêmes.
    private val tools: JSONArray by lazy {
        JSONArray().apply {
            put(tool("open_app", "Ouvre une application par son nom", mapOf("app_name" to "nom de l'app, ex: WhatsApp")))
            put(tool("send_sms", "Envoie un SMS à un contact", mapOf("contact" to "nom ou numéro", "message" to "contenu du SMS")))
            put(tool("click_on_screen", "Clique sur un élément visible à l'écran par son texte/label", mapOf("label" to "texte du bouton/élément à cliquer")))
            put(tool("type_text", "Tape du texte dans le champ actuellement sélectionné", mapOf("text" to "texte à taper")))
            put(tool("go_home", "Retourne à l'écran d'accueil du téléphone", emptyMap()))
            put(tool("go_back", "Appuie sur le bouton retour", emptyMap()))
        }
    }

    private fun tool(name: String, description: String, params: Map<String, String>): JSONObject {
        val properties = JSONObject()
        params.forEach { (key, desc) -> properties.put(key, JSONObject().put("type", "string").put("description", desc)) }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JSONArray(params.keys))
                })
            })
        }
    }

    /**
     * Envoie la commande utilisateur à Groq et récupère l'action décidée.
     * Appel bloquant : à lancer depuis une coroutine (Dispatchers.IO).
     */
    fun decideAction(userText: String): JarvisAction {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("tools", tools)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Tu es Jarvis, un assistant personnel sur Android. " +
                        "Si la demande nécessite une action sur le téléphone, appelle l'outil correspondant. " +
                        "Sinon, réponds simplement en texte, en français, de façon concise.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: return JarvisAction.Speak("Pas de réponse de Groq."))
            val message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")

            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val call = toolCalls.getJSONObject(0).getJSONObject("function")
                val args = JSONObject(call.getString("arguments"))
                return when (call.getString("name")) {
                    "open_app" -> JarvisAction.OpenApp(args.getString("app_name"))
                    "send_sms" -> JarvisAction.SendSms(args.getString("contact"), args.getString("message"))
                    "click_on_screen" -> JarvisAction.ClickOnScreen(args.getString("label"))
                    "type_text" -> JarvisAction.TypeText(args.getString("text"))
                    "go_home" -> JarvisAction.GoHome
                    "go_back" -> JarvisAction.GoBack
                    else -> JarvisAction.Speak("Action inconnue.")
                }
            }

            return JarvisAction.Speak(message.optString("content", "..."))
        }
    }
}
