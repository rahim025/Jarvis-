package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.app.ai.CodeClient
import com.jarvis.app.ai.CodeLauncher
import kotlin.concurrent.thread

/** Aperçu d'un site ou d'une application : code à gauche, rendu à droite (tourne le téléphone pour un meilleur confort). */
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "code_id"
        private const val EXTRA_NAME = "code_name"

        fun intent(context: Context, id: String, name: String): Intent =
            Intent(context, PreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NAME, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        val code = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(190, 235, 200))
            setPadding(20, 20, 20, 20)
            text = "Chargement du code…"
        }
        val codeScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 14, 20))
            addView(HorizontalScrollView(this@PreviewActivity).apply { addView(code) })
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.WHITE)
            webViewClient = WebViewClient()
        }
        val panes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(codeScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(View(this@PreviewActivity).apply { setBackgroundColor(Color.rgb(60, 70, 90)) },
                LinearLayout.LayoutParams(3, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(web, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val title = TextView(this).apply {
            text = name.ifBlank { "Projet" }
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val zipButton = Button(this).apply {
            text = "ZIP"
            setOnClickListener { downloadZip(id, name) }
        }
        val closeButton = Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(20, 24, 34))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(zipButton)
            addView(closeButton)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 20))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(panes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        thread(name = "preview-load") {
            try {
                val src = CodeClient.source(id)
                runOnUiThread {
                    code.text = src
                    web.loadDataWithBaseURL(null, src, "text/html", "UTF-8", null)
                }
            } catch (e: Exception) {
                runOnUiThread { code.text = "Erreur : ${e.message}" }
            }
        }
    }

    private fun downloadZip(id: String, name: String) {
        thread(name = "preview-zip") {
            val msg = try {
                val bytes = CodeClient.download(id)
                "ZIP enregistré : " + CodeLauncher.saveZip(this@PreviewActivity, bytes, name)
            } catch (e: Exception) {
                e.message ?: "Erreur pendant le téléchargement."
            }
            runOnUiThread { Toast.makeText(this@PreviewActivity, msg, Toast.LENGTH_LONG).show() }
        }
    }
}
