package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    /** Cherche un élément visible à l'écran dont le texte contient [label] et clique dessus. */
    fun clickByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, label) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.contains(text, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, text)?.let { return it }
        }
        return null
    }

    /** Tape du texte dans le champ actuellement focalisé. */
    fun typeInFocusedField(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
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
