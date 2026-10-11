package com.jarvis.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.UserProfile

/** Première ouverture : nom, âge, ton de Jarvis et voix (dont « kawai »). Ensuite, on passe directement à Jarvis. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UserProfile.isDone(this)) {
            openMain()
            return
        }

        val pad = (24 * resources.displayMetrics.density).toInt()

        fun field(hint: String, numeric: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(140, 150, 170))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(246, 192, 38))
            textSize = 14f
            setPadding(0, pad / 2, 0, 4)
        }

        val nameField = field("Ton nom")
        val ageField = field("Ton âge", numeric = true)
        val toneField = field("Le ton de Jarvis (ex : sarcastique et drôle, calme et sérieux…)")

        val standard = RadioButton(this).apply { text = "Standard"; setTextColor(Color.WHITE) }
        val kawai = RadioButton(this).apply {
            text = "Kawai : lycéenne japonaise très mignonne"
            setTextColor(Color.WHITE)
        }
        val voiceGroup = RadioGroup(this).apply {
            addView(standard)
            addView(kawai)
        }
        standard.isChecked = true

        val start = Button(this).apply { text = "Commencer" }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SetupActivity).apply {
                text = "Bienvenue. Configurons Jarvis."
                setTextColor(Color.WHITE)
                textSize = 22f
            })
            addView(label("Comment tu t'appelles ?"))
            addView(nameField)
            addView(label("Quel âge as-tu ?"))
            addView(ageField)
            addView(label("Quel ton veux-tu donner à Jarvis ?"))
            addView(toneField)
            addView(label("Quelle voix ?"))
            addView(voiceGroup)
            addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 5, 8))
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
            val voice = if (kawai.isChecked) UserProfile.VOICE_KAWAI else UserProfile.VOICE_STANDARD
            UserProfile.save(this@SetupActivity, name.take(40), age, tone, voice)
            openMain()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
