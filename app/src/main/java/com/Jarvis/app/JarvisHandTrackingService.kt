package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Suit la main de l'utilisateur via la caméra avant (MediaPipe Hand Landmarker) et déplace
 * un point noir à l'écran en fonction de la position de l'index. Un pincement pouce-index
 * déclenche un vrai tap (ou un glissement si la main bouge pendant le pincement), exécuté
 * via JarvisAccessibilityService — exactement comme si l'utilisateur touchait l'écran.
 *
 * NOTE : nécessite le fichier de modèle "hand_landmarker.task" dans app/src/main/assets/
 * (voir instructions fournies séparément — c'est un fichier binaire que je ne peux pas
 * générer moi-même).
 */
class JarvisHandTrackingService : LifecycleService() {

    companion object {
        var isRunning = false
            private set

        // Distance normalisée (0..1, proportionnelle à la largeur de l'image) en dessous
        // de laquelle pouce+index sont considérés comme "pincés". À ajuster si besoin :
        // trop bas = pincement jamais détecté, trop haut = clics déclenchés par erreur.
        private const val PINCH_THRESHOLD = 0.07f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var dotView: View
    private lateinit var dotParams: WindowManager.LayoutParams
    private lateinit var cameraExecutor: ExecutorService
    private var handLandmarker: HandLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0

    private var isPinching = false
    private var pinchStartTime = 0L
    private val pathPoints = mutableListOf<PointF>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        cameraExecutor = Executors.newSingleThreadExecutor()
        readScreenSize()
        startForegroundWithNotification()
        setupDot()
        setupHandLandmarker()
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun readScreenSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManagerDefault().defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun windowManagerDefault(): WindowManager =
        getSystemService(WINDOW_SERVICE) as WindowManager

    private fun setupDot() {
        windowManager = windowManagerDefault()
        dotView = View(this).apply {
            setBackgroundResource(R.drawable.hand_cursor_dot)
        }
        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (28 * resources.displayMetrics.density).toInt()
        dotParams = WindowManager.LayoutParams(
            sizePx, sizePx, overlayType,
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
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
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

    private fun onHandResult(result: HandLandmarkerResult) {
        mainHandler.post {
            if (result.landmarks().isEmpty()) return@post
            val hand = result.landmarks()[0]
            val indexTip = hand[8]
            val thumbTip = hand[4]

            val screenX = indexTip.x() * screenWidth
            val screenY = indexTip.y() * screenHeight
            moveDot(screenX, screenY)

            val dx = indexTip.x() - thumbTip.x()
            val dy = indexTip.y() - thumbTip.y()
            val pinchDistance = sqrt(dx * dx + dy * dy)
            handlePinchState(pinchDistance < PINCH_THRESHOLD, screenX, screenY)
        }
    }

    private fun moveDot(x: Float, y: Float) {
        dotParams.x = (x - dotParams.width / 2).toInt().coerceIn(0, screenWidth)
        dotParams.y = (y - dotParams.height / 2).toInt().coerceIn(0, screenHeight)
        if (::dotView.isInitialized) {
            runCatching { windowManager.updateViewLayout(dotView, dotParams) }
        }
    }

    private fun handlePinchState(pinching: Boolean, x: Float, y: Float) {
        when {
            pinching && !isPinching -> {
                isPinching = true
                pinchStartTime = SystemClock.uptimeMillis()
                pathPoints.clear()
                pathPoints.add(PointF(x, y))
            }
            pinching && isPinching -> {
                val last = pathPoints.lastOrNull()
                if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) > 6) {
                    pathPoints.add(PointF(x, y))
                }
            }
            !pinching && isPinching -> {
                isPinching = false
                val duration = SystemClock.uptimeMillis() - pinchStartTime
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    updateNotification("Active le contrôle d'écran pour que le pincement agisse.")
                } else if (pathPoints.size <= 2) {
                    service.tapAt(x, y)
                } else {
                    service.swipePath(pathPoints.toList(), duration)
                }
                pathPoints.clear()
            }
        }
    }

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
            .setContentText("Curseur main actif — pince pouce-index pour cliquer.")
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
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cameraExecutor.shutdown()
        handLandmarker?.close()
        if (::dotView.isInitialized) {
            runCatching { windowManager.removeView(dotView) }
        }
    }
}
