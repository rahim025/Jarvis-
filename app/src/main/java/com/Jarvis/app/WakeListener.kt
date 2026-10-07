package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Détecte le réveil : un double clap (AudioRecord, sans réseau) ou le mot « Jarvis »
 * (SpeechRecognizer relancé en boucle). À utiliser depuis le thread principal.
 */
class WakeListener(private val context: Context, private val onWake: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var clapThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null

    fun start(mode: String) {
        stop()
        running = true
        when (mode) {
            WakeSettings.CLAP -> startClap()
            WakeSettings.WORD -> main.post { listenWord() }
            else -> running = false
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        clapThread = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun fire() {
        if (!running) return
        running = false
        main.post { onWake() }
    }

    // ── Double clap ─────────────────────────────────────────────────────────────

    private fun startClap() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { running = false; return }
        val t = Thread {
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096) * 2
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@Thread
                rec.startRecording()
                val buf = ShortArray(320) // 20 ms
                var noise = 800.0
                var lastClap = 0L
                var prevClap = 0L
                while (running && clapThread === Thread.currentThread()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0
                    for (i in 0 until n) { val v = abs(buf[i].toInt()); if (v > peak) peak = v }
                    val now = SystemClock.elapsedRealtime()
                    val threshold = max(noise * 5.0, 5000.0)
                    if (peak > threshold) {
                        if (now - lastClap > 180) {
                            lastClap = now
                            if (prevClap != 0L && now - prevClap in 200..900) { fire(); return@Thread }
                            prevClap = now
                        }
                    } else {
                        noise = noise * 0.99 + peak * 0.01
                    }
                    if (prevClap != 0L && now - prevClap > 900) prevClap = 0L
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec?.stop() }
                runCatching { rec?.release() }
            }
        }
        clapThread = t
        t.start()
    }

    // ── Mot « Jarvis » ──────────────────────────────────────────────────────────

    private fun normalize(s: String) =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun matches(b: Bundle?): Boolean {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        return list.any { val n = normalize(it); n.contains("jarvis") || n.contains("jarvice") || n.contains("djarvis") }
    }

    private fun listenWord() {
        if (!running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { running = false; return }
        recognizer?.let { runCatching { it.destroy() } }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) { if (matches(results)) fire() else restart(300) }
            override fun onPartialResults(partialResults: Bundle?) { if (matches(partialResults)) fire() }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> running = false
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(1500)
                    else -> restart(400)
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(i) }.onFailure { restart(1500) }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.postDelayed({ listenWord() }, delayMs)
    }
}
