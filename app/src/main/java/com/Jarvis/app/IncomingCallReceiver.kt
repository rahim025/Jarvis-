package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Détecte la sonnerie d'un appel entrant pour déclencher le portier vocal de Jarvis
 * (JarvisForegroundService.onIncomingCall). Ne fait rien si la bulle en arrière-plan
 * n'est pas active : sans elle, pas de moteur vocal disponible pour poser la question
 * "tu es disponible ?", donc le téléphone sonne normalement comme d'habitude.
 */
class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return
        if (!JarvisForegroundService.isRunning) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        JarvisForegroundService.onIncomingCall(number)
    }
}
