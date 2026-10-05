package com.jarvis.app.ai

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.UiElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exécute des tâches Android en plusieurs étapes, à partir d'une commande vocale naturelle.
 *
 * Principe : chaque étape est suivie d'une VÉRIFICATION (on attend que l'écran prouve que ça a marché)
 * avant de passer à la suivante.
 *  - Scénarios connus (envoyer un message, chercher dans une app, Wi-Fi/Bluetooth) : étapes rapides et
 *    déterministes, avec plusieurs tentatives.
 *  - Si une étape échoue, ou pour toute autre tâche : boucle ADAPTATIVE. Jarvis regarde l'écran, le
 *    cerveau choisit la prochaine action, on la fait, on vérifie qu'elle a eu un effet, et on recommence.
 *    Si l'interface a changé ou si un élément est introuvable, le cerveau essaie une autre stratégie.
 */
object TaskRunner {

    @Volatile private var cancelled = false
    @Volatile var running = false
        private set

    /** Interrompt la tâche en cours (« stop Jarvis », bouton stop). */
    fun cancel() {
        if (running) cancelled = true
    }

    private class TaskCancelled : Exception()

    private class StepFailed(val step: String) : Exception(step)

    private const val NO_SERVICE =
        "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."

    // Mots qui, sur un bouton, demandent une vraie confirmation de l'utilisateur (achat, suppression...).
    private val DANGEROUS = listOf(
        "payer", "acheter", "commander", "supprimer", "effacer", "vider", "virement", "transferer",
        "envoyer de l argent", "delete", "remove", "pay", "buy", "purchase", "confirm payment"
    )

    // ── Point d'entrée ──────────────────────────────────────────────────────────

    suspend fun run(context: Context, task: JarvisAction.RunTask): String =
        withContext(Dispatchers.Default) {
            val service = JarvisAccessibilityService.instance ?: return@withContext NO_SERVICE
            if (running) return@withContext "Je suis déjà occupé par une autre tâche."
            running = true
            cancelled = false
            try {
                val goal = task.goal.ifBlank { defaultGoal(task) }
                val scripted = when {
                    task.kind == "send_message" && task.app.isNotBlank() &&
                        task.contact.isNotBlank() && task.message.isNotBlank() ->
                        runScripted(context, service, goal) { sendMessageFlow(context, service, task) }
                    task.kind == "search" && task.app.isNotBlank() &&
                        (task.query.ifBlank { task.contact }).isNotBlank() ->
                        runScripted(context, service, goal) { searchFlow(context, service, task) }
                    else -> null
                }
                scripted ?: agentLoop(context, service, goal, task.app, "")
            } catch (e: TaskCancelled) {
                "D'accord, j'arrête là."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Je n'ai pas pu terminer la tâche : ${e.message ?: "erreur inconnue"}."
            } finally {
                running = false
                cancelled = false
            }
        }

    /**
     * Lance un scénario. S'il réussit : son message de confirmation. S'il bloque sur une étape :
     * on bascule sur la boucle adaptative, en lui disant où on en était.
     */
    private suspend fun runScripted(
        context: Context,
        service: JarvisAccessibilityService,
        goal: String,
        flow: suspend () -> String
    ): String {
        return try {
            flow()
        } catch (e: StepFailed) {
            agentLoop(context, service, goal, "", "Le scénario rapide a bloqué à l'étape « ${e.step} ». Reprends depuis l'écran actuel.")
        }
    }

    private fun defaultGoal(t: JarvisAction.RunTask): String = when (t.kind) {
        "send_message" -> "Envoyer « ${t.message} » à ${t.contact} sur ${t.app}"
        "search" -> "Chercher « ${t.query.ifBlank { t.contact }} » dans ${t.app}"
        else -> "Accomplir la demande dans ${t.app}"
    }

    // ── Scénario : envoyer un message à un contact dans une app de messagerie ───

