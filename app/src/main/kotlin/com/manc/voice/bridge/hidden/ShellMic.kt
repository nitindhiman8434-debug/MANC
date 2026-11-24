package com.manc.voice.bridge.hidden

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** Captures the real microphone from the shell process (VOICE_COMMUNICATION). */
object ShellMic {
    private const val TAG = "ShellMic"

    fun create(sampleRate: Int): AudioRecord? {
        ShellContext.prepare()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = if (minBuf > 0) minBuf * 4 else sampleRate * 2
        return try {
            val b = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
            try {
                val setCtx = AudioRecord.Builder::class.java.getMethod("setContext", android.content.Context::class.java)
                setCtx.invoke(b, ShellContext.context)
            } catch (_: Throwable) {}
            b.build()
        } catch (e: Throwable) {
            Log.w(TAG, "mic builder failed, trying MIC source: ${e.message}")
            try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (e2: Throwable) { Log.e(TAG, "mic create failed: ${e2.message}"); null }
        }
    }
}
