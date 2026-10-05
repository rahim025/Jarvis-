package com.jarvis.app.ai

/**
 * Commandes comprises directement par l'app : AUCUN appel à l'IA, donc 0 token consommé.
 * Tout ce qui est plus complexe (ou ambigu) renvoie null et part vers le cerveau comme avant.
 */
object LocalCommands {

    private fun has(n: String, vararg words: String): Boolean = words.any { n.contains(it) }

    private fun number(n: String): Int? =
        Regex("\\b(\\d{1,3})\\b").find(n)?.groupValues?.get(1)?.toIntOrNull()

    private fun wordCount(s: String): Int = s.split(" ").count { it.isNotBlank() }

    // Phrases enchaînant plusieurs actions : on laisse le cerveau s'en occuper.
    private val STEPS = listOf(" puis ", " ensuite ", " apres ")
    private val STEPS_ET = STEPS + " et "

    // Anciennes règles (commandes courtes) : on exclut les phrases qui ressemblent à une tâche complexe.
    private val COMPLEX = STEPS_ET + listOf(
        " cherche", " ecris", " envoie", " dans ", " sur ", " message", " appelle", " retiens", " oublie"
    )

    fun parse(text: String): List<JarvisAction>? {
        val t = text.trim()
        val n = JarvisMemory.normalize(t)
        if (n.isBlank()) return null
        val padded = " $n "
        val multi = STEPS.any { padded.contains(it) }
        val multiEt = STEPS_ET.any { padded.contains(it) }

        // ── SMS : « envoie un SMS à Crépin disant je suis en route » (texte d'origine : on garde le message tel quel)
        Regex("^envoie (?:un )?sms (?:à|a) (.+?) (?:disant|qui dit|que)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(t)?.let { m ->
                val contact = m.groupValues[1].trim()
                val message = m.groupValues[2].trim()
                if (contact.isNotBlank() && wordCount(contact) <= 4 && message.isNotBlank()) {
                    return listOf(JarvisAction.SendSms(contact, message))
                }
            }

        // ── Écrire dans le champ actif : « écris bonjour tout le monde »
        Regex("^[eéÉE]cris (.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            if (!multi && !has(n, "envoie", "message", "sms", "whatsapp", "messenger")) {
                return listOf(JarvisAction.TypeText(m.groupValues[1].trim()))
            }
        }

        // ── Ouvrir un site : « ouvre le site google.com »
        Regex("^ouvre (?:le )?site (\\S+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.let { m ->
            return listOf(JarvisAction.OpenUrl(m.groupValues[1]))
        }

        if (multi) return null

        // ── Musique : « joue Burna Boy », « joue Calm Down sur YouTube »
        Regex("^joue (.+?)(?: sur (youtube|spotify))?$").matchEntire(n)?.let { m ->
            val query = m.groupValues[1].trim()
            if (query.isNotBlank() && wordCount(query) <= 8) {
                return listOf(JarvisAction.PlayMusic(query, m.groupValues[2]))
            }
        }

        if (!multiEt) {
            // ── Appels : « appelle Crépin », « appelle Crépin sur WhatsApp », « … en vidéo sur WhatsApp »
            Regex("^appelle (.+?)(?: en video)?(?: sur whatsapp)?$").matchEntire(n)?.let { m ->
                val name = m.groupValues[1].trim()
                if (name.isNotBlank() && wordCount(name) <= 4) {
                    val whatsapp = n.endsWith("whatsapp")
                    return if (whatsapp) listOf(JarvisAction.WhatsAppCall(name, has(n, " en video")))
                    else listOf(JarvisAction.CallContact(name, ""))
                }
            }

            // ── Navigation : « emmène-moi à Cotonou »
            Regex("^(?:emmene moi|amene moi|conduis moi|navigue vers|itineraire vers|guide moi vers) (?:a |au |aux |vers |chez )?(.+)$")
                .matchEntire(n)?.let { m ->
                    val dest = m.groupValues[1].trim()
                    if (dest.isNotBlank()) return listOf(JarvisAction.Navigate(dest))
                }

            // ── Wi-Fi / Bluetooth : « active le Wi-Fi », « désactive le Bluetooth »
            if (has(n, "wifi", "wi fi", "bluetooth") && wordCount(n) <= 6) {
                val off = has(n, "desactive", "eteins", "eteint", "coupe", "arrete")
                val on = has(n, "active", "allume", "mets", "lance")
                if (off || on) {
                    val setting = if (has(n, "bluetooth")) "bluetooth" else "wifi"
                    return listOf(JarvisAction.SetToggle(setting, !off))
                }
            }

            // ── Défilement : « défile vers le bas »
            if (n.startsWith("defile")) {
                val dir = when {
                    has(n, "bas") -> "down"
                    has(n, "haut") -> "up"
                    has(n, "gauche") -> "left"
                    has(n, "droite") -> "right"
                    else -> null
                }
                if (dir != null) return listOf(JarvisAction.Scroll(dir))
            }

            // ── Clic : « clique sur Envoyer »
            Regex("^clique sur (.+)$").matchEntire(n)?.let { m ->
                val label = m.groupValues[1].trim()
                if (label.isNotBlank() && wordCount(label) <= 5) return listOf(JarvisAction.ClickOnScreen(label))
            }

            // ── Haut-parleur
            if (has(n, "haut parleur")) {
                val off = has(n, "coupe", "desactive", "eteins", "arrete")
                return listOf(JarvisAction.Speaker(!off))
            }

            // ── Mémoire
            if (has(n, "sais sur moi", "sais de moi")) return listOf(JarvisAction.ListMemory)
        }

