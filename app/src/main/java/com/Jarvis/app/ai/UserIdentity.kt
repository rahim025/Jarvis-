package com.jarvis.app.ai

import android.content.Context
import java.util.UUID

/**
 * Identifiant stable de l'utilisateur, utilisé par le backend pour retrouver
 * la bonne mémoire (faits + historique) dans Firestore.
 *
 * Généré une seule fois et sauvegardé dans les SharedPreferences : il reste le
 * même à chaque lancement de l'app, que ce soit depuis MainActivity ou la
 * bulle en arrière-plan. Un identifiant fixe sert de secours si jamais l'accès
 * aux SharedPreferences échoue, pour ne jamais bloquer l'appel au backend.
 */
object UserIdentity {
    private const val PREFS = "jarvis_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val FALLBACK_USER_ID = "rahim"

    private fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (existing != null) return existing

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }

    /** À utiliser partout : ne lève jamais d'exception. */
    fun getSafe(context: Context): String =
        runCatching { get(context) }.getOrElse { FALLBACK_USER_ID }
}
