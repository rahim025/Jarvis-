package com.jarvis.app.ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.jarvis.app.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Garde la conversation avec cette personne » : Jarvis regarde la discussion ouverte à l'écran
 * (capture + texte, comme toi), reconnaît le contact et les derniers messages, les range dans la mémoire
 * de la conversation, autorise ce contact aux réponses automatiques, et répond tout de suite si le dernier
 * message vient de lui et attend une réponse.
 */
object ScreenChat {

    private const val NO_SERVICE =
        "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."

    suspend fun keep(context: Context, contactHint: String): String = withContext(Dispatchers.Default) {
        val service = JarvisAccessibilityService.instance ?: return@withContext NO_SERVICE
        val appKey = AutoReplyStore.appKeyForPackage(service.currentPackage())
            ?: return@withContext "Ouvre d'abord la discussion dans WhatsApp ou Messenger, puis redis-moi de la garder."

        // 1. Regarder l'écran : capture (si Android 11+) + éléments visibles avec leur position.
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toDouble()
        val h = metrics.heightPixels.toDouble()
        val elements = JSONArray()
        service.snapshot(150).forEach { e ->
            if (e.text.isBlank()) return@forEach
            elements.put(JSONObject()
                .put("t", e.text.take(200))
                .put("x", (e.bounds.centerX() / w * 100).toInt())
                .put("y", (e.bounds.centerY() / h * 100).toInt()))
        }
        val chat = withContext(Dispatchers.IO) {
            val shot = runCatching { service.captureScreenBase64() }.getOrNull()
            runCatching { BackendClient.readChat(shot, elements, appKey, ApiKeyStore.requestExtras(context)) }.getOrNull()
        } ?: return@withContext "Je n'arrive pas à lire la discussion. Réessaie dans un instant."

        if (!chat.isChat) return@withContext "Je ne vois pas de discussion ouverte à l'écran."
        if (chat.group) return@withContext "C'est une discussion de groupe : je ne réponds pas dans les groupes."
        val name = chat.contact.ifBlank { contactHint }.trim()
        if (name.isBlank()) return@withContext "Je n'arrive pas à lire le nom du contact. Dis-le moi : garde la conversation avec untel."

        // 2. Ranger ce que j'ai vu dans la mémoire de la conversation (sans doublons avec ce que Jarvis connaît déjà).
        val store = ConversationStore.get(context)
        withContext(Dispatchers.IO) {
            val known = store.recent(name, 60)
            val now = System.currentTimeMillis()
            chat.messages.forEachIndexed { i, (fromMe, text) ->
                if (known.none { it.fromMe == fromMe && it.text.trim() == text.trim() }) {
                    store.add(name, appKey, fromMe, text, now - (chat.messages.size - i) * 1000L)
                }
            }
            if (chat.summary.isNotBlank() && store.summary(name).isBlank()) {
                store.saveNotes(name, chat.summary, store.summarizedUpTo(name))
            }
        }

        // 3. Autoriser ce contact pour les réponses automatiques.
        AutoReplyStore.addContact(context, name)
        AutoReplyStore.setEnabled(context, true)
        AutoReplyStore.setApp(context, appKey, true)

        val listenerOk = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val base = "C'est noté : je garde la conversation avec $name."

        // 4. Si le dernier message vient de lui, on y répond tout de suite.
        val last = chat.messages.lastOrNull()
        var tail = ""
        if (last != null && !last.first) {
            tail = " " + replyNow(context, service, store, name, appKey, last.second)
        }

        if (!listenerOk) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return@withContext "$base$tail Pour répondre aux prochains messages, autorise Jarvis à lire les notifications dans les réglages qui viennent de s'ouvrir."
        }
        "$base$tail Je répondrai à ses prochains messages."
    }

    /** Écrit et envoie la réponse dans la discussion ouverte. Renvoie une phrase à dire à voix haute. */
    private suspend fun replyNow(
        context: Context,
        service: JarvisAccessibilityService,
        store: ConversationStore,
        name: String,
        appKey: String,
        lastText: String
    ): String {
        if (AutoReplyStore.SENSITIVE.containsMatchIn(lastText)) {
            return "Son dernier message parle d'argent, de code ou d'urgence : je te laisse lui répondre."
        }
        val history = store.recent(name, 30).map { Triple(it.fromMe, it.text, it.ts) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                BackendClient.autoReply(
                    name, appKey, history, store.summary(name),
                    JarvisMemory.get(context).buildPayload(lastText),
                    ApiKeyStore.requestExtras(context)
                )
            }.getOrNull()
        } ?: return "Je n'ai pas pu préparer de réponse pour le moment."

        if (result.skip || result.reply.isBlank()) {
            return if (result.reason.contains("rien à répondre", ignoreCase = true)) ""
            else "Je ne lui réponds pas tout de suite : ${result.reason.ifBlank { "je préfère te laisser faire" }}."
        }
        result.remember.takeIf { it.isNotBlank() }?.let { store.appendNote(name, it) }

        delay((1500L + result.reply.length * 60L).coerceAtMost(8000L)) // le temps d'« écrire »
        if (!typeAndSend(service, result.reply)) return "Je n'ai pas réussi à écrire dans la discussion."
        withContext(Dispatchers.IO) { store.add(name, appKey, true, result.reply, System.currentTimeMillis()) }
        return "Je lui ai répondu : « ${result.reply} »."
    }

    private suspend fun typeAndSend(service: JarvisAccessibilityService, text: String): Boolean {
        val wanted = JarvisMemory.normalize(text).take(30)
        val field = service.snapshot().firstOrNull { it.editable } ?: return false
        service.setText(field, text)
        delay(500)
        val typed = service.snapshot().firstOrNull { it.editable && it.normText.contains(wanted) } ?: return false

        val sendBtn = service.snapshot().firstOrNull {
            !it.editable && (it.normText == "envoyer" || it.normText == "send" || it.viewId.endsWith("send"))
        }
        val clicked = if (sendBtn != null) service.clickElement(sendBtn) else service.pressEnter(typed)
        if (!clicked) return false

        // Vérifier l'envoi : le champ ne contient plus le texte.
        repeat(12) {
            delay(400)
            val s = service.snapshot()
            if (s.any { it.editable } && s.none { it.editable && it.normText.contains(wanted) }) return true
        }
        return false
    }
}
