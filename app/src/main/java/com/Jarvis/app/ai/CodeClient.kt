package com.jarvis.app.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import com.jarvis.app.BuildConfig
import com.jarvis.app.PreviewActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Parle aux routes /code/… du backend (projet de code -> ZIP). Bloquant : hors thread principal. */
object CodeClient {

    class CodeException(message: String) : Exception(message)

    private val BASE_URL = BuildConfig.BACKEND_URL.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url("$BASE_URL$path")
        if (BuildConfig.APP_SHARED_SECRET.isNotBlank()) b.addHeader("x-app-secret", BuildConfig.APP_SHARED_SECRET)
        return b
    }

    private fun errorOf(code: Int, raw: String?): CodeException {
        val msg = runCatching { JSONObject(raw ?: "").optString("error") }.getOrNull().orEmpty()
        return CodeException(msg.ifBlank { "Erreur du serveur ($code)." })
    }

    fun start(ctx: Context, request: String, language: String, name: String, mode: String): String {
        val body = JSONObject(ApiKeyStore.requestExtras(ctx).toString())
            .put("request", request)
            .put("language", language)
            .put("name", name)
            .put("mode", mode)
        val req = request("/code/start")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}").optString("id").ifBlank { throw CodeException("Réponse inattendue du serveur.") }
        }
    }

    fun status(id: String): JSONObject {
        http.newCall(request("/code/status/$id").get().build()).execute().use { r ->
            val raw = r.body?.string()
            if (!r.isSuccessful) throw errorOf(r.code, raw)
            return JSONObject(raw ?: "{}")
        }
    }

    /** Code source de l'aperçu (index.html) pour les projets en mode web. */
    fun source(id: String): String {
        http.newCall(request("/code/$id/source").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw errorOf(r.code, r.body?.string())
            return r.body?.string() ?: throw CodeException("Code vide.")
        }
    }

    fun download(id: String): ByteArray {
        http.newCall(request("/code/$id/project.zip").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw errorOf(r.code, r.body?.string())
            return r.body?.bytes() ?: throw CodeException("ZIP vide.")
        }
    }
}

/** Lance un projet de code en tâche de fond, puis enregistre le ZIP dans Téléchargements/Jarvis. */
object CodeLauncher {

    fun start(context: Context, action: JarvisAction.CreateCode): String {
        val request = action.request.trim()
        if (request.isBlank()) return "Qu'est-ce que je dois coder ?"
        val app = context.applicationContext
        thread(name = "code-project") { runJob(app, request, action.language.trim(), action.name.trim(), action.mode.trim()) }
        return "C'est parti, je code le projet. Je te préviens quand le ZIP est prêt."
    }

    private fun runJob(ctx: Context, request: String, language: String, name: String, mode: String) {
        val handler = Handler(Looper.getMainLooper())
        fun say(msg: String) = handler.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        var lastLogSeq = 0
        try {
            val id = CodeClient.start(ctx, request, language, name, mode)
            say("C'est parti, je code le projet…")
            var fails = 0
            while (true) {
                Thread.sleep(2500)
                val st = try {
                    CodeClient.status(id).also { fails = 0 }
                } catch (e: IOException) {
                    if (++fails > 40) throw CodeClient.CodeException("Connexion perdue avec le serveur. Relance la demande.")
                    continue
                }
                val log = st.optJSONArray("log")
                if (log != null) {
                    for (i in 0 until log.length()) {
                        val entry = log.optJSONObject(i) ?: continue
                        val n = entry.optInt("n", 0)
                        if (n > lastLogSeq) {
                            lastLogSeq = n
                            val msg = entry.optString("msg", "")
                            if (msg.isNotBlank()) say(msg)
                        }
                    }
                }
                when (st.optString("status")) {
                    "done" -> {
                        if (st.optBoolean("web")) {
                            ctx.startActivity(PreviewActivity.intent(ctx, id, st.optString("name", "projet")))
                            return
                        }
                        val bytes = CodeClient.download(id)
                        val file = saveZip(ctx, bytes, st.optString("name", "projet"))
                        say("Projet prêt : $file")
                        return
                    }
                    "error" -> throw CodeClient.CodeException(st.optString("error", "Erreur inconnue."))
                }
            }
        } catch (e: Exception) {
            say(e.message ?: "Erreur pendant la création du projet.")
        }
    }

    fun saveZip(ctx: Context, bytes: ByteArray, projectName: String): String {
        val fileName = "${projectName.ifBlank { "projet" }}.zip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Jarvis")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw CodeClient.CodeException("Impossible d'enregistrer le ZIP.")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Téléchargements/Jarvis/$fileName"
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "code").apply { mkdirs() }
        File(dir, fileName).writeBytes(bytes)
        return dir.absolutePath + "/" + fileName
    }
}
