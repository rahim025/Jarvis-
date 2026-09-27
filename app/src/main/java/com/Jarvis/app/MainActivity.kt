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

        requestMicPermissionIfNeeded()

        voiceManager = VoiceManager(
            context = this,
            onResult = { spokenText -> conversation.onVoiceResult(spokenText) },
            onError = { err -> conversation.onVoiceError(err) }
        )

        conversation = JarvisConversationController(
            context = this,
            voiceManager = voiceManager,
            onStatus = { status -> binding.statusText.text = status }
        )

        binding.micButton.setOnClickListener {
            conversation.activate()
        }

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.enableBackgroundButton.setOnClickListener {
            enableBackgroundMode()
        }
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
        val serviceIntent = Intent(this, JarvisForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        binding.statusText.text = "Jarvis tourne en arrière-plan (bulle flottante active)."
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
