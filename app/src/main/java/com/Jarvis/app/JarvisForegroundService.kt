package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.jarvis.app.ai.JarvisConversationController
import com.jarvis.app.voice.VoiceManager

/**
 * Fait tourner Jarvis en dehors de l'app : une bulle flottante par-dessus les autres
 * applications, avec laquelle on peut parler à tout moment (comme le J.A.R.V.I.S. d'Iron Man).
 * Tourne en foreground service (notification permanente) pour ne pas être tué par le système
 * pendant qu'il écoute/parle.
 */
class JarvisForegroundService : Service() {

    companion object {
        /** true tant que le service (et sa bulle) tourne, pour éviter qu'un deuxième
         *  SpeechRecognizer (celui de MainActivity) n'entre en conflit avec le micro. */
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var voiceManager: VoiceManager
    private lateinit var conversation: JarvisConversationController

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundWithNotification()
        setupVoice()
        setupBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupVoice() {
        voiceManager = VoiceManager(
            context = this,
            onResult = { text -> conversation.onVoiceResult(text) },
            onError = { err -> conversation.onVoiceError(err) }
        )
        conversation = JarvisConversationController(
            context = this,
            voiceManager = voiceManager,
            onStatus = { updateNotification(it) }
        )
    }

    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        // Glisser la bulle pour la déplacer ; un simple tap (sans déplacement) parle à Jarvis.
        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX)
                    val dy = (event.rawY - initialTouchY)
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        conversation.activate()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun startForegroundWithNotification() {
        val channelId = "jarvis_background"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis en arrière-plan", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText("Actif — appuie sur la bulle pour lui parler.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String) {
        val channelId = "jarvis_background"
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        conversation.stop()
        voiceManager.destroy()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
    }
}
