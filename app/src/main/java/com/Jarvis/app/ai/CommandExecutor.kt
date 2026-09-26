package com.jarvis.app.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.jarvis.app.JarvisAccessibilityService

/**
 * Reçoit une JarvisAction (décidée par Groq) et l'exécute réellement.
 * Deux façons d'agir :
 *  - Intents Android classiques (SMS, ouvrir une app par package)
 *  - JarvisAccessibilityService (cliquer sur l'écran, taper du texte)
 * Retourne un texte à dire à l'utilisateur en confirmation.
 */
object CommandExecutor {

    fun execute(context: Context, action: JarvisAction): String {
        return when (action) {
            is JarvisAction.Speak -> action.text

            is JarvisAction.OpenApp -> {
                val pm = context.packageManager
                val pkg = pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(action.appName, ignoreCase = true)
                }
                if (pkg != null) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        }
    }
}
