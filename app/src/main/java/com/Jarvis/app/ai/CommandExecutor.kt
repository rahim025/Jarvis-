package com.jarvis.app.ai

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.app.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reçoit les JarvisAction décidées par le cerveau (Groq) et les exécute réellement.
 * Trois façons d'agir :
 *  - Intents Android classiques (SMS, appel, alarme, navigation, musique...)
 *  - JarvisAccessibilityService (cliquer sur l'écran, taper du texte, capture d'écran)
 *  - Services système (volume, torche, luminosité, batterie)
 * Retourne un texte à dire à l'utilisateur en confirmation.
 */
object CommandExecutor {

    /** Exécute toutes les actions dans l'ordre et assemble les réponses en une phrase. */
    suspend fun executeAll(context: Context, actions: List<JarvisAction>): String {
        val replies = mutableListOf<String>()
        for (action in actions) {
            val reply = runCatching { execute(context, action) }
                .getOrElse { "Je n'ai pas pu terminer cette action." }
            if (reply.isNotBlank()) replies.add(reply)
        }
        return replies.joinToString(" ").ifBlank { "C'est fait." }
    }

    suspend fun execute(context: Context, action: JarvisAction): String {
        return when (action) {
            is JarvisAction.Speak -> action.text

            is JarvisAction.OpenApp -> {
                val pm = context.packageManager
                val pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(action.appName, ignoreCase = true)
                }
                val launchIntent = pkg?.let { pm.getLaunchIntentForPackage(it.packageName) }
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    "J'ouvre ${action.appName}."
                } else {
                    "Je ne trouve pas l'application ${action.appName}."
                }
            }

            is JarvisAction.SendSms -> {
                // Ouvre l'app SMS pré-remplie ; pour un envoi 100% silencieux il faudrait
                // SmsManager + la permission SEND_SMS déjà déclarée dans le manifest.
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${action.contact}")
                    putExtra("sms_body", action.message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "SMS prêt pour ${action.contact}."
            }

            is JarvisAction.CallContact -> callContact(context, action.contact, action.app)

            is JarvisAction.WhatsAppCall -> whatsappCall(context, action.contact, action.video)

            JarvisAction.Redial -> redial(context)

            JarvisAction.EndCall -> endCall(context)

            is JarvisAction.Speaker -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = action.on
                if (action.on) "Haut-parleur activé." else "Haut-parleur coupé."
            }

