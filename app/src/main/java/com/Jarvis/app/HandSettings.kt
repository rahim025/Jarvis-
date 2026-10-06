package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages du curseur main (caméra). Stockés dans les préférences : le service les relit
 * en direct dès qu'une valeur change, donc on voit l'effet immédiatement.
 */
object HandSettings {
    private const val PREFS = "jarvis_hand"
    private const val KEY_CLICK = "click_mode"
    private const val KEY_SENS = "sensitivity"
    private const val KEY_SMOOTH = "smoothing"
    private const val KEY_THRESH = "click_threshold"
    private const val KEY_PREVIEW = "preview"

    const val DEFAULT_SENS = 150
    const val DEFAULT_SMOOTH = 40
    const val DEFAULT_THRESH = 22

    /** Les gestes de clic au choix. [action] complète la phrase « … pour cliquer ». */
    enum class ClickMode(val id: String, val label: String, val action: String, val hint: String) {
        PINCH_INDEX(
            "pinch_index", "Pincement pouce + index", "pince pouce + index",
            "Le plus naturel. Le pointeur suit le dos de la main (pas le bout de l'index) pour ne pas bouger quand tu pinces."
        ),
        PINCH_MIDDLE(
            "pinch_middle", "Pincement pouce + majeur", "pince pouce + majeur",
            "Le plus précis : l'index pointe, le majeur clique, donc le pointeur ne bouge pas pendant le clic."
        ),
        DWELL(
            "dwell", "Rester immobile (1 seconde)", "garde le pointeur immobile 1 s",
            "Sans aucun geste : le clic part quand tu gardes le pointeur immobile. Plus lent, mais aucun faux clic."
        ),
        FIST(
            "fist", "Poing fermé (expérimental)", "ferme le poing",
            "Poing fermé = appui, main ouverte = relâché. Pratique pour glisser, moins précis pour viser."
        );

        companion object {
            fun from(id: String?): ClickMode = values().firstOrNull { it.id == id } ?: PINCH_INDEX
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun registerListener(context: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(context: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(l)

    fun clickMode(context: Context): ClickMode = ClickMode.from(prefs(context).getString(KEY_CLICK, null))
    fun setClickMode(context: Context, mode: ClickMode) = prefs(context).edit().putString(KEY_CLICK, mode.id).apply()

    /** Sensibilité du pointeur en % : 150 = la main n'a besoin de parcourir que les 2/3 de l'image. */
    fun sensitivity(context: Context): Int = prefs(context).getInt(KEY_SENS, DEFAULT_SENS).coerceIn(50, 300)
    fun setSensitivity(context: Context, v: Int) = prefs(context).edit().putInt(KEY_SENS, v).apply()

    /** Lissage en % : plus c'est haut, plus le pointeur est stable (mais un peu plus lent à suivre). */
    fun smoothing(context: Context): Int = prefs(context).getInt(KEY_SMOOTH, DEFAULT_SMOOTH).coerceIn(0, 90)
    fun setSmoothing(context: Context, v: Int) = prefs(context).edit().putInt(KEY_SMOOTH, v).apply()

    /** Seuil de clic en % de la taille de la main : plus c'est haut, plus il est facile de déclencher. */
    fun clickThreshold(context: Context): Int = prefs(context).getInt(KEY_THRESH, DEFAULT_THRESH).coerceIn(10, 50)
    fun setClickThreshold(context: Context, v: Int) = prefs(context).edit().putInt(KEY_THRESH, v).apply()

    fun preview(context: Context): Boolean = prefs(context).getBoolean(KEY_PREVIEW, true)
    fun setPreview(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_PREVIEW, on).apply()

    fun reset(context: Context) = prefs(context).edit().clear().apply()
}
