package com.jarvis.app

import android.content.Intent
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Réglages du volant virtuel. Appliqués tout de suite (le service relit les valeurs à chaque image). */
object WheelSettingsDialog {

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
        fun slider(label: String, minV: Int, maxV: Int, value: Int, onChange: (Int) -> Unit) {
            val title = addText("$label : $value°", 14f, bold = true, topDp = 14)
            val bar = SeekBar(activity)
            bar.max = maxV - minV
            bar.progress = value.coerceIn(minV, maxV) - minV
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + minV
                    title.text = "$label : $v°"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            root.addView(bar)
        }

        if (!JarvisWheelService.isRunning) {
            addText("Le volant n'est pas actif : active-le dans le menu ⚙ pour voir l'effet en direct.", 13f)
        }

        slider("Angle pour braquer à fond", 15, 90, WheelSettings.lockDeg(activity)) {
            WheelSettings.setLockDeg(activity, it)
        }
        addText("Petit = volant nerveux (un petit tour suffit). Grand = plus doux et plus précis.", 12f)

        slider("Zone morte (tout droit)", 2, 20, WheelSettings.deadzoneDeg(activity)) {
            WheelSettings.setDeadzoneDeg(activity, it)
        }
        addText("Grand = les petits tremblements de mains ne font plus braquer.", 12f)

        val inv = SwitchCompat(activity).apply {
            text = "Inverser gauche / droite"
            isChecked = WheelSettings.invert(activity)
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, on -> WheelSettings.setInvert(activity, on) }
        }
        root.addView(inv)
        addText("À activer seulement si l'aperçu affiche DROITE quand tu tournes à gauche.", 12f)

        AlertDialog.Builder(activity, R.style.JarvisDialog)
            .setTitle("Volant virtuel")
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Recentrer") { _, _ ->
                if (JarvisWheelService.isRunning) {
                    activity.startService(Intent(activity, JarvisWheelService::class.java).setAction(JarvisWheelService.ACTION_RECENTER))
                }
            }
            .setNegativeButton("Valeurs par défaut") { _, _ ->
                WheelSettings.reset(activity)
                show(activity)
            }
            .show()
    }
}
