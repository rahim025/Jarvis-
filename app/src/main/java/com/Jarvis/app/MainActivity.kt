package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.jarvis.app.ai.BackendClient
import com.jarvis.app.ai.CommandExecutor
import com.jarvis.app.ai.JarvisAction
import com.jarvis.app.databinding.ActivityMainBinding
import com.jarvis.app.voice.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager

    /** true tant que la conversation continue est active (pas besoin de retaper sur le micro). */
    private var conversationActive = false

    // Phrases (normalisées : minuscules, sans accents) qui mettent fin à la conversation continue.
    private val stopPhrases = listOf(
        "c'est bon pour le moment",
        "c'est bon pour l'instant",
        "ca suffit pour le moment",
        "ca suffira pour le moment",
        "ca suffit",
        "stop jarvis",
        "arrete jarvis",
        "silence jarvis",
        "c'est tout pour le moment",
        "merci ca suffit"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestMicPermissionIfNeeded()

        voiceManager = VoiceManager(
            context = this,
            onResult = { spokenText -> handleUserCommand(spokenText) },
            onError = { err -> handleVoiceError(err) }
        )

        // Une fois que Jarvis a fini de parler, on relance l'écoute automatiquement
        // si la conversation continue est toujours active — plus besoin de retaper le micro.
        voiceManager.onSpeakDone = {
            if (conversationActive) {
                startListeningRound()
            }
        }

        binding.micButton.setOnClickListener {
            if (!conversationActive) {
                // Premier appui : Jarvis salue selon l'heure du Bénin, puis se met à écouter.
                conversationActive = true
                binding.statusText.text = "Jarvis : ${greetingMessage()}"
                voiceManager.speak(greetingMessage())
                // L'écoute démarre automatiquement via onSpeakDone une fois la salutation terminée.
            } else {
                // Conversation déjà active : un appui manuel relance juste une écoute immédiate.
                startListeningRound()
            }
        }

        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startListeningRound() {
        binding.statusText.text = "J'écoute..."
        voiceManager.startListening()
    }

    /** "Bonjour Monsieur" ou "Bonsoir Monsieur" selon l'heure au Bénin (Africa/Porto-Novo, UTC+1). */
    private fun greetingMessage(): String {
        val beninZone = TimeZone.getTimeZone("Africa/Porto-Novo")
        val hour = Calendar.getInstance(beninZone).get(Calendar.HOUR_OF_DAY)
        return if (hour in 5..17) "Bonjour Monsieur." else "Bonsoir Monsieur."
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.FRENCH)
            .replace(Regex("[éèêë]"), "e")
            .replace(Regex("[àâ]"), "a")
            .replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o")
            .replace(Regex("[ûùü]"), "u")
            .replace(Regex("[^a-z0-9' ]"), "")
            .trim()
    }

    private fun isStopPhrase(text: String): Boolean {
        val normalized = normalize(text)
        return stopPhrases.any { normalized.contains(it) }
    }

    private fun handleVoiceError(err: String) {
        binding.statusText.text = err
        // On ne relance pas automatiquement si le problème vient des permissions
        // (ça bouclerait indéfiniment sur la même erreur).
        if (conversationActive && err != "Erreur STT: 9") {
            binding.root.postDelayed({
                if (conversationActive) startListeningRound()
            }, 900)
        }
    }

    private fun handleUserCommand(text: String) {
        binding.statusText.text = "Toi : $text"

        if (isStopPhrase(text)) {
            conversationActive = false
            val bye = "Très bien Monsieur, je reste disponible dès que vous avez besoin de moi."
            binding.statusText.text = "Jarvis : $bye"
            voiceManager.speak(bye)
            return
        }

        // Appel réseau -> jamais sur le thread principal
        CoroutineScope(Dispatchers.Main).launch {
            val action = withContext(Dispatchers.IO) {
                runCatching { BackendClient.decideAction(text) }
                    .getOrElse { JarvisAction.Speak("Erreur réseau : ${it.message}") }
            }
            val reply = CommandExecutor.execute(this@MainActivity, action)
            binding.statusText.text = "Jarvis : $reply"
            voiceManager.speak(reply)
            // L'écoute repart automatiquement via onSpeakDone si la conversation est toujours active.
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
