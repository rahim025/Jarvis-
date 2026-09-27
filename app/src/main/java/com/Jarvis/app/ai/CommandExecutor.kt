package com.jarvis.app.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
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

            is JarvisAction.CallContact -> {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    "Autorise l'appel téléphonique dans les paramètres, puis réessaie."
                } else {
                    val number = findPhoneNumber(context, action.contact) ?: action.contact
                    val intent = Intent(Intent.ACTION_CALL).apply {
                        data = Uri.parse("tel:$number")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "J'appelle ${action.contact}."
                }
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
        }
    }

    /** Cherche le numéro de téléphone d'un contact à partir de son nom (utilisé pour les
     *  appels : Groq ne renvoie que le nom dit à voix haute, pas un numéro). Renvoie null
     *  si rien n'est trouvé (ou si la permission READ_CONTACTS n'est pas accordée) — dans
     *  ce cas on retente l'appel avec le texte reçu tel quel, au cas où c'était déjà un numéro. */
    private fun findPhoneNumber(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")

        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }
}
