package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Volant virtuel : la caméra avant regarde tes deux mains, l'angle de la ligne qui les relie
 * donne l'angle du volant. Selon cet angle, Jarvis maintient (ou pulse) un appui sur le bouton
 * de direction GAUCHE ou DROITE du jeu, via JarvisAccessibilityService (vrais touchers).
 *
 *  - Les deux mains doivent être visibles : sinon, plus aucun appui (la voiture va tout droit).
 *  - Le « point mort » est pris automatiquement chaque fois que tes deux mains réapparaissent.
 *  - Un petit volant s'affiche en haut de l'écran et tourne comme le tien (rouge = mains perdues).
 *
 * Nécessite le modèle "hand_landmarker.task" dans assets (déjà utilisé par le curseur main).
 */
class JarvisWheelService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    companion object {
        var isRunning = false
            private set

        const val ACTION_CALIBRATE = "com.jarvis.app.WHEEL_CALIBRATE"

        private const val CHANNEL = "jarvis_wheel"
        private const val SEG_MS = 120L          // durée d'un tronçon d'appui maintenu
        private const val PERIOD_MS = 360L       // période du « pulsé » pour les petits braquages
        private const val HOLD_AT = 0.7f         // au-delà de 70 % de braquage : appui continu
        private const val DEADZONE_DEG = 5f
        private const val ACQUIRE_FRAMES = 12    // images stables pour fixer le point mort
        private const val LOST_FRAMES = 4
        private const val WATCHDOG_MS = 400L
        private const val PREVIEW_INTERVAL_MS = 60L

