package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.ai.JarvisConversationController
import com.jarvis.app.databinding.ActivityMainBinding
import com.jarvis.app.voice.VoiceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager
    private lateinit var conversation: JarvisConversationController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.jarvisAvatar.settings.javaScriptEnabled = true
        binding.jarvisAvatar.loadUrl("file:///android_asset/jarvis_avatar.html")

        requestMicPermissionIfNeeded()

        voiceManager = VoiceManager(
            context = this,
            onResult = { spokenText -> conversation.onVoiceResult(spokenText) },
            onError = { err -> conversation.onVoiceError(err) }
        )

        conversation = JarvisConversationController(
            context = this,
            voiceManager = voiceManager,
            onStatus = { status -> binding.statusText.text = status },
            onAvatarState = { state ->
                binding.jarvisAvatar.evaluateJavascript("setJarvisState('$state')", null)
            }
        )

        binding.jarvisAvatar.setOnClickListener {
            if (JarvisForegroundService.isRunning) {
                binding.statusText.text =
                    "Jarvis est déjà actif en arrière-plan (bulle). Utilise-la, ou désactive " +
                    "le mode arrière-plan ci-dessous pour reprendre le micro ici."
            } else {
                conversation.activate()
            }
        }

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.enableBackgroundButton.setOnClickListener {
            if (JarvisForegroundService.isRunning) {
                conversation.stop()
                stopService(Intent(this, JarvisForegroundService::class.java))
                binding.statusText.text = "Jarvis en arrière-plan désactivé."
                updateBackgroundButtonLabel()
            } else {
                enableBackgroundMode()
            }
        }

        binding.enableHandCursorButton.setOnClickListener {
            if (JarvisHandTrackingService.isRunning) {
                stopService(Intent(this, JarvisHandTrackingService::class.java))
                binding.statusText.text = "Curseur main désactivé."
                updateHandCursorButtonLabel()
            } else {
                enableHandCursorMode()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundButtonLabel()
        updateHandCursorButtonLabel()
    }

    override fun onPause() {
        super.onPause()
        // Si l'app passe en arrière-plan sans que le mode bulle soit actif, on coupe la
        // conversation ici : sinon le SpeechRecognizer lié à cette Activity se met à échouer
        // en boucle (Erreur STT: 5) et l'app semble figée au retour.
        if (!JarvisForegroundService.isRunning && ::conversation.isInitialized && conversation.conversationActive) {
            conversation.stop()
            binding.statusText.text =
                "Écoute mise en pause (appli en arrière-plan). Retape sur le micro, " +
                "ou active « Jarvis en arrière-plan » pour continuer sans interruption."
        }
    }

    private fun updateHandCursorButtonLabel() {
        binding.enableHandCursorButton.text =
            if (JarvisHandTrackingService.isRunning) "Désactiver le curseur main"
            else "Activer le curseur main (caméra)"
    }

    /**
     * Curseur main : suit la pointe de l'index via la caméra avant et affiche un point noir
     * qui reproduit ses mouvements ; pincer pouce-index clique ou glisse à cet endroit.
     * Nécessite la permission caméra + l'affichage par-dessus les autres apps.
     */
    private fun enableHandCursorMode() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            binding.statusText.text = "Autorise l'accès à la caméra, puis retape sur le bouton."
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 2)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            binding.statusText.text = "Autorise l'affichage par-dessus les autres apps, puis réessaie."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, JarvisHandTrackingService::class.java))
        binding.statusText.text = "Curseur main actif : pince pouce-index pour cliquer."
        binding.enableHandCursorButton.text = "Désactiver le curseur main"
    }

    private fun updateBackgroundButtonLabel() {
        binding.enableBackgroundButton.text =
            if (JarvisForegroundService.isRunning) "Désactiver Jarvis en arrière-plan"
            else "Activer Jarvis en arrière-plan"
    }

    /**
     * Jarvis en arrière-plan : une bulle flottante, visible même en dehors de l'app,
     * qui permet de lui parler sans rouvrir l'écran principal.
     * Nécessite la permission "Afficher par-dessus les autres applications".
     */
    private fun enableBackgroundMode() {
        if (!Settings.canDrawOverlays(this)) {
            binding.statusText.text = "Autorise l'affichage par-dessus les autres apps, puis réessaie."
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
        binding.statusText.text = "Jarvis tourne en arrière-plan (bulle flottante active)."
        binding.enableBackgroundButton.text = "Désactiver Jarvis en arrière-plan"
    }

    private fun requestMicPermissionIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
