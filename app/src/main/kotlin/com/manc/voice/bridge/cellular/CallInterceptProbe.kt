package com.manc.voice.bridge.cellular

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.manc.voice.bridge.hidden.ShellContext
import org.json.JSONObject

/**
 * Non-destructive check of whether this device lets a privileged app intercept
 * PSTN (cellular) call audio. Nothing is changed; we only read capabilities.
 *
 * The decisive OS gate is AudioManager.isPstnCallAudioInterceptable(), which is
 * true only when the platform exposes TELEPHONY_TX and TELEPHONY_RX audio
 * devices. Most consumer phones route cellular audio inside the modem/DSP and
 * do NOT expose these, so this usually reports false — which is the honest
 * answer about the cellular path on that device.
 */
object CallInterceptProbe {
    fun run(): String {
        val json = JSONObject()
        try {
            val ctx = ShellContext.context
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            json.put("shellPackage", ShellContext.PACKAGE_NAME)
            json.put("mode", am.mode)

            var txFound = false; var rxFound = false
            val devs = am.getDevices(AudioManager.GET_DEVICES_ALL)
            val names = org.json.JSONArray()
            for (d in devs) {
                val t = d.type
                if (t == AudioDeviceInfo.TYPE_TELEPHONY) { txFound = true; rxFound = true }
                names.put(t)
            }
            // reflect internal TELEPHONY_TX/RX detection via isPstnCallAudioInterceptable
            var interceptable: Boolean? = null
            try {
                val m = AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
                interceptable = m.invoke(am) as Boolean
            } catch (e: Throwable) {
                json.put("isPstnCallAudioInterceptableError", e.message ?: e.toString())
            }
            json.put("telephonyDeviceVisible", txFound || rxFound)
            json.put("isPstnCallAudioInterceptable", interceptable ?: JSONObject.NULL)
            json.put("deviceTypes", names)

            val verdict = when {
                interceptable == true -> "LIKELY_SUPPORTED"
                interceptable == false -> "NOT_SUPPORTED_ON_THIS_DEVICE"
                else -> "UNKNOWN"
            }
            json.put("verdict", verdict)
        } catch (e: Throwable) {
            json.put("error", e.message ?: e.toString())
            json.put("verdict", "PROBE_FAILED")
        }
        return json.toString()
    }
}
