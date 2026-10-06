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
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)
        setupHandLandmarker()
        startCamera()
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
            handLandmarker?.detectAsync(BitmapImageBuilder(bmp).build(), SystemClock.uptimeMillis())
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    // ── Résultat : angle du volant → direction ──────────────────────────────────

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.size < 2) { onHandsLost(force = false); return }

        val aspect = frameAspect
        // Centre de la paume (poignet + base du majeur) de chaque main, de gauche à droite dans l'image.
        val pts = hands.map { h ->
            PointF((h[0].x() + h[9].x()) / 2f * aspect, (h[0].y() + h[9].y()) / 2f)
        }.sortedBy { it.x }
        val dx = pts[1].x - pts[0].x
        val dy = pts[1].y - pts[0].y
        if (hypot(dx, dy) < 0.12f) { onHandsLost(force = false); return } // mains trop proches : mesure instable

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
            return
        }

        angleSm += ((raw - neutral) - angleSm) * 0.5f
        val lock = WheelSettings.lockDeg(this).toFloat()
        val a = angleSm
        val steer = if (abs(a) < DEADZONE_DEG) 0f
        else (Math.signum(a) * (abs(a) - DEADZONE_DEG) / (lock - DEADZONE_DEG)).coerceIn(-1f, 1f)

        showWheel(a, steer, true)
        applySteer(steer, SystemClock.uptimeMillis())
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
