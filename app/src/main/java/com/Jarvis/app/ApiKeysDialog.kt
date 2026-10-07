package com.jarvis.app

import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Écran "Clés API & fournisseurs" (menu ⚙) : liste des fournisseurs, saisie de la clé,
 * choix du modèle, choix du cerveau actif, et bouton "Tester la clé".
 */
object ApiKeysDialog {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun dp(a: AppCompatActivity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun showList(activity: AppCompatActivity, onChanged: (String) -> Unit) {
        val active = ApiKeyStore.activeId(activity)
        val ids = ApiKeyStore.PROVIDERS.map { it.id } + ApiKeyStore.GEMINI_ID
        val entries = ids.map { id ->
            val isGemini = id == ApiKeyStore.GEMINI_ID
            val name = if (isGemini) "Gemini" else ApiKeyStore.PROVIDERS.first { it.id == id }.label
            val has = ApiKeyStore.hasKey(activity, id)
            val sub = when {
                isGemini -> "vision d'écran + recherche web"
                has && id == active -> "cerveau actif"
                else -> null
            }
            JarvisMenu.Entry(
                icon = if (isGemini) android.R.drawable.ic_menu_view else android.R.drawable.ic_lock_lock,
                title = name, sub = sub,
                badge = if (has) "clé ok" else "pas de clé", badgeOn = has
            ) { showEdit(activity, id, onChanged) }
        }
        JarvisMenu.show(activity, listOf(JarvisMenu.Section("fournisseurs", entries)),
            title = "Clés API", subtitle = "fournisseurs")
    }

    private fun showEdit(activity: AppCompatActivity, id: String, onChanged: (String) -> Unit) {
        val isGemini = id == ApiKeyStore.GEMINI_ID
        val isCustom = id == "custom"
        val provider = ApiKeyStore.PROVIDERS.firstOrNull { it.id == id }
        val title = if (isGemini) "Gemini" else provider?.label ?: id
        val existing = ApiKeyStore.getKey(activity, id)

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(0, dp(activity, 12), 0, 0)
        }

        val urlInput = EditText(activity).apply {
            hint = "https://…/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(ApiKeyStore.getBaseUrl(activity, id))
        }
        if (isCustom) {
            box.addView(label("URL de l'API (https, finit par /v1)"))
            box.addView(urlInput)
        }

        box.addView(label(if (existing.isNotBlank()) "Clé API (actuelle : ${ApiKeyStore.mask(existing)})" else "Clé API"))
        val keyInput = EditText(activity).apply {
            hint = if (existing.isNotBlank()) "Laisse vide pour garder l'actuelle" else "Colle ta clé ici"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(keyInput)

        val modelInput = EditText(activity).apply {
            hint = provider?.defaultModel?.ifBlank { "nom du modèle" } ?: ApiKeyStore.DEFAULT_GEMINI_MODEL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(ApiKeyStore.getModel(activity, id))
        }
        box.addView(label(if (isGemini) "Modèle de vision / recherche web" else "Modèle"))
        box.addView(modelInput)

        val useCheck = CheckBox(activity).apply {
            text = "Utiliser comme cerveau de Jarvis"
            isChecked = ApiKeyStore.activeId(activity).let { it == null || it == id }
        }
        if (!isGemini) box.addView(useCheck)

        val result = TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(activity, 8), 0, 0)
        }
        val testBtn = Button(activity).apply { text = "Tester la clé" }
        box.addView(testBtn)
        box.addView(result)

        testBtn.setOnClickListener {
            val typed = ApiKeyStore.cleanKey(keyInput.text.toString())
            val key = typed.ifBlank { existing }
            if (key.isBlank()) {
                result.text = "Saisis d'abord une clé."
                return@setOnClickListener
            }
            val base = if (isCustom) urlInput.text.toString().trim().trimEnd('/')
            else ApiKeyStore.getBaseUrl(activity, id)
            result.text = "Test en cours…"
            Thread {
                val model = modelInput.text.toString().trim()
                val msg = runCatching { testKey(id, base, key, model) }.getOrElse { "Échec réseau : ${it.message}" }
                activity.runOnUiThread { result.text = msg }
            }.start()
        }

        AlertDialog.Builder(activity, R.style.JarvisDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val typed = ApiKeyStore.cleanKey(keyInput.text.toString())
                if (typed.isNotBlank()) ApiKeyStore.setKey(activity, id, typed)
                ApiKeyStore.setModel(activity, id, modelInput.text.toString())
                if (!isGemini) {
                    if (isCustom) ApiKeyStore.setCustomBaseUrl(activity, urlInput.text.toString())
                    if (useCheck.isChecked && ApiKeyStore.hasKey(activity, id)) ApiKeyStore.setActive(activity, id)
                }
                onChanged("Clé $title enregistrée. Jarvis l'utilisera dès la prochaine commande.")
            }
            .setNeutralButton("Supprimer la clé") { _, _ ->
                ApiKeyStore.removeKey(activity, id)
                onChanged("Clé $title supprimée.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Fait un tout petit appel réel au fournisseur : 200 = clé et modèle valides. */
    private fun testKey(id: String, baseUrl: String, key: String, model: String): String {
        val request = if (id == ApiKeyStore.GEMINI_ID) {
            Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .addHeader("x-goog-api-key", key)
                .build()
        } else {
            if (!baseUrl.startsWith("https://")) return "URL invalide (https obligatoire)."
            if (model.isBlank()) return "Indique le nom du modèle avant de tester."
            val json = org.json.JSONObject()
                .put("model", model)
                .put("max_completion_tokens", 8)
                .put(
                    "messages",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("role", "user").put("content", "ping")
                    )
                )
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }
        http.newCall(request).execute().use { r ->
            val detail = runCatching {
                org.json.JSONObject(r.body?.string() ?: "").optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty().take(160)
            return when {
                r.isSuccessful -> "✓ Clé et modèle valides."
                r.code == 401 || r.code == 403 -> "✗ Clé refusée (${r.code}) : vérifie-la, ou la région (api.minimax.io ≠ api.minimaxi.com)."
                r.code == 404 || r.code == 400 -> "✗ Modèle ou URL incorrects (${r.code}) $detail"
                r.code == 429 -> "⚠ Quota atteint (429) : la clé est bonne mais la limite est dépassée."
                else -> "✗ Erreur ${r.code} $detail"
            }
        }
    }
}
