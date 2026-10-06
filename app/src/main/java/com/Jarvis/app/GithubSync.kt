package com.jarvis.app

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Envoie des fichiers sur un dépôt GitHub (API « contents »).
 * Si le fichier existe déjà, il est mis à jour (on récupère son sha) ; sinon il est créé.
 * Le jeton (token) est stocké chiffré avec les autres clés (ApiKeyStore, id « github »).
 */
object GithubSync {
    class Item(val path: String, val bytes: ByteArray)
    class Result(val ok: Int, val errors: List<String>)

    private const val PREFS = "jarvis_github"
    private const val MAX_BYTES = 25 * 1024 * 1024
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build()

    fun repo(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("repo", "rahim025/Jarvis-") ?: ""
    fun branch(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("branch", "main") ?: "main"
    fun folder(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("folder", "") ?: ""
    fun message(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("message", "Mise à jour depuis Jarvis") ?: ""

    fun save(c: Context, repo: String, branch: String, folder: String, message: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("repo", repo.trim()).putString("branch", branch.trim().ifBlank { "main" })
            .putString("folder", folder.trim().trim('/')).putString("message", message.trim().ifBlank { "Mise à jour depuis Jarvis" })
            .apply()
    }

    private fun enc(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** À appeler hors du thread principal. */
    fun upload(token: String, repo: String, branch: String, folder: String, message: String,
               items: List<Item>, onProgress: (String) -> Unit): Result {
        var ok = 0
        val errors = mutableListOf<String>()
        for ((i, it) in items.withIndex()) {
            val path = (if (folder.isBlank()) it.path else "$folder/${it.path}").trim('/')
            onProgress("(${i + 1}/${items.size}) $path")
            if (it.bytes.size > MAX_BYTES) { errors += "$path : trop gros (> 25 Mo)"; continue }
            try {
                val url = "https://api.github.com/repos/$repo/contents/${enc(path)}"
                // 1) le fichier existe-t-il déjà ? → on récupère son sha pour le mettre à jour
                var sha: String? = null
                client.newCall(base(url + "?ref=" + URLEncoder.encode(branch, "UTF-8"), token).get().build()).execute().use { r ->
                    if (r.isSuccessful) sha = JSONObject(r.body?.string() ?: "{}").optString("sha").ifBlank { null }
                }
                // 2) création ou mise à jour
                val body = JSONObject().put("message", message).put("branch", branch)
                    .put("content", Base64.encodeToString(it.bytes, Base64.NO_WRAP))
                sha?.let { s -> body.put("sha", s) }
                client.newCall(base(url, token).put(body.toString().toRequestBody(JSON)).build()).execute().use { r ->
                    if (r.isSuccessful) ok++
                    else errors += "$path : ${r.code} ${JSONObject(r.body?.string() ?: "{}").optString("message")}"
                }
            } catch (e: Exception) {
                errors += "$path : ${e.message}"
            }
        }
        return Result(ok, errors)
    }

    private fun base(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
}
