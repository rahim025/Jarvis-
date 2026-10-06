package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages du volant virtuel (caméra). Les positions des boutons de direction du jeu sont
 * stockées en proportion de l'écran (0..1), donc elles restent valables si l'écran tourne.
 */
object WheelSettings {
    private const val PREFS = "jarvis_wheel"

    // Valeurs par défaut : bas de l'écran, à gauche et à droite. À corriger avec
    // « Régler les boutons de direction du jeu » dans le menu ⚙.
    const val DEFAULT_LEFT_X = 0.15f
    const val DEFAULT_LEFT_Y = 0.80f
    const val DEFAULT_RIGHT_X = 0.85f
    const val DEFAULT_RIGHT_Y = 0.80f
    const val DEFAULT_LOCK_DEG = 40

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun leftX(c: Context) = prefs(c).getFloat("left_x", DEFAULT_LEFT_X)
    fun leftY(c: Context) = prefs(c).getFloat("left_y", DEFAULT_LEFT_Y)
    fun rightX(c: Context) = prefs(c).getFloat("right_x", DEFAULT_RIGHT_X)
    fun rightY(c: Context) = prefs(c).getFloat("right_y", DEFAULT_RIGHT_Y)

    fun setLeft(c: Context, x: Float, y: Float) =
        prefs(c).edit().putFloat("left_x", x).putFloat("left_y", y).apply()

    fun setRight(c: Context, x: Float, y: Float) =
        prefs(c).edit().putFloat("right_x", x).putFloat("right_y", y).apply()

    /** Angle (en degrés) dont il faut tourner le volant pour braquer à fond. Plus petit = plus nerveux. */
    fun lockDeg(c: Context): Int = prefs(c).getInt("lock_deg", DEFAULT_LOCK_DEG).coerceIn(15, 90)
    fun setLockDeg(c: Context, v: Int) = prefs(c).edit().putInt("lock_deg", v).apply()
}
