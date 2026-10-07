package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Clavier AZERTY transparent qui flotte par-dessus les autres apps (style Jarvis).
 * Il ne capte jamais les touchers : c'est le curseur main (service caméra) qui lui dit sur quelle
 * touche tu pinces. Le texte est écrit dans le champ actif via le service d'accessibilité.
 */
class HandKeyboard(
    private val context: Context,
    private val wm: WindowManager,
    private val screenW: Int,
    private val screenH: Int,
    private val overlayType: Int
) {
    enum class Kind { CHAR, SHIFT, BACK, SPACE, MODE }

    class Key(val kind: Kind, val label: String, val weight: Float) {
        val rect = RectF()
    }

    var isVisible = false
        private set

    private var shift = false
    private var numbers = false
    private var rows: List<List<Key>> = emptyList()
    private var hovered: Key? = null
    private var flashKey: Key? = null
    private var flashUntil = 0L
    private var message = ""
    private var messageUntil = 0L
    private var sendProgress = 0f

    private var view: KbView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private val width get() = (screenW * 0.96f).toInt()
    private val pad get() = dp(8)
    private val gap get() = dp(5)
    private val strip get() = dp(30)
    private val keyH get() = dp(46)
    private val height get() = pad + strip + gap + 4 * keyH + 3 * gap + pad

    // ── Affichage ───────────────────────────────────────────────────────────────

    fun show() {
        if (isVisible) return
        buildRows()
        val v = KbView(context)
        val p = WindowManager.LayoutParams(
            width, height, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - width) / 2
            y = screenH - height - dp(28)
        }
        runCatching { wm.addView(v, p) }.onSuccess { view = v; params = p; isVisible = true }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
        isVisible = false
        hovered = null
    }

    fun toggle() = if (isVisible) hide() else show()

    // ── Touches ─────────────────────────────────────────────────────────────────

    private fun buildRows() {
        fun chars(s: String, w: Float = 1f) = s.map { Key(Kind.CHAR, it.toString(), w) }
        val r1: List<Key>
        val r2: List<Key>
        val r3: List<Key>
        if (!numbers) {
            r1 = chars("azertyuiop")
            r2 = chars("qsdfghjklm")
            r3 = listOf(Key(Kind.SHIFT, "⇧", 2f)) + chars("wxcvbn") + listOf(Key(Kind.BACK, "⌫", 2f))
        } else {
            r1 = chars("1234567890")
            r2 = chars("-/:;()€&@\"")
            r3 = chars(".,?!'_#+") + listOf(Key(Kind.BACK, "⌫", 2f))
        }
        val r4 = listOf(Key(Kind.MODE, if (numbers) "ABC" else "123", 1.5f)) + chars(",") +
            listOf(Key(Kind.SPACE, "espace", 5f)) + chars(".") + chars("?", 1.5f)
        rows = listOf(r1, r2, r3, r4)
        layoutRects()
    }

    private fun layoutRects() {
        var y = (pad + strip + gap).toFloat()
        for (row in rows) {
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - 2 * pad - gap * (row.size - 1)) / total
            var x = pad.toFloat()
            for (k in row) {
                val w = unit * k.weight
                k.rect.set(x, y, x + w, y + keyH)
                x += w + gap
            }
            y += keyH + gap
        }
    }

    /** Touche sous le point (x, y) de l'écran, ou null. */
    fun keyAt(x: Float, y: Float): Key? {
        val p = params ?: return null
        val lx = x - p.x
        val ly = y - p.y
        for (row in rows) for (k in row) if (k.rect.contains(lx, ly)) return k
        return null
    }

    /** Vrai si le point est dans la zone du clavier (même entre deux touches) : on n'envoie alors aucun clic dessous. */
    fun covers(x: Float, y: Float): Boolean {
        val p = params ?: return false
        return x >= p.x && x <= p.x + width && y >= p.y && y <= p.y + height
    }

    fun setHover(x: Float, y: Float) {
        val k = keyAt(x, y)
        if (k !== hovered) { hovered = k; view?.invalidate() }
    }

    fun setSendProgress(p: Float) {
        if (kotlin.math.abs(p - sendProgress) > 0.03f || p == 0f) { sendProgress = p; view?.invalidate() }
    }

    fun flashMessage(text: String, ms: Long = 1800) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + ms
        view?.invalidate()
        handler.postDelayed({ view?.invalidate() }, ms + 50)
    }

    // ── Frappe ──────────────────────────────────────────────────────────────────

    fun press(k: Key) {
        flashKey = k
        flashUntil = SystemClock.uptimeMillis() + 160
        handler.postDelayed({ view?.invalidate() }, 180)
        when (k.kind) {
            Kind.SHIFT -> shift = !shift
            Kind.MODE -> { numbers = !numbers; shift = false; buildRows() }
            Kind.CHAR -> {
                val c = if (shift && !numbers) k.label.uppercase() else k.label
                type(c)
                if (shift) shift = false
            }
            Kind.SPACE -> type(" ")
            Kind.BACK -> backspace()
        }
        view?.invalidate()
    }

    fun type(text: String) {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) { flashMessage("Active le contrôle d'écran"); return }
        if (!svc.appendToFocusedField(text)) flashMessage("Touche d'abord le champ de message")
    }

    fun backspace() {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) { flashMessage("Active le contrôle d'écran"); return }
        if (!svc.backspaceFocusedField()) flashMessage("Touche d'abord le champ de message")
    }

    // ── Dessin (style Jarvis : bleu nuit transparent, cadre cyan) ───────────────

    private inner class KbView(c: Context) : View(c) {
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 5, 12, 20) }
        private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(210, 27, 70, 104)
        }
        private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(135, 10, 22, 35) }
        private val keyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(190, 27, 70, 104)
        }
        private val hoverFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 11, 34, 54) }
        private val hoverStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.parseColor("#4CA8E8")
        }
        private val flashFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 76, 168, 232) }
        private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 34, 197, 94) }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CFE6F7"); textAlign = Paint.Align.CENTER; textSize = dp(19).toFloat()
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8FB3CC"); textAlign = Paint.Align.CENTER
            textSize = dp(12).toFloat(); typeface = Typeface.MONOSPACE
        }

        override fun onDraw(c: Canvas) {
            val r = dp(14).toFloat()
            val full = RectF(1f, 1f, width - 1f, height - 1f)
            c.drawRoundRect(full, r, r, panel)
            c.drawRoundRect(full, r, r, panelStroke)

            // Bandeau du haut : aide, message, ou progression de l'envoi (pouce levé).
            val now = SystemClock.uptimeMillis()
            val stripRect = RectF(pad.toFloat(), pad.toFloat(), width - pad.toFloat(), (pad + strip).toFloat())
            if (sendProgress > 0f) {
                val bar = RectF(stripRect.left, stripRect.top, stripRect.left + stripRect.width() * sendProgress, stripRect.bottom)
                c.drawRoundRect(bar, dp(8).toFloat(), dp(8).toFloat(), barFill)
            }
            val label = when {
                sendProgress > 0f -> "garde le pouce levé pour envoyer…"
                now < messageUntil -> message
                else -> "pince pour taper · pouce levé 1 s = envoyer"
            }
            c.drawText(label, stripRect.centerX(), stripRect.centerY() - (small.ascent() + small.descent()) / 2f, small)

            for (row in rows) for (k in row) {
                val flash = k === flashKey && now < flashUntil
                val hover = k === hovered
                val kr = k.rect
                val kc = dp(10).toFloat()
                c.drawRoundRect(kr, kc, kc, if (flash) flashFill else if (hover) hoverFill else keyFill)
                c.drawRoundRect(kr, kc, kc, if (hover) hoverStroke else keyStroke)
                val shown = when {
                    k.kind == Kind.CHAR && shift && !numbers -> k.label.uppercase()
                    k.kind == Kind.SHIFT && shift -> "⇪"
                    else -> k.label
                }
                val p = if (k.kind == Kind.SPACE || k.kind == Kind.MODE) small else txt
                c.drawText(shown, kr.centerX(), kr.centerY() - (p.ascent() + p.descent()) / 2f, p)
            }
        }
    }
}