        // Liaisons entre les 21 points de la main (squelette dessiné dans l'aperçu).
        private val HAND_LINKS = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(0, 17)
        )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private var analysis: ImageAnalysis? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wheelView: WheelView? = null
    private var calibView: View? = null
    private val injector = SteeringInjector()

    @Volatile private var frameAspect = 0.75f

    // ── Aperçu caméra (fenêtre en haut à gauche) ──
    private var previewView: ImageView? = null
    private var previewLp: WindowManager.LayoutParams? = null
    @Volatile private var lastPreviewFrame: Bitmap? = null
    @Volatile private var lastPreviewFrameTime = 0L
    private var lastPreviewDraw = 0L
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CA8E8") }
    private val wheelLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val labelBgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    // ── État du volant ──
    private var acquired = false
    private var acquireFrames = 0
    private var acquireSum = 0f
    private var neutral = 0f
    private var angleSm = 0f
    private var lostFrames = 0

    private val watchdog = Runnable { onHandsLost(force = true) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // L'écran a tourné (ex. passage en paysage pour le jeu) : l'image caméra doit suivre.
            analysis?.targetRotation = currentRotation()
        }
    }

    // ── Cycle de vie ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        startForegroundWithNotification("Volant actif — tiens ton volant imaginaire avec les deux mains.")
        setupWheelOverlay()
        setupPreview()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)
        setupHandLandmarker()
        startCamera()
        Toast.makeText(this, "Volant actif : l'aperçu caméra s'affiche en haut de l'écran.", Toast.LENGTH_SHORT).show()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        if (JarvisAccessibilityService.instance == null) {
            updateNotification("Active le contrôle d'écran (Accessibilité) pour que le volant agisse.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CALIBRATE) scheduleCalibration()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        injector.release()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        cameraExecutor.shutdown()
        handLandmarker?.close()
        wheelView?.let { runCatching { windowManager.removeView(it) } }
        previewView?.let { runCatching { windowManager.removeView(it) } }
        calibView?.let { runCatching { windowManager.removeView(it) } }
    }

    // ── Utilitaires ─────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay.rotation

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return m
    }

    // ── Petit volant affiché à l'écran ──────────────────────────────────────────

    private class WheelView(ctx: Context) : View(ctx) {
        var angle = 0f
        var steer = 0f
        var tracking = false

        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bar = RectF()

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = width / 2f
            val r = width * 0.40f
            val color = if (tracking) Color.parseColor("#4CA8E8") else Color.parseColor("#EF4444")

            c.save()
            c.rotate(angle, cx, cy)
            rim.color = color
            rim.strokeWidth = r * 0.18f
            c.drawCircle(cx, cy, r, rim)
            rim.strokeWidth = r * 0.12f
            c.drawLine(cx, cy, cx - r, cy, rim)   // branche gauche
            c.drawLine(cx, cy, cx + r, cy, rim)   // branche droite
            c.drawLine(cx, cy, cx, cy + r, rim)   // branche basse
            fill.color = Color.WHITE
            c.drawRect(cx - r * 0.08f, cy - r * 1.12f, cx + r * 0.08f, cy - r * 0.86f, fill) // repère du haut
            c.restore()

            // Jauge de direction sous le volant : se remplit vers la gauche ou la droite.
            val by = width + dpPx(6f)
            val bh = dpPx(6f)
            fill.color = Color.argb(90, 255, 255, 255)
            bar.set(width * 0.1f, by, width * 0.9f, by + bh)
            c.drawRoundRect(bar, bh, bh, fill)
            fill.color = color
            val half = width * 0.4f
            if (steer >= 0) bar.set(cx, by, cx + half * steer, by + bh)
            else bar.set(cx + half * steer, by, cx, by + bh)
            c.drawRect(bar, fill)
        }

        private fun dpPx(v: Float) = v * resources.displayMetrics.density

        override fun onMeasure(w: Int, h: Int) {
            val s = (resources.displayMetrics.density * 96).toInt()
            setMeasuredDimension(s, s + (resources.displayMetrics.density * 14).toInt())
        }
    }

    private fun setupWheelOverlay() {
        val v = WheelView(this).apply { alpha = 0.9f }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            // Jamais de toucher intercepté : le jeu reçoit tout.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        runCatching { windowManager.addView(v, lp) }.onSuccess { wheelView = v }
    }

    // ── Aperçu caméra : ce que Jarvis voit de tes mains ─────────────────────────

    private fun setupPreview() {
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
            dp(220), dp(165), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(10)
        }
        runCatching { windowManager.addView(iv, lp) }
            .onSuccess { previewView = iv; previewLp = lp }
            .onFailure { updateNotification("Aperçu impossible : autorise l'affichage par-dessus les autres apps.") }
    }

    /** Dessine sur l'image : squelette des mains, ligne du volant entre les deux paumes, et l'état. */
    private fun drawPreview(frame: Bitmap?, hands: List<List<NormalizedLandmark>>, label: String, ok: Boolean) {
        val view = previewView ?: return
        if (frame == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewDraw < PREVIEW_INTERVAL_MS) return
        lastPreviewDraw = now

        val out = frame.copy(Bitmap.Config.ARGB_8888, true) ?: return
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        // La fenêtre épouse le format de l'image (portrait ou paysage).
        previewLp?.let { lp ->
            val ph = (dp(220) * h / w).toInt()
            if (abs(lp.height - ph) > dp(2)) {
                lp.height = ph
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }

        linePaint.strokeWidth = w * 0.012f
        for (hand in hands) {
            for (link in HAND_LINKS) {
                val a = hand[link[0]]
                val b = hand[link[1]]
                canvas.drawLine(a.x() * w, a.y() * h, b.x() * w, b.y() * h, linePaint)
            }
            for (i in hand.indices) {
                canvas.drawCircle(hand[i].x() * w, hand[i].y() * h, w * 0.013f, jointPaint)
            }
        }
        if (hands.size >= 2) {
            val palms = hands.map { hd -> PointF((hd[0].x() + hd[9].x()) / 2f * w, (hd[0].y() + hd[9].y()) / 2f * h) }
                .sortedBy { it.x }
            wheelLinePaint.color = if (ok) Color.parseColor("#22C55E") else Color.parseColor("#F59E0B")
            wheelLinePaint.strokeWidth = w * 0.03f
            canvas.drawLine(palms[0].x, palms[0].y, palms[1].x, palms[1].y, wheelLinePaint)
            for (pt in palms) canvas.drawCircle(pt.x, pt.y, w * 0.035f, wheelLinePaint.apply { style = Paint.Style.FILL })
            wheelLinePaint.style = Paint.Style.STROKE
        }

        labelPaint.textSize = w * 0.075f
        val barH = labelPaint.textSize * 1.7f
        canvas.drawRect(0f, h - barH, w, h, labelBgPaint)
        canvas.drawText(label, w * 0.04f, h - barH * 0.3f, labelPaint)
        view.setImageBitmap(out)
    }

    // ── Modèle de main + caméra ─────────────────────────────────────────────────

    private fun setupHandLandmarker() {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setResultListener { result, _ -> mainHandler.post { handleResult(result) } }
            .setErrorListener { /* trame ignorée */ }
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val a = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(currentRotation())
                .build()
            a.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
            analysis = a
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            } catch (e: Exception) {
                updateNotification("Erreur caméra : ${e.message}")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val raw = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(proxy.planes[0].buffer)
            val m = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f) // vue « selfie » : ta main gauche est à gauche de l'image
            }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            frameAspect = bmp.width.toFloat() / bmp.height.toFloat()
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastPreviewFrameTime >= PREVIEW_INTERVAL_MS) {
                val pw = 240
                val ph = (pw * bmp.height / bmp.width).coerceAtLeast(1)
                lastPreviewFrame = Bitmap.createScaledBitmap(bmp, pw, ph, true)
                lastPreviewFrameTime = nowMs
            }
            handLandmarker?.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    // ── Résultat : angle du volant → direction ──────────────────────────────────

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        val frame = lastPreviewFrame
        if (hands.size < 2) {
            onHandsLost(force = false)
            drawPreview(frame, hands, if (hands.isEmpty()) "pas de mains" else "montre les 2 mains", false)
            return
        }

        val aspect = frameAspect
        // Centre de la paume (poignet + base du majeur) de chaque main, de gauche à droite dans l'image.
        val pts = hands.map { h ->
            PointF((h[0].x() + h[9].x()) / 2f * aspect, (h[0].y() + h[9].y()) / 2f)
        }.sortedBy { it.x }
        val dx = pts[1].x - pts[0].x
        val dy = pts[1].y - pts[0].y
        if (hypot(dx, dy) < 0.12f) { // mains trop proches : mesure instable
            onHandsLost(force = false)
            drawPreview(frame, hands, "mains trop proches", false)
            return
        }

        lostFrames = 0
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_MS)

        // Angle de la ligne main gauche → main droite. Main droite plus basse = sens horaire = braquer à droite.
        val raw = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()

        if (!acquired) {
            acquireSum += raw
            acquireFrames++
            if (acquireFrames >= ACQUIRE_FRAMES) {
                neutral = acquireSum / acquireFrames
                acquired = true
                angleSm = 0f
            }
            showWheel(0f, 0f, true)
            injector.setDesired(0)
            drawPreview(frame, hands, "point mort ${acquireFrames * 100 / ACQUIRE_FRAMES}%  tiens droit", false)
            return
        }

        angleSm += ((raw - neutral) - angleSm) * 0.5f
        val lock = WheelSettings.lockDeg(this).toFloat()
        val a = angleSm
        val steer = if (abs(a) < DEADZONE_DEG) 0f
        else (Math.signum(a) * (abs(a) - DEADZONE_DEG) / (lock - DEADZONE_DEG)).coerceIn(-1f, 1f)

        showWheel(a, steer, true)
        applySteer(steer, SystemClock.uptimeMillis())

        val dir = if (steer < -0.02f) "GAUCHE" else if (steer > 0.02f) "DROITE" else "tout droit"
        drawPreview(frame, hands, "${a.toInt()}°  $dir ${(abs(steer) * 100).toInt()}%", true)
    }

    private fun onHandsLost(force: Boolean) {
        lostFrames++
        if (force || lostFrames >= LOST_FRAMES) {
            acquired = false
            acquireFrames = 0
            acquireSum = 0f
            angleSm = 0f
            injector.setDesired(0)
            showWheel(0f, 0f, false)
        }
    }

    private fun showWheel(angle: Float, steer: Float, tracking: Boolean) {
        wheelView?.let {
            it.angle = angle
            it.steer = steer
            it.tracking = tracking
            it.invalidate()
        }
    }

    /** Petits braquages = appuis pulsés (proportionnels) ; au-delà de 70 % = appui continu. */
    private fun applySteer(steer: Float, now: Long) {
        val mag = abs(steer)
        if (mag < 0.02f) { injector.setDesired(0); return }
        val side = if (steer < 0) -1 else 1
        val duty = (mag / HOLD_AT).coerceAtMost(1f)
        val phase = (now % PERIOD_MS).toFloat() / PERIOD_MS
        injector.setDesired(if (phase < duty) side else 0)
    }

    // ── Injection des appuis (bouton gauche / droite du jeu) ────────────────────

    private inner class SteeringInjector {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var side = 0       // côté actuellement maintenu : -1 gauche, 1 droite, 0 rien
        private var desired = 0
        private var busy = false

        fun setDesired(s: Int) { desired = s; pump() }
        fun release() { desired = 0; pump() }

        private fun pointFor(s: Int): PointF {
            val m = realMetrics()
            return if (s < 0) PointF(WheelSettings.leftX(this@JarvisWheelService) * m.widthPixels,
                WheelSettings.leftY(this@JarvisWheelService) * m.heightPixels)
            else PointF(WheelSettings.rightX(this@JarvisWheelService) * m.widthPixels,
                WheelSettings.rightY(this@JarvisWheelService) * m.heightPixels)
        }

        private fun pump() {
            if (busy) return
            val svc: AccessibilityService = JarvisAccessibilityService.instance ?: return
            val cur = stroke
            val next: GestureDescription.StrokeDescription
            if (cur != null) {
                val p = pointFor(side)
                val path = Path().apply { moveTo(p.x, p.y) }
                val keep = desired == side
                next = cur.continueStroke(path, 0, if (keep) SEG_MS else 20L, keep)
                if (!keep) { stroke = null; side = 0 } else stroke = next
            } else if (desired != 0) {
                val p = pointFor(desired)
                val path = Path().apply { moveTo(p.x, p.y) }
                next = GestureDescription.StrokeDescription(path, 0, SEG_MS, true)
                stroke = next
                side = desired
            } else return

            busy = true
            val ok = svc.dispatchGesture(
                GestureDescription.Builder().addStroke(next).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { busy = false; pump() }
                    override fun onCancelled(g: GestureDescription?) {
                        busy = false; stroke = null; side = 0; pump()
                    }
                },
                mainHandler
            )
            if (!ok) { busy = false; stroke = null; side = 0 }
        }
    }

    // ── Réglage : où sont les boutons de direction du jeu ? ─────────────────────

    /** Laisse 10 s pour ouvrir le jeu, puis demande de toucher la flèche gauche puis la droite. */
    private fun scheduleCalibration() {
        Toast.makeText(this, "Ouvre BB Racing (en course). Le réglage démarre dans 10 secondes.", Toast.LENGTH_LONG).show()
        mainHandler.postDelayed({ showCalibration() }, 10_000L)
    }

    private fun showCalibration() {
        if (calibView != null) return
        var step = 0
        val tv = TextView(this).apply {
            text = "Touche le bouton de direction GAUCHE du jeu"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.argb(120, 5, 5, 8))
        }
        tv.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val m = realMetrics()
                val rx = (e.rawX / m.widthPixels).coerceIn(0f, 1f)
                val ry = (e.rawY / m.heightPixels).coerceIn(0f, 1f)
                if (step == 0) {
                    WheelSettings.setLeft(this, rx, ry)
                    step = 1
                    tv.text = "Parfait. Maintenant touche le bouton de direction DROITE"
                } else {
                    WheelSettings.setRight(this, rx, ry)
                    runCatching { windowManager.removeView(tv) }
                    calibView = null
                    Toast.makeText(this, "Boutons de direction enregistrés.", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(tv, lp) }.onSuccess { calibView = tv }
    }

    // ── Notification ────────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Jarvis — volant virtuel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis - volant virtuel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(3, n)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(3, buildNotification(text))
    }
}