            is JarvisAction.ClickOnScreen -> {
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."
                } else {
                    val ok = service.clickByLabel(action.label)
                    if (ok) "Fait." else "Je ne trouve pas '${action.label}' à l'écran."
                }
            }

            is JarvisAction.TypeText -> {
                val service = JarvisAccessibilityService.instance
                if (service?.typeInFocusedField(action.text) == true) "Texte tapé." else "Impossible de taper le texte."
            }

            JarvisAction.GoHome -> {
                JarvisAccessibilityService.instance?.goHome()
                "Retour à l'accueil."
            }

            JarvisAction.GoBack -> {
                JarvisAccessibilityService.instance?.goBack()
                "Retour en arrière."
            }

            JarvisAction.CloseApp -> {
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."
                } else {
                    service.closeCurrentApp()
                    "J'ai fermé l'application."
                }
            }

            is JarvisAction.Scroll -> {
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."
                } else {
                    service.scroll(action.direction)
                    "Fait."
                }
            }

            // ── Mémoire d'éléphant ─────────────────────────────────────────────

            is JarvisAction.Memorize -> {
                if (action.key.isBlank() || action.value.isBlank()) {
                    "Je n'ai pas compris ce qu'il faut retenir."
                } else {
                    JarvisMemory.get(context).saveFact(action.key, action.value)
                    "C'est noté, je m'en souviendrai."
                }
            }

            is JarvisAction.Forget -> {
                if (JarvisMemory.get(context).deleteFact(action.key)) "C'est oublié."
                else "Je n'avais pas cette information en mémoire."
            }

            JarvisAction.ListMemory -> {
                val facts = JarvisMemory.get(context).allFacts()
                if (facts.isEmpty()) {
                    "Je n'ai encore aucun fait précis en mémoire."
                } else {
                    val spoken = facts.takeLast(10).joinToString(". ") { "${it.first} : ${it.second}" }
                    "J'ai ${facts.size} informations en mémoire. $spoken."
                }
            }

            // ── Fonctions portées de la version Windows ────────────────────────

            is JarvisAction.SetVolume -> setVolume(context, action)

            is JarvisAction.MediaControl -> mediaControl(context, action.command)

            is JarvisAction.PlayMusic -> playMusic(context, action)

            is JarvisAction.Flashlight -> flashlight(context, action.on)

            is JarvisAction.SetBrightness -> setBrightness(context, action.percent)

            is JarvisAction.SetAlarm -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, action.hour.coerceIn(0, 23))
                    putExtra(AlarmClock.EXTRA_MINUTES, action.minute.coerceIn(0, 59))
                    putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val mm = if (action.minute > 0) " ${action.minute}" else ""
                "Alarme réglée à ${action.hour} heures$mm."
            }

            is JarvisAction.SetTimer -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, action.seconds.coerceAtLeast(1))
                    putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val s = action.seconds
                val spoken = if (s >= 60) "${s / 60} minutes" + (if (s % 60 > 0) " ${s % 60} secondes" else "") else "$s secondes"
                "Minuteur lancé pour $spoken."
            }

            is JarvisAction.OpenUrl -> {
                val url = if (action.url.startsWith("http")) action.url else "https://${action.url}"
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "J'ouvre la page."
            }

            is JarvisAction.Navigate -> {
                val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(action.destination)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { context.startActivity(nav) }.isSuccess
                if (!ok) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.destination)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                "Je lance la navigation vers ${action.destination}."
            }

            JarvisAction.DeviceStatus -> deviceStatus(context)

            is JarvisAction.DescribeScreen -> {
                val service = JarvisAccessibilityService.instance
                if (service == null) {
                    "Le contrôle d'écran n'est pas activé. Active-le dans les paramètres d'accessibilité."
                } else {
                    withContext(Dispatchers.IO) {
                        val b64 = service.captureScreenBase64()
                        if (b64 == null) {
                            "Je n'arrive pas à capturer l'écran. Il faut Android 11 ou plus, et le contrôle d'écran activé."
                        } else {
                            BackendClient.analyzeImage(action.question, b64, "image/jpeg")
                        }
                    }
                }
            }
        }
    }

    // ── Détails des fonctions système ──────────────────────────────────────────

    private fun setVolume(context: Context, a: JarvisAction.SetVolume): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val dir = a.direction.lowercase()
        val step = (max * 0.15).toInt().coerceAtLeast(1)
        val target = when {
            a.percent != null -> (max * a.percent.coerceIn(0, 100) / 100.0).toInt()
            listOf("mute", "muet", "coupe").any { dir.contains(it) } -> 0
            listOf("up", "mont", "augment", "plus", "fort").any { dir.contains(it) } -> cur + step
            listOf("down", "baiss", "diminu", "moins", "doux").any { dir.contains(it) } -> cur - step
            else -> cur
        }.coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Volume à ${if (max > 0) target * 100 / max else 0} pour cent."
    }

    private fun mediaControl(context: Context, command: String): String {
        val c = command.lowercase()
        val (code, label) = when {
            listOf("next", "suiv").any { c.contains(it) } -> KeyEvent.KEYCODE_MEDIA_NEXT to "Piste suivante."
            listOf("prev", "préc", "prec", "retour").any { c.contains(it) } -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Piste précédente."
            c.contains("stop") -> KeyEvent.KEYCODE_MEDIA_STOP to "Musique arrêtée."
            c.contains("pause") -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Pause."
            c == "play" || c.contains("lecture") || c.contains("reprend") -> KeyEvent.KEYCODE_MEDIA_PLAY to "Lecture."
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "C'est fait."
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return label
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun playMusic(context: Context, a: JarvisAction.PlayMusic): String {
        val wantsYoutube = a.app.contains("youtube", ignoreCase = true)
        if (!wantsYoutube && isInstalled(context, "com.spotify.music")) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.spotify.music")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, a.query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return "Je lance ${a.query} sur Spotify."
        }
        val url = "https://www.youtube.com/results?search_query=${Uri.encode(a.query)}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Je cherche ${a.query} sur YouTube."
    }

    private fun flashlight(context: Context, on: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Ce téléphone n'a pas de lampe torche."
        cm.setTorchMode(id, on)
        return if (on) "Lampe allumée." else "Lampe éteinte."
    }

    private fun setBrightness(context: Context, percent: Int): String {
        if (!Settings.System.canWrite(context)) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Autorise Jarvis à modifier les réglages système, puis redemande-moi."
        }
        val value = (percent.coerceIn(1, 100) * 255 / 100)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return "Luminosité à $percent pour cent."
    }

    private fun deviceStatus(context: Context): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramUsed = if (mem.totalMem > 0) ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt() else 0

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000L

        val batteryText = if (pct >= 0) "Batterie à $pct pour cent${if (charging) ", en charge" else ""}." else "Batterie inconnue."
        return "$batteryText Mémoire vive utilisée à $ramUsed pour cent. Il reste $freeGb giga de stockage."
    }

    // ── Appels ─────────────────────────────────────────────────────────────────

    private data class ContactHit(val name: String, val number: String)

    private fun hasPerm(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /** « mon amour » -> « Ma vie » : un surnom retenu en mémoire (« retiens que mon amour = Ma vie »). */
    private fun resolveAlias(context: Context, spoken: String): String {
        val key = JarvisMemory.normalize(spoken)
        val facts = runCatching { JarvisMemory.get(context).allFacts() }.getOrDefault(emptyList())
        val hit = facts.firstOrNull {
            val k = JarvisMemory.normalize(it.first)
            k == key || k == "contact $key" || k == "appeler $key"
        }
        return hit?.second ?: spoken
    }

    /** Un numéro dicté (« 01 97 12 34 56 », « +229 ... ») est utilisé tel quel. */
    private fun asPhoneNumber(text: String): String? {
        val cleaned = text.replace(Regex("[\\s.\\-()]"), "")
        return if (Regex("^\\+?[0-9]{6,15}$").matches(cleaned)) cleaned else null
    }

    /** Cherche le meilleur contact pour un nom dit à voix haute (insensible aux accents et à la casse). */
    private fun findContact(context: Context, spoken: String): ContactHit? {
        if (!hasPerm(context, android.Manifest.permission.READ_CONTACTS)) return null
        val wanted = JarvisMemory.normalize(spoken)
        if (wanted.isBlank()) return null
        val wantedTokens = wanted.split(" ").filter { it.isNotBlank() }

        var best: ContactHit? = null
        var bestScore = 0
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val norm = JarvisMemory.normalize(name)
                val score = when {
                    norm == wanted -> 100
                    norm.startsWith(wanted) -> 80
                    wantedTokens.all { norm.contains(it) } -> 60
                    norm.contains(wanted) -> 50
                    wantedTokens.any { it.length >= 3 && norm.split(" ").contains(it) } -> 30
                    else -> 0
                } + (if (c.getInt(2) > 0) 1 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = ContactHit(name, number)
                }
            }
        }
        return if (bestScore >= 30) best else null
    }

    private fun callContact(context: Context, spoken: String, app: String): String {
        if (app.contains("whatsapp", ignoreCase = true)) return whatsappCall(context, spoken, false)
        if (!hasPerm(context, android.Manifest.permission.CALL_PHONE)) {
            return "Autorise l'appel téléphonique dans les paramètres de l'application, puis réessaie."
        }
        val target = resolveAlias(context, spoken)
        val direct = asPhoneNumber(target)
        val hit = if (direct == null) findContact(context, target) else null
        val number = direct ?: hit?.number
            ?: return if (!hasPerm(context, android.Manifest.permission.READ_CONTACTS))
                "Autorise l'accès aux contacts pour que je puisse appeler $spoken."
            else "Je ne trouve personne qui s'appelle $spoken dans tes contacts."

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.fromParts("tel", number, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "J'appelle ${hit?.name ?: spoken}."
    }

    /** Appel WhatsApp (voix ou vidéo) via les entrées WhatsApp du carnet de contacts. */
    private fun whatsappCall(context: Context, spoken: String, video: Boolean): String {
        if (!hasPerm(context, android.Manifest.permission.READ_CONTACTS)) {
            return "Autorise l'accès aux contacts pour que je puisse appeler sur WhatsApp."
        }
        val wanted = JarvisMemory.normalize(resolveAlias(context, spoken))
        val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

        var bestId = -1L
        var bestName = spoken
        var bestScore = 0
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(mime), null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val norm = JarvisMemory.normalize(name)
                val score = when {
                    norm == wanted -> 100
                    norm.startsWith(wanted) -> 80
                    norm.contains(wanted) -> 60
                    wanted.split(" ").all { it.isNotBlank() && norm.contains(it) } -> 50
                    else -> 0
                }
                if (score > bestScore) { bestScore = score; bestId = c.getLong(0); bestName = name }
            }
        }
        if (bestId < 0 || bestScore < 50) {
            return "Je ne trouve pas $spoken parmi tes contacts WhatsApp."
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.android.contacts/data/$bestId"), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // WhatsApp classique, puis WhatsApp Business.
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (!isInstalled(context, pkg)) continue
            intent.setPackage(pkg)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return if (video) "J'appelle $bestName en vidéo sur WhatsApp." else "J'appelle $bestName sur WhatsApp."
            }
        }
        return "WhatsApp n'a pas accepté l'appel. Vérifie qu'il est installé et autorisé à accéder aux contacts."
    }

    /** Rappelle le dernier numéro composé. */
    private fun redial(context: Context): String {
        if (!hasPerm(context, android.Manifest.permission.READ_CALL_LOG)) {
            return "Autorise l'accès au journal d'appels pour que je puisse rappeler."
        }
        if (!hasPerm(context, android.Manifest.permission.CALL_PHONE)) {
            return "Autorise l'appel téléphonique dans les paramètres de l'application."
        }
        var number: String? = null
        var name: String? = null
        context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME),
            "${android.provider.CallLog.Calls.TYPE} = ?",
            arrayOf(android.provider.CallLog.Calls.OUTGOING_TYPE.toString()),
            "${android.provider.CallLog.Calls.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) { number = c.getString(0); name = c.getString(1) }
        }
        val n = number ?: return "Je ne trouve aucun appel sortant dans ton journal."
        context.startActivity(
            Intent(Intent.ACTION_CALL).apply {
                data = Uri.fromParts("tel", n, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return "Je rappelle ${name ?: n}."
    }

    /** Raccroche l'appel en cours (Android 9+). */
    private fun endCall(context: Context): String {
        if (!hasPerm(context, android.Manifest.permission.ANSWER_PHONE_CALLS)) {
            return "Autorise la gestion des appels dans les paramètres de l'application pour que je puisse raccrocher."
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
        @Suppress("DEPRECATION")
        val ok = runCatching { telecom.endCall() }.getOrDefault(false)
        return if (ok) "J'ai raccroché." else "Je n'ai pas d'appel en cours à raccrocher."
    }
}
