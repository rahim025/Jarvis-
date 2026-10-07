package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        /** Action de la notification « Écrire » : ouvre le clavier visuel par-dessus l'app en cours. */
        const val ACTION_OPEN_TEXT = "com.jarvis.app.OPEN_TEXT"

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

    // Clavier visuel (saisie au lieu de la voix) et carte de réponse écrite, par-dessus les autres apps.
    private val uiHandler = Handler(Looper.getMainLooper())
    private var textPanel: View? = null
    private var replyCard: View? = null
    private val hideReplyRunnable = Runnable { hideReplyCard() }

    // Réveil (double clap / mot « Jarvis ») : écoute seulement quand Jarvis est au repos
    // (le micro ne peut pas servir à deux écoutes en même temps).
    private lateinit var wake: WakeListener
    private var wakeActiveMode: String? = null
    private val wakeTick = object : Runnable {
        override fun run() {
            val mode = WakeSettings.mode(this@JarvisForegroundService)
            val busy = conversation.conversationActive || MainActivity.visible || com.jarvis.app.ai.TaskRunner.running
            if (mode == WakeSettings.OFF || busy) {
                if (wakeActiveMode != null) { wake.stop(); wakeActiveMode = null }
            } else if (wakeActiveMode != mode) {
                wake.start(mode); wakeActiveMode = mode
            }
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun onWakeTriggered() {
        wake.stop()
        wakeActiveMode = null
        if (WakeSettings.openApp(this)) {
            // Permis depuis l'arrière-plan grâce à la bulle (autorisation « par-dessus les autres apps »).
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
                })
            }.onFailure { conversation.activate() }
        } else {
            conversation.activate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        startForegroundWithNotification()
        setupVoice()
        setupBubble()
        wake = WakeListener(this) { onWakeTriggered() }
        uiHandler.post(wakeTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPEN_TEXT) showTextPanel()
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
            onAvatarState = { state -> setAvatarState(state) },
            onTextReply = { reply -> showReplyCard(reply) }
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
                        // Appui long : clavier visuel. Simple tap : on parle à Jarvis.
                        if (event.eventTime - event.downTime >= 450) showTextPanel()
                        else conversation.activate()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val writeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisForegroundService::class.java).setAction(ACTION_OPEN_TEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "jarvis_background")
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Écrire", writeIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "jarvis_background", "Jarvis en arrière-plan", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = buildNotification(
            "Actif — tape sur la bulle pour parler, maintiens-la (ou « Écrire ») pour taper."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    // ── Clavier visuel + vision, par-dessus l'app en cours ───────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun panelBackground(radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F2080C18"))
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), Color.parseColor("#664CA8E8"))
    }

    /** Barre de saisie en haut de l'écran (le clavier Android s'ouvre en bas, donc rien ne se chevauche). */
    private fun showTextPanel() {
        if (textPanel != null) return
        hideReplyCard()
        conversation.stop() // on libère le micro : l'utilisateur écrit

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = panelBackground(28)
        }

        val input = EditText(this).apply {
            hint = "Écris à Jarvis..."
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // 👁 Vision : Jarvis regarde l'écran pour répondre.
        val eye = TextView(this).apply {
            text = "\uD83D\uDC41"
            textSize = 20f
            gravity = Gravity.CENTER
            alpha = if (conversation.visionMode) 1f else 0.35f
        }
        eye.setOnClickListener {
            val on = !conversation.visionMode
            if (on && JarvisAccessibilityService.instance == null) {
                Toast.makeText(
                    this, "Active d'abord le contrôle d'écran (paramètres d'accessibilité).", Toast.LENGTH_LONG
                ).show()
            } else {
                conversation.visionMode = on
                eye.alpha = if (on) 1f else 0.35f
                Toast.makeText(this, if (on) "Vision activée" else "Vision désactivée", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(eye, LinearLayout.LayoutParams(dp(44), dp(44)))

        val send = TextView(this).apply {
            text = "\u27A4"
            setTextColor(Color.parseColor("#4CA8E8"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        row.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))

        val close = TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 18f
            gravity = Gravity.CENTER
        }
        row.addView(close, LinearLayout.LayoutParams(dp(40), dp(44)))

        fun submit() {
            val message = input.text.toString().trim()
            hideTextPanel()
            if (message.isNotEmpty()) {
                // Petit délai : le temps que le clavier se ferme et que l'app en dessous reprenne le focus,
                // sinon le contrôle d'écran et la capture verraient la barre de saisie au lieu de l'app.
                uiHandler.postDelayed({ conversation.onTextInput(message) }, 450)
            }
        }
        send.setOnClickListener { submit() }
        close.setOnClickListener { hideTextPanel() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }

        // Fenêtre focusable (pas de FLAG_NOT_FOCUSABLE) : c'est ce qui permet au clavier de s'ouvrir.
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        windowManager.addView(row, lp)
        textPanel = row

        input.post {
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideTextPanel() {
        val panel = textPanel ?: return
        textPanel = null
        runCatching {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(panel.windowToken, 0)
            windowManager.removeView(panel)
        }
    }

    /** Réponse écrite (question tapée) : carte en haut de l'écran, non tactile pour ne pas gêner l'app dessous. */
    private fun showReplyCard(message: String) {
        hideReplyCard()
        val card = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = panelBackground(18)
        }
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        runCatching { windowManager.addView(card, lp) }.onSuccess { replyCard = card }
        uiHandler.postDelayed(hideReplyRunnable, (5000L + message.length * 50L).coerceAtMost(25000L))
    }

    private fun hideReplyCard() {
        uiHandler.removeCallbacks(hideReplyRunnable)
        val card = replyCard ?: return
        replyCard = null
        runCatching { windowManager.removeView(card) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        uiHandler.removeCallbacks(wakeTick)
        if (::wake.isInitialized) wake.stop()
        conversation.stop()
        voiceManager.destroy()
        hideTextPanel()
        hideReplyCard()
        if (::bubbleView.isInitialized) {
            windowManager.removeView(bubbleView)
        }
    }
}