    private suspend fun sendMessageFlow(
        context: Context,
        service: JarvisAccessibilityService,
        t: JarvisAction.RunTask
    ): String {
        val contact = t.contact.trim()
        val message = t.message.trim()
        val wantedContact = JarvisMemory.normalize(contact)
        val wantedMessage = JarvisMemory.normalize(message)

        // 1. Ouvrir l'application et attendre qu'elle soit au premier plan.
        val pkg = openApp(context, service, t.app) ?: throw StepFailed("ouvrir ${t.app}")

        // 2. Revenir à l'écran principal de l'app (si elle était restée dans une discussion), puis ouvrir la recherche.
        val search = openSearchField(service, pkg) ?: throw StepFailed("trouver la recherche de ${t.app}")

        // 3. Taper le nom du contact, et vérifier que c'est bien écrit.
        typeAndVerify(service, search, contact) ?: throw StepFailed("écrire « $contact » dans la recherche")

        // 4. Choisir le résultat qui correspond, et vérifier qu'on est dans la bonne discussion.
        val chat = openBestResult(service, contact) ?: throw StepFailed("trouver $contact dans les résultats")
        if (!chat) throw StepFailed("ouvrir la discussion avec $contact")

        // 5. Écrire le message, et vérifier qu'il est bien dans le champ.
        val field = waitFor(5000) {
            service.snapshot().firstOrNull { it.editable && !it.normText.contains(wantedContact) }
        } ?: throw StepFailed("trouver le champ de message")
        service.setText(field, message)
        waitFor(3000) {
            service.snapshot().firstOrNull { it.editable && it.normText.contains(wantedMessage) }
        } ?: run {
            // Deuxième tentative : le champ a pu changer d'identité après le focus.
            val again = service.snapshot().firstOrNull { it.editable } ?: throw StepFailed("écrire le message")
            service.setText(again, message)
            waitFor(3000) {
                service.snapshot().firstOrNull { it.editable && it.normText.contains(wantedMessage) }
            } ?: throw StepFailed("écrire le message")
        }

        // 6. Envoyer : bouton « Envoyer », sinon touche Entrée.
        val sendBtn = waitFor(3000) {
            service.snapshot().firstOrNull { !it.editable && (it.normText == "envoyer" || it.normText == "send" || it.normText.startsWith("envoyer") || it.viewId.endsWith("send")) }
        }
        val clicked = if (sendBtn != null) service.clickElement(sendBtn)
        else service.snapshot().firstOrNull { it.editable }?.let { service.pressEnter(it) } ?: false
        if (!clicked) throw StepFailed("appuyer sur envoyer")

        // 7. Vérifier l'envoi : le champ de saisie ne contient plus le message.
        waitFor(6000) {
            val s = service.snapshot()
            // Le champ existe toujours mais ne contient plus le message : il est parti.
            if (s.any { it.editable } && s.none { it.editable && it.normText.contains(wantedMessage) }) true else null
        } ?: throw StepFailed("vérifier l'envoi")

        return "J'ai envoyé « $message » à $contact sur ${t.app}."
    }

    // ── Scénario : chercher quelque chose dans une app ─────────────────────────

    private suspend fun searchFlow(
        context: Context,
        service: JarvisAccessibilityService,
        t: JarvisAction.RunTask
    ): String {
        val query = t.query.ifBlank { t.contact }.trim()
        val wanted = JarvisMemory.normalize(query)

        val pkg = openApp(context, service, t.app) ?: throw StepFailed("ouvrir ${t.app}")
        val search = openSearchField(service, pkg) ?: throw StepFailed("trouver la recherche de ${t.app}")
        val field = typeAndVerify(service, search, query) ?: throw StepFailed("écrire « $query » dans la recherche")

        if (t.submit) {
            val before = service.screenSignature()
            if (!service.pressEnter(field)) {
                // Pas de touche Entrée possible : bouton de recherche, sinon première suggestion.
                val btn = service.snapshot().firstOrNull {
                    !it.editable && (it.normText == "rechercher" || it.normText == "search" || it.normText == "go")
                } ?: service.snapshot().firstOrNull { !it.editable && it.normText.contains(wanted) }
                if (btn == null || !service.clickElement(btn)) throw StepFailed("valider la recherche")
            }
            waitFor(6000) { if (service.screenSignature() != before) true else null }
                ?: throw StepFailed("afficher les résultats")
            return "Voici les résultats pour $query dans ${t.app}."
        }

        // Recherche « en direct » (contacts, discussions) : on vérifie qu'un résultat correspond.
        val hit = waitFor(6000) {
            service.snapshot().firstOrNull { !it.editable && it.normText.contains(wanted) }
        } ?: return "Je n'ai trouvé aucun résultat pour $query dans ${t.app}."
        return "J'ai trouvé ${hit.text} dans ${t.app}."
    }

