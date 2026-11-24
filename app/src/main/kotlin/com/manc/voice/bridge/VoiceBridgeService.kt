package com.manc.voice.bridge

import android.media.AudioRecord
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.manc.voice.bridge.cellular.CallInterceptProbe
import com.manc.voice.bridge.cellular.CellularRedirectEngine
import com.manc.voice.bridge.hidden.AudioReflect
import com.manc.voice.bridge.hidden.ShellContext
import com.manc.voice.bridge.hidden.ShellMic
import com.manc.voice.audio.dsp.Resampler
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Runs inside the shell-user process (UID 2000): MANC's own helper
 * (server/BridgeServer) or, as a fallback, a Shizuku user service. Implements the
 * bridge the app talks to. The shell UID is what makes the hidden audio-routing
 * and call-interception APIs reachable; the app itself never holds them.
 */
class VoiceBridgeService : IVoiceBridge.Stub {

    @Suppress("unused")
    constructor() { ShellContext.prepare() }

    @Suppress("unused")
    constructor(ctx: android.content.Context) { ShellContext.prepare() }

    @Volatile private var micRecord: AudioRecord? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var micRunning = false

    @Volatile private var injection: AudioReflect.InjectionHandle? = null
    @Volatile private var injThread: Thread? = null
    @Volatile private var injRunning = false

    private val cellular = CellularRedirectEngine()

    // Binder transport state: the mic is read on demand, and cleaned PCM is fed to
    // the existing injector threads through a pipe that stays inside this process.
    @Volatile private var pullRecord: AudioRecord? = null
    private var pullRate = 16000
    private var pullShorts = ShortArray(0)
    private var pullIn = FloatArray(0)
    private var pullOut = FloatArray(0)
    private var pullResampler: Resampler? = null
    @Volatile private var sinkPfd: ParcelFileDescriptor? = null
    @Volatile private var sinkOut: FileOutputStream? = null

    override fun getProtocolVersion(): Int = 2

    override fun describeEnvironment(): String {
        val j = JSONObject()
        j.put("uid", Process.myUid())
        j.put("pid", Process.myPid())
        j.put("package", ShellContext.PACKAGE_NAME)
        j.put("sdkInt", Build.VERSION.SDK_INT)
        j.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        return j.toString()
    }

