package com.jarvis.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.jarvis.app.ai.AutoReplyStore
import com.jarvis.app.ai.ConversationStore

/**
 * Écran « Conversations » : réglages des réponses automatiques + ce que Jarvis retient de chaque contact.
 * Tu peux corriger le résumé, voir les derniers messages, effacer une conversation, ou arrêter de répondre à quelqu'un.
 */
class ConversationsActivity : AppCompatActivity() {

    private var openKey: String? = null
    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    private val cyan = Color.parseColor("#00E5FF")
    private val grey = Color.parseColor("#8899AA")
    private val card = Color.parseColor("#0F1722")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Conversations"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openKey != null) showList() else finish()
            }
        })
        showList()
    }

    override fun onResume() {
        super.onResume()
        if (openKey == null) showList()
    }

    // ── Liste ──────────────────────────────────────────────────────────────────

    private fun showList() {
        openKey = null
        val column = newColumn()
        column.addView(heading("Conversations"))
        column.addView(subtitle("Réponses automatiques et mémoire de chaque contact. Tout reste sur ton téléphone."))

        val listenerOk = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!listenerOk) {
            column.addView(box("Jarvis n'a pas encore accès aux notifications : sans ça, il ne peut pas lire ni répondre aux messages.").also {
                it.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
            column.addView(Button(this).apply {
                text = "Autoriser l'accès aux notifications"
                setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
        }

        column.addView(switchRow("Réponses automatiques", AutoReplyStore.isEnabled(this)) {
            AutoReplyStore.setEnabled(this, it)
        })
        column.addView(switchRow("Répondre à tous mes contacts (hors groupes)", AutoReplyStore.answersAll(this)) {
            AutoReplyStore.setAnswerAll(this, it)
            if (it) Toast.makeText(this, "Jarvis répondra à tout le monde en privé.", Toast.LENGTH_LONG).show()
        })
        column.addView(switchRow("WhatsApp", "whatsapp" in AutoReplyStore.apps(this)) { AutoReplyStore.setApp(this, "whatsapp", it) })
        column.addView(switchRow("Messenger / Facebook", "messenger" in AutoReplyStore.apps(this)) { AutoReplyStore.setApp(this, "messenger", it) })

        column.addView(sectionTitle("Conversations retenues"))
        val store = ConversationStore.get(this)
        val list = runCatching { store.contactsList() }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            column.addView(subtitle("Rien pour l'instant. Dis par exemple : « Réponds à ma place à Crépin sur WhatsApp »."))
        }
        for (c in list) {
            val on = AutoReplyStore.allows(this, c.display) || AutoReplyStore.contacts(this).any { it == c.key }
            val summaryLine = c.summary.lineSequence().firstOrNull { it.isNotBlank() }?.take(90).orEmpty()
            val ago = DateUtils.getRelativeTimeSpanString(c.lastTs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            column.addView(
                box("${c.display}${if (on) "  ✅" else ""}\n${c.count} messages · $ago" +
                    if (summaryLine.isNotBlank()) "\n$summaryLine" else "")
                    .also { it.setOnClickListener { _ -> showDetail(c.key) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, px(6)) }
            )
        }

        column.addView(Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })
        show(column)
    }

    // ── Détail d'un contact ────────────────────────────────────────────────────

    private fun showDetail(key: String) {
        openKey = key
        val store = ConversationStore.get(this)
        val name = store.displayName(key)
        val column = newColumn()
        column.addView(heading(name))
        column.addView(subtitle("Ce que Jarvis retient de cette conversation. Tu peux corriger le résumé."))

        val summary = EditText(this).apply {
            setText(store.summary(key))
            hint = "(aucun résumé pour le moment)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            setTextColor(Color.WHITE)
            setHintTextColor(grey)
            textSize = 14f
            setPadding(px(12), px(12), px(12), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(10).toFloat(); setColor(card); setStroke(px(1), Color.parseColor("#1E3A4A"))
            }
        }
        column.addView(summary)
        column.addView(Button(this).apply {
            text = "Enregistrer le résumé"
            setOnClickListener {
                store.saveNotes(key, summary.text.toString(), store.summarizedUpTo(key))
                Toast.makeText(context, "Résumé enregistré.", Toast.LENGTH_SHORT).show()
            }
        })

        val replying = AutoReplyStore.contacts(this).any { it == key }
        column.addView(Button(this).apply {
            text = if (replying) "Arrêter de répondre à $name" else "Répondre à $name à ma place"
            setOnClickListener {
                if (replying) AutoReplyStore.removeContact(context, name) else {
                    AutoReplyStore.addContact(context, name)
                    AutoReplyStore.setEnabled(context, true)
                }
                showDetail(key)
            }
        })

        column.addView(sectionTitle("Derniers messages"))
        val now = System.currentTimeMillis()
        val msgs = store.recent(key, 30)
        if (msgs.isEmpty()) column.addView(subtitle("Aucun message."))
        for (m in msgs.asReversed()) {
            val ago = DateUtils.getRelativeTimeSpanString(m.ts, now, DateUtils.MINUTE_IN_MILLIS)
            column.addView(TextView(this).apply {
                text = "${if (m.fromMe) "Moi" else name} · $ago\n${m.text}"
                textSize = 14f
                setTextColor(if (m.fromMe) cyan else Color.parseColor("#E6F7FF"))
                setPadding(px(12), px(8), px(12), px(8))
            })
        }

        column.addView(Button(this).apply {
            text = "Effacer cette conversation"
            setTextColor(Color.parseColor("#FF6B6B"))
            setOnClickListener {
                AlertDialog.Builder(context, R.style.JarvisDialog)
                    .setTitle("Effacer ?")
                    .setMessage("Jarvis oubliera tout ce qu'il retient de $name (messages et résumé).")
                    .setPositiveButton("Effacer") { _, _ -> store.clear(key); showList() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })
        column.addView(Button(this).apply {
            text = "Retour"
            setOnClickListener { showList() }
        })
        show(column)
    }

    // ── Petits éléments d'interface ────────────────────────────────────────────

    private fun newColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(16), px(16), px(32))
    }

    private fun show(column: LinearLayout) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050508"))
            addView(column)
        })
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(cyan)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(grey); setPadding(0, px(4), 0, px(8))
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, px(20), 0, px(8))
    }

    private fun box(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(Color.parseColor("#E6F7FF"))
        setPadding(px(14), px(12), px(14), px(12))
        background = GradientDrawable().apply {
            cornerRadius = px(10).toFloat(); setColor(card); setStroke(px(1), Color.parseColor("#1E3A4A"))
        }
        isClickable = true
        isFocusable = true
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, px(8), 0, px(8))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }
}
