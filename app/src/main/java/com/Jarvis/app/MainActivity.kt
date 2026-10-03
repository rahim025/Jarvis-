package com.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.ai.JarvisConversationController
import com.jarvis.app.ai.JarvisMemory
import com.jarvis.app.databinding.ActivityMainBinding
import com.jarvis.app.voice.VoiceManager
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager
    private lateinit var conversation: JarvisConversationController

    private var pageReady = false
    private var lastState = "idle"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val web = binding.jarvisUi
        web.setBackgroundColor(Color.parseColor("#050508"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                js("setJarvisState('$lastState')")
                refreshMemoryBadge()
            }
        }
        web.addJavascriptInterface(JarvisBridge(), "Android")
        web.loadUrl("file:///android_asset/jarvis_orb.html")

        requestMicPermissionIfNeeded()

        voiceManager = VoiceManager(
            context = this,
            onResult = { spokenText -> conversation.onVoiceResult(spokenText) },
            onError = { err -> conversation.onVoiceError(err) }
        )

        conversation = JarvisConversationController(
            context = this,
            voiceManager = voiceManager,
            onStatus = { status -> showStatus(status) },
            onAvatarState = { state ->
                lastState = state
                js("setJarvisState('$state')")
            }
        )
    }

    // ── Pont JavaScript <-> Android (appelé par l'interface de l'orbe) ──────────

    inner class JarvisBridge {
        @JavascriptInterface
        fun onMicTap() {
            runOnUiThread {
                if (JarvisForegroundService.isRunning) {
                    showMessage(
                        "Jarvis est déjà actif en arrière-plan (bulle). Utilise-la, ou désactive " +
                            "le mode arrière-plan dans le menu ⚙ pour reprendre le micro ici."
                    )
                } else if (conversation.conversationActive && lastState == "listening") {
                    conversation.stop()
                } else {
                    conversation.activate()
                }
            }
        }

        @JavascriptInterface
        fun onStopTap() {
            runOnUiThread {
                voiceManager.stopSpeaking()
                conversation.stop()
            }
        }

        @JavascriptInterface
        fun openMenu() {
            runOnUiThread { showMenu() }
        }
    }

    private fun js(code: String) {
        if (!pageReady) return
        binding.jarvisUi.post { binding.jarvisUi.evaluateJavascript(code, null) }
    }

    private fun q(text: String): String = JSONObject.quote(text)

    /** Affiche un message de Jarvis dans la bulle de l'interface. */
    private fun showMessage(text: String) {
        js("setJarvisText(${q(text)})")
    }

    /** Dispatche les statuts du contrôleur vers les bonnes zones de l'interface. */
    private fun showStatus(status: String) {
        when {
            status.startsWith("Toi : ") -> {
                js("setUserText(${q(status.removePrefix("Toi : "))})")
                js("setJarvisText('')")
            }
            status.startsWith("Jarvis : ") -> {
                showMessage(status.removePrefix("Jarvis : "))
                refreshMemoryBadge()
            }
            status == "J'écoute..." -> {}
            else -> showMessage(status)
        }
    }

    private fun refreshMemoryBadge() {
        Thread {
            val n = runCatching { JarvisMemory.get(this).turnCount() }.getOrDefault(0)
            runOnUiThread { js("setBadge(${q("mémoire : $n souvenirs")})") }
        }.start()
    }

    // ── Menu ⚙ ──────────────────────────────────────────────────────────────────

    private fun showMenu() {
        val bgLabel = if (JarvisForegroundService.isRunning) "Désactiver Jarvis en arrière-plan"
        else "Activer Jarvis en arrière-plan"
        val handLabel = if (JarvisHandTrackingService.isRunning) "Désactiver le curseur main"
        else "Activer le curseur main (caméra)"
        val items = arrayOf(
            "Activer le contrôle d'écran",
            bgLabel,
            handLabel,
            "Ce que Jarvis sait de moi",
            "Autoriser les réglages (luminosité)",
            "Effacer toute la mémoire"
        )
        AlertDialog.Builder(this)
            .setTitle("Jarvis")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    1 -> toggleBackground()
                    2 -> toggleHandCursor()
                    3 -> showMemorySummary()
                    4 -> startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    )
                    5 -> confirmClearMemory()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showMemorySummary() {
        Thread {
            val text = runCatching { JarvisMemory.get(this).summaryText() }
                .getOrDefault("Mémoire indisponible.")
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Ce que Jarvis sait de moi")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun confirmClearMemory() {
        AlertDialog.Builder(this)
            .setTitle("Effacer la mémoire ?")
            .setMessage("Jarvis oubliera tous les faits et tous les anciens échanges. C'est définitif.")
            .setPositiveButton("Tout effacer") { _, _ ->
                Thread {
                    runCatching { JarvisMemory.get(this).clearAll() }
                    runOnUiThread {
                        showMessage("Mémoire effacée.")
                        refreshMemoryBadge()
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toggleBackground() {
        if (JarvisForegroundService.isRunning) {
            conversation.stop()
            stopService(Intent(this, JarvisForegroundService::class.java))
            showMessage("Jarvis en arrière-plan désactivé.")
        } else {
            enableBackgroundMode()
        }
    }

    private fun toggleHandCursor() {
        if (JarvisHandTrackingService.isRunning) {
            stopService(Intent(this, JarvisHandTrackingService::class.java))
            showMessage("Curseur main désactivé.")
        } else {
            enableHandCursorMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // Si l'app passe en arrière-plan sans que le mode bulle soit actif, on coupe la
        // conversation ici : sinon le SpeechRecognizer lié à cette Activity se met à échouer
        // en boucle (Erreur STT: 5) et l'app semble figée au retour.
        if (!JarvisForegroundService.isRunning && ::conversation.isInitialized && conversation.conversationActive) {
            conversation.stop()
            showMessage(
                "Écoute mise en pause (appli en arrière-plan). Retape sur le micro, " +
                    "ou active « Jarvis en arrière-plan » depuis le menu ⚙."
            )
        }
    }

    /**
     * Curseur main : suit la pointe de l'index via la caméra avant et affiche un point noir
     * qui reproduit ses mouvements ; pincer pouce-index clique ou glisse à cet endroit.
     * Nécessite la permission caméra + l'affichage par-dessus les autres apps.
     */
    private fun enableHandCursorMode() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            showMessage("Autorise l'accès à la caméra, puis réessaie depuis le menu ⚙.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showMessage("Autorise l'affichage par-dessus les autres apps, puis réessaie.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, JarvisHandTrackingService::class.java))
        showMessage("Curseur main actif : pince pouce-index pour cliquer.")
    }

    /**
     * Jarvis en arrière-plan : une bulle flottante, visible même en dehors de l'app,
     * qui permet de lui parler sans rouvrir l'écran principal.
     * Nécessite la permission "Afficher par-dessus les autres applications".
     */
    private fun enableBackgroundMode() {
        if (!Settings.canDrawOverlays(this)) {
            showMessage("Autorise l'affichage par-dessus les autres apps, puis réessaie.")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        // On coupe d'abord la conversation locale : sinon son SpeechRecognizer reste actif
        // en même temps que celui du service (bulle), et les deux se disputent le micro.
        if (::conversation.isInitialized) {
            conversation.stop()
        }
        val serviceIntent = Intent(this, JarvisForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        showMessage("Jarvis tourne en arrière-plan (bulle flottante active).")
    }

    private fun requestMicPermissionIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }
}
