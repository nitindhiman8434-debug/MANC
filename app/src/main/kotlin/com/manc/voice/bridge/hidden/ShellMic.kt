package com.manc.voice.bridge.hidden

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures the real microphone from the shell process. Tries the requested rate
 * first, then common native rates; the caller reads `record.sampleRate` and
 * resamples to the engine rate if they differ.
 */
object ShellMic {
    private const val TAG = "ShellMic"

    fun create(preferredRate: Int): AudioRecord? {
        ShellContext.prepare()
        val rates = intArrayOf(preferredRate, 48000, 44100, 32000, 8000).distinct()
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        for (src in sources) for (rate in rates) {
            val rec = tryCreate(src, rate) ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                BridgeLog.i(TAG, "mic ok source=$src rate=$rate")
                return rec
            }
            try { rec.release() } catch (_: Throwable) {}
        }
        BridgeLog.e(TAG, "no microphone configuration worked")
        return null
    }

    private fun tryCreate(source: Int, rate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return try {
            val b = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
            try {
                val setCtx = AudioRecord.Builder::class.java.getMethod("setContext", android.content.Context::class.java)
                setCtx.invoke(b, ShellContext.context)
            } catch (_: Throwable) {}
            // Must NOT be a "privacy sensitive" capture. VOICE_COMMUNICATION is private by
            // default, and during a call Android then feeds audio only to the call app's
            // own recorder and silences every other private recorder, ours included
            // (AudioPolicyService::updateUidStates). A regular capture by a privileged
            // client (the shell holds CAPTURE_AUDIO_OUTPUT) is allowed to run alongside.
            // Seen on the first real call: the far end heard nothing at all.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try { b.setPrivacySensitive(false) } catch (e: Throwable) { BridgeLog.w(TAG, "setPrivacySensitive(false): ${BridgeLog.describe(e)}") }
            }
            b.build()
        } catch (e: Throwable) {
            BridgeLog.w(TAG, "mic create failed source=$source rate=$rate: ${BridgeLog.describe(e)}"); null
        }
    }
}
