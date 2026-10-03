package com.jarvis.app.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Stocke les clés API saisies dans l'app (menu ⚙ > Clés API & fournisseurs).
 * Chiffrées sur le téléphone (EncryptedSharedPreferences). À chaque question,
 * Jarvis les envoie au backend, qui les utilise à la place de ses variables Render.
 */
object ApiKeyStore {

    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String
    )

    /** Fournisseurs "cerveau" (API compatible OpenAI avec appels d'outils). */
    val PROVIDERS = listOf(
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-oss-120b"),
        Provider("minimax", "MiniMax", "https://api.minimax.io/v1", "MiniMax-M3"),
        Provider("custom", "Autre (compatible OpenAI)", "", "")
    )

    /** Gemini sert à la vision d'écran et à la recherche web (pas de cerveau). */
    const val GEMINI_ID = "gemini"

    /** Alias Google qui pointe toujours vers le dernier modèle Flash (évite les noms périmés). */
    const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

    private const val FILE = "jarvis_api_keys"
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext
            val p = runCatching {
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                // Keystore capricieux sur certains téléphones : on garde un stockage privé simple.
                app.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
            }
            cached = p
            return p
        }
    }

    /** Une clé collée contient souvent des espaces, retours à la ligne ou guillemets : on nettoie. */
    fun cleanKey(raw: String): String =
        raw.trim().trim('"', '\'').replace(Regex("\\s+"), "")

    fun getKey(ctx: Context, id: String): String = prefs(ctx).getString("key_$id", "") ?: ""

    fun setKey(ctx: Context, id: String, key: String) {
        prefs(ctx).edit().putString("key_$id", cleanKey(key)).apply()
    }

    fun removeKey(ctx: Context, id: String) {
        prefs(ctx).edit().remove("key_$id").apply()
    }

    fun hasKey(ctx: Context, id: String): Boolean = getKey(ctx, id).isNotBlank()

    fun getModel(ctx: Context, id: String): String {
        val saved = prefs(ctx).getString("model_$id", "") ?: ""
        if (id == GEMINI_ID) return saved.ifBlank { DEFAULT_GEMINI_MODEL }
        return saved.ifBlank { PROVIDERS.firstOrNull { it.id == id }?.defaultModel ?: "" }
    }

    fun setModel(ctx: Context, id: String, model: String) {
        prefs(ctx).edit().putString("model_$id", model.trim()).apply()
    }

    fun getBaseUrl(ctx: Context, id: String): String {
        if (id == "custom") return prefs(ctx).getString("url_custom", "") ?: ""
        return PROVIDERS.firstOrNull { it.id == id }?.baseUrl ?: ""
    }

    fun setCustomBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString("url_custom", url.trim().trimEnd('/')).apply()
    }

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString("active", id).apply()
    }

    /** Fournisseur utilisé comme cerveau : celui choisi s'il a une clé, sinon le premier qui en a une. */
    fun activeId(ctx: Context): String? {
        val chosen = prefs(ctx).getString("active", null)
        if (chosen != null && hasKey(ctx, chosen)) return chosen
        return PROVIDERS.firstOrNull { hasKey(ctx, it.id) }?.id
    }

    fun mask(key: String): String =
        if (key.length <= 4) "••••" else "••••" + key.takeLast(4)

    /** Champs ajoutés à chaque requête vers le backend (vide si rien n'est configuré dans l'app). */
    fun requestExtras(ctx: Context): JSONObject {
        val out = JSONObject()
        activeId(ctx)?.let { id ->
            val base = getBaseUrl(ctx, id)
            val model = getModel(ctx, id)
            if (base.isNotBlank() && model.isNotBlank()) {
                out.put(
                    "llm",
                    JSONObject()
                        .put("baseUrl", base)
                        .put("apiKey", getKey(ctx, id))
                        .put("model", model)
                )
            }
        }
        val gemini = getKey(ctx, GEMINI_ID)
        if (gemini.isNotBlank()) {
            out.put("geminiKey", gemini)
            out.put("geminiModel", getModel(ctx, GEMINI_ID))
        }
        return out
    }
}
