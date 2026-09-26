package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.jarvis.app.ai.CommandExecutor
import com.jarvis.app.ai.GroqClient
import com.jarvis.app.databinding.ActivityMainBinding
import com.jarvis.app.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestMicPermissionIfNeeded()

        voiceManager = VoiceManager(
            context = this,
            onResult = { spokenText -> handleUserCommand(spokenText) },
            onError = { err -> binding.statusText.text = err }
        )

        binding.micButton.setOnClickListener {
            binding.statusText.text = "J'écoute..."
            voiceManager.startListening()
        }

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun handleUserCommand(text: String) {
        binding.statusText.text = "Toi : $text"
        // Appel réseau -> jamais sur le thread principal
        CoroutineScope(Dispatchers.Main).launch {
            val action = withContext(Dispatchers.IO) {
                runCatching { GroqClient.decideAction(text) }
                    .getOrElse { com.jarvis.app.ai.JarvisAction.Speak("Erreur réseau : ${it.message}") }
            }
            val reply = CommandExecutor.execute(this@MainActivity, action)
            binding.statusText.text = "Jarvis : $reply"
            voiceManager.speak(reply)
        }
    }

    private fun requestMicPermissionIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.destroy()
    }
}
