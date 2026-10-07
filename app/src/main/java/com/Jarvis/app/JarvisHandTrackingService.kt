package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.jarvis.app.HandSettings.ClickMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Suit la main via la caméra avant (MediaPipe Hand Landmarker) et déplace un point noir à l'écran.
 * Le clic dépend du geste choisi dans les réglages (pincement pouce+index, pouce+majeur, rester
 * immobile, poing). Les clics et glissés sont exécutés via JarvisAccessibilityService, comme un vrai toucher.
 *
 * Réglages en direct (voir HandSettings) : sensibilité du pointeur, lissage, seuil de clic, aperçu caméra.
 *
 * NOTE : nécessite le fichier de modèle "hand_landmarker.task" dans app/src/main/assets/
 * (téléchargé automatiquement par le workflow GitHub Actions).
 *
 * Implémente LifecycleOwner "à la main" (via LifecycleRegistry) pour éviter de dépendre
 * d'androidx.lifecycle:lifecycle-service — CameraX a seulement besoin d'un LifecycleOwner.
 */
class JarvisHandTrackingService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        var isRunning = false
            private set

        /** Intent pour afficher / cacher le clavier main depuis l'app. */
        const val ACTION_TOGGLE_KEYBOARD = "com.jarvis.app.TOGGLE_KEYBOARD"
        const val EXTRA_SHOW_KEYBOARD = "show_keyboard"

        /** true tant que le clavier flottant est affiché. */
        @Volatile var keyboardVisible = false
            private set

        /** Relâcher le clic demande un écart 1,45× plus grand que l'appuyer : évite les clics qui « clignotent ». */
        private const val RELEASE_FACTOR = 1.45f
        private const val LONG_PRESS_MS = 600L
        private const val DWELL_MS = 1000L
        private const val PREVIEW_INTERVAL_MS = 60L

        // Liaisons entre les 21 points de la main (pour dessiner le squelette dans l'aperçu).
        private val HAND_LINKS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(0, 17)
        )
        private val TIPS = intArrayOf(4, 8, 12, 16, 20)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var dotView: View
    private lateinit var dotParams: WindowManager.LayoutParams
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0

    // ── Réglages (relus en direct) ──
    private var clickMode = ClickMode.PINCH_INDEX
    private var gain = 1.5f
    private var smoothing = 0.4f
    private var pressThreshold = 0.22f
    private var previewOn = false
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> loadSettings() }

    // ── Pointeur ──
    private var pointerX = 0f
    private var pointerY = 0f
    private var hasPointer = false
    private var handLostFrames = 0

    // ── Appui (pincement / poing) ──
    private var isPressed = false
    private var pressStartTime = 0L
    private val pathPoints = mutableListOf<PointF>()

    // ── Clavier main ──
    private var keyboard: HandKeyboard? = null
    private var keyConsumed = false          // le pincement a tapé une touche : pas de clic dessous
    private var sendHoldStart = 0L           // pouce levé : début du maintien
    private var sendCooldownUntil = 0L
    private var vHoldStart = 0L              // signe V : début du maintien (afficher / cacher)
    private var vCooldownUntil = 0L
    private val sweep = ArrayList<Pair<Long, Float>>() // positions récentes du pointeur (balayage main ouverte)
    private var sweepCooldownUntil = 0L

    // ── Rester immobile ──
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellStart = 0L
    private var dwellFired = false

    // ── Aperçu caméra ──
    private var previewView: ImageView? = null
    @Volatile private var lastPreviewFrame: Bitmap? = null
    @Volatile private var lastPreviewFrameTime = 0L
    @Volatile private var frameAspect = 0.75f // largeur / hauteur de l'image analysée
    @Volatile private var previewWanted = false
    private var lastPreviewDraw = 0L
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CA8E8") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelBgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        isRunning = true
        cameraExecutor = Executors.newSingleThreadExecutor()
        readScreenSize()
        startForegroundWithNotification()
        setupDot()
        keyboard = HandKeyboard(this, windowManager, screenWidth, screenHeight, overlayType())
        HandSettings.registerListener(this, prefsListener)
        loadSettings()
        setupHandLandmarker()
        startCamera()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_TOGGLE_KEYBOARD -> toggleKeyboard()
            intent?.getBooleanExtra(EXTRA_SHOW_KEYBOARD, false) == true -> showKeyboard()
        }
        return START_STICKY
    }

    private fun showKeyboard() {
        val kb = keyboard ?: return
        kb.show()
        // Le point du curseur doit rester AU-DESSUS du clavier : on le remet en dernier.
        if (::dotView.isInitialized) {
            runCatching { windowManager.removeView(dotView); windowManager.addView(dotView, dotParams) }
        }
        keyboardVisible = kb.isVisible
        updateNotification("Clavier main : touche un champ de message, puis pince pour taper.")
    }

    private fun hideKeyboard() {
        keyboard?.hide()
        keyboardVisible = false
        updateNotification("Curseur main actif — ${clickMode.action} pour cliquer.")
    }

    private fun toggleKeyboard() {
        if (keyboard?.isVisible == true) hideKeyboard() else showKeyboard()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun readScreenSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManagerDefault().defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun windowManagerDefault(): WindowManager =
        getSystemService(WINDOW_SERVICE) as WindowManager

    // ── Réglages ────────────────────────────────────────────────────────────────

    private fun loadSettings() {
        val newMode = HandSettings.clickMode(this)
        if (newMode != clickMode) {
            cancelPress()
            resetDwell()
        }
        clickMode = newMode
        gain = HandSettings.sensitivity(this) / 100f
        smoothing = HandSettings.smoothing(this) / 100f
        pressThreshold = HandSettings.clickThreshold(this) / 100f
        val wantPreview = HandSettings.preview(this)
        previewWanted = wantPreview
        if (wantPreview != previewOn) {
            previewOn = wantPreview
            setPreviewVisible(wantPreview)
        }
        updateNotification("Curseur main actif — ${clickMode.action} pour cliquer.")
    }

    // ── Pointeur (point noir) ───────────────────────────────────────────────────

    private fun setupDot() {
        windowManager = windowManagerDefault()
        dotView = View(this).apply {
            setBackgroundResource(R.drawable.hand_cursor_dot)
        }
        val sizePx = dp(28)
        dotParams = WindowManager.LayoutParams(
            sizePx, sizePx, overlayType(),
            // NOT_TOUCHABLE + NOT_FOCUSABLE : le point ne doit jamais intercepter
            // les vrais touchers, il ne fait qu'être affiché.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2
            y = screenHeight / 2
        }
        windowManager.addView(dotView, dotParams)
    }

    /** Le point rétrécit quand tu appuies (ou pendant que le délai « immobile » se remplit). */
    private fun moveDot(x: Float, y: Float, scale: Float) {
        val size = (dp(28) * scale).toInt().coerceAtLeast(dp(10))
        dotParams.width = size
        dotParams.height = size
        dotParams.x = (x - size / 2f).toInt().coerceIn(0, screenWidth)
        dotParams.y = (y - size / 2f).toInt().coerceIn(0, screenHeight)
        if (::dotView.isInitialized) {
            runCatching { windowManager.updateViewLayout(dotView, dotParams) }
        }
    }

    // ── Aperçu de la caméra (petite fenêtre en haut à droite) ───────────────────

    private fun setPreviewVisible(on: Boolean) {
        if (on) {
            if (previewView != null) return
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(2), Color.parseColor("#4CA8E8"))
                }
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                alpha = 0.92f
            }
            val lp = WindowManager.LayoutParams(
                dp(112), dp(150), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(8)
                y = dp(72)
            }
            runCatching { windowManager.addView(iv, lp) }.onSuccess { previewView = iv }
        } else {
            previewView?.let { runCatching { windowManager.removeView(it) } }
            previewView = null
        }
    }

    /** Dessine le squelette de la main + l'état du geste sur la dernière image, puis l'affiche. */
    private fun drawPreview(
        frame: Bitmap?,
        hand: List<NormalizedLandmark>?,
        anchor: PointF?,
        pressed: Boolean,
        label: String
    ) {
        val view = previewView ?: return
        if (frame == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewDraw < PREVIEW_INTERVAL_MS) return
        lastPreviewDraw = now

        val out = frame.copy(Bitmap.Config.ARGB_8888, true) ?: return
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        if (hand != null) {
            linePaint.strokeWidth = w * 0.012f
            for (link in HAND_LINKS) {
                val a = hand[link[0]]
                val b = hand[link[1]]
                canvas.drawLine(a.x() * w, a.y() * h, b.x() * w, b.y() * h, linePaint)
            }
            for (i in hand.indices) {
                val r = if (i in TIPS) w * 0.024f else w * 0.013f
                canvas.drawCircle(hand[i].x() * w, hand[i].y() * h, r, jointPaint)
            }
            if (anchor != null) {
                ringPaint.strokeWidth = w * 0.016f
                ringPaint.color = if (pressed) Color.parseColor("#FF3B30") else Color.parseColor("#22C55E")
                canvas.drawCircle(anchor.x * w, anchor.y * h, w * 0.05f, ringPaint)
            }
        }

        labelPaint.textSize = w * 0.085f
        val barH = labelPaint.textSize * 1.7f
        canvas.drawRect(0f, h - barH, w, h, labelBgPaint)
        canvas.drawText(label, w * 0.04f, h - barH * 0.3f, labelPaint)
        view.setImageBitmap(out)
    }

    // ── Modèle de main + caméra ─────────────────────────────────────────────────

    private fun setupHandLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ -> onHandResult(result) }
            .setErrorListener { /* trame ignorée en cas d'erreur ponctuelle */ }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                )
            } catch (e: Exception) {
                updateNotification("Erreur caméra : ${e.message}")
            }
        }, mainExecutorCompat())
    }

    private fun mainExecutorCompat() = androidx.core.content.ContextCompat.getMainExecutor(this)

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            frameAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            // Copie réduite pour l'aperçu (seulement si l'aperçu est affiché, et pas à chaque image).
            val now = SystemClock.uptimeMillis()
            if (previewWanted && now - lastPreviewFrameTime >= PREVIEW_INTERVAL_MS) {
                val pw = 240
                val ph = (pw * bitmap.height / bitmap.width).coerceAtLeast(1)
                lastPreviewFrame = Bitmap.createScaledBitmap(bitmap, pw, ph, true)
                lastPreviewFrameTime = now
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, now)
        } catch (_: Exception) {
            // Trame ignorée si la conversion échoue — la suivante arrivera très vite.
        } finally {
            imageProxy.close()
        }
    }

    /** Convertit l'image caméra en Bitmap, corrigée en rotation et mise en miroir (vue "selfie"). */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val raw = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            // Miroir horizontal : la caméra avant capture "à l'envers" par rapport
            // à ce que l'utilisateur voit de lui-même (comme un miroir).
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }

    // ── Résultat : pointeur + geste de clic ─────────────────────────────────────

    private fun onHandResult(result: HandLandmarkerResult) {
        val frame = lastPreviewFrame
        mainHandler.post { handleResult(result, frame) }
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark, aspect: Float): Float =
        hypot((a.x() - b.x()) * aspect, a.y() - b.y())

    /** Point de la main qui pilote le pointeur : choisi pour ne pas bouger pendant le geste de clic. */
    private fun anchorOf(hand: List<NormalizedLandmark>): PointF = when (clickMode) {
        // Dos de la main (articulation de l'index) : ne bouge pas quand le bout de l'index va vers le pouce.
        ClickMode.PINCH_INDEX -> PointF(hand[5].x(), hand[5].y())
        // Bout de l'index : il ne participe pas au clic, donc il reste immobile.
        ClickMode.PINCH_MIDDLE, ClickMode.DWELL -> PointF(hand[8].x(), hand[8].y())
        // Centre des articulations des 4 doigts : stable quand les doigts se replient.
        ClickMode.FIST -> PointF(
            (hand[5].x() + hand[9].x() + hand[13].x() + hand[17].x()) / 4f,
            (hand[5].y() + hand[9].y() + hand[13].y() + hand[17].y()) / 4f
        )
    }

    /** « Écart » du geste, rapporté à la taille de la main (indépendant de la distance à la caméra). */
    private fun gestureRatio(hand: List<NormalizedLandmark>, aspect: Float, handSize: Float): Float = when (clickMode) {
        ClickMode.PINCH_INDEX -> dist(hand[4], hand[8], aspect) / handSize
        ClickMode.PINCH_MIDDLE -> dist(hand[4], hand[12], aspect) / handSize
        ClickMode.FIST -> {
            // Distance moyenne bout des doigts -> poignet : ~1,7 main ouverte, ~0,9 poing fermé.
            var sum = 0f
            for (tip in intArrayOf(8, 12, 16, 20)) sum += dist(hand[tip], hand[0], aspect)
            ((sum / 4f) / handSize - 0.7f).coerceAtLeast(0f)
        }
        ClickMode.DWELL -> 1f
    }

    private fun handleResult(result: HandLandmarkerResult, frame: Bitmap?) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            handLostFrames++
            if (handLostFrames >= 6) { // main sortie du champ : on lâche sans rien déclencher
                cancelPress()
                resetDwell()
            }
            drawPreview(frame, null, null, false, "pas de main")
            return
        }
        handLostFrames = 0
        val hand = hands[0]
        val aspect = frameAspect
        val handSize = dist(hand[0], hand[9], aspect).coerceAtLeast(0.02f)

        // 1) Position visée sur l'écran : la sensibilité agrandit le mouvement autour du centre de l'image.
        val anchor = anchorOf(hand)
        val targetX = (0.5f + (anchor.x - 0.5f) * gain).coerceIn(0f, 1f) * screenWidth
        val targetY = (0.5f + (anchor.y - 0.5f) * gain).coerceIn(0f, 1f) * screenHeight

        // 2) Lissage adaptatif : les petits tremblements sont amortis, les grands mouvements suivent vite.
        if (!hasPointer) {
            pointerX = targetX
            pointerY = targetY
            hasPointer = true
        } else {
            val d = hypot(targetX - pointerX, targetY - pointerY)
            val base = (1f - smoothing).coerceIn(0.05f, 1f)
            val boost = (d / (screenWidth * 0.15f)).coerceIn(0f, 1f)
            val alpha = base + (1f - base) * boost
            pointerX += (targetX - pointerX) * alpha
            pointerY += (targetY - pointerY) * alpha
        }

        // 3) Geste de clic.
        val now = SystemClock.uptimeMillis()
        var visualScale = 1f
        var label = "pointe"
        if (clickMode == ClickMode.DWELL) {
            val progress = updateDwell(now)
            visualScale = 1f - 0.5f * progress
            label = if (dwellFired) "CLIC" else "pointe ${(progress * 100).toInt()}%"
        } else {
            val ratio = gestureRatio(hand, aspect, handSize)
            val threshold = if (clickMode == ClickMode.FIST) pressThreshold + 0.12f else pressThreshold
            val pressedNow = if (isPressed) ratio < threshold * RELEASE_FACTOR else ratio < threshold
            handlePress(pressedNow, now)
            if (isPressed) visualScale = 0.6f
            label = (if (isPressed) "CLIC " else "pointe ") + String.format("%.2f/%.2f", ratio, threshold)
        }

        moveDot(pointerX, pointerY, visualScale)
        keyboardGestures(hand, aspect, handSize, now)
        drawPreview(frame, hand, anchor, isPressed || dwellFired, label)
    }

    /**
     * Gestes du clavier :
     *  - V (index + majeur) tenu 1,5 s : affiche / cache le clavier ;
     *  - pouce levé tenu 1 s : envoie le message ;
     *  - main ouverte balayée vite vers la gauche : efface une lettre ; vers la droite : espace.
     */
    private fun keyboardGestures(hand: List<NormalizedLandmark>, aspect: Float, handSize: Float, now: Long) {
        val kb = keyboard ?: return
        fun ext(tip: Int, pip: Int) = dist(hand[tip], hand[0], aspect) > dist(hand[pip], hand[0], aspect) * 1.1f
        fun fold(tip: Int, pip: Int) = dist(hand[tip], hand[0], aspect) < dist(hand[pip], hand[0], aspect)
        val idx = ext(8, 6)
        val mid = ext(12, 10)
        val ring = ext(16, 14)
        val pinky = ext(20, 18)

        // V tenu : afficher / cacher
        val vSign = idx && mid && fold(16, 14) && fold(20, 18)
        if (vSign && now >= vCooldownUntil) {
            if (vHoldStart == 0L) vHoldStart = now
            if (now - vHoldStart >= 1500L) {
                vHoldStart = 0L
                vCooldownUntil = now + 2000L
                toggleKeyboard()
                return
            }
        } else if (!vSign) {
            vHoldStart = 0L
        }
        if (!kb.isVisible) return
        kb.setHover(pointerX, pointerY)

        // Pouce levé tenu : envoyer
        val thumbUp = fold(8, 6) && fold(12, 10) && fold(16, 14) && fold(20, 18) &&
            hand[4].y() < hand[5].y() - 0.35f * handSize &&
            dist(hand[4], hand[0], aspect) > dist(hand[2], hand[0], aspect) * 1.15f
        if (!thumbUp) {
            sendHoldStart = 0L
            kb.setSendProgress(0f)
        } else if (now >= sendCooldownUntil) {
            if (sendHoldStart == 0L) sendHoldStart = now
            val p = ((now - sendHoldStart) / 1000f).coerceIn(0f, 1f)
            kb.setSendProgress(p)
            if (p >= 1f) {
                sendHoldStart = 0L
                sendCooldownUntil = now + 2000L
                kb.setSendProgress(0f)
                val ok = JarvisAccessibilityService.instance?.sendFocusedMessage() == true
                kb.flashMessage(if (ok) "Message envoyé" else "Envoi impossible : ouvre une conversation")
            }
        }

        // Balayage main ouverte : gauche = effacer, droite = espace
        val palm = idx && mid && ring && pinky
        if (!palm) { sweep.clear(); return }
        sweep.add(now to pointerX)
        while (sweep.isNotEmpty() && now - sweep.first().first > 300L) sweep.removeAt(0)
        if (now >= sweepCooldownUntil && sweep.size >= 3) {
            val dx = sweep.last().second - sweep.first().second
            if (kotlin.math.abs(dx) > screenWidth * 0.5f) {
                sweepCooldownUntil = now + 700L
                sweep.clear()
                if (dx < 0) kb.backspace() else kb.type(" ")
            }
        }
    }

    private fun handlePress(pressedNow: Boolean, now: Long) {
        when {
            pressedNow && !isPressed -> {
                isPressed = true
                pressStartTime = now
                pathPoints.clear()
                pathPoints.add(PointF(pointerX, pointerY))
                // Clavier visible : le pincement tape la touche visée (et ne clique pas dessous).
                val kb = keyboard
                if (kb != null && kb.isVisible && sendHoldStart == 0L) {
                    val k = kb.keyAt(pointerX, pointerY)
                    if (k != null) { kb.press(k); keyConsumed = true }
                    else if (kb.covers(pointerX, pointerY)) keyConsumed = true
                }
            }
            pressedNow && isPressed -> {
                val last = pathPoints.lastOrNull()
                if (last == null || hypot(pointerX - last.x, pointerY - last.y) > 6f) {
                    pathPoints.add(PointF(pointerX, pointerY))
                }
            }
            !pressedNow && isPressed -> {
                isPressed = false
                if (keyConsumed) { keyConsumed = false; pathPoints.clear(); return }
                val duration = now - pressStartTime
                val first = pathPoints.firstOrNull() ?: PointF(pointerX, pointerY)
                val last = pathPoints.lastOrNull() ?: first
                val moved = hypot(last.x - first.x, last.y - first.y)
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    updateNotification("Active le contrôle d'écran pour que le geste agisse.")
                } else if (moved < dp(14)) {
                    // Pas de vrai déplacement : appui court = tap, appui long = appui long, tous deux à l'endroit
                    // où le geste a COMMENCÉ (c'est là que tu visais, avant que la main bouge).
                    if (duration >= LONG_PRESS_MS) service.swipePath(listOf(first), duration)
                    else service.tapAt(first.x, first.y)
                } else {
                    service.swipePath(pathPoints.toList(), duration.coerceAtLeast(120L))
                }
                pathPoints.clear()
            }
        }
    }

    /** Clic par immobilité. Renvoie la progression 0..1 du délai. */
    private fun updateDwell(now: Long): Float {
        val radius = dp(18).toFloat()
        if (hypot(pointerX - dwellX, pointerY - dwellY) > radius) {
            dwellX = pointerX
            dwellY = pointerY
            dwellStart = now
            dwellFired = false
            return 0f
        }
        if (dwellFired) return 1f
        val progress = ((now - dwellStart).toFloat() / DWELL_MS).coerceIn(0f, 1f)
        if (progress >= 1f) {
            dwellFired = true // il faudra bouger avant le prochain clic
            val kbKey = keyboard?.takeIf { it.isVisible }?.keyAt(dwellX, dwellY)
            val service = JarvisAccessibilityService.instance
            if (kbKey != null) {
                keyboard?.press(kbKey)
            } else if (service == null) {
                updateNotification("Active le contrôle d'écran pour que le geste agisse.")
            } else {
                service.tapAt(dwellX, dwellY)
            }
        }
        return progress
    }

    private fun resetDwell() {
        dwellFired = false
        dwellStart = SystemClock.uptimeMillis()
        dwellX = pointerX
        dwellY = pointerY
    }

    private fun cancelPress() {
        keyConsumed = false
        isPressed = false
        pathPoints.clear()
    }

    // ── Notification ────────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val channelId = "jarvis_hand_tracking"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Jarvis - suivi de la main", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText("Curseur main actif.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(2, notification)
        }
    }

    private fun updateNotification(text: String) {
        val channelId = "jarvis_hand_tracking"
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
        isRunning = false
        previewWanted = false
        HandSettings.unregisterListener(this, prefsListener)
        cameraExecutor.shutdown()
        handLandmarker?.close()
        setPreviewVisible(false)
        keyboard?.hide()
        keyboardVisible = false
        if (::dotView.isInitialized) {
            runCatching { windowManager.removeView(dotView) }
        }
    }
}
