package com.jarvis.app.ai

import com.jarvis.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Résultat renvoyé par le backend : soit une réponse texte à dire à l'utilisateur,
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

/**
 * Parle uniquement au backend Render — plus jamais directement à Groq ou Gemini.
 * Les clés API restent côté serveur ; ici on n'a que l'URL du backend et,
 * si configuré, un secret partagé envoyé dans un header.
 */
object BackendClient {

    // Défini dans local.properties -> BuildConfig, ex: "https://jarvis-43io.onrender.com"
    private val BASE_URL = BuildConfig.BACKEND_URL.trimEnd('/')

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Le plan gratuit de Render met le service en veille après inactivité ;
        // le premier appel après une pause peut prendre 30-50s à répondre.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url).addHeader("Content-Type", "application/json")
        if (BuildConfig.APP_SHARED_SECRET.isNotBlank()) {
            builder.addHeader("x-app-secret", BuildConfig.APP_SHARED_SECRET)
        }
        return builder
    }

    /** Envoie le texte reconnu par la voix au backend, qui interroge Groq et décide d'une action. */
    fun decideAction(userText: String): JarvisAction {
        val body = JSONObject().put("text", userText)
        val request = requestBuilder("$BASE_URL/ask")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: return JarvisAction.Speak("Pas de réponse du backend."))

            if (!response.isSuccessful) {
                return JarvisAction.Speak("Erreur backend : ${json.optString("error", "inconnue")}")
            }

            return when (json.optString("type")) {
                "action" -> parseAction(json.getString("name"), json.getJSONObject("args"))
                else -> JarvisAction.Speak(json.optString("text", "..."))
            }
        }
    }

    /** Envoie une image (ex: capture d'écran) en base64 au backend pour analyse par Gemini. */
    fun analyzeImage(prompt: String, imageBase64: String, mimeType: String = "image/png"): String {
        val body = JSONObject().apply {
            put("prompt", prompt)
            put("imageBase64", imageBase64)
            put("mimeType", mimeType)
        }
        val request = requestBuilder("$BASE_URL/vision")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: return "Pas de réponse du backend.")
            return json.optString("text", "Pas de réponse.")
        }
    }

    private fun parseAction(name: String, args: JSONObject): JarvisAction = when (name) {
        "open_app" -> JarvisAction.OpenApp(args.getString("app_name"))
        "send_sms" -> JarvisAction.SendSms(args.getString("contact"), args.getString("message"))
        "click_on_screen" -> JarvisAction.ClickOnScreen(args.getString("label"))
        "type_text" -> JarvisAction.TypeText(args.getString("text"))
        "go_home" -> JarvisAction.GoHome
        "go_back" -> JarvisAction.GoBack
        else -> JarvisAction.Speak("Action inconnue.")
    }
}
