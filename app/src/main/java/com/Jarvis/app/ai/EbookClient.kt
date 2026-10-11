package com.jarvis.app.ai

import android.content.Context
import com.jarvis.app.BuildConfig
import com.jarvis.app.EbookActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Parle aux routes /ebook/… du backend (création d'un e-book, progression, téléchargement).
 * Tout est bloquant : à appeler hors du thread principal.
 */
object EbookClient {

    /** Erreur lisible par l'utilisateur (message du serveur ou explication). */
    class EbookException(message: String) : Exception(message)

    private val BASE_URL = BuildConfig.BACKEND_URL.trimEnd('/')

    private val http = OkHttpClient.Builder()
        // Le plan gratuit de Render s'endort : le premier appel peut mettre 30-50 s à répondre.
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url("$BASE_URL$path")
        if (BuildConfig.APP_SHARED_SECRET.isNotBlank()) b.addHeader("x-app-secret", BuildConfig.APP_SHARED_SECRET)
        return b
    }

    private fun errorOf(code: Int, raw: String?): EbookException {
        val msg = runCatching { JSONObject(raw ?: "").optString("error") }.getOrNull().orEmpty()
        return EbookException(msg.ifBlank { "Erreur du serveur ($code)." })
    }

    /** Lance la création et renvoie l'identifiant du travail. */
    fun start(ctx: Context, topic: String, author: String, chapters: String, style: String, photo: String): String {
        val body = JSONObject(ApiKeyStore.requestExtras(ctx).toString())
            .put("topic", topic)
            .put("author", author)
            .put("style", style)
            .put("photo", photo)
        chapters.trim().toIntOrNull()?.let { body.put("chapters", it) }
        val req = request("/ebook/start")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}").optString("id").ifBlank { throw EbookException("Réponse inattendue du serveur.") }
        }
    }

    /** État du travail ; seules les nouvelles lignes de journal (après [since]) sont renvoyées. */
    fun status(id: String, since: Int): JSONObject {
        http.newCall(request("/ebook/status/$id?since=$since").get().build()).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}")
        }
    }

    fun cancel(id: String) {
        val req = request("/ebook/cancel/$id").post("{}".toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { }
    }

    /** Télécharge "cover.svg" ou "book.epub". */
    fun download(id: String, file: String): ByteArray {
        http.newCall(request("/ebook/$id/$file").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw errorOf(r.code, r.body?.string())
            return r.body?.bytes() ?: throw EbookException("Fichier vide.")
        }
    }
}

/** Ouvre l'écran de progression et renvoie la phrase que Jarvis dit à voix haute. */
object EbookLauncher {
    fun start(context: Context, action: JarvisAction.CreateEbook): String {
        val topic = action.topic.trim()
        if (topic.isBlank()) return "Sur quel sujet veux-tu que je crée l'e-book ?"
        val author = action.author.trim().ifBlank { rememberedFirstName(context) }
        context.startActivity(
            EbookActivity.intent(context, topic, author, action.chapters, action.style, action.photo)
        )
        return "C'est parti, je crée l'e-book sur $topic. Tu peux suivre chaque étape à l'écran."
    }

    /** Prénom retenu par la mémoire de Jarvis (« retiens que je m'appelle… »), pour la signature de la couverture. */
    private fun rememberedFirstName(context: Context): String = runCatching {
        val facts = JarvisMemory.get(context).allFacts()
        facts.firstOrNull { JarvisMemory.normalize(it.first) in listOf("prenom", "nom", "surnom") }?.second.orEmpty()
    }.getOrDefault("")
}