        // ── Commandes courtes (anciennes règles)
        if (wordCount(n) > 8) return null
        if (COMPLEX.any { padded.contains(it) }) return null

        // Lampe torche
        if (has(n, "lampe", "torche")) {
            val off = has(n, "eteins", "eteint", "coupe", "desactive", "arrete")
            return listOf(JarvisAction.Flashlight(!off))
        }

        // Luminosité (avec un chiffre)
        if (has(n, "luminosite")) {
            val pct = number(n) ?: return null
            return listOf(JarvisAction.SetBrightness(pct))
        }

        // Volume
        if (has(n, "volume") || has(n, "coupe le son", "mute")) {
            val pct = number(n)
            return when {
                has(n, "volume") && pct != null -> listOf(JarvisAction.SetVolume("set", pct))
                has(n, "baisse", "diminue", "moins", "reduis") -> listOf(JarvisAction.SetVolume("down", null))
                has(n, "augmente", "monte", "plus fort") -> listOf(JarvisAction.SetVolume("up", null))
                has(n, "coupe", "mute", "muet") -> listOf(JarvisAction.SetVolume("mute", null))
                else -> null
            }
        }

        // Musique / médias
        if (has(n, "piste suivante", "chanson suivante")) return listOf(JarvisAction.MediaControl("next"))
        if (has(n, "piste precedente", "chanson precedente")) return listOf(JarvisAction.MediaControl("prev"))
        if (n == "pause" || n == "mets pause" || n == "mets en pause") return listOf(JarvisAction.MediaControl("pause"))

        // Navigation dans le téléphone
        if (has(n, "accueil")) return listOf(JarvisAction.GoHome)
        if (has(n, "arriere") || n == "retour") return listOf(JarvisAction.GoBack)

        // Minuteur : « lance un minuteur de 10 minutes »
        if (has(n, "minuteur", "timer")) {
            val v = number(n) ?: return null
            val seconds = if (has(n, "seconde")) v else v * 60
            return listOf(JarvisAction.SetTimer(seconds, "Jarvis"))
        }

        // Alarme : « mets une alarme à 7 heures 30 »
        if (has(n, "alarme", "reveil")) {
            val m = Regex("(\\d{1,2})\\s*(?:heures?|h)\\s*(\\d{1,2})?").find(n) ?: return null
            val hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            return listOf(JarvisAction.SetAlarm(hour, minute, "Jarvis"))
        }

        // État du téléphone, appels
        if (has(n, "etat du telephone", "batterie")) return listOf(JarvisAction.DeviceStatus)
        if (n.startsWith("raccroche")) return listOf(JarvisAction.EndCall)
        if (has(n, "rappelle") && has(n, "dernier numero")) return listOf(JarvisAction.Redial)

        // Ouvrir une application : « ouvre WhatsApp »
        if (n.startsWith("ouvre ") && !has(n, "site", "page", "parametres", "reglages", "commandes")) {
            val name = n.removePrefix("ouvre ")
                .removePrefix("l application ").removePrefix("l ")
                .removePrefix("la ").removePrefix("le ").removePrefix("les ")
                .trim()
            if (name.isNotBlank() && wordCount(name) <= 3) return listOf(JarvisAction.OpenApp(name))
        }

        return null
    }
}
