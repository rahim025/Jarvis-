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
import android.webkit.WebView
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

        /** Instance active, utilisée par IncomingCallReceiver pour déclencher le portier
         *  vocal quand le téléphone sonne (rien à faire si le service n'est pas actif :
         *  pas de moteur vocal disponible pour poser la question). */
        private var instance: JarvisForegroundService? = null

        fun onIncomingCall(callerNumber: String?) {
            instance?.handleIncomingCall(callerNumber)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var avatarWebView: WebView
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
        instance = this
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
            onStatus = { updateNotification(it) },
            onAvatarState = { state -> setAvatarState(state) }
        )
    }

    /** Reflète l'état de la conversation sur l'avatar HUD affiché dans la bulle. */
    private fun setAvatarState(state: String) {
        if (::avatarWebView.isInitialized) {
            avatarWebView.evaluateJavascript("setJarvisState('$state')", null)
        }
    }

    /**
     * Appel entrant : on ne décroche JAMAIS à l'aveugle. On demande d'abord à l'utilisateur
     * s'il est disponible, via le même micro/TTS que la conversation habituelle (dérouté
     * temporairement avec resultOverride/errorOverride pour ne pas perturber la conversation).
     *  - "oui" -> on décroche et on te laisse la ligne.
     *  - "non" / pas de réponse claire sous 8s -> on décroche puis on tente de dire à
     *    l'appelant que tu n'es pas disponible (best-effort : Android ne garantit pas
     *    qu'une appli tierce puisse injecter de l'audio dans un appel sur tous les téléphones).
     */
    private fun handleIncomingCall(callerNumber: String?) {
        if (!::voiceManager.isInitialized) return
        val callerLabel = resolveCallerLabel(callerNumber)
        var resolved = false
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun respond(answerText: String) {
            if (resolved) return
            resolved = true
            voiceManager.resultOverride = null
            voiceManager.errorOverride = null
            val available = Regex("oui|dispo|ouais|vas-y|vas y", RegexOption.IGNORE_CASE)
                .containsMatchIn(answerText)
            val telecomManager = getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (available) {
                telecomManager?.acceptRingingCall()
                voiceManager.speak("D'accord, je te passe l'appel.")
            } else {
                telecomManager?.acceptRingingCall()
                IncomingCallCallerNotifier.speakToCaller(
                    this,
                    "Le créateur de Jarvis n'est pas disponible pour le moment. Merci de rappeler plus tard."
                )
            }
        }

        voiceManager.resultOverride = { text -> respond(text) }
        voiceManager.errorOverride = { respond("") } // pas compris = traité comme "pas disponible", par sécurité
        voiceManager.speak("$callerLabel t'appelle. Tu es disponible ?")
        voiceManager.onSpeakDone = {
            voiceManager.startListening()
            timeoutHandler.postDelayed({ respond("") }, 8000)
        }
    }

    /** Cherche un nom de contact pour le numéro entrant ; à défaut, renvoie le numéro
     *  brut, ou "Quelqu'un" si même le numéro est masqué/inconnu. */
    private fun resolveCallerLabel(number: String?): String {
        if (number.isNullOrBlank()) return "Quelqu'un"
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return number

        val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon().appendPath(number).build()
        contentResolver.query(
            uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: number
            }
        }
        return number
    }

    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        avatarWebView = bubbleView.findViewById(R.id.bubbleAvatar)
        avatarWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        avatarWebView.settings.javaScriptEnabled = true
        avatarWebView.loadUrl("file:///android_asset/jarvis_orb.html?compact=1")

        val touchCatcher = bubbleView.findViewById<View>(R.id.bubbleTouchCatcher)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // Accélération matérielle : indispensable pour que l'orbe 3D (WebGL) tourne dans la bulle.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        // Glisser la bulle pour la déplacer ; un simple tap (sans déplacement) parle à Jarvis.
        // Écouté sur touchCatcher (au-dessus de la WebView) : sinon la WebView absorbe le
        // toucher et ni le tap ni le glissé ne remontent jusqu'ici.
        touchCatcher.setOnTouchListener { _, event ->
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
        instance = null
        conversation.stop()
        voiceManager.destroy()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
    }
}
