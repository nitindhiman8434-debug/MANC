@file:JvmName("BridgeServer")

package com.manc.voice.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.os.Process
import com.manc.voice.audio.dsp.Resampler
import com.manc.voice.bridge.hidden.AudioReflect
import com.manc.voice.bridge.hidden.ShellContext
import com.manc.voice.bridge.hidden.ShellMic
import org.json.JSONObject

/**
 * Entry point launched as the shell user (UID 2000) via:
 *
 *   CLASSPATH=<app.apk> app_process /system/bin com.manc.voice.server.BridgeServer <appId>
 *
 * The shell UID is what makes the hidden audio-routing API reachable; the app
 * process itself never holds those permissions. This replaces the Shizuku
 * UserService: the in-app ADB client (see adb/AdbManager) starts this over the
 * device's own wireless-debugging ADB, so no separate Shizuku app is needed.
 *
 * Only framework + this app's pure-Kotlin DSP/reflection classes are touched,
 * never AndroidX, so the class loads cleanly under app_process.
 */
object BridgeServer {

    private const val TAG = "BridgeServer"

    @Volatile private var running = true
    @Volatile private var injection: AudioReflect.InjectionHandle? = null
    @Volatile private var micRecord: android.media.AudioRecord? = null
    private val threads = ArrayList<Thread>()

    @JvmStatic
    fun main(args: Array<String>) {
        val appId = args.getOrNull(0) ?: "com.manc.voice"
        try { Looper.prepareMainLooper() } catch (_: Throwable) {}
        log("start uid=${Process.myUid()} appId=$appId sdk=${android.os.Build.VERSION.SDK_INT}")
        ShellContext.prepare()

        // Control socket: one command, one JSON reply, kept open. Its close = shutdown.
        val ctl = try {
            LocalServerSocket(BridgeProtocol.socketName(appId, BridgeProtocol.CTL))
        } catch (e: Throwable) {
            log("ctl bind failed: ${e.message}"); return
        }

        val ctlThread = Thread {
            try {
                val client = ctl.accept()
                val reader = client.inputStream.bufferedReader()
                val writer = client.outputStream.bufferedWriter()
                val line = reader.readLine()
                val cmd = line?.let { BridgeProtocol.parse(it) }
                val reply = handle(appId, cmd)
                writer.write(reply.toString()); writer.write("\n"); writer.flush()
                // Block until the client goes away, then tear down.
                try { while (reader.readLine() != null) { /* ignore */ } } catch (_: Throwable) {}
            } catch (e: Throwable) {
                log("ctl loop: ${e.message}")
            } finally {
                shutdown()
                try { ctl.close() } catch (_: Throwable) {}
            }
        }
        ctlThread.isDaemon = false
        ctlThread.start()
        ctlThread.join()
        log("exit")
    }

    private fun handle(appId: String, cmd: BridgeProtocol.Command?): JSONObject {
        val j = JSONObject()
        if (cmd == null) return j.put("ok", false).put("reason", "bad command")
        when (cmd) {
            is BridgeProtocol.Command.Describe -> {
                j.put("ok", true).put("uid", Process.myUid())
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                return j
            }
            is BridgeProtocol.Command.Stop -> { shutdown(); return j.put("ok", true).put("stopped", true) }
            is BridgeProtocol.Command.Everywhere -> return startAudio(appId, null, AudioReflect.CALL_PRESETS, cmd.sampleRate)
            is BridgeProtocol.Command.Uid -> return startAudio(appId, cmd.uid, AudioReflect.CALL_PRESETS, cmd.sampleRate)
            is BridgeProtocol.Command.Preset -> return startAudio(appId, null, intArrayOf(cmd.preset), cmd.sampleRate)
        }
    }

    private fun startAudio(appId: String, uid: Int?, presets: IntArray, sampleRate: Int): JSONObject {
        val j = JSONObject()
        val handle = AudioReflect.registerInjector(uid, presets, sampleRate, forCall = false)
        if (handle == null) return j.put("ok", false).put("reason", "virtual mic registration failed")
        handle.start()
        injection = handle

        val rec = ShellMic.create(sampleRate)
        if (rec == null) {
            try { handle.release() } catch (_: Throwable) {}
            injection = null
            return j.put("ok", false).put("reason", "microphone capture failed")
        }
        micRecord = rec

        // MIC: server -> client, real mic resampled to sampleRate.
        val micSock = LocalServerSocket(BridgeProtocol.socketName(appId, BridgeProtocol.MIC))
        threads += Thread { pumpMic(micSock, rec, sampleRate) }.apply { isDaemon = true; start() }

        // INJ: client -> server, cleaned PCM into the injector.
        val injSock = LocalServerSocket(BridgeProtocol.socketName(appId, BridgeProtocol.INJ))
        threads += Thread { pumpInject(injSock, handle, sampleRate) }.apply { isDaemon = true; start() }

        rec.startRecording()
        return j.put("ok", true).put("apps", true).put("sampleRate", sampleRate)
    }

    private fun pumpMic(server: LocalServerSocket, rec: android.media.AudioRecord, outRate: Int) {
        var client: LocalSocket? = null
        try {
            client = server.accept()
            val out = client.outputStream
            val devRate = rec.sampleRate
            val shorts = ShortArray(devRate / 50)
            val fin = FloatArray(shorts.size)
            val fout = FloatArray(outRate / 50 + 16)
            val bytes = ByteArray(fout.size * 2)
            val resampler = if (devRate != outRate) Resampler(devRate, outRate) else null
            while (running) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val m: Int
                if (resampler != null) {
                    for (i in 0 until n) fin[i] = shorts[i] / 32768f
                    m = resampler.process(fin, n, fout)
                    for (i in 0 until m) {
                        var v = (fout[i] * 32767f).toInt()
                        if (v > 32767) v = 32767 else if (v < -32768) v = -32768
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
        } catch (e: Throwable) {
            log("mic pump end: ${e.message}")
        } finally {
            try { client?.close() } catch (_: Throwable) {}
            try { server.close() } catch (_: Throwable) {}
        }
    }

    private fun pumpInject(server: LocalServerSocket, handle: AudioReflect.InjectionHandle, sampleRate: Int) {
        var client: LocalSocket? = null
        try {
            client = server.accept()
            val input = client.inputStream
            val bytes = ByteArray(sampleRate / 25 * 2)
            val shorts = ShortArray(bytes.size / 2)
            while (running) {
                var filled = 0
                while (filled < bytes.size) {
                    val r = input.read(bytes, filled, bytes.size - filled)
                    if (r <= 0) { running = false; break }
                    filled += r
                }
                if (!running) break
                val n = filled / 2
                for (i in 0 until n) {
                    val lo = bytes[i * 2].toInt() and 0xFF
                    val hi = bytes[i * 2 + 1].toInt()
                    shorts[i] = ((hi shl 8) or lo).toShort()
                }
                handle.write(shorts, n)
            }
        } catch (e: Throwable) {
            log("inject pump end: ${e.message}")
        } finally {
            try { client?.close() } catch (_: Throwable) {}
            try { server.close() } catch (_: Throwable) {}
        }
    }

    private fun shutdown() {
        running = false
        try { micRecord?.stop() } catch (_: Throwable) {}
        try { micRecord?.release() } catch (_: Throwable) {}
        micRecord = null
        try { injection?.release() } catch (_: Throwable) {}
        injection = null
    }

    private fun log(m: String) {
        System.err.println("$TAG: $m")
    }
}
