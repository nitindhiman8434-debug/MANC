package com.manc.voice.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Detects calls from any app. On Android 12+ the global audio mode is the
 * universal signal: VoIP apps and telephony switch it to RINGTONE /
 * IN_CALL / IN_COMMUNICATION for the duration of a call and back to NORMAL
 * afterwards. Older releases fall back to telephony state (cellular only).
 */
class CallWatcher(private val ctx: Context, private val onState: (Boolean) -> Unit) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var phoneListener: PhoneStateListener? = null
    @Volatile var active = false; private set

    private fun modeIsCall(mode: Int) = mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
        mode == 5 /* MODE_CALL_REDIRECT */ || mode == 6 /* MODE_COMMUNICATION_REDIRECT */

    private fun publish(a: Boolean) { if (a != active) { active = a; onState(a) } }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { mode -> publish(modeIsCall(mode)) }
            modeListener = l
            try { am.addOnModeChangedListener(ctx.mainExecutor, l) } catch (e: Throwable) { Log.w(TAG, "mode listener: ${e.message}") }
            publish(modeIsCall(am.mode))
        } else {
            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        publish(state != TelephonyManager.CALL_STATE_IDLE)
                    }
                }
                phoneListener = l
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: Throwable) { Log.w(TAG, "telephony listener: ${e.message}") }
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeListener?.let { try { am.removeOnModeChangedListener(it) } catch (_: Throwable) {} }
        } else {
            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                phoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            } catch (_: Throwable) {}
        }
    }

    companion object { private const val TAG = "CallWatcher" }
}
