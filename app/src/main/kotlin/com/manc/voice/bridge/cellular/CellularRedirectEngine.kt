package com.manc.voice.bridge.cellular

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.manc.voice.bridge.hidden.BridgeLog
import com.manc.voice.bridge.hidden.ShellContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream

/**
 * EXPERIMENTAL cellular uplink path. When the device reports PSTN call audio as
 * interceptable, the OS lets a CALL_AUDIO_INTERCEPTION holder (the shell) obtain
 * an uplink-injection AudioTrack: whatever we write becomes the audio the remote
 * party hears, replacing the handset mic. We feed it the cleaned voice.
 *
 * This only works while a call is in an interceptable mode and the platform
 * exposes telephony TX/RX. On devices where it does not, we fail loudly with a
 * JSON reason rather than pretending.
 */
class CellularRedirectEngine {
    private val TAG = "CellularRedirect"
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start(cleanPcm: InputStream, sampleRate: Int): String {
        val json = JSONObject()
        try {
            val am = ShellContext.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val interceptable = try {
                AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(am) as Boolean
            } catch (e: Throwable) { false }
            json.put("mode", am.mode)
            json.put("isPstnCallAudioInterceptable", interceptable)
            if (!interceptable) {
                json.put("started", false)
                json.put("reason", "Device does not expose interceptable PSTN call audio in the current mode. " +
                        "Start this during an active call, or this device routes cellular audio in the modem (no software access).")
                return json.toString()
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val m = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            val t = m.invoke(am, format) as AudioTrack
            track = t
            t.play()
            running = true
            thread = Thread {
                val buf = ByteArray(sampleRate / 25 * 2) // ~40 ms
                val shorts = ShortArray(buf.size / 2)
                try {
                    while (running) {
                        val read = cleanPcm.read(buf)
                        if (read <= 0) break
                        val n = read / 2
                        for (i in 0 until n) {
                            val lo = buf[i * 2].toInt() and 0xFF
                            val hi = buf[i * 2 + 1].toInt()
                            shorts[i] = ((hi shl 8) or lo).toShort()
                        }
                        t.write(shorts, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                } catch (e: Throwable) { BridgeLog.w(TAG, "uplink loop: ${BridgeLog.describe(e)}") }
            }.apply { isDaemon = true; start() }
            json.put("started", true)
            json.put("reason", "Uplink injection track active; remote party now hears the cleaned voice.")
        } catch (e: Throwable) {
            running = false
            json.put("started", false)
            json.put("reason", "Uplink injection failed: ${e.message ?: e.toString()}")
        }
        return json.toString()
    }

    /** Fan-out mode: open the uplink track only (no reader thread). */
    fun attach(sampleRate: Int): Boolean {
        return try {
            val am = ShellContext.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Only for a real phone-network call. The uplink-injection track registers a
            // second audio policy that matches every VOICE_COMMUNICATION recorder; in an
            // internet call that policy swallowed the bridge's own microphone capture, so
            // the bridge heard its own (silent) output and the far end heard nothing.
            if (am.mode != AudioManager.MODE_IN_CALL) return false
            val interceptable = try {
                AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(am) as Boolean
            } catch (e: Throwable) { false }
            if (!interceptable) return false
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val m = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            val t = m.invoke(am, format) as AudioTrack
            t.play()
            track = t
            true
        } catch (e: Throwable) { BridgeLog.w(TAG, "attach: ${BridgeLog.describe(e)}"); false }
    }

    fun write(shorts: ShortArray, n: Int) {
        val t = track ?: return
        try { t.write(shorts, 0, n, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Throwable) {}
    }

    val isAttached get() = track != null

    fun stop() {
        running = false
        try { thread?.join(200) } catch (_: Throwable) {}
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }
}
