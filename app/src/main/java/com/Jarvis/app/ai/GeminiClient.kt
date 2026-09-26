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
 * Client Gemini — utile pour tout ce qui est multimodal (analyser une photo,
 * une capture d'écran, un document) plutôt que pour les actions rapides,
 * qui restent gérées par Groq (plus rapide en texte pur).
 */
object GeminiClient {

    private const val MODEL = "gemini-2.0-flash"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Question simple en texte. */
    fun ask(prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: return "Pas de réponse de Gemini.")
            return json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: "Pas de réponse de Gemini."
        }
    }

    /** Question avec une image (ex: capture d'écran) encodée en base64. */
    fun askWithImage(prompt: String, imageBase64: String, mimeType: String = "image/png"): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${BuildConfig.GEMINI_API_KEY}"
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            put(JSONObject().put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", imageBase64)
            }))
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: return "Pas de réponse de Gemini.")
            return json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: "Pas de réponse de Gemini."
        }
    }
}
