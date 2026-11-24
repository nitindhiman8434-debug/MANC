package com.manc.voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.manc.voice.data.Prefs

/** Brings the standby service back after a reboot or an app update, if protection is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val snap = Prefs.loadBlocking(context)
        if (snap.autoStart) {
            try { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) } catch (_: Exception) {}
        }
    }
}
