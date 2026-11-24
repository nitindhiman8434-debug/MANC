package com.manc.voice.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.manc.voice.MainActivity
import com.manc.voice.R

/**
 * The pairing code is shown by a Settings dialog that closes as soon as the user
 * leaves it, so the code is typed into this notification's inline reply instead
 * (the same trick Shizuku uses). Needs the notification permission; without it
 * the in-app code field (split screen) still works.
 */
object PairingNotifier {
    private const val CHANNEL = "manc_pairing"
    private const val ID = 7
    const val KEY_CODE = "pairing_code"
    const val ACTION_CODE = "com.manc.voice.PAIRING_CODE"

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "One-time pairing", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return nm
    }

    private fun base(ctx: Context, text: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MANC Voice pairing")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(false)
    }

    /** "Type the 6 digits here" with an inline reply field. */
    fun askForCode(ctx: Context, retry: Boolean) {
        try {
            val reply = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, PairingReceiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            val action = Notification.Action.Builder(null, "Enter pairing code", reply).addRemoteInput(input).build()
            val text = if (retry) "That code did not work. Open “Pair device with pairing code” again and enter the new code here."
            else "Pairing dialog found. Tap “Enter pairing code” and type the 6 digits shown in Settings."
            manager(ctx).notify(ID, base(ctx, text).addAction(action).setAutoCancel(false).build())
        } catch (_: Throwable) { }
    }

    fun status(ctx: Context, text: String, done: Boolean = false) {
        try {
            manager(ctx).notify(ID, base(ctx, text).setAutoCancel(done).setTimeoutAfter(if (done) 15_000 else 0).build())
        } catch (_: Throwable) { }
    }

    fun cancel(ctx: Context) {
        try { manager(ctx).cancel(ID) } catch (_: Throwable) { }
    }
}

/** Receives the code typed into the pairing notification. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingNotifier.ACTION_CODE) return
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString().orEmpty()
        PairingNotifier.status(context, "Pairing…")
        AdbLauncher.pair(context, code)
    }
}