    // ── Scénario : Wi-Fi / Bluetooth ───────────────────────────────────────────

    suspend fun setToggle(context: Context, setting: String, on: Boolean): String =
        withContext(Dispatchers.Default) {
            val service = JarvisAccessibilityService.instance ?: return@withContext NO_SERVICE
            val (action, label) = when {
                setting.contains("wifi") || setting.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS to "Le Wi-Fi"
                setting.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS to "Le Bluetooth"
                else -> return@withContext "Je sais régler le Wi-Fi et le Bluetooth, pas « $setting »."
            }
            if (running) return@withContext "Je suis déjà occupé par une autre tâche."
            running = true
            cancelled = false
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val sw = waitFor(7000) { service.snapshot().firstOrNull { it.checkable } }
                    ?: return@withContext agentLoop(
                        context, service,
                        "${if (on) "Activer" else "Désactiver"} ${label.lowercase().removePrefix("le ")} dans les réglages",
                        "", "La page de réglages est ouverte mais je ne vois pas d'interrupteur."
                    )
                val state = if (on) "activé" else "désactivé"
                if (sw.checked == on) {
                    service.goBack()
                    return@withContext "$label est déjà $state."
                }
                service.clickElement(sw)
                val ok = waitFor(5000) {
                    if (service.snapshot().firstOrNull { it.checkable }?.checked == on) true else null
                }
                service.goBack()
                if (ok != null) "$label est maintenant $state."
                else "J'ai appuyé sur l'interrupteur mais je ne vois pas le changement. Vérifie sur l'écran."
            } catch (e: TaskCancelled) {
                "D'accord, j'arrête là."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Je n'ai pas pu régler ${label.lowercase()} : ${e.message ?: "erreur inconnue"}."
            } finally {
                running = false
                cancelled = false
            }
        }

    // ── Boucle adaptative : regarder -> décider -> agir -> vérifier ─────────────

    private suspend fun agentLoop(
        context: Context,
        service: JarvisAccessibilityService,
        goal: String,
        app: String,
        note: String,
        maxSteps: Int = 14
    ): String {
        val history = mutableListOf<String>()
        if (note.isNotBlank()) history.add(note)
        if (app.isNotBlank() && service.currentPackage().isEmpty()) {
            openApp(context, service, app)?.let { history.add("J'ai ouvert $app.") }
        }
        val goalNorm = JarvisMemory.normalize(goal)
        var noEffect = 0

        for (step in 1..maxSteps) {
            checkCancelled()
            val elements = service.snapshot(70)
            val pkg = service.currentPackage()
            val decision = withContext(Dispatchers.IO) {
                runCatching {
                    BackendClient.agentStep(goal, pkg, elementsJson(elements), history.takeLast(12), ApiKeyStore.requestExtras(context))
                }.getOrNull()
            } ?: return "Je n'arrive plus à joindre mon cerveau pour poursuivre la tâche."

            when (decision.action) {
                "done" -> return decision.say.ifBlank { "C'est fait." }
                "fail" -> return decision.say.ifBlank { "Je n'ai pas réussi : ${decision.reason}." }
            }

            val before = service.screenSignature()
            val target: UiElement? = decision.index?.let { elements.getOrNull(it) }
            var result: String
            when (decision.action) {
                "click" -> {
                    if (target == null) {
                        result = "échec : élément ${decision.index} introuvable"
                    } else if (isDangerous(target.normText, goalNorm)) {
                        result = "refusé par sécurité : « ${target.text} » demande une confirmation de l'utilisateur"
                    } else {
                        result = if (service.clickElement(target)) "ok" else "échec : clic impossible"
                    }
                }
                "type" -> {
                    val field = target?.takeIf { it.editable }
                        ?: service.snapshot().firstOrNull { it.editable }
                    if (field == null) {
                        result = "échec : aucun champ de saisie"
                    } else {
                        service.setText(field, decision.text)
                        val typed = waitFor(2500) {
                            service.snapshot().firstOrNull {
                                it.editable && it.normText.contains(JarvisMemory.normalize(decision.text))
                            }
                        }
                        result = if (typed != null) "ok, texte présent dans le champ" else "échec : le texte n'apparaît pas dans le champ"
                    }
                }
                "enter" -> {
                    val field = target?.takeIf { it.editable } ?: service.snapshot().firstOrNull { it.editable }
                    result = if (field != null && service.pressEnter(field)) "ok" else "échec : touche Entrée impossible"
                }
                "scroll" -> {
                    service.scroll(decision.direction.lowercase())
                    result = "ok"
                }
                "back" -> { service.goBack(); result = "ok" }
                "home" -> { service.goHome(); result = "ok" }
                "open_app" -> {
                    result = if (openApp(context, service, decision.app) != null) "ok" else "échec : application ${decision.app} introuvable"
                }
                "wait" -> { delay(1500); result = "ok" }
                else -> result = "action inconnue « ${decision.action} »"
            }

            // Vérification : l'écran a-t-il réagi ? (sauf pour « wait » et « type », déjà vérifiés)
            if (result.startsWith("ok") && decision.action !in listOf("wait", "type")) {
                val changed = waitFor(2500) { if (service.screenSignature() != before) true else null }
                if (changed == null) {
                    result = "aucun effet visible sur l'écran"
                    noEffect++
                } else noEffect = 0
            } else if (!result.startsWith("ok")) noEffect++ else noEffect = 0

            history.add("$step. ${describe(decision, target)} -> $result")
            if (noEffect >= 4) return "Je suis bloqué : plusieurs actions de suite n'ont rien changé à l'écran."
        }
        return "Je n'ai pas réussi à terminer en un nombre raisonnable d'étapes."
    }

    private fun describe(d: AgentDecision, target: UiElement?): String = when (d.action) {
        "click" -> "clic sur « ${target?.text ?: "#${d.index}"} »"
        "type" -> "saisie de « ${d.text} »"
        "scroll" -> "défilement ${d.direction}"
        "open_app" -> "ouverture de ${d.app}"
        else -> d.action
    }

    private fun elementsJson(elements: List<UiElement>): JSONArray {
        val arr = JSONArray()
        elements.forEachIndexed { i, e ->
            arr.put(JSONObject().apply {
                put("i", i)
                put("t", e.text.take(70).ifBlank { e.hint.take(70) })
                put("k", when {
                    e.editable -> "champ"
                    e.checkable -> "case"
                    e.clickable -> "bouton"
                    else -> "texte"
                })
                if (e.checkable) put("c", e.checked)
                if (e.editable && e.hint.isNotBlank()) put("hint", e.hint.take(40))
            })
        }
        return arr
    }

    private fun isDangerous(labelNorm: String, goalNorm: String): Boolean =
        DANGEROUS.any { labelNorm.contains(it) && !goalNorm.contains(it) }

    // ── Briques communes ───────────────────────────────────────────────────────

    private fun checkCancelled() {
        if (cancelled) throw TaskCancelled()
    }

    /** Réessaie [check] toutes les 300 ms jusqu'à ce qu'il renvoie autre chose que null, ou jusqu'au délai. */
    private suspend fun <T : Any> waitFor(timeoutMs: Long, intervalMs: Long = 300, check: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            checkCancelled()
            val r = runCatching { check() }.getOrNull()
            if (r != null) return r
            if (SystemClock.uptimeMillis() >= end) return null
            delay(intervalMs)
        }
    }

    /** Résout « WhatsApp » en nom de paquet : égalité du nom > début > contient. */
    private fun resolvePackage(context: Context, appName: String): String? {
        val pm = context.packageManager
        val wanted = JarvisMemory.normalize(appName)
        if (wanted.isBlank()) return null
        var best: String? = null
        var bestScore = 0
        for (info in pm.getInstalledApplications(0)) {
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue
            val label = JarvisMemory.normalize(pm.getApplicationLabel(info).toString())
            val score = when {
                label == wanted -> 100
                label.startsWith(wanted) -> 80
                label.contains(wanted) -> 60
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = info.packageName }
        }
        return best
    }

    /** Ouvre l'app et attend qu'elle soit réellement au premier plan. Renvoie son paquet, ou null. */
    private suspend fun openApp(context: Context, service: JarvisAccessibilityService, appName: String): String? {
        val pkg = resolvePackage(context, appName) ?: return null
        if (service.currentPackage() != pkg) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        waitFor(9000) { if (service.currentPackage() == pkg && service.snapshot(5).isNotEmpty()) true else null }
            ?: return null
        delay(500) // laisse l'écran d'accueil de l'app finir de se dessiner
        return pkg
    }

    private fun findSearchAffordance(service: JarvisAccessibilityService): UiElement? {
        val s = service.snapshot()
        s.firstOrNull { it.editable && (it.normText.contains("recherch") || it.hint.lowercase().let { h -> h.contains("recherch") || h.contains("search") }) }
            ?.let { return it }
        return s.firstOrNull {
            !it.editable && (it.normText.contains("rechercher") || it.normText.contains("recherche") || it.normText == "search" || it.normText.contains("search"))
        }
    }

    /**
     * Trouve le champ de recherche de l'app. Si l'app est restée dans une discussion ou un sous-écran,
     * on revient en arrière (sans quitter l'app) jusqu'à voir la recherche.
     */
    private suspend fun openSearchField(service: JarvisAccessibilityService, pkg: String): UiElement? {
        repeat(5) {
            checkCancelled()
            val aff = waitFor(2500) { findSearchAffordance(service) }
            if (aff != null) {
                if (aff.editable) return aff
                service.clickElement(aff)
                val field = waitFor(4000) { service.snapshot().firstOrNull { it.editable } }
                if (field != null) return field
            }
            service.goBack()
            delay(600)
            if (service.currentPackage() != pkg) return null
        }
        return null
    }

    /** Écrit [text] dans un champ et vérifie qu'il est bien affiché. Renvoie le champ, ou null. */
    private suspend fun typeAndVerify(service: JarvisAccessibilityService, field: UiElement, text: String): UiElement? {
        val wanted = JarvisMemory.normalize(text)
        repeat(3) {
            service.setText(field, text)
            val ok = waitFor(2500) {
                service.snapshot().firstOrNull { it.editable && it.normText.contains(wanted) }
            }
            if (ok != null) return ok
            val current = service.snapshot().firstOrNull { it.editable }
            if (current != null) service.setText(current, text)
        }
        return null
    }

    /**
     * Clique sur le résultat qui correspond le mieux à [contact] (nom exact > commence par > contient),
     * puis vérifie qu'on est dans la discussion : le champ de recherche a disparu, un champ de message
     * est là, et le nom du contact est affiché. Renvoie null si aucun résultat, false si la discussion
     * ne s'est pas ouverte.
     */
    private suspend fun openBestResult(service: JarvisAccessibilityService, contact: String): Boolean? {
        val wanted = JarvisMemory.normalize(contact)
        val result = waitFor(7000) {
            val candidates = service.snapshot().filter { !it.editable && it.normText.contains(wanted) }
            candidates.maxByOrNull {
                when {
                    it.normText == wanted -> 3
                    it.normText.startsWith(wanted) -> 2
                    else -> 1
                }
            }
        } ?: return null

        repeat(2) {
            service.clickElement(result)
            val inChat = waitFor(5000) {
                val s = service.snapshot()
                val field = s.firstOrNull { it.editable }
                // Dans la discussion : champ de message vide (≠ recherche) + nom du contact affiché.
                if (field != null && !field.normText.contains(wanted) && s.any { !it.editable && it.normText.contains(wanted) }) true else null
            }
            if (inChat != null) return true
        }
        return false
    }
}
