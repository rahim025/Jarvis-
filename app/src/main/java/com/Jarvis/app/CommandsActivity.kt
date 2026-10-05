package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.CommandBus
import com.jarvis.app.ai.CommandView
import org.json.JSONObject

/**
 * Liste claire de toutes les commandes de Jarvis, regroupées par catégories.
 * Chaque commande est directement utilisable : un appui l'exécute comme si tu l'avais dite.
 * Si elle contient un champ à remplir, [comme ceci], Jarvis te demande la valeur d'abord.
 */
class CommandsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_VIEW = "commands_view_json"

        fun intent(context: Context, view: CommandView): Intent =
            Intent(context, CommandsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_VIEW, view.toJson().toString())
    }

    private val placeholder = Regex("\\[([^\\]]+)\\]")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = runCatching {
            CommandView.fromJson(JSONObject(intent.getStringExtra(EXTRA_VIEW) ?: "{}"))
        }.getOrElse { CommandView("", emptyList()) }

        title = "Commandes de Jarvis"
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(32))
        }

        column.addView(TextView(this).apply {
            text = if (view.title == "Toutes les commandes" || view.title.isBlank()) "Toutes les commandes"
            else "Commandes : ${view.title}"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        })
        column.addView(TextView(this).apply {
            text = "Touche une commande pour la lancer."
            textSize = 14f
            setTextColor(Color.parseColor("#8899AA"))
            setPadding(0, px(4), 0, px(8))
        })

        for (section in view.sections) {
            column.addView(TextView(this).apply {
                text = section.title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, px(20), 0, px(8))
            })
            for (command in section.commands) {
                column.addView(TextView(this).apply {
                    text = command
                    textSize = 15f
                    setTextColor(Color.parseColor("#E6F7FF"))
                    setPadding(px(14), px(12), px(14), px(12))
                    background = GradientDrawable().apply {
                        cornerRadius = px(10).toFloat()
                        setColor(Color.parseColor("#0F1722"))
                        setStroke(px(1), Color.parseColor("#1E3A4A"))
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { runCommand(command) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, px(6)) })
            }
        }

        column.addView(Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(20) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050508"))
            addView(column)
        })
    }

    /** Remplit les [champs] un par un (une petite boîte de dialogue chacun), puis lance la commande. */
    private fun runCommand(template: String) {
        val match = placeholder.find(template)
        if (match == null) {
            launch(template)
            return
        }
        val input = EditText(this).apply {
            hint = match.groupValues[1]
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(match.groupValues[1].replaceFirstChar { it.uppercase() })
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) runCommand(template.replaceFirst(match.value, value))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launch(command: String) {
        val runner = CommandBus.runner
        if (runner == null) {
            Toast.makeText(this, "Dis-le à voix haute : $command", Toast.LENGTH_LONG).show()
            return
        }
        runner(command)
        finish()
    }
}
