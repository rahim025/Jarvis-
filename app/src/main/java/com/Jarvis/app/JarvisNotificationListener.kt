package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.jarvis.app.ai.ApiKeyStore
import com.jarvis.app.ai.AutoReplyStore
import com.jarvis.app.ai.BackendClient
import com.jarvis.app.ai.ConversationStore
import com.jarvis.app.ai.JarvisMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Répond à ta place sur WhatsApp / Messenger / Facebook quand un contact autorisé t'écrit.
 *
 * Principe : Jarvis lit la notification du message, demande au cerveau une réponse dans TON style
 * (d'après tes anciens messages dans la discussion et la mémoire), puis la renvoie via le bouton
 * « Répondre » de la notification. Aucun écran n'est touché : ça marche en parallèle des autres tâches.
 *
 * Garde-fous : seulement les contacts autorisés (ou « tout le monde » si tu l'as demandé), jamais les
 * groupes, plafond de réponses par heure (anti-boucle), et silence + alerte pour l'argent, les codes,
 * les engagements ou quand on te demande si c'est bien toi.
 */
class JarvisNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, Job>()
    private val sent = ConcurrentHashMap<String, MutableList<Long>>()

    // Sujets qu'on ne laisse JAMAIS à un robot, même avant d'interroger le cerveau.
    private val SENSITIVE = Regex(
        "(mot de passe|password|\\bcode\\b|\\botp\\b|\\b\\d{4,8}\\b|virement|argent|fcfa|\\bxof\\b|momo|mobile money|transfert|pr[eê]te[- ]moi|rembourse|urgence|urgent)",
        RegexOption.IGNORE_CASE
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!AutoReplyStore.isEnabled(this)) return
        val appKey = AutoReplyStore.appKeyForPackage(sbn.packageName) ?: return
        if (appKey !in AutoReplyStore.apps(this)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        if (style.isGroupConversation) return
        val last = style.messages.lastOrNull() ?: return
        val person = last.person ?: return // pas de personne = message de moi-même
        val contact = (person.name ?: style.conversationTitle)?.toString()?.trim().orEmpty()
        if (contact.isBlank() || !AutoReplyStore.allows(this, contact)) return

        // Chaque message reçu (et ceux que tu as écrits toi-même) entre dans la mémoire de la conversation.
        val store = ConversationStore.get(this)
        val incoming = style.messages.mapNotNull { m ->
            val t = m.text?.toString() ?: return@mapNotNull null
            ConversationStore.Msg(m.person == null, t, m.timestamp, appKey)
        }
        scope.launch(Dispatchers.IO) { runCatching { store.ingest(contact, appKey, incoming) } }

        val key = "${sbn.packageName}|${contact.lowercase()}"
        // On attend un peu : si le contact envoie plusieurs messages d'affilée, on répond une seule fois (et ça fait plus humain).
        pending[key]?.cancel()
        pending[key] = scope.launch {
            delay(Random.nextLong(5000, 9000))
            answer(sbn.key, appKey, contact, key)
        }
    }

    private suspend fun answer(notifKey: String, appKey: String, contact: String, convKey: String) {
        // On relit la notification la plus récente (le contact a pu écrire d'autres messages).
        val sbn = activeNotifications?.firstOrNull { it.key == notifKey } ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) ?: return
        val last = style.messages.lastOrNull() ?: return
        if (last.person == null) return // j'ai déjà répondu entre-temps

        if (SENSITIVE.containsMatchIn(last.text ?: "")) {
            alert(contact, "sujet sensible (argent, code, urgence…)", last.text.toString())
            return
        }
        if (!underLimit(convKey)) return

        // Le fil complet vient de la mémoire de la conversation (pas seulement de la notification).
        val store = ConversationStore.get(this)
        withContext(Dispatchers.IO) {
            runCatching {
                store.ingest(contact, appKey, style.messages.mapNotNull { m ->
                    m.text?.toString()?.let { ConversationStore.Msg(m.person == null, it, m.timestamp, appKey) }
                })
            }
        }
        val history = store.recent(contact, 30).map { Triple(it.fromMe, it.text, it.ts) }
        val summary = store.summary(contact)
        val memory = JarvisMemory.get(this)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                BackendClient.autoReply(
                    contact, appKey, history, summary,
                    memory.buildPayload(last.text?.toString().orEmpty()),
                    ApiKeyStore.requestExtras(this@JarvisNotificationListener)
                )
            }.getOrNull()
        } ?: return

        result.remember.takeIf { it.isNotBlank() }?.let { store.appendNote(contact, it) }

        if (result.skip || result.reply.isBlank()) {
            // Simple « ok » / « merci » qui clôt l'échange : pas besoin de te déranger.
            if (result.reason.contains("rien à répondre", ignoreCase = true)) return
            alert(contact, result.reason.ifBlank { "je préfère te laisser répondre" }, last.text.toString())
            return
        }

        // Un humain met un moment à écrire : durée proportionnelle à la longueur du message.
        // Si le contact écrit encore pendant ce temps, ce travail est annulé et on repart avec le nouveau message.
        delay((1500L + result.reply.length * 60L).coerceAtMost(8000L))

        val fresh = activeNotifications?.firstOrNull { it.key == notifKey } ?: sbn
        if (sendReply(fresh, result.reply)) {
            record(convKey)
            withContext(Dispatchers.IO) {
                runCatching { store.add(contact, appKey, true, result.reply, System.currentTimeMillis()) }
                runCatching { maybeSummarize(contact) }
            }
        }
    }

    /** Quand la conversation s'allonge, les vieux échanges sont condensés dans le résumé (le fil reste léger). */
    private fun maybeSummarize(contact: String) {
        val store = ConversationStore.get(this)
        val older = store.unsummarizedOlder(contact, 20)
        val current = store.summary(contact)
        if (older.size < 25 && current.length < 1500) return
        val merged = BackendClient.summarizeConversation(
            contact, current, older.map { Triple(it.fromMe, it.text, it.ts) },
            ApiKeyStore.requestExtras(this)
        ) ?: return
        val upTo = older.lastOrNull()?.ts ?: store.summarizedUpTo(contact)
        store.saveNotes(contact, merged, upTo)
        store.pruneUpTo(contact, upTo)
    }

    /** Renvoie [text] via le champ « Répondre » de la notification. */
    private fun sendReply(sbn: StatusBarNotification, text: String): Boolean {
        val n = sbn.notification
        val actions = (0 until NotificationCompat.getActionCount(n)).mapNotNull { NotificationCompat.getAction(n, it) } +
            NotificationCompat.WearableExtender(n).actions
        val reply = actions.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return false
        val inputs = reply.remoteInputs ?: return false
        val bundle = Bundle().also { b -> inputs.forEach { b.putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return runCatching { reply.actionIntent.send(this, 0, intent) }.isSuccess
    }

    private fun underLimit(convKey: String): Boolean {
        val now = System.currentTimeMillis()
        val hour = now - 3_600_000
        val mine = sent.getOrPut(convKey) { mutableListOf() }
        synchronized(mine) { mine.removeAll { it < hour } ; if (mine.size >= 12) return false }
        val total = sent.values.sumOf { l -> synchronized(l) { l.count { it >= hour } } }
        return total < 40
    }

    private fun record(convKey: String) {
        val l = sent.getOrPut(convKey) { mutableListOf() }
        synchronized(l) { l.add(System.currentTimeMillis()) }
    }

    /** Prévient l'utilisateur qu'il doit répondre lui-même. */
    private fun alert(contact: String, reason: String, message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = "jarvis_autoreply"
        nm.createNotificationChannel(NotificationChannel(channel, "Réponses automatiques", NotificationManager.IMPORTANCE_DEFAULT))
        val notif = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis n'a pas répondu à $contact")
            .setContentText("À toi de jouer : $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText("« ${message.take(200)} »\nJe n'ai pas répondu : $reason."))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(contact.hashCode(), notif) }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
