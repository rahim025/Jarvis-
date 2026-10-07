package com.jarvis.app

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Fenêtre de réglage du curseur main. Chaque changement est appliqué tout de suite au service. */
object HandSettingsDialog {

    fun show(activity: AppCompatActivity) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun addText(text: String, sizeSp: Float, bold: Boolean = false, topDp: Int = 0): TextView {
            val tv = TextView(activity).apply {
                this.text = text
                textSize = sizeSp
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(topDp), 0, dp(2))
            }
            root.addView(tv)
            return tv
        }

        if (!JarvisHandTrackingService.isRunning) {
            addText("Le curseur main n'est pas actif : active-le dans le menu ⚙ pour voir l'effet en direct.", 13f)
        }
        addText(
            "L'aperçu (en haut à droite) montre la main vue par la caméra. Le nombre est l'écart des doigts " +
                "divisé par le seuil : tu cliques quand il passe sous le seuil.",
            12f
        )

        // ── Geste de clic ──
        addText("Geste de clic", 14f, bold = true, topDp = 14)
        val current = HandSettings.clickMode(activity)
        val hint = TextView(activity).apply { textSize = 12f; text = current.hint }
        val group = RadioGroup(activity)
        for (mode in HandSettings.ClickMode.values()) {
            val rb = RadioButton(activity).apply {
                id = View.generateViewId()
                text = mode.label
                tag = mode
                isChecked = mode == current
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val mode = g.findViewById<RadioButton>(checkedId)?.tag as? HandSettings.ClickMode
            if (mode != null) {
                HandSettings.setClickMode(activity, mode)
                hint.text = mode.hint
            }
        }
        root.addView(group)
        root.addView(hint)

        // ── Curseurs ──
        fun slider(label: String, minV: Int, maxV: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
            val title = addText("$label : ${format(value)}", 14f, bold = true, topDp = 14)
            val bar = SeekBar(activity)
            bar.max = maxV - minV
            bar.progress = value.coerceIn(minV, maxV) - minV
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + minV
                    title.text = "$label : ${format(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            root.addView(bar)
        }

        slider("Sensibilité du pointeur", 50, 300, HandSettings.sensitivity(activity), { "$it %" }) {
            HandSettings.setSensitivity(activity, it)
        }
        addText("Haut = un petit mouvement de main parcourt tout l'écran.", 12f)

        slider("Stabilité (lissage)", 0, 90, HandSettings.smoothing(activity), { "$it %" }) {
            HandSettings.setSmoothing(activity, it)
        }
        addText("Haut = pointeur plus stable, mais un peu plus lent à suivre.", 12f)

        slider("Seuil de clic", 10, 50, HandSettings.clickThreshold(activity), { "$it %" }) {
            HandSettings.setClickThreshold(activity, it)
        }
        addText("Haut = clic plus facile à déclencher. Baisse-le si tu cliques sans le vouloir.", 12f)

        // ── Aperçu caméra ──
        val preview = SwitchCompat(activity).apply {
            text = "Aperçu de la caméra"
            isChecked = HandSettings.preview(activity)
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, on -> HandSettings.setPreview(activity, on) }
        }
        root.addView(preview)

        AlertDialog.Builder(activity, R.style.JarvisDialog)
            .setTitle("Curseur main")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton("Fermer", null)
            .setNegativeButton("Valeurs par défaut") { _, _ ->
                HandSettings.reset(activity)
                show(activity)
            }
            .show()
    }
}
