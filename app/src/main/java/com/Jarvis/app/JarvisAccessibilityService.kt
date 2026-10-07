package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import com.jarvis.app.ai.JarvisMemory
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Un élément visible à l'écran, tel que Jarvis le « voit » (texte ou description, rôle, état). */
data class UiElement(
    val node: AccessibilityNodeInfo,
    val text: String,
    val hint: String,
    val viewId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val bounds: Rect
) {
    val normText: String get() = JarvisMemory.normalize(text)
}

/**
 * Le cœur du "contrôle avancé" : ce service tourne en arrière-plan une fois
 * activé par l'utilisateur (Paramètres > Accessibilité) et permet de :
 *  - lire ce qui est affiché à l'écran (arbre de vues)
 *  - cliquer sur un élément par son texte
 *  - taper du texte dans un champ
 *  - simuler les boutons retour / accueil
 *
 * L'instance active est exposée en statique pour que CommandExecutor
 * puisse l'utiliser facilement (approche simple pour un prototype).
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // On pourrait logger ici les changements d'écran / notifications
        // pour donner du contexte à Groq/Gemini si besoin.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Capture l'écran (Android 11+) et renvoie la photo en JPEG base64, réduite à ~1280 px
     * de large pour rester légère. À appeler depuis un thread d'arrière-plan (bloque ~1 s).
     * Sert à la vision : « Jarvis, qu'est-ce que je regarde ? » (analysée par Gemini).
     */
    fun captureScreenBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var result: String? = null
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        if (soft != null) {
                            val ratio = 1280f / soft.width.toFloat()
                            val scaled = if (ratio < 1f)
                                Bitmap.createScaledBitmap(soft, 1280, (soft.height * ratio).toInt(), true)
                            else soft
                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            result = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        }
                    } catch (e: Exception) {
                        result = null
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    /**
     * Lit le texte exact affiché à l'écran (titres, messages, boutons, champs) via l'arbre
     * d'accessibilité. Envoyé avec la capture à l'IA pour qu'elle « voie » l'écran comme toi :
     * l'image donne l'apparence, ce texte donne les mots exacts.
     */
    fun readScreenText(maxChars: Int = 3000): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            if (sb.length >= maxChars || !n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (t.isNotEmpty()) t else d
            if (label.isNotEmpty()) {
                val kind = when {
                    n.isEditable -> "[champ] "
                    n.isClickable -> "[bouton] "
                    else -> ""
                }
                sb.append(kind).append(label.take(150)).append('\n')
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        return sb.toString().take(maxChars)
    }

    /** Cherche un élément visible à l'écran dont le texte contient [label] et clique dessus. */
    fun clickByLabel(label: String): Boolean {
        val el = findElement(label, editable = false) ?: return false
        return clickElement(el)
    }

    // ── Briques utilisées par TaskRunner (tâches en plusieurs étapes avec vérification) ──

    /** Nom du paquet de l'application actuellement au premier plan (vide si inconnu). */
    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    /** Liste à plat des éléments visibles (texte / description / champs / cases), dans l'ordre de l'écran. */
    fun snapshot(maxElements: Int = 120): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiElement>()
        fun walk(n: AccessibilityNodeInfo) {
            if (out.size >= maxElements || !n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val label = if (t.isNotEmpty()) t else d
            if (label.isNotEmpty() || n.isEditable || n.isCheckable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                out.add(
                    UiElement(
                        node = n,
                        text = label.take(150),
                        hint = n.hintText?.toString().orEmpty(),
                        viewId = n.viewIdResourceName.orEmpty(),
                        clickable = n.isClickable,
                        editable = n.isEditable,
                        checkable = n.isCheckable,
                        checked = n.isChecked,
                        bounds = r
                    )
                )
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        return out
    }

    /** Empreinte de l'écran : change dès que quelque chose d'affiché change (sert à vérifier qu'une action a eu un effet). */
    fun screenSignature(): Int {
        val els = snapshot(80)
        return (currentPackage() + "|" + els.joinToString("|") { it.text + it.checked }).hashCode()
    }

    /** Trouve le meilleur élément pour [label] (égalité > début > contient), insensible aux accents et à la casse. */
    fun findElement(
        label: String,
        editable: Boolean? = null,
        elements: List<UiElement> = snapshot(200)
    ): UiElement? {
        val wanted = JarvisMemory.normalize(label)
        if (wanted.isBlank()) return null
        var best: UiElement? = null
        var bestScore = 0
        for (e in elements) {
            if (editable != null && e.editable != editable) continue
            val t = e.normText
            val score = when {
                t.isEmpty() -> 0
                t == wanted -> 100
                t.startsWith(wanted) -> 80
                t.contains(wanted) -> 60
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = e }
        }
        return best
    }

    /** Clique sur l'élément ; si lui-même n'est pas cliquable, remonte à un parent cliquable, puis tape au centre en dernier recours. */
    fun clickElement(e: UiElement): Boolean {
        var n: AccessibilityNodeInfo? = e.node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        if (!e.bounds.isEmpty) {
            tapAt(e.bounds.exactCenterX(), e.bounds.exactCenterY())
            return true
        }
        return false
    }

    /** Met le focus sur un champ et y écrit [text] (remplace le contenu). */
    fun setText(e: UiElement, text: String): Boolean {
        e.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        e.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return e.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Touche « Entrée » du clavier sur un champ (valider une recherche). Android 11+. */
    fun pressEnter(e: UiElement): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return e.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Tape du texte dans le champ actuellement focalisé. */
    fun typeInFocusedField(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    // ── Clavier main : écrire / effacer / envoyer dans la conversation ouverte ──

    private fun focusedInputNode(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setNodeText(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Ajoute [s] à la fin du texte du champ actif (le texte « Message… » grisé n'est pas du vrai texte). */
    fun appendToFocusedField(s: String): Boolean {
        val n = focusedInputNode() ?: return false
        val cur = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        return setNodeText(n, cur + s)
    }

    fun backspaceFocusedField(): Boolean {
        val n = focusedInputNode() ?: return false
        if (n.isShowingHintText) return true
        val cur = n.text?.toString().orEmpty()
        if (cur.isEmpty()) return true
        return setNodeText(n, cur.dropLast(1))
    }

    /** Appuie sur « Envoyer » dans l'app ouverte (WhatsApp, Instagram, Messenger…), sinon sur Entrée. */
    fun sendFocusedMessage(): Boolean {
        for (label in listOf("Envoyer", "Send", "Enviar")) if (clickByLabel(label)) return true
        val n = focusedInputNode() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /** "Sors de l'appli" : il n'existe pas de "fermer proprement telle appli" côté API
     *  publique Android sans droits admin, donc on revient à l'écran d'accueil, ce qui
     *  fait quitter l'appli au premier plan. */
    fun closeCurrentApp() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Simule un défilement au centre de l'écran dans une direction donnée
     *  ("up", "down", "left", "right"), utilisable sur n'importe quelle appli. */
    fun scroll(direction: String) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val path = Path()
        when (direction) {
            // "défiler en bas" (voir la suite du contenu) : le doigt glisse vers le haut.
            "down" -> { path.moveTo(cx, h * 0.75f); path.lineTo(cx, h * 0.25f) }
            // "défiler en haut" (remonter dans le contenu) : le doigt glisse vers le bas.
            "up" -> { path.moveTo(cx, h * 0.25f); path.lineTo(cx, h * 0.75f) }
            // "défiler à gauche" (voir le contenu de gauche) : le doigt glisse vers la droite.
            "left" -> { path.moveTo(w * 0.25f, cy); path.lineTo(w * 0.85f, cy) }
            // "défiler à droite" (voir le contenu de droite) : le doigt glisse vers la gauche.
            "right" -> { path.moveTo(w * 0.85f, cy); path.lineTo(w * 0.25f, cy) }
            else -> return
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Simule un tap à des coordonnées précises (utile si aucun label ne matche). */
    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Simule un glissement suivant une série de points (utilisé par le suivi de main par caméra). */
    fun swipePath(points: List<android.graphics.PointF>, durationMs: Long) {
        if (points.isEmpty()) return
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        val safeDuration = durationMs.coerceIn(50, 3000)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, safeDuration))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
