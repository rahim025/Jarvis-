package com.jarvis.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.UserProfile

/** Première ouverture : nom, âge, ton de Jarvis et voix (dont « kawai »). Ensuite, on passe directement à Jarvis. */
class SetupActivity : AppCompatActivity() {

    // Charte graphique JARVIS (mêmes couleurs que le reste de l'app).
    private val bg = Color.parseColor("#050508")
    private val card = Color.parseColor("#0F1722")
    private val border = Color.parseColor("#1E3A4A")
    private val cyan = Color.parseColor("#4CA8E8")
    private val textMain = Color.parseColor("#E6F7FF")
    private val textSoft = Color.parseColor("#CFE6F7")
    private val muted = Color.parseColor("#5F7F99")
    private val hintColor = Color.parseColor("#4A6D88")

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardShape(stroke: Int) = GradientDrawable().apply {
        cornerRadius = px(10).toFloat()
        setColor(card)
        setStroke(px(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UserProfile.isDone(this)) {
            openMain()
            return
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val pad = px(24)

        fun field(hintText: String, numeric: Boolean = false, multiline: Boolean = false) = EditText(this).apply {
            this.hint = hintText
            setTextColor(textMain)
            setHintTextColor(hintColor)
            textSize = 15f
            background = cardShape(border)
            setPadding(px(14), px(14), px(14), px(14))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
            // Bordure cyan quand le champ est actif.
            setOnFocusChangeListener { v, focused ->
                (v.background as GradientDrawable).setStroke(px(1), if (focused) cyan else border)
            }
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text.uppercase()
            setTextColor(cyan)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, px(20), 0, px(8))
        }

        val nameField = field("Ton nom")
        val ageField = field("Ton âge", numeric = true)
        val toneField = field("Le ton de Jarvis (ex : sarcastique et drôle, calme et sérieux…)", multiline = true)

        // Choix de la voix : deux cartes sélectionnables (une seule active).
        var kawaiSelected = false
        val rows = mutableListOf<Pair<LinearLayout, View>>()
        fun refreshVoice() {
            rows.forEachIndexed { i, (row, dot) ->
                val on = (i == 1) == kawaiSelected
                (row.background as GradientDrawable).setStroke(px(1), if (on) cyan else border)
                (dot.background as GradientDrawable).apply {
                    setColor(if (on) cyan else Color.TRANSPARENT)
                    setStroke(px(2), if (on) cyan else Color.parseColor("#35506A"))
                }
            }
        }
        fun voiceCard(text: String, kawai: Boolean): LinearLayout {
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                layoutParams = LinearLayout.LayoutParams(px(18), px(18)).apply { marginEnd = px(12) }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardShape(border)
                setPadding(px(14), px(14), px(14), px(14))
                addView(dot)
                addView(TextView(this@SetupActivity).apply {
                    this.text = text
                    setTextColor(textSoft)
                    textSize = 14.5f
                })
                setOnClickListener { kawaiSelected = kawai; refreshVoice() }
            }
            rows.add(row to dot)
            return row
        }
        val standardRow = voiceCard("Standard", kawai = false)
        val kawaiRow = voiceCard("Kawai : lycéenne japonaise très mignonne", kawai = true)
        refreshVoice()

        val start = Button(this).apply {
            text = "Commencer"
            isAllCaps = true
            setTextColor(textMain)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            stateListAnimator = null
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1B4668"), Color.parseColor("#103049"))
            ).apply {
                cornerRadius = px(10).toFloat()
                setStroke(px(1), cyan)
            }
            // Léger halo cyan (Android 9+).
            elevation = px(8).toFloat()
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = cyan
                outlineAmbientShadowColor = cyan
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad + px(8), pad, pad)
            addView(TextView(this@SetupActivity).apply {
                text = "Bienvenue. Configurons JARVIS."
                setTextColor(textMain)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SetupActivity).apply {
                text = "Quelques informations pour personnaliser votre assistant."
                setTextColor(muted)
                textSize = 13f
                setPadding(0, px(4), 0, px(8))
            })
            addView(label("Comment tu t'appelles ?"))
            addView(nameField)
            addView(label("Quel âge as-tu ?"))
            addView(ageField)
            addView(label("Quel ton veux-tu donner à Jarvis ?"))
            addView(toneField)
            addView(label("Quelle voix ?"))
            addView(standardRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(10) })
            addView(kawaiRow)
            addView(start, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(54)
            ).apply { topMargin = px(28) })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(column)
        })

        start.setOnClickListener {
            val name = nameField.text.toString().trim()
            val age = ageField.text.toString().trim().toIntOrNull()
            if (name.isBlank()) {
                Toast.makeText(this@SetupActivity, "Dis-moi ton nom.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (age == null || age !in 3..120) {
                Toast.makeText(this@SetupActivity, "Entre un âge valide.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tone = toneField.text.toString().trim().take(200).ifBlank { "élégant, précis et respectueux" }
            val voice = if (kawaiSelected) UserProfile.VOICE_KAWAI else UserProfile.VOICE_STANDARD
            UserProfile.save(this@SetupActivity, name.take(40), age, tone, voice)
            openMain()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
