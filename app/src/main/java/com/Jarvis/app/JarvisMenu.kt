package com.jarvis.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Menu ⚙ au style Jarvis : fond bleu nuit, cadre et icônes cyan, sections, voyants on/off. */
object JarvisMenu {
    class Entry(
        val icon: Int,
        val title: String,
        val sub: String? = null,
        val state: Boolean? = null,      // null = flèche ›, sinon voyant on/off
        val danger: Boolean = false,
        val onClick: () -> Unit
    )

    class Section(val label: String, val entries: List<Entry>)

    private val CYAN = Color.parseColor("#4CA8E8")
    private val GREEN = Color.parseColor("#22C55E")
    private val RED = Color.parseColor("#EF4444")
    private val TEXT = Color.parseColor("#CFE6F7")
    private val MUTED = Color.parseColor("#5F7F99")

    fun show(a: Activity, sections: List<Section>) {
        val dm = a.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        fun box(fill: String, stroke: String, radius: Int) = GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
            cornerRadius = dp(radius).toFloat()
        }

        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = box("#08111B", "#1B4668", 16)
            setPadding(dp(12), dp(14), dp(12), dp(10))
        }

        // Titre
        panel.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
            addView(TextView(a).apply {
                text = "Jarvis"; textSize = 20f; setTextColor(CYAN); letterSpacing = 0.35f
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(a).apply {
                text = "réglages"; textSize = 11f; setTextColor(MUTED)
                typeface = Typeface.MONOSPACE; letterSpacing = 0.12f
            })
        })

        // Liste défilante
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        for (s in sections) {
            list.addView(TextView(a).apply {
                text = s.label; textSize = 11f; setTextColor(Color.parseColor("#4A6D88"))
                typeface = Typeface.MONOSPACE; letterSpacing = 0.2f
                setPadding(dp(4), dp(12), dp(4), dp(6))
            })
            for (e in s.entries) {
                val row = LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = if (e.danger) box("#150B10", "#4A1D24", 12) else box("#0A1623", "#12304A", 12)
                    isClickable = true
                    setOnClickListener { d.dismiss(); e.onClick() }
                }
                val tint = if (e.danger) RED else CYAN
                row.addView(ImageView(a).apply {
                    setImageResource(e.icon)
                    setColorFilter(tint)
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = if (e.danger) box("#220F15", "#6B2630", 10) else box("#0B2236", "#1B4668", 10)
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(12) })

                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(a).apply {
                    text = e.title; textSize = 14f
                    setTextColor(if (e.danger) Color.parseColor("#F3B4B4") else TEXT)
                })
                e.sub?.let { sub ->
                    col.addView(TextView(a).apply { text = sub; textSize = 11f; setTextColor(MUTED) })
                }
                row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

                when {
                    e.state != null -> row.addView(TextView(a).apply {
                        text = if (e.state) "● on" else "● off"
                        textSize = 11f; typeface = Typeface.MONOSPACE
                        setTextColor(if (e.state) GREEN else MUTED)
                    })
                    !e.danger -> row.addView(TextView(a).apply {
                        text = "›"; textSize = 20f; setTextColor(Color.parseColor("#35506A"))
                    })
                }
                list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
            }
        }
        panel.addView(ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Fermer
        panel.addView(TextView(a).apply {
            text = "FERMER"; textSize = 13f; setTextColor(CYAN)
            typeface = Typeface.MONOSPACE; letterSpacing = 0.18f
            gravity = Gravity.END
            setPadding(dp(6), dp(12), dp(6), dp(4))
            setOnClickListener { d.dismiss() }
        })

        d.setContentView(panel)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.86f).toInt())
        }
        d.show()
    }
}
