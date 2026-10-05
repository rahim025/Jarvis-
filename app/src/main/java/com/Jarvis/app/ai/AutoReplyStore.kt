package com.jarvis.app.ai

import android.content.Context

/**
 * Réglages des réponses automatiques (WhatsApp / Messenger / Facebook) :
 * interrupteur général, apps surveillées, et liste des contacts à qui Jarvis répond à ta place.
 * Par défaut, Jarvis ne répond QU'AUX contacts de la liste (« tout le monde » est une option explicite).
 */
object AutoReplyStore {
    private const val FILE = "jarvis_autoreply"
    private const val K_ENABLED = "enabled"
    private const val K_ALL = "answer_all"
    private const val K_CONTACTS = "contacts"
    private const val K_APPS = "apps"

    private val PACKAGES = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "messenger" to listOf("com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.facebook.lite")
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Clé d'app (« whatsapp » / « messenger ») depuis un paquet Android, ou null si non surveillée. */
    fun appKeyForPackage(pkg: String): String? = PACKAGES.entries.firstOrNull { pkg in it.value }?.key

    /** Clé d'app depuis ce que dit l'utilisateur (« Facebook » = Messenger). */
    fun appKeyFromName(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("whatsapp") -> "whatsapp"
            n.contains("messenger") || n.contains("facebook") -> "messenger"
            else -> null
        }
    }

    fun isEnabled(c: Context) = prefs(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_ENABLED, on).apply()

    fun answersAll(c: Context) = prefs(c).getBoolean(K_ALL, false)
    fun setAnswerAll(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_ALL, on).apply()

    fun apps(c: Context): Set<String> = prefs(c).getStringSet(K_APPS, setOf("whatsapp", "messenger")) ?: emptySet()
    fun setApp(c: Context, key: String, on: Boolean) {
        val s = apps(c).toMutableSet()
        if (on) s.add(key) else s.remove(key)
        prefs(c).edit().putStringSet(K_APPS, s).apply()
    }

    fun contacts(c: Context): Set<String> = prefs(c).getStringSet(K_CONTACTS, emptySet()) ?: emptySet()
    fun addContact(c: Context, name: String) =
        prefs(c).edit().putStringSet(K_CONTACTS, contacts(c) + JarvisMemory.normalize(name)).apply()
    fun removeContact(c: Context, name: String) {
        val n = JarvisMemory.normalize(name)
        prefs(c).edit().putStringSet(K_CONTACTS, contacts(c).filterNot { it.contains(n) || n.contains(it) }.toSet()).apply()
    }

    /** Jarvis a-t-il le droit de répondre à ce contact (nom tel qu'affiché dans la notification) ? */
    fun allows(c: Context, contactName: String): Boolean {
        if (!isEnabled(c)) return false
        if (answersAll(c)) return true
        val n = JarvisMemory.normalize(contactName)
        return n.isNotBlank() && contacts(c).any { it.isNotBlank() && (n.contains(it) || it.contains(n)) }
    }
}
