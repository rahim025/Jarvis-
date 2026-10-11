package com.jarvis.app.ai

import android.content.Context
import org.json.JSONObject

/** Profil saisi à la première ouverture : nom, âge, ton de Jarvis et voix. Stocké sur le téléphone seulement. */
object UserProfile {
    const val VOICE_STANDARD = "standard"
    const val VOICE_KAWAI = "kawai"
    private const val PREFS = "jarvis_profile"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDone(ctx: Context): Boolean = prefs(ctx).getBoolean("done", false)

    fun save(ctx: Context, name: String, age: Int, tone: String, voice: String) {
        prefs(ctx).edit()
            .putString("name", name)
            .putInt("age", age)
            .putString("tone", tone)
            .putString("voice", voice)
            .putBoolean("done", true)
            .apply()
    }

    fun voice(ctx: Context): String = prefs(ctx).getString("voice", VOICE_STANDARD) ?: VOICE_STANDARD

    fun toJson(ctx: Context): JSONObject {
        val p = prefs(ctx)
        return JSONObject()
            .put("name", p.getString("name", "") ?: "")
            .put("age", p.getInt("age", 0))
            .put("tone", p.getString("tone", "") ?: "")
            .put("voice", voice(ctx))
    }
}
