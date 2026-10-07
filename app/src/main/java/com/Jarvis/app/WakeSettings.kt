package com.jarvis.app

import android.content.Context
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Réglages du réveil : off, double clap, ou mot « Jarvis ». */
object WakeSettings {
    const val OFF = "off"
    const val CLAP = "clap"
    const val WORD = "word"
    private const val PREFS = "jarvis_wake"

    fun mode(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", OFF) ?: OFF
    fun openApp(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("openApp", true)

    fun save(c: Context, mode: String, openApp: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode).putBoolean("openApp", openApp).apply()
    }

    fun label(c: Context): String = when (mode(c)) {
        CLAP -> "double clap"
        WORD -> "mot « Jarvis »"
        else -> "désactivé"
    }
}

object WakeDialog {
    /** [ensureService] est appelé si un réveil est activé alors que le service d'arrière-plan ne tourne pas. */
    fun show(a: AppCompatActivity, ensureService: () -> Unit, onChanged: (String) -> Unit) {
        val dp = a.resources.displayMetrics.density
        val group = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            WakeSettings.OFF to "Désactivé",
            WakeSettings.CLAP to "Double clap (fiable, sans réseau)",
            WakeSettings.WORD to "Dire « Jarvis » (expérimental)"
        )
        val current = WakeSettings.mode(a)
        val buttons = options.map { (key, text) ->
            RadioButton(a).apply { id = android.view.View.generateViewId(); this.text = text; tag = key; isChecked = key == current }
                .also { group.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        val openApp = CheckBox(a).apply {
            text = "Ouvrir l'app au réveil (sinon la bulle écoute)"
            isChecked = WakeSettings.openApp(a)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(group)
            addView(openApp)
        }
        AlertDialog.Builder(a, R.style.JarvisDialog)
            .setTitle("Réveil vocal")
            .setMessage("Jarvis écoute en arrière-plan et se réveille tout seul. Un seul mode à la fois (le micro ne peut pas servir aux deux).")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val mode = buttons.firstOrNull { it.isChecked }?.tag as? String ?: WakeSettings.OFF
                WakeSettings.save(a, mode, openApp.isChecked)
                if (mode != WakeSettings.OFF && !JarvisForegroundService.isRunning) ensureService()
                onChanged(if (mode == WakeSettings.OFF) "Réveil désactivé." else "Réveil activé : ${WakeSettings.label(a)}.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