    override fun startMicCapture(sampleRate: Int): ParcelFileDescriptor? {
        stopMicCapture()
        val rec = ShellMic.create(sampleRate) ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        micRecord = rec
        micRunning = true
        rec.startRecording()
        micThread = Thread {
            val out = FileOutputStream(writeEnd.fileDescriptor)
            val devRate = rec.sampleRate
            val shorts = ShortArray(devRate / 50) // 20 ms at device rate
            val fin = FloatArray(shorts.size)
            val fout = FloatArray(sampleRate / 50 + 16)
            val bytes = ByteArray(fout.size * 2)
            val resampler = if (devRate != sampleRate) Resampler(devRate, sampleRate) else null
            try {
                while (micRunning) {
                    val n = rec.read(shorts, 0, shorts.size)
                    if (n <= 0) continue
                    val m: Int
                    if (resampler != null) {
                        for (i in 0 until n) fin[i] = shorts[i] / 32768f
                        m = resampler.process(fin, n, fout)
                        for (i in 0 until m) {
                            var v = (fout[i] * 32767f).toInt(); if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                            bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                    } else {
                        m = n
                        for (i in 0 until n) {
                            val s = shorts[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte(); bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                    }
                    out.write(bytes, 0, m * 2)
                }
            } catch (e: Throwable) { Log.w(TAG, "mic loop end: ${e.message}") }
            finally { try { out.close() } catch (_: Throwable) {}; try { writeEnd.close() } catch (_: Throwable) {} }
        }.apply { isDaemon = true; start() }
        return readEnd
    }

    override fun stopMicCapture() {
        micRunning = false
        try { micThread?.join(200) } catch (_: Throwable) {}
        micThread = null
        try { micRecord?.stop() } catch (_: Throwable) {}
        try { micRecord?.release() } catch (_: Throwable) {}
        micRecord = null
        val pr = pullRecord
        pullRecord = null
        try { pr?.stop() } catch (_: Throwable) {}
        try { pr?.release() } catch (_: Throwable) {}
    }

    override fun openMic(sampleRate: Int): Boolean {
        stopMicCapture()
        val rec = ShellMic.create(sampleRate) ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        val devRate = rec.sampleRate
        pullRate = sampleRate
        pullShorts = ShortArray(devRate / 50) // 20 ms at the device rate
        pullIn = FloatArray(pullShorts.size)
        pullOut = FloatArray(sampleRate / 50 + 16)
        pullResampler = if (devRate != sampleRate) Resampler(devRate, sampleRate) else null
        return try {
            rec.startRecording()
            pullRecord = rec
            true
        } catch (e: Throwable) {
            Log.w(TAG, "openMic start: ${e.message}")
            try { rec.release() } catch (_: Throwable) {}
            false
        }
    }

    override fun readMic(): ByteArray {
        val rec = pullRecord ?: return EMPTY
        return try {
            val n = rec.read(pullShorts, 0, pullShorts.size)
            if (n <= 0) return EMPTY
            val rs = pullResampler
            if (rs != null) {
                for (i in 0 until n) pullIn[i] = pullShorts[i] / 32768f
                val m = rs.process(pullIn, n, pullOut)
                val bytes = ByteArray(m * 2)
                for (i in 0 until m) {
                    var v = (pullOut[i] * 32767f).toInt(); if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                    bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                bytes
            } else {
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = pullShorts[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                bytes
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readMic: ${e.message}"); EMPTY
        }
    }

    override fun openSink(mode: Int, arg: Int, sampleRate: Int): String {
        return try {
            val pipe = ParcelFileDescriptor.createPipe() // both ends stay in this process
            val report: String = when (mode) {
                0 -> startCleanEverywhere(pipe[0], sampleRate)
                1 -> JSONObject().put("started", startInjectionForUid(arg, pipe[0], sampleRate)).toString()
                2 -> JSONObject().put("started", startInjectionForPreset(arg, pipe[0], sampleRate)).toString()
                3 -> startCellularRedirect(pipe[0], sampleRate)
                else -> JSONObject().put("started", false).put("reason", "unknown mode $mode").toString()
            }
            // the start* calls above closed any previous sink; publish the new one now
            sinkPfd = pipe[1]
            sinkOut = FileOutputStream(pipe[1].fileDescriptor)
            report
        } catch (e: Throwable) {
            JSONObject().put("started", false).put("reason", e.message ?: e.toString()).toString()
        }
    }

    override fun writeClean(pcm: ByteArray?) {
        if (pcm == null || pcm.isEmpty()) return
        try { sinkOut?.write(pcm) } catch (e: Throwable) { Log.w(TAG, "writeClean: ${e.message}") }
    }

    private fun closeSink() {
        val o = sinkOut; val p = sinkPfd
        sinkOut = null; sinkPfd = null
        try { o?.close() } catch (_: Throwable) {}
        try { p?.close() } catch (_: Throwable) {}
    }

    override fun startInjectionForUid(targetUid: Int, cleanPcmReadEnd: ParcelFileDescriptor, sampleRate: Int): Boolean =
        startInjection(targetUid, AudioReflect.CALL_PRESETS, cleanPcmReadEnd, sampleRate, forCall = false)

    override fun startInjectionForPreset(capturePreset: Int, cleanPcmReadEnd: ParcelFileDescriptor, sampleRate: Int): Boolean =
        startInjection(null, intArrayOf(capturePreset), cleanPcmReadEnd, sampleRate, forCall = false)

    private fun startInjection(uid: Int?, presets: IntArray, readEnd: ParcelFileDescriptor, sampleRate: Int, forCall: Boolean): Boolean {
        stopInjection()
        val handle = AudioReflect.registerInjector(uid, presets, sampleRate, forCall) ?: run {
            try { readEnd.close() } catch (_: Throwable) {}
            return false
        }
        handle.start()
        injection = handle
        injRunning = true
        injThread = Thread {
            val input = FileInputStream(readEnd.fileDescriptor)
            val bytes = ByteArray(sampleRate / 25 * 2) // 40 ms
            val shorts = ShortArray(bytes.size / 2)
            try {
                while (injRunning) {
                    val read = input.read(bytes)
                    if (read <= 0) break
                    val n = read / 2
                    for (i in 0 until n) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        shorts[i] = ((hi shl 8) or lo).toShort()
                    }
                    handle.write(shorts, n)
                }
            } catch (e: Throwable) { Log.w(TAG, "inj loop end: ${e.message}") }
            finally { try { input.close() } catch (_: Throwable) {}; try { readEnd.close() } catch (_: Throwable) {} }
        }.apply { isDaemon = true; start() }
        return true
    }

    override fun stopInjection() {
        injRunning = false
        closeSink() // unblocks an injector thread waiting on the in-process pipe
        try { cellular.stop() } catch (_: Throwable) {}
        try { injThread?.join(200) } catch (_: Throwable) {}
        injThread = null
        try { injection?.release() } catch (_: Throwable) {}
        injection = null
    }

    override fun startCleanEverywhere(cleanPcmReadEnd: ParcelFileDescriptor, sampleRate: Int): String {
        stopInjection()
        val report = JSONObject()
        val handle = AudioReflect.registerInjector(null, AudioReflect.CALL_PRESETS, sampleRate, forCall = false)
        val cellOk = cellular.attach(sampleRate)
        report.put("apps", handle != null)
        report.put("cellular", cellOk)
        if (handle == null && !cellOk) {
            try { cleanPcmReadEnd.close() } catch (_: Throwable) {}
            report.put("started", false)
            report.put("reason", "No sink available: virtual mic registration failed and cellular is not interceptable.")
            return report.toString()
        }
        handle?.start()
        injection = handle
        injRunning = true
        injThread = Thread {
            val input = FileInputStream(cleanPcmReadEnd.fileDescriptor)
            val bytes = ByteArray(sampleRate / 25 * 2) // 40 ms
            val shorts = ShortArray(bytes.size / 2)
            try {
                while (injRunning) {
                    val read = input.read(bytes)
                    if (read <= 0) break
                    val n = read / 2
                    for (i in 0 until n) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        shorts[i] = ((hi shl 8) or lo).toShort()
                    }
                    handle?.write(shorts, n)
                    if (cellular.isAttached) cellular.write(shorts, n)
                }
            } catch (e: Throwable) { Log.w(TAG, "fanout loop end: ${e.message}") }
            finally { try { input.close() } catch (_: Throwable) {}; try { cleanPcmReadEnd.close() } catch (_: Throwable) {} }
        }.apply { isDaemon = true; start() }
        report.put("started", true)
        return report.toString()
    }

    override fun probeCellular(): String = CallInterceptProbe.run()

    override fun startCellularRedirect(cleanPcmReadEnd: ParcelFileDescriptor, sampleRate: Int): String {
        return try {
            cellular.start(FileInputStream(cleanPcmReadEnd.fileDescriptor), sampleRate)
        } catch (e: Throwable) {
            JSONObject().put("started", false).put("reason", e.message ?: e.toString()).toString()
        }
    }

    override fun stopCellularRedirect() { cellular.stop() }

    companion object {
        private const val TAG = "VoiceBridgeService"
        private val EMPTY = ByteArray(0)
    }
}
