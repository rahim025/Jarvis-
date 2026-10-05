package com.jarvis.app.ai

import android.content.Context
import com.jarvis.app.CommandsActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * CATALOGUE DES COMMANDES — la seule liste que Jarvis affiche quand on dit « Affiche les commandes ».
 *
 * ➜ POUR AJOUTER UNE NOUVELLE CAPACITÉ : ajoute son outil dans backend/server.js, son parsing dans
 *   BackendClient.parseAction, puis UNE ligne « cmd » (nom de l'outil en premier argument) ci-dessous.
 *   Le script scripts/check-commands.js (lancé par GitHub Actions avant la compilation) fait échouer
 *   le build si un outil n'a pas de commande ici : la liste affichée ne peut donc pas être en retard.
 *
 * Les textes entre crochets, ex. [texte], sont à remplir : en touchant la commande, Jarvis demande la valeur.
 * {app} est remplacé par le nom de l'application (WhatsApp, YouTube, ou celle demandée dans le filtre).
 */
enum class CommandCategory(val emoji: String, val title: String, val key: String, val aliases: List<String> = emptyList()) {
    TASKS("🤖", "Tâches en plusieurs étapes", "taches", listOf("tache", "etapes", "enchainement")),
    APPS("📱", "Applications", "applications", listOf("application", "apps", "app", "applis", "appli")),
    MESSAGES("💬", "Messages", "messages", listOf("message", "sms", "discussion")),
    CALLS("📞", "Appels", "appels", listOf("appel", "telephone", "appeler")),
    SEARCH("🔎", "Recherche", "recherche", listOf("recherches", "chercher", "internet", "web")),
    SYSTEM("⚙️", "Système", "systeme", listOf("reglages", "parametres", "telephone")),
    MEDIA("🎵", "Musique et médias", "musique", listOf("media", "medias", "son", "audio")),
    TIME("⏰", "Alarmes et minuteurs", "alarmes", listOf("alarme", "minuteur", "minuteurs", "rappel", "rappels", "heure")),
    INFO("🌍", "Infos et navigation", "infos", listOf("info", "meteo", "navigation", "gps", "actualites")),
    SCREEN("🖐️", "Contrôle de l'écran", "ecran", listOf("ecrans", "clic", "defilement")),
    MEMORY("🧠", "Mémoire", "memoire", listOf("souvenirs", "retenir")),
    HELP("❓", "Aide", "aide", listOf("commandes"))
}

data class Command(
    /** Nom de l'outil du backend (server.js) que cette commande déclenche. */
    val tool: String,
    val category: CommandCategory,
    val text: String,
    val tags: List<String>,
    /** Si non vide : modèle avec {app}, affiché pour chacune de ces apps (ou pour celle du filtre). */
    val defaultApps: List<String>,
    val messagingOnly: Boolean
) {
    val perApp: Boolean get() = defaultApps.isNotEmpty()
    fun render(app: String): String = text.replace("{app}", app)
}

data class CommandSection(val title: String, val commands: List<String>)

data class CommandView(val title: String, val sections: List<CommandSection>) {
    val total: Int get() = sections.sumOf { it.commands.size }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("sections", JSONArray().apply {
            sections.forEach { s ->
                put(JSONObject().put("title", s.title).put("commands", JSONArray(s.commands)))
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): CommandView {
            val arr = json.optJSONArray("sections") ?: JSONArray()
            val sections = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val cmds = o.optJSONArray("commands") ?: JSONArray()
                CommandSection(o.optString("title"), (0 until cmds.length()).map { cmds.getString(it) })
            }
            return CommandView(json.optString("title"), sections)
        }
    }
}

object CommandCatalog {

    private val MESSAGING_APPS = listOf(
        "whatsapp", "telegram", "messenger", "signal", "messages", "instagram", "snapchat", "viber", "discord"
    )

    private fun cmd(
        tool: String,
        cat: CommandCategory,
        text: String,
        tags: List<String> = emptyList(),
        apps: List<String> = emptyList(),
        messagingOnly: Boolean = false
    ) = Command(tool, cat, text, tags, apps, messagingOnly)

    private val ALL: List<Command> = listOf(
        // 🤖 Tâches en plusieurs étapes
        cmd("run_task", CommandCategory.TASKS, "Ouvre WhatsApp, cherche Crépin, écris “Salut” et envoie le message", tags = listOf("whatsapp", "message", "etapes")),
        cmd("run_task", CommandCategory.TASKS, "Dans {app}, [décris ce que je dois faire]", apps = listOf("YouTube")),

        // 📱 Applications
        cmd("open_app", CommandCategory.APPS, "Ouvre {app}", apps = listOf("WhatsApp", "YouTube")),
        cmd("open_app", CommandCategory.APPS, "Ouvre les paramètres", tags = listOf("reglages", "parametres")),
        cmd("close_app", CommandCategory.APPS, "Ferme l'application"),
        cmd("go_home", CommandCategory.APPS, "Retourne à l'accueil", tags = listOf("accueil")),
        cmd("go_back", CommandCategory.APPS, "Reviens en arrière", tags = listOf("retour")),

        // 💬 Messages
        cmd("run_task", CommandCategory.MESSAGES, "Envoie “Salut” à Crépin sur {app}", apps = listOf("WhatsApp"), messagingOnly = true),
        cmd("send_sms", CommandCategory.MESSAGES, "Envoie un SMS “Je suis en route” à Crépin", tags = listOf("sms")),

        // 📞 Appels
        cmd("call_contact", CommandCategory.CALLS, "Appelle Crépin", tags = listOf("telephone")),
        cmd("whatsapp_call", CommandCategory.CALLS, "Appelle Crépin sur WhatsApp", tags = listOf("whatsapp")),
        cmd("whatsapp_call", CommandCategory.CALLS, "Appelle Crépin en vidéo sur WhatsApp", tags = listOf("whatsapp", "video")),
        cmd("redial", CommandCategory.CALLS, "Rappelle le dernier numéro"),
        cmd("end_call", CommandCategory.CALLS, "Raccroche"),
        cmd("speaker", CommandCategory.CALLS, "Active le haut-parleur", tags = listOf("haut parleur")),
        cmd("speaker", CommandCategory.CALLS, "Coupe le haut-parleur", tags = listOf("haut parleur")),

        // 🔎 Recherche
        cmd("run_task", CommandCategory.SEARCH, "Cherche Crépin dans {app}", apps = listOf("WhatsApp"), messagingOnly = true),
        cmd("run_task", CommandCategory.SEARCH, "Recherche [texte] sur {app}", apps = listOf("YouTube")),
        cmd("web_search", CommandCategory.SEARCH, "Cherche sur internet [ta question]", tags = listOf("web", "google", "actualites")),
        cmd("open_url", CommandCategory.SEARCH, "Ouvre le site [adresse du site]", tags = listOf("web", "navigateur")),

        // ⚙️ Système
        cmd("set_toggle", CommandCategory.SYSTEM, "Active le Wi-Fi", tags = listOf("wifi", "wi fi", "internet")),
        cmd("set_toggle", CommandCategory.SYSTEM, "Désactive le Bluetooth", tags = listOf("bluetooth")),
        cmd("set_volume", CommandCategory.SYSTEM, "Augmente le volume", tags = listOf("son")),
        cmd("set_volume", CommandCategory.SYSTEM, "Baisse le volume", tags = listOf("son")),
        cmd("set_volume", CommandCategory.SYSTEM, "Mets le volume à 50 pour cent", tags = listOf("son")),
        cmd("flashlight", CommandCategory.SYSTEM, "Allume la lampe torche", tags = listOf("lampe", "torche", "lumiere")),
        cmd("flashlight", CommandCategory.SYSTEM, "Éteins la lampe torche", tags = listOf("lampe", "torche", "lumiere")),
        cmd("set_brightness", CommandCategory.SYSTEM, "Mets la luminosité à 70 pour cent", tags = listOf("ecran")),
        cmd("device_status", CommandCategory.SYSTEM, "Donne-moi l'état du téléphone", tags = listOf("batterie", "memoire", "stockage", "ram")),

        // 🎵 Musique et médias
        cmd("media_control", CommandCategory.MEDIA, "Mets pause", tags = listOf("lecture", "play")),
        cmd("media_control", CommandCategory.MEDIA, "Piste suivante", tags = listOf("suivant")),
        cmd("media_control", CommandCategory.MEDIA, "Piste précédente", tags = listOf("precedent")),
        cmd("play_music", CommandCategory.MEDIA, "Joue [chanson ou artiste]", tags = listOf("spotify", "youtube", "chanson")),

        // ⏰ Alarmes et minuteurs
        cmd("set_alarm", CommandCategory.TIME, "Mets une alarme à 7 heures", tags = listOf("reveil")),
        cmd("set_timer", CommandCategory.TIME, "Lance un minuteur de 10 minutes", tags = listOf("chrono")),

        // 🌍 Infos et navigation
        cmd("get_weather", CommandCategory.INFO, "Quelle est la météo aujourd'hui ?", tags = listOf("temps", "pluie")),
        cmd("navigate", CommandCategory.INFO, "Emmène-moi à [lieu]", tags = listOf("gps", "maps", "itineraire")),
        cmd("tiktok_followers", CommandCategory.INFO, "Combien ai-je d'abonnés TikTok ?", tags = listOf("tiktok")),

        // 🖐️ Contrôle de l'écran
        cmd("describe_screen", CommandCategory.SCREEN, "Qu'est-ce qui est affiché sur l'écran ?", tags = listOf("regarde", "lis")),
        cmd("click_on_screen", CommandCategory.SCREEN, "Clique sur [texte du bouton]", tags = listOf("appuie")),
        cmd("type_text", CommandCategory.SCREEN, "Écris [texte]", tags = listOf("tape", "saisie")),
        cmd("scroll_down", CommandCategory.SCREEN, "Défile vers le bas", tags = listOf("scroll")),
        cmd("scroll_up", CommandCategory.SCREEN, "Défile vers le haut", tags = listOf("scroll")),
        cmd("scroll_left", CommandCategory.SCREEN, "Défile vers la gauche", tags = listOf("scroll")),
        cmd("scroll_right", CommandCategory.SCREEN, "Défile vers la droite", tags = listOf("scroll")),

        // 🧠 Mémoire
        cmd("memorize", CommandCategory.MEMORY, "Retiens que [information]", tags = listOf("souviens")),
        cmd("forget", CommandCategory.MEMORY, "Oublie [information]"),
        cmd("list_memory", CommandCategory.MEMORY, "Qu'est-ce que tu sais sur moi ?"),

        // ❓ Aide
        cmd("show_commands", CommandCategory.HELP, "Affiche les commandes"),
        cmd("show_commands", CommandCategory.HELP, "Cherche les commandes pour WhatsApp", tags = listOf("whatsapp"))
    )

    /** Noms des outils couverts par le catalogue (vérifié au build par scripts/check-commands.js). */
    val toolNames: Set<String> get() = ALL.map { it.tool }.toSet()

    // ── Reconnaissance de la demande « affiche les commandes » ────────────────────

    private val TRIGGER = Regex("\\b(affiche|afficher|affichez|montre|montrer|montrez|voir|vois|liste|lister|cherche|chercher|recherche|donne|donner|dis|quelles|quelle|disponibles)\\b")
    private val STOP = setOf(
        "pour", "de", "du", "des", "d", "sur", "dans", "concernant", "lie", "liee", "liees", "lies",
        "relatives", "relative", "a", "au", "aux", "la", "le", "les", "l", "disponibles", "disponible",
        "moi", "toutes", "tout", "s", "il", "te", "plait", "svp", "stp", "vous", "jarvis", "qui", "et",
        "avec", "ma", "mon", "mes", "en", "ce", "que", "tu", "peux", "sont"
    )

    /**
     * Renvoie null si [spoken] n'est pas une demande de liste de commandes ; sinon le filtre demandé
     * ("" = toutes les commandes, "whatsapp" = « Cherche les commandes pour WhatsApp »).
     */
    fun parseShowRequest(spoken: String): String? {
        val n = JarvisMemory.normalize(spoken)
        if (!Regex("\\bcommandes\\b").containsMatchIn(n)) return null
        if (!TRIGGER.containsMatchIn(n)) return null
        val tail = n.substringAfter("commandes")
        return tail.split(" ").filter { it.isNotBlank() && it !in STOP }.joinToString(" ")
    }

    // ── Construction de la liste affichée ────────────────────────────────────────

    /**
     * [filter] vide : toutes les commandes, par catégorie. Sinon : la catégorie demandée (« système »),
     * ou les commandes liées au mot (« wifi », « batterie »), ou celles d'une application installée
     * (« WhatsApp »), grâce à [installedAppLabel] qui renvoie le vrai nom de l'app si elle existe.
     */
    fun build(filter: String, installedAppLabel: (String) -> String?): CommandView {
        val nf = JarvisMemory.normalize(filter)
        if (nf.isBlank()) {
            return CommandView("Toutes les commandes", group(ALL.flatMap { expand(it, null) }))
        }
        // Mots de 3 lettres minimum : « fi » ou « de » ne doivent pas ramener des commandes au hasard.
        val tokens = nf.split(" ").filter { it.length >= 3 }

        val categories = CommandCategory.values().filter { cat ->
            val words = listOf(cat.key) + cat.aliases
            tokens.any { t -> words.any { w -> w == t || (t.length >= 4 && w.startsWith(t)) } }
        }
        if (categories.isNotEmpty()) {
            val items = ALL.filter { it.category in categories }.flatMap { expand(it, null) }
            return CommandView(categories.joinToString(" / ") { it.title }, group(items))
        }

        val appLabel = installedAppLabel(filter)
        val direct = ALL.filter { c ->
            val text = JarvisMemory.normalize(c.text)
            val tags = c.tags.map { JarvisMemory.normalize(it) }
            !c.perApp && (
                text.contains(nf) || tags.any { it.contains(nf) } ||
                    tokens.any { t -> text.contains(t) || tags.any { tag -> tag.contains(t) } }
                )
        }
        val templates = if (appLabel != null) {
            val messaging = MESSAGING_APPS.any { JarvisMemory.normalize(appLabel).contains(it) }
            ALL.filter { it.perApp && (!it.messagingOnly || messaging) }
        } else emptyList()

        val items = (direct.flatMap { expand(it, null) } + templates.flatMap { expand(it, appLabel) })
        return CommandView(appLabel ?: filter, group(items))
    }

    private fun expand(c: Command, app: String?): List<Pair<CommandCategory, String>> =
        if (!c.perApp) listOf(c.category to c.text)
        else (if (app != null) listOf(app) else c.defaultApps).map { c.category to c.render(it) }

    private fun group(items: List<Pair<CommandCategory, String>>): List<CommandSection> =
        CommandCategory.values().mapNotNull { cat ->
            val cmds = items.filter { it.first == cat }.map { it.second }.distinct()
            if (cmds.isEmpty()) null else CommandSection("${cat.emoji} ${cat.title}", cmds)
        }
}

/**
 * Pont entre l'écran des commandes et le contrôleur de conversation actif : toucher une commande
 * l'exécute exactement comme si l'utilisateur l'avait dite à voix haute.
 */
object CommandBus {
    @Volatile var runner: ((String) -> Unit)? = null
}

object CommandsLauncher {

    /** Ouvre l'écran des commandes (filtré ou non) et renvoie la phrase que Jarvis dit à voix haute. */
    fun show(context: Context, filter: String): String {
        val view = CommandCatalog.build(filter) { installedAppLabel(context, it) }
        if (view.sections.isEmpty()) {
            return "Je n'ai aucune commande pour ${filter.ifBlank { "cela" }}. Dis « affiche les commandes » pour tout voir."
        }
        context.startActivity(CommandsActivity.intent(context, view))
        return if (filter.isBlank()) "Voici les ${view.total} commandes disponibles, regroupées par catégories."
        else "Voici les commandes pour ${view.title}."
    }

    private fun installedAppLabel(context: Context, spoken: String): String? {
        val pm = context.packageManager
        val wanted = JarvisMemory.normalize(spoken)
        if (wanted.isBlank()) return null
        var best: String? = null
        var bestScore = 0
        for (info in pm.getInstalledApplications(0)) {
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue
            val label = pm.getApplicationLabel(info).toString()
            val n = JarvisMemory.normalize(label)
            val score = when {
                n == wanted -> 3
                n.startsWith(wanted) -> 2
                n.contains(wanted) -> 1
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = label }
        }
        return best
    }
}
